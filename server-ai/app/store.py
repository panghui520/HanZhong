"""Chroma 向量库的读写封装，以及检索的入口。

四个约定值得说明：

1. **collection 名带向量化实现的指纹**（`kb_city__ngram2048x123-<别名指纹>`）。
   Chroma 的集合维度是固定的，把两种 embedding 的向量混进同一个集合会静默出错。
   指纹里带上别名表哈希，改一条别名就等于换了特征空间，自然落到另一个集合。
   `rebuild` 会顺手清掉本库里其他 `kb_city__*` 集合，不留孤儿。

2. **IDF 与语料指纹单独落盘**（`idf.json` / `manifest.json`）。
   查询侧要用与索引侧完全一致的 IDF，否则算出来的相似度没有可比性。
   指纹用来判断索引是否过期——数据包改了但没重建时，能明确报出来而不是给出旧答案。

3. **召回与排序是两件事**（见 `query`）。
   向量负责"别漏掉"，排序由 **RRF 融合**负责 —— 只用两路的名次、不用分数，
   因为 BM25 归一化分与余弦本来就不可比（实测加权方案随权重单调变差）。
   改用 RRF 的完整对比数据写在 `query` 的 docstring 里。

4. **来源性质随元数据落库**（`source_kind` / `snippet`）。
   查询侧不重新判断"这条来源可不可核对"——那是语料构建时的结论，
   存进来、原样传给前端即可。排序与召回完全不看这两个字段。
"""

from __future__ import annotations

import hashlib
import json
import logging
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Sequence

from .corpus import Chunk
from .lexical import LexicalIndex

COLLECTION_BASE = "kb_city"
IDF_FILE = "idf.json"
MANIFEST_FILE = "manifest.json"

# 候选池下限。实测：池子取 20 时 top5 命中与全量 BM25 完全一致（26 条用例），
# 再放大到 40、83 都没有变化。取 20 是为了在语料长大之后仍然只做局部精排。
MIN_POOL = 20

# RRF（Reciprocal Rank Fusion）的 k 参数，作用是压平名次差异——k 越小，
# 头部名次被放得越大。60 是 RRF 原文的默认值。
# 实测 k 取 1/3/5/10/20/60 时 top1/top5 完全一致（26 条用例），
# 所以不引入需要额外解释的调参，用默认值即可。
RRF_K = 60

logger = logging.getLogger("hanyou.ai")

# 向量通道失效的告警去重。每问一次就刷一行会把日志淹掉，
# 但完全静默又会让"通道其实没生效"藏很久——见 _warn_vector_failure 的注释。
_VECTOR_FAIL_WARNED: set[str] = set()


def _warn_vector_failure(exc: BaseException) -> None:
    """向量通道失效时记一条日志（同一原因只记一次）。

    这条日志是补出来的，起因是一个真实的坑：`ApiEmbedder.embed()` 早先不接受
    `restrict_vocab` 参数，而检索层统一传 `restrict_vocab=True`，于是配了厂商
    嵌入模型之后每次查询都 TypeError。异常被"向量失败就降级"的 except 吞掉，
    结果**向量通道一次都没生效**，而检索表面上仍然"能用"（BM25 本来就负责排序，
    分数看着正常），直到做通道消融——纯向量 top1 命中 0/26——才暴露。

    所以这里的取舍是：降级本身是对的（外部依赖坏掉不该让整个请求失败），
    但**降级必须是可观测的**。同一原因只报一次，既不淹没日志也不隐形。
    """
    key = f"{type(exc).__name__}: {exc}"
    if key in _VECTOR_FAIL_WARNED:
        return
    _VECTOR_FAIL_WARNED.add(key)
    logger.warning("[检索] 向量通道失效，本次退化为纯 BM25：%s", key)


@dataclass
class Hit:
    chunk_id: str
    doc_id: str
    text: str
    score: float
    """归一化到 [0,1] 的 BM25 相关度，供前端展示。**口径未变**"""
    raw: float
    """BM25 原始分，只用于排查"""
    vector_score: float
    """余弦相似度。仅作诊断，不参与排序"""
    fusion: float = 0.0
    """RRF 融合分，**排序键**。两路名次的调和，不要求两边分数可比"""
    metadata: dict[str, Any] = field(default_factory=dict)


