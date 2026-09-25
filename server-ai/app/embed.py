"""文本向量化。默认实现完全离线、可复现，不依赖任何外部模型文件。

为什么不用现成的中文句向量模型：

1. BGE 一类效果好的中文模型要 PyTorch，装上就是 GB 级依赖，单机演示没必要；
2. Chroma 自带的 ONNX MiniLM 是英文语料训练的，中文效果一般，而且**首次使用要联网下载模型**
   —— 答辩现场断网就起不来；
3. 本项目语料只有百来段，检索要的是"能准确找到那一段"，不是开放域语义泛化。

所以默认用字符 n-gram 哈希向量：只用标准库 + numpy，完全离线，同一份语料任何时候
建出来的向量完全一致（可复现）。IDF 在构建期从语料统计并落盘，查询时复用。

**召回与排序的分工，取决于用哪种向量化实现——这条结论换实现后必须重测。**

- 离线 ngram 实现：余弦要除以文档模长，短文档命中两个无关特征就能赢过长文档
  命中五个真特征，所以纯余弦的 top1 命中**不如** BM25（当初实测 12 条 vs 18 条）。
- 厂商语义嵌入（实测 BAAI/bge-m3，1024 维）：纯向量 top1 命中 **22/26**，
  **反超** BM25 的 20/26，两通道互补 8 条。

所以"向量只召回、BM25 排序"这个分工在 ngram 下成立，在 bge-m3 下已经**不是最优**
（融合排序按互补关系估算可到 25/26）。**当前线上仍按 BM25 排序**——改排序会波及
全部检索结果，需要单独一轮验收。实测数据与建议见 docs/验收记录-M3.md。

配置了 LLM_EMBEDDING_MODEL 时可以换成厂商的 embedding 接口——两种实现的
signature 不同，collection 名会跟着变，所以不会把两个模型的向量混进同一个集合。
"""

from __future__ import annotations

import hashlib
import json
import math
import re
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Sequence

import numpy as np

from .aliases import expand, fingerprint as alias_fingerprint

DIM = 2048
NGRAM_SIZES = (1, 2, 3)

# 只保留中日韩文字、拉丁字母与数字。标点、空白、Markdown 记号对语义没有贡献，
# 却会在哈希空间里制造大量共现噪声，把真正区分文档的特征稀释掉。
_KEEP = re.compile(r"[\u4e00-\u9fff\u3400-\u4dbf\u3040-\u30ffa-zA-Z0-9]+")


def tokenize(text: str) -> list[str]:
    """切成字符 1/2/3-gram。

    中文没有空格分词。用字符 n-gram 既避开分词器依赖，也天然覆盖"汉中仙毫""采茶"
    这类固定搭配——2-gram 与 3-gram 会把它们当成整体特征，比单字匹配准得多。

    先过一遍别名表（`aliases.expand`），把口语与简称统一成语料里的规范写法。
    文档与查询走同一条归一化路径，所以"高铁"和"高速铁路"会落到同一个特征上。
    """
    out: list[str] = []
    for run in _KEEP.findall(expand(text)):
        if run.isascii():
            # 连续的字母数字整体作为一个特征，否则 "SCE001" 会被拆成无意义的单字符
            out.append(run.lower())
            continue
        n = len(run)
        for size in NGRAM_SIZES:
            if n < size:
                continue
            for i in range(n - size + 1):
                out.append(run[i : i + size])
    return out


def _bucket(term: str) -> tuple[int, float]:
    """把特征哈希到 [0, DIM) 并给出正负号。

    用 blake2b 而不是内置 hash()：内置 hash() 对字符串带进程级随机盐，
    同一个词在不同进程里会落到不同桶，向量就不可复现了。
    带符号哈希（signed hashing）能抵消一部分哈希碰撞带来的系统性偏移。
    """
    digest = hashlib.blake2b(term.encode("utf-8"), digest_size=8).digest()
    value = int.from_bytes(digest, "big")
    index = value % DIM
    sign = 1.0 if (value >> 63) & 1 else -1.0
    return index, sign


