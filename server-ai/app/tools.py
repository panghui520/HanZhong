"""Agent 可用的工具（M4 第一阶段）。

## 工具的两种输出，缺一不可

每个工具执行完要给出两样东西：

1. **`text`** —— 给模型看的【工具结果】。模型只被允许引用这里出现过的内容。
2. **`cards`** —— 给前端渲染的结构化卡片（酒店 / 餐厅 / 景点）。

为什么不合成一个：模型的输出是**自然语言**，而卡片里的酒店名、地址、距离、
经纬度必须**逐字可核对**。让模型把结构化数据「抄」进回答、再从前端解析出来，
等于把可靠性寄托在它抄得准不准上 —— 抄错一个数字，用户就按错地址去订房。
所以卡片直接从工具结果来，模型只负责说人话。

## 工具的选择是模型的事，执行是这里的事

`TOOL_SPECS` 是给调度器提示词看的「工具清单」，`execute()` 是执行器。
两者放同一个文件，是为了**加一个工具时不会漏改另一处**。

## 失败不抛给上层

工具失败（高德超时、key 无效、地址查不到）一律**降级成一条空结果 + 一句说明**，
由模型告诉用户「这次没查到」。整轮对话不该因为一个外部服务抖动就中断 ——
用户看到的是「附近没查到酒店」，而不是一个红色报错。
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Any, Sequence

from .amap import (
    DEFAULT_RADIUS,
    TYPE_LABELS,
    TYPES_ACCOMMODATION,
    TYPES_ATTRACTION,
    TYPES_FOOD,
    TYPES_TRANSPORT,
    AmapError,
    AmapPoi,
    GeoPoint,
)
# 行程规划的产物类型。只 import 数据类型（纯 dataclass），不 import 执行逻辑 ——
# 规划算法在 `itinerary.py`，这里只负责把它的结果**翻译**成给模型看的文本
# 与给前端看的卡片。
from .itinerary import Itinerary, Stop
# 复用 qa 的来源载荷组装，避免两处各写一份字段清单
# （以后给切片元数据加字段时，只改 _source_payload 一处就够）。
from .qa import _source_payload
from .store import Hit, KbStore

logger = logging.getLogger("hanyou.ai.tools")

# 工具名。用字符串常量而不是裸字面量：调度器返回的 JSON 要跟它们比对，
# 拼错一个字母的表现是「永远走到 none 分支」，很难发现。
TOOL_KNOWLEDGE = "knowledge_search"
TOOL_NEARBY = "search_nearby"
TOOL_POI = "search_poi"
TOOL_PLAN = "plan_itinerary"
TOOL_ROUTE = "get_route"
TOOL_NONE = "none"

# 关键词 -> 类型码的兜底推断。
# 调度器**应该**给出 types；它没给（或给了个不认识的）时用这张表兜住。
# 顺序有意义：先匹配到的先用，所以把更具体的说法放前面。
_KEYWORD_TYPES: tuple[tuple[tuple[str, ...], str], ...] = (
    (("酒店", "宾馆", "民宿", "旅馆", "住宿", "住哪", "住哪儿", "落脚"), TYPES_ACCOMMODATION),
    (("餐厅", "饭馆", "小吃", "美食", "吃", "饭菜", "馆子"), TYPES_FOOD),
    (("景点", "景区", "公园", "博物馆", "古镇", "玩的", "好玩"), TYPES_ATTRACTION),
    (("车站", "高铁", "火车", "机场", "汽车站", "服务区"), TYPES_TRANSPORT),
)

# typecode 前两位（大类）-> 卡片类别。前端据此决定卡片长什么样。
_KIND_BY_MAJOR = {
    "10": "hotel",
    "05": "restaurant",
    "11": "attraction",
    "15": "transport",
}


@dataclass(frozen=True)
class ToolSpec:
    """一个工具的规格。`title` 与 `args_hint` 会原样进调度器提示词。"""

    name: str
    title: str
    args_hint: str
    needs_amap: bool = False


TOOL_SPECS: tuple[ToolSpec, ...] = (
    ToolSpec(
        name=TOOL_NEARBY,
        title="搜索某个地点附近的酒店 / 餐厅 / 景点 / 车站（高德地图真实数据）",
        args_hint='{"center": "中心点地点名", "keyword": "关键词", "types": "类型编码", "radius": 半径米数}',
        needs_amap=True,
    ),
    ToolSpec(
        name=TOOL_POI,
        title="按名称搜索某个具体地点，拿到它的位置、地址、电话（高德地图真实数据）",
        args_hint='{"keywords": "地点名", "types": "类型编码（可省略）"}',
        needs_amap=True,
    ),
    ToolSpec(
        name=TOOL_KNOWLEDGE,
        title="查询本地知识库：汉中历史、文化、气候、物产、特产，以及数据包里资源点 / 体验 / 产品的介绍",
        args_hint='{"query": "用户问题的核心问法"}',
    ),
    ToolSpec(
        name=TOOL_PLAN,
        title="为{city}排出按天的行程线路（用户问「两日游」「三天怎么安排」「怎么玩」时用）",
        args_hint='{"days": 天数（整数，如 2）, "preference": "偏好，可省略（自然风光 / 历史文化 / 亲子）"}',
        # **不需要高德**：它读的是数据包里的资源点，不联网。所以高德没配时
        # 行程规划仍然可用 —— 这也是"断网也能演示"的一环。
    ),
    ToolSpec(
        name=TOOL_ROUTE,
        title="查询两个地点之间的驾车路线（距离 / 时长）",
        # **不取 polyline**：路径线要前端画才有用，返回它只会撑大 JSON。
        # 上层 `_get_route` 内部用 `places.PlaceResolver` 解析地名，再调高德
        # `/v3/direction/driving`。所以即使一端是高德数据库里的现成地点
        # （"汉中博物馆"），也能算出 —— 不必先 `search_poi` 拿坐标。
        args_hint='{"from": "起点地名", "to": "终点地名"}',
        needs_amap=True,
    ),
)


def available_tools(amap_enabled: bool) -> list[ToolSpec]:
    """当前**真正可用**的工具。

    单独抽成一个函数，是为了让"哪些工具可用"只有一处定义：
    提示词渲染、`/ai/health` 的能力上报、以及离线自检脚本都读它。
    早先自检脚本靠"清单字符串里有没有出现 search_nearby"来判断，
    结果被那句"search_nearby 与 search_poi 不可用"的说明误命中 ——
    靠字符串猜语义，迟早会猜错。
    """
    return [spec for spec in TOOL_SPECS if amap_enabled or not spec.needs_amap]


def tool_menu(amap_enabled: bool, city_name: str) -> str:
    """生成给调度器提示词看的工具清单。

    高德没配时不列那两个工具，并**明说原因** —— 否则模型会以为「附近搜不到」
    是地理事实，而不是本机没配 key。这两种情况对用户是完全不同的两件事。
    """
    lines: list[str] = []
    for spec in available_tools(amap_enabled):
        # title 里可以写 `{city}` 占位符（行程规划那条就用了），在这里统一替换。
        # 不在 spec 里写死"汉中"：数据包是可迁移的，工具清单也该跟着城市走。
        lines.append(f"- `{spec.name}`：{spec.title.replace('{city}', city_name)}")
        lines.append(f"  参数：{spec.args_hint}")
    lines.append(f"- `{TOOL_NONE}`：不需要任何工具（与{city_name}无关的问题）")
    lines.append("  参数：{}")

    if amap_enabled:
        codes = "、".join(f"{code}={label}" for code, label in TYPE_LABELS.items())
        lines.append("")
        lines.append(f"可用的 types 编码：{codes}")
    else:
        lines.append("")
        lines.append(
            "注意：本机**未配置高德地图**，所以 `search_nearby` 与 `search_poi` 不可用。"
            "凡是问真实地点的问题，一律选 `none`，由生成阶段如实说明查不了。"
        )
        # 这一句是必须的：清单里明明列着 plan_itinerary，上面却说"真实地点的问题
        # 一律选 none"，不点破的话模型会把"排两天行程"也当成"问真实地点"而弃用。
        lines.append(
            "`knowledge_search` 与 `plan_itinerary` **不受影响**，照常使用 ——"
            "前者读本地知识库，后者读本地数据包，都不需要联网。"
        )
    return "\n".join(lines)


@dataclass
class ToolResult:
    """一次工具执行的结果。"""

    name: str
    kind: str = "none"
    text: str = ""
    cards: list[dict[str, Any]] = field(default_factory=list)
    count: int = 0
    label: str = ""
    # 非空表示这次没拿到数据。**不是异常**：上层照常让模型说话，
    # 只是把这句话交给它，让它如实告诉用户。
    error: str = ""
    # knowledge_search 的原始命中。给 agent 复用 M3 的 `build_messages` 用 ——
    # 那套提示词要的是 Hit 列表而不是拼好的文本，所以这里把原文一并带出来，
    # 避免"为了走 M3 的提示词再检索一次"。
    hits: list[Hit] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.error


# ----------------------------------------------------------------------
# 文本与卡片组装
# ----------------------------------------------------------------------


def _kind_of(poi: AmapPoi, fallback: str = "poi") -> str:
    major = poi.typecode[:2] if len(poi.typecode) >= 2 else ""
    return _KIND_BY_MAJOR.get(major, fallback)


def _cards_of(pois: Sequence[AmapPoi]) -> list[dict[str, Any]]:
    """把 POI 转成前端卡片，并**给每张卡片单独标上类别**。

    为什么要逐张标，而不是让前端读 `cards` 事件上那个 kind：
    事件上的 kind 是"这批结果整体是什么"（取第一条的大类），
    而前端真正要判断的是"**这张**卡片能不能被选为住处"。用户问
    "汉中市区附近有什么"时结果里可能餐厅、酒店混在一起，只要第一条
    不是酒店，整批就都会被判成非酒店，那张酒店卡片上就不会出现"选择"按钮
    —— 用户会觉得这个功能时灵时不灵。

    类别由 typecode 前两位决定，与 `_KIND_BY_MAJOR` 是同一处定义，
    不另立一套规则。
    """
    return [{**poi.to_card(), "kind": _kind_of(poi)} for poi in pois]


def _describe(poi: AmapPoi, index: int) -> str:
    """一条 POI 的文本形式。**只写有值的字段** —— 写「电话：（无）」会让模型
    以为"高德说这家店没有电话"，而事实是我们没拿到这个字段。"""
    lines = [f"{index}. {poi.name}"]
    if poi.category:
        lines.append(f"   类型：{poi.category}")
    if poi.full_address:
        lines.append(f"   地址：{poi.full_address}")
    if poi.distance_m is not None:
        lines.append(f"   距离中心点：{poi.distance_m} 米")
    if poi.business_area:
        lines.append(f"   商圈：{poi.business_area}")
    if poi.tel:
        lines.append(f"   电话：{poi.tel}")
    extras = []
    if poi.rating:
        extras.append(f"评分 {poi.rating}")
    if poi.cost:
        extras.append(f"人均 {poi.cost} 元")
    if extras:
        lines.append("   " + " / ".join(extras))
    if poi.tag:
        lines.append(f"   特色：{poi.tag}")
    return "\n".join(lines)


def _render(pois: Sequence[AmapPoi], header: str, limit: int) -> str:
    picked = list(pois[:limit])
    body = "\n".join(_describe(p, i) for i, p in enumerate(picked, 1))
    return (
        f"{header}\n"
        f"（以下为高德地图返回的真实数据，共 {len(pois)} 条，"
        f"按距离由近到远，此处列出前 {len(picked)} 条）\n\n{body}"
    )


def _price_text(value: Any) -> str:
    """门票。数据包里 0 表示免费、null 表示未知 —— 两者必须分开说。

    写成"门票 0 元"和"门票免费"对用户是同一件事，但"门票 未知 元"是明显的
    机器话；而把 null 说成 0 元，用户会以为这个景区不要钱。
    """
    if value in (None, ""):
        return ""
    try:
        amount = float(value)
    except (TypeError, ValueError):
        return f"门票 {value}"
    if amount <= 0:
        return "门票免费"
    return f"门票 {amount:g} 元"


def _stop_payload(stop: Stop) -> dict[str, Any]:
    """一个游览点 -> 前端卡片里的一条。**字段全部来自数据包**，模型碰不到。"""
    return {
        "poi_id": stop.poi_id,
        "name": stop.name,
        "district": stop.district,
        "level": stop.level,
        "duration_min": stop.duration_min,
        "open_hours": stop.open_hours,
        "ticket_price": stop.ticket_price,
        "summary": stop.summary,
        "tags": list(stop.tags),
    }


def _itinerary_card(
    plan: Itinerary, *, city_name: str, days: int, preference: str
) -> dict[str, Any]:
    """把行程转成前端卡片。

    **它是一张卡、不是一批卡**：行程是一个整体（第几天去哪几个点），
    拆成"每个景点一张卡"就丢掉了天数与区县这两个最关键的字段 ——
    而用户要看的恰恰是"第一天在汉台区、第二天在南郑区"。

    `requested_days`（用户要几天）与 `days`（真的排出来几天）分开给：
    数据包里的点不够时算法会少排，前端要能如实显示"要 3 天，排出 2 天"，
    而不是把 2 天冒充成 3 天。
    """
    return {
        "kind": "itinerary",
        "title": f"{city_name} {days} 日行程",
        "requested_days": days,
        "days": [
            {
                "day": day.day,
                "district": day.district,
                "minutes": day.minutes,
                "over_budget": day.over_budget,
                "stops": [_stop_payload(s) for s in day.stops],
            }
            for day in plan.days
        ],
        "minutes_per_day": plan.minutes_per_day,
        "preference": preference,
        "notes": list(plan.notes),
    }


def _render_itinerary(
    plan: Itinerary, *, city_name: str, days: int, start_note: str = ""
) -> str:
    """行程的文本形式（给模型看）。

    这里**要把排法讲清楚**，而不只是列点。原因是用户对旧版本的抱怨是
    "说不明白" —— 模型拿到一串景点名，只会照抄；拿到"为什么这么排"，
    才能回答"为什么第二天去南郑"这种追问。
    """
    lines = [
        f"系统已按本地数据包为{city_name}排出 {len(plan.days)} 天行程"
        f"（用户要求 {days} 天）。",
        "排法：同一天只安排在**同一个区县**（县域之间车程 1–2 小时，跨县串点时间全耗在路上）；"
        f"每天游览时长预算约 {plan.minutes_per_day} 分钟；"
        "每个区县内按景区级别优先选点，再按地理位置就近排序。",
        "",
    ]
    for day in plan.days:
        lines.append(f"第 {day.day} 天 · {day.district}（合计 {day.minutes} 分钟）")
        for i, stop in enumerate(day.stops, 1):
            bits = []
            if stop.level:
                bits.append(stop.level)
            bits.append(f"建议游览 {stop.duration_min} 分钟")
            price = _price_text(stop.ticket_price)
            if price:
                bits.append(price)
            if stop.open_hours:
                bits.append(f"开放 {stop.open_hours}")
            lines.append(f"  {i}. {stop.name}｜{'｜'.join(bits)}")
            if stop.summary:
                lines.append(f"     {stop.summary}")
        lines.append("")

    lines.append("关于上面这份行程的说明：")
    lines.append(
        "- 名称、级别、建议时长、门票、开放时间**全部来自本地数据包的静态整理值**"
        "（每条都有 source_url），**不是实时信息**。请提示用户出行前以景区公告为准。"
    )
    lines.append(
        "- 本行程**只排游览点**，不含餐饮、住宿，也不含交通方式与车程估算 ——"
        "数据包里的坐标是基于规则的估算值，据此算车程会是假精度。"
    )
    lines.append("- 同一个游览点不会在两天里重复出现。")
    if start_note:
        lines.append(f"- {start_note}")
    for note in plan.notes:
        lines.append(f"- {note}")
    return "\n".join(lines)


# ----------------------------------------------------------------------
# 四个执行器
# ----------------------------------------------------------------------


def _infer_types(keyword: str, given: str) -> str:
    """决定 types。调度器给了就用它，没给就从关键词推断。"""
    given = (given or "").strip()
    if given:
        return given
    for words, code in _KEYWORD_TYPES:
        if any(w in keyword for w in words):
            return code
    return ""


def _search_nearby(
    args: dict[str, Any],
    *,
    amap,
    resolver,
    city_name: str,
    city_center: tuple[float, float] | None,
    hotel: dict[str, Any] | None = None,
) -> ToolResult:
    center_name = str(args.get("center") or "").strip()
    keyword = str(args.get("keyword") or args.get("keywords") or "").strip()
    types = _infer_types(keyword, str(args.get("types") or ""))
    try:
        radius = int(args.get("radius") or DEFAULT_RADIUS)
    except (TypeError, ValueError):
        radius = DEFAULT_RADIUS

    hotel = hotel or {}
    hotel_location = str(hotel.get("location") or "").strip()
    hotel_name = str(hotel.get("name") or "").strip()

    # ------------------------------------------------------------------
    # 搜索中心的三级回退：用户点名的地名 > 本次行程已选酒店 > 数据包城市中心
    #
    # 中间那一级就是 M4 阶段二要解决的问题：用户问"附近有什么好吃的"，
    # 而"附近"是相对**他住的地方**说的。上一轮他在卡片上选过酒店，
    # 这一轮不该再问他一遍 —— 上下文里已经有坐标了。
    # ------------------------------------------------------------------
    location = ""
    center_label = ""
    center_note = ""

    if center_name:
        # 地名解析交给 `places.PlaceResolver`，三级：本地地名表 → 高德 POI 搜索
        # → 地理编码兜底。**这一行是本模块历史上最贵的一处修复**：
        # 原来直接调 `amap.geocode()`，而高德地理编码对「汉中高铁站」返回的首条
        # 是「洋县高铁站(公交站)」—— 用户问汉台区的酒店，拿到一整屏洋县的宾馆。
        # 详见 `places.py` 模块注释里的实测对照表。
        place = resolver.resolve(center_name)
        if place is not None:
            location = place.location
            # `label` 是"标准名（所属区县）"（如 `汉中站（汉台区）`）。
            # 它同时进 label 与给模型的 text，让模型**直接读到**实际用的中心
            # 是哪儿、在哪个区 —— 而不是从结果地址里全是"洋县"反推出来。
            # 区县要显式写出来：用户判断"这是不是我要的地方"第一眼看的就是它，
            # 而本次的错误形态恰恰是"名字看着像、区县完全不对"。
            center_label = place.label
        else:
            center_note = f"没能定位到「{center_name}」，已改用{city_name}市区中心作为搜索中心。"

    # 只有**用户没有点名中心**时才用已选酒店。
    # 反例：用户说"汉中高铁站附近有什么好吃的"，而"汉中高铁站"地理编码失败 ——
    # 这时改去搜他住的酒店周边就是答非所问。那批结果看着合理，
    # 但回答的不是他问的问题，而且用户很难发现中心被换掉了。
    if not location and not center_name and hotel_location:
        location = hotel_location
        # 名称在前、说明在后：它会同时进 label（"正在搜索「XX酒店（您选择的酒店）」附近…"）
        # 与给模型的 header，两种场合下都读得通。反过来写成
        # "您选择的酒店「XX酒店」"会在 label 里套成双层引号，很别扭。
        center_label = f"{hotel_name}（您选择的酒店）" if hotel_name else "您选择的酒店"
        # 这句要进给模型的 text：模型得知道"附近"是相对酒店说的，
        # 才能在回答里写"您住的 XX 酒店附近…"，而不是含糊地说"附近"。
        center_note = (
            f"用户本次行程已选定住处「{hotel_name or '未命名'}」，"
            f"他没有另外指定中心点，因此以该酒店为中心搜索。"
        )

    if not location:
        if city_center is None:
            return ToolResult(
                name=TOOL_NEARBY,
                kind="poi",
                label=f"正在搜索{center_name or city_name}附近…",
                error=f"没能确定搜索中心点（「{center_name or '未指定'}」查不到，且数据包里没有城市中心坐标）",
            )
        location = f"{city_center[0]:.6f},{city_center[1]:.6f}"
        center_label = f"{city_name}市区中心"
        if not center_note:
            center_note = f"未指定搜索中心，已用{city_name}市区中心。"

    label = f"正在搜索「{center_label}」附近"
    label += "的酒店…" if types == TYPES_ACCOMMODATION else "…"

    try:
        pois = amap.search_around(
            location, keywords=keyword, types=types, radius=radius, offset=20
        )
    except AmapError as exc:
        logger.warning("[工具] 周边搜索失败：%s", exc)
        return ToolResult(name=TOOL_NEARBY, kind="poi", label=label, error=f"高德查询失败：{exc}")

    if not pois:
        what = TYPE_LABELS.get(types, keyword or "地点")
        prefix = f"{center_note}\n" if center_note else ""
        # 注意这里**不设 error**。「这个范围内确实没有酒店」不是故障 ——
        # 把它标成 error 会让前端显示一个红色失败态，而实际发生的事是
        # "查了，没有"。真正的故障（超时 / key 无效 / 中心点定不下来）
        # 才设 error。空结果由 count=0 表达，agent._generate 会据此
        # 给模型加一句「如实说明，不要用别的数据凑数」。
        return ToolResult(
            name=TOOL_NEARBY,
            kind=_KIND_BY_MAJOR.get(types[:2], "poi") if types else "poi",
            label=label,
            count=0,
            text=f"{prefix}{label}\n（高德在该范围内没有返回任何{what}）",
        )

    kind = _kind_of(pois[0])
    # 中心点的**名称 + 坐标**都要给模型：只给坐标它不知道那是哪儿，
    # 只给名称它没法核对。名称是地理编码解析出来的标准名。
    header = f"搜索中心：{center_label}（{location}，半径 {radius} 米）"
    if center_note:
        header = f"{center_note}\n{header}"
    text = _render(pois, header, limit=12)

    return ToolResult(
        name=TOOL_NEARBY,
        kind=kind,
        label=label,
        count=len(pois),
        text=text,
        cards=_cards_of(pois[:12]),
    )


def _search_poi(args: dict[str, Any], *, amap, city_name: str) -> ToolResult:
    keywords = str(args.get("keywords") or args.get("keyword") or "").strip()
    types = str(args.get("types") or "").strip()
    if not keywords and not types:
        return ToolResult(
            name=TOOL_POI, kind="poi", error="search_poi 需要 keywords 或 types 至少一个"
        )

    label = f"正在搜索「{keywords or types}」…"
    try:
        pois = amap.search_text(keywords, types=types, city=city_name, offset=10)
    except AmapError as exc:
        logger.warning("[工具] 地点搜索失败：%s", exc)
        return ToolResult(name=TOOL_POI, kind="poi", label=label, error=f"高德查询失败：{exc}")

    if not pois:
        return ToolResult(
            name=TOOL_POI,
            kind="poi",
            label=label,
            count=0,
            text=f"{label}\n（高德在{city_name}范围内没有查到「{keywords}」）",
        )

    # 名称匹配检查。实测：查「汉中博物馆」时高德返回的是拜将坛、古汉台这些
    # **周边景点**，没有一条名字里带"汉中博物馆"。不说明的话，模型可能把
    # 第一条当成"就是它"，用户就按一个错的地名出发了。
    # 只在**关键词确实没被任何一条命中**时才提示，避免正常搜索也被加一句废话。
    header = f"地点搜索：{keywords}（限定 {city_name}）"
    if keywords and not any(keywords in p.name or p.name in keywords for p in pois):
        names = "、".join(p.name for p in pois[:3])
        header = (
            f"注意：返回结果里**没有任何一条的名称包含「{keywords}」**，"
            f"以下是高德认为相关的附近地点（如 {names} 等）。"
            f"请如实说明没有精确定位到「{keywords}」，不要把某一条说成就是它。\n{header}"
        )

    text = _render(pois, header, limit=6)
    return ToolResult(
        name=TOOL_POI,
        kind=_kind_of(pois[0]),
        label=label,
        count=len(pois),
        text=text,
        cards=_cards_of(pois[:6]),
    )


def _knowledge_search(args: dict[str, Any], *, store: KbStore, top_k: int) -> ToolResult:
    query = str(args.get("query") or args.get("keywords") or "").strip()
    if not query:
        return ToolResult(name=TOOL_KNOWLEDGE, kind="knowledge", error="knowledge_search 需要 query")

    # ★ 这一句会**原样显示在游客端的工具轨迹里**（Agent.vue 的 .tstep__label）。
    #   原来写的是"正在检索本地知识库…" —— "本地知识库"是我们的实现口径，
    #   游客看到只会困惑。改成"正在查资料…"，说的是游客能理解的事。
    #   注意：这是展示文案，**不动 TOOL_KNOWLEDGE 这个工具名**，
    #   前端的卡片分支与后端的路由都按 name 匹配，改 name 会连带改坏两处。
    label = "正在查资料…"
    hits = store.query(query, top_k)
    if not hits:
        return ToolResult(
            name=TOOL_KNOWLEDGE,
            kind="knowledge",
            label=label,
            count=0,
            text="（知识库里没有检索到与这个问题相关的内容）",
        )

    blocks = [
        f"【资料{i}】{h.metadata.get('title', '')}\n{h.text}"
        for i, h in enumerate(hits, 1)
    ]
    return ToolResult(
        name=TOOL_KNOWLEDGE,
        kind="knowledge",
        label=label,
        count=len(hits),
        text="\n\n".join(blocks),
        cards=[_source_payload(h.metadata, h.score) for h in hits],
        hits=list(hits),
    )


def _plan_itinerary(
    args: dict[str, Any],
    *,
    planner,
    city_name: str,
    hotel: dict[str, Any] | None = None,
) -> ToolResult:
    """按天排行程。**不联网** —— 读的是数据包里的资源点。

    与 `knowledge_search` 的分工要说清楚，这是两条最容易混的路：

    | 用户问的 | 走哪条 | 因为 |
    |---|---|---|
    | 「帮我排一个汉中两日游」 | 这里 | 要的是**具体方案**：哪天去哪几个点 |
    | 「汉中行程一般怎么安排 / 有什么要注意」 | `knowledge_search` | 要的是**原则**：那篇文档讲的是排法本身 |

    混起来的后果是：问"怎么排"的时候系统硬给一份两日游（用户还没说几天），
    问"两日游"的时候系统讲一堆原则却不给点。所以两条路都留着，
    由调度器按"有没有点名天数 / 要方案还是要方法"来分。
    """
    try:
        days = int(args.get("days") or 0)
    except (TypeError, ValueError):
        days = 0
    # 没给天数或给了个离谱的值，按 2 天算 —— 这是最常见的诉求
    # （用户说"周末去汉中"其实就是两天），比报错让模型重新问一轮好。
    if days <= 0:
        days = 2
    days = min(days, 7)  # 上限与 `itinerary.plan` 一致：再多就该分次旅行了

    preference = str(args.get("preference") or "").strip()
    # 措辞与 `agent._label` 保持一致：那一句在工具执行**之前**就发出去了，
    # 两句不一致的话，用户会先看到"正在规划…"再变成"正在为…排…"，
    # 看起来像换了件事在做。
    label = f"正在规划{city_name} {days} 天行程…"

    if planner is None or not planner.ready:
        # 数据包读不到。**这是 error 而不是空结果** —— 空结果的意思是
        # "查了，这个范围里没有"，而这里是"本机根本没有可用的数据"。
        return ToolResult(
            name=TOOL_PLAN,
            kind="itinerary",
            label=label,
            error="数据包里没有可用的游览点数据，排不了行程",
        )

    hotel = hotel or {}
    hotel_name = str(hotel.get("name") or "").strip()
    start_note = ""
    start_district = ""
    if str(hotel.get("location") or "").strip():
        # 用户已选住处 -> 行程从他住的区县开始排。
        # **反查而不是让 Java 侧存一个区县字段**：那个字段要么是导入时算的、
        # 要么是前端传的，两条路都可能与坐标不一致；这里从坐标现算，只有一处真相。
        start_district = planner.district_of(str(hotel["location"]))
        if start_district:
            start_note = (
                f"行程从用户已选住处「{hotel_name or '未命名'}」所在的{start_district}开始排，"
                "他不用第一天就跨县。"
            )

    plan = planner.plan(days, preference=preference, start_district=start_district)
    if not plan.days:
        return ToolResult(
            name=TOOL_PLAN,
            kind="itinerary",
            label=label,
            count=0,
            text=f"{label}\n（数据包里没有可排的游览点）",
        )

    return ToolResult(
        name=TOOL_PLAN,
        kind="itinerary",
        label=label,
        # count 是**天数**（不是条数）。前端在行程卡片上按"共 N 天"显示。
        count=len(plan.days),
        text=_render_itinerary(plan, city_name=city_name, days=days, start_note=start_note),
        cards=[_itinerary_card(plan, city_name=city_name, days=days, preference=preference)],
    )


# ----------------------------------------------------------------------
# get_route —— 两点驾车路径规划（M4 第三段）
#
# 不像 `search_nearby` / `search_poi` 那样返回一组候选点 —— 它返回的是
# 一个**单一事实**："从 A 到 B 自驾多远 / 多久"。模型拿到这串数字就能
# 给游客一段具体答复（"汉中站到青木川古镇自驾约 280 公里，约 4 小时"）。
#
# 为什么用 `PlaceResolver` 而不是直接 `amap.geocode`：
# `places.py` 是**三档校验**（本地高德 POI 表 → 高德 /place/text → 高德 /geocode/geo），
# 单 `geocode` 对「汉中高铁站」这类口语地名命中率很低。复用同一个 resolver
# 与 `search_nearby` 的输入解析口径一致 —— "汉中站" 在前端能搜到的地方，
# 在路线工具里也能算 —— 这是同一件事，不该有两套解析。
# ----------------------------------------------------------------------
def _get_route(
    args: dict[str, Any],
    *,
    amap,
    resolver,
    city_name: str,
) -> ToolResult:
    from_name = str(args.get("from") or "").strip()
    to_name = str(args.get("to") or "").strip()
    if not from_name or not to_name:
        return ToolResult(
            name=TOOL_ROUTE,
            kind="route",
            error="get_route 需要 from 与 to 两个地名都不能为空",
        )

    label = f"正在算「{from_name}」到「{to_name}」的驾车路线…"

    def _resolve(name: str) -> GeoPoint | None:
        """解析地名 → 坐标。**与 `search_nearby` 同一口径**：本地地名表优先，
        查不到再走高德地理编码。解析失败返回 None。

        ★ 2026-09-27 实测修正（这一处曾被 mock 骗过，见下）：
        原来写的是 `resolver.resolve(name, city=city_name)` 并读 `resolved.point`，
        **两处都与真实契约不符** ——

          · `PlaceResolver.resolve(query)` 只有一个位置参数，**没有 `city`**；
            传了抛 TypeError。
          · 它返回的 `ResolvedPlace` 坐标在 `.lng` / `.lat`（还有 `.location`
            属性给出 `"lng,lat"`），**没有 `.point`**。

        两处错误都被下面的 `except Exception` 吞掉、静默降级到 `amap.geocode`，
        于是**"本地表优先"这一档从来没生效过**。后果不是崩，而是悄悄变差：
        `places.py` 里记着"本模块历史上最贵的一处修复"—— 高德地理编码对
        「汉中高铁站」返回的首条是「洋县高铁站(公交站)」。本地表优先正是为了
        避开它；这一档死了，就等于那个 bug 又回来了。

        为什么 `accept_m4_route.py` 当时全绿：那个脚本里的 `_FakeResolver`
        自己编了 `city=` 参数、自己编了 `.point` 字段 —— **假对象比真对象"宽容"，
        测试就把错误的契约固化了下来**。现在假对象已按真实签名重写，
        并加了一条"本地表必须赢过高德"的断言，这类错下次会被抓住。
        """
        if resolver is not None:
            try:
                resolved = resolver.resolve(name)
            except Exception as exc:  # noqa: BLE001
                logger.warning("get_route: resolver 解析「%s」失败: %s", name, exc)
                resolved = None
            if resolved is not None:
                return GeoPoint(lng=resolved.lng, lat=resolved.lat)
        try:
            return amap.geocode(name, city=city_name)
        except AmapError:
            return None

    from_pt = _resolve(from_name)
    to_pt = _resolve(to_name)
    if from_pt is None or to_pt is None:
        missing = [n for n, p in ((from_name, from_pt), (to_name, to_pt)) if p is None]
        return ToolResult(
            name=TOOL_ROUTE,
            kind="route",
            label=label,
            error=f"这些地名高德数据库里没找到坐标：{', '.join(missing)}（换个更具体的写法试试）",
        )

    # 用 `GeoPoint.location`（`"lng,lat"`，六位小数）而不是自己拼 ——
    # `search_nearby` 也是这么给高德的，两边格式保持一致。
    origin = from_pt.location
    destination = to_pt.location
    try:
        info = amap.direction_driving(origin=origin, destination=destination)
    except AmapError as exc:
        return ToolResult(
            name=TOOL_ROUTE,
            kind="route",
            label=label,
            error=f"高德路径规划失败：{exc}",
        )
    if info is None:
        # 少见分支：高德 status=1 但 route.paths 为空。
        # ★ 别在这句里写"跨城太远" —— 实测跨到境外回的是 AmapError 20011，
        # 走的是上面那条分支（见 amap.direction_driving 的注释）。
        return ToolResult(
            name=TOOL_ROUTE,
            kind="route",
            label=label,
            error="高德返回了空结果（这条起终点之间没有规划出驾车路线）",
        )

    distance_km = info["distance_m"] / 1000.0
    duration_min = round(info["duration_s"] / 60)
    hours, mins = divmod(duration_min, 60)
    if hours > 0 and mins > 0:
        duration_text = f"约 {hours} 小时 {mins} 分钟"
    elif hours > 0:
        duration_text = f"约 {hours} 小时"
    else:
        duration_text = f"约 {mins} 分钟"

    text = (
        f"从「{from_name}」到「{to_name}」自驾 {distance_km:.1f} 公里，"
        f"{duration_text}（高德驾车规划）"
    )
    return ToolResult(
        name=TOOL_ROUTE,
        kind="route",
        label=label,
        # 只给 text，不下发 cards。理由：路径线要前端画才有用，没画就只是一个
        # "{from} → {to}" 的两行字 + 一个距离数字，与 text 重复。让前端只在
        # 回答正文里展示 —— 与 `tool_count_text` 的 "返回 N 条" 不冲突（kind=route
        # 时 `count` 留 0，scene 不去查）。
        cards=[],
        text=text,
        # 给前端 cardKind 一个稳定值，便于后续扩展时按 kind 分流渲染。
        # 现在 kind=route 没有专门卡片模板，下发空数组即可，渲染不会出错。
    )


# ----------------------------------------------------------------------
# 入口
# ----------------------------------------------------------------------


def execute(
    name: str,
    args: dict[str, Any],
    *,
    store: KbStore,
    amap,
    resolver,
    planner,
    top_k: int,
    city_name: str,
    city_center: tuple[float, float] | None,
    hotel: dict[str, Any] | None = None,
) -> ToolResult:
    """执行一个工具。**不认识的名字返回一条 error 结果，不抛异常** ——
    调度器是模型，它可能编一个不存在的工具名出来，那不是系统故障。

    `hotel` 是本次行程已选定的住处（M4 阶段二），来自 `trip_context`，
    被 `search_nearby` 用来决定"附近"是相对哪儿、被 `plan_itinerary`
    用来决定从哪个区县开始排。其余工具不看它。

    `resolver` 是地名解析器（`places.PlaceResolver`），只有 `search_nearby` 用它
    ——把用户说的地名变成坐标是那个工具独有的需求。由调用方持有一个实例
    （本地地名表只读一次），而不是每次执行都新建。

    `planner` 是行程规划器（`itinerary.ItineraryPlanner`），只有 `plan_itinerary`
    用它。同样由调用方持有一个实例 —— 它构造时要读一次 `pois.json` 并算区县中心，
    每次提问重算一遍是白费。
    """
    if name == TOOL_NONE:
        return ToolResult(name=TOOL_NONE, kind="none")

    if name == TOOL_KNOWLEDGE:
        return _knowledge_search(args, store=store, top_k=top_k)

    if name == TOOL_PLAN:
        return _plan_itinerary(
            args, planner=planner, city_name=city_name, hotel=hotel
        )

    if name in (TOOL_NEARBY, TOOL_POI):
        if not amap.enabled:
            return ToolResult(
                name=name,
                kind="poi",
                error="本机未配置高德地图（AMAP_KEY），查不到真实地点数据",
            )
        if name == TOOL_NEARBY:
            return _search_nearby(
                args,
                amap=amap,
                resolver=resolver,
                city_name=city_name,
                city_center=city_center,
                hotel=hotel,
            )
        return _search_poi(args, amap=amap, city_name=city_name)

    if name == TOOL_ROUTE:
        if not amap.enabled:
            return ToolResult(
                name=TOOL_ROUTE,
                kind="route",
                error="本机未配置高德地图（AMAP_KEY），算不了真实路线",
            )
        return _get_route(args, amap=amap, resolver=resolver, city_name=city_name)

    return ToolResult(
        name=name, kind="none", error=f"没有名为「{name}」的工具"
    )
