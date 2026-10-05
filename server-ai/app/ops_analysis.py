"""M7 运营分析：把**已经算好的结构化指标**交给模型做三段式解读。

与 M3 / M4 的根本区别：**输入不是自然语言问题，而是结构化指标**。

    指标  ← Java `OpsService`（从 `poi_visit_stats` 聚合）
    判定  ← Java `RuleEngine`（已落成 `risk_event`）
    解释  ← 本模块（模型只做这一段）

**这个分工不能反过来。** 模型算数会算错，而且错得看不出来；指标算错时，
模型只会把错的数讲得更圆。所以这里送进去的每一个数字都由 Java 侧算好，
模型的任务只有"把数字讲成人话"，并且**不许自己引入任何新数字**。

三段式（方案 §12.2 / §14）：
    1. 发生了什么（数据事实）
    2. 为什么（**归因假设**，必须标注为假设）
    3. 建议做什么（2–3 条可执行项）

第 2 段刻意叫"假设"而不是"原因"：本系统没有做因果推断的能力，
把相关当因果是最容易被评审问倒的一句话。
"""

from __future__ import annotations

import hashlib
import json
import logging
import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Mapping, Sequence

from .llm import complete_llm

logger = logging.getLogger(__name__)

CST = timezone(timedelta(hours=8))

# 运营解读要"像人写的"，所以温度比调度器高。调度器是 0（要稳定可复现），
# 这里是 0.3 —— 与 M3 生成回答同一个量级。
TEMPERATURE = 0.3
MAX_TOKENS = 900


def _now() -> datetime:
    return datetime.now(CST)


# ===========================================================================
# 关注点：一次只解读一个指标组
#
# 为什么不做"一次解读全部"：面板上的按钮就是"点哪个指标就解读哪个"（方案 §14
# 的 P0 交互）。全量塞进提示词有两个坏处 —— 输出会被摊薄成一句一段的泛泛之谈，
# 而且模型会挑它爱讲的那一块讲，运营点了"销售"却拿到一段讲客流的分析。
# ===========================================================================


@dataclass(frozen=True)
class Focus:
    key: str
    label: str
    #: 这一块在看什么。写进提示词，决定模型把注意力放在哪
    hint: str
    #: 从快照里取哪几段
    slices: tuple[str, ...]


# ★ hint 的用词**必须与指标的真实口径一致**。
#   第一版这里写的是"全市客流""区县客流落差"，结果模型照着说成
#   "当日全市客流 84529 人次"（其实是**核心景区**到访）和
#   "南郑区景区客流 102"（其实是**承载占用率 102%**）。
#   模型没有读错数字，是照着我们的措辞讲的 —— 提示词里的口径错误
#   会 100% 变成输出里的假话，而且看起来特别像真的。
FOCUSES: dict[str, Focus] = {
    "overview": Focus(
        key="overview",
        label="运营总览",
        hint="统计区间内核心景区与乡村的到访、乡村占比，农产品销售与乡村复购，以及待处置风险的总体状况",
        slices=(
            "period_label",
            "synthetic",
            "total_visitors",
            "rural_visitors",
            "rural_ratio",
            "product_sales",
            "repurchase_rate",
            "open_risks",
            "hot_scenic_count",
            "rural_avg_usage",
            "idle_districts",
            "deltas",
        ),
    ),
    "trend": Focus(
        key="trend",
        label="客流与承载趋势",
        hint="统计区间内逐日到访人次与当日承载占用率的变化，以及到访环比",
        slices=("period_label", "synthetic", "trend", "deltas", "hot_scenic_count", "rural_avg_usage"),
    ),
    "mix": Focus(
        key="mix",
        label="业态客流构成",
        hint="统计区间内到访在各业态（核心景区 / 乡村旅游 / 餐饮 / 住宿）之间的分布，重点是乡村占比",
        slices=("period_label", "synthetic", "mix", "total_visitors", "rural_visitors", "rural_ratio"),
    ),
    "imbalance": Focus(
        key="imbalance",
        label="冷热失衡",
        hint="各区县景区与乡村的**平均承载占用率**落差，找出景区高位与乡村闲置并存的地方",
        slices=(
            "period_label",
            "synthetic",
            "imbalance",
            "hot_scenic_count",
            "idle_districts",
            "rural_avg_usage",
        ),
    ),
    "sales": Focus(
        key="sales",
        label="乡村好物销售",
        hint="乡村产地产品的销售额排行与乡村复购率",
        slices=("period_label", "synthetic", "rural_sales_top", "product_sales", "repurchase_rate", "deltas"),
    ),
    "risks": Focus(
        key="risks",
        label="风险事件",
        hint="规则引擎命中的风险事件明细（**含已建单与已闭环的历史**）及其等级、类型、涉及点位",
        slices=("period_label", "synthetic", "risks", "open_risks"),
    ),
}

