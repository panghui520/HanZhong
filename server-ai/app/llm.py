"""回答生成：**三条链路**，按可用条件降级，任何时候都不返回空。

三条链路决定"模型被允许使用哪一部分知识"，这是 M3 从「知识库回答器」改成
「知识库增强的助手」的关键（见 docs/验收记录-M3.md 十）：

| 链路 | 什么时候用 | 给模型的上下文 |
|---|---|---|
| `rag` | 问题在知识库覆盖范围内 | 检索到的【资料】+ "不得补充资料外本地事实" |
| `constrained` | 涉及本地事实、但知识库可能没写过 | **同样给【资料】**，但提示词明说"这些词不在语料里，资料可能答不了"，要求先看资料、答不了才说缺依据，且不得编造本地事实 |
| `general` | 与汉中无关（问系统自身、通用概念、闲聊） | **不给资料、也不检索**；当通用助手直接回答 |

`rag` 与 `constrained` 的差别**只在提示词**，不在有没有上下文 —— 这是被实测逼出来的：
缺口判定会误报（51 条检索用例里 3 条被误判，如"浆水面为什么叫浆水面"，
jieba 把"叫浆"切成了碎片），如果 `constrained` 干脆不给资料，
这些本来答得上来的问题就会被提示词劝退。所以它的措辞是"资料**可能**答不了"。

为什么要有 `constrained` 而不是直接拒答：拒答的代价是"连模型本来就能答的部分也丢了"。
原来的实现里，凡是 `topic_gaps` 命中的问题一律回"知识库里没有记载"，
于是"你是 AI 吗"这种跟知识库毫无关系的问题也被拒答了 —— 那是把
"知识库的覆盖范围"错当成了"系统能回答的范围"。`constrained` 保住了事实约束
（不许编造汉中的数字/价格/时间/名单），同时把通用部分交还给模型。

`general` 刻意**不检索**：既然不用知识库，就不该为它付一次嵌入调用的钱，
也不该在界面上显示"检索 5 条"这种误导性的数字。

降级链（没有模型时）：

| 模式 | 触发条件 | 特点 |
|---|---|---|
| `llm` | 配了 LLM_API_KEY 且未开 DEMO_MODE | 真正的流式生成，答案用自己的话组织 |
| `cache` | DEMO_MODE=true，或问题命中预生成缓存 | 回放预先写好的答案，逐字吐出以保持流式体验 |
| `extractive` | 没有模型，但问题在知识库范围内 | 直接摘录检索到的资料原文，不润色、不编造 |
| `no_answer` | 没有模型，且这条问题本来要靠模型（通用问答 / 知识库无依据） | 说明离线模式的边界 |

`extractive` 这一档是刻意保留的：没有它，没配 key 的环境下系统就只会说"AI 不可用"，
演示时等于白屏；有了它，系统仍然能给出**有出处、可核对**的回答，只是措辞不好看。
`no_answer` 现在只出现在**离线**且问题超出知识库范围的场景 ——
有模型时不再有"因为知识库没写过就拒答"这条路。
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, AsyncIterator, Mapping, Sequence

import httpx

from .aliases import expand
from .store import Hit

# 三条链路的标识。`build_messages` 用它选提示词，`qa.py` 用它选分支，
# 也随 meta 事件发给前端（前端据此说明"这一问是怎么答出来的"）。
ROUTE_RAG = "rag"
ROUTE_CONSTRAINED = "constrained"
ROUTE_GENERAL = "general"

# 三份系统提示，差别只有一处：**模型被允许使用哪一部分知识**。
#
# 为什么不能只用一份（原来的写法）：原来那份写着"只依据【资料】里的内容回答，
# 资料里没有的不要用你自己的知识补充"，而"没有资料"和"资料里没有这一条"
# 在模型眼里是一回事 —— 于是通用问题（"你是 AI 吗"）即使把资料换成空，
# 也只会得到一句"知识库里没有相关记载"。**约束本身没有错，错的是把它
# 用在了不该用的问题上。** 所以现在按链路分三份，各守各的边界。

SYSTEM_PROMPT_RAG = """你是「汉游智脑」的文旅知识助手，服务对象是准备到陕西{city}旅游的游客和当地文旅管理者。
本次回答附有从本地知识库检索到的【资料】。

