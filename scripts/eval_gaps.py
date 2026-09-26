#!/usr/bin/env python
"""缺口判据的边界压测（`topic_gaps`）。

用法（在仓库根目录执行）：
    <venv>/Scripts/python.exe scripts/eval_gaps.py
    <venv>/Scripts/python.exe scripts/eval_gaps.py -v   # 打印每个词的判定依据

**为什么在 `eval_retrieval.py` 之外还要单独一个脚本。**
`eval_retrieval.py` 里的反例只有 10 条，是"判据能不能拦住明显超纲的问题"；
本脚本用 51 条反例 + 30 条正例压同一个函数，问的是另一个问题：
**判据的边界到底在哪、边界之外还剩几条漏网。** 两者不能互相替代 ——
10 条反例证明不了 51 条里的另外 41 条也安全。

只依赖 `build_chunks` + `LexicalIndex`，不碰向量库与 API Key，秒级跑完。

## 两个集合

- `OUT_OF_SCOPE`（51 条）：语料里**没有写过**的主题，期望 `topic_gaps` 返回非空。
- `IN_SCOPE`（30 条）：语料覆盖范围内的问法，期望返回空 —— 这一半是防"改判据改到误拒"。

## `KNOWN_LEAKS`：已知漏网，且必须**精确**对得上

判据回答的是"语料**写过**这个主题没有"，不是"**答得上**没有"。一个词只要被
顺带提过一次（df=1）就算"写过"，于是这几条拦不住。它们的成因已逐条查清
（见下面每条后面的注释），属于**已知边界**，不是待修的 bug。

但"已知"必须是可以被证伪的：脚本会比对"实际漏过的集合"与 `KNOWN_LEAKS`，
**多一条就失败**（说明出现了新泄漏），**少一条也失败**（说明某条被修好了，
就该把它从这里删掉、并同步更新 docs/验收记录-M3.md 9.10）。这样这张表不会腐烂。

## 曾经想过的收紧方案：`df >= 2`（已否决，别重复试）

把第 3 条的"出现过"从 `df > 0` 提到 `df >= 2`，直觉上能干掉上面这些 df=1 的漏网。
**实测会误拒 4 条在覆盖范围内的问句**：

    "汉中是什么时候解放的"        「解放」 df=1
    "汉江发源于哪里"             「发源」 df=1
    "汉中面皮是省级非物质文化遗产吗"  「省级」 df=1
    "菜豆腐是什么？怎么做出来的？"    「出来」 df=1

语料确实写了这四件事，只是各写了一次。也就是说这个方向上没有安全的阈值，
只有"放过真缺口"与"误拒真覆盖"两种代价 —— 本项目选后者不可接受
（拒答自己答得上来的问题是演示里最刺眼的缺陷）。

**也不为迁就判据去改语料。** 上面 4 个词里 `公交`/`停车场`/`收费`/`新闻` 的 df
是 2026-09-26 扩充语料时从 0 抬到 1 的（`git archive 48519db~1` 对照实测），
但对应的句子（"机场大巴停靠点均为市区内公交车站站牌""18 个收费或免费景区"）
都是自然、有用的表达，删掉是把工具的局限转嫁成内容的损失。

**退出码**：0 = 无新泄漏、无误拒、`KNOWN_LEAKS` 与实测一致；1 = 上述任一条不成立。
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server-ai"))

from app.config import get_settings  # noqa: E402
from app.corpus import build_chunks  # noqa: E402
from app.lexical import LexicalIndex  # noqa: E402

# 语料里没有写过的主题。必须全部被拦下（`topic_gaps` 返回非空）。
# 分组只是为了读起来方便，判定不看分组。
OUT_OF_SCOPE: list[str] = [
    # 城市基础设施与公共服务
    "汉中有地铁吗", "汉中房价多少", "汉中有几家三甲医院", "汉中哪里能办护照",
    "怎么申请签证", "汉中户口怎么迁", "汉中的社保怎么交", "汉中公积金怎么提取",
    "汉中有多少所大学", "汉中最好的中学是哪个", "汉中有几家银行", "汉中的快递公司在哪",
    "汉中哪里能买药", "汉中的超市几点关门", "汉中哪里有理发店", "汉中加油站多吗",
    "汉中哪里能充电", "汉中有网吧吗", "汉中有 KTV 吗", "汉中的菜市场在哪",
    # 经济与市场
    "汉中有哪些上市公司", "汉中的平均工资多少", "汉中商铺租金多少", "汉中有什么招聘岗位",
    "汉中的股票行情", "汉中哪里能买到显卡", "汉中二手车市场在哪", "汉中装修公司推荐",
    # 交通与出行细节（语料只有高铁与机场）
    "汉中限行吗", "汉中哪里能加油", "汉中的公交线路有哪些", "汉中出租车起步价多少",
    "汉中高速路况怎么样", "汉中到西安的机票多少钱", "汉中哪里能租车", "汉中停车场收费吗",
    # 时事与实时
    "汉中今天空气质量怎么样", "汉中最近有什么新闻", "汉中明天天气怎么样", "汉中哪里能做核酸",
    "汉中有什么疫情政策", "汉中的物价水平如何", "汉中最近有什么活动",
    # 与文旅无关的领域
    "汉中有什么律师事务所", "汉中哪里能修手机", "汉中的垃圾分类怎么分", "汉中有什么工厂",
    "汉中能考驾照吗", "汉中有什么编程培训班", "汉中哪里能买家具", "汉中的宠物医院在哪",
]

# 语料覆盖范围内的问法。一条都不该被拒（`topic_gaps` 返回空）。
IN_SCOPE: list[str] = [
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

# 已知漏网的问题 -> 它凭什么被放过。**这张表必须与实测精确一致**（见模块 docstring）。
KNOWN_LEAKS: dict[str, str] = {
    "汉中的公交线路有哪些":
        "「公交线路」拆成「公交」+「线路」两个语料里有过的词 → 被 `_composed_of_known` 放过。"
        "「公交」df=1，出自 doc:12-aviation「机场大巴停靠点均为市区内公交车站站牌」——"
        "本次扩充新写的一句，扩充前 df=0。",
    "汉中高速路况怎么样":
        "「路况」df=4，出自 doc:02-climate「山区景区在暴雨与降雪时段需要特别留意路况」"
        "与 doc:27-seasons 的三处呼应。写的是「要留意路况」，不是「路况如何」。扩充前 df=1。",
    "汉中停车场收费吗":
        "「停车场」df=1（doc:11-tiankeng「建设服务保障点、停车场等设施」）、"
        "「收费」df=1（doc:20-scenic-guide「18 个收费或免费景区」）。两个词扩充前 df 都是 0。",
    "汉中最近有什么新闻":
        "「新闻」df=1，出自 doc:11-tiankeng「陕西省人民政府召开新闻发布会」。扩充前 df=0。",
    "汉中明天天气怎么样":
        "别名表把「天气」映射成「气候」（天气本身 df=0），而「气候」df=13 —— "
        "判据看到的是「气候」被写过。这是别名表的**设计行为**，不是缺口判据的漏洞："
        "问题被当成气候问题回答，答案本身站得住，只是答的不是「明天」。",
    "汉中最近有什么活动":
        "「活动」df=2，但两处都不是「活动/节庆」的意思：doc:05-zhu-huan「朱鹮活动区域」、"
        "doc:11-tiankeng「龙门山活动构造带」。属分词后的同形异义。扩充前 df=1。",
}


def main() -> int:
    parser = argparse.ArgumentParser(description="topic_gaps 边界压测")
    parser.add_argument("-v", "--verbose", action="store_true", help="打印每条的缺口词")
    args = parser.parse_args()

    index = LexicalIndex(build_chunks(get_settings().city_dir))
    print(f"[eval_gaps] 语料 {index.n_docs} 片，词表 {len(index.df)} 个特征\n")

    # ---- 反例：必须拦下 ----
    leaked: list[str] = []
    print(f"=== 反例（必须拦下，{len(OUT_OF_SCOPE)} 条）===")
    for question in OUT_OF_SCOPE:
        gaps = index.topic_gaps(question)
        if gaps:
            if args.verbose:
                print(f"OK {question:<26} 缺口：{'、'.join(gaps)}")
        else:
            leaked.append(question)
            known = "已知" if question in KNOWN_LEAKS else "★ 新增"
            print(f"{known} 漏过 {question}")

    new_leaks = [q for q in leaked if q not in KNOWN_LEAKS]
    fixed = [q for q in KNOWN_LEAKS if q not in leaked]
    print(f"漏过 {len(leaked)} 条：已知 {len(leaked) - len(new_leaks)} 条，新增 {len(new_leaks)} 条")

    # ---- 正例：必须放行 ----
    refused: list[tuple[str, list[str]]] = []
    print(f"\n=== 正例（必须放行，{len(IN_SCOPE)} 条）===")
    for question in IN_SCOPE:
        gaps = index.topic_gaps(question)
        if gaps:
            refused.append((question, gaps))
            print(f"★ 误拒 {question}  →  {'、'.join(gaps)}")
        elif args.verbose:
            print(f"OK {question}")
    if not refused:
        print(f"全部放行 {len(IN_SCOPE)}/{len(IN_SCOPE)}")

    # ---- 判定 ----
    print("\n" + "=" * 64)
    print(f"  反例漏过   {len(leaked):>2}/{len(OUT_OF_SCOPE)}   "
          f"（已知 {len(leaked) - len(new_leaks)}，新增 {len(new_leaks)}）")
    print(f"  正例误拒   {len(refused):>2}/{len(IN_SCOPE)}")
    if new_leaks:
        print(f"  ★ 新增漏过：{'、'.join(new_leaks)}")
        print("     这是判据被放松了。先查是不是语料新增了顺带提及，"
              "确认属于已知边界后再加进 KNOWN_LEAKS 并说明成因。")
    if fixed:
        print(f"  ★ 已被拦下（应从 KNOWN_LEAKS 移出）：{'、'.join(fixed)}")
        print("     判据被收紧了。请从 KNOWN_LEAKS 删除，并同步 docs/验收记录-M3.md 9.10。")
    if refused:
        print(f"  ★ 误拒了在覆盖范围内的问句：{'、'.join(q for q, _ in refused)}")
        print("     拒答自己答得上来的问题，是演示里最刺眼的缺陷 —— 优先修这个。")

    ok = not new_leaks and not fixed and not refused
    print(f"\n{'通过' if ok else '★ 不达标'}")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
