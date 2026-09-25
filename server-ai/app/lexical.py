"""词法检索：BM25 排序 + 主题缺口判定。

这个模块是 M3 检索质量的主要承担者。两个能力都建立在同一份语料统计上：

**BM25 排序**
为什么不是余弦（实测结论，26 条检索用例）：
余弦相似度要除以文档模长，而本项目的切片长度差异很大（city_doc 中位 646 个特征，
poi/product 只有 226/253）。短切片只要命中两个无关特征，就能赢过长切片命中五个真特征。
问"汉中在哪个省"，正确文档命中 5 个查询特征（汉/中/在/省/汉中），
错误文档只命中 4 个（中在/个/在/中）却因为两个罕见碎片权重高而排第一。
BM25 换成"对查询词逐项求和"，命中越多得分越高，再用 b 参数做长度归一化，两个毛病一起解决。

分词仍用字符 n-gram（`embed.tokenize`），实测优于 jieba 词级：
  · 字符 1/2/3-gram + BM25   top1 18/26  top5 25/26
  · jieba 词级 + BM25        top1 14/26  top5 23/26
  · jieba 词 + 字符 2-gram    top1 17/26  top5 24/26
原因是语料里有"汉中仙毫""午子仙毫""山茱萸"这类领域词，jieba 的通用词典会切错，
字符 n-gram 天然把它们当成整体特征。

**主题缺口判定**
用来回答"知识库里到底有没有这方面内容"。实测过的六个分数型判据（BM25 分、
top1/top2 比、余弦、IDF 覆盖率、缺失词 IDF 占比、最高命中 IDF）**全部无法区分**
正例与反例——因为反例（"汉中有地铁吗""汉中房价多少"）都带"汉中"，
词面上和语料很近，字符重合度根本不反映"有没有答案"。

有效的信号是另一个：**查询里的实词在语料中是否完全不存在**。
"恐龙""护照""地铁""房价""签证""机票""上市公司"这些词一个都不在语料里，
而"汉中""气候""朱鹮""茶园"都在。判定规则见 `topic_gaps`。
实测：26 条正例 0 条误拒，10 条反例 1 条漏过（"汉中有哪些上市公司"，
因为"公司"在语料里出现过，2 字子串判据把它当成了已知词组合）。
"""

from __future__ import annotations

import math
from collections import Counter
from dataclasses import dataclass, field
from typing import Sequence

from .aliases import expand
from .corpus import Chunk
from .embed import tokenize

# BM25 标准参数。k1 控制词频饱和速度，b 控制长度归一化强度。
# 用业界默认值，没有针对本项目调参——语料只有 83 片，调参容易过拟合到测试用例上。
K1 = 1.2
B = 0.75

# 疑问、语气、程度、指示类词。它们不指向具体主题，缺失不代表知识库有缺口。
# 这张表是判据的必要组成部分：没有它，"汉中盆地有多大"会因为"多大"不在语料里而被拒答。
QUESTION_WORDS = frozenset(
    {
        "什么", "什么样", "什么时候", "怎么", "怎样", "怎么样", "如何", "为什么", "为啥",
        "哪个", "哪些", "哪里", "哪儿", "多少", "多少钱", "多少只", "多大", "多久", "几个",
        "几家", "几号", "几点", "是否", "能否", "能不能", "有没有", "值得", "介绍", "告诉",
        "时候", "现在", "目前", "可以", "请问", "一下", "冷不冷", "热不热", "好不好", "多远",
        "多长", "多高", "好吃", "好喝", "好玩", "去哪", "在哪儿", "今天", "明天", "后天",
        "最近", "附近", "推荐", "怎么样", "多少公里", "几个小时",
    }
)


@dataclass
class Scored:
    """一条命中。score 是归一化到 [0,1] 的相关度，便于前端展示。"""

    doc_id: str
    chunk_id: str
    score: float
    raw: float
    metadata: dict = field(default_factory=dict)


