"""回答生成：**三条链路**，按可用条件降级，任何时候都不返回空。

三条链路决定"模型被允许使用哪一部分知识"，这是 M3 从「知识库回答器」改成
「知识库增强的助手」的关键（见 docs/验收记录-M3.md 十）：

| 链路 | 什么时候用 | 给模型的上下文 |
|---|---|---|
| `rag` | 问题在知识库覆盖范围内 | 检索到的【资料】+ "不得补充资料外本地事实" |
| `constrained` | 涉及本地事实、但知识库可能没写过 | **同样给【资料】**，但提示词明说"这些词不在语料里，资料可能答不了"，要求先看资料、答不了才说缺依据，且不得编造本地事实 |
| `general` | 与汉中无关（问系统自身、通用概念、闲聊） | **不给资料、也不检索**；当通用助手直接回答 |

`rag` 与 `constrained` 的差别**只在提示词**，不在有没有上下文 —— 这是被实测逼出来的：
缺口判定会误报（51 条检索用例里 3 条被误判，如"浆水面为什么叫浆水面"，
jieba 把"叫浆"切成了碎片），如果 `constrained` 干脆不给资料，
这些本来答得上来的问题就会被提示词劝退。所以它的措辞是"资料**可能**答不了"。

为什么要有 `constrained` 而不是直接拒答：拒答的代价是"连模型本来就能答的部分也丢了"。
原来的实现里，凡是 `topic_gaps` 命中的问题一律回"知识库里没有记载"，
于是"你是 AI 吗"这种跟知识库毫无关系的问题也被拒答了 —— 那是把
"知识库的覆盖范围"错当成了"系统能回答的范围"。`constrained` 保住了事实约束
（不许编造汉中的数字/价格/时间/名单），同时把通用部分交还给模型。

`general` 刻意**不检索**：既然不用知识库，就不该为它付一次嵌入调用的钱，
也不该在界面上显示"检索 5 条"这种误导性的数字。

降级链（没有模型时）：

| 模式 | 触发条件 | 特点 |
|---|---|---|
| `llm` | 配了 LLM_API_KEY 且未开 DEMO_MODE | 真正的流式生成，答案用自己的话组织 |
| `cache` | DEMO_MODE=true，或问题命中预生成缓存 | 回放预先写好的答案，逐字吐出以保持流式体验 |
| `extractive` | 没有模型，但问题在知识库范围内 | 直接摘录检索到的资料原文，不润色、不编造 |
| `no_answer` | 没有模型，且这条问题本来要靠模型（通用问答 / 知识库无依据） | 说明离线模式的边界 |

`extractive` 这一档是刻意保留的：没有它，没配 key 的环境下系统就只会说"AI 不可用"，
演示时等于白屏；有了它，系统仍然能给出**有出处、可核对**的回答，只是措辞不好看。
`no_answer` 现在只出现在**离线**且问题超出知识库范围的场景 ——
有模型时不再有"因为知识库没写过就拒答"这条路。
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

# 三条链路的标识。`build_messages` 用它选提示词，`qa.py` 用它选分支，
# 也随 meta 事件发给前端（前端据此说明"这一问是怎么答出来的"）。
ROUTE_RAG = "rag"
ROUTE_CONSTRAINED = "constrained"
ROUTE_GENERAL = "general"

# 三份系统提示，差别只有一处：**模型被允许使用哪一部分知识**。
#
# 为什么不能只用一份（原来的写法）：原来那份写着"只依据【资料】里的内容回答，
# 资料里没有的不要用你自己的知识补充"，而"没有资料"和"资料里没有这一条"
# 在模型眼里是一回事 —— 于是通用问题（"你是 AI 吗"）即使把资料换成空，
# 也只会得到一句"知识库里没有相关记载"。**约束本身没有错，错的是把它
# 用在了不该用的问题上。** 所以现在按链路分三份，各守各的边界。

SYSTEM_PROMPT_RAG = """你是「汉游智脑」的文旅知识助手，服务对象是准备到陕西{city}旅游的游客和当地文旅管理者。
本次回答附有从本地知识库检索到的【资料】。

回答规则：
1. 以【资料】为主要依据。资料里明确写了的，按资料说，不要改动其中的数字与名称。
2. **资料里没有的{city}具体事实，一律不要凭自己的知识补充或推测** ——
   价格、开放时间、电话、里程、班次、名单、机构、政策都在此列。
   这类信息错一个就可能让人白跑一趟，宁可说"资料里没有"。