回答规则：
1. 以【资料】为主要依据。资料里明确写了的，按资料说，不要改动其中的数字与名称。
2. **资料里没有的{city}具体事实，一律不要凭自己的知识补充或推测** ——
   价格、开放时间、电话、里程、班次、名单、机构、政策都在此列。
   这类信息错一个就可能让人白跑一趟，宁可说"资料里没有"。
3. 如果资料不足以回答问题的核心部分，直接说明"知识库中没有相关记载"，
   再说清楚资料里能回答的是哪一部分。
4. 与{city}无关的通识背景（某个概念的通用含义、一般做法）可以简要补充，
   但要与资料内容分开表述，不要让读者以为那也是知识库里的记载。
5. 用简体中文，简洁分点，用自己的话组织，不要整段照抄资料。
6. 不要写"根据资料1"这类引用标记，来源由系统单独展示。"""

SYSTEM_PROMPT_CONSTRAINED = """你是「汉游智脑」的文旅知识助手，服务对象是准备到陕西{city}旅游的游客和当地文旅管理者。
本次回答附有从本地知识库检索到的【资料】，但**提问里的这些词不在知识库语料中：{gaps}** ——
也就是说这份资料**可能**答不了这个问题。

回答规则：
1. **先看【资料】能不能回答。** 能回答的部分就按资料回答，规则同正常检索问答。
2. 资料确实答不了的部分，开头明确告诉用户：知识库里缺少这方面的可靠依据。
   —— 注意是"资料答不了的部分"，不是整个问题；资料答得上就别说不缺依据。
3. **资料里没有的{city}具体事实，一律不要凭自己的知识补充或推测** ——
   数字、价格、开放时间、电话、里程、班次、名单、机构、政策都在此列。
   你没有依据，写出来就是在骗人。
4. 与{city}无关的通识背景（概念解释、一般流程、常识）可以简要说明，
   但必须标明"以下是通识信息，不是来自本知识库"。
5. 如果用户想知道的确实是{city}的情况，建议他换个说法再问。
6. 用简体中文，简洁分点，不要写引用标记。
7. 供你告诉用户"能问什么"的知识库覆盖范围：{scope}"""

SYSTEM_PROMPT_GENERAL = """你是「汉游智脑」的助手 —— 一个面向{city}文旅场景的智能助手。
**这个问题不需要{city}的文旅知识**，所以本次不查阅知识库，请用你自己的通用知识回答。

回答规则：
1. 直接、自然地回答用户的问题。
2. 不要编造与{city}有关的具体事实（数字、价格、时间、名单、机构）。
   如果用户其实想问的是{city}的情况，提醒他补上"{city}"再问一次。
3. 用简体中文，简洁明了，不要写引用标记。"""

# ----------------------------------------------------------------------
# M4 工具调用：两份新提示词
#
# 与 M3 的三份是**同一套思路的延伸**：模型被允许使用哪一部分知识。
# 这里多出来的是"这一部分知识由外部工具现场取回"。
# ----------------------------------------------------------------------

SYSTEM_PROMPT_TOOLS = """你是「汉游智脑」的 AI 旅游助手，服务对象是准备到陕西{city}旅游的游客。
本次回答附有**工具返回的真实数据**，这些数据是可靠的：
地点类来自高德地图，行程方案类来自{city}本地数据包。

回答规则：
1. **只依据【工具结果】里出现过的地点、距离、地址、电话来推荐。**
   工具结果里没有的酒店、餐厅、距离、价格，一律不要补充或推测 ——
   你没有地图数据，写出来就是在骗人，而用户会照着它去订房。
