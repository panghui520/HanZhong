"""行程规划：把数据包里的资源点，排成按天的线路。

## 为什么需要这个模块（而不是继续靠知识库）

用户问「帮我规划一个汉中两日游」时，系统原本走 `knowledge_search`，
命中数据包里的《汉中行程怎么组合》—— 那是一篇**散文**，讲的是排行程的
**原则**（"先按方向分组""一天累计超过 360 分钟基本装不下"），
给不出"你的两天具体去哪几个点"。用户的评价是"说不明白"，这个评价是准确的。

**排行程的算法不该只存在于文档里。** 这个模块把那几条原则变成代码：

| 知识库里的原则 | 这里的实现 |
|---|---|
| 先按方向分组 | **同一天只排一个区县**（县域之间车程 1–2 小时，跨县串点时间全耗在路上） |
| 中心是汉台区 | 城市中心所在的区县**排在第一天** |
| 一天累计超过 360 分钟基本装不下 | `MINUTES_PER_DAY = 360` 作为每日预算 |
| 用"建议时长"做预算 | 每个 POI 的 `duration_min` 累加，装不下就换一天 |

## 只用景点与乡村点

`business_type` 有 5 类：`SCENIC`(18) / `RURAL_SPOT`(12) / `FOOD`(6) /
`LODGING`(4) / `TRANSPORT`(2)。行程的**游览点**只取前两类 ——
餐饮和住宿是配套（用户自己会决定在哪吃住，助手另有工具帮他找），
交通枢纽是出入口不是景点。把"汉中站"排进"第一天的景点"是明显的错误。

## 这一层不做的事

- **不算车程**：数据包里的经纬度是**基于规则的估算值**（见 `meta.json` 的
  `disclaimer`），据此算出的"车程"会是假精度。所以只给"同一天在同一区县"
  这个**结构性保证**，具体怎么走交给地图导航。
- **不排餐饮与住宿**：不替用户决定在哪吃、住哪。已选住处另有上下文承载。
- **不做门票与开放时间的实时校验**：数据包里的价格/时间是静态整理值，
  输出时如实标注来源，不假装是实时信息。
"""

from __future__ import annotations

import json
import logging
import math
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

logger = logging.getLogger("hanyou.ai.itinerary")

# 每日游览时长预算（分钟）。取自知识库《汉中行程怎么组合》里那条经验值，
# 不是拍脑袋定的：它已经被写进给用户看的文档，这里只是让代码与文档一致。
MINUTES_PER_DAY = 360

# 行程里算作"游览点"的业态。其余是配套（餐饮/住宿/交通）。
TOUR_TYPES = ("SCENIC", "RURAL_SPOT")

# 景区级别排序用的权重。数据包里 level 的取值不止 A 级 ——
# 还有"省级乡村旅游示范村""有机认证基地""非遗工坊"这些乡村业态，
# 它们**不是低等级**，只是另一个体系，所以统一给一个中间权重，
# 不与 4A/3A 直接比高低（那会把乡村点全挤掉）。
_LEVEL_WEIGHT = {"5A": 100, "4A": 90, "3A": 80, "2A": 70}


def _level_weight(level: str) -> int:
    """A 级按等级，其余（乡村业态）统一给 60 —— 比 2A 低，但不为 0。"""
    return _LEVEL_WEIGHT.get(level, 60)


@dataclass(frozen=True)
class Stop:
    """一个游览点。字段全部来自 `citypack/<city>/pois.json`，无一处由模型生成。"""

    poi_id: str
    name: str
    district: str
    lng: float
    lat: float
    duration_min: int
    level: str
    business_type: str
    ticket_price: Any
    open_hours: str
    summary: str
    tags: tuple[str, ...] = ()

    def distance_to(self, other: "Stop") -> float:
        """两点间的近似距离（公里）。用等距圆柱投影 —— 汉中跨度只有 2 个经度，
        这个近似的误差远小于数据本身的估算误差，不值得引一个地理库。"""
        lat_mid = math.radians((self.lat + other.lat) / 2)
        dx = (other.lng - self.lng) * 111.32 * math.cos(lat_mid)
        dy = (other.lat - self.lat) * 110.57
        return math.hypot(dx, dy)


@dataclass
class DayPlan:
    day: int
    district: str
    stops: list[Stop] = field(default_factory=list)
    minutes: int = 0

    @property
    def over_budget(self) -> bool:
        return self.minutes > MINUTES_PER_DAY


@dataclass
class Itinerary:
    days: list[DayPlan]
    minutes_per_day: int
    notes: list[str] = field(default_factory=list)


# ----------------------------------------------------------------------
# 读数据
# ----------------------------------------------------------------------