def _l2(vec: np.ndarray) -> np.ndarray:
    norm = float(np.linalg.norm(vec))
    return vec / norm if norm > 0 else vec


@dataclass
class NgramEmbedder:
    """字符 n-gram + IDF 的向量空间模型。"""

    idf: dict[str, float] = field(default_factory=dict)
    n_docs: int = 0

    @property
    def signature(self) -> str:
        """写进 collection 名，保证换实现时不会和旧向量混用。

        带上别名表指纹：别名参与分词，改一条别名就等于换了特征空间，
        旧向量与旧 IDF 全部作废。签名一变，集合名跟着变，
        `NgramEmbedder.load` 也会直接报错要求重建，不会拿旧向量当新结果用。
        """
        return f"ngram{DIM}x{''.join(str(s) for s in NGRAM_SIZES)}-{alias_fingerprint()}"

    @classmethod
    def fit(cls, texts: Sequence[str]) -> "NgramEmbedder":
        """从语料统计 IDF。语料只有百来段，全量统计一次的成本可以忽略"""
        n = len(texts)
        df: Counter[str] = Counter()
        for text in texts:
            df.update(set(tokenize(text)))
        idf = {term: math.log((n + 1) / (d + 1)) + 1.0 for term, d in df.items()}
        return cls(idf=idf, n_docs=n)

    def _default_idf(self) -> float:
        """语料里没出现过的词按只出现在 1 篇文档处理，给最高权重"""
        return math.log((self.n_docs + 1) / 2) + 1.0

    def embed(self, text: str, *, restrict_vocab: bool = False) -> list[float]:
        """把文本变成单位向量。

        `restrict_vocab=True` 用于**查询侧**，只保留语料词表里存在的特征。
        这一步不是调参技巧，而是向量空间模型的正确语义：文档定义了这个空间的词表，
        词表外的特征在空间里根本不存在，参与计算只会污染方向。

        实际影响很大：像"怎么坐高铁去汉中"这种口语化问句，字符 n-gram 会产生
        "怎么坐""坐高铁""高铁去""去汉中"等大量跨词片段，它们在语料里一个都不存在，
        却各自拿到最高 IDF，合起来把查询向量的模长撑大，真正有信号的"高""铁""汉中"
        反而被稀释，相似度排序直接错掉。

        词表内一个特征都没有时（问的是知识库里完全没写过的东西），退化为不限制，
        这样仍能返回一批候选，由回答层去说明"没有直接相关的资料"——比返回空列表诚实。
        """
        counts = Counter(tokenize(text))
        default = self._default_idf()

        if restrict_vocab:
            known = {term: c for term, c in counts.items() if term in self.idf}
            if known:
                counts = Counter(known)

        vec = np.zeros(DIM, dtype=np.float32)
        for term, count in counts.items():
            index, sign = _bucket(term)
            # sublinear tf：一个词出现 10 次并不比出现 3 次重要 3 倍
            vec[index] += sign * (1.0 + math.log(count)) * self.idf.get(term, default)
        return _l2(vec).tolist()

    def embed_many(self, texts: Iterable[str]) -> list[list[float]]:
        return [self.embed(t) for t in texts]

    # ---- 持久化。IDF 必须和向量一起存，否则查询侧算出来的权重和索引侧不一致 ----

    def save(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps({"signature": self.signature, "n_docs": self.n_docs, "idf": self.idf}, ensure_ascii=False),
            encoding="utf-8",
        )

    @classmethod
    def load(cls, path: Path) -> "NgramEmbedder":
        payload = json.loads(path.read_text(encoding="utf-8"))
        if payload.get("signature") != cls().signature:
            raise ValueError(
                f"IDF 文件与当前向量化实现不匹配（文件 {payload.get('signature')} / 代码 {cls().signature}），"
                f"请重新构建知识库：{path}"
            )
        return cls(idf=payload["idf"], n_docs=payload["n_docs"])


