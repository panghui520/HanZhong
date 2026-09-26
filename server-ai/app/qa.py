"""问答编排：定链路 → 检索 → 定模式 → 生成 → 附带来源。

## 三条链路（`route`）

架构上这里是**最要紧的一处**：DeepSeek 是回答模型，知识库是它的增强来源，
而不是"知识库回答器"。所以先判"这一问要不要用知识库"，再决定怎么答。

| route | 判定 | 检索 | 上下文 | 来源卡片 |
|---|---|---|---|---|
| `rag` | 缺口判定没发现问题 | 是 | 检索到的【资料】 | 有 |
| `constrained` | 涉及本地事实，但知识库**可能**没写过 | 是 | 检索到的【资料】（提示词更严） | 有 |
| `general` | 与汉中无关（问系统自身 / 通用概念 / 闲聊） | **不检索** | 无 | 无 |

判定顺序见 `_route`：**先看提没提城市名，再看是不是通用问题，最后才看缺口**。
顺序是有意的 ——
① 城市名优先，让"通用问题"的识别永远盖不过本地问题（"汉中有 AI 产业吗"必须按本地处理）；
② 通用问题的识别必须排在缺口判定之前，否则"你是谁"这类分词后没有实词的问题
会拿到空 gaps、被当成"知识库覆盖"送去检索，最后拿一堆无关资料回答"你是谁"；
③ 其余一律按本地问题保守处理。误判成 `general` 的代价是"该守事实约束时没守"，
比反过来严重得多。

`constrained` **照常把资料给模型**，只是提示词换了一份更严的（见
`llm.SYSTEM_PROMPT_CONSTRAINED`）。这一点是被实测逼出来的：缺口判定会误报
（51 条检索用例里有 3 条被误判成有缺口，例如"浆水面为什么叫浆水面"，
jieba 把领域词切碎成了"叫浆"），如果不给资料，这些答得上来的问题会被劝退。

## 为什么不再用 `topic_gaps` 直接拒答

原来的实现是：`topic_gaps` 非空 → `mode=no_answer`，直接回"知识库里没有记载"。
问题在于它把**知识库的覆盖范围**当成了**系统能回答的范围** ——
"你是 AI 吗""什么是 AI"这类跟知识库毫无关系的问题也被拒答了，
而 DeepSeek 本来就能好好回答它们。

现在 `topic_gaps` 只作为**信号**（决定 `route` 与提示词），不再作为闸门：
有模型时不存在"因为知识库没写过就拒答"这条路。事实约束移到提示词里，
比一刀切拒答精确得多 —— 而且它现在**同时管两个方向**：资料里有就照资料答，
资料里没有的本地事实不许编。

**来源卡片只在"检索命中"的口径下给**（前端那栏的标题写的就是"检索命中"，
不是"回答依据"）：`rag` 与 `constrained` 给了模型资料，所以列出命中的切片；
`general` 什么都没检索，因此不给 —— 署名一份没用上的资料，等于把"可追溯"
变成"看起来可追溯"。

## 事件协议（每个 SSE `data:` 是一行 JSON）

    {"type":"meta",  "mode":"llm", "route":"rag", "generator":"deepseek-chat",
                     "sources":[...], "retrieved":5, "top_score":0.62,
                     "gaps":[], "gaps_hint":""}
    {"type":"delta", "text":"汉中"}
    {"type":"done",  "mode":"llm", "elapsed_ms":812}
    {"type":"error", "message":"..."}

`generator` 说明这一条回答**实际由谁产出**：模型名（真调了 DeepSeek）/
`cache` / `extractive` / `none`。它是给"到底有没有调模型"这个问题一个
可直接断言的字段，不用从回答文本里猜。

`sources[]` 每一项（前端据此渲染来源卡片）：

    {"title":"汉中热面皮（老字号）", "doc_type":"poi",
     "snippet":"汉中标志性早餐，米浆蒸制后切条拌辣子，配菜豆腐是本地标准吃法。",
     "source_name":"汉中市文化和旅游局", "source_url":"http://wl.hanzhong.gov.cn/",
     "source_kind":"site", "poi_id":"P-FOOD-001", "score":0.42}

**这些字段全部来自知识库元数据，模型只负责正文。** 见 `_source_payload`。
`general` 这条链路**不给来源** —— 它根本没检索，署名一份没用上的资料
等于把"可追溯"变成"看起来可追溯"。`constrained` 照常给（它检索了、
也把资料交给了模型），见 `_resolve_sources`。

先发 meta 再发 delta 是有意的：前端拿到 meta 就能立刻渲染来源卡片与模式提示，
不用等正文结束。用户在看到"正在生成"的同时就知道这次回答有没有出处。

**"知识库里没有"是怎么判定的**：不用相似度阈值。
实测过六个分数型判据（BM25 分、top1/top2 比、余弦、IDF 覆盖率、缺失词 IDF 占比、
最高命中 IDF），全部无法区分正例与反例——因为"汉中有地铁吗""汉中房价多少"
这类反例都带"汉中"，词面上和语料很近，分数反而可能比某些正例还高。
真正有效的是 lexical.topic_gaps：查问题里有没有语料完全没写过的实词。
它的**边界**（会漏过什么、为什么不再收紧）见 `scripts/eval_gaps.py`。
"""

