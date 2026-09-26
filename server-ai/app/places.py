"""地名解析：把游客说的地名，变成可信的搜索中心坐标。

## 这个模块解决的是哪一个真实错误

用户问「汉中高铁站附近有哪些酒店」，得到的是一整屏**洋县**的宾馆。

根因不在提示词也不在模型，而在 `amap.geocode()` 这一步：高德地理编码对
「汉中高铁站」返回两条候选，**第一条是「洋县高铁站(公交站)」**（洋县也通高铁，
名字里同样带「高铁站」），而我们只取了第一条。于是汉台区的问题被答成了洋县。

实测对照（2026-09-26）：

| 查询 | `geocode/geo` 首条 | `place/text` 首条 |
|---|---|---|
| 汉中高铁站 | ❌ 洋县高铁站(公交站) 107.525665,33.226810 | ✅ 汉中站（汉台区）107.029962,33.090721 |
| 汉台区 | ✅ 汉台区 107.032010,33.067523 | ✅ 汉台区 107.032010,33.067523 |
| 宁强县 | ✅ 宁强县 106.257636,32.830032 | ✅ 宁强县 106.257636,32.830032 |

**`geocode` 擅长行政区划、不擅长"口语地名"；`place/text` 反过来。**
所以这里不选一个，而是按地名形态分工（见 `resolve` 的三级）。

## 为什么不把「标准查询词」写进表里，而是存坐标

存坐标 = **不依赖网络**。区县中心与主城区火车站的位置在一个比赛周期内不会变，
存本地既快又稳，`DEMO_MODE` 断网演示时也照样能定位。
本表没收录的地名仍由高德兜底，能力不减。

## 这一层只做「地名 -> 坐标」

不判断用户想找什么、不决定搜索半径、不碰卡片。那些在 `tools.py`。
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Any

logger = logging.getLogger("hanyou.ai.places")

# 解析来源。写进日志与提示词，让"这个中心是怎么来的"永远可回答。
SOURCE_TABLE = "table"  # 本地地名表
SOURCE_TEXT = "amap-text"  # 高德 POI 关键词搜索
SOURCE_GEOCODE = "amap-geocode"  # 高德地理编码兜底

# 匹配本地表前要剥掉的行政区前缀。
# 用户会说「汉中市汉台区」「陕西省汉中市宁强县」，而表里存的是「汉台区」「宁强县」。
# **只剥这些固定前缀，不做通用后缀剥离** —— 「洋县高铁站」里的「洋县」是地名的一部分，
# 按后缀剥会把它错误地当成「洋县」这个区县，而那正是我们要修的错误形态。
_ADMIN_PREFIXES = ("陕西省", "汉中市")


def _norm(text: str) -> str:
    """归一化：去首尾空白与全角空格。**不做繁简/错别字纠正** —— 那是另一个量级的问题。"""
    return str(text or "").strip().replace("\u3000", "").strip()


def _strip_admin(text: str) -> str:
    """循环剥掉行政区前缀。`陕西省汉中市宁强县` -> `宁强县`。"""
    out = text
    changed = True
    while changed:
        changed = False
        for prefix in _ADMIN_PREFIXES:
            if out.startswith(prefix) and len(out) > len(prefix):
                out = out[len(prefix) :]
                changed = True
    return out


def _relevant(query: str, name: str) -> bool:
    """高德返回的 POI 名，与用户说的地名**是不是同一件事**。

    `place/text` 是**模糊**搜索：查不到时它照样返回一堆无关 POI。
    实测 `search_text("不存在的地方xyz")` 的首条是「一个嗦粉的地方（汉台区）」——
    无条件取第一条，会把"用户打错了一个字"静默变成"一个随机的餐馆"，
    而且这个餐馆还带着看似合理的距离与地址，用户无从分辨。

    判据刻意选得**简单且可解释**（不做分词、不引依赖）：
    一方包含另一方，或**查询词的前两个字**出现在 POI 名里。

    | 查询 | 高德首条 | 判定 |
    |---|---|---|
    | 石门栈道 | 石门栈道风景区（勉县） | ✅ 前两字「石门」命中 |
    | 汉中博物馆 | 汉中市博物馆（汉台区） | ✅ 前两字「汉中」命中（中间插了「市」） |
    | 不存在的地方xyz | 一个嗦粉的地方（汉台区） | ❌ 两字头「不存」不在名字里 |

    为什么用"前两字"而不是"任意公共子串"：`不存在的地方xyz` 与
    `一个嗦粉的地方` 的公共子串是「地方」——**长度 2 就通过，等于没挡**。
    查询词的开头部分才真正代表用户在指哪儿。
    """
    if not query or not name:
        return False
    if query in name or name in query:
        return True
    head = query[:2] if len(query) >= 2 else query
    return head in name


@dataclass(frozen=True)
class ResolvedPlace:
    """一个解析成功的地名。字段全部来自数据表或高德，没有一处由模型生成。"""

    name: str
    lng: float
    lat: float
    source: str
    district: str = ""
    # 只用于建索引（本地表里的别名），解析结果本身不带它。
    aliases: tuple[str, ...] = ()

    @property
    def location(self) -> str:
        """高德要的 `"经度,纬度"`，经度在前。"""
        return f"{self.lng:.6f},{self.lat:.6f}"

    @property
    def label(self) -> str:
        """写进 label 与给模型 header 的显示名。

        带上所属区县（`汉中站（汉台区）`）：区县是用户判断"这是不是我要的地方"
        的第一依据 —— 本次的 bug 恰恰是"名字看着对、区县完全不对"。
        区县条目自身不重复显示（`汉中站（汉台区）` 而不是 `汉台区（汉台区）`）。
        """
        if self.district and self.district != self.name:
            return f"{self.name}（{self.district}）"
        return self.name


class PlaceTable:
    """本地地名表。从 `citypack/<city>/places.json` 读，读不到就是一张空表。

    空表不是错误：没有这个文件时整条链路退化成"直接问高德"，
    也就是本次修复之前的行为 —— 能用，只是慢一点、且会踩「汉中高铁站」那个坑。
    所以这里**只记一条 warning，不抛异常**。
    """

    def __init__(self, entries: list[ResolvedPlace]) -> None:
        self._by_key: dict[str, ResolvedPlace] = {}
        for place in entries:
            self._by_key.setdefault(_norm(place.name), place)
            for alias in place.aliases:
                self._by_key.setdefault(_norm(alias), place)

    def __len__(self) -> int:
        return len(self._by_key)

    def lookup(self, query: str) -> ResolvedPlace | None:
        """精确匹配名字或别名；再试一次剥掉行政区前缀之后。"""
        key = _norm(query)
        if not key:
            return None
        hit = self._by_key.get(key)
        if hit is not None:
            return hit
        stripped = _strip_admin(key)
        if stripped != key:
            return self._by_key.get(stripped)
        return None

    @classmethod
    def from_file(cls, path: Path) -> "PlaceTable":
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            logger.warning(
                "[地名] 没有找到 %s，地名解析将直接走高德（「汉中高铁站」这类"
                "口语地名可能落到别的区县）",
                path,
            )
            return cls([])
        except (OSError, json.JSONDecodeError) as exc:
            logger.warning("[地名] %s 读取失败，按空表处理：%s", path, exc)
            return cls([])

        entries: list[ResolvedPlace] = []
        for group in ("districts", "landmarks"):
            for item in raw.get(group) or []:
                if not isinstance(item, dict):
                    continue
                try:
                    place = ResolvedPlace(
                        name=_norm(item.get("name")),
                        lng=float(item["lng"]),
                        lat=float(item["lat"]),
                        source=SOURCE_TABLE,
                        district=_norm(item.get("district"))
                        # 区县条目没有 district 字段：它自己就是那个区县
                        or (_norm(item.get("name")) if group == "districts" else ""),
                        aliases=tuple(_norm(a) for a in (item.get("aliases") or [])),
                    )
                except (KeyError, TypeError, ValueError) as exc:
                    logger.warning("[地名] 跳过一条坏记录 %r：%s", item.get("name"), exc)
                    continue
                if not place.name:
                    continue
                entries.append(place)

        table = cls(entries)
        logger.info("[地名] 已加载 %d 条地名（含别名索引）", len(table))
        return table


class PlaceResolver:
    """三级解析。**顺序不可换**，理由见每一级。"""

    def __init__(self, amap: Any, city_name: str, table: PlaceTable | None = None) -> None:
        self._amap = amap
        self._city = city_name
        self._table = table or PlaceTable([])

    def resolve(self, query: str) -> ResolvedPlace | None:
        """解析失败返回 None（**不抛异常**）。

        解析不出地名是常见输入（用户打错字、说了个不存在的地方），
        调用方据此退回城市中心并在回答里说明 —— 比让整轮对话失败合理。
        """
        key = _norm(query)
        if not key:
            return None

        # 一级：本地地名表。零成本、零网络、结果确定。
        # **只认精确匹配**（含别名与行政区前缀剥离），不做"包含匹配" ——
        # 「洋县高铁站」包含「洋县」，按包含匹配会落到洋县区县中心，
        # 而用户要的是那个车站。这类"更具体的地名"必须交给二级去搜。
        hit = self._table.lookup(key)
        if hit is not None:
            logger.info("[地名] %r -> %s（本地表）", query, hit.label)
            return hit

        # 二级：高德 POI 关键词搜索。
        # 它对**口语地名**（汉中高铁站、汉中火车站、石门栈道）比地理编码准得多，
        # 因为它搜的是"地点"而不是"地址"。citylimit 已由 amap.search_text 锁定在本市。
        place = self._search_text(key)
        if place is not None:
            logger.info("[地名] %r -> %s（高德 POI 搜索）", query, place.label)
            return place

        # 三级：地理编码。对行政区划与规范地址最准，对口语地名最不准，
        # 所以放在最后 —— 前两级都不中时，它的结果仍然比"退回城市中心"好。
        place = self._geocode(key)
        if place is not None:
            logger.info("[地名] %r -> %s（高德地理编码）", query, place.label)
            return place

        logger.info("[地名] %r 三级都没解析出结果", query)
        return None

    # ---- 二三级实现 ----

    def _search_text(self, key: str) -> ResolvedPlace | None:
        try:
            pois = self._amap.search_text(key, city=self._city)
        except Exception as exc:  # noqa: BLE001
            # 高德抖动不该让解析中断：还有第三级。**降级必须可观测**，所以记 warning。
            logger.warning("[地名] POI 搜索 %r 失败，转地理编码：%s", key, exc)
            return None
        for poi in pois:
            if poi.lng is None or poi.lat is None:
                continue
            # **逐条校验相关性，不是只看第一条。**
            # 首条不相关但第二条相关是常见情形（高德会按"热度/距离"排，
            # 不按字面匹配度排），直接跳过首条就退回地理编码，白白浪费一次好结果。
            if not _relevant(key, poi.name):
                continue
            return ResolvedPlace(
                name=poi.name or key,
                lng=poi.lng,
                lat=poi.lat,
                source=SOURCE_TEXT,
                district=poi.adname,
            )
        return None

    def _geocode(self, key: str) -> ResolvedPlace | None:
        try:
            point = self._amap.geocode(key, city=self._city)
        except Exception as exc:  # noqa: BLE001
            logger.warning("[地名] 地理编码 %r 失败：%s", key, exc)
            return None
        if point is None:
            return None
        # 地理编码同样是模糊的：实测「不存在的地方xyz」会返回
        # 「陕西省汉中市镇巴县小洋镇」。它比 POI 搜索更隐蔽 ——
        # 返回的是一个**看着完全合理的行政区地址**，用户不会怀疑。
        # 所以这里要过同一道相关性校验（拿标准地址做比对对象）。
        name = point.formatted or key
        if not _relevant(key, name):
            logger.info("[地名] 地理编码 %r 的结果 %r 与查询不相关，视为没查到", key, name)
            return None
        return ResolvedPlace(
            name=name,
            lng=point.lng,
            lat=point.lat,
            source=SOURCE_GEOCODE,
        )


def create_resolver(settings: Any, amap: Any) -> PlaceResolver:
    """按配置建解析器。地名表路径 = `citypack/<city>/places.json`。"""
    table = PlaceTable.from_file(settings.city_dir / "places.json")
    return PlaceResolver(amap, settings.city_name, table)
