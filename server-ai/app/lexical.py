"""词法检索：BM25 打分 + 主题缺口判定。

**打分，不是排序。** 排序由 store.query() 的 RRF 融合负责（它同时看 BM25 名次
与向量名次）。这里只提供每个切片的 BM25 原始分与归一化上限，
"谁排前面"是上层的事 —— 早先这里有一个 `rank()` 自己排序，随 RRF 落地已删除。

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

有效的信号是另一个：**查询里的主题词在语料中是否完全不存在**。
"恐龙""护照""地铁""房价""签证""机票""上市公司"这些词一个都不在语料里，
而"汉中""气候""朱鹮""茶园"都在。判定规则见 `topic_gaps`。
实测：26 条正例 0 条误拒，10 条反例 0 条漏过，5 条推荐问题全部放行
（脚本 `scripts/eval_retrieval.py`，**退出码即结论**）。

判据演进过两轮，都是被真实缺陷逼出来的：

· **第一轮**：原先只用"2 字子串是否命中"来放过已知词组合，结果"上市公司"
  的 2 字子串里有"市公"，恰好来自语料中"城市公共…"这类文字，整词被当成
  已知词组合放过 —— 反例漏过 1 条。改成"能否拆成两个语料里都出现过的词"
  之后漏过归零（见 `_composed_of_known`）。
· **第二轮**：加词性过滤。系统首屏推荐的
  「汉中的气候怎么样，什么季节去最合适？」被拒答 —— jieba 把"最合适"
  标成 a（形容词），语料里当然没有这个词。形容词/代词/数词/时间词缺失
  只说明**问法**，不说明知识库有缺口（见 `NON_TOPIC_FLAGS`）。
  这一条当时没被 26 条用例测出来，因为用例里没有"推荐问题"那种问法 ——
  所以 `eval_retrieval.py` 补了第三段专门守推荐问题。
· **第三轮**：放过"领域词被虚词粘住"产生的碎片（见 `_glued_with_function_word`）。
  语料从 83 片扩到 151 片后，新写的地质、茶、行程三篇反而被自己的缺口判定挡住：
  jieba 把「汉中天坑群在哪里」切成 `…天坑 / 群在 / 哪里`，把「午子仙毫产自哪里」
  切成 `午子 / 仙 / 毫产自 / 哪里` —— "群在""毫产自"语料里当然没有，
  但它们不是"没写过的主题"，是分词器不认识"天坑群""仙毫"、把它和后面的
  虚词粘在了一起。这一轮同时把"玩完""最佳"这类**问法用词**补进 `QUESTION_WORDS`。
"""

from __future__ import annotations

import math
from collections import Counter
from typing import Mapping, Sequence

from .aliases import expand
from .corpus import Chunk
from .embed import tokenize

# BM25 标准参数。k1 控制词频饱和速度，b 控制长度归一化强度。
# 用业界默认值，没有针对本项目调参——语料只有 151 片，调参容易过拟合到测试用例上。
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
        # 问法用词（2026-09-26 补）：它们描述"用户想怎么问"，不指向主题。
        # "汉中几天能玩完"里的"玩完"、别名表把"什么时候去"换成"最佳季节"后残留的
        # "最佳"，都只说明问法 —— 词性是 v/z，挡不住（v 里混着"滑雪""采摘"这类真主题词）。
        "玩完", "玩遍", "逛完", "最佳",
    }
)

# 词性上就不可能是"主题词"的标记。这些词缺失只说明**问法**，不说明知识库没写过。
#
# 为什么要有这一层：光靠 QUESTION_WORDS 补词是补不完的 —— "最合适""更方便"
# "更划算"是开放集合。词性是封闭集合，用它可以一次挡住一整类误判。
#
# 真实触发场景：系统自己推荐的第一个问题「汉中的气候怎么样，什么季节去最合适？」
# 被判定成"知识库未覆盖"而拒答，因为 jieba 把"最合适"标成 a（形容词）、
# 语料里当然没有这个词。推荐你问、又拒绝回答，是很刺眼的缺陷。
NON_TOPIC_FLAGS = frozenset(
    {
        "a", "ad", "an", "ag",      # 形容词：最合适、更方便
        "d", "df",                  # 副词：很、都
        "r", "rr", "rz", "ry",      # 代词：怎么、哪里、什么
        "m", "mq", "q", "mq",       # 数词与量词：多少、几家
        "t", "tg",                  # 时间词：今天、最近
        "c", "cc",                  # 连词：可以、而且
        "p", "pba", "pbei",         # 介词：从、把
        "u", "uj", "ul", "uz", "y",  # 助词与语气词：的、了、吗
        "e", "o", "h", "k", "x", "w", "f", "zg",
    }
)

