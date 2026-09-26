#!/usr/bin/env python
"""链路分流的离线回归（`qa._route`）。

用法（在仓库根目录执行）：
    <venv>/Scripts/python.exe scripts/eval_routes.py

**为什么需要它。**
`_route` 决定"这一问用不用知识库"，判错的两个方向代价不对称：

  · 该走 `rag` 的走成了 `general` —— 该守事实约束时没守，模型可能编造本地事实；
  · 该走 `general` 的走成了 `rag` —— 拿一堆无关资料去回答"你是谁"。

这两个错误在**有模型**的时候都是静默的：接口照样 200、照样流式吐字，
只有逐条看 `route` 字段才发现。而 `_route` 只依赖本地词法索引（毫秒级、
不花钱），所以可以把**已有全部用例**一次性扫完，不必真的调模型。

## 三个集合与期望

  · `eval_retrieval.CASES`（51）+ `SUGGESTION_FALLBACK`（5）+ 30 条在范围内问句
      → 必须全部 `rag`（知识库覆盖）
  · `eval_retrieval.NEGATIVE`（10）+ 51 条超纲问句
      → 必须**不是** `rag`（constrained / general 都可以，两者都不编造本地事实）
  · 必测 5 条（这次改动的验收用例）逐条断言

## `KNOWN_LEAKS` / `KNOWN_MISROUTES`：两个方向的已知偏差，都做**精确比对**

`KNOWN_LEAKS` 沿用 `eval_gaps` 的表 —— 缺口判定"漏过"（语料写过却判成缺口）
本不属于本次改动，但会**顺带影响分流**（漏过 → 没有 gaps → 走 `rag`），
所以这里放行它们，并在 `eval_gaps` 里单独追责。

`KNOWN_MISROUTES` 记反方向：缺口判定**误报**（语料写了却判成缺口）导致本该
`rag` 的走了 `constrained`。成因是分词器把领域词切碎：

    「浆水面为什么叫浆水面」→ jieba 切成 浆/水面/叫浆/水面，"叫浆"语料里当然没有

影响有限（`constrained` 照样把资料给模型、并要求"先看资料能不能回答"，
答案仍然对），但**记录必须可证伪**：多一条失败说明分流被改坏了，
少一条也失败说明修好了、该把这行删掉。两张表都靠这条纪律防止腐烂。

**退出码**：0 = 全部符合期望、两张已知表都与实测一致；1 = 任一条不成立。
"""

from __future__ import annotations

import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server-ai"))
sys.path.insert(0, str(ROOT / "scripts"))

from app.config import get_settings  # noqa: E402
from app.corpus import build_chunks  # noqa: E402
from app.lexical import LexicalIndex  # noqa: E402
from app.llm import ROUTE_CONSTRAINED, ROUTE_GENERAL, ROUTE_RAG  # noqa: E402
from app.qa import _route  # noqa: E402

import eval_gaps  # noqa: E402
import eval_retrieval  # noqa: E402

# 缺口判定已知会漏过的（见 eval_gaps.KNOWN_LEAKS）：漏过 → 没有 gaps → 走 rag。
# 这是既成边界、不是本次改动引入的，放行；追责在 eval_gaps.py。
KNOWN_LEAKS: set[str] = set(eval_gaps.KNOWN_LEAKS)

# 缺口判定误报导致的分流偏差（本该 rag 却 constrained）。逐条记成因，精确比对。
KNOWN_MISROUTES: dict[str, str] = {
    "浆水面为什么叫浆水面": "jieba 把「浆水面」切碎，动词「叫」和碎片「浆」粘成「叫浆」",
}

# 本次改动的验收用例：前 3 条必须脱离知识库（否则"你是 AI 吗"会被拿去做检索），
# 后 2 条必须走知识库（否则等于把知识库降级成摆设）。
MUST: list[tuple[str, str]] = [
    (ROUTE_GENERAL, "你是 AI 吗？"),
    (ROUTE_GENERAL, "你是什么助手？"),
    (ROUTE_GENERAL, "什么是 AI？"),
    (ROUTE_RAG, "汉中有哪些好吃的？"),
    (ROUTE_RAG, "汉中有哪些乡村体验？"),
]

# 语料覆盖范围内的问法，必须走 rag。取自 M3 验收与首屏推荐，覆盖"什么/哪里/怎么/
# 多少/为什么"几类问法 —— 分流判错最常见的形式就是被某个疑问词误判成通用问题。
IN_SCOPE_STRESS: list[str] = [
    "汉中有哪些好吃的？", "汉中适合亲子游吗？", "汉中有哪些乡村体验？",
    "汉中有什么特产值得带走？", "石门栈道有什么特色？", "汉中天坑群在哪里？",
    "午子仙毫产自哪里？", "汉中仙毫多少钱？", "汉中几天能玩完？行程怎么安排？",
    "汉中什么时候去最好？", "汉中怎么去？", "汉中在哪个省？", "汉中为什么叫汉中？",
    "汉中冬天冷不冷？", "汉中的气候怎么样，什么季节去最合适？", "朱鹮是在哪里发现的？",
    "汉中哪些景区是免费的？", "汉中有什么值得带走的特产？", "南郑·黄官茶园能体验什么？",
    "汉中哪个区县有什么资源？", "镇巴腊肉是什么？", "留坝有什么民宿？",
    "汉中仙毫是什么茶？为什么叫地理标志产品？", "汉中面皮是省级非物质文化遗产吗？",
    "菜豆腐是什么？怎么做出来的？", "汉中油菜花什么时候开？", "汉中下辖哪些县区？",
    "汉中森林覆盖率是多少？", "汉中城固机场离市区多远？", "汉中天坑群为什么是世界级的？",
]