from __future__ import annotations

import re
import time
from typing import Any, AsyncIterator, Sequence

from .aliases import expand
from .llm import (
    ROUTE_CONSTRAINED,
    ROUTE_GENERAL,
    ROUTE_RAG,
    AnswerCache,
    build_messages,
    chunk_for_stream,
    extractive_answer,
    stream_llm,
)
from .store import Hit, KbStore

MAX_SOURCES = 5

# 指向"系统自身"或"寒暄"的词。出现它们说明用户在跟助手对话，不是在问汉中。
# 这类词只用来把问题**从本地问题里排除出去**，所以宁可窄：识别不出来就按
# 本地问题处理（保守），误判成 general 才是要防的方向。
META_WORDS = frozenset(
    {
        "助手", "机器人", "智能体", "人工智能", "大模型",
        "ai", "gpt", "chatgpt", "deepseek", "llm",
        "你好", "您好", "在吗", "谢谢", "再见",
    }
)

# 自称代词 + 身份疑问词的组合，覆盖"你是谁 / 你是什么助手 / 你会什么"这类问法。
# 单看代词不够 —— "你能推荐一条线路吗"也带"你"，但那是正经问题。
SELF_PRONOUNS = ("你", "您")
IDENTITY_HINTS = (
    "谁", "什么", "啥", "助手", "机器人", "模型", "智能体",
    "ai", "人工智能", "大模型", "做什么", "干什么",
)

# 定义式问法。它问的是"X 是什么"，X 通常是通用概念；
# 如果 X 其实在语料里（"什么是天坑"），缺口判定会先给出空 gaps，走不到这里。
DEFINITION_PREFIXES = ("什么是", "啥是", "什么叫")


def _looks_like_meta_or_concept(question: str) -> bool:
    """问题是不是在问"系统自身"或"通用概念"，而不是在问汉中。

    只做**正向识别**，识别不出来就当本地问题处理 —— 见模块 docstring 里
    关于判定顺序的说明。
    """
    text = expand(question).lower()
    if any(word in text for word in META_WORDS):
        return True
    if text.startswith(DEFINITION_PREFIXES):
        return True
    return any(p in text for p in SELF_PRONOUNS) and any(
        h in text for h in IDENTITY_HINTS
    )


