#!/usr/bin/env python
"""M4 工具调用链路的离线回归（**不联网、不花钱、退出码即结论**）。

用法（在仓库根目录执行）：
    <venv>/Scripts/python.exe scripts/eval_agent.py

**为什么需要它。**
M4 的链路比 M3 多了一层"模型决定调什么工具"，于是出错的地方也从一处变成三处：

  · **工具清单渲染错了** —— 高德没配却仍然把它列给模型，模型会选它，
    然后拿一个空结果去回答，用户看到的是"附近没有酒店"（而事实是本机没配 key）；
  · **调度器输出解析太脆** —— 模型多打一句"好的，我判断…"就整个解析失败，
    一轮对话直接挂掉；
  · **高德返回归一化漏了空值** —— 高德用 `[]` 表示"这个字段没有值"，
    直接 `.strip()` 会 AttributeError，而这类字段（电话、商圈、评分）恰好都是可选字段，
    所以**只有部分 POI 会炸**，看起来像"随机失败"。

这三处都不需要真的调模型或高德就能测：工具清单是纯字符串拼接、解析是纯函数、
归一化可以用构造的响应喂。所以这个脚本全程离线，毫秒级跑完。

**它不能替代什么。**
真调高德的那部分（key 类型对不对、配额还有没有、返回结构与文档是否一致）
只能靠实网探测，见 `docs/验收记录-M4.md` 里的实网记录。这里测的是
"**我们自己的代码在高德返回各种形状时是否稳**"，不是"高德是否可用"。

**退出码**：0 = 全部通过；1 = 任一条不成立。
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server-ai"))

from app import amap  # noqa: E402
from app.agent import _parse_decision  # noqa: E402
from app.amap import (  # noqa: E402
    TYPES_ACCOMMODATION,
    TYPES_ATTRACTION,
    TYPES_FOOD,
    TYPES_TRANSPORT,
    AmapClient,
    AmapError,
    _parse_pois,
    _s,
    _split_location,
)
from app.tools import (  # noqa: E402
    TOOL_KNOWLEDGE,
    TOOL_NEARBY,
    TOOL_NONE,
    TOOL_PLAN,
    TOOL_POI,
    TOOL_ROUTE,
    TOOL_SPECS,
    _infer_types,
    available_tools,
    tool_menu,
)


def main() -> int:
    problems: list[str] = []
    passed = 0

    def check(ok: bool, label: str) -> None:
        nonlocal passed
        if ok:
            passed += 1
        else:
            problems.append(label)

    # ------------------------------------------------------------------
    # 1. 工具规格自检
    # ------------------------------------------------------------------
    print("【1】工具规格")

    names = [spec.name for spec in TOOL_SPECS]
    check(len(names) == len(set(names)), f"工具名有重复：{names}")
    check(
        all(spec.title and spec.args_hint for spec in TOOL_SPECS),
        "有工具缺 title 或 args_hint（会渲染出空行给模型看）",
    )
    needs_amap = {spec.name for spec in TOOL_SPECS if spec.needs_amap}
    # ★ 2026-09-28 补 `TOOL_ROUTE`：`get_route`（两点驾车）加进来时带了
    #   `needs_amap=True`，但这张期望表没跟着改 —— 于是这条断言从那天起一直是红的，
    #   而它红得"安静"（没有 CI，只有人手动跑）。漏在这里的代价很实际：
    #   它同时意味着"高德没配时把 get_route 列给模型"这类错不会被发现。
    check(
        needs_amap == {TOOL_NEARBY, TOOL_POI, TOOL_ROUTE},
        f"needs_amap 标记不对：{needs_amap}",
    )
    print(f"    工具 {len(TOOL_SPECS)} 个：{'、'.join(names)}")

    # ------------------------------------------------------------------
    # 2. 工具清单渲染（amap 开 / 关两种）
    # ------------------------------------------------------------------
    print("【2】工具清单渲染")

    menu_on = tool_menu(True, "汉中")
    menu_off = tool_menu(False, "汉中")
    names_on = [spec.name for spec in available_tools(True)]
    names_off = [spec.name for spec in available_tools(False)]

    # 判断"哪些工具可用"要看**列表**，不要看清单字符串里有没有出现工具名 ——
    # 那句"未配置高德地图"的说明里本来就会提到那两个工具名，
    # 用子串匹配会被自己写的说明误命中。
    check(
        set(names_on) == {TOOL_NEARBY, TOOL_POI, TOOL_ROUTE, TOOL_KNOWLEDGE, TOOL_PLAN},
        f"amap 已配时的可用工具不对：{names_on}",
    )
    # 高德没配时剩下的**不是只有知识库**：行程规划读本地数据包，不需要联网，
    # 所以它必须还在。写成 `== [TOOL_KNOWLEDGE]` 是旧版本的真相，
    # 加了 plan_itinerary 之后那句就变成假的了。
    check(
        names_off == [TOOL_KNOWLEDGE, TOOL_PLAN],
        f"amap 未配时应剩知识库 + 行程规划，实际 {names_off}",
    )
    for name in names_on:
        check(f"- `{name}`：" in menu_on, f"amap 已配时清单里缺工具 {name}")
    check(f"- `{TOOL_NONE}`：" in menu_on, "清单里缺 none")
    check("100000=" in menu_on, "amap 已配时清单里没给出 types 编码对照")

    check(
        "未配置高德地图" in menu_off,
        "amap 未配时清单里没有说明原因（模型会把'查不到'当成地理事实）",
    )
    check(
        "100000=" not in menu_off,
        "amap 未配时不该给出 types 编码对照（等于暗示那些工具能用）",
    )
    # 高德没配时，清单里仍然列着 plan_itinerary，而上面一句说"问真实地点一律选 none"。
    # 不补一句"这两个不受影响"，模型会把"排两天行程"也当成问真实地点而弃用。
    check(
        f"- `{TOOL_PLAN}`：" in menu_off and "不受影响" in menu_off,
        "amap 未配时清单没有说明行程/知识库工具仍然可用",
    )
    # `{city}` 占位符必须已经被替换掉：漏替换的表现是模型读到字面的"{city}"。
    check("{city}" not in menu_on and "汉中" in menu_on, "工具清单里的 {city} 占位符没被替换")
    print("    amap 开：5 工具 + 类型码；amap 关：知识库 + 行程规划 + 原因说明")

    # ------------------------------------------------------------------
    # 3. 调度器输出解析（脏输出宽容度）
    # ------------------------------------------------------------------
    print("【3】调度器输出解析")

    dirty_cases: list[tuple[str, str, dict]] = [
        (
            '{"tool": "search_nearby", "args": {"center": "汉中高铁站", "types": "100000"}}',
            TOOL_NEARBY,
            {"center": "汉中高铁站", "types": "100000"},
        ),
        (
            '```json\n{"tool": "none", "args": {}}\n```',
            TOOL_NONE,
            {},
        ),
        (
            '好的，我判断应该调用：{"tool": "search_poi", "args": {"keywords": "汉中博物馆"}}',
            TOOL_POI,
            {"keywords": "汉中博物馆"},
        ),
        (
            # 缺 args：不能崩，当成空参
            '{"tool": "search_nearby"}',
            TOOL_NEARBY,
            {},
        ),
        (
            # 模型编了个不存在的工具名：降级成 none，**不抛异常**
            '{"tool": "call_amap_v2", "args": {"x": 1}}',
            TOOL_NONE,
            {},
        ),
        (
            # args 不是对象：同样降级成空参
            '{"tool": "search_nearby", "args": "汉中高铁站"}',
            TOOL_NEARBY,
            {},
        ),
    ]
    for raw, want_name, want_args in dirty_cases:
        try:
            got_name, got_args = _parse_decision(raw)
        except Exception as exc:  # noqa: BLE001
            check(False, f"解析 {raw[:40]!r} 抛了异常：{exc}")
            continue
        check(
            got_name == want_name and got_args == want_args,
            f"解析 {raw[:40]!r} 得到 ({got_name}, {got_args})，期望 ({want_name}, {want_args})",
        )

    # 完全没 JSON 时必须抛（这是真的没法救，交给上层退回知识库）
    for broken in ("", "   ", "我不知道该用什么工具"):
        try:
            _parse_decision(broken)
            check(False, f"{broken!r} 应该解析失败但没有")
        except ValueError:
            check(True, "")

    print(f"    {len(dirty_cases)} 条脏输出 + 3 条不可救输出，行为符合期望")

    # ------------------------------------------------------------------
    # 4. 高德返回归一化（用构造的响应，覆盖"空字段是 []"这个坑）
    # ------------------------------------------------------------------
    print("【4】高德返回归一化")

    check(_s([]) == "" and _s({}) == "" and _s(None) == "", "_s 没有把 [] / {} / None 归一成空串")
    check(_s("  汉中市  ") == "汉中市", "_s 没有 strip")
    check(_s("100") == "100", "_s 把数字字符串改坏了")
    check(_split_location("107.031234,33.071234") == (107.031234, 33.071234), "_split_location 解析失败")
    check(_split_location([]) is None, "_split_location 没处理空值 []")
    check(_split_location("107.03") is None, "_split_location 没处理缺纬度")

    fake_payload = {
        "status": "1",
        "info": "OK",
        "infocode": "10000",
        "count": "3",
        "pois": [
            {
                "id": "B001",
                "name": "汉中示例酒店",
                "type": "住宿服务;宾馆酒店;五星级宾馆",
                "typecode": "100101",
                "address": "汉台区示例路 1 号",
                "location": "107.031234,33.071234",
                "distance": "320",
                "tel": "0916-0000000",
                "adname": "汉台区",
                "cityname": "汉中市",
                "business_area": "中心广场",
                "biz_ext": {"rating": "4.6", "cost": "380"},
                "photos": [{"title": "", "url": "https://example.invalid/a.jpg"}],
            },
            {
                # 这一条把可选字段全空掉，且**按高德的真实行为用 [] 而不是 ""**
                "id": "B002",
                "name": "汉中示例旅馆",
                "type": "住宿服务;旅馆招待所;",
                "typecode": "100200",
                "address": [],
                "location": "107.041234,33.081234",
                "distance": "1500",
                "tel": [],
                "adname": "汉台区",
                "cityname": "汉中市",
                "business_area": [],
                "biz_ext": [],
                "photos": [],
            },
            {
                # 没有坐标：对本项目没用（附近搜索、路线规划都要它），必须被丢掉
                "id": "B003",
                "name": "没有坐标的记录",
                "location": [],
            },
        ],
    }

    pois = _parse_pois(fake_payload)
    check(len(pois) == 2, f"应保留 2 条（丢掉无坐标的那条），实际 {len(pois)}")

    if len(pois) == 2:
        a, b = pois
        check(a.name == "汉中示例酒店" and a.distance_m == 320, "第 1 条的名称/距离不对")
        check(a.rating == "4.6" and a.cost == "380", "biz_ext 里的评分/人均没取到")
        check(a.photo.endswith("a.jpg"), "photos[0].url 没取到")
        check(a.location == "107.031234,33.071234", f"location 还原不对：{a.location}")
        check(a.category == "五星级宾馆", f"category 取错：{a.category}")

        check(b.address == "" and b.tel == "", "空字段 [] 没有被归一成空串")
        check(b.rating == "" and b.photo == "", "空的 biz_ext / photos 没被处理")
        check(b.full_address == "汉中市汉台区", f"地址兜底不对：{b.full_address}")
        check(b.category == "旅馆招待所", f"category 取错（尾部空段没跳过）：{b.category}")

        card = a.to_card()
        check(card["source"] == "amap", "卡片的 source 不是 amap")
        check(
            card["distance_m"] == 320 and card["typecode"] == "100101",
            "卡片字段不对",
        )

    # status=0 必须抛异常并带 infocode，不能静默返回空列表
    class _FakeResp:
        def __init__(self, payload):
            self._payload = payload

        def raise_for_status(self):
            return None

        def json(self):
            return self._payload

    class _FakeClient:
        payload: dict = {}

        def __init__(self, **kwargs):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

        def get(self, url, params=None):
            return _FakeResp(type(self).payload)

    original_client = amap.httpx.Client
    amap.httpx.Client = _FakeClient
    try:
        _FakeClient.payload = {"status": "0", "info": "INVALID_USER_KEY", "infocode": "10001"}
        client = AmapClient(key="fake", base_url="https://example.invalid/v3")
        try:
            client.search_around("107.0,33.0", types=TYPES_ACCOMMODATION)
            check(False, "status=0 时没有抛异常（会被当成'附近没有酒店'）")
        except AmapError as exc:
            check(exc.infocode == "10001", f"异常里没带 infocode：{exc}")

        # 正常响应能走通
        _FakeClient.payload = fake_payload
        got = client.search_around("107.0,33.0", types=TYPES_ACCOMMODATION)
        check(len(got) == 2, "正常响应下 search_around 返回条数不对")
    finally:
        amap.httpx.Client = original_client

    print("    空值 / 无坐标 / status=0 三种形状都已覆盖")

    # ------------------------------------------------------------------
    # 5. 提示词渲染（占位符被替换、JSON 示例的单层花括号没被破坏）
    # ------------------------------------------------------------------
    print("【5】提示词渲染")

    from app.llm import SYSTEM_PROMPT_TOOLS, _fill  # noqa: E402

    rendered = _fill(SYSTEM_PROMPT_TOOLS, city="汉中")
    check("{city}" not in rendered, "SYSTEM_PROMPT_TOOLS 里残留 {city} 占位符")
    check("汉中" in rendered, "SYSTEM_PROMPT_TOOLS 没替换成城市名")

    from app.llm import build_router_messages  # noqa: E402

    msgs = build_router_messages("汉中高铁站附近有什么酒店", menu_on, city="汉中")
    sys_text = msgs[0]["content"]
    check("{tool_menu}" not in sys_text, "ROUTER_PROMPT 里残留 {tool_menu} 占位符")
    check("{city}" not in sys_text, "ROUTER_PROMPT 里残留 {city} 占位符")
    check(TOOL_NEARBY in sys_text, "ROUTER_PROMPT 里没有工具清单")
    # _fill 是逐键 replace，不是 str.format —— JSON 示例必须是单层花括号。
    # 写成双花括号的话会被原样留在提示词里，模型看到的是一坨 {{"tool": ...}}。
    check('{"tool": "工具名"' in sys_text, "JSON 示例的单层花括号被破坏了")
    check("{{" not in sys_text, "提示词里出现了双花括号（_fill 不会还原它们）")
    check(msgs[1]["content"] == "汉中高铁站附近有什么酒店", "user 消息不是原问题")

    print("    两份新提示词占位符替换正确，JSON 示例保持单层花括号")

    # ------------------------------------------------------------------
    # 6. 关键词 -> 类型码推断（调度器没给 types 时的兜底）
    # ------------------------------------------------------------------
    print("【6】types 推断兜底")

    infer_cases: list[tuple[str, str, str]] = [
        ("酒店", "", TYPES_ACCOMMODATION),
        ("附近有什么好吃的", "", TYPES_FOOD),
        ("景点", "", TYPES_ATTRACTION),
        ("高铁站", "", TYPES_TRANSPORT),
        ("", "", ""),
        ("随便什么", "", ""),
        # 调度器给了就以它为准，不覆盖
        ("酒店", TYPES_FOOD, TYPES_FOOD),
    ]
    for keyword, given, want in infer_cases:
        got = _infer_types(keyword, given)
        check(got == want, f"_infer_types({keyword!r}, {given!r}) = {got!r}，期望 {want!r}")

    print(f"    {len(infer_cases)} 条推断用例全部符合期望")

    # ------------------------------------------------------------------
    # 7. 工具层：空结果不是错误，真故障才是错误
    #
    # 这条语义如果反了，表现是「这个范围内确实没有酒店」在前端显示成
    # 一个红色的失败态 —— 用户会以为系统坏了，而事实是"查了，没有"。
    # 所以两种结果必须能被区分开。
    # ------------------------------------------------------------------
    print("【7】空结果与真故障的区分")

    from app.agent import AgentService  # noqa: E402
    from app.places import PlaceResolver, PlaceTable, ResolvedPlace  # noqa: E402
    from app.tools import _search_nearby, _search_poi  # noqa: E402

    class _StubSettings:
        """只带 samples()/工具清单/地名表会用到的字段，
        避免为了测一个纯函数去起整个服务"""

        city_name = "汉中"
        llm_enabled = False
        # `AgentService.__init__` 会建地名解析器，它要读 citypack/<city>/places.json。
        # 指向真实数据包而不是假路径：这是一次**本地文件读**，不联网，
        # 而且能顺带保证"数据包里的地名表真的能被解析器读出来"。
        city_dir = ROOT / "citypack" / "hanzhong"
        # 行程规划器要它来算"城市中心落在哪个区县"。取 meta.json 里声明的
        # 真实值（107.02, 33.07），这样【15】段验的就是线上那条路径。
        city_center = (107.02, 33.07)

    class _AmapOk:
        """按需返回构造好的 POI。

        `std_name` 是地名解析给出的**标准名**，用来验证「实际用的中心点」
        有没有被写进给模型的文本。取「汉中站（汉台区）」而不是原来的
        「洋县高铁站(公交站)」：后者正是本次修掉的错误形态，把它留在断言里
        会让"解析又退回到洋县"这件事**看不出来**。
        """

        enabled = True
        std_name = "汉中站"
        std_district = "汉台区"

        def __init__(self, pois=None):
            self._pois = pois or []

        def geocode(self, address, city=""):
            return amap.GeoPoint(lng=107.525665, lat=33.22681, formatted=self.std_name)

        def search_around(self, location, **kwargs):
            return list(self._pois)

    class _AmapDown:
        enabled = True

        def geocode(self, address, city=""):
            return amap.GeoPoint(lng=107.035, lat=33.071, formatted=address)

        def search_around(self, location, **kwargs):
            raise AmapError("每日配额已用尽", infocode="10003", info="DAILY_QUERY_OVER_LIMIT")

    def _make_poi(pid: str, name: str, location: str, distance: str):
        """构造一条高德形状的 POI。**走 _parse_pois 而不是直接 new AmapPoi** ——
        这样测的是线上同一条解析路径，而不是绕过它自己拼一个对象。"""
        return _parse_pois(
            {
                "status": "1",
                "info": "OK",
                "infocode": "10000",
                "count": "1",
                "pois": [
                    {
                        "id": pid,
                        "name": name,
                        "type": "住宿服务;宾馆酒店;",
                        "typecode": "100100",
                        "address": "示例路 1 号",
                        "location": location,
                        "distance": distance,
                        "tel": "0916-0000000",
                        "adname": "洋县",
                        "cityname": "汉中市",
                        "business_area": [],
                        "biz_ext": {"rating": "4.8", "cost": []},
                        "photos": [],
                    }
                ],
            }
        )[0]

    nearby_args = {"center": "汉中高铁站", "types": TYPES_ACCOMMODATION}

    class _ResolverStub:
        """地名解析器的离线替身。

        **它不模拟三级策略** —— 三级策略由【14】段用真实的 `PlaceTable`
        离线测（读真实 `places.json`，不打网络）。这里只保证 `_search_nearby`
        能拿到一个确定的坐标，好让它自己那几条断言（空结果 / 有结果 / 故障 /
        三级回退）与地名解析解耦：两者坏掉时的表现完全不同，
        混在一个替身里会分不清是哪边的问题。
        """

        def __init__(self, name: str = "", lng: float = 107.525665, lat: float = 33.22681,
                     district: str = "", missing: bool = False) -> None:
            self._place = (
                None
                if missing
                else ResolvedPlace(name=name, lng=lng, lat=lat, source="table", district=district)
            )

        def resolve(self, query):
            return self._place

    class _ResolverNever:
        """用户**没点名中心**时不该走到地名解析 —— 多一次网络调用只是浪费，
        但如果它返回了别的东西，搜索中心就被悄悄换掉了。"""

        def resolve(self, query):
            raise AssertionError(f"这一步不该解析地名（query={query!r}）")

    empty = _search_nearby(
        nearby_args,
        amap=_AmapOk(),
        resolver=_ResolverStub(_AmapOk.std_name, district=_AmapOk.std_district),
        city_name="汉中",
        city_center=(107.02, 33.07),
    )
    check(empty.count == 0, f"空结果的 count 应为 0，实际 {empty.count}")
    check(
        empty.error == "",
        "「该范围内没有查到」被标成了 error —— 它是空结果不是故障，前端会显示红色失败态",
    )
    check(empty.ok is True, "空结果不该让 ToolResult.ok 变成 False")
    check("没有返回任何" in empty.text, "空结果的 text 没有把'没查到'说清楚")
    check(empty.cards == [], "空结果不该带卡片")

    # 有结果时：中心点的标准名必须出现在给模型的文本里。
    # 踩过两次：① 文本里只有坐标时，模型是从一堆"洋县"地址里**反推**出中心在哪儿
    # （反推对了是运气）；② 后来把名字写进去了，但解析本身是错的 ——
    # 「汉中高铁站」被地理编码到「洋县高铁站(公交站)」，文本再清楚也是答非所问。
    # 所以这两件事要分开测：这里测"名字有没有进文本"，
    # "解析对不对"由【14】段用真实地名表测。
    one = _search_nearby(
        nearby_args,
        amap=_AmapOk([_make_poi("B001", "洋县示例酒店", "107.525700,33.226800", "441")]),
        resolver=_ResolverStub(_AmapOk.std_name, district=_AmapOk.std_district),
        city_name="汉中",
        city_center=(107.02, 33.07),
    )
    check(one.ok is True and one.count == 1, f"有结果时应 ok 且 count=1，实际 ok={one.ok} count={one.count}")
    check(
        _AmapOk.std_name in one.text,
        "给模型的文本里没有地理编码解析出的中心点名称 —— 模型只能靠猜",
    )
    check(
        _AmapOk.std_name in one.label,
        f"工具标签没有换成解析出的中心点名称：{one.label!r}",
    )
    check(len(one.cards) == 1 and one.cards[0]["source"] == "amap", "卡片没有正确生成或缺少 source")

    down = _search_nearby(
        nearby_args,
        amap=_AmapDown(),
        resolver=_ResolverStub(_AmapOk.std_name, district=_AmapOk.std_district),
        city_name="汉中",
        city_center=(107.02, 33.07),
    )
    check(down.ok is False, "高德抛错时 ToolResult 应该不 ok")
    check("10003" in down.error or "配额" in down.error, f"真故障没把原因带出来：{down.error!r}")
    check(down.count == 0, "故障时的 count 应为 0")

    # search_poi：关键词没被任何一条名称命中时必须显式提示。
    # 实测：查「汉中博物馆」返回的是拜将坛、古汉台 —— 没有一条名字带"汉中博物馆"。
    # 不提示的话，模型可能把第一条当成"就是它"，用户就按一个错的地名出发了。
    class _AmapText:
        enabled = True

        def __init__(self, names):
            self._names = names

        def search_text(self, keywords, **kwargs):
            return [
                _make_poi("T%d" % i, n, "107.031000,33.071000", "0")
                for i, n in enumerate(self._names)
            ]

    miss = _search_poi({"keywords": "汉中博物馆"}, amap=_AmapText(["拜将坛", "古汉台"]), city_name="汉中")
    check(
        "没有任何一条的名称包含" in miss.text,
        "名称完全没命中时没有给出提示 —— 模型可能把第一条当成就是它",
    )

    hit = _search_poi({"keywords": "拜将坛"}, amap=_AmapText(["拜将坛", "古汉台"]), city_name="汉中")
    check(
        "没有任何一条的名称包含" not in hit.text,
        "名称命中时不该加那句提示（会变成每次搜索都出现的废话）",
    )

    print("    空结果 ok=True 且 error 为空；有结果时中心点标准名进文本；抛错时 ok=False 并带 infocode")
    print("    search_poi 名称未命中时给出提示，命中时不加废话")

    # ------------------------------------------------------------------
    # 8. 示例问题随能力变化
    #
    # 高德没配时若仍然推荐「附近有什么好吃的」，用户点了必然失败 ——
    # 一个点不出结果的引导按钮，比没有引导更糟。
    # ------------------------------------------------------------------
    print("【8】示例问题随能力变化")

    svc_on = AgentService(_StubSettings(), None, _AmapOk())
    groups_on = svc_on.samples()
    check(len(groups_on) == 3, f"高德可用时应有 3 组示例，实际 {len(groups_on)}")
    flat_on = [q for g in groups_on for q in g["items"]]
    check(
        any("附近" in q for q in flat_on),
        "高德可用时应该给出「附近…」这类示例",
    )
    # ★ 2026-09-28：这两条原来断言 `any("知识库" in g["title"])` / `any("行程" in g["title"])` ——
    #   把**展示文案**当成了契约。游客端口径复查时把标题从"查本地知识库（有出处的公开资料）"
    #   改成"汉中的来历与风物（都有出处）"，断言立刻变红，而功能一行没动。
    #   文案本来就该随口径改（这次就是专门去改它），所以锚点不能放在标题上。
    #
    #   换成按**结构**断言：每组都有标题且不为空、三组之间没有抄同一批问题、
    #   「附近」类正好占一组（它由高德能力决定，是这一段真正要守的东西）。
    #   "三组都在"由上面的 `len(groups_on) == 3` 与下面的 `len(groups_off) == 2` 兜住 ——
    #   所以这里**不是把断言改松**，是把"盯着文案"换成"盯着结构"。
    nearby_groups = [g for g in groups_on if any("附近" in q for q in g["items"])]
    check(
        len(nearby_groups) == 1,
        f"「附近」类示例应正好占一组，实际 {len(nearby_groups)} 组",
    )
    check(
        all(g["title"].strip() and g["items"] for g in groups_on),
        f"有分组缺标题或没有示例问题：{[g['title'] for g in groups_on]}",
    )
    item_sets = [tuple(g["items"]) for g in groups_on]
    check(
        len(set(item_sets)) == len(item_sets),
        f"有两个分组给了同一批示例问题：{item_sets}",
    )

    svc_off = AgentService(_StubSettings(), None, None)
    groups_off = svc_off.samples()
    flat_off = [q for g in groups_off for q in g["items"]]
    check(len(groups_off) == 2, f"高德不可用时应有 2 组示例，实际 {len(groups_off)}")
    check(
        not any("附近" in q for q in flat_off),
        "高德不可用时仍然推荐了「附近…」问题 —— 点了必然失败",
    )
    # 行程不联网，所以高德不可用时它**必须还在** —— 藏起来等于白白少一个能用的功能。
    check(
        any("行程" in g["title"] for g in groups_off),
        "高德不可用时把行程示例也藏起来了（它不需要高德）",
    )

    print("    高德可用 3 组（含附近类，正好占一组）；不可用 2 组（行程组不受影响），不含附近类")

    # ------------------------------------------------------------------
    # 9. 配置：留空 = 用默认值
    #
    # .env 里写 `AMAP_BASE_URL=`（留空）是很自然的写法，意思是"用默认值"。
    # 但 `os.environ.get("X", default)` 此时返回的是**空串**（键存在），
    # 于是 base_url 变成 ""，请求打到没有 host 的地址上 —— 报错方向完全错。
    # 数字型更糟：`AI_SERVER_PORT=` 会让 float/int 在 import 期抛异常，
    # 整个服务起不来。
    # ------------------------------------------------------------------
    print("【9】配置留空回落默认值")

    from app import config  # noqa: E402

    check(config._str("__HY_ABSENT__", "def") == "def", "未设置的环境变量没有回落默认值")

    os.environ["__HY_BLANK__"] = ""
    check(config._str("__HY_BLANK__", "def") == "def", "留空的字符串环境变量没有回落默认值")
    check(config._int("__HY_BLANK__", 7) == 7, "留空的数字环境变量没有回落默认值")

    os.environ["__HY_BAD__"] = "八千"
    check(
        config._int("__HY_BAD__", 7) == 7,
        "非数字环境变量没有回落默认值 —— 会让服务在 import 期就起不来",
    )

    os.environ["__HY_SPACE__"] = "   "
    check(config._str("__HY_SPACE__", "def") == "def", "只有空格的环境变量没有回落默认值")

    del os.environ["__HY_BLANK__"], os.environ["__HY_BAD__"], os.environ["__HY_SPACE__"]

    print("    未设置 / 留空 / 只有空格 / 非数字，四种情况都回落默认值")

    # ------------------------------------------------------------------
    # 10. 行程上下文渲染
    #
    # 阶段二的核心是"助手记得住"，而它落到代码上就是**一段文字**：
    # 服务端从 trip_context 读出来的东西，必须变成模型读得懂的两三行。
    # 这里测的就是这段文字，不需要数据库也不需要模型。
    # ------------------------------------------------------------------
    print("【10】行程上下文渲染")

    from app.llm import (  # noqa: E402
        build_messages,
        build_router_messages,
        build_tool_messages,
        trip_context_text,
    )

    check(trip_context_text(None) == "", "没有上下文时不该渲染出任何文字")
    check(trip_context_text({}) == "", "空字典也不该渲染出任何文字")
    check(
        trip_context_text("汉中") == "",
        "上下文不是字典时应当作'没有'处理，而不是崩掉 —— 它是增强项，坏了不该让整轮失败",
    )

    only_dest = trip_context_text({"destination": "汉中"})
    check("汉中" in only_dest, "目的地没进上下文文字")
    check("住处" not in only_dest, "没选酒店时不该出现'住处'一行")

    HOTEL_CTX = {
        "destination": "汉中",
        "selected_hotel": {
            "poi_id": "B001",
            "name": "洋县示例酒店",
            "address": "陕西省汉中市洋县示例路 1 号",
            "location": "107.525665,33.22681",
            "source": "amap",
        },
        "hotel_booking_status": "not_booked",
    }
    full = trip_context_text(HOTEL_CTX)
    check("洋县示例酒店" in full, "酒店名没进上下文文字")
    check("107.525665,33.22681" in full, "坐标没进上下文文字 —— 模型没法判断哪家更近")
    check("未预订" in full, "预订状态没有翻成中文，模型会看到 not_booked 这种内部取值")
    check("{" not in full, "上下文文字里混进了原始 JSON —— 它是给模型读的，不是给程序读的")

    weird = trip_context_text({"destination": "汉中", "selected_hotel": "洋县示例酒店"})
    check("汉中" in weird, "hotel 不是对象时，目的地仍该渲染出来（不能整段丢掉）")

    print("    空上下文返回空串；有上下文时目的地/酒店/坐标/预订状态都进文字，且不含原始 JSON")

    # ------------------------------------------------------------------
    # 11. "附近"的三级回退：用户点名 > 已选酒店 > 城市中心
    #
    # 这是阶段二**唯一**改动检索行为的地方，也是最容易写错的一处：
    # ④ 那一档（点名了但地理编码失败）如果顺手用了酒店，
    # 用户问"汉中高铁站附近"，得到的却是他住的那家酒店周边 ——
    # 那批结果看起来完全正常，错得非常安静。
    # ------------------------------------------------------------------
    print("【11】「附近」的三级回退：用户点名 > 已选酒店 > 城市中心")

    class _AmapRecord:
        """记下实际用的搜索中心。**用户没点名时不该去解析地名** ——
        多一次网络调用只是浪费，但如果它返回了别的东西，中心就被悄悄换掉了。
        用 `_ResolverNever` 把这条约束变成会炸的断言。"""

        enabled = True

        def __init__(self, pois=None):
            self.pois = list(pois or [])
            self.asked = None

        def search_around(self, location, **kwargs):
            self.asked = location
            return list(self.pois)

    HOTEL_ARG = {"name": "洋县示例酒店", "location": "107.525665,33.22681"}
    ONE_POI = [_make_poi("B002", "洋县小馆", "107.525700,33.226800", "180")]

    # ① 用户点名了中心 —— 即使有已选酒店，也该用点名的那个
    r = _search_nearby(
        {"center": "汉中高铁站", "types": TYPES_ACCOMMODATION},
        amap=_AmapOk([_make_poi("B001", "洋县示例酒店", "107.525700,33.226800", "441")]),
        resolver=_ResolverStub(_AmapOk.std_name, district=_AmapOk.std_district),
        city_name="汉中",
        city_center=(107.02, 33.07),
        hotel={"name": "另一家酒店", "location": "108.0,34.0"},
    )
    check(
        "另一家酒店" not in r.text and "另一家酒店" not in r.label,
        "用户点名了中心，却仍然用了已选酒店 —— 那是答非所问",
    )

    # ② 用户没说在哪附近 —— 用已选酒店
    rec = _AmapRecord(ONE_POI)
    r = _search_nearby(
        {"types": TYPES_FOOD}, amap=rec, resolver=_ResolverNever(), city_name="汉中",
        city_center=(107.02, 33.07), hotel=HOTEL_ARG,
    )
    check(
        rec.asked == "107.525665,33.22681",
        f"没用已选酒店的坐标当搜索中心（实际 {rec.asked!r}）",
    )
    check("洋县示例酒店" in r.label, f"标签没说明'这是您选的酒店'：{r.label!r}（实际 {r.label!r}）")
    check("107.525665,33.22681" in r.text, "给模型的文本里没有中心点坐标，模型没法核对")

    # ③ 没点名 + 没有已选酒店 —— 城市中心
    rec2 = _AmapRecord([_make_poi("B003", "市区小馆", "107.031000,33.071000", "200")])
    r = _search_nearby(
        {"types": TYPES_FOOD}, amap=rec2, resolver=_ResolverNever(), city_name="汉中",
        city_center=(107.02, 33.07),
    )
    check(rec2.asked.startswith("107.02"), f"没回落到城市中心（实际 {rec2.asked!r}）")
    check("市区中心" in r.text, "文本里没说明用的是市区中心")

    # ④ 点名了、但**地名解析不出来** + 有已选酒店 —— **仍然用城市中心**
    class _AmapNoGeo(_AmapRecord):
        """高德不可用的极端情形：解析器三级全失败（用 stub 的 missing 模拟）。"""

    rec3 = _AmapNoGeo([_make_poi("B004", "市区小馆", "107.031000,33.071000", "200")])
    r = _search_nearby(
        {"center": "不存在的地名", "types": TYPES_FOOD}, amap=rec3,
        resolver=_ResolverStub(missing=True), city_name="汉中",
        city_center=(107.02, 33.07), hotel=HOTEL_ARG,
    )
    check(
        rec3.asked.startswith("107.02"),
        f"点名中心解析失败后改用酒店 —— 用户问的是那个地方，答的却是他住哪（实际 {rec3.asked!r}）",
    )
    check("没能定位到" in r.text, "没说明中心点被替换过，模型会以为搜的就是用户说的那个地方")

    # ⑤ 什么都定不下来 —— 明确报错，而不是随便挑个点去搜
    class _AmapNoGeoNoPoi(_AmapNoGeo):
        def search_around(self, location, **kwargs):
            raise AssertionError("中心点都定不下来时不该去搜")

    r = _search_nearby(
        {"types": TYPES_FOOD}, amap=_AmapNoGeoNoPoi(), resolver=_ResolverNever(),
        city_name="汉中", city_center=None,
    )
    check(
        r.ok is False and "没能确定搜索中心点" in r.error,
        f"定不下中心点时应报错：{r.error!r}",
    )

    print("    点名 > 已选酒店 > 城市中心，四档都符合预期；定不下中心点时明确报错")

    # ------------------------------------------------------------------
    # 12. 卡片逐张标类别
    #
    # 前端靠 `card.kind === 'hotel'` 决定要不要显示「选择酒店」按钮。
    # 如果改用"这批结果的整体类别"（取第一条），混合结果里只要第一条不是
    # 酒店，那张酒店卡片就没有按钮 —— 用户会觉得这个功能时灵时不灵。
    # ------------------------------------------------------------------
    print("【12】卡片逐张标类别")

    from app.tools import _cards_of  # noqa: E402

    mixed = [
        _make_poi("R1", "某餐厅", "107.031000,33.071000", "100"),
        _make_poi("H1", "某酒店", "107.032000,33.072000", "200"),
    ]
    mixed[0].typecode = "050100"  # 餐饮服务；_make_poi 造出来的默认是 100100
    cards = _cards_of(mixed)
    check(cards[0]["kind"] == "restaurant", f"餐厅卡片的 kind 不对：{cards[0]['kind']!r}")
    check(cards[1]["kind"] == "hotel", f"酒店卡片的 kind 不对：{cards[1]['kind']!r}")
    check(all("kind" in c for c in cards), "有卡片没带 kind")
    check(all(c["source"] == "amap" for c in cards), "加 kind 的时候把 source 弄丢了")

    print("    混合结果里餐厅/酒店各自带对 kind，source 未被影响")

    # ------------------------------------------------------------------
    # 13. 上下文进入提示词
    #
    # 渲染出来只是第一步，它必须真的被拼进提示词。这里测的是拼装结果，
    # 顺带守住一条边界：**M3 那条路（build_messages）不能被动**。
    # ------------------------------------------------------------------
    print("【13】上下文进入提示词")

    menu = tool_menu(True, "汉中")
    sys_ctx = build_router_messages(
        "这附近有什么好吃的", menu, city="汉中", context=HOTEL_CTX
    )[0]["content"]
    check("洋县示例酒店" in sys_ctx, "调度器的提示词里没有已选酒店")
    check("107.525665,33.22681" in sys_ctx, "调度器的提示词里没有酒店坐标")
    check(
        "空字符串" in sys_ctx,
        "调度器没被告知'没说在哪附近时把 center 留空' —— 它会自己编一个城市名填进去",
    )

    sys_no_ctx = build_router_messages("这附近有什么好吃的", menu, city="汉中")[0]["content"]
    check("没有行程上下文" in sys_no_ctx, "没有上下文时没给出明确说明")
    check("洋县示例酒店" not in sys_no_ctx, "没有上下文时却出现了酒店")

    tool_sys = build_tool_messages("x", "y", city="汉中", context=HOTEL_CTX)[0]["content"]
    check("洋县示例酒店" in tool_sys, "生成阶段的提示词里没有已选酒店")
    tool_sys_no = build_tool_messages("x", "y", city="汉中")[0]["content"]
    check("洋县示例酒店" not in tool_sys_no, "没有上下文时不该凭空出现酒店")
    check(
        "先在【工具结果】里找到它" in tool_sys_no,
        "提示词里没有'数字必须在工具结果里找得到'这条规则 —— 半径/距离都是模型爱自己补的数字",
    )

    m3_msgs = build_messages("汉中仙毫是什么茶", [], "rag", city="汉中")
    check(len(m3_msgs) == 2, "M3 的消息条数被改动了")
    check(
        m3_msgs[1]["content"].endswith("汉中仙毫是什么茶"),
        "M3 的 user 消息结构被改动了 —— 阶段二不该碰已验收的那条路",
    )
    check(
        "洋县示例酒店" not in m3_msgs[0]["content"],
        "行程上下文漏进了 M3 的提示词（事实类问题不需要它）",
    )

    print("    调度器与生成阶段都拿到上下文；M3 那条路一行未动；半径不许改写的规则在位")

    # ------------------------------------------------------------------
    # 14. 地名解析：本地表 / POI 搜索 / 地理编码兜底
    #
    # 这一段守的是**用户报上来的那个 bug**：问「汉中高铁站附近有哪些酒店」，
    # 拿到一整屏洋县的宾馆。根因是 `amap.geocode()` 对「汉中高铁站」返回的
    # 首条是「洋县高铁站(公交站)」（洋县也通高铁），而当时只取了首条。
    #
    # 用**真实的地名表**（读 `citypack/hanzhong/places.json`）+ 假的 amap 客户端：
    # 离线、不花钱、毫秒级，但表本身是真的 —— 表里坐标写错了这里就会红。
    # ------------------------------------------------------------------
    print("【14】地名解析：本地表 / POI 搜索 / 地理编码兜底")

    from app.places import _relevant  # noqa: E402

    table = PlaceTable.from_file(ROOT / "citypack" / "hanzhong" / "places.json")
    check(len(table) >= 22, f"地名表索引条目太少（{len(table)}），可能没读到文件")

    class _AmapForPlaces:
        """假的 amap：把 `search_text` / `geocode` 的返回固定下来，
        好让"本地表没收录时该走哪一级"变成可断言的事。"""

        enabled = True

        def __init__(self, text=None, geo=None):
            self._text = list(text or [])
            self._geo = geo
            self.text_calls = 0
            self.geo_calls = 0

        def search_text(self, keywords, **kwargs):
            self.text_calls += 1
            return list(self._text)

        def geocode(self, address, city=""):
            self.geo_calls += 1
            return self._geo

    def _poi(name: str, lng: float, lat: float, adname: str = ""):
        """走 _parse_pois 造一条 POI —— 与线上同一条解析路径。"""
        return _parse_pois(
            {
                "status": "1",
                "pois": [
                    {
                        "id": "P1",
                        "name": name,
                        "type": "地名",
                        "typecode": "190100",
                        "address": [],
                        "location": f"{lng},{lat}",
                        "adname": adname,
                    }
                ],
            }
        )[0]

    # ① 区县名与主城区车站：本地表命中，**一次网络都不打**
    silent = _AmapForPlaces()
    res = PlaceResolver(silent, "汉中", table)
    for query, expect_lng, expect_district in [
        ("汉台区", 107.032010, "汉台区"),
        ("宁强县", 106.257636, "宁强县"),
        ("留坝县", 106.920781, "留坝县"),
        ("佛坪县", 107.990551, "佛坪县"),
    ]:
        got = res.resolve(query)
        check(got is not None, f"{query} 解析失败")
        if got is None:
            continue
        check(abs(got.lng - expect_lng) < 1e-6, f"{query} 的经度不对：{got.lng}")
        check(got.source == "table", f"{query} 没有走本地表（source={got.source}）")
        check(got.district == expect_district, f"{query} 的区县不对：{got.district!r}")
    check(
        silent.text_calls == 0 and silent.geo_calls == 0,
        "本地表命中时仍然打了网络 —— 断网演示会直接失效",
    )

    # ② ★ 本次修复的核心：「汉中高铁站」必须落到**汉台区**，不是洋县
    got = res.resolve("汉中高铁站")
    check(got is not None, "「汉中高铁站」解析失败")
    if got is not None:
        check(
            got.district == "汉台区",
            f"「汉中高铁站」又落到 {got.district or '未知'} 了 —— 这正是用户报的那个 bug",
        )
        check(
            got.label == "汉中站（汉台区）",
            f"标准名/区县没进 label：{got.label!r}（模型看不到它在哪个区）",
        )
        check(abs(got.lng - 107.029962) < 1e-6, f"坐标不对：{got.lng}")
    # 别名也要能命中
    for alias in ("汉中站", "汉中火车站"):
        a = res.resolve(alias)
        check(a is not None and a.district == "汉台区", f"别名 {alias} 没解析到汉台区")

    # ③ 行政区前缀要能剥掉（用户会说「汉中市汉台区」「陕西省汉中市宁强县」）
    for query, expect in [
        ("汉中市汉台区", "汉台区"),
        ("陕西省汉中市宁强县", "宁强县"),
        ("汉中市区", "汉台区"),
    ]:
        got = res.resolve(query)
        check(got is not None and got.district == expect, f"{query} 没剥掉前缀：{got}")

    # ④ 本地表没收录的具体地名 -> 走 POI 搜索
    text_client = _AmapForPlaces(text=[_poi("石门栈道风景区", 106.961962, 33.222439, "勉县")])
    got = PlaceResolver(text_client, "汉中", table).resolve("石门栈道")
    check(got is not None and got.source == "amap-text", f"没走 POI 搜索：{got}")
    check(got is not None and got.district == "勉县", f"区县没带回来：{got}")
    check(text_client.geo_calls == 0, "POI 搜索成功时不该再调地理编码")

    # ⑤ ★ POI 搜索是**模糊**的：首条不相关时不能认，要往下找相关的
    mixed = _AmapForPlaces(
        text=[
            _poi("一个嗦粉的地方", 107.054125, 33.073425, "汉台区"),  # 不相关
            _poi("汉中市博物馆", 107.033572, 33.069450, "汉台区"),  # 相关
        ]
    )
    got = PlaceResolver(mixed, "汉中", table).resolve("汉中博物馆")
    check(
        got is not None and got.name == "汉中市博物馆",
        f"首条不相关时没有继续往下找相关的：{got}",
    )

    # ⑥ POI 搜索全不相关 -> 退到地理编码；地理编码也不相关 -> 判定"没查到"
    junk_text = _AmapForPlaces(text=[_poi("一个嗦粉的地方", 107.054125, 33.073425, "汉台区")])
    junk_text._geo = amap.GeoPoint(lng=107.920165, lat=32.509964, formatted="陕西省汉中市镇巴县小洋镇")
    got = PlaceResolver(junk_text, "汉中", table).resolve("不存在的地方xyz")
    check(
        got is None,
        f"乱码地名被解析成了 {got.label if got else None} —— 用户会拿到一个随机的真实地点",
    )
    check(junk_text.geo_calls == 1, "POI 搜索失败后应该尝试一次地理编码")

    # ⑦ 高德抛错时降级到下一级，而不是让整轮对话失败
    class _AmapBoom:
        enabled = True

        def __init__(self):
            self.geo_calls = 0

        def search_text(self, keywords, **kwargs):
            raise AmapError("每日配额已用尽", infocode="10003")

        def geocode(self, address, city=""):
            self.geo_calls += 1
            return amap.GeoPoint(lng=107.02, lat=33.07, formatted="陕西省汉中市")

    boom = _AmapBoom()
    got = PlaceResolver(boom, "汉中", table).resolve("汉中")
    check(boom.geo_calls == 1, "POI 搜索抛错后没有降级到地理编码")
    check(got is not None and got.source == "amap-geocode", f"降级结果不对：{got}")

    # ⑧ 空查询 / 纯空白 -> None，且不打网络
    blank = _AmapForPlaces(text=[_poi("随便", 107.0, 33.0)])
    res_blank = PlaceResolver(blank, "汉中", table)
    for q in ("", "   ", "\u3000"):
        check(res_blank.resolve(q) is None, f"空查询 {q!r} 不该解析出结果")
    check(blank.text_calls == 0, "空查询不该打网络")

    # ⑨ `_relevant` 的判据本身（纯函数，值得单独钉住）
    check(_relevant("石门栈道", "石门栈道风景区"), "包含关系应判为相关")
    check(_relevant("汉中博物馆", "汉中市博物馆"), "前两字命中应判为相关（中间可插字）")
    check(not _relevant("不存在的地方xyz", "一个嗦粉的地方"), "只共享「地方」不该判为相关")
    check(not _relevant("", "汉中站"), "空查询不该判为相关")

    print("    区县/别名/前缀剥离走本地表；口语地名走 POI 搜索；乱码与不相关结果被挡下")

    # ------------------------------------------------------------------
    # 15. 行程规划（M4 阶段三）
    #
    # 这一段测的不是"算法能不能排出点"，而是**几条会安静地把行程排错的边界**：
    # 同一天串了两个区县、同一个点出现两次、数据不够却硬凑天数、
    # 用户住在宁强却从汉台区开始排。它们都不会抛异常，只会给出一份
    # "看着挺像样"的行程 —— 所以必须在这里钉死。
    # ------------------------------------------------------------------
    print("【15】行程规划")

    from app.itinerary import (  # noqa: E402
        MINUTES_PER_DAY,
        TOUR_TYPES,
        ItineraryPlanner,
        load_stops,
    )
    from app.llm import ROUTER_PROMPT, SYSTEM_PROMPT_TOOLS, build_router_messages  # noqa: E402
    from app.tools import _plan_itinerary, _price_text  # noqa: E402

    pack = ROOT / "citypack" / "hanzhong"
    stops = load_stops(pack)

    # ① 只取游览点：餐饮 / 住宿 / 交通枢纽不能排进"今天的景点"
    check(len(stops) == 30, f"应加载 30 个游览点，实际 {len(stops)}")
    check(
        all(s.business_type in TOUR_TYPES for s in stops),
        "行程里混进了非游览业态（餐饮/住宿/交通）",
    )
    check(
        all(s.duration_min > 0 for s in stops),
        "有游览点的建议时长为 0 —— 会让每日预算累加失真",
    )
    check(
        all(s.district and s.name for s in stops),
        "有游览点缺区县或名称，行程里会出现空行",
    )

    planner = ItineraryPlanner(pack, (107.02, 33.07))
    check(planner.ready, "数据包读到了点，planner 应为 ready")
    check(
        planner.center_district == "汉台区",
        f"城市中心应落在汉台区，实际 {planner.center_district!r}",
    )

    # ② 两日游：第 1 天在中心区、每天只有一个区县、点不重复、不超预算
    two = planner.plan(2)
    check(len(two.days) == 2, f"两日游应排出 2 天，实际 {len(two.days)}")
    check(
        two.days[0].district == "汉台区",
        f"第 1 天应在城市中心所在的汉台区，实际 {two.days[0].district!r}",
    )
    check(
        all(len({s.district for s in d.stops}) == 1 for d in two.days),
        "同一天里出现了跨区县的点 —— 时间会全耗在跨县路上",
    )
    check(
        all(d.district == d.stops[0].district for d in two.days),
        "某天的区县标签与该天实际景点所属区县不一致（前端会按标签显示）",
    )
    check(
        all(d.minutes <= MINUTES_PER_DAY for d in two.days),
        f"有某天超出每日预算：{[d.minutes for d in two.days]}",
    )
    check(
        all(not d.over_budget for d in two.days),
        "有某天被标记为超预算",
    )
    ids_two = [s.poi_id for d in two.days for s in d.stops]
    check(len(ids_two) == len(set(ids_two)), "两日游里同一个游览点出现了两次")

    # ③ 三日游：区县互不相同（同一天只排一个区县，同一次行程不重排同一个区县）
    three = planner.plan(3)
    check(len(three.days) == 3, f"三日游应排出 3 天，实际 {len(three.days)}")
    check(
        len({d.district for d in three.days}) == 3,
        f"三日游的区县应各不相同，实际 {[d.district for d in three.days]}",
    )

    # ④ 天数上下限
    check(len(planner.plan(1).days) == 1, "一日游应排出 1 天")
    check(len(planner.plan(9).days) <= 7, "天数应被截到 7 天上限")

    # ⑤ 七日游：仍然不重复，且不超上限
    seven = planner.plan(7)
    ids_seven = [s.poi_id for d in seven.days for s in d.stops]
    check(len(seven.days) <= 7, f"七日游排出了超过 7 天：{len(seven.days)}")
    check(len(ids_seven) == len(set(ids_seven)), "七日游里出现了重复的游览点")
    check(
        len({d.district for d in seven.days}) == len(seven.days),
        "七日游里有两天排在了同一个区县（第一轮应该每个区县各占一天）",
    )

    # ⑥ 偏好命中 tags 的点排到当天最前
    pref = planner.plan(2, preference="亲子")
    check(
        any("亲子" in s.tags for s in pref.days[0].stops),
        f"给了「亲子」偏好，第 1 天却没排进任何一个亲子点："
        f"{[s.name for s in pref.days[0].stops]}",
    )

    # ⑦ 起点区县：用户住在哪个区县，行程就从哪个区县开始
    #    用**宁强县**而不是汉台区 —— 后者本来就是城市中心，
    #    "起点生效"和"中心默认"会得出同一个结果，测不出起点有没有生效。
    ningqiang = next(s for s in stops if s.district == "宁强县")
    check(
        planner.district_of(f"{ningqiang.lng},{ningqiang.lat}") == "宁强县",
        "district_of 没把宁强县的坐标落到宁强县",
    )
    started = planner.plan(2, start_district="宁强县")
    check(
        started.days[0].district == "宁强县",
        f"指定起点区县后第 1 天不在宁强县：{started.days[0].district!r}",
    )

    # ⑧ district_of 的非法输入 -> 空串（不抛异常，退回按体量排序）
    for bad in ("", "107.02", "abc,def", "107.02,33.07,1"):
        check(planner.district_of(bad) == "", f"district_of 对非法坐标 {bad!r} 应返回空串")

    # ⑨ 数据包读不到 -> 空行程 / 报错，而不是抛异常
    empty_planner = ItineraryPlanner(ROOT / "citypack" / "__not_exist__", None)
    check(not empty_planner.ready, "不存在的数据包应让 planner 不 ready")
    check(empty_planner.plan(2).days == [], "空数据包应排出空行程而不是抛异常")

    # ⑩ 工具层：形状、卡片、给模型的文本
    res = _plan_itinerary({"days": 2}, planner=planner, city_name="汉中")
    check(res.ok and res.name == TOOL_PLAN, f"行程工具应成功，实际 error={res.error!r}")
    check(res.kind == "itinerary", f"行程卡片的 kind 应为 itinerary，实际 {res.kind!r}")
    check(res.count == 2, f"count 应为天数 2，实际 {res.count}")
    check(len(res.cards) == 1, f"行程应下发**一张**卡（不是每个点一张），实际 {len(res.cards)}")
    card = res.cards[0]
    check(card.get("kind") == "itinerary", "卡片里没有 kind 判别式 —— 前端会渲染成空白卡")
    check(
        card.get("requested_days") == 2 and len(card.get("days") or []) == 2,
        f"卡片的天数字段不对：{card.get('requested_days')} / {len(card.get('days') or [])}",
    )
    check(all(d.get("stops") for d in card["days"]), "有某一天一个点都没排")
    stop_keys = {"poi_id", "name", "district", "level", "duration_min",
                 "open_hours", "ticket_price", "summary", "tags"}
    check(
        all(stop_keys <= set(s) for d in card["days"] for s in d["stops"]),
        "卡片里的游览点字段不全，前端会缺格",
    )
    check(
        "第 1 天" in res.text and "汉台区" in res.text,
        "给模型的文本里没写清哪天在哪个区县",
    )
    check(
        "不是实时信息" in res.text,
        "给模型的文本没有标注门票/开放时间是静态整理值",
    )

    # ⑪ days 的兜底：没给 / 非数字 / 0 都按 2 天；给了大数截到 7
    for raw, label in (({}, "没给 days"), ({"days": "abc"}, "days 非数字"), ({"days": 0}, "days=0")):
        got = _plan_itinerary(raw, planner=planner, city_name="汉中")
        check(got.count == 2, f"{label} 应按 2 天兜底，实际 {got.count}")
    check(
        _plan_itinerary({"days": 99}, planner=planner, city_name="汉中").count <= 7,
        "days 给了个大数，应被截到 7",
    )

    # ⑫ planner 缺失 / 数据包为空 -> error（不是"查了没有"那种空结果）
    broken = _plan_itinerary({"days": 2}, planner=None, city_name="汉中")
    check(not broken.ok, "没有 planner 时应报错")
    check("数据包" in broken.error, f"错误说明应指明是数据包的问题，实际 {broken.error!r}")
    not_ready = _plan_itinerary({"days": 2}, planner=empty_planner, city_name="汉中")
    check(not not_ready.ok, "数据包为空时应报错，而不是给一份空行程")

    # ⑬ 已选住处驱动起点
    with_hotel = _plan_itinerary(
        {"days": 2},
        planner=planner,
        city_name="汉中",
        hotel={"name": "青木川客栈", "location": f"{ningqiang.lng},{ningqiang.lat}"},
    )
    check(
        with_hotel.ok and with_hotel.cards[0]["days"][0]["district"] == "宁强县",
        "给了住处坐标后，第 1 天没排在他住的区县",
    )
    check(
        "宁强县开始排" in with_hotel.text,
        "给模型的文本没有说明行程从住处的区县开始",
    )
    # 住处坐标坏掉时**不能崩**，退回按体量排序
    bad_hotel = _plan_itinerary(
        {"days": 2}, planner=planner, city_name="汉中",
        hotel={"name": "某酒店", "location": "not-a-coord"},
    )
    check(bad_hotel.ok, "住处坐标格式不对时行程工具不该失败")

    # ⑭ 门票渲染：0 是免费、null 是未知，两者必须分开说
    check(_price_text(0) == "门票免费", f"0 元应说成免费，实际 {_price_text(0)!r}")
    check(_price_text(None) == "", "门票未知时应什么都不说，而不是说成 0 元")
    check(_price_text(70) == "门票 70 元", f"门票格式不对：{_price_text(70)!r}")

    # ⑮ 调度器要知道有这个工具，也要知道它和知识库那条路的区别
    check("plan_itinerary" in ROUTER_PROMPT, "调度器提示词里没有行程工具")
    check(
        "knowledge_search" in ROUTER_PROMPT and "方法、原则" in ROUTER_PROMPT,
        "调度器提示词没有区分「要一份方案」和「要排法原则」—— 两条路会互相抢",
    )
    router = build_router_messages(
        "帮我规划汉中两日游", tool_menu(True, "汉中"), city="汉中"
    )[0]["content"]
    for placeholder in ("{tool_menu}", "{city}", "{trip_context}"):
        check(placeholder not in router, f"调度器提示词里的 {placeholder} 没被替换")
    check("plan_itinerary" in router, "渲染后的调度器提示词里没有行程工具")

    # ⑯ 生成阶段的提示词要约束住行程的呈现方式
    for phrase, why in (
        ("原样呈现", "没有要求按原样呈现 —— 模型会自己加减景点"),
        ("以景区公告为准", "没有要求提示门票/时间可能过时"),
        ("不要编造交通方式", "没有禁止编造车程与路线"),
        ("不含餐饮与住宿", "没有说明行程不含餐饮住宿，模型会自己补"),
        # 回答是按纯文本段落渲染的（前端只把 `**加粗**` 转成 strong）。
        # 不禁止 Markdown 标题，页面上就会原样显示成一串井号。
        ("不要用 `#` / `##` 标题", "没有禁止 Markdown 标题 —— 会显示成一串井号"),
    ):
        check(phrase in SYSTEM_PROMPT_TOOLS, f"行程提示词缺约束：{why}")

    # ⑰ 解析器认得这个工具名（拼错一个字母的表现是"永远走 none 分支"）
    parsed_name, parsed_args = _parse_decision('{"tool": "plan_itinerary", "args": {"days": 3}}')
    check(
        parsed_name == TOOL_PLAN and parsed_args == {"days": 3},
        f"解析器没认出 plan_itinerary：{parsed_name} {parsed_args}",
    )

    print(
        f"    30 个游览点；两日游={[d.district for d in two.days]}；"
        f"三日游={[d.district for d in three.days]}"
    )
    print(
        "    每天只排一个区县、点不重复、不超预算；起点随住处；"
        "天数有兜底与上限；数据包缺失时报错而非空行程"
    )

    # ------------------------------------------------------------------
    # 16. 工具失败文案的**游客口径**
    #
    # `ToolResult.error` 是**诊断**口径：它进模型提示词（`agent._generate` 的
    # "本次工具没有取到数据（{error}）"），也被 `accept_m4_route.py` 断言
    # （G4 明确要求里面透出 infocode）。所以它**可以**带厂商名、错误码、内部工具名。
    #
    # 但它曾经**原样渲染在游客端**（`Agent.vue` 的 `{{ t.tool.error }}`）——
    # 于是游客会看到"高德查询失败：高德返回错误 10001: INVALID_USER_KEY"
    # 和"本机未配置高德地图（AMAP_KEY）"。解法是拆成两条：
    # `error` 留诊断，`user_error` 给游客，前端取 `user_error or error`。
    #
    # 规矩：**凡是 error 里带了实现口径的，必须同时给出 user_error；
    # 且 user_error 自己一个都不许带。**
    #
    # 为什么这条要写成断言：2026-09-28 那次游客端口径复查**就是漏在这里**的 ——
    # 渲染级探针只看得见"页面上已经渲染出来的字"，而这些文案只在**失败路径**出现，
    # 探针永远跑不到。范围类要求要按**入口清单**验，不能按"我想到的那个页面"验。
    #
    # 做法是**静态扫源码**（ast）而不是构造调用：这样连"以后新增的失败分支"
    # 也一起管住，而且不依赖能不能把这个分支构造出来。
    # ------------------------------------------------------------------
    print("【16】工具失败文案的游客口径")

    import ast  # noqa: E402

    # 这些词是**实现口径**：厂商名、环境变量名、内部工具名、实现名词。
    BANNED_IN_USER_TEXT = (
        "高德",
        "AMAP_KEY",
        "LLM_API_KEY",
        "INTERNAL_TOKEN",
        "search_poi",
        "search_nearby",
        "knowledge_search",
        "plan_itinerary",
        "get_route",
        "数据包",
        "infocode",
        "INVALID_USER_KEY",
    )

    def _literal_parts(node: object) -> str:
        """取字符串字面量（含 f-string 里的固定片段）的文本，供禁用词扫描。

        只取**固定片段**：`f"高德查询失败：{exc}"` 里能扫到的是"高德查询失败："。
        插值部分（`exc`）扫不到 —— 那正是要的：它是运行时的诊断内容，
        本来就不该出现在游客文案里，所以它只可能出现在 `error` 上。
        """
        if isinstance(node, ast.Constant) and isinstance(node.value, str):
            return node.value
        if isinstance(node, ast.JoinedStr):
            return "".join(
                v.value
                for v in node.values
                if isinstance(v, ast.Constant) and isinstance(v.value, str)
            )
        return ""

    tools_src = Path(__file__).resolve().parents[1] / "server-ai" / "app" / "tools.py"
    tree = ast.parse(tools_src.read_text(encoding="utf-8"))

    def _tool_result_calls(node: ast.AST):
        for sub in ast.walk(node):
            if isinstance(sub, ast.Call) and getattr(sub.func, "id", "") == "ToolResult":
                yield sub

    needs_user_error = 0
    for call in _tool_result_calls(tree):
        kw = {k.arg: k.value for k in call.keywords if k.arg}
        err_text = _literal_parts(kw["error"]) if "error" in kw else ""
        hits = [w for w in BANNED_IN_USER_TEXT if w in err_text]
        if not hits:
            continue
        needs_user_error += 1
        user_text = _literal_parts(kw["user_error"]) if "user_error" in kw else ""
        check(
            "user_error" in kw,
            f"tools.py:{call.lineno} 的 error 带了实现口径 {hits} 却没有 user_error"
            " —— 这一条会原样渲染到游客端",
        )
        check(
            not [w for w in BANNED_IN_USER_TEXT if w in user_text],
            f"tools.py:{call.lineno} 的 user_error 自己带了实现口径：{user_text!r}",
        )
    # ★ 让这条扫描**证明自己的覆盖面**：断言形状写错（比如函数名改了）会扫到 0 条，
    #   而"0 条都不违规"是平凡为真的 —— 那比没有断言更糟。
    check(
        needs_user_error >= 10,
        f"只扫到 {needs_user_error} 条需要 user_error 的失败文案 —— 扫描本身可能失效了",
    )
    # 这句**只报数，不报结论** —— 结论由上面两条 check 给。写成"全部干净"的话，
    # 一旦某条 check 失败，这句会跟着打出一句相反的话（反向对照时实测过）。
    print(f"    扫到 {needs_user_error} 条带诊断口径的失败文案（每条都必须另有游客口径）")

    # ------------------------------------------------------------------
    print()
    if problems:
        print(f"★ 不达标，问题 {len(problems)} 条：")
        for p in problems:
            print(f"  ★ {p}")
        return 1

    print(f"通过：{passed} 项断言全部成立")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