def _flatten_metadata(chunk: Chunk) -> dict[str, Any]:
    """Chroma 的 metadata 只接受标量，把 extra 里的值摊平并丢掉空值。

    空字符串不写进去：Chroma 不支持 None，而空串会让 `where` 过滤出现
    "字段存在但为空"的歧义情况。
    """
    meta: dict[str, Any] = {
        "chunk_id": chunk.chunk_id,
        "doc_id": chunk.doc_id,
        "title": chunk.title,
        "doc_type": chunk.doc_type,
        "seq": chunk.seq,
    }
    if chunk.source_url:
        meta["source_url"] = chunk.source_url
    if chunk.source_name:
        meta["source_name"] = chunk.source_name
    if chunk.data_origin:
        meta["data_origin"] = chunk.data_origin

    # 来源性质与简介（M3 来源卡片用，见 corpus.py 顶部「来源分三档」）。
    # source_kind **一定写**：它是前端"该不该给外链、给哪种标签"的判据，
    # 缺了这一格前端只能猜，而猜错的表现就是给一条点进去找不到内容的链接。
    # snippet 允许为空 —— 抽不出简介时前端只是少一行，不该因此报错。
    meta["source_kind"] = chunk.source_kind
    if chunk.snippet:
        meta["snippet"] = chunk.snippet

    for key, value in chunk.extra.items():
        if isinstance(value, (str, int, float, bool)) and value != "":
            meta[key] = value
    return meta


def corpus_fingerprint(chunks: Sequence[Chunk], signature: str) -> str:
    """语料 + 向量化实现的指纹。用来判断索引是否需要重建。

    **元数据也算进来**（来源名/链接/性质/简介）。只算 chunk_id + 正文是不够的：
    给文档补一列来源、改一次简介，都不会动到正文，指纹就会认为"没变化"、
    `is_stale` 返回 False，而新字段其实根本没进库 —— 前端静默拿到空简介、
    来源退化成默认档，日志里一句话都没有。这种"看起来正常"的不一致最难查，
    所以宁可让指纹敏感一点：元数据变了就要求重建，代价只是重跑一次建库脚本。
    """
    digest = hashlib.blake2b(digest_size=16)
    digest.update(signature.encode("utf-8"))
    for chunk in chunks:
        digest.update(chunk.chunk_id.encode("utf-8"))
        digest.update(chunk.text.encode("utf-8"))
        for value in (
            chunk.source_name,
            chunk.source_url,
            chunk.source_kind,
            chunk.snippet,
        ):
            digest.update(b"\x1f")
            digest.update(value.encode("utf-8"))
    return digest.hexdigest()