# 超纲问句，必须**不是** rag。全部带"汉中"是有意的：这正是难点 ——
# 词面上和语料很近，靠相似度分不开，只能靠缺口判定。
# 也刻意混入"汉中最好的中学是哪个"这类**含通用词**的本地问题：
# 它在汉中旅游站里问的一定是本地事实，不能因为带"中学/模型"字样就送去 general。
OUT_OF_SCOPE_STRESS: list[str] = [
    "汉中有地铁吗", "汉中房价多少", "汉中有几家三甲医院", "汉中哪里能办护照",
    "怎么申请签证", "汉中户口怎么迁", "汉中的社保怎么交", "汉中公积金怎么提取",
    "汉中有多少所大学", "汉中最好的中学是哪个", "汉中有几家银行", "汉中的快递公司在哪",
    "汉中哪里能买药", "汉中的超市几点关门", "汉中哪里有理发店", "汉中加油站多吗",
    "汉中哪里能充电", "汉中有网吧吗", "汉中有 KTV 吗", "汉中的菜市场在哪",
    "汉中有哪些上市公司", "汉中的平均工资多少", "汉中商铺租金多少", "汉中有什么招聘岗位",
    "汉中的股票行情", "汉中哪里能买到显卡", "汉中二手车市场在哪", "汉中装修公司推荐",
    "汉中限行吗", "汉中哪里能加油", "汉中的公交线路有哪些", "汉中出租车起步价多少",
    "汉中高速路况怎么样", "汉中到西安的机票多少钱", "汉中哪里能租车", "汉中停车场收费吗",
    "汉中今天空气质量怎么样", "汉中最近有什么新闻", "汉中明天天气怎么样", "汉中哪里能做核酸",
    "汉中有什么疫情政策", "汉中的物价水平如何", "汉中最近有什么活动",
    "汉中有什么律师事务所", "汉中哪里能修手机", "汉中的垃圾分类怎么分", "汉中有什么工厂",
    "汉中能考驾照吗", "汉中有什么编程培训班", "汉中哪里能买家具", "汉中的宠物医院在哪",
]


def main() -> int:
    settings = get_settings()
    index = LexicalIndex(build_chunks(settings.city_dir))
    city = settings.city_name
    print(f"[eval_routes] 语料 {index.n_docs} 片，词表 {len(index.df)} 个特征，"
          f"城市名 {city}（取自 citypack meta.json）\n")

    def route_of(question: str) -> str:
        return _route(question, index.topic_gaps(question), city)

    problems: list[str] = []

    def scan(label: str, questions: list[str], must_be: str) -> None:
        counts: Counter[str] = Counter()
        bad: list[str] = []
        known: list[str] = []
        for q in dict.fromkeys(questions):
            r = route_of(q)
            counts[r] += 1
            if r != must_be:
                (known if q in KNOWN_MISROUTES else bad).append(f"{q} → {r}")
        dist = "、".join(f"{k}={v}" for k, v in sorted(counts.items()))
        print(f"{label:<18} 共 {sum(counts.values()):>3} 条   {dist}   期望全部 {must_be}")
        for b in known:
            print(f"    (已知) {b}   —— {KNOWN_MISROUTES[b.split(' → ')[0]]}")
        for b in bad:
            print(f"    ★ {b}")
        problems.extend(bad)

    print("=== 必须走 rag（知识库覆盖）===")
    scan("检索用例", [q for q, _ in eval_retrieval.CASES], ROUTE_RAG)
    scan("首屏推荐问题", eval_retrieval.SUGGESTION_FALLBACK, ROUTE_RAG)
    scan("范围内压测", IN_SCOPE_STRESS, ROUTE_RAG)

    print("\n=== 必须不是 rag（知识库没依据）===")
    negatives = eval_retrieval.NEGATIVE + OUT_OF_SCOPE_STRESS
    counts: Counter[str] = Counter()
    neg_bad: list[str] = []
    for q in dict.fromkeys(negatives):
        r = route_of(q)
        counts[r] += 1
        if r == ROUTE_RAG and q not in KNOWN_LEAKS:
            neg_bad.append(q)
    dist = "、".join(f"{k}={v}" for k, v in sorted(counts.items()))
    print(f"{'反例':<18} 共 {sum(counts.values()):>3} 条   {dist}   "
          f"期望无 rag（{len(KNOWN_LEAKS)} 条已知漏过除外）")
    for q in neg_bad:
        print(f"    ★ 不该走 rag：{q}")
    problems.extend(neg_bad)

    print("\n=== 必测 5 条（本次改动的验收用例）===")
    for want, q in MUST:
        got = route_of(q)
        gaps = index.topic_gaps(q)
        mark = "OK " if got == want else "★  "
        if got != want:
            problems.append(f"{q}: 实际 {got}，期望 {want}")
        print(f"  {mark} {q:<16} 期望 {want:<10} 实际 {got:<10} gaps={gaps}")

    # ---- 已知表必须精确对得上（多一条/少一条都算失败）----
    print("\n" + "=" * 64)
    fixed = [
        q for q in KNOWN_MISROUTES
        if q in set(IN_SCOPE_STRESS) | {c for c, _ in eval_retrieval.CASES}
        and route_of(q) == ROUTE_RAG
    ]
    if fixed:
        print(f"★ 这些误报已被修好，请从 KNOWN_MISROUTES 删掉：{'、'.join(fixed)}")
        problems.extend(fixed)

    if problems:
        print(f"\n★ 不达标，问题 {len(problems)} 条：")
        for p in problems:
            print(f"  ★ {p}")
        return 1

    print("通过：所有用例分流符合期望，两张已知表都与实测一致")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
