#!/usr/bin/env python
"""检索效果自检。

用法（在仓库根目录执行）：
    <venv>/Scripts/python.exe scripts/eval_retrieval.py
    <venv>/Scripts/python.exe scripts/eval_retrieval.py -v     # 打印每条的 top3

三件事一起验：

1. **能不能找对** —— 26 条有明确目标文档的问题，看目标文档排在第几。
   指标是 top1 与 top5，因为两者服务的场景不同：top1 决定摘录模式（无模型时）
   给出的第一段原文，top5 决定喂给模型的上下文。
2. **能不能承认找不到** —— 10 条语料完全没写过的问题，看主题缺口判定是否拦下。
3. **会不会拒答自己推荐的问题** —— 页面首屏摆给用户点的快捷问题必须能答。
   这一段是补上来的：曾发生推荐问题「汉中的气候怎么样，什么季节去最合适？」
   被判成"知识库未覆盖"而拒答（jieba 把"最合适"标成形容词，语料里当然没有），
   而当时前两段用例里没有一条是推荐问题的写法，所以完全没测出来。
   **推荐你问、又拒绝回答**是最刺眼的缺陷，必须单独守住。

这三个指标必须一起看：只优化第一个会退化成"什么问题都敢答"，
只优化第二个会退化成"什么都答不出来"。

**退出码**：任一指标不达标返回 1，全绿返回 0。自检脚本要能失败，
否则它只是打印，不是检查。

结果口径见 docs/验收记录-M3.md。改了别名表、切片策略、缺口判据或语料之后要重跑。
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server-ai"))

from app.config import get_settings  # noqa: E402
from app.corpus import build_chunks  # noqa: E402
from app.embed import create_embedder  # noqa: E402
from app.llm import AnswerCache  # noqa: E402
from app.store import IDF_FILE, KbStore  # noqa: E402

# 问题 -> 期望命中的 doc_id。目标文档的选取标准是「这句话的答案主要出自哪一篇」，
# 不要求唯一：比如"朱鹮是哪年发现的"，朱鹮知识文档与朱鹮梨园都算相关，
# 这里取记载了发现年份的那一篇。
CASES: list[tuple[str, str]] = [
    ("汉中在哪个省", "doc:01-geography"),
    ("汉中的地形是什么样的", "doc:01-geography"),
    ("汉中盆地有多大", "doc:01-geography"),
    ("汉中下辖哪些县区", "doc:08-districts"),
    ("汉中是什么时候解放的", "doc:03-history"),
    ("汉中为什么叫汉中", "doc:03-history"),
    ("汉中森林覆盖率是多少", "doc:04-ecology"),
    ("汉江发源于哪里", "doc:04-ecology"),
    ("汉中天坑群", "doc:04-ecology"),
    ("朱鹮是哪年发现的", "doc:05-zhu-huan"),
    ("朱鹮现在有多少只", "doc:05-zhu-huan"),
    ("西成高铁什么时候开通的", "doc:06-transport"),
    ("西安到汉中坐高铁要多久", "doc:06-transport"),
    ("汉中有什么值得带走的特产", "doc:07-local-products"),
    ("汉中的矿产资源有哪些", "doc:07-local-products"),
    ("汉中茶叶产区在哪", "doc:07-local-products"),
    ("汉中油菜花什么时候开", "doc:02-climate"),
    ("汉中冬天冷不冷", "doc:02-climate"),
    ("汉中石门栈道是什么地方", "poi:P-SCE-001"),
    ("黄官茶园能体验什么", "poi:P-RUR-002"),
    ("留坝有什么民宿", "poi:P-RUR-012"),
    ("汉中仙毫多少钱", "prd:PRD-001"),
    ("镇巴腊肉", "prd:PRD-012"),
    ("汉中有什么好吃的", "poi:P-FOOD-001"),
    ("汉中哪里可以住宿", "poi:P-LOD-001"),
    ("汉中的非物质文化遗产", "exp:E-007"),
]

# 语料完全没有覆盖的主题。期望系统明确回答"没有相关记载"。
NEGATIVE: list[str] = [
    "有恐龙化石吗",
    "怎么办理护照",
    "汉中有地铁吗",
    "汉中房价多少",
    "怎么申请签证",
    "汉中有几家三甲医院",
    "汉中到西安的机票多少钱",
    "汉中有哪些上市公司",
    "汉中在哪里能买到显卡",
    "汉中今天空气质量怎么样",
]

# 页面首屏的快捷问题。正常情况下取自 cache/qa_demo.json 的 suggestions 字段，
# 缓存缺失时用这份兜底（与 qa.QaService.suggestions 的兜底保持一致）。
SUGGESTION_FALLBACK: list[str] = [
    "汉中的气候怎么样，什么季节去最合适？",
    "朱鹮是在哪里发现的？",
    "从西安怎么去汉中？",
    "汉中有什么值得带走的特产？",
    "南郑·黄官茶园能体验什么？",
]


def load_suggestions(settings) -> list[str]:
    """取快捷问题。与线上同一来源，避免"测的和演示的不是一批问题"。"""
    cache = AnswerCache(settings.cache_file)
    if cache.meta.get("suggestions"):
        return list(cache.meta["suggestions"])
    return SUGGESTION_FALLBACK


def main() -> int:
    parser = argparse.ArgumentParser(description="检索效果自检")
    parser.add_argument("-v", "--verbose", action="store_true", help="打印每条问题的 top3")
    args = parser.parse_args()

    settings = get_settings()
    chunks = build_chunks(settings.city_dir)
    embedder = create_embedder(settings, settings.vectorstore_dir / IDF_FILE)
    store = KbStore.open(settings.vectorstore_dir, embedder, chunks)

    if store.count() == 0:
        print(f"[eval] 知识库是空的，请先构建：python scripts/build_kb.py", file=sys.stderr)
        return 2

    print(f"[eval] 语料 {len(chunks)} 片 / {len({c.doc_id for c in chunks})} 篇，"
          f"向量 {store.count()} 条，集合 {store.collection_name}")

    hit1 = hit5 = 0
    misses: list[str] = []
    print(f"\n=== 检索命中（{len(CASES)} 条）===")
    for question, want in CASES:
        hits = store.query(question, 5)
        ids = [h.doc_id for h in hits]
        rank = ids.index(want) + 1 if want in ids else 0
        if rank == 1:
            hit1 += 1
        if rank:
            hit5 += 1
        else:
            misses.append(question)
        mark = "OK " if rank == 1 else ("    " if rank else "!! ")
        detail = f"@{rank}" if rank else f"未命中 (top1={ids[0] if ids else '空'})"
        print(f"{mark}{question:<24} {detail}")
        if args.verbose:
            for i, h in enumerate(hits[:3], 1):
                print(f"      {i}. {h.score:.3f} {h.doc_id}  {h.metadata.get('title', '')}")

    print(f"\n=== 主题缺口判定（{len(NEGATIVE)} 条语料未覆盖的问题）===")
    blocked = 0
    leaked: list[str] = []
    for question in NEGATIVE:
        gaps = store.topic_gaps(question)
        if gaps:
            blocked += 1
            print(f"OK {question:<24} 判定超范围：{'、'.join(gaps)}")
        else:
            leaked.append(question)
            print(f"!! {question:<24} 未拦住，会去检索")

    # ---- 推荐问题：必须能答 ----
    suggestions = load_suggestions(settings)
    print(f"\n=== 推荐问题不应被拒答（{len(suggestions)} 条，取自演示缓存）===")
    refused: list[str] = []
    for question in suggestions:
        gaps = store.topic_gaps(question)
        if gaps:
            refused.append(question)
            print(f"!! {question:<28} ★被误判超范围：{'、'.join(gaps)}")
        else:
            print(f"OK {question:<28} 正常放行")

    total = len(CASES)
    print("\n=== 汇总 ===")
    print(f"  top1 命中   {hit1:>2}/{total}  ({hit1 / total * 100:.0f}%)")
    print(f"  top5 命中   {hit5:>2}/{total}  ({hit5 / total * 100:.0f}%)")
    print(f"  正确拒答    {blocked:>2}/{len(NEGATIVE)}  ({blocked / len(NEGATIVE) * 100:.0f}%)")
    print(f"  推荐问题放行 {len(suggestions) - len(refused):>2}/{len(suggestions)}")
    if misses:
        print(f"  未进 top5：{'、'.join(misses)}")
    if leaked:
        print(f"  漏过的反例：{'、'.join(leaked)}")
    if refused:
        print(f"  被误拒的推荐问题：{'、'.join(refused)}")

    bad = len(leaked) + len(refused)
    print(f"\n{'通过' if bad == 0 else '★ 不达标'}：反例漏过 {len(leaked)}，推荐问题误拒 {len(refused)}")
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