2. 可以做的：按距离远近排序、把同类地点分组、指出哪个更靠近某个地标、
   提醒用户哪些字段（电话、营业时间）需要自行核实。
3. 不可以做的：编造评分、编造「步行 8 分钟」、编造「离车站只有 500 米」、
   编造营业时间或房型价格。距离**只引用工具给出的米数**。
   搜索半径同理：工具里写「半径 2000 米」，就说 2000 米或 2 公里，
   不要换成别的数字。凡是要写数字，先在【工具结果】里找到它；找不到就不要写。
4. 工具结果为空时，直接说明这个位置附近没有查到该类地点，不要拿别的数据凑数。
5. 用简体中文，简洁分点，用自己的话组织，不要整段照抄工具结果。
   **回答会按纯文本段落显示**（一行 = 一段）：不要用 `#` / `##` 标题，
   不要用 `-` / `*` 列表符号（用「1. 2. 3.」或直接分行）；需要强调某个名字时
   用 `**加粗**`。写成 Markdown 标题会原样显示成一串井号。
6. 不要写「根据工具结果1」这类引用标记，卡片由系统单独展示。
7. 本系统只做**推荐**，不做预订。不要承诺能帮用户订房、订座或付款，
   **也不要描述界面上有「预订」按钮或跳转链接** —— 这一版没有这些按钮，
   说了用户会去找一个不存在的东西。要订房时，说明需要他自己到第三方平台预订。
8. 如果工具结果的说明里指出**搜索中心被替换过**（例如"没能定位到 X，已改用
   市区中心"），必须在回答开头如实说明这一点，再给结果。用户问的是 X 附近，
   答的是别处附近，不说明就等于答错了问题。
9. 上面给了「用户当前行程」时，可以自然地用上它（例如"您住的 XX 酒店附近…"），
   让回答接得上之前的对话。但**不要复述坐标数字**，也不要在与行程无关的
   回答里硬扯上住处 —— 那只会显得啰嗦。

【工具结果是「行程方案」时，额外遵守这几条】
10. **按它给的天数和区县原样呈现**：第几天在哪个区县、那天有哪几个点，
    一个不少、一个不多。不要自己加景点、不要减、不要改天数、
    不要把不同天的点互换 —— 这份方案是按"同一天只在同一个区县"排出来的，
    你一改就破坏了它唯一的保证。
11. 门票与开放时间是**数据包里的静态整理值，不是实时信息**。写出来的时候
    必须带上"以景区公告为准"这类提醒，不要说成当前价格或当前时间。
12. **不要编造交通方式、车程、里程或路线**。方案里只给了"哪一天在哪个区县"，
    没有给怎么走。用户问"怎么过去"时，说明需要他自行用地图导航查询。
13. 方案里**不含餐饮与住宿**，这是有意的。不要替用户补上"中午在 XX 吃"这类安排；
    他想找吃的、找住处，你可以告诉他换个问法（问某个地方附近有什么）。
14. 用户要的天数多于方案排出来的天数时（方案说明里会写），
    **如实说明"数据包里只够排 N 天"**，不要硬凑、也不要重复推荐同一个点。"""

ROUTER_PROMPT = """你是「汉游智脑」的**工具调度器**。你的唯一任务是判断用户这一句话
需要调用哪个工具，**不要回答用户的问题**。

可用工具：
{tool_menu}

{trip_context}

判断规则（按顺序看）：
1. 问「某地附近 / 周围有什么酒店、餐厅、景点」这类**以某个地点为中心**的搜索
   → `search_nearby`
2. 问**某个具体地点本身**的位置或信息（「汉中博物馆在哪」「汉中高铁站在哪」）
   → `search_poi`
