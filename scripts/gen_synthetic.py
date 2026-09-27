#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""生成合成客流与经营数据，写入 citypack/<city>/visit_stats.json。

为什么需要这个脚本
------------------------------------------------------------------
本平台的"承载力联动分流"要能演示，就必须有客流数据。真实的景区客流
统计拿不到（也不该去抓商业平台），所以按方案第 17 节的约定：
**用规则仿真生成，脚本入库、可复现，并如实标记为合成数据。**

脚本把"数据长什么样"这件事从**前端**搬回**数据包**：
在此之前，承载率是 `web/src/mock/stats.ts` 在浏览器里按 POI id 现算的，
那是演示阶段的权宜之计——它意味着"换一份数据包，指标不会跟着变"，
而且 M5 的规则引擎在 Java 侧，读不到浏览器里的数字。
现在这份数字落进 citypack，与 pois.json 一样是数据包的一部分。

⚠️ 本脚本产出的所有数字都是**基于规则的仿真值，不是真实观测值**。
   区间与刻意留出的触发样本都是**设计选择**，目的是让规则引擎与
   演示脚本有可讲的东西，详见下面"区间是怎么定的"。

时间是怎么表达的（★ 关键设计）
------------------------------------------------------------------
数据包里存的是**距今天的天数偏移**（offset，0 = 今天，-59 = 59 天前），
不是绝对日期。绝对日期一旦写进数据包，跑一个月后"近 7 日"就全过期了；
偏移量由导入器在导入时物化成真实日期，演示永远新鲜。

offset 0 的 base 值等于按设计区间算出的承载率，因此"今日"看到的
承载水平就是设计区间本身；更早的日子叠加趋势与抖动。

**刻意不施加"周末系数"**。听起来更真实的做法是按真实星期给周末乘一个
放大系数，但那样会让**同一天跑演示得到不同的数字**：今天若是周末，
景区承载整体抬升，而规则 4「乡村闲置」要求乡村 < 20% —— 核心规则
就会时有时无。演示需要的是"每次跑都长一样"，不是"更像真实日历"。
7 日趋势图仍然有起伏（来自逐日抖动与 60 天趋势项），只是没有固定的一周节律。

用法
------------------------------------------------------------------
    python scripts/gen_synthetic.py                # 写数据包（默认汉中）
    python scripts/gen_synthetic.py --days 60
    python scripts/gen_synthetic.py --verify       # 只打印参考值，不写文件
    python scripts/gen_synthetic.py --city hanzhong

