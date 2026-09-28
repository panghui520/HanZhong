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
| `search_nearby` / `search_poi` / `plan_itinerary` | 新增 `SYSTEM_PROMPT_TOOLS` |

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

## 行程上下文（第二阶段）

`stream(question, context)` 的 `context` 是**服务端**从 `trip_context` 读出来的
本次行程上下文（目的地、已选住处及坐标、预订状态），由 Java 侧放进请求体。
前端传不了它 —— 否则任何人都能伪造"我住在某某酒店"去影响检索结果。

它解决的是"助手记不住"这件事：用户上一轮在卡片上点了"选择酒店"，
这一轮问"这附近有什么好吃的"，`search_nearby` 会以**那家酒店的坐标**为中心
（见 `tools._search_nearby` 的三级回退）。没有它，用户就得每次重述自己住哪，
而"附近"这个词在每一轮对话里都是悬空的。

`context` 为 `None` 或空字典时，整条链路的行为与第一阶段**完全一致** ——
未登录用户走的就是这条路。
"""

from __future__ import annotations

import json
import logging
import re
import time
from typing import Any, AsyncIterator, Mapping

from . import places, tools
from .itinerary import ItineraryPlanner
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
        # 地名解析器（`places.py`）：把用户说的地名变成可信的搜索中心坐标。
        # 在构造时建一次、而不是每次提问新建 —— 本地地名表是只读的，
        # 每次提问都重读一遍文件、重建索引是白费。它只在 `search_nearby`
        # 解析中心点时用到，其余工具不看。
        self.places = places.create_resolver(settings, amap)
        # 行程规划器（`itinerary.py`）：同样只读一次数据包（构造时要读
        # `pois.json` 并算出城市中心落在哪个区县），之后每次规划都复用。
        # 它**不需要高德**，所以高德没配时它照样可用。
        self.planner = ItineraryPlanner(settings.city_dir, settings.city_center)

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

        **行程那一组不带条件**：`plan_itinerary` 读的是数据包，不需要高德，
        也不需要模型（离线时由工具自己排、只少了模型润色）。给它加个
        `if amap_ok` 会让"没配高德"的机器白白藏起一个能用的功能。
        """
        amap_ok = bool(self.amap and self.amap.enabled)
        groups: list[dict[str, Any]] = []

        if amap_ok:
            groups.append(
                {
                    # ★ 游客端分组标题：不说"调用高德地图"、不写 POI ——
                    #   那是我们的技术栈口径。说游客能得到的：找到真实的地方。
                    "title": "找真实的地方（酒店、餐厅、景点）",
                    "items": [
                        "汉中高铁站附近推荐酒店",
                        # 举一个**区县**的例子：汉中有 2 区 9 县，用户不知道
                        # 系统支持按区县查时，只会反复问市区。这一条是给能力做广告的。
                        "宁强县有什么酒店",
                        "汉中市区附近有什么好吃的",
                        "留坝县附近有什么景点",
                    ],
                }
            )

        groups.append(
            {
                # ★ 同上：不写"读本地数据包、不需要联网"，只说"按天数排一份行程"。
                "title": "排一份行程（按天数自动安排）",
                "items": [
                    "帮我规划汉中两日游",
                    "汉中三天怎么玩",
                    "带小孩去汉中玩两天怎么安排",
                ],
            }
        )

        groups.append(
            {
                # ★ 这是游客端「试试这样问」的分组标题，会直接显示给游客。
                #   原来写的是"查本地知识库（有出处的公开资料）"——"知识库"是我们的实现口径。
                #   改成说内容本身：汉中的来历与风物。
                "title": "汉中的来历与风物（都有出处）",
                "items": [
                    "汉中仙毫是什么茶",
                    "汉中天坑群在哪里",
                    "汉中为什么被称为汉家发祥地",
                ],
            }
        )
        return groups

    # ---- 主流程 ----

    async def stream(
        self, question: str, context: Mapping[str, Any] | None = None
    ) -> AsyncIterator[dict[str, Any]]:
        started = time.monotonic()
        question = (question or "").strip()
        if not question:
            yield {"type": "error", "message": "问题不能为空"}
            return

        # 上下文只认字典。传了别的东西（None / 字符串 / 数组）一律当"没有上下文"，
        # 而不是抛异常：它是增强项，坏了不该让整轮对话失败。
        context = context if isinstance(context, Mapping) else None
        hotel = self._hotel_of(context)

        amap_ok = bool(self.amap and self.amap.enabled)

        yield {
            "type": "meta",
            "mode": "llm" if self.settings.llm_enabled else "extractive",
            "generator": self.settings.llm_model if self.settings.llm_enabled else "extractive",
            "city": self.settings.city_name,
            "amap": amap_ok,
            # **与 /ai/health 用同一处定义**（`tools.available_tools`）。
            # 早先这里写的是"高德没配就只报 knowledge_search"，加了
            # `plan_itinerary`（不需要高德）之后那句话就变成假的了 ——
            # 前端会据此把行程示例问题藏起来，而它其实可用。
            "tools": [spec.name for spec in tools.available_tools(amap_ok)],
        }

        # 把"这一轮到底有没有记忆"写进日志。前端看 /api/trips/current 就知道，
        # 但排查"为什么助手没记住"时，需要一条能证明上下文到达了这一侧的记录 ——
        # 否则只能靠猜"是没传过来，还是传了没用上"。
        if context:
            logger.info(
                "[Agent] 行程上下文 目的地=%s 已选酒店=%s",
                context.get("destination"),
                hotel.get("name") if hotel else "（无）",
            )
        else:
            logger.info("[Agent] 本次没有行程上下文（未登录或未选择住处）")

        # 1) 决定用哪个工具
        if self.settings.llm_enabled:
            try:
                name, args, raw = await self._decide(question, context)
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
                "label": self._label(name, args, context),
            }
            tool_started = time.monotonic()
            result = tools.execute(
                name,
                args,
                store=self.store,
                amap=self.amap,
                resolver=self.places,
                planner=self.planner,
                top_k=self.settings.top_k,
                city_name=self.settings.city_name,
                city_center=self.settings.city_center,
                hotel=hotel,
            )
            yield {
                "type": "tool",
                "name": name,
                "status": "done" if result.ok else "error",
                "label": result.label or self._label(name, args, context),
                "count": result.count,
                "elapsed_ms": int((time.monotonic() - tool_started) * 1000),
                "error": result.error,
            }
            # 卡片直接从工具结果来，**不经过模型**（见 tools.py 的模块 docstring）
            if result.cards:
                yield {"type": "cards", "kind": result.kind, "items": result.cards}

        # 3) 组织回答
        try:
            async for piece in self._generate(question, name, result, context):
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

    @staticmethod
    def _hotel_of(context: Mapping[str, Any] | None) -> dict[str, Any] | None:
        """从上下文里取出已选住处。没选或结构不对时返回 None。"""
        if not context:
            return None
        hotel = context.get("selected_hotel")
        return dict(hotel) if isinstance(hotel, Mapping) else None

    def _label(
        self, name: str, args: dict[str, Any], context: Mapping[str, Any] | None = None
    ) -> str:
        """工具运行中显示的那句话。

        这一步**发生在工具执行之前**，所以中心点还没解析出来。这里只能按
        "用户点名了中心 / 有已选酒店 / 都没有"三种情况给一个诚实的预告，
        真正的中心名由 `tools._search_nearby` 解析后放进 tool 事件的 label。
        """
        if name == tools.TOOL_NEARBY:
            center = str(args.get("center") or "").strip()
            if center:
                return f"正在搜索「{center}」附近…"
            hotel = self._hotel_of(context)
            hotel_name = str((hotel or {}).get("name") or "").strip()
            if hotel_name:
                return f"正在搜索您选择的「{hotel_name}」附近…"
            return f"正在搜索{self.settings.city_name}市区附近…"
        if name == tools.TOOL_POI:
            return f"正在搜索「{args.get('keywords') or ''}」…"
        if name == tools.TOOL_KNOWLEDGE:
            # 与 tools.py 里 _knowledge_search 的 label 保持同一句话：
            # 这个 label 会原样出现在游客端的工具轨迹上，不能写实现口径（"本地知识库"）。
            return "正在查资料…"
        if name == tools.TOOL_PLAN:
            # 天数可能来自模型、也可能没给（工具会按 2 天兜底）。这里不猜，
            # 给不出天数就只说"正在排行程"，不编一个"2 天"进 label ——
            # 万一工具按别的天数排出来，label 和结果就对不上了。
            try:
                days = int(args.get("days") or 0)
            except (TypeError, ValueError):
                days = 0
            span = f" {days} 天" if days > 0 else ""
            return f"正在规划{self.settings.city_name}{span}行程…"
        return "正在思考…"

    async def _decide(
        self, question: str, context: Mapping[str, Any] | None = None
    ) -> tuple[str, dict[str, Any], str]:
        menu = tools.tool_menu(
            bool(self.amap and self.amap.enabled), self.settings.city_name
        )
        messages = build_router_messages(
            question, menu, city=self.settings.city_name, context=context
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
        self,
        question: str,
        name: str,
        result: tools.ToolResult,
        context: Mapping[str, Any] | None = None,
    ) -> AsyncIterator[str]:
        if self.settings.llm_enabled:
            if name == tools.TOOL_NONE:
                messages = build_messages(
                    question, [], ROUTE_GENERAL, city=self.settings.city_name
                )
            elif name == tools.TOOL_KNOWLEDGE and result.hits:
                # 走 M3 那条路：分流规则、提示词、来源策略全部复用。
                # **刻意不传上下文** —— 这条路上的问题是"汉中仙毫是什么茶"
                # 这类事实问题，答案与用户住哪无关（详见 llm.build_tool_messages）。
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
                    question,
                    result.text,
                    city=self.settings.city_name,
                    note=note,
                    context=context,
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
        """覆盖范围的一句话说明。给用户看，也塞进提示词。"""
        by_type_docs = self.store.manifest().get("by_type_docs", {})
        return (
            f"覆盖{self.settings.city_name}的地理气候、历史文化、生态与物产等公开资料，"
            f"以及 {by_type_docs.get('poi', 0)} 处好去处、"
            f"{by_type_docs.get('experience', 0)} 项乡村体验、"
            f"{by_type_docs.get('product', 0)} 款乡村好物。"
        )

    def _offline_text(self, question: str) -> str:
        """没有模型、也没有检索命中时的兜底回答。

        ★ 面向游客（2026-09-28）：这里原来写的是"当前处于离线演示模式（未配置大模型），
          我无法调用高德地图查询真实地点…配置 LLM_API_KEY 与 AMAP_KEY 之后…" ——
          把环境变量名和实现口径写进了**给游客的回答**里。改成说人话，
          同时保留"能力受限"这个如实告知（不假装什么都能答）。
          配置指引留在 .env 与 README，不该出现在答案里。
        """
        return (
            "这一问暂时没找到可核对的资料。当前只能查到本地整理过的内容，"
            "没法实时搜索周边的酒店、餐厅与景点。\n\n"
            f"{self._scope_text()}\n\n"
            "换个说法，或者问问上面这些方向，我再找找。"
        )