# 可以和领域词"粘"在一起的虚词标记 —— 是 `NON_TOPIC_FLAGS` 的一个**子集**，
# 专供 `_glued_with_function_word` 剥离使用。
#
# 为什么不直接用 NON_TOPIC_FLAGS：那里面还有形容词(a)、数词量词(m/q)、时间词(t)，
# 剥掉它们会把**真缺口**放过去。反例集里的"三甲医院"就是例子 ——
# jieba 把它切成"三甲 / 医院"，而"三"的词性是 m，若允许剥离，
# 剩下的"甲"（df=0 但拆得出"甲"以外的已知片段）会被判成"不是缺口"，
# 于是"汉中有几家三甲医院"就漏过去了。动词(v)同理，不能剥：
# "滑雪""采摘""观鸟"都是 v，它们是主题词本身。
GLUE_FLAGS = frozenset(
    {
        "p", "pba", "pbei",          # 介词：在、把、被
        "r", "rr", "rz", "ry",       # 代词：自、其、哪里
        "u", "uj", "ul", "uz",       # 助词：的、地、得
        "y",                         # 语气词：吗、呢
        "d", "df",                   # 副词：也、都
        "c", "cc",                   # 连词：和、而且
        "e", "o", "w",               # 叹词、拟声、标点
    }
)


class LexicalIndex:
    """语料词表 + BM25。构建成本很低（83 片语料约几十毫秒），随服务启动重建。"""

    def __init__(self, chunks: Sequence[Chunk]) -> None:
        self.chunks = list(chunks)
        self.tfs = [Counter(tokenize(c.text)) for c in self.chunks]
        self.lens = [sum(tf.values()) for tf in self.tfs]
        self.avgdl = (sum(self.lens) / len(self.lens)) if self.lens else 1.0

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
        """每个切片的 BM25 原始分（与 self.chunks 同序）。

        这是给上层的**打分**接口：store.query() 用它算 BM25 名次，再与向量名次
        做 RRF 融合；score_ceiling() 用它归一化成展示用的百分比。

        曾经这里还有一个 `rank()` 做"纯 BM25 排序 + 文档去重"，随 RRF 落地
        已删除（它已无调用点）。纯 BM25 的对比数据保留在 docs/验收记录-M3.md。
        """
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

    # ---- 主题缺口 ----

    def topic_gaps(self, question: str) -> list[str]:
        """找出问题里"语料完全没写过"的主题词。

        判定一个词是缺口，要同时满足五条（后两条是"放过"，不满足任一条就放过）：

        1. 长度 >= 2 —— 单字多是虚词或分词碎片，不足以判断主题；
        2. 不在疑问词表里，**且词性不属于 NON_TOPIC_FLAGS** ——
           "多大""多久""最合适"缺失只说明问法，不代表知识库有缺口；
        3. 它自己不在语料里；
        4. 它也不是"已知词拼出来的新词"：能拆成两个语料里都出现过的词就放过。
           问"汉中盆地有多大"，"汉中盆地"整体没出现过，但拆成"汉中"+"盆地"
           都在语料里，属于覆盖范围；而"恐龙""地铁"怎么拆都拆不出来，是真缺口。
        5. 它也不是"领域词被虚词粘住"的碎片（见 `_glued_with_function_word`）。

        返回空列表表示"知识库大概率覆盖这个问题"。
        """
        expanded = expand(question)
        flags = _pos_flags(expanded)
        gaps: list[str] = []
        for word in _segment(expanded):
            if len(word) < 2 or word in QUESTION_WORDS:
                continue
            if flags.get(word, "") in NON_TOPIC_FLAGS:
                continue
            if self.df.get(word, 0) > 0:
                continue
            if _composed_of_known(word, self.df):
                continue
            if _glued_with_function_word(word, self.df, flags):
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


