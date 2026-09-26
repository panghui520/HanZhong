"""AI 旅游助手 Agent：**决定用哪个工具 → 执行 → 用工具结果组织回答**（M4 第一阶段）。

## 与 M3 的关系

M3 是「知识库增强的助手」：先判定这一问要不要用知识库，再决定怎么答。
M4 是它的自然延伸 —— **知识库之外再多几路数据源**（高德地图），
而且由模型自己决定这一问该走哪一路。

所以这里**不重写任何提示词**：

| 决策 | 复用的 M3 提示词 |
|---|---|
| `none`（与汉中无关） | `SYSTEM_PROMPT_GENERAL` |
| `knowledge_search` | `SYSTEM_PROMPT_RAG` / `_CONSTRAINED`（按 `qa._route` 分流） |
| `search_nearby` / `search_poi` | 新增 `SYSTEM_PROMPT_TOOLS` |

## 为什么是「JSON 路由器」而不是原生 function calling

OpenAI 兼容接口的 `tools` 参数当然能用，但这里刻意不用，理由有三条：

1. **我们要的决定很短，不需要流式。** 原生 tool calling 在 `stream=true` 时要把
   `tool_calls` 的增量按 `index` 累加，各家实现的细节并不一致；而我们这一步
   本来就该是一次短的非流式调用（`temperature=0`），拿一个完整 JSON 最省事。
2. **可移植。** 项目的 `.env` 允许换成通义 / Kimi / 本地 Ollama 等任一兼容端点。
   自己定一个 JSON 协议，换模型不用重新验证 tool_calls 的行为差异。
3. **可解释。** 模型决定调用什么工具、参数是什么，会**原样出现在日志里**
   （`[Agent] 决策 …`）。答辩被问「你怎么知道它调了工具」时，答案是那一行日志，
   而不是「我猜它调了」。

代价是我们自己负责解析模型的输出，所以 `_parse_decision` 写得比较宽容
（去代码围栏、取第一个 `{` 到最后一个 `}`）。**解析失败的处理分两种**，
这一点容易记混，所以写清楚：

- **模型说了话但格式坏**（没有 JSON、JSON 不合法）→ 抛 `ValueError`，
  由 `stream()` 捕获后退回 `knowledge_search`。理由：模型是活的，
  它至少说明这一问值得回答，而知识库检索是当下最可能给出依据的一条路。
- **格式没问题但工具名不认识**（模型编了个 `search_hotel`）→ 归一到 `none`。
  理由：这不是故障，是它跑偏了；用通用提示词正常作答，比报错好。

## 不走预生成缓存

M3 的 `AnswerCache` 在这里用不上：Agent 的回答依赖**实时工具数据**
（此刻的高德结果），把某一次的结果缓存下来回放，等于给用户看一份过期的酒店列表。
断网时走的是另一条路：规则兜底到知识库摘录，见 `_fallback_decision`。
"""

from __future__ import annotations

import json
import logging
import re
import time
from typing import Any, AsyncIterator

from . import tools
from .llm import (
    ROUTE_GENERAL,
    build_messages,
    build_router_messages,
    build_tool_messages,
    complete_llm,
    stream_llm,
)
# `_route` 与 `_source_payload` 是 qa 模块里的内部函数。这里直接引用而不是
# 复制一份：Agent 与 M3 必须**用同一套分流规则**，复制一份迟早会漂移
# （改了 M3 的判定顺序、忘了改 Agent，就会出现两个页面给出不同答案）。
from .qa import _route
from .store import KbStore

logger = logging.getLogger("hanyou.ai.agent")

# 已知工具名。模型编出来的名字不在这张表里，会被降级成 none。
_KNOWN_TOOLS = {spec.name for spec in tools.TOOL_SPECS} | {tools.TOOL_NONE}


def _parse_decision(raw: str) -> tuple[str, dict[str, Any]]:
    """解析调度器的输出。宽容到「能救就救」，救不回来抛 ValueError。

    真实会遇到的三种脏输出，都在这里处理：
    - 包了 ```json 代码围栏（尽管提示词说了不要）
    - 前面带一句「好的，我判断应该调用…」
    - 只输出 `{"tool": "search_nearby"}`（没有 args）
    """
    text = (raw or "").strip()
    if not text:
        raise ValueError("调度器返回空内容")

    if text.startswith("```"):
        text = re.sub(r"^```[a-zA-Z]*\s*", "", text)
        text = re.sub(r"```\s*$", "", text).strip()

    start = text.find("{")
    end = text.rfind("}")
    if start == -1 or end <= start:
        raise ValueError(f"调度器没有输出 JSON：{text[:120]}")

    payload = json.loads(text[start : end + 1])
    if not isinstance(payload, dict):
        raise ValueError("调度器输出的不是对象")

    name = str(payload.get("tool") or tools.TOOL_NONE).strip()
    args = payload.get("args")
    args = args if isinstance(args, dict) else {}

    if name not in _KNOWN_TOOLS:
        # 模型编了个不存在的工具名。这不是故障，是它跑偏了 —— 当成 none，
        # 由生成阶段用通用提示词正常回答，比报错好。
        logger.warning("[Agent] 调度器给出了未知工具 %r，按 none 处理", name)
        return tools.TOOL_NONE, {}

    return name, args