--verify 打印每个 POI 的 capacity_usage / today_visitors，
用于与前端 `web/src/mock/stats.ts` 的原实现逐值比对——
本文件里的 seed() 是那份 TS 的**精确移植**（含 Math.imul 与 >>> 的
32 位语义），比对通过才说明移植是忠实的。详见验收记录。
"""

from __future__ import annotations

import argparse
import io
import json
import os
import sys
from decimal import Decimal, ROUND_HALF_UP

# ----------------------------------------------------------------------
# 一、确定性伪随机种子 —— web/src/mock/hash.ts 的精确移植
#
# JS 的位运算是 32 位有符号整数语义，Python 的整数是任意精度，
# 所以每一步都要显式收敛回 32 位，否则数值会在几十步之后发散。
# ----------------------------------------------------------------------

_MASK32 = 0xFFFFFFFF


def _to_int32(x: int) -> int:
    """等价于 JS 的 `x | 0`：截断到 32 位并解释为有符号整数。"""
    x &= _MASK32
    return x - 0x100000000 if x >= 0x80000000 else x


def _imul(a: int, b: int) -> int:
    """等价于 JS 的 Math.imul(a, b)：32 位有符号乘法。"""
    return _to_int32((a & _MASK32) * (b & _MASK32))


def _ushr(h: int, n: int) -> int:
    """等价于 JS 的 `h >>> n`：先当无符号再看，结果非负。"""
    return (h & _MASK32) >> n


def _xor32(a: int, b: int) -> int:
    """等价于 JS 的 `a ^ b`：逐位异或后收敛回 32 位有符号。"""
    return _to_int32((a & _MASK32) ^ (b & _MASK32))


def _utf16_units(s: str) -> list[int]:
    """JS 的 charCodeAt 取的是 UTF-16 码元，不是码点。

    汉字都在 BMP 内，两种取法一致；但用 UTF-16 编码来取能顺带
    正确处理代理对（emoji 之类），免得以后有人往 id 里塞非 BMP 字符
    就静默算出一套不同的数。
    """
    b = s.encode("utf-16-le")
    return [b[i] | (b[i + 1] << 8) for i in range(0, len(b), 2)]


def _fnv1a(s: str) -> int:
    h = 2166136261
    for u in _utf16_units(s):
        h = _xor32(h, u)
        h = _imul(h, 16777619)
    return h & _MASK32


def _fmix32(h: int) -> int:
    h = _xor32(h, _ushr(h, 16))
    h = _imul(h, 2246822507)
    h = _xor32(h, _ushr(h, 13))
    h = _imul(h, 3266489909)
    h = _xor32(h, _ushr(h, 16))
    return h & _MASK32


def seed(*parts: object) -> float:
    """把任意多个片段散列成 [0,1) 的确定性随机数。

    逐段各自散列再混合，而不是拼成一个长串再散列 —— 原因见
    web/src/mock/hash.ts 的注释：FNV-1a 对"只有末尾不同"的输入
    区分度极差，'t0'/'t1'/'t2' 会落进同一个桶，表现为
    "7 天趋势是一条平线"。
    """
    h = 2166136261
    for p in parts:
        h = _fmix32(_xor32(h, _fnv1a(_js_str(p))))
    return _fmix32(h) / 4294967296


def _js_str(p: object) -> str:
    """等价于 JS 的 String(p)。

    只处理本项目会用到的类型；布尔要转成 'true'/'false' 而不是
    Python 的 'True'/'False'，否则同一个语义在两种语言里散出不同的数。
    """
    if isinstance(p, bool):
        return "true" if p else "false"
    return str(p)


def seed_int(lo: int, hi: int, *parts: object) -> int:
    """取 [lo, hi] 闭区间内的整数。"""
    return lo + int(seed(*parts) * (hi - lo + 1))


def _to_fixed(x: float, digits: int = 4) -> float:
    """等价于 JS 的 Number(x.toFixed(digits))。

    Python 的 round() 用的是银行家舍入（.5 向偶数靠），JS 的 toFixed
    是四舍五入，两者在恰好落在半格上时会差一个最低位。承载率要参与
    "是否超载"的阈值判定，差一个最低位就可能让一个边界点从触发变成
    不触发，所以这里显式用 ROUND_HALF_UP。
    """
    q = Decimal(1).scaleb(-digits)
    return float(Decimal(repr(float(x))).quantize(q, rounding=ROUND_HALF_UP))


# ----------------------------------------------------------------------
# 二、区间是怎么定的（★ 这些是设计选择，不是真实观测值）
#
# 本项目要演示的核心叙事是「热点景区超载 + 周边乡村闲置」。
# 如果各业态承载在同一个区间里均匀取值，这个失衡就看不出来，
# 规则引擎一条都不会触发，演示也就没有可讲的东西。
# 因此按业态设定不同区间。
# ----------------------------------------------------------------------

USAGE_RANGE: dict[str, tuple[float, float]] = {
    # 热点景区：区间上限 > 1 表示超载。18 个景区里约 3 个超载、
    # 约 7 个进入高位（≥80%）——真正挤的永远只有少数几个头部点位，
    # 比"半数以上高位"更接近真实。
    "SCENIC": (0.25, 1.15),
    # 乡村：整体宽裕，才有承接空间。
    #
    # ⚠️ 这里与前端旧 mock（web/src/mock/stats.ts 的 [0.22, 0.67]）**不同**。
    # 旧区间永远取不到 20% 以下，而方案第 15 节规则 4「乡村闲置」的
    # 判据是「景区 > 80% 且周边乡村 < 20%」—— 也就是说旧区间下
    # **核心规则一次都不会触发**。旧 mock 于是把阈值放宽成了 75/40，
    # 那是把标准迁就数据。这里的做法相反：让数据覆盖到标准要求的区间，
    # 阈值仍然用方案写的 80/20。
    #
    # 上限 0.58 而不是 0.62：实测南郑区（黄官茶园 + 食用菌产业园）
    # 的乡村均值在 0.62 下算出来是 19.1%，离 20% 只差 0.9pp ——
    # 贴边意味着以后随便改一下种子片段就会静默不触发。
    # 压到 0.58 后南郑区落在 16.6%，留出 3.4pp 余量。
    "RURAL_SPOT": (0.07, 0.58),
    "FOOD": (0.35, 0.88),
    "LODGING": (0.30, 0.80),
    "TRANSPORT": (0.40, 0.90),
    "SHOPPING": (0.25, 0.70),
}

# 近 7 日环比增幅超过这个倍数的点位，判为「热度突变」。
# 绝大多数点位是平稳的，只有下面 HEAT_SURGE_POIS 里的几个被刻意
# 造成上升趋势，否则规则 3 永远不会触发。
#
# 做法是**最近 7 天整体抬到 SURGE_MULTIPLE 倍、再往前 7 天保持基准**，
# 于是「近 7 日 ÷ 前 7 日」恰好等于这个倍数，稳定高于规则 3 的 80% 阈值。
# 用阶跃而不是渐变：真实的"突然火了"本来就是阶跃（一条短视频带来的），
# 而且阶跃能保证比值可预测，不用靠调参试出来。
#
# ⚠️ 选点要挑**基准承载偏低**的：倍数直接乘在基准上，若挑一个基准
# 已经 0.77 的点，抬完就是 162% 承载，看着不像"火了"而像数据错了。
# 这里两个基准都在 0.4 以下，抬完落在 80% 上下 —— 正是"热度突变 →
# 进入高位预警"该有的样子。
HEAT_SURGE_POIS = ("P-SCE-001", "P-RUR-003")
SURGE_MULTIPLE = 2.1

# 负面评价率异常高的点位，用于让规则 2「差评激增」触发。
# ⚠️ 必须选**到访量足够大**的点位：规则 2 要求「样本 ≥ 5」，
# 而餐饮类 POI 容量只有 80–200，日评价量只有个位数，永远凑不够样本。
# 这里两个都是景区（容量 3000 / 9000）。
NEGATIVE_SURGE_POIS = ("P-SCE-003", "P-SCE-011")

# 体验→购买转化率明显偏低的乡村点，用于让规则 5「产品转化低」触发。
LOW_CONVERSION_POIS = ("P-RUR-005", "P-RUR-009")

# 复购量持续衰减的乡村点，用于让规则 6「复购衰减」触发。
REPURCHASE_DECAY_POIS = ("P-RUR-002",)

DAYS_DEFAULT = 60

# 只有乡村点会产生体验参与与购买，其余业态这几列恒为 0
EXPERIENCE_TYPES = ("RURAL_SPOT",)


def base_usage(poi_id: str, business_type: str) -> float:
    """设计承载率。与前端旧 mock 的 capacityUsage 同口径（同种子、同算法）。"""
    lo, hi = USAGE_RANGE.get(business_type, USAGE_RANGE["SCENIC"])
    return _to_fixed(lo + seed(poi_id, "usage") * (hi - lo), 4)


def day_factor(offset: int) -> float:
    """日间波动：一条缓慢的趋势 + 逐日抖动。

    **offset 0（今天）刻意恒等于 1.0**，即今天看到的承载率**就是**
    USAGE_RANGE 里的设计区间，不再叠抖动。理由有两条：
      ① 规则 4「乡村闲置」的判据是「景区 > 80% 且乡村 < 20%」，
         若今天再叠 ±8% 抖动，一个设计值 19% 的乡村点会被抖到 20.5%
         而静默不触发 —— 演示结果会依赖一个看不出原因的随机偏移；
      ② 验收文档里写"承载区间 [0.08, 0.62]"，读者应当能直接在
         数据里看到这个区间，而不是看到区间乘一个说不清的系数。
    更早的日子仍然有起伏，7 日趋势图不会是一条平线。
    """
    if offset == 0:
        return 1.0
    # 60 天内 8% 幅度的缓慢趋势，方向由 offset 决定
    trend = 1.0 + 0.08 * (offset / float(DAYS_DEFAULT))
    jitter = 0.92 + seed("daily", offset) * 0.16
    return trend * jitter


def build_poi_series(poi: dict, days: int) -> dict:
    """算一个 POI 的逐日序列。"""
    poi_id = poi["id"]
    btype = poi["business_type"]
    capacity = int(poi.get("capacity") or 0)
    usage = base_usage(poi_id, btype)

    is_rural = btype in EXPERIENCE_TYPES
    surge = poi_id in HEAT_SURGE_POIS
    neg_surge = poi_id in NEGATIVE_SURGE_POIS
    low_conv = poi_id in LOW_CONVERSION_POIS
    decay = poi_id in REPURCHASE_DECAY_POIS

    series = []
    for offset in range(-(days - 1), 1):
        factor = day_factor(offset)

        # 热度突变：最近 7 天抬到 SURGE_MULTIPLE 倍，再往前 7 天保持基准。
        # 这样「近 7 日 ÷ 前 7 日」= SURGE_MULTIPLE，稳定高于规则 3 的阈值。
        if surge and offset >= -6:
            factor *= SURGE_MULTIPLE

        visitors = max(0, int(round(capacity * usage * factor)))

        # ---- 评价：近 24h 的样本量与负面数 ----
        # 评价量与到访量同向，但不完全成比例（不是每个游客都写评价）。
        # 比例取 0.8%–2.0%：景区日到访数千，对应日评价十几到几十条，
        # 能过规则 2 的「样本 ≥ 5」；餐饮类到访几百，评价只有几条，
        # 因此规则 2 实际上只可能命中到访量大的点位 —— 这是真实情况。
        review_count = max(
            0, int(round(visitors * (0.008 + seed(poi_id, "rev", offset) * 0.012)))
        )
        neg_rate = 0.02 + seed(poi_id, "neg", offset) * 0.06
        if neg_surge and offset >= -1:
            # 只有最近两天负面率飙升 —— "激增"是事件，不是常态
            neg_rate = 0.34 + seed(poi_id, "negsurge", offset) * 0.18
        negative_count = max(0, int(round(review_count * neg_rate)))

        # ---- 体验参与与购买（只有乡村点非零）----
        if is_rural:
            exp_visits = max(0, int(round(visitors * (0.10 + seed(poi_id, "exp", offset) * 0.14))))
            conv = 0.09 + seed(poi_id, "conv", offset) * 0.14
            if low_conv:
                conv = 0.018 + seed(poi_id, "lowconv", offset) * 0.022
            purchases = max(0, int(round(exp_visits * conv)))
            repurchase_rate = 0.10 + seed(poi_id, "rep", offset) * 0.16
            if decay:
                # 越靠近今天复购越低，形成 30 日环比下滑
                decline = 1.0 - 0.55 * ((days - 1 + offset) / float(days - 1))
                repurchase_rate *= max(0.05, decline)
            repurchases = max(0, int(round(purchases * repurchase_rate)))
        else:
            exp_visits = purchases = repurchases = 0

        series.append(
            {
                "offset": offset,
                "visitors": visitors,
                "review_count": review_count,
                "negative_count": negative_count,
                "experience_visits": exp_visits,
                "purchases": purchases,
                "repurchases": repurchases,
            }
        )

    return {
        "poi_id": poi_id,
        "business_type": btype,
        "capacity": capacity,
        "base_usage": usage,
        "series": series,
    }


def build_pack(pois: list[dict], days: int) -> dict:
    return {
        "synthetic": True,
        "generator": "scripts/gen_synthetic.py",
        "note": (
            "本文件全部为基于规则的仿真数据，不是真实统计。"
            "客流、评价、购买与复购均由 POI id 派生的确定性伪随机值生成，"
            "区间与刻意留出的触发样本是设计选择，目的是让规则引擎可演示。"
            "详见 scripts/gen_synthetic.py 的文件头与 docs/验收记录-M5.md。"
        ),
        "days": days,
        "pois": [build_poi_series(p, days) for p in pois],
    }


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="生成合成客流数据到 citypack")
    ap.add_argument("--city", default="hanzhong", help="城市包目录名，默认 hanzhong")
    ap.add_argument("--days", type=int, default=DAYS_DEFAULT, help="生成多少天，默认 60")
    ap.add_argument("--verify", action="store_true", help="只打印参考值用于比对，不写文件")
    args = ap.parse_args(argv)

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    pack_dir = os.path.join(root, "citypack", args.city)
    pois_path = os.path.join(pack_dir, "pois.json")
    if not os.path.isfile(pois_path):
        print(f"找不到数据包：{pois_path}", file=sys.stderr)
        return 2

    with io.open(pois_path, encoding="utf-8") as f:
        pois = json.load(f)

    if args.verify:
        # 打印与前端原实现可直接比对的量。
        # today_visitors 的口径与 mock 的 todayVisitors 一致：
        # capacity * usage * (0.55 + seed(id,'day') * 0.5)
        for p in pois:
            u = base_usage(p["id"], p["business_type"])
            ratio = 0.55 + seed(p["id"], "day") * 0.5
            tv = int(round(int(p.get("capacity") or 0) * u * ratio))
            print(f"{p['id']}\t{u:.4f}\t{tv}")
        return 0

    pack = build_pack(pois, args.days)
    out_path = os.path.join(pack_dir, "visit_stats.json")
    with io.open(out_path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(pack, f, ensure_ascii=False, indent=1)
        f.write("\n")

    total = sum(len(x["series"]) for x in pack["pois"])
    print(f"已写入 {out_path}")
    print(f"  POI {len(pack['pois'])} 个 × {args.days} 天 = {total} 条日度记录")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
