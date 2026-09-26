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
    TOOL_POI,
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
    check(
        needs_amap == {TOOL_NEARBY, TOOL_POI},
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
        set(names_on) == {TOOL_NEARBY, TOOL_POI, TOOL_KNOWLEDGE},
        f"amap 已配时的可用工具不对：{names_on}",
    )
    check(
        names_off == [TOOL_KNOWLEDGE],
        f"amap 未配时只应剩知识库，实际 {names_off}",
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
    print("    amap 开：3 工具 + 类型码；amap 关：仅知识库 + 原因说明")

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
    from app.tools import _search_nearby, _search_poi  # noqa: E402

    class _StubSettings:
        """只带 samples()/工具清单会用到的字段，避免为了测一个纯函数去起整个服务"""

        city_name = "汉中"
        llm_enabled = False

    class _AmapOk:
        """按需返回构造好的 POI。geocode 返回一个**与输入不同**的标准名，
        用来验证「实际用的中心点」有没有被写进给模型的文本。"""

        enabled = True
        std_name = "陕西省汉中市洋县高铁站(公交站)"

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

    empty = _search_nearby(nearby_args, amap=_AmapOk(), city_name="汉中", city_center=(107.02, 33.07))
    check(empty.count == 0, f"空结果的 count 应为 0，实际 {empty.count}")
    check(
        empty.error == "",
        "「该范围内没有查到」被标成了 error —— 它是空结果不是故障，前端会显示红色失败态",
    )
    check(empty.ok is True, "空结果不该让 ToolResult.ok 变成 False")
    check("没有返回任何" in empty.text, "空结果的 text 没有把'没查到'说清楚")
    check(empty.cards == [], "空结果不该带卡片")

    # 有结果时：中心点的标准名必须出现在给模型的文本里。
    # 实测踩过这个坑 —— 「汉中高铁站」被地理编码到「洋县高铁站(公交站)」，
    # 而当时文本里只有坐标，模型是从一堆"洋县"地址里**反推**出中心在哪儿的。
    # 反推对了是运气；把名字写进去才是设计。
    one = _search_nearby(
        nearby_args,
        amap=_AmapOk([_make_poi("B001", "洋县示例酒店", "107.525700,33.226800", "441")]),
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

    down = _search_nearby(nearby_args, amap=_AmapDown(), city_name="汉中", city_center=(107.02, 33.07))
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
    check(len(groups_on) == 2, f"高德可用时应有 2 组示例，实际 {len(groups_on)}")
    flat_on = [q for g in groups_on for q in g["items"]]
    check(
        any("附近" in q for q in flat_on),
        "高德可用时应该给出「附近…」这类示例",
    )
    check(
        any("知识库" in g["title"] for g in groups_on),
        "示例里缺少知识库那一组",
    )

    svc_off = AgentService(_StubSettings(), None, None)
    groups_off = svc_off.samples()
    flat_off = [q for g in groups_off for q in g["items"]]
    check(len(groups_off) == 1, f"高德不可用时只应剩 1 组示例，实际 {len(groups_off)}")
    check(
        not any("附近" in q for q in flat_off),
        "高德不可用时仍然推荐了「附近…」问题 —— 点了必然失败",
    )

    print("    高德可用 2 组（含附近类）；不可用只留知识库组，不含附近类")

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