class LexicalIndex:
    """语料词表 + BM25。构建成本很低（83 片语料约几十毫秒），随服务启动重建。"""

    def __init__(self, chunks: Sequence[Chunk]) -> None:
        self.chunks = list(chunks)
        self.tfs = [Counter(tokenize(c.text)) for c in self.chunks]
        self.lens = [sum(tf.values()) for tf in self.tfs]
        self.avgdl = (sum(self.lens) / len(self.lens)) if self.lens else 1.0
        self.doc_ids = [c.doc_id for c in self.chunks]
        self.chunk_ids = [c.chunk_id for c in self.chunks]

        df: Counter[str] = Counter()
        for tf in self.tfs:
            df.update(tf.keys())
        self.df = df
        self.n_docs = len(self.chunks)
        self.idf = {
            term: math.log(1 + (self.n_docs - d + 0.5) / (d + 0.5)) for term, d in df.items()
        }

    # ---- 打分 ----

    def raw_scores(self, question: str) -> list[float]:
        query = Counter(tokenize(question))
        if not query:
            return [0.0] * len(self.chunks)

        out: list[float] = []
        for i, tf in enumerate(self.tfs):
            length_norm = K1 * (1 - B + B * self.lens[i] / self.avgdl)
            score = 0.0
            for term, _ in query.items():
                freq = tf.get(term)
                if not freq:
                    continue
                score += self.idf.get(term, 0.0) * freq * (K1 + 1) / (freq + length_norm)
            out.append(score)
        return out

    def score_ceiling(self, question: str) -> float:
        """查询理论上能达到的最高分，用来把 BM25 分归一化到 [0,1]。

        单个词的贡献上限是 idf*(k1+1)（词频趋于无穷时）。所以全部查询词都
        充分命中时的上限是 idf 之和乘以 (k1+1)。这是上界而非可达值，
        但对同一条查询是单调的，所以不影响排序，只影响展示出来的百分比。
        """
        query = Counter(tokenize(question))
        return (K1 + 1) * sum(self.idf.get(term, 0.0) for term in query) or 1.0

    def rank(self, question: str, top_k: int) -> list[Scored]:
        """按 BM25 排序，同一篇文档只保留最高分切片。"""
        raw = self.raw_scores(question)
        ceiling = self.score_ceiling(question)

        order = sorted(range(len(raw)), key=lambda i: -raw[i])
        best: dict[str, Scored] = {}
        for i in order:
            if raw[i] <= 0:
                break
            doc_id = self.doc_ids[i]
            if doc_id in best:
                continue
            best[doc_id] = Scored(
                doc_id=doc_id,
                chunk_id=self.chunk_ids[i],
                score=min(1.0, raw[i] / ceiling),
                raw=raw[i],
            )
        return list(best.values())[:top_k]

    def rank_all(self, question: str) -> list[Scored]:
        """不截断的完整排序，供候选池融合使用。"""
        return self.rank(question, len(self.chunks))

    # ---- 主题缺口 ----

    def topic_gaps(self, question: str) -> list[str]:
        """找出问题里"语料完全没写过"的实词。

        判定一个词是缺口，要同时满足三条：

        1. 长度 >= 2 —— 单字多是虚词或分词碎片，不足以判断主题；
        2. 不在疑问词表里 —— "多大""多久""什么样"缺失不代表知识库有缺口；
        3. 它自己不在语料里，**且它的 2 字子串也一个都不在** ——
           这一条用来放过"已知词组合成的新词"：问"汉中盆地有多大"，
           "汉中盆地"整体没出现过，但它由"汉中""盆地"组成，属于知识库覆盖范围；
           而"恐龙""地铁""护照"的 2 字子串同样不存在，是真的缺口。

        返回空列表表示"知识库大概率覆盖这个问题"。
        """
        gaps: list[str] = []
        for word in _segment(expand(question)):
            if len(word) < 2 or word in QUESTION_WORDS:
                continue
            if self.df.get(word, 0) > 0:
                continue
            sub_pairs = [word[i : i + 2] for i in range(len(word) - 1)]
            if any(self.df.get(pair, 0) > 0 for pair in sub_pairs):
                continue
            gaps.append(word)
        return gaps

    def coverage(self) -> dict[str, int]:
        """语料规模概览，给 /ai/health 用"""
        return {
            "chunks": self.n_docs,
            "terms": len(self.df),
            "avg_tokens": int(self.avgdl),
        }


def _segment(text: str) -> list[str]:
    """jieba 分词，只保留中文与字母数字。

    这里用 jieba 而检索用字符 n-gram，是刻意的分工：
    检索要的是"不漏"，字符 n-gram 对领域词更稳；
    判定缺口要的是"词边界准确"，否则跨词碎片（如"中在"）会被当成实词。
    jieba 是纯 Python 包、词典随包分发，完全离线，不引入模型下载。
    """
    import jieba

    out: list[str] = []
    for word in jieba.cut(text):
        word = word.strip()
        if not word:
            continue
        if any("\u4e00" <= ch <= "\u9fff" or ch.isalnum() for ch in word):
            out.append(word.lower())
    return out


def warm_up() -> None:
    """预热 jieba 词典。放在服务启动时调用，避免第一个请求承担建词典的耗时。"""
    _segment("预热")