class KbStore:
    def __init__(
        self,
        path: Path,
        embedder,
        collection_name: str,
        lexical: LexicalIndex,
    ) -> None:
        import chromadb

        self.path = path
        self.embedder = embedder
        self.collection_name = collection_name
        self.lexical = lexical
        # chunk_id -> Chunk，命中时取正文用。BM25 命中的切片不一定在向量候选里，
        # 所以正文统一从这里取，不依赖 Chroma 返回的 documents。
        self._by_chunk = {c.chunk_id: c for c in lexical.chunks}
        self._index_of_chunk = {c.chunk_id: i for i, c in enumerate(lexical.chunks)}

        self.path.mkdir(parents=True, exist_ok=True)
        self._client = chromadb.PersistentClient(path=str(path))
        self._collection = self._client.get_or_create_collection(
            name=collection_name,
            embedding_function=None,  # 向量由我们自己算好传进去
            metadata={"hnsw:space": "cosine"},
        )

    # ---- 构建 ----

    @classmethod
    def rebuild(cls, path: Path, embedder, chunks: Sequence[Chunk]) -> "KbStore":
        """全量重建。语料是权威来源，不做事后补丁式更新。"""
        import chromadb

        collection_name = f"{COLLECTION_BASE}__{embedder.signature}"
        path.mkdir(parents=True, exist_ok=True)
        client = chromadb.PersistentClient(path=str(path))

        # 清掉本库里其他 kb_city__* 集合：改了别名表或换了向量化实现之后，
        # 旧集合不会被任何人再读到，留着只会在目录里积垃圾。
        for existing in client.list_collections():
            name = existing if isinstance(existing, str) else getattr(existing, "name", "")
            if name.startswith(f"{COLLECTION_BASE}__") and name != collection_name:
                try:
                    client.delete_collection(name)
                except Exception:  # noqa: BLE001 - 删不掉不影响建库，交给下一次重建
                    pass

        # 同名集合已存在时先删掉，避免残留上一次的切片
        try:
            client.delete_collection(collection_name)
        except Exception:  # noqa: BLE001 - 不存在时不同版本抛的异常类型不一致
            pass

        collection = client.get_or_create_collection(
            name=collection_name,
            embedding_function=None,
            metadata={"hnsw:space": "cosine"},
        )
        if chunks:
            texts = [c.text for c in chunks]
            collection.add(
                ids=[c.chunk_id for c in chunks],
                documents=texts,
                embeddings=embedder.embed_many(texts),
                metadatas=[_flatten_metadata(c) for c in chunks],
            )

        # IDF 只有离线实现需要落盘；API embedding 不需要（它不依赖语料统计）
        if hasattr(embedder, "save"):
            embedder.save(path / IDF_FILE)

        (path / MANIFEST_FILE).write_text(
            json.dumps(
                {
                    "collection": collection_name,
                    "embedder": embedder.signature,
                    "chunks": len(chunks),
                    "docs": len({c.doc_id for c in chunks}),
                    "by_type": _count_by_type(chunks),
                    "by_source_kind": _count_by_source_kind(chunks),
                    "fingerprint": corpus_fingerprint(chunks, embedder.signature),
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )

        return cls(path, embedder, collection_name, LexicalIndex(chunks))

    # ---- 读取 ----

    @classmethod
    def open(cls, path: Path, embedder, chunks: Sequence[Chunk]) -> "KbStore":
        return cls(path, embedder, f"{COLLECTION_BASE}__{embedder.signature}", LexicalIndex(chunks))

    def count(self) -> int:
        return self._collection.count()

    def manifest(self) -> dict[str, Any]:
        file = self.path / MANIFEST_FILE
        if not file.is_file():
            return {}
        return json.loads(file.read_text(encoding="utf-8"))

    def is_stale(self, chunks: Sequence[Chunk]) -> bool:
        """语料或向量化实现与索引不一致时返回 True"""
        recorded = self.manifest().get("fingerprint")
        return recorded != corpus_fingerprint(chunks, self.embedder.signature)

    def metadata_for_docs(self, doc_ids: Sequence[str]) -> list[dict[str, Any]]:
        """按 doc_id 取元数据，用于把缓存答案里写的来源还原成可点击的引用。

        缓存里只记 doc_id，来源名称与链接在这里现查——这样改了数据包之后，
        缓存答案的引用会跟着更新，不会留下一串过期的硬编码链接。
        """
        if not doc_ids:
            return []
        try:
            result = self._collection.get(
                where={"doc_id": {"$in": list(doc_ids)}},
                include=["metadatas"],
            )
        except Exception:  # noqa: BLE001 - 集合为空或过滤器不匹配时返回空即可
            return []

        seen: set[str] = set()
        out: list[dict[str, Any]] = []
        for meta in result.get("metadatas") or []:
            if not meta:
                continue
            doc_id = meta.get("doc_id")
            if doc_id in seen:
                continue
            seen.add(doc_id)
            out.append(meta)
        return out

    def query(self, question: str, top_k: int, where: dict | None = None) -> list[Hit]:
        """混合检索：向量召回 ∪ BM25 召回 → **RRF 融合排序**。

        为什么要两个通道而不是只留 BM25：BM25 是词法匹配，遇到完全换一种说法的
        问句（用户的话和语料一个字都不重合）会落空，向量通道能靠语义相似度
        拉回候选。

        **为什么排序用 RRF 而不是 BM25 单边**（这是实测改过来的，不是偏好）：

        分工的原始依据是"向量分数受文档长度影响太大，实测比 BM25 差 6 条"——
        那是**离线 ngram 实现**的结论。换成厂商语义嵌入（BAAI/bge-m3）后，
        通道消融实测（26 条用例，见 docs/验收记录-M3.md）：

        | 策略 | top1 | top5 |
        |---|---|---|
        | BM25 单边（旧） | 20/26 | 26/26 |
        | 纯向量 | 22/26 | **25/26** |
        | **RRF 融合（现）** | **23/26** | **26/26** |
        | 加权归一化 w_bm25=0.3/0.5/0.7 | 22/21/19 | 26/26 |

        纯向量的 top1 更高，但 **top5 掉了一条**——top5 决定喂给模型的上下文，
        不能降。加权归一化随权重单调变差，说明 BM25 归一化分与余弦**本来就不可比**，
        硬加权只是凭经验调。RRF 只用名次、不用分数，所以两个指标都不劣且 top1 更高。

        `Hit.score` 仍是 BM25 归一化分（前端"首条相关度"的口径没变）——
        实测 RRF 排序后首条的 BM25 归一化分落在 19%~78%、**没有一条为 0**，
        所以展示口径不需要跟着改。`Hit.fusion` 才是排序键。

        候选池 = 向量 top-pool ∪ BM25 top-pool。当前语料只有 83 片、pool 取 20，
        实测与全量 BM25 的 top5 命中完全一致；语料长大后 pool 才真正起到限流作用。
        """
        if self.count() == 0 or not self.lexical.chunks:
            return []

        pool = max(top_k * 4, MIN_POOL)
        raw_scores = self.lexical.raw_scores(question)
        ceiling = self.lexical.score_ceiling(question)

        # 两路通道各自的名次（名次从 1 起）。
        # RRF 只用名次、不用分数，这样就不必去解决"BM25 分和余弦怎么放到同一个
        # 量纲"这个本来就没有正确答案的问题 —— 加权方案实测随权重单调变差。
        bm25_order = [
            i
            for i in sorted(range(len(raw_scores)), key=lambda i: -raw_scores[i])
            if raw_scores[i] > 0
        ][:pool]

        fusion: dict[int, float] = {}
        for rank, index in enumerate(bm25_order, 1):
            fusion[index] = fusion.get(index, 0.0) + 1.0 / (RRF_K + rank)

        # 向量侧按返回顺序即名次（_vector_candidates 保证相似度降序）
        vector_scores: dict[str, float] = {}
        for rank, (chunk_id, similarity) in enumerate(
            self._vector_candidates(question, pool, where), 1
        ):
            vector_scores[chunk_id] = similarity
            index = self._index_of_chunk.get(chunk_id)
            if index is not None:
                fusion[index] = fusion.get(index, 0.0) + 1.0 / (RRF_K + rank)

        # 同一篇文档只保留融合分最高的切片
        hits: list[Hit] = []
        seen_docs: set[str] = set()
        for index in sorted(fusion, key=lambda i: -fusion[i]):
            chunk = self.lexical.chunks[index]
            if chunk.doc_id in seen_docs:
                continue
            seen_docs.add(chunk.doc_id)
            raw = raw_scores[index]
            hits.append(
                Hit(
                    chunk_id=chunk.chunk_id,
                    doc_id=chunk.doc_id,
                    text=chunk.text,
                    # ceiling 理论上不会为 0（查询词全不在语料时 raw 也都是 0），
                    # 但除法不该依赖"理论上"，留一个显式分支
                    score=min(1.0, raw / ceiling) if ceiling else 0.0,
                    raw=raw,
                    vector_score=vector_scores.get(chunk.chunk_id, 0.0),
                    fusion=fusion[index],
                    metadata=_flatten_metadata(chunk),
                )
            )
            if len(hits) >= top_k:
                break
        return hits

    def _vector_candidates(
        self, question: str, pool: int, where: dict | None
    ) -> list[tuple[str, float]]:
        """向量通道的召回。**返回顺序按相似度降序**（Chroma 的 query 按距离升序
        返回，这里保持它给的顺序），调用方直接把这个顺序当名次，不要再排一次。

        集合为空或维度不符时静默退化为"没有候选"，让检索仍然能靠 BM25 出结果，
        而不是整个请求失败 —— 但失败会记一条日志，见 `_warn_vector_failure`。"""
        try:
            vector = self.embedder.embed(question, restrict_vocab=True)
            result = self._collection.query(
                query_embeddings=[vector],
                n_results=min(pool, max(self.count(), 1)),
                where=where or None,
                include=["distances"],
            )
        except Exception as exc:  # noqa: BLE001 - 向量通道是增益项，失败不该拖垮检索
            _warn_vector_failure(exc)
            return []

        ids = result.get("ids") or [[]]
        distances = result.get("distances") or [[]]
        return [
            (chunk_id, 1.0 - float(distance))
            for chunk_id, distance in zip(ids[0], distances[0])
        ]

    def topic_gaps(self, question: str) -> list[str]:
        """知识库里完全没写过的问题主题。非空即表示应当回答"没有相关记载"。"""
        return self.lexical.topic_gaps(question)


def _count_by_type(chunks: Sequence[Chunk]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for chunk in chunks:
        counts[chunk.doc_type] = counts.get(chunk.doc_type, 0) + 1
    return counts


def _count_by_source_kind(chunks: Sequence[Chunk]) -> dict[str, int]:
    """按来源性质统计。写进 manifest 是为了让"有多少文档真有可核对的具体页面"
    变成一个能一眼看到的数字，而不是要翻 80 条记录才发现全指向同一个首页。
    """
    counts: dict[str, int] = {}
    for chunk in chunks:
        counts[chunk.source_kind] = counts.get(chunk.source_kind, 0) + 1
    return counts