DEFAULT_FOCUS = "overview"

# ===========================================================================
# 指标口径
#
# ★ 这一段不是"锦上添花的说明"，而是**必需**的。
#
# 为什么：这些字段名（total_visitors、scenic/rural）本身**看不出单位与分母**，
# 而模型一定会替它们补一个最顺口的说法。第一版没有这段，模型就把
# `total_visitors`（核心景区到访人次）讲成"当日全市客流"、把
# `imbalance[].scenic`（承载占用率百分比）讲成"景区客流 102"。
# 数字一个没错，说法全错 —— 而"看起来特别像真的"正是最危险的地方
# （`OpsSnapshotVO` 里为同一个问题写了整整两段注释）。
#
# 只列**容易被读错**的字段，不给全字段清单：全列一遍会把注意力摊薄，
# 而且字段一多，模型会开始挑它认识的讲。
# ===========================================================================

METRIC_GLOSSARY = """★ 三条通用规则，先看这三条再看下面：

  (1) **正文里不要出现英文字段名**。指标 JSON 的键是给程序看的，
      正文要写成中文（`open_risks` 写成"未闭环风险事件"，不要写成
      "当前未闭环的 open_risks 为 16 条"）。
  (2) **比例一律说成百分比**。0–1 的小数（`rural_ratio`、`repurchase_rate`、
      `rural_avg_usage`）在正文里换算成百分比说：0.1684 说成 16.84%、
      0.3113 说成 31.1%。而**已经是百分比数值**的
      （`trend[].usage`、`imbalance[].scenic`）**不要再乘 100** ——
      73.5 就是 73.5%，不是 7350%。
      两者混用会让某个数字凭空差 100 倍，而且看不出来。
  (3) **时间口径一律以 `period_label` 为准，不要自己写"近 7 日"**。
      `period_label` 形如"统计区间 2026-09-28 ～ 2026-10-04（7 天 · 仿真）"，
      它说明这一批指标覆盖的是哪一段。运营可以把统计区间切成
      今日 / 近 7 天 / 近 30 天 / 全部四档，**同一组字段的窗口会变**。
      正文里要么说"该统计区间内"，要么引用 `period_label` 的起止日期；
      ★ **写死"近 7 日"会在切到别的档位时变成假话**（数字是 30 天的、
      却说是近 7 日的），而屏幕上两句话并排、没人能看出对不上。

- total_visitors：**当前统计区间内**核心景区到访人次。不含乡村/餐饮/住宿/交通
  （它们与景区到访高度重叠），单位是**人次**，同一人逛两个景区算两次。
  ★ **不要写成"全市客流"或"独立游客数"**。
  ★ **也不要写成"当日"** —— 只有"今日"档区间才是 1 天；切到近 7 天 /
  近 30 天 / 全部时它是整段区间的**合计**，写成"当日"就把 30 天的数说成一天的。
  区间是几天看 `period_label`；说不清就用"该统计区间内"。
- rural_visitors：当前统计区间内乡村点到访人次，与 total_visitors 同口径
  （同样**不要说成"当日"**）。
- rural_ratio：乡村到访 ÷（核心景区 + 乡村），★ **不是**占全市到访的比例。
- product_sales：**当前统计区间内**农产品销售额，单位元。区间是几天，
  看 `period_label`（它写明了起止日期与天数）—— 不要说成固定"近 7 日"。
- repurchase_rate：**当前统计区间内**乡村复购率 = **复购笔数 ÷ 购买笔数**，
  0–1 的小数。★ 这是**笔数比**，统计口径是"资源点 × 天"的汇总，
  **没有用户身份维度**，所以它**不是**"离境消费的用户里有多少又买了"
  这种**用户级**复购率。称呼固定用"乡村复购率"，
  ★ **不要写成"离境复购率"或"用户复购率"** —— 那会把一个笔数比说成用户留存率。
- open_risks：当前**未闭环**的风险事件数。
- hot_scenic_count：统计区间内**平均**承载占用率 ≥ 80% 的核心景区**个数**
  （不是"某一天超 80% 的次数"，也不是客流人数）。
- idle_districts：景区高位但乡村闲置的**区县名**。
- rural_avg_usage：乡村点在统计区间内的**平均**承载占用率，0–1 的小数（不是人数）。
- deltas.*：与上一个同等长度窗口的真实环比，不是写死的。
- trend[].visitors：**该日**的核心景区到访人次 —— 与 total_visitors 同口径，
  只是逐日展开成 `period_label` 所说天数（7 天档是 7 个点，近 30 天档是 30 个点）。
  ★ 不要说成"近 7 日的当日到访"这类自相矛盾的话。
  trend[].usage：该日整体承载占用率，**单位是百分比数值**（73.5 表示 73.5%）。
- mix[].name / mix[].value：该业态名称 / 该业态在统计区间内的到访人次。
- imbalance[].scenic / imbalance[].rural：该区县景区 / 乡村在统计区间内的
  **平均承载占用率，单位是百分比数值**（102 表示 102%）。
  ★ **它们不是客流人数，不要说成"客流"**。
- rural_sales_top[].sales：该乡村点**当前统计区间内**销售额，单位元。
- risks[]：规则引擎命中的风险事件明细，★ **含已建单与已闭环的历史**
  （看 status 字段），所以它的条数不一定等于 open_risks。"""

