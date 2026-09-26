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
)
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
        lines.append(f"- `{spec.name}`：{spec.title}")
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
            "注意：本机**未配置高德地图**，所以搜索真实地点的工具不可用。"
            "凡是问真实地点的问题，一律选 `none`，由生成阶段如实说明查不了。"
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


# ----------------------------------------------------------------------
# 三个执行器
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
    city_name: str,
    city_center: tuple[float, float] | None,
) -> ToolResult:
    center_name = str(args.get("center") or "").strip()
    keyword = str(args.get("keyword") or args.get("keywords") or "").strip()
    types = _infer_types(keyword, str(args.get("types") or ""))
    try:
        radius = int(args.get("radius") or DEFAULT_RADIUS)
    except (TypeError, ValueError):
        radius = DEFAULT_RADIUS

    label = (
        f"正在搜索「{center_name}」附近"
        if center_name
        else f"正在搜索{city_name}市区附近"
    )
    label += "的酒店…" if types == TYPES_ACCOMMODATION else "…"

    # 中心点：先拿地名去地理编码；拿不到就退回数据包声明的城市中心。
    # **不要静默用城市中心** —— 用户问的是"汉中高铁站附近"，结果给的是市中心，
    # 那批结果看着合理但答的不是他问的问题。所以退回时要在 note 里说清楚。
    center_note = ""
    location = ""
    center_label = ""
    if center_name:
        point = amap.geocode(center_name, city=city_name)
        if point is not None:
            location = point.location
            # 地理编码返回的**标准名称**可能与用户说的不是一回事
            # （实测：「汉中高铁站」落到「洋县高铁站(公交站)」）。
            # 所以这个名字既要替换进 label，也要写进给模型的 text ——
            # 让模型能直接读到"实际用的中心是哪儿"，而不是从结果地址里
            # 全是"洋县"反推出来。反推对了是运气，说清楚才是设计。
            center_label = point.formatted or center_name
            label = label.replace(f"「{center_name}」", f"「{center_label}」")
        else:
            center_note = f"没能定位到「{center_name}」，已改用{city_name}市区中心作为搜索中心。"

    if not location:
        if city_center is None:
            return ToolResult(
                name=TOOL_NEARBY,
                kind="poi",
                label=label,
                error=f"没能确定搜索中心点（「{center_name or '未指定'}」查不到，且数据包里没有城市中心坐标）",
            )
        location = f"{city_center[0]:.6f},{city_center[1]:.6f}"
        center_label = f"{city_name}市区中心"
        if not center_note:
            center_note = f"未指定搜索中心，已用{city_name}市区中心。"

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
        cards=[p.to_card() for p in pois[:12]],
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
        cards=[p.to_card() for p in pois[:6]],
    )


def _knowledge_search(args: dict[str, Any], *, store: KbStore, top_k: int) -> ToolResult:
    query = str(args.get("query") or args.get("keywords") or "").strip()
    if not query:
        return ToolResult(name=TOOL_KNOWLEDGE, kind="knowledge", error="knowledge_search 需要 query")

    label = "正在检索本地知识库…"
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


# ----------------------------------------------------------------------
# 入口
# ----------------------------------------------------------------------


def execute(
    name: str,
    args: dict[str, Any],
    *,
    store: KbStore,
    amap,
    top_k: int,
    city_name: str,
    city_center: tuple[float, float] | None,
) -> ToolResult:
    """执行一个工具。**不认识的名字返回一条 error 结果，不抛异常** ——
    调度器是模型，它可能编一个不存在的工具名出来，那不是系统故障。"""
    if name == TOOL_NONE:
        return ToolResult(name=TOOL_NONE, kind="none")

    if name == TOOL_KNOWLEDGE:
        return _knowledge_search(args, store=store, top_k=top_k)

    if name in (TOOL_NEARBY, TOOL_POI):
        if not amap.enabled:
            return ToolResult(
                name=name,
                kind="poi",
                error="本机未配置高德地图（AMAP_KEY），查不到真实地点数据",
            )
        if name == TOOL_NEARBY:
            return _search_nearby(
                args, amap=amap, city_name=city_name, city_center=city_center
            )
        return _search_poi(args, amap=amap, city_name=city_name)

    return ToolResult(
        name=name, kind="none", error=f"没有名为「{name}」的工具"
    )