def _route(question: str, gaps: Sequence[str], city_name: str) -> str:
    """决定这一问走哪条链路。

    判定顺序（**先看提没提城市名，再看是不是通用问题，最后才看缺口**）：

    1. **提到城市名** → 本地问题。有缺口走 `constrained`（事实约束最严的那份提示词），
       没缺口走 `rag`。城市名这一步放在最前面，是为了让"通用问题"的识别
       **永远盖不过本地问题** —— 例如"汉中有 AI 产业吗"，虽然带"ai"，
       但既然点名了汉中，就必须按本地事实来处理。
    2. **没提城市名，但看得出是在问系统自身或通用概念** → `general`。
       这一步必须排在缺口判定**之前**：像"你是谁"这种问题，分词后没有一个
       长度 ≥2 的实词，缺口判定会给出空 gaps，如果先看 gaps 就会被当成
       "知识库覆盖"而送去检索，最后拿一堆无关资料去回答"你是谁"。
    3. **其余** → 按本地问题保守处理（`constrained` / `rag`）。
       "有恐龙化石吗"就落在这里：在汉中旅游站里问，几乎一定是在问汉中。
    """
    text = expand(question)
    if city_name and city_name in text:
        return ROUTE_CONSTRAINED if gaps else ROUTE_RAG
    if _looks_like_meta_or_concept(question):
        return ROUTE_GENERAL
    return ROUTE_CONSTRAINED if gaps else ROUTE_RAG



def _source_payload(meta: dict[str, Any], score: float | None) -> dict[str, Any]:
    """一条来源卡片的数据。

    **所有字段都取自知识库元数据，没有一处由模型生成。** 这是 M3 的硬约束：
    模型只负责组织答案正文；来源（名称、链接、性质）必须来自检索到的切片元数据。
    否则模型可能编出一个看起来很像的网址，而"可追溯"就成了空话 ——
    本项目里来源的全部价值就是可核对。

    - `source_kind`：detail（有具体页面）/ site（只有站点级参考）/ dataset（数据包自有）。
      前端据此决定标签文案与是否给外链。缺失时按 site 处理：宁可少说一句"原文可查"，
      也不要把一份真实出处标成"没有出处"。
    - `poi_id`：拼站内链接用。资源点、体验、产品三类文档都带它
      （体验与产品记的是挂靠的资源点），所以点一下就能跳到 `/poi/{poi_id}`
      看完整内容 —— 这是"数据包自有"那两档唯一能给的**真链接**。
    - `snippet`：构建期算好的简短介绍，允许为空（前端少一行，不报错）。
    """
    return {
        "title": meta.get("title", ""),
        "doc_type": meta.get("doc_type", ""),
        "source_name": meta.get("source_name", ""),
        "source_url": meta.get("source_url", ""),
        "source_kind": meta.get("source_kind", "site"),
        "snippet": meta.get("snippet", ""),
        "poi_id": meta.get("poi_id", ""),
        "score": None if score is None else round(score, 4),
    }


def _sources_from_hits(hits: Sequence[Hit]) -> list[dict[str, Any]]:
    """把命中转成前端要的来源列表。

    `score` 是 **BM25 归一化分**，不是排序键 —— 排序由 `store.query()` 的 RRF
    融合分决定，所以这个数组里的 score **不保证单调递减**（第 3 条可能比第 1 条高）。
    前端只用它展示首条相关度（`meta.top_score`），不逐条展示；保留它是为了排查时
    能看到每条来源各自的词法相关度。
    """
    return [_source_payload(h.metadata, h.score) for h in hits[:MAX_SOURCES]]


