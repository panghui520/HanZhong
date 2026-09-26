"""问答编排：检索 → 定模式 → 生成 → 附带来源。

事件协议（每个 SSE `data:` 是一行 JSON）：

    {"type":"meta",  "mode":"llm", "sources":[...], "retrieved":5, "top_score":0.62,
                     "gaps":[], "gaps_hint":""}
    {"type":"delta", "text":"汉中"}
    {"type":"done",  "mode":"llm", "elapsed_ms":812}
    {"type":"error", "message":"..."}

`sources[]` 每一项（前端据此渲染来源卡片）：

    {"title":"汉中热面皮（老字号）", "doc_type":"poi",
     "snippet":"汉中标志性早餐，米浆蒸制后切条拌辣子，配菜豆腐是本地标准吃法。",
     "source_name":"汉中市文化和旅游局", "source_url":"http://wl.hanzhong.gov.cn/",
     "source_kind":"site", "poi_id":"P-FOOD-001", "score":0.42}

**这些字段全部来自知识库元数据，模型只负责正文。** 见 `_source_payload`。

先发 meta 再发 delta 是有意的：前端拿到 meta 就能立刻渲染来源卡片与模式提示，
不用等正文结束。用户在看到"正在生成"的同时就知道这次回答有没有出处。

**"知识库里没有"是怎么判定的**：不用相似度阈值。
实测过六个分数型判据（BM25 分、top1/top2 比、余弦、IDF 覆盖率、缺失词 IDF 占比、
最高命中 IDF），全部无法区分正例与反例——因为"汉中有地铁吗""汉中房价多少"
这类反例都带"汉中"，词面上和语料很近，分数反而可能比某些正例还高。
真正有效的是 lexical.topic_gaps：查问题里有没有语料完全没写过的实词。
"""

from __future__ import annotations

import time
from typing import Any, AsyncIterator, Sequence

from .llm import (
    AnswerCache,
    build_messages,
    chunk_for_stream,
    extractive_answer,
    stream_llm,
)
from .store import Hit, KbStore

MAX_SOURCES = 5


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
            "by_type": manifest.get("by_type", {}),
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

        hits = self.store.query(question, self.settings.top_k)
        top_score = hits[0].score if hits else 0.0
        gaps = self.store.topic_gaps(question)

        # 模式选择：问题超出知识库范围 -> 直接说没有；
        # 有模型就用模型；演示模式或没模型时先看缓存；再不行就摘录原文。
        cached = None if self.settings.llm_enabled else self.cache.lookup(question)

        if gaps and not cached:
            mode = "no_answer"
        elif self.settings.llm_enabled:
            mode = "llm"
        elif cached is not None:
            mode = "cache"
        else:
            mode = "extractive"

        sources = self._resolve_sources(mode, cached, hits)

        yield {
            "type": "meta",
            "mode": mode,
            "sources": sources,
            "retrieved": len(hits),
            "top_score": round(top_score, 4),
            "gaps": gaps,
            "gaps_hint": self._gaps_hint(gaps),
        }

        try:
            async for piece in self._generate(mode, question, hits, cached, gaps):
                yield {"type": "delta", "text": piece}
        except Exception as exc:  # noqa: BLE001 - 外部模型失败要让前端知道，而不是静默截断
            yield {"type": "error", "message": f"生成失败：{exc}"}
            return

        yield {
            "type": "done",
            "mode": mode,
            "elapsed_ms": int((time.monotonic() - started) * 1000),
        }

    def _resolve_sources(self, mode: str, cached, hits: Sequence[Hit]) -> list[dict[str, Any]]:
        if mode == "cache" and cached is not None:
            # 缓存里只记 doc_id，来源在这里现查 —— 改了数据包之后，
            # 缓存答案的引用会跟着更新，不会留下一串过期的硬编码链接。
            metas = self.store.metadata_for_docs(cached.doc_ids)
            return [_source_payload(m, None) for m in metas[:MAX_SOURCES]]
        if mode == "no_answer":
            return []
        return _sources_from_hits(hits)

    async def _generate(
        self,
        mode: str,
        question: str,
        hits: Sequence[Hit],
        cached,
        gaps: Sequence[str],
    ) -> AsyncIterator[str]:
        if mode == "llm":
            async for piece in stream_llm(self.settings, build_messages(question, hits)):
                yield piece
            return

        if mode == "cache" and cached is not None:
            text = cached.answer
        elif mode == "extractive":
            text = extractive_answer(question, hits)
        else:
            text = self._no_answer_text(question, gaps)

        for piece in chunk_for_stream(text):
            yield piece

    def _gaps_hint(self, gaps: Sequence[str]) -> str:
        """给前端的一句话提示。让用户知道被判定超出范围的是哪个词，
        而不是笼统地说"不知道"——他可以换个说法再问。"""
        if not gaps:
            return ""
        return "知识库里没有关于「" + "」「".join(gaps) + "」的记载"

    def _no_answer_text(self, question: str, gaps: Sequence[str]) -> str:
        """查不到时的回答。不能只说"不知道"——要告诉用户知识库到底覆盖了什么。"""
        manifest = self.store.manifest()
        by_type = manifest.get("by_type", {})
        scope = (
            f"当前知识库覆盖 {self.settings.city_name} 的地理气候、历史文化、生态与物产等公开资料，"
            f"以及 {by_type.get('poi', 0)} 个资源点、{by_type.get('experience', 0)} 项乡村体验、"
            f"{by_type.get('product', 0)} 款乡村产品。"
        )
        reason = self._gaps_hint(gaps)
        tips = "\n".join(f"· {q}" for q in self.suggestions()[:4])
        return (
            f"{reason or '知识库里没有与这个问题相关的记载'}。\n\n"
            f"{scope}\n\n"
            f"你可以换个说法再问，或者试试下面这些问题：\n{tips}"
        )