@dataclass
class ApiEmbedder:
    """厂商 OpenAI 兼容 embedding 接口。配了 LLM_EMBEDDING_MODEL 才用。"""

    base_url: str
    api_key: str
    model: str
    timeout_s: float = 60.0
    _dim: int = 0

    @property
    def signature(self) -> str:
        # 维度建库时才知道，用模型名做签名已经足够区分
        safe = re.sub(r"[^a-zA-Z0-9._-]", "-", self.model)
        return f"api-{safe}"[:48]

    def embed_many(self, texts: Iterable[str]) -> list[list[float]]:
        import httpx

        out: list[list[float]] = []
        batch = list(texts)
        with httpx.Client(timeout=self.timeout_s) as client:
            for i in range(0, len(batch), 32):
                chunk = batch[i : i + 32]
                resp = client.post(
                    f"{self.base_url}/embeddings",
                    headers={"Authorization": f"Bearer {self.api_key}"},
                    json={"model": self.model, "input": chunk},
                )
                # 不能只靠 raise_for_status()：厂商把真正的原因（"模型不存在"
                # "余额不足""key 无效"）写在响应体里，而异常消息只带状态码。
                # 排错时最需要的就是那一行，所以这里把它拼进异常消息。
                if resp.status_code >= 400:
                    raise RuntimeError(
                        f"嵌入接口返回 {resp.status_code}（{self.base_url}/embeddings，"
                        f"model={self.model}）：{resp.text[:300]}"
                    )
                data = sorted(resp.json()["data"], key=lambda d: d["index"])
                out.extend([d["embedding"] for d in data])
        if out:
            self._dim = len(out[0])
        return out

    def embed(self, text: str, *, restrict_vocab: bool = False) -> list[float]:
        """单条向量化。签名与 `NgramEmbedder.embed` 对齐，供检索层统一调用。

        `restrict_vocab` 在这里是**接受但忽略**的：它表达的是"查询侧只保留语料
        词表里存在的特征"，那是字符 n-gram 向量空间模型才有的语义——厂商模型有
        自己的固定词表且不可枚举，没法照做（BGE-M3 这类模型本来也不需要）。

        为什么不让调用方干脆不传：检索层要用同一行代码调两种实现。这里少一个参数
        的代价是实打实的——`store._vector_candidates` 传了 `restrict_vocab=True`，
        于是配了厂商嵌入模型之后每次查询都 TypeError，又被检索层的"向量失败就降级"
        逻辑吞掉，**向量通道实际一次都没生效**，而表面上检索仍然"能用"
        （BM25 本来就负责排序）。直到做通道消融（纯向量 0/26）才暴露出来。
        """
        return self.embed_many([text])[0]


def create_embedder(settings, idf_path: Path):
    """按配置挑向量化实现。

    配了 LLM_EMBEDDING_MODEL + LLM_EMBEDDING_API_KEY 就用厂商的 embedding 接口，
    否则用离线实现。注意用的是 **llm_embedding_*** 那组凭证，不是 llm_* ——
    对话模型与嵌入模型是两家厂商（DeepSeek 没有 embeddings 端点）。

    离线实现需要从磁盘恢复 IDF——IDF 是构建期从语料统计出来的，
    查询侧必须用同一份，否则相似度不可比。
    """
    if settings.embedding_enabled:
        return ApiEmbedder(
            base_url=settings.llm_embedding_base_url,
            api_key=settings.llm_embedding_api_key,
            model=settings.llm_embedding_model,
            timeout_s=settings.llm_timeout_s,
        )
    if not idf_path.is_file():
        raise FileNotFoundError(f"缺少 IDF 文件 {idf_path}，请先构建知识库：python scripts/build_kb.py")
    return NgramEmbedder.load(idf_path)


def fit_embedder(settings, texts: Sequence[str]):
    """构建知识库时使用：API 模式直接用接口，离线模式从语料统计 IDF。"""
    if settings.embedding_enabled:
        return ApiEmbedder(
            base_url=settings.llm_embedding_base_url,
            api_key=settings.llm_embedding_api_key,
            model=settings.llm_embedding_model,
            timeout_s=settings.llm_timeout_s,
        )
    return NgramEmbedder.fit(texts)