# ===========================================================================
# 提示词
# ===========================================================================

SYSTEM_PROMPT = """你是{city}市智慧文旅平台的运营分析助手。你会收到一组**已经算好**的结构化运营指标，
你要把它讲成一段运营人员能直接用的解读。

【硬约束】
1. **只能用给出的数字**。不得引入任何未出现在指标里的数字、地名、点位名或事件。
   指标里没有的，就不要提；需要举例时用指标里出现的名字。
2. **按下面的口径称呼每一个数**。同一个数字换个说法就可能从"对"变成"错"，
   而这类错误在页面上看不出来（详见【指标口径】）。
3. 输出**严格三段**，每段 2–4 句，依次是：
   - 发生了什么：只陈述指标里的事实，不要解释原因。
   - 为什么（归因假设）：明确写成**假设**口吻（例如"可能与…有关"），
     不要写成结论。本系统没有做因果推断，把相关说成因果会被追问倒。
   - 建议做什么：2–3 条可执行的运营动作，每条要能落到具体点位或指标上。
4. 数据是**仿真数据**，不要把它描述成真实统计；也不要在正文里反复强调这一点
   （界面会统一标注），只在语气上避免"实测""调研显示"这类词。
5. 不要输出 Markdown 标题（`#`、`##`）、表格或代码块。正文里可以用 `**加粗**`
   强调关键数字或点位名，其余按纯文本段落写。
6. 不要寒暄、不要复述任务、不要写"以下是分析"。

【输出格式】只输出一个 JSON 对象，不要包代码围栏：
{{"fact": "第一段正文", "why": "第二段正文", "todo": "第三段正文"}}
"""

USER_TEMPLATE = """【本次解读的关注点】{label}
【这一块在看什么】{hint}

【指标口径】（★ 必须按这里的说法称呼每一个数）
{glossary}

【指标】（period_label 说明了时间口径；synthetic=true 表示仿真数据）
{metrics}

请按三段式输出 JSON。"""


def build_messages(
    settings,
    focus: Focus,
    metrics: Mapping[str, Any],
) -> list[dict[str, str]]:
    """组装消息。

    ★ 指标 JSON 里**一定有花括号**，所以它只能走普通字符串拼接，
    不能当 `_fill` 的模板值 —— 否则 JSON 里的 `{...}` 会被当成占位符再扫一遍。
    这是 M3/M4 那边 `_fill` 注释里提醒过的同一类问题，这里直接绕开。
    """
    system = SYSTEM_PROMPT.replace("{city}", settings.city_name)
    user = USER_TEMPLATE.format(
        label=focus.label,
        hint=focus.hint,
        glossary=METRIC_GLOSSARY,
        metrics=json.dumps(metrics, ensure_ascii=False, indent=1, sort_keys=True),
    )
    return [
        {"role": "system", "content": system},
        {"role": "user", "content": user},
    ]


