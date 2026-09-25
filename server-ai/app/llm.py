"""回答生成：三种模式，按可用条件降级，任何时候都不返回空。

| 模式 | 触发条件 | 特点 |
|---|---|---|
| `llm` | 配了 LLM_API_KEY 且未开 DEMO_MODE | 真正的流式生成，答案用自己的话组织 |
| `cache` | DEMO_MODE=true，或问题命中预生成缓存 | 回放预先写好的答案，逐字吐出以保持流式体验 |
| `extractive` | 没有可用模型，且问题不在缓存里 | 直接摘录检索到的资料原文，不润色、不编造 |

`extractive` 这一档是刻意保留的：没有它，没配 key 的环境下系统就只会说"AI 不可用"，
演示时等于白屏；有了它，系统仍然能给出**有出处、可核对**的回答，只是措辞不好看。
另外检索得分低于阈值时统一走 `no_answer`，明确说"知识库里没有相关记载"——
宁可说不知道，也不能让模型拿别处的知识编一个像样的答案出来。
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, AsyncIterator, Sequence

import httpx

from .aliases import expand
from .store import Hit

SYSTEM_PROMPT = """你是「汉游智脑」的文旅知识助手，服务对象是准备到陕西汉中旅游的游客和当地文旅管理者。

回答规则：
1. 只依据【资料】里的内容回答。资料里没有的，不要用你自己的知识补充，也不要推测。
2. 资料不足以回答时，直接说"知识库里没有相关记载"，再说清楚你能回答什么。
3. 不要编造价格、开放时间、电话、里程等具体数字，除非资料里明确写了。
4. 用简体中文，简洁分点，用自己的话组织，不要整段照抄资料。
5. 回答里不要写"根据资料1"这类引用标记，来源由系统单独展示。"""


def build_messages(question: str, hits: Sequence[Hit]) -> list[dict[str, str]]:
    blocks = []
    for i, hit in enumerate(hits, 1):
        blocks.append(f"【资料{i}】{hit.metadata.get('title', '')}\n{hit.text}")
    context = "\n\n".join(blocks)
    return [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": f"【资料】\n{context}\n\n【问题】\n{question}"},
    ]


# ----------------------------------------------------------------------
# 预生成缓存
# ----------------------------------------------------------------------


def normalize_question(text: str) -> str:
    """缓存键的归一化：去空白与标点、统一别名、去掉疑问语气词。

    不做这一步的话，"朱鹮是哪年发现的？"和"朱鹮是哪年发现的"会算两个不同的问题。
    """
    text = expand(text).lower()
    # 中文引号写成 \u 转义而不是字面字符：字面写会和字符串定界符混在一起，
    # 被 Python 解析成三段字面量的隐式拼接，raw 前缀只作用于第一段，
    # "\[" 就成了非法转义（SyntaxWarning），引号本身也漏掉了。
    # re 模块自己认识 \uXXXX，所以转义写法在正则里同样有效。
    text = re.sub(r'[\s，。？！、；：\u201c\u201d\u2018\u2019（）()【】\[\]?!.,;:~·\-—_]+', "", text)
    for filler in ("请问", "我想知道", "麻烦问一下", "一下"):
        text = text.replace(filler, "")
    return text


@dataclass
class CachedAnswer:
    answer: str
    doc_ids: list[str]
    mode: str = "cache"


class AnswerCache:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.meta: dict[str, Any] = {}
        self._index: dict[str, CachedAnswer] = {}
        self._load()

    def _load(self) -> None:
        if not self.path.is_file():
            return
        payload = json.loads(self.path.read_text(encoding="utf-8"))
        self.meta = payload.get("meta", {})
        for entry in payload.get("answers", []):
            cached = CachedAnswer(
                answer=entry["answer"].strip(),
                doc_ids=list(entry.get("sources", [])),
            )
            # 同一段答案可以用多种问法命中
            for question in entry.get("questions", []):
                self._index[normalize_question(question)] = cached

    def __len__(self) -> int:
        return len(self._index)

    def lookup(self, question: str) -> CachedAnswer | None:
        return self._index.get(normalize_question(question))


# ----------------------------------------------------------------------
# 生成
# ----------------------------------------------------------------------


async def stream_llm(settings, messages: list[dict[str, str]]) -> AsyncIterator[str]:
    """调 OpenAI 兼容接口，逐段吐出增量文本。"""
    payload = {
        "model": settings.llm_model,
        "messages": messages,
        "stream": True,
        "temperature": 0.3,
    }
    headers = {
        "Authorization": f"Bearer {settings.llm_api_key}",
        "Content-Type": "application/json",
    }

    timeout = httpx.Timeout(settings.llm_timeout_s, connect=5.0)
    async with httpx.AsyncClient(timeout=timeout) as client:
        async with client.stream(
            "POST", f"{settings.llm_base_url}/chat/completions", json=payload, headers=headers
        ) as response:
            response.raise_for_status()
            async for line in response.aiter_lines():
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if not data or data == "[DONE]":
                    continue
                try:
                    delta = json.loads(data)["choices"][0]["delta"]
                except (json.JSONDecodeError, KeyError, IndexError):
                    continue
                piece = delta.get("content")
                if piece:
                    yield piece


def extractive_answer(question: str, hits: Sequence[Hit]) -> str:
    """没有可用模型时的兜底：摘录资料原文，不做润色。

    明确标注是摘录而不是生成——把"这是原文"和"这是模型组织过的话"分开，
    读者才知道该信到什么程度。
    """
    if not hits:
        return "知识库里没有与这个问题相关的资料。"

    lines = [
        "当前未配置大模型（或处于离线演示模式），下面是知识库中与你的问题最相关的原文摘录：",
        "",
    ]
    for hit in hits[:3]:
        title = hit.metadata.get("title", "")
        # 去掉 Markdown 标题记号，摘录里不需要
        body = re.sub(r"^#+\s*", "", hit.text).strip()
        body = re.sub(r"\s+", " ", body)
        excerpt = body[:160] + ("…" if len(body) > 160 else "")
        lines.append(f"· {title}：{excerpt}")
    lines += ["", "以上内容直接摘自知识库，未经整理。配置 LLM_API_KEY 后可获得归纳后的回答。"]
    return "\n".join(lines)


def chunk_for_stream(text: str, size: int = 12) -> list[str]:
    """把整段答案切成小块模拟流式。

    缓存回放和摘录兜底都是一次成文的，但前端只实现了一套流式渲染。
    在这里切成小块，三种模式对前端是同一个协议——少一条分支就少一类只在
    某种模式下才出现的 bug。
    """
    return [text[i : i + size] for i in range(0, len(text), size)]
