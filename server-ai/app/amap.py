"""高德地图 Web 服务客户端（M4 工具调用的数据来源）。

## 这个模块存在的意义

Agent 可以调用外部工具取真实地点数据。**真实地点信息必须来自工具，不能来自模型** ——
酒店名、地址、距离、经纬度这些一旦由模型生成，就会出现"听起来很合理但不存在的酒店"，
而用户是照着它去订房的。所以这里只做一件事：把高德的返回**原样、可核对地**搬进来。

## 三个必须处理的高德特性（都是会静默出错的那种）

1. **空字段返回 `[]` 而不是 `""`。** 官方文档明说："部分返回值当返回值存在时，
   将以字符串类型返回；当返回值不存在时，则以数组类型返回。"
   所以 `poi["address"]` 可能是 `""`、也可能是 `[]`，直接 `.strip()` 会 AttributeError。
   所有取值都过 `_s()`。同理 `biz_ext` 可能是 `{}`、也可能是 `[]`。
2. **`status` 是字符串 `"1"` / `"0"`**，不是布尔也不是数字。
   `status=0` 时必须**抛异常并带上 `infocode`** —— 否则"这个位置附近真没有酒店"
   与"key 无效 / 配额超限"会表现成同一个空列表，排查时完全看不出方向。
3. **坐标是 `"经度,纬度"` 字符串**，经度在前；官方要求小数点后不超过 6 位。

## 只做只读查询

不缓存、不落库。第一阶段不需要，而且高德的配额与数据时效性不是本项目能替它决定的。
每次请求都带超时：高德是同步 HTTP，正常几百毫秒返回，超时给太长会把"高德挂了"
表现成"整个回答卡住"，用户看不出在等什么。
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Any

import httpx

logger = logging.getLogger("hanyou.ai.amap")

# ----------------------------------------------------------------------
# POI 类型码
#
# 高德是三级编码：前 2 位大类 + 中 2 位中类 + 后 2 位小类。
# **指定大类会包含其下全部中类与小类**（官方文档：指定 100000 则 100100 等中类
# 与 100101 等小类都会被包含），所以这里只写大类，不写小类 ——
# 写小类反而会漏掉"民宿"这种不在我们预设清单里的形态。
# 编码对照来自官方 POI 分类表（大类序号 5 餐饮 / 10 住宿 / 11 风景名胜 / 15 交通设施）。
# ----------------------------------------------------------------------
TYPES_ACCOMMODATION = "100000"  # 住宿服务
TYPES_FOOD = "050000"  # 餐饮服务
TYPES_ATTRACTION = "110000"  # 风景名胜
TYPES_TRANSPORT = "150000"  # 交通设施服务

# 给模型看的"编码 -> 人话"对照。放这里而不是在提示词里硬写一遍，
# 是为了让提示词与代码不会各自漂移（改了编码只改这一处）。
TYPE_LABELS: dict[str, str] = {
    TYPES_ACCOMMODATION: "住宿（酒店 / 宾馆 / 民宿）",
    TYPES_FOOD: "餐饮（餐厅 / 小吃 / 特色风味）",
    TYPES_ATTRACTION: "景点（风景名胜 / 公园 / 博物馆）",
    TYPES_TRANSPORT: "交通（火车站 / 汽车站 / 机场）",
}

MAX_RADIUS = 50000  # 官方上限，超过按默认值处理
DEFAULT_RADIUS = 3000
MAX_OFFSET = 25  # 官方"强烈建议不超过 25，若超过 25 可能造成访问报错"


class AmapError(RuntimeError):
    """高德返回 status=0，或网络/解析失败。

    带上 `infocode` 是刻意的：高德的错误原因全在那串编码里
    （`INVALID_USER_KEY` 是 key 类型/值不对，`DAILY_QUERY_OVER_LIMIT` 是配额用完，
    `ENGINE_RESPONSE_DATA_ERROR` 是参数不对）。只回一句"查询失败"等于把排查成本
    全丢给下一个人。
    """

    def __init__(self, message: str, *, infocode: str = "", info: str = "") -> None:
        super().__init__(message)
        self.infocode = infocode
        self.info = info


# ----------------------------------------------------------------------
# 取值归一化
# ----------------------------------------------------------------------


def _s(value: Any) -> str:
    """把高德的值统一成字符串。

    高德的字段可能是 `str` / `list`（空值）/ `dict` / `None`，
    这里把 `list` 与 `dict` 一律当空 —— 因为高德用 `[]` 表示"这个字段没有值"，
    而不是表示一个空列表（见模块 docstring 第 1 条）。
    """
    if value is None or isinstance(value, (list, dict)):
        return ""
    return str(value).strip()


def _f(value: Any) -> float | None:
    text = _s(value)
    if not text:
        return None
    try:
        return float(text)
    except ValueError:
        return None


def _split_location(raw: Any) -> tuple[float, float] | None:
    """`"107.02,33.07"` -> `(107.02, 33.07)`。经度在前。"""
    text = _s(raw)
    if "," not in text:
        return None
    lng_text, _, lat_text = text.partition(",")
    try:
        return round(float(lng_text), 6), round(float(lat_text), 6)
    except ValueError:
        return None


# ----------------------------------------------------------------------
# 数据模型
# ----------------------------------------------------------------------


@dataclass
class GeoPoint:
    """地理编码的结果：一个地址对应的坐标。"""

    lng: float
    lat: float
    formatted: str = ""
    level: str = ""

    @property
    def location(self) -> str:
        return f"{self.lng:.6f},{self.lat:.6f}"


@dataclass
class AmapPoi:
    """一个高德 POI。字段是**高德原样**，没有一处由模型生成。"""

    id: str
    name: str
    type: str
    typecode: str
    address: str
    lng: float | None
    lat: float | None
    distance_m: int | None = None
    tel: str = ""
    adname: str = ""
    cityname: str = ""
    business_area: str = ""
    rating: str = ""
    cost: str = ""
    tag: str = ""
    photo: str = ""
    # 原始 JSON 留着只为排查（比如想知道某个字段到底长什么样），不进提示词也不进卡片
    raw: dict[str, Any] = field(default_factory=dict, repr=False)

    @property
    def location(self) -> str:
        if self.lng is None or self.lat is None:
            return ""
        return f"{self.lng:.6f},{self.lat:.6f}"

    @property
    def category(self) -> str:
        """`type` 是 `"餐饮服务;中餐厅;特色/地方风味餐厅"` 这样的三级串，取最后一级。"""
        parts = [p for p in self.type.split(";") if p]
        return parts[-1] if parts else ""

    @property
    def full_address(self) -> str:
        """地址为空时用"城市 + 区县"兜底 —— 宁可给一个粗地址，也不要给空白。"""
        if self.address:
            return self.address
        return "".join(x for x in (self.cityname, self.adname) if x)

    def to_card(self) -> dict[str, Any]:
        """前端卡片的数据。

        `distance_m` 只在周边搜索时有值（高德规定），所以可能为 None ——
        前端要能接受"没有距离"这一档，不能显示成 0 米。
        """
        return {
            "poi_id": self.id,
            "name": self.name,
            "address": self.full_address,
            "district": self.adname,
            "business_area": self.business_area,
            "typecode": self.typecode,
            "category": self.category,
            "lng": self.lng,
            "lat": self.lat,
            "distance_m": self.distance_m,
            "tel": self.tel,
            "rating": self.rating,
            "cost": self.cost,
            "tag": self.tag,
            "photo": self.photo,
            "source": "amap",
        }


# ----------------------------------------------------------------------
# 客户端
# ----------------------------------------------------------------------


def _parse_pois(payload: dict[str, Any]) -> list[AmapPoi]:
    pois = payload.get("pois")
    if not isinstance(pois, list):
        return []

    out: list[AmapPoi] = []
    for item in pois:
        if not isinstance(item, dict):
            continue
        point = _split_location(item.get("location"))
        if point is None:
            # 没有坐标的 POI 对本项目没用（附近搜索、路线规划都要它），直接丢
            continue

        # biz_ext 是"深度信息"，评分与人均消费在里面；空值同样是 []
        biz = item.get("biz_ext")
        biz = biz if isinstance(biz, dict) else {}

        photos = item.get("photos")
        photo = ""
        if isinstance(photos, list) and photos and isinstance(photos[0], dict):
            photo = _s(photos[0].get("url"))

        distance = _f(item.get("distance"))

        out.append(
            AmapPoi(
                id=_s(item.get("id")),
                name=_s(item.get("name")),
                type=_s(item.get("type")),
                typecode=_s(item.get("typecode")),
                address=_s(item.get("address")),
                lng=point[0],
                lat=point[1],
                distance_m=int(distance) if distance is not None else None,
                tel=_s(item.get("tel")),
                adname=_s(item.get("adname")),
                cityname=_s(item.get("cityname")),
                business_area=_s(item.get("business_area")),
                rating=_s(biz.get("rating")),
                cost=_s(biz.get("cost")),
                tag=_s(item.get("tag")),
                photo=photo,
                raw=item,
            )
        )
    return out


class AmapClient:
    """高德 Web 服务客户端。**只读**。"""

    def __init__(self, key: str, base_url: str, timeout_s: float = 8.0) -> None:
        self.key = key
        self.base_url = base_url.rstrip("/")
        self.timeout_s = timeout_s

    @property
    def enabled(self) -> bool:
        return bool(self.key)

    # ---- 底层 ----

    def _get(self, path: str, params: dict[str, Any]) -> dict[str, Any]:
        if not self.enabled:
            raise AmapError("未配置 AMAP_KEY，高德工具不可用")

        query = {k: v for k, v in params.items() if v not in (None, "")}
        query["key"] = self.key
        url = f"{self.base_url}{path}"

        try:
            with httpx.Client(timeout=self.timeout_s) as client:
                resp = client.get(url, params=query)
                resp.raise_for_status()
                payload = resp.json()
        except httpx.HTTPError as exc:
            raise AmapError(f"高德请求失败：{exc}") from exc
        except ValueError as exc:
            # resp.json() 在返回非 JSON（例如被网关拦截返回 HTML）时抛 JSONDecodeError
            raise AmapError(f"高德返回的不是合法 JSON：{exc}") from exc

        if not isinstance(payload, dict):
            raise AmapError("高德返回结构异常（不是对象）")

        if _s(payload.get("status")) != "1":
            info = _s(payload.get("info"))
            infocode = _s(payload.get("infocode"))
            raise AmapError(
                f"高德返回错误 {infocode}：{info}", infocode=infocode, info=info
            )

        return payload

    # ---- 三个只读能力 ----

    def geocode(self, address: str, city: str = "") -> GeoPoint | None:
        """地址 -> 坐标。查不到返回 None（不是异常：地址写错是常见输入）。"""
        payload = self._get("/geocode/geo", {"address": address, "city": city})
        geocodes = payload.get("geocodes")
        if not isinstance(geocodes, list) or not geocodes:
            return None
        first = geocodes[0]
        if not isinstance(first, dict):
            return None
        point = _split_location(first.get("location"))
        if point is None:
            return None
        return GeoPoint(
            lng=point[0],
            lat=point[1],
            formatted=_s(first.get("formatted_address")),
            level=_s(first.get("level")),
        )

    def search_around(
        self,
        location: str,
        *,
        keywords: str = "",
        types: str = "",
        radius: int = DEFAULT_RADIUS,
        offset: int = 20,
        page: int = 1,
    ) -> list[AmapPoi]:
        """周边搜索。`location` 是 `"经度,纬度"`。

        `keywords` 与 `types` 都不传时，高德会默认搜"餐饮 + 生活服务 + 商务住宅" ——
        那不是我们要的语义，所以调用方必须至少给一个。
        """
        payload = self._get(
            "/place/around",
            {
                "location": location,
                "keywords": keywords,
                "types": types,
                "radius": max(0, min(int(radius), MAX_RADIUS)),
                "offset": max(1, min(int(offset), MAX_OFFSET)),
                "page": max(1, int(page)),
                "sortrule": "distance",
                # extensions=all 才会返回 tel / adname / business_area / biz_ext(评分) / photos。
                # 这些正是卡片要显示的字段，用默认的 base 会全部拿不到。
                "extensions": "all",
            },
        )
        return _parse_pois(payload)

    def search_text(
        self,
        keywords: str,
        *,
        types: str = "",
        city: str = "",
        citylimit: bool = True,
        offset: int = 20,
        page: int = 1,
    ) -> list[AmapPoi]:
        """关键字搜索。`keywords` 与 `types` 二选一必填。

        `citylimit=true` 很重要：不加它，"汉中博物馆"可能返回别的城市的同名地点
        （官方例子：在深圳搜天安门会返回北京天安门）。
        """
        payload = self._get(
            "/place/text",
            {
                "keywords": keywords,
                "types": types,
                "city": city,
                "citylimit": "true" if (citylimit and city) else "",
                "offset": max(1, min(int(offset), MAX_OFFSET)),
                "page": max(1, int(page)),
                "extensions": "all",
            },
        )
        return _parse_pois(payload)


def create_client(settings) -> AmapClient:
    """按配置建客户端。没配 key 时返回一个 `enabled=False` 的客户端 ——
    调用方用 `client.enabled` 判断，而不是到处 `if settings.amap_key`。"""
    return AmapClient(
        key=settings.amap_key,
        base_url=settings.amap_base_url,
        timeout_s=settings.amap_timeout_s,
    )