# ===========================================================================
# 指标切片与「依据」
# ===========================================================================


def slice_metrics(focus: Focus, metrics: Mapping[str, Any]) -> dict[str, Any]:
    """只把本关注点用得到的字段送进去。

    除了省 token，更重要的是**减少模型挑错重点的机会**：送 5 段它就讲 5 段。
    """
    return {key: metrics.get(key) for key in focus.slices if metrics.get(key) is not None}


def metrics_digest(sliced: Mapping[str, Any]) -> str:
    """切片摘要。用来判断缓存是不是"同一批数据"算出来的。

    用 sort_keys + 固定分隔符，保证同一份指标在任何机器上都得到同一个摘要。
    """
    blob = json.dumps(sliced, ensure_ascii=False, sort_keys=True, default=str)
    return hashlib.sha1(blob.encode("utf-8")).hexdigest()[:12]


def _pct(value: Any) -> str:
    try:
        return f"{float(value) * 100:.1f}%"
    except (TypeError, ValueError):
        return "—"


def _int(value: Any) -> str:
    try:
        return f"{int(value):,}"
    except (TypeError, ValueError):
        return "—"


def _yuan(value: Any) -> str:
    try:
        return f"{float(value):,.0f} 元"
    except (TypeError, ValueError):
        return "—"


def _pctnum(value: Any) -> str:
    """承载占用率**已经是百分比数值**（73.5 表示 73.5%），不能再乘 100。

    与 `_pct` 分开是刻意的：快照里两种都有 —— `rural_ratio` 是 0–1 的小数，
    `trend[].usage` 与 `imbalance[].scenic` 是百分比数值。
    混用同一个格式化函数，会让某一类数字凭空差 100 倍，而且**不报错**。
    """
    try:
        return f"{float(value):.1f}%"
    except (TypeError, ValueError):
        return "—"


def _range_prefix(metrics: Mapping[str, Any]) -> str:
    """把快照的 `range` 档位翻译成指标卡上那个周期前缀。

    ★ 必须与前端 `Dashboard.vue` 的 `rangePrefix` **是同一套词**。
    依据（basis）是"给人对着面板核"的那一份，标签差一个字就核不上 ——
    而"核不上"在界面上表现为"这两个数好像是两回事"，比不显示更糟。
    不认识的档位（含老缓存里没有 `range` 的情况）回落"今日"，
    与后端 `normalizeRange` 的回落方向保持一致。
    """
    return {
        "LAST7": "近 7 日",
        "LAST30": "近 30 日",
        "ALL": "全部",
    }.get(str(metrics.get("range") or "").upper(), "今日")