3. 问**具体几天的行程方案**（「两日游怎么安排」「汉中三天怎么玩」
   「帮我排一个两天行程」「周末去汉中怎么安排」）→ `plan_itinerary`
   **这一条最容易和下面的知识库搞混，看清区别**：
   要的是"一份具体方案"（哪天去哪几个点）→ 这里；
   要的是"排行程的方法、原则、注意事项"（「行程一般怎么安排比较好」
   「一天排几个景点合适」「汉中旅游有什么要注意的」）→ 走第 4 条的 `knowledge_search`。
4. 问{city}的历史、文化、气候、物产、特产知识，或问数据包里某处资源点、
   某项体验、某款产品的介绍 → `knowledge_search`
5. 与{city}无关（问助手自身、通用概念、闲聊），或只是打招呼 → `none`

只输出**一行 JSON**，不要解释、不要 Markdown 代码块、不要多余文字：

{"tool": "工具名", "args": {...}}

各工具的 args 写法：
- `search_nearby`：{"center": "中心点的地点名", "keyword": "关键词，如 酒店 / 餐厅", "types": "类型编码", "radius": 半径米数}
- `search_poi`：{"keywords": "要查的地点名", "types": "类型编码，可省略"}
- `plan_itinerary`：{"days": 天数（整数，如 2）, "preference": "偏好，可省略（如 自然风光 / 历史文化 / 亲子）"}
- `knowledge_search`：{"query": "用户问题的核心问法"}
- `none`：{}

三条硬性约束：
- **一次只选一个工具。**
- **不要编造地点名**。用户没提到的地点不要自己补。
  用户说「附近 / 周围」但**没有指明具体地点**时，把 `center` 写成**空字符串** ——
  系统会用他本次行程已选定的住处作为中心（没有已选住处时才用城市中心）。
  你自己填一个酒店名或城市名，反而会把更准确的那个中心覆盖掉。
- `types` 只能用上面工具清单里给出的编码，不要自己发明。
- `plan_itinerary` 的 `days` **只能从用户的话里读**（"两天/三日/三天/周末"），
  读不出来就把 `days` 留空（系统按 2 天处理），**不要自己替用户决定玩几天**。