3. 如果资料不足以回答问题的核心部分，直接说明"知识库中没有相关记载"，
   再说清楚资料里能回答的是哪一部分。
4. 与{city}无关的通识背景（某个概念的通用含义、一般做法）可以简要补充，
   但要与资料内容分开表述，不要让读者以为那也是知识库里的记载。
5. 用简体中文，简洁分点，用自己的话组织，不要整段照抄资料。
6. 不要写"根据资料1"这类引用标记，来源由系统单独展示。"""

SYSTEM_PROMPT_CONSTRAINED = """你是「汉游智脑」的文旅知识助手，服务对象是准备到陕西{city}旅游的游客和当地文旅管理者。
本次回答附有从本地知识库检索到的【资料】，但**提问里的这些词不在知识库语料中：{gaps}** ——
也就是说这份资料**可能**答不了这个问题。

回答规则：
1. **先看【资料】能不能回答。** 能回答的部分就按资料回答，规则同正常检索问答。
2. 资料确实答不了的部分，开头明确告诉用户：知识库里缺少这方面的可靠依据。
   —— 注意是"资料答不了的部分"，不是整个问题；资料答得上就别说不缺依据。
3. **资料里没有的{city}具体事实，一律不要凭自己的知识补充或推测** ——
   数字、价格、开放时间、电话、里程、班次、名单、机构、政策都在此列。
   你没有依据，写出来就是在骗人。
4. 与{city}无关的通识背景（概念解释、一般流程、常识）可以简要说明，
   但必须标明"以下是通识信息，不是来自本知识库"。
5. 如果用户想知道的确实是{city}的情况，建议他换个说法再问。
6. 用简体中文，简洁分点，不要写引用标记。
7. 供你告诉用户"能问什么"的知识库覆盖范围：{scope}"""

SYSTEM_PROMPT_GENERAL = """你是「汉游智脑」的助手 —— 一个面向{city}文旅场景的智能助手。
**这个问题不需要{city}的文旅知识**，所以本次不查阅知识库，请用你自己的通用知识回答。

回答规则：
1. 直接、自然地回答用户的问题。
2. 不要编造与{city}有关的具体事实（数字、价格、时间、名单、机构）。
   如果用户其实想问的是{city}的情况，提醒他补上"{city}"再问一次。
3. 用简体中文，简洁明了，不要写引用标记。"""


def _fill(template: str, **values: str) -> str:
    """占位符替换。

    刻意不用 `str.format`：提示词里迟早会出现 `{}` 或 JSON 示例，
    那时 format 会把它们当成占位符报 KeyError，而错误信息指向的是模板本身、
    不是"你多写了一个花括号"。逐键 replace 没有这个隐患。
    """
    out = template
    for key, value in values.items():
        out = out.replace("{" + key + "}", value)
    return out


def build_messages(
    question: str,
    hits: Sequence[Hit],
    route: str = ROUTE_RAG,
    *,
    city: str = "汉中",
    gaps: Sequence[str] = (),
    scope: str = "",
) -> list[dict[str, str]]:
    """按链路组装消息。

    - `rag` 与 `constrained` 都会把检索到的切片作为【资料】放进 user 消息 ——
      **只有提示词的严格程度不同**。这一点是有意为之：缺口判定会误报
      （实测 51 条检索用例里有 3 条被误判成有缺口，如"浆水面为什么叫浆水面"
      因为分词器把"叫浆"切成了碎片），如果 `constrained` 干脆不给资料，
      这些本来答得上来的问题就会被提示词劝退。所以 `constrained` 的措辞是
      "资料**可能**答不了"而不是"资料答不了"，并要求模型先看资料。
    - `general` 不带资料、也没有资料可带（这条链路不检索）。"""
    if route == ROUTE_GENERAL:
        return [
            {"role": "system", "content": _fill(SYSTEM_PROMPT_GENERAL, city=city)},
            {"role": "user", "content": question},
        ]

    blocks = []
    for i, hit in enumerate(hits, 1):
        blocks.append(f"【资料{i}】{hit.metadata.get('title', '')}\n{hit.text}")
    context = "\n\n".join(blocks) or "（本次没有检索到任何资料）"

    if route == ROUTE_CONSTRAINED:
        system = _fill(
            SYSTEM_PROMPT_CONSTRAINED,
            city=city,
            gaps="、".join(gaps) or "（未识别出具体词）",
            scope=scope or "（未提供）",
        )
    else:
        system = _fill(SYSTEM_PROMPT_RAG, city=city)

    return [
        {"role": "system", "content": system},
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