def basis_of(focus: Focus, metrics: Mapping[str, Any]) -> list[dict[str, str]]:
    """「模型依据的是这些数」。

    ★ 这一段**由代码算，不由模型生成**。理由是它必须可核对：
    如果让模型复述数字，它会顺手改写（把 84,529 写成"约 8.5 万"、
    把 3.2% 写成"3%"），于是"依据"和面板上的数字对不上 ——
    而这一段存在的唯一意义就是"你能对着面板核"。

    ★ 标签的措辞**必须与 `OpsSnapshotVO` 的口径一致**。第一版把
    `total_visitors` 标成"近 7 日到访总量"，它其实是**当日核心景区**到访。
    依据上的口径写错，模型就会照着错的讲 —— 这一段的可信度全在措辞准确上。

    ★ **周期前缀必须随档位走**（2026-10-04 加）。加了统计区间切换之后，
    同一个 `total_visitors` 在"今日"档是 1 天的数、在"近 30 天"档是 30 天的
    合计。标签写死"当日"的话，切到近 30 天时依据上会出现
    "当日核心景区到访 2,370,783 人次" —— 一个把 30 天说成 1 天的假话，
    而它就印在"你能对着核"的那一栏里。前缀与前端 `rangePrefix` 同一套词。

    除总览外，每个关注点都**把自己那一段明细的头几行列出来**：
    只给一两个汇总数，模型讲细节时就没有可核对的锚点。
    """
    rows: list[dict[str, str]] = []
    add = lambda label, value: rows.append({"label": label, "value": value})  # noqa: E731
    p = _range_prefix(metrics)

    if focus.key in ("overview", "trend"):
        add(f"{p}核心景区到访", f"{_int(metrics.get('total_visitors'))} 人次")
        add("到访环比", f"{float(metrics.get('deltas', {}).get('visitors_pct') or 0):+.1f}%")
    if focus.key == "trend":
        points = [p_ for p_ in (metrics.get("trend") or []) if isinstance(p_, dict)]
        if points:
            peak = max(points, key=lambda x: x.get("visitors") or 0)
            low = min(points, key=lambda x: x.get("visitors") or 0)
            # 趋势图的窗口是 max(range, 7 天) —— 所以峰谷的周期**按点数说**，
            # 不按 range 说。否则"今日"档会标成"今日峰值"而它其实是 7 天的峰值。
            add(
                f"近 {len(points)} 日峰值",
                f"{peak.get('date')} {_int(peak.get('visitors'))} 人次 / 承载 {_pctnum(peak.get('usage'))}",
            )
            add(
                f"近 {len(points)} 日低谷",
                f"{low.get('date')} {_int(low.get('visitors'))} 人次 / 承载 {_pctnum(low.get('usage'))}",
            )
    if focus.key in ("overview", "mix"):
        add(f"{p}乡村旅游到访", f"{_int(metrics.get('rural_visitors'))} 人次")
        add(f"{p}乡村旅游客流占比", _pct(metrics.get("rural_ratio")))
    if focus.key == "mix":
        for item in (metrics.get("mix") or [])[:4]:
            add(f"{p}{item.get('name')}到访", f"{_int(item.get('value'))} 人次")
    if focus.key in ("overview", "imbalance"):
        add(f"{p}承载超 80% 的核心景区", f"{metrics.get('hot_scenic_count', '—')} 处")
        add(f"{p}乡村点平均承载", _pct(metrics.get("rural_avg_usage")))
        idle = metrics.get("idle_districts") or []
        if idle:
            add("景区高位与乡村低位并存", "、".join(idle))
    if focus.key == "imbalance":
        for row in (metrics.get("imbalance") or [])[:3]:
            add(
                f"{row.get('name')} 景区/乡村承载",
                f"{_pctnum(row.get('scenic'))} / {_pctnum(row.get('rural'))}",
            )
    if focus.key in ("overview", "sales"):
        add(f"{p}乡村产地产品销售额", _yuan(metrics.get("product_sales")))
        # 标签写全称"乡村复购率"并附口径：这里是**依据**，是给人核对的那一份，
        # 名称必须和面板上的指标卡一字不差，否则"核对"就无从谈起。
        add(f"{p}乡村复购率（复购笔数 / 购买笔数）", _pct(metrics.get("repurchase_rate")))
    if focus.key == "sales":
        for row in (metrics.get("rural_sales_top") or [])[:3]:
            add(str(row.get("name")), _yuan(row.get("sales")))
    if focus.key in ("overview", "risks"):
        # 括号里写"全部点位"而不是"全部"：它是**全市所有点位**的未闭环数，
        # 与下面"快照明细"的条数不是一回事（快照只挑了 5 条展示）。
        # ★ 不能只写"（全部）" —— 加了统计区间档位之后，"全部"这个词
        # 已经被"全部（时间）档"占用了，两个意思混在一个词里必被读错。
        add(f"{p}未闭环风险事件（全部点位）", f"{metrics.get('open_risks', '—')} 条")
    if focus.key == "risks":
        detail = [r for r in (metrics.get("risks") or []) if isinstance(r, dict)]
        add("快照中的风险明细", f"{len(detail)} 条（含已建单/已闭环）")
        if detail:
            # ★ 模型正文里会讲"HIGH 级 3 条、MID 级 2 条"，依据里就得有对应的数 ——
            #   否则那句话没法核。依据存在的意义就是"你能对着它核"。
            high = sum(1 for r in detail if r.get("level") == "HIGH")
            mid = sum(1 for r in detail if r.get("level") == "MID")
            add("明细中 HIGH / MID", f"{high} / {mid} 条")
            pending = sum(1 for r in detail if r.get("status") == "OPEN")
            add("明细中仍待处置", f"{pending} 条")
    return rows