class QaService:
    def __init__(self, settings, store: KbStore, cache: AnswerCache) -> None:
        self.settings = settings
        self.store = store
        self.cache = cache

    # ---- 给 /ai/health 用 ----

    def status(self) -> dict[str, Any]:
        manifest = self.store.manifest()
        return {
            "ok": self.store.count() > 0,
            "city": self.settings.city,
            "chunks": self.store.count(),
            "docs": manifest.get("docs", 0),
            # by_type 是**切片**数，by_type_docs 是**文档**数。
            # 前端"知识库里有这些"要显示"20 篇城市知识 / 42 处资源点"，
            # 用的是 by_type_docs；by_type 留给侧栏的"切片"口径。
            "by_type": manifest.get("by_type", {}),
            "by_type_docs": manifest.get("by_type_docs", {}),
            "embedder": self.store.embedder.signature,
            "lexical": self.store.lexical.coverage(),
            "llm_configured": self.settings.llm_enabled,
            "demo_mode": self.settings.demo_mode,
            "model": self.settings.llm_model if self.settings.llm_enabled else None,
            "cache_entries": len(self.cache),
        }

    def suggestions(self) -> list[str]:
        """推荐问题。取缓存里的问题，没配缓存时给一组通用问法。"""
        if self.cache.meta.get("suggestions"):
            return list(self.cache.meta["suggestions"])
        return [
            "汉中的气候怎么样，什么季节去最合适？",
            "朱鹮是在哪里发现的？",
            "从西安怎么去汉中？",
            "汉中有什么值得带走的特产？",
            "南郑·黄官茶园能体验什么？",
        ]

    # ---- 主流程 ----

    async def stream(self, question: str) -> AsyncIterator[dict[str, Any]]:
        started = time.monotonic()
        question = (question or "").strip()

        if not question:
            yield {"type": "error", "message": "问题不能为空"}
            return

        # 1) 缺口判定只是**信号**，不再决定要不要回答（见模块 docstring）
        gaps = self.store.topic_gaps(question)
        route = _route(question, gaps, self.settings.city_name)

        # 2) 检索。`general` 这条链路不用知识库，连检索都不做 ——
        #    既省一次嵌入调用，也避免界面显示"检索 5 条"这种误导性数字。
        hits = (
            []
            if route == ROUTE_GENERAL
            else self.store.query(question, self.settings.top_k)
        )
        top_score = hits[0].score if hits else 0.0

        # 3) 模式：有模型就用模型（三条链路都走模型）；没模型才降级
        cached = None if self.settings.llm_enabled else self.cache.lookup(question)
        if self.settings.llm_enabled:
            mode = "llm"
        elif cached is not None:
            mode = "cache"
        elif route == ROUTE_RAG:
            # 离线但问题在知识库范围内 → 摘录原文，仍然给得出有出处的回答
            mode = "extractive"
        else:
            # 离线，且这一问本来要靠模型（通用问答 / 知识库无依据）→ 只能说明边界
            mode = "no_answer"

        sources = self._resolve_sources(mode, cached, hits, route)
        scope = self._scope_text()

        yield {
            "type": "meta",
            "mode": mode,
            "route": route,
            "generator": self._generator(mode),
            "sources": sources,
            "retrieved": len(hits),
            "top_score": round(top_score, 4),
            "gaps": gaps,
            "gaps_hint": self._gaps_hint(gaps),
        }

        try:
            async for piece in self._generate(
                mode, question, hits, cached, gaps, route, scope
            ):
                yield {"type": "delta", "text": piece}
        except Exception as exc:  # noqa: BLE001 - 外部模型失败要让前端知道，而不是静默截断
            yield {"type": "error", "message": f"生成失败：{exc}"}
            return

        yield {
            "type": "done",
            "mode": mode,
            "elapsed_ms": int((time.monotonic() - started) * 1000),
        }

    def _generator(self, mode: str) -> str:
        """这一条回答实际由谁产出。给"到底有没有调模型"一个可断言的字段。

        不用从回答文本里猜（长度、措辞都会骗人），也不用去看日志 ——
        `generator` 是模型名就说明真的发了请求。
        """
        if mode == "llm":
            return self.settings.llm_model or "llm"
        if mode == "cache":
            return "cache"
        if mode == "extractive":
            return "extractive"
        return "none"

    def _scope_text(self) -> str:
        """知识库覆盖范围的一句话说明。给用户看，也塞进 constrained 的提示词。"""
        by_type_docs = self.store.manifest().get("by_type_docs", {})
        return (
            f"当前知识库覆盖{self.settings.city_name}的地理气候、历史文化、生态与物产等公开资料，"
            f"以及 {by_type_docs.get('poi', 0)} 个资源点、"
            f"{by_type_docs.get('experience', 0)} 项乡村体验、"
            f"{by_type_docs.get('product', 0)} 款乡村产品。"
        )

    def _resolve_sources(
        self, mode: str, cached, hits: Sequence[Hit], route: str
    ) -> list[dict[str, Any]]:
        """来源卡片。

        给来源的条件是**"这次真的检索了、而且把命中的切片交给了模型"**：

        - `rag` / `constrained`：都检索了、也都给了资料 → 列出命中切片。
          前端那一栏的标题写的是"检索命中"而不是"回答依据"（取的是 top-5 候选，
          模型实际用到的往往只有前一两张），所以这个口径是如实的。
        - `cache`：答案正文里引用了那几篇文档 → 列出来。
        - `general`：根本没检索 → 不给。署名一份没用上的资料，
          等于把"可追溯"变成"看起来可追溯"。
        """
        if mode == "cache" and cached is not None:
            # 缓存里只记 doc_id，来源在这里现查 —— 改了数据包之后，
            # 缓存答案的引用会跟着更新，不会留下一串过期的硬编码链接。
            metas = self.store.metadata_for_docs(cached.doc_ids)
            return [_source_payload(m, None) for m in metas[:MAX_SOURCES]]
        if route == ROUTE_GENERAL or mode == "no_answer":
            return []
        return _sources_from_hits(hits)

    async def _generate(
        self,
        mode: str,
        question: str,
        hits: Sequence[Hit],
        cached,
        gaps: Sequence[str],
        route: str,
        scope: str,
    ) -> AsyncIterator[str]:
        if mode == "llm":
            messages = build_messages(
                question,
                hits,
                route,
                city=self.settings.city_name,
                gaps=gaps,
                scope=scope,
            )
            async for piece in stream_llm(self.settings, messages):
                yield piece
            return

        if mode == "cache" and cached is not None:
            text = cached.answer
        elif mode == "extractive":
            text = extractive_answer(question, hits)
        else:
            text = self._no_answer_text(question, gaps, route)

        for piece in chunk_for_stream(text):
            yield piece

    def _gaps_hint(self, gaps: Sequence[str]) -> str:
        """给前端的一句话提示。让用户知道被判定超出范围的是哪个词，
        而不是笼统地说"不知道"——他可以换个说法再问。"""
        if not gaps:
            return ""
        return "知识库里没有关于「" + "」「".join(gaps) + "」的记载"

    def _no_answer_text(self, question: str, gaps: Sequence[str], route: str) -> str:
        """**离线**模式下答不了的两种情形。不能只说"不知道"——
        要告诉用户离线模式的边界在哪、知识库覆盖了什么。

        有模型时不会走到这里：`constrained` 交给模型说明"缺少依据"，
        `general` 直接交给模型回答。
        """
        scope = self._scope_text()
        tips = "\n".join(f"· {q}" for q in self.suggestions()[:4])

        if route == ROUTE_GENERAL:
            return (
                "当前处于离线演示模式（未配置大模型），我只能回答知识库覆盖的汉中问题。\n\n"
                f"{scope}\n\n"
                f"你可以试试下面这些问题：\n{tips}"
            )

        reason = self._gaps_hint(gaps) or "知识库里没有与这个问题相关的记载"
        return (
            f"{reason}。\n\n"
            f"当前处于离线演示模式（未配置大模型），这类问题无法展开回答 —— "
            f"配置模型后它会说明知识库缺少哪部分依据，并回答其中通用的部分。\n\n"
            f"{scope}\n\n"
            f"你可以换个说法再问，或者试试下面这些问题：\n{tips}"
        )