（注意：上面 JSON 示例里的花括号是**字面量**，不是占位符。本提示词由 `_fill()`
逐键替换，不走 `str.format`，所以不需要写双花括号。）"""

# 预订状态 -> 给模型看的中文。**只描述事实，不描述能力** ——
# 本系统不代办预订（规则 7），这里写"未预订"就够了，不要写成"可以帮您预订"。
_BOOKING_LABEL = {
    "not_booked": "未预订",
    "external_pending": "已跳转到第三方平台，尚未确认",
    "booked": "已预订",
    "cancelled": "已取消",
}


def trip_context_text(context: "Mapping[str, Any] | None") -> str:
    """把行程上下文渲染成提示词里的一段中文。没有上下文时返回空串。

    **只渲染这一轮真的用得上的两件事**：目的地、已选住处（含坐标）。
    上下文里多一个字段就多一行提示词，而模型的注意力是有限的 ——
    把行程编号、创建时间这类东西塞进去，只会稀释"用户住哪"这个关键信息。

    坐标要写进来，是因为模型需要用它在回答里判断"哪几家更近"（工具结果里
    有距离米数）；但它被规则 9 要求**不要复述**，所以这不是给用户看的。

    抽成一个函数而不是在 agent.py 里拼字符串：调度器与生成阶段读的必须是
    同一段文字，否则会出现"调度器知道用户住在哪、生成阶段不知道"这种
    只在多轮对话里才暴露的不一致。

    **不是字典就当作没有上下文**，而不是抛异常。它来自 HTTP 请求体，
    上游已经挡过一道（见 `main._body_of` 与 `AgentService.stream`），
    但这里再挡一次几乎不花钱：万一以后多一条调用路径忘了挡，
    后果会是"整轮对话 500"，而上下文本来只是个增强项。
    """
    if not isinstance(context, Mapping) or not context:
        return ""

    lines: list[str] = []
    destination = str(context.get("destination") or "").strip()
    if destination:
        lines.append(f"- 本次行程目的地：{destination}")

    hotel = context.get("selected_hotel")
    if isinstance(hotel, Mapping):
        name = str(hotel.get("name") or "").strip()
        if name:
            address = str(hotel.get("address") or "").strip()
            location = str(hotel.get("location") or "").strip()
            line = f"- 已选住处：{name}"
            if address:
                line += f"（{address}）"
            if location:
                line += f"，坐标 {location}"
            lines.append(line)
            status = str(context.get("hotel_booking_status") or "").strip()
            if status:
                lines.append(f"- 住处预订状态：{_BOOKING_LABEL.get(status, status)}")

    if not lines:
        return ""
    return "用户当前行程：\n" + "\n".join(lines)


def _fill(template: str, **values: str) -> str:
    """占位符替换。

    刻意不用 `str.format`：提示词里迟早会出现 `{}` 或 JSON 示例，
    那时 format 会把它们当成占位符报 KeyError，而错误信息指向的是模板本身、
    不是"你多写了一个花括号"。逐键 replace 没有这个隐患。
    """
    out = template
    for key, value in values.items():
        out = out.replace("{" + key + "}", value)
    return out


def build_messages(
    question: str,
    hits: Sequence[Hit],
    route: str = ROUTE_RAG,
    *,
    city: str = "汉中",
    gaps: Sequence[str] = (),
    scope: str = "",
) -> list[dict[str, str]]:
    """按链路组装消息。

    - `rag` 与 `constrained` 都会把检索到的切片作为【资料】放进 user 消息 ——
      **只有提示词的严格程度不同**。这一点是有意为之：缺口判定会误报
      （实测 51 条检索用例里有 3 条被误判成有缺口，如"浆水面为什么叫浆水面"
      因为分词器把"叫浆"切成了碎片），如果 `constrained` 干脆不给资料，
      这些本来答得上来的问题就会被提示词劝退。所以 `constrained` 的措辞是
      "资料**可能**答不了"而不是"资料答不了"，并要求模型先看资料。
    - `general` 不带资料、也没有资料可带（这条链路不检索）。"""
    if route == ROUTE_GENERAL:
        return [
            {"role": "system", "content": _fill(SYSTEM_PROMPT_GENERAL, city=city)},
            {"role": "user", "content": question},
        ]

    blocks = []
    for i, hit in enumerate(hits, 1):
        blocks.append(f"【资料{i}】{hit.metadata.get('title', '')}\n{hit.text}")
    context = "\n\n".join(blocks) or "（本次没有检索到任何资料）"

    if route == ROUTE_CONSTRAINED:
        system = _fill(
            SYSTEM_PROMPT_CONSTRAINED,
            city=city,
            gaps="、".join(gaps) or "（未识别出具体词）",
            scope=scope or "（未提供）",
        )
    else:
        system = _fill(SYSTEM_PROMPT_RAG, city=city)

    return [
        {"role": "system", "content": system},
        {"role": "user", "content": f"【资料】\n{context}\n\n【问题】\n{question}"},
    ]


def build_router_messages(
    question: str,
    tool_menu: str,
    *,
    city: str = "汉中",
    context: Mapping[str, Any] | None = None,
) -> list[dict[str, str]]:
    """工具调度器的消息。它**不回答**问题，只输出一行 JSON 决定用哪个工具。

    `context` 是本次行程上下文（M4 阶段二）。调度器需要它，是因为
    "用户说「附近」但没说在哪附近"这条规则必须有确定的落点：
    没有上下文时系统用城市中心兜底，有已选酒店时用酒店兜底。
    把上下文给调度器，它才敢把 `center` 留空 —— 否则它会自己编一个
    "汉中市区"填进去，把更准确的那个中心覆盖掉。
    """
    block = trip_context_text(context) or "（本次没有行程上下文：用户未登录，或尚未选择住处）"
    # trip_context 放最后传：它的内容来自数据库（酒店名可能含花括号），
    # 最后替换就不会被后面的键再扫一遍
    return [
        {
            "role": "system",
            "content": _fill(
                ROUTER_PROMPT, city=city, tool_menu=tool_menu, trip_context=block
            ),
        },
        {"role": "user", "content": question},
    ]


def build_tool_messages(
    question: str,
    tool_text: str,
    *,
    city: str = "汉中",
    note: str = "",
    context: Mapping[str, Any] | None = None,
) -> list[dict[str, str]]:
    """把工具结果作为【工具结果】交给模型，由它组织成人话。

    与 `build_messages` 的结构刻意保持一致（system + 一条含【】区块的 user），
    这样"资料"与"工具结果"对模型是同一类东西：**它只能引用，不能补充**。

    `note` 用于追加一句本次的边界说明（例如"高德未配置""只取到 3 条"）。
    放在 system 而不是 user，是因为它约束的是模型的行为而不是内容。

    `context` 是本次行程上下文（M4 阶段二）。**只在这里加，不加进
    `build_messages`（M3 那条路）**：走知识库的问题是"汉中仙毫是什么茶"
    这类事实问题，答案与用户住哪无关，把行程塞进去只会让它多扯一句废话；
    而 M3 的提示词是已验收的，为一件用不上的事去改它不划算。
    真需要接上下文的是"附近 / 帮我安排"这类问题，它们都走这条路。
    """
    system = _fill(SYSTEM_PROMPT_TOOLS, city=city)
    block = trip_context_text(context)
    if block:
        system = f"{system}\n\n{block}"
    if note:
        system = f"{system}\n\n补充说明：{note}"
    return [
        {"role": "system", "content": system},
        {"role": "user", "content": f"【工具结果】\n{tool_text}\n\n【问题】\n{question}"},
    ]


# ----------------------------------------------------------------------
# 预生成缓存
# ----------------------------------------------------------------------


def normalize_question(text: str) -> str:
    """缓存键的归一化：去空白与标点、统一别名、去掉疑问语气词。

    不做这一步的话，"朱鹮是哪年发现的？"和"朱鹮是哪年发现的"会算两个不同的问题。
    """
    text = expand(text).lower()
    # 中文引号写成 \u 转义而不是字面字符：字面写会和字符串定界符混在一起，
    # 被 Python 解析成三段字面量的隐式拼接，raw 前缀只作用于第一段，
    # "\[" 就成了非法转义（SyntaxWarning），引号本身也漏掉了。
    # re 模块自己认识 \uXXXX，所以转义写法在正则里同样有效。
    text = re.sub(r'[\s，。？！、；：\u201c\u201d\u2018\u2019（）()【】\[\]?!.,;:~·\-—_]+', "", text)
    for filler in ("请问", "我想知道", "麻烦问一下", "一下"):
        text = text.replace(filler, "")
    return text


@dataclass
class CachedAnswer:
    answer: str
    doc_ids: list[str]
    mode: str = "cache"


class AnswerCache:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.meta: dict[str, Any] = {}
        self._index: dict[str, CachedAnswer] = {}
        self._load()

    def _load(self) -> None:
        if not self.path.is_file():
            return
        payload = json.loads(self.path.read_text(encoding="utf-8"))
        self.meta = payload.get("meta", {})
        for entry in payload.get("answers", []):
            cached = CachedAnswer(
                answer=entry["answer"].strip(),
                doc_ids=list(entry.get("sources", [])),
            )
            # 同一段答案可以用多种问法命中
            for question in entry.get("questions", []):
                self._index[normalize_question(question)] = cached

    def __len__(self) -> int:
        return len(self._index)

    def lookup(self, question: str) -> CachedAnswer | None:
        return self._index.get(normalize_question(question))


# ----------------------------------------------------------------------
# 生成
# ----------------------------------------------------------------------


async def stream_llm(settings, messages: list[dict[str, str]]) -> AsyncIterator[str]:
    """调 OpenAI 兼容接口，逐段吐出增量文本。"""
    payload = {
        "model": settings.llm_model,
        "messages": messages,
        "stream": True,
        "temperature": 0.3,
    }
    headers = {
        "Authorization": f"Bearer {settings.llm_api_key}",
        "Content-Type": "application/json",
    }

    timeout = httpx.Timeout(settings.llm_timeout_s, connect=5.0)
    async with httpx.AsyncClient(timeout=timeout) as client:
        async with client.stream(
            "POST", f"{settings.llm_base_url}/chat/completions", json=payload, headers=headers
        ) as response:
            response.raise_for_status()
            async for line in response.aiter_lines():
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if not data or data == "[DONE]":
                    continue
                try:
                    delta = json.loads(data)["choices"][0]["delta"]
                except (json.JSONDecodeError, KeyError, IndexError):
                    continue
                piece = delta.get("content")
                if piece:
                    yield piece


async def complete_llm(
    settings, messages: list[dict[str, str]], *, max_tokens: int = 400
) -> str:
    """非流式补全，返回完整文本。

    给「工具调度器」这类**只要一小段结构化输出**的调用用。为什么不复用
    `stream_llm`：路由决策要的是一次完整、可直接 `json.loads` 的输出，
    流式只会让我们自己把碎片再拼回来，多一层出错机会。而决定本身只有几十个
    token，非流式与流式的延迟差异可以忽略。

    `temperature=0`：决策要**稳定**，同一句话问两遍不该选到不同工具。
    生成回答那边是 0.3（要的是措辞自然），两者目的不同，不要统一。
    """
    payload = {
        "model": settings.llm_model,
        "messages": messages,
        "stream": False,
        "temperature": 0,
        "max_tokens": max_tokens,
    }
    headers = {
        "Authorization": f"Bearer {settings.llm_api_key}",
        "Content-Type": "application/json",
    }

    timeout = httpx.Timeout(settings.llm_timeout_s, connect=5.0)
    async with httpx.AsyncClient(timeout=timeout) as client:
        response = await client.post(
            f"{settings.llm_base_url}/chat/completions", json=payload, headers=headers
        )
        response.raise_for_status()
        data = response.json()

    try:
        content = data["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as exc:
        # 把原始返回截一段带出来：模型服务换了实现时，这一行是唯一的线索
        raise RuntimeError(f"模型返回结构异常：{str(data)[:200]}") from exc
    return content or ""


def extractive_answer(question: str, hits: Sequence[Hit]) -> str:
    """没有可用模型时的兜底：摘录资料原文，不做润色。

    明确标注是摘录而不是生成——把"这是原文"和"这是模型组织过的话"分开，
    读者才知道该信到什么程度。
    """
    if not hits:
        return "知识库里没有与这个问题相关的资料。"

    lines = [
        "当前未配置大模型（或处于离线演示模式），下面是知识库中与你的问题最相关的原文摘录：",
        "",
    ]
    for hit in hits[:3]:
        title = hit.metadata.get("title", "")
        # 去掉 Markdown 标题记号，摘录里不需要
        body = re.sub(r"^#+\s*", "", hit.text).strip()
        body = re.sub(r"\s+", " ", body)
        excerpt = body[:160] + ("…" if len(body) > 160 else "")
        lines.append(f"· {title}：{excerpt}")
    lines += ["", "以上内容直接摘自知识库，未经整理。配置 LLM_API_KEY 后可获得归纳后的回答。"]
    return "\n".join(lines)


def chunk_for_stream(text: str, size: int = 12) -> list[str]:
    """把整段答案切成小块模拟流式。

    缓存回放和摘录兜底都是一次成文的，但前端只实现了一套流式渲染。
    在这里切成小块，三种模式对前端是同一个协议——少一条分支就少一类只在
    某种模式下才出现的 bug。
    """
    return [text[i : i + size] for i in range(0, len(text), size)]