# ===========================================================================
# 输出解析
# ===========================================================================

SECTION_TITLES = {
    "fact": "发生了什么",
    "why": "为什么（归因假设）",
    "todo": "建议做什么",
}


def parse_sections(raw: str) -> list[dict[str, str]]:
    """宽容解析模型输出，救不回来抛 ValueError。

    会遇到的脏输出与 M4 的调度器同源：包了 ```json 围栏、前面带一句
    "好的，我来分析"，或者多包了一层 `{"analysis": {...}}`。
    """
    text = (raw or "").strip()
    if not text:
        raise ValueError("模型返回空内容")

    if text.startswith("```"):
        text = re.sub(r"^```[a-zA-Z]*\s*", "", text)
        text = re.sub(r"```\s*$", "", text).strip()

    start, end = text.find("{"), text.rfind("}")
    if start == -1 or end <= start:
        raise ValueError(f"模型没有输出 JSON：{text[:120]}")
    payload = json.loads(text[start : end + 1])
    if not isinstance(payload, dict):
        raise ValueError("模型输出的不是对象")

    # 多包一层时往下找一层（模型偶尔会写成 {"analysis": {...}}）
    for key in ("analysis", "result", "data"):
        inner = payload.get(key)
        if isinstance(inner, dict) and any(k in inner for k in SECTION_TITLES):
            payload = inner
            break

    sections: list[dict[str, str]] = []
    for kind, title in SECTION_TITLES.items():
        body = payload.get(kind)
        if isinstance(body, list):  # 模型把 todo 写成数组，是合理写法，拼起来
            body = "\n".join(str(x) for x in body)
        body = str(body or "").strip()
        if body:
            sections.append({"kind": kind, "title": title, "text": body})

    if len(sections) < 3:
        missing = [k for k in SECTION_TITLES if k not in {s["kind"] for s in sections}]
        raise ValueError(f"模型缺少段落：{'、'.join(missing)}")
    return sections


# ===========================================================================
# 离线演示缓存（只读）
#
# 与 M3 的 `qa_demo.json` 同一思路，但**生成方式不同**，原因是本模块的答案
# 里全是数字：手写的答案第二天就和面板对不上了。所以这份缓存由
# `scripts/build_ops_demo.py` 跑一次真实调用后**存下来**，运行时只读。
#
# 它记了 `metrics_digest`：数据换了以后回放，响应里 `stale=true`，
# 界面会说明"回放的是 X 月 X 日那批数据"。**宁可口径说清楚，也不要
# 让一段过期数字冒充当前解读**。
# ===========================================================================