def load_stops(city_dir: Path) -> list[Stop]:
    """读数据包里的游览点。读不到就返回空表（调用方按"没有可用数据"处理）。"""
    path = city_dir / "pois.json"
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        logger.warning("[行程] 没有找到 %s，无法规划行程", path)
        return []
    except (OSError, json.JSONDecodeError) as exc:
        logger.warning("[行程] %s 读取失败：%s", path, exc)
        return []

    items = raw if isinstance(raw, list) else raw.get("pois") or []
    stops: list[Stop] = []
    for item in items:
        if not isinstance(item, dict):
            continue
        if item.get("business_type") not in TOUR_TYPES:
            continue
        try:
            stops.append(
                Stop(
                    poi_id=str(item.get("id") or ""),
                    name=str(item.get("name") or ""),
                    district=str(item.get("district") or "（未知区县）"),
                    lng=float(item["lng"]),
                    lat=float(item["lat"]),
                    duration_min=int(item.get("duration_min") or 0),
                    level=str(item.get("level") or ""),
                    business_type=str(item.get("business_type") or ""),
                    ticket_price=item.get("ticket_price"),
                    open_hours=str(item.get("open_hours") or ""),
                    summary=str(item.get("summary") or ""),
                    tags=tuple(item.get("tags") or ()),
                )
            )
        except (KeyError, TypeError, ValueError) as exc:
            logger.warning("[行程] 跳过一条坏记录 %r：%s", item.get("name"), exc)
    logger.info("[行程] 已加载 %d 个游览点（来自 %d 条 POI）", len(stops), len(items))
    return stops


# ----------------------------------------------------------------------
# 排序与分配
# ----------------------------------------------------------------------


def _rank_by_level(district_stops: list[Stop], preference: str = "") -> list[Stop]:
    """按"值不值得去"排序：级别高的在前，同级按建议时长长的在前。

    **这一步只决定"选哪些点"，不决定顺序。** 顺序由 `_nearest_order` 负责 ——
    两者必须分开：第一版把它们混成一个排序（先按级别、再在整表上做最近邻），
    结果最近邻把级别**完全覆盖**了，汉台区排出的是"古汉台 + 拜将坛 + 天汉湿地"
    （三个 3A，因为它们在市中心、离中心点最近），而石门栈道、兴汉胜境两个 4A
    因为离得远被挤到预算之外。**"顺路"只在同一天已选的点之间才有意义。**

    `preference`（"亲子""自然风光"这类）命中 `tags` 的点**排到最前**，
    但**不改变级别权重** —— 偏好决定"先看哪些"，级别仍决定"值不值得去"。
    两者冲突时偏好优先，因为用户明确说了他要什么。
    """
    want = (preference or "").strip()

    def key(stop: Stop) -> tuple[int, int, int, str]:
        hit = 0
        if want:
            hit = 1 if any(want in tag or tag in want for tag in stop.tags) else 0
            # 偏好也可能写在名称里（"朱鹮梨园"里的"朱鹮"）
            if not hit and (want in stop.name or want in stop.summary):
                hit = 1
        return (-hit, -_level_weight(stop.level), -stop.duration_min, stop.name)

    return sorted(district_stops, key=key)


def _nearest_order(chosen: list[Stop]) -> list[Stop]:
    """把**当天已选的点**排成一条顺路的线：从它们的中心出发，每次挑最近的。

    只在这几个点之间做，所以不会像第一版那样把远处的高级别景点挤掉。
    """
    if len(chosen) <= 2:
        return chosen
    cur_lng = sum(s.lng for s in chosen) / len(chosen)
    cur_lat = sum(s.lat for s in chosen) / len(chosen)

    remaining = chosen[:]
    ordered: list[Stop] = []
    while remaining:
        nearest = min(
            remaining, key=lambda s: (s.lng - cur_lng) ** 2 + (s.lat - cur_lat) ** 2
        )
        remaining.remove(nearest)
        ordered.append(nearest)
        cur_lng, cur_lat = nearest.lng, nearest.lat
    return ordered


def _fill_day(stops: list[Stop], budget: int) -> tuple[list[Stop], int]:
    """从列表头部取点填满一天。**至少取一个** —— 否则时长超预算的点永远排不进去。"""
    chosen: list[Stop] = []
    minutes = 0
    for stop in stops:
        if chosen and minutes + stop.duration_min > budget:
            break
        chosen.append(stop)
        minutes += stop.duration_min
    return chosen, minutes