class AgentService:
    def __init__(self, settings, store: KbStore, amap) -> None:
        self.settings = settings
        self.store = store
        self.amap = amap

    # ---- 给 /ai/health 用 ----

    def status(self) -> dict[str, Any]:
        """给 /ai/health 用。工具列表要**与实际可用的一致** ——
        高德没配时不能把 search_nearby 列出来，否则前端会显示一个点了没用的能力。"""
        amap_ok = bool(self.amap and self.amap.enabled)
        return {
            "amap_configured": amap_ok,
            "tools": [spec.name for spec in tools.available_tools(amap_ok)],
            "samples": self.samples(),
        }

    def samples(self) -> list[dict[str, Any]]:
        """给前端的示例问题，**按当前可用的工具生成**。

        为什么不写在前端：高德没配时，"汉中高铁站附近推荐酒店"这类问题
        点了必然失败（`search_nearby` 不可用）。让后端按实际能力给例子，
        前端不需要自己判断"现在能不能演示地图"——那种判断散在前端，
        迟早有一处漏了，变成一个点不出结果的引导按钮。
        """
        amap_ok = bool(self.amap and self.amap.enabled)
        groups: list[dict[str, Any]] = []

        if amap_ok:
            groups.append(
                {
                    "title": "调用高德地图（真实 POI）",
                    "items": [
                        "汉中高铁站附近推荐酒店",
                        "汉中市区附近有什么好吃的",
                        "汉中火车站附近有哪些景点",
                    ],
                }
            )

        groups.append(
            {
                "title": "查本地知识库（有出处的公开资料）",
                "items": [
                    "汉中仙毫是什么茶",
                    "汉中天坑群在哪里",
                    "汉中为什么被称为汉家发祥地",
                ],
            }
        )
        return groups

    # ---- 主流程 ----

    async def stream(self, question: str) -> AsyncIterator[dict[str, Any]]:
        started = time.monotonic()
        question = (question or "").strip()
        if not question:
            yield {"type": "error", "message": "问题不能为空"}
            return

        amap_ok = bool(self.amap and self.amap.enabled)

        yield {
            "type": "meta",
            "mode": "llm" if self.settings.llm_enabled else "extractive",
            "generator": self.settings.llm_model if self.settings.llm_enabled else "extractive",
            "city": self.settings.city_name,
            "amap": amap_ok,
            "tools": [spec.name for spec in tools.TOOL_SPECS] if amap_ok else [tools.TOOL_KNOWLEDGE],
        }

        # 1) 决定用哪个工具
        if self.settings.llm_enabled:
            try:
                name, args, raw = await self._decide(question)
                logger.info("[Agent] 决策 tool=%s args=%s raw=%s", name, args, raw[:200])
            except Exception as exc:  # noqa: BLE001 - 决策失败不该让整轮失败
                logger.warning("[Agent] 决策失败，退回知识库：%s", exc)
                name, args = tools.TOOL_KNOWLEDGE, {"query": question}
        else:
            name, args = self._fallback_decision(question)

        # 2) 执行工具（none 不执行）
        result = tools.ToolResult(name=tools.TOOL_NONE, kind="none")
        if name != tools.TOOL_NONE:
            yield {
                "type": "tool",
                "name": name,
                "status": "running",
                "label": self._label(name, args),
            }
            tool_started = time.monotonic()
            result = tools.execute(
                name,
                args,
                store=self.store,
                amap=self.amap,
                top_k=self.settings.top_k,
                city_name=self.settings.city_name,
                city_center=self.settings.city_center,
            )
            yield {
                "type": "tool",
                "name": name,
                "status": "done" if result.ok else "error",
                "label": result.label or self._label(name, args),
                "count": result.count,
                "elapsed_ms": int((time.monotonic() - tool_started) * 1000),
                "error": result.error,
            }
            # 卡片直接从工具结果来，**不经过模型**（见 tools.py 的模块 docstring）
            if result.cards:
                yield {"type": "cards", "kind": result.kind, "items": result.cards}

        # 3) 组织回答
        try:
            async for piece in self._generate(question, name, result):
                yield {"type": "delta", "text": piece}
        except Exception as exc:  # noqa: BLE001 - 模型失败要让前端知道，而不是静默截断
            yield {"type": "error", "message": f"生成失败：{exc}"}
            return

        yield {
            "type": "done",
            "mode": "llm" if self.settings.llm_enabled else "extractive",
            "tool": name,
            "elapsed_ms": int((time.monotonic() - started) * 1000),
        }

    # ---- 内部 ----

    def _label(self, name: str, args: dict[str, Any]) -> str:
        if name == tools.TOOL_NEARBY:
            center = str(args.get("center") or "").strip()
            return f"正在搜索「{center}」附近…" if center else f"正在搜索{self.settings.city_name}市区附近…"
        if name == tools.TOOL_POI:
            return f"正在搜索「{args.get('keywords') or ''}」…"
        if name == tools.TOOL_KNOWLEDGE:
            return "正在检索本地知识库…"
        return "正在思考…"

    async def _decide(self, question: str) -> tuple[str, dict[str, Any], str]:
        menu = tools.tool_menu(
            bool(self.amap and self.amap.enabled), self.settings.city_name
        )
        messages = build_router_messages(
            question, menu, city=self.settings.city_name
        )
        raw = await complete_llm(self.settings, messages, max_tokens=200)
        name, args = _parse_decision(raw)
        return name, args, raw

    def _fallback_decision(self, question: str) -> tuple[str, dict[str, Any]]:
        """没有模型时的规则兜底：只能查知识库。

        为什么还留着这条路：离线演示是硬需求（见 M3 的降级链）。没有它，
        断网时这一页就是白屏；有了它，知识库覆盖的问题仍然答得出来（摘录原文），
        只是**查不了高德**——那也需要网络，而且需要模型来决定调不调。
        """
        return tools.TOOL_KNOWLEDGE, {"query": question}

    async def _generate(
        self, question: str, name: str, result: tools.ToolResult
    ) -> AsyncIterator[str]:
        if self.settings.llm_enabled:
            if name == tools.TOOL_NONE:
                messages = build_messages(
                    question, [], ROUTE_GENERAL, city=self.settings.city_name
                )
            elif name == tools.TOOL_KNOWLEDGE and result.hits:
                # 走 M3 那条路：分流规则、提示词、来源策略全部复用
                gaps = self.store.topic_gaps(question)
                route = _route(question, gaps, self.settings.city_name)
                messages = build_messages(
                    question,
                    result.hits,
                    route,
                    city=self.settings.city_name,
                    gaps=gaps,
                    scope=self._scope_text(),
                )
            else:
                note = ""
                if result.error:
                    note = (
                        f"本次工具没有取到数据（{result.error}）。"
                        "请如实告诉用户这次没查到，不要用别的数据凑数。"
                    )
                elif result.count == 0:
                    note = "本次工具没有返回任何结果。请如实说明，不要用别的数据凑数。"
                messages = build_tool_messages(
                    question, result.text, city=self.settings.city_name, note=note
                )
            async for piece in stream_llm(self.settings, messages):
                yield piece
            return

        # 离线：只有知识库摘录这一条路
        from .llm import chunk_for_stream, extractive_answer

        if result.hits:
            text = extractive_answer(question, result.hits)
        else:
            text = self._offline_text(question)
        for piece in chunk_for_stream(text):
            yield piece

    def _scope_text(self) -> str:
        by_type_docs = self.store.manifest().get("by_type_docs", {})
        return (
            f"当前知识库覆盖{self.settings.city_name}的地理气候、历史文化、生态与物产等公开资料，"
            f"以及 {by_type_docs.get('poi', 0)} 个资源点、"
            f"{by_type_docs.get('experience', 0)} 项乡村体验、"
            f"{by_type_docs.get('product', 0)} 款乡村产品。"
        )

    def _offline_text(self, question: str) -> str:
        return (
            "当前处于离线演示模式（未配置大模型），我无法调用高德地图查询真实地点，"
            "也回答不了知识库之外的问题。\n\n"
            f"{self._scope_text()}\n\n"
            "配置 LLM_API_KEY 与 AMAP_KEY 之后，我可以搜索真实酒店、餐厅与景点，"
            "并把结果整理成可核对的推荐。"
        )