class OpsAnalysisCache:
    def __init__(self, path: Path) -> None:
        self.path = path
        self._analyses: dict[str, dict[str, Any]] = {}
        self._meta: dict[str, Any] = {}
        self._load()

    def _load(self) -> None:
        try:
            raw = json.loads(self.path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            logger.info(
                "[M7] 运营分析缓存不存在（%s），离线演示只能返回「不可用」", self.path
            )
            return
        except Exception as exc:  # noqa: BLE001 - 缓存坏了不该让服务起不来
            logger.warning("[M7] 运营分析缓存读取失败：%s", exc)
            return
        if not isinstance(raw, dict):
            return
        analyses = raw.get("analyses")
        self._analyses = analyses if isinstance(analyses, dict) else {}
        meta = raw.get("meta")
        self._meta = meta if isinstance(meta, dict) else {}

    def __len__(self) -> int:
        return len(self._analyses)

    @property
    def meta(self) -> dict[str, Any]:
        return dict(self._meta)

    def lookup(self, focus_key: str) -> dict[str, Any] | None:
        entry = self._analyses.get(focus_key)
        return dict(entry) if isinstance(entry, dict) else None


# ===========================================================================
# 主入口
# ===========================================================================


def _result(
    *,
    focus: Focus,
    metrics: Mapping[str, Any],
    sliced: Mapping[str, Any],
    sections: Sequence[Mapping[str, str]],
    mode: str,
    model: str,
    generated_at: str,
    stale: bool = False,
    note: str = "",
) -> dict[str, Any]:
    return {
        "focus": focus.key,
        "focus_label": focus.label,
        "mode": mode,  # llm / cache / unavailable
        "model": model,
        "generated_at": generated_at,
        "stale": stale,
        "note": note,
        "period_label": metrics.get("period_label"),
        "synthetic": bool(metrics.get("synthetic", True)),
        "sections": [dict(s) for s in sections],
        # 传**全量** metrics 而不是 sliced：basis 的标签要按档位加周期前缀
        # （"近 30 日乡村复购率"），前缀要从 `range` 取，而 `range` 不在切片里。
        # 加哪些行仍由 focus 决定（见函数内的 if），所以传全量不会多出行来。
        "basis": basis_of(focus, metrics),
    }


def _unavailable(focus: Focus, metrics: Mapping[str, Any], sliced: Mapping[str, Any], reason: str) -> dict[str, Any]:
    """没有模型、也没有缓存时的返回。

    ★ 这里**不回落成模板文案**。驾驶舱原来那段"运营建议"就是模板，
    M7 把它换掉的全部意义在于"这一段真的有模型参与"；再回落成模板，
    界面就会在不知情的情况下把模板当 AI 解读展示 —— 那正是我们要消除的东西。
    """
    return _result(
        focus=focus,
        metrics=metrics,
        sliced=sliced,
        sections=[],
        mode="unavailable",
        model="",
        generated_at=_now().isoformat(timespec="seconds"),
        note=reason,
    )


async def analyze(
    settings,
    focus_key: str,
    metrics: Mapping[str, Any],
    cache: OpsAnalysisCache,
) -> dict[str, Any]:
    focus = FOCUSES.get(focus_key) or FOCUSES[DEFAULT_FOCUS]
    sliced = slice_metrics(focus, metrics)
    digest = metrics_digest(sliced)

    offline = (not settings.llm_enabled) or settings.demo_mode
    if offline:
        reason = "DEMO_MODE 已开启" if settings.demo_mode else "未配置模型"
        return _from_cache(focus, metrics, sliced, digest, cache, reason)

    try:
        raw = await complete_llm(
            settings,
            build_messages(settings, focus, sliced),
            max_tokens=MAX_TOKENS,
            temperature=TEMPERATURE,
        )
        sections = parse_sections(raw)
    except Exception as exc:  # noqa: BLE001 - 模型挂了要能用缓存顶住
        logger.warning("[M7] 模型解读失败（focus=%s）：%s", focus.key, exc)
        return _from_cache(focus, metrics, sliced, digest, cache, f"模型调用失败：{exc}")

    return _result(
        focus=focus,
        metrics=metrics,
        sliced=sliced,
        sections=sections,
        mode="llm",
        model=settings.llm_model,
        generated_at=_now().isoformat(timespec="seconds"),
    )


def _from_cache(
    focus: Focus,
    metrics: Mapping[str, Any],
    sliced: Mapping[str, Any],
    digest: str,
    cache: OpsAnalysisCache,
    reason: str,
) -> dict[str, Any]:
    entry = cache.lookup(focus.key)
    if not entry:
        return _unavailable(focus, metrics, sliced, f"{reason}，且没有可回放的缓存。")
    sections = entry.get("sections")
    if not isinstance(sections, list) or len(sections) < 3:
        return _unavailable(focus, metrics, sliced, f"{reason}，缓存的条目结构不完整。")

    stale = entry.get("metrics_digest") != digest
    generated_at = str(entry.get("generated_at") or "")
    note = f"离线回放（{reason}）"
    if stale:
        # 数据换了却回放旧解读 —— 必须说出来，否则就是拿过期数字冒充当前解读
        note += f"；回放的是 {entry.get('period_label') or generated_at} 那批数据，与当前面板数字可能不一致"
    return _result(
        focus=focus,
        metrics=metrics,
        sliced=sliced,
        sections=sections,
        mode="cache",
        model=str(entry.get("model") or ""),
        generated_at=generated_at or _now().isoformat(timespec="seconds"),
        stale=stale,
        note=note,
    )