def plan(
    days: int,
    stops: list[Stop],
    *,
    minutes_per_day: int = MINUTES_PER_DAY,
    center_district: str = "",
    start_district: str = "",
    preference: str = "",
) -> Itinerary:
    """排出 `days` 天的行程。

    **同一天只排一个区县**（知识库里"方向相反不要排同一天"的可执行版本）。
    区县的出场顺序：`start_district`（用户已选住处所在区县，如果有）→
    城市中心所在区县 → 其余按"可游览总时长"降序。

    第一轮每个区县各占一天；如果天数还有富余，再回头给"还有没排完的点"的
    区县加天（`rounds` 循环）。这样两日游是"中心一天 + 最值得去的周边一天"，
    而不是"中心两天、别处一天没有"。
    """
    days = max(1, min(int(days or 1), 7))  # 上限 7 天：再多就该分次旅行了
    notes: list[str] = []

    by_district: dict[str, list[Stop]] = defaultdict(list)
    for stop in stops:
        by_district[stop.district].append(stop)
    if not by_district:
        return Itinerary(days=[], minutes_per_day=minutes_per_day, notes=["数据包里没有可排的游览点。"])

    order = sorted(
        by_district, key=lambda d: (-sum(s.duration_min for s in by_district[d]), d)
    )
    # 起点优先：用户已选住处 -> 城市中心。都不在数据里就保持按体量排序。
    for preferred in (start_district, center_district):
        if preferred and preferred in order:
            order.remove(preferred)
            order.insert(0, preferred)
            break

    # 每个区县内部先按级别排好（决定"选哪些点"），每天选完再排顺路顺序（决定"怎么走"）
    pending = {d: _rank_by_level(list(by_district[d]), preference) for d in order}
    plans: list[DayPlan] = []
    day_no = 1

    while day_no <= days:
        progressed = False
        for district in order:
            if day_no > days:
                break
            left = pending.get(district) or []
            if not left:
                continue
            chosen, minutes = _fill_day(left, minutes_per_day)
            if not chosen:
                continue
            del left[: len(chosen)]
            plans.append(
                DayPlan(
                    day=day_no,
                    district=district,
                    stops=_nearest_order(chosen),
                    minutes=minutes,
                )
            )
            day_no += 1
            progressed = True
        if not progressed:
            break

    if len(plans) < days:
        notes.append(
            f"数据包里可排的游览点只够 {len(plans)} 天，没有凑满 {days} 天 —— "
            "宁可少排一天，也不重复推荐同一个点。"
        )
    if start_district and start_district in by_district:
        notes.append(f"行程从您已选的住处所在的{start_district}开始。")

    return Itinerary(days=plans, minutes_per_day=minutes_per_day, notes=notes)


def center_district_of(stops: list[Stop], center: tuple[float, float] | None) -> str:
    """城市中心坐标落在哪个区县 —— 取距离最近的那个点所属的区县。

    用"最近的点"而不是"包含中心的行政区"：后者需要区县边界数据（本项目没有），
    而城市中心必然紧邻该区县的某个资源点，这个近似的结论是一样的。
    """
    if not stops or center is None:
        return ""
    lng, lat = center
    probe = Stop(
        poi_id="", name="", district="", lng=lng, lat=lat, duration_min=0,
        level="", business_type="", ticket_price=None, open_hours="", summary="",
    )
    return min(stops, key=lambda s: s.distance_to(probe)).district


class ItineraryPlanner:
    """行程规划器。**数据包只读一次**，之后每次规划都复用这份内存里的点。

    与 `places.PlaceResolver` 同一个套路：在 `AgentService` 构造时建一次，
    而不是每次提问都重读一遍 `pois.json`、重新算一遍区县中心。
    """

    def __init__(self, city_dir: Path, city_center: tuple[float, float] | None) -> None:
        self.stops = load_stops(city_dir)
        self.center_district = center_district_of(self.stops, city_center)

    @property
    def ready(self) -> bool:
        """有没有可排的点。数据包读不到时为 False，调用方据此给出明确说明。"""
        return bool(self.stops)

    def district_of(self, location: str) -> str:
        """把「lng,lat」这样的坐标串落到一个区县。解析不出来返回空串。

        用途只有一个：用户已选住处时，行程要**从他住的区县开始排**
        （第二天不该让他先跨一个县再回来）。而上下文里的住处只有坐标，
        没有区县字段 —— 与其在 Java 侧加一个可能填错的字段，
        不如在这里用它旁边最近的那个资源点所属区县反推，规则只有一条。

        解析失败**返回空串而不是抛异常**：住处坐标是外部传进来的，
        格式不对不该让整轮对话失败，退回"按体量排序"就是了。
        """
        if not self.stops:
            return ""
        parts = str(location or "").split(",")
        if len(parts) != 2:
            return ""
        try:
            point = (float(parts[0]), float(parts[1]))
        except ValueError:
            return ""
        return center_district_of(self.stops, point)

    def plan(
        self, days: int, *, preference: str = "", start_district: str = ""
    ) -> Itinerary:
        return plan(
            days,
            self.stops,
            center_district=self.center_district,
            start_district=start_district,
            preference=preference,
        )