def _composed_of_known(word: str, df: Mapping[str, int]) -> bool:
    """word 能否拆成两段、且两段都在语料里出现过。

    用来放过"已知词组合成的新词"。比原来"任一 2 字子串命中"更准：
    后者会被偶然出现的跨词片段骗过 —— "上市公司"的 2 字子串里有"市公"，
    它恰好来自语料中"城市公共…"这类文字，于是整词被当成已知词组合放过，
    而"上市公司"确实一个字都没在语料里出现过。
    实测这一改动把反例漏过从 1 条降到 0 条，且不影响 26 条正例。
    """
    # 从 2 开始：左侧至少 2 字，才有意义（单字多是碎片）
    for i in range(2, len(word) - 1):
        if df.get(word[:i], 0) > 0 and df.get(word[i:], 0) > 0:
            return True
    return False


def _glued_with_function_word(
    word: str, df: Mapping[str, int], flags: Mapping[str, str]
) -> bool:
    """word 是不是"领域词 + 尾部虚词"被 jieba 粘成的一串。

    实测触发的两例（语料扩到 151 片后暴露的）：

        「汉中天坑群在哪里」  -> …天坑 / **群在** / 哪里
        「午子仙毫产自哪里」  -> 午子 / 仙 / **毫产自** / 哪里

    "群在""毫产自"在语料里当然不存在，但它们**不是"没写过的主题"** ——
    是分词器不认识"天坑群""仙毫"这两个领域词，把它和紧跟其后的虚词
    （在/p、自/r）切在了一起。真正的缺口（"地铁""房价""恐龙"）不满足这个形状：
    它们的尾字是实词（铁/n、价/n、龙/n），剥不出虚词来。

    判据只有两步：
    1. **只剥尾部**，且只剥 `GLUE_FLAGS` 里的虚词标记。
       不剥首字：反例"三甲医院"的首字"三"是数词(m)，剥掉就会漏过真缺口。
    2. 剥剩下的核心必须"在语料里说得通"：整块是已知词，或能拆成两块已知词。

    保守取向没变 —— 这是**放过**条件，剥不出虚词、或核心对不上，就仍然算缺口。
    """
    if len(word) < 2:
        return False

    core = word
    while core and flags.get(core[-1], "") in GLUE_FLAGS:
        core = core[:-1]
    if core == word or not core:
        # 一个虚词都没剥下来，或整串都是虚词 —— 都不是"粘住领域词"的形状
        return False

    if df.get(core, 0) > 0:
        return True
    # 核心可以是"两个已知词拼起来的"（"毫产" -> "毫"+"产"）。
    # 这里允许 i=1，与 _composed_of_known 不同 —— 因为能走到这一步，
    # 前提是这串词已经以虚词结尾，形状已经很具体了。
    for i in range(1, len(core)):
        if df.get(core[:i], 0) > 0 and df.get(core[i:], 0) > 0:
            return True
    return False


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


def _pos_flags(text: str) -> dict[str, str]:
    """词 -> 词性。单独用 posseg 跑一遍，**只借词性，不用它的词边界**。

    为什么不直接把 _segment 换成 posseg.cut：实测两者词边界并不完全一致
    （22 条问句里 3 条不同，且 posseg 会把领域词切碎 —— "汉中仙毫"切成"仙""毫"）。
    换实现会连带改变"什么算缺口"的判定，而那是已经验收过的行为，不能顺手改。

    所以边界仍由 _segment 决定；posseg 切不出来的词在字典里查不到，
    按"可能是主题词"处理（保守，宁可多判一个缺口也不放过真缺口）。
    """
    import jieba.posseg as pseg

    out: dict[str, str] = {}
    for pair in pseg.cut(text):
        word = pair.word.strip().lower()
        if word:
            out.setdefault(word, pair.flag)
    return out


def warm_up() -> None:
    """预热 jieba 词典。放在服务启动时调用，避免第一个请求承担建词典的耗时。"""
    _segment("预热")
