"""问答编排：检索 → 定模式 → 生成 → 附带来源。

事件协议（每个 SSE `data:` 是一行 JSON）：

    {"type":"meta",  "mode":"llm", "sources":[...], "retrieved":5, "top_score":0.62,
                     "gaps":[], "gaps_hint":""}
    {"type":"delta", "text":"汉中"}
    {"type":"done",  "mode":"llm", "elapsed_ms":812}
    {"type":"error", "message":"..."}

先发 meta 再发 delta 是有意的：前端拿到 meta 就能立刻渲染来源角标与模式提示，
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


def _sources_from_hits(hits: Sequence[Hit]) -> list[dict[str, Any]]:
    return [
        {
            "title": h.metadata.get("title", ""),
            "doc_type": h.metadata.get("doc_type", ""),
            "source_name": h.metadata.get("source_name", ""),
            "source_url": h.metadata.get("source_url", ""),
            "score": round(h.score, 4),
        }
        for h in hits[:MAX_SOURCES]
    ]


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
            metas = self.store.metadata_for_docs(cached.doc_ids)
            return [
                {
                    "title": m.get("title", ""),
                    "doc_type": m.get("doc_type", ""),
                    "source_name": m.get("source_name", ""),
                    "source_url": m.get("source_url", ""),
                    "score": None,
                }
                for m in metas[:MAX_SOURCES]
            ]
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
            f"当前知识库覆盖 {self.settings.city} 的地理气候、历史文化、生态与物产等公开资料，"
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
