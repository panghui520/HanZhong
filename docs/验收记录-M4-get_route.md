# M4 路线规划（`get_route`）· 验收记录

> 验收日期：2026-09-27 · 结论：**通过**
> （工具逻辑 **23 项** / 真实高德链路 **10 项** / 端到端路由 **6 项** / 前端呈现 **9 项**，四层全绿）
> 范围：`server-ai/app/amap.py`（新增 `direction_driving`）→ `tools.py`
> （`TOOL_ROUTE` + `ToolSpec` + `_get_route` + `execute` 分支）→ `llm.py`
> （路由规则 + 地名约束）→ `web/src/types/index.ts` + `web/src/views/portal/Agent.vue`
> （工具中文名 + 状态条"条数"文案）→ 四个探针脚本（`.workbuddy-ai/tmp/`）
> 需求来源：`docs/验收记录-M4.md` 第九节「阶段三」，原设计写的是
> "`get_route`（高德路径规划，**酒店 → 景点**）"。

---

## 一、口径：做的和原设计**不是一回事**

这一节放在最前面，因为它是本模块最容易被误读的地方。

| | 原设计 | 实际实现 |
|---|---|---|
| 起终点怎么定 | 用 `selected_attraction` 当终点（`docs/M4-第一阶段设计分析.md:130`），即"酒店 → 景点" | **通用两点驾车**：`from` / `to` 两个地名**直接取自用户原话**，路由提示词明确禁止模型改写地名 |
| 和行程的关系 | 行程链路上的一环（算这一段车程） | **没打通**：与 `trip_context` / `plan_itinerary` 之间没有调用关系 |
| 产出 | 路线 + 车程，画在地图上 | **只给距离与时长**；`cards=[]`、不返回 polyline |

所以必须分清两句话：

- ✅ **"问两地怎么走"已经能答了。** 问「汉中站到青木川古镇多远」会真的调高德算出路线。
- ❌ **"行程里逐段带车程"仍然没做**，互动地图上仍然没有路线。
  **后者别算进已完成的账。**

## 二、为什么验收要分四层

这是本模块最值得留下的一条经验。**一层验收测不出另一层的错**：

| 层 | 脚本 | 覆盖 | 打网络 | 打模型 | 开浏览器 |
|---|---|---|---|---|---|
| ① 工具逻辑 | `accept_m4_route.py` | `_get_route` 的全部分支（正常 / 起点失败 / 终点失败 / 高德抛错 / 无路线 / 空参 / 时长格式 / 字段约定 / 解析优先级） | ✗（高德被 mock） | ✗ | ✗ |
| ② 高德 HTTP 链路 | `probe_route_live.py` | `AmapClient.direction_driving` 的 URL / 参数 / 响应解析 | ✓ | ✗ | ✗ |
| ③ 端到端路由 | `probe_route_e2e.py` | **模型会不会选中 `get_route`** + 真高德出数 | ✓ | ✓ | ✗ |
| ④ 前端呈现 | `probe_route_ui.mjs` | 助手页上**工具名**与**条数文案**怎么显示 | ✓ | ✓ | ✓ |

第 ① 层当时 **21 条全绿**，而代码里有两个真 bug —— 因为 mock 把
`direction_driving` **整个方法**换掉了，URL 根本没被走过；假 resolver 又比真对象
"宽容"。第 ③ 层全绿时前端还错着两处 —— 因为 **SSE 事件里每一项都合法**
（`name:"get_route"` 是字符串、`count:0` 是数字），错的是"前端拿它渲染成了什么"。

**这是本模块最大的教训：每一层只能看见自己那一层。**
"协议对了"不等于"画对了"，"工具能调通"不等于"URL 拼对了"。

## 三、交付物

| 文件 | 改动 |
|---|---|
| `server-ai/app/amap.py` | 新增 `AmapClient.direction_driving()`；给 `_get` 补上"`path` 不带 `/v3`"的约定说明；修正 `direction_driving` 关于返回 `None` 的注释 |
| `server-ai/app/tools.py` | `TOOL_ROUTE` 常量、`ToolSpec` 菜单项、`_get_route()`、`execute()` 分支；修正 `_resolve` 的解析契约；`origin/destination` 改用 `GeoPoint.location` |
| `server-ai/app/llm.py` | 路由规则 1–6（含"问两地距离/怎么走/自驾多久 → `get_route`"）+ "`from`/`to` 必须取自用户原话"的约束 |
| `web/src/types/index.ts` | `AGENT_TOOL_LABEL` 加 `get_route: '高德 · 驾车路线'`；`AgentToolName` 补 `plan_itinerary`/`get_route` 并加 `satisfies` 编译期兜底；新增 `AGENT_TOOL_COUNT_UNIT` |
| `web/src/views/portal/Agent.vue` | 状态条"条数"那格改由 `countMeta()` 决定（新增 helper），不再就地三元判断 |
| `.workbuddy-ai/tmp/accept_m4_route.py` | 21 → **23** 条；`_FakeResolver` 重写为返回真 `ResolvedPlace`；新增 G10 |
| `.workbuddy-ai/tmp/probe_route_live.py` | 新增（真实高德链路） |
| `.workbuddy-ai/tmp/probe_route_e2e.py` | 新增（端到端路由） |
| `.workbuddy-ai/tmp/probe_route_ui.mjs` | 新增（前端呈现 + 行程那格回归） |

**前端呈现上只做了"让它显示对"，没做"路线卡"。** `cards=[]`，所以
`Agent.vue` 的判别式渲染不会拿到 `kind=route` 的卡片，只在正文里显示。
这是刻意的取舍，见 §八.2。

## 四、验收结果

### 4.1 工具逻辑（`accept_m4_route.py`）—— 23/23

覆盖 G1–G10。新增的 G10 见 §五.2，它是这次最该加的一条。

### 4.2 真实高德链路（`probe_route_live.py`）—— 10/10

```
--- 1) 真实 geocode ---
   汉中站     -> GeoPoint(lng=107.029962, lat=33.090721, level='兴趣点')
   青木川古镇 -> GeoPoint(lng=105.580353, lat=32.830272, level='乡镇')
--- 2) 真实 direction_driving ---
   -> {'distance_m': 175213, 'duration_s': 13896, 'strategy': '速度最快'}
--- 3) _get_route 挂真客户端（不给 resolver，逼它走 geocode 兜底）---
   text : 从「汉中站」到「青木川古镇」自驾 175.2 公里，约 3 小时 52 分钟（高德驾车规划）
--- 4) 汉中 → 东京 ---
   抛出 AmapError: 高德返回错误 20011：INSUFFICIENT_ABROAD_PRIVILEGES
--- 5) 坐标写成中文 ---
   抛出 AmapError: 高德返回错误 20000：INVALID_PARAMS
```

### 4.3 端到端路由（`probe_route_e2e.py`）—— 6/6（2 问 × 3 断言）

进程内 ASGI 打 `/ai/agent`，真模型 + 真高德：

| 问 | 模型决策 | 回答正文 |
|---|---|---|
| 汉中站到青木川古镇多远 | `get_route{from:汉中站, to:青木川古镇}` | 自驾约 **175.7 公里**，约 3 小时 50 分钟 |
| 从汉中博物馆到石门栈道自驾要多久 | `get_route{from:汉中博物馆, to:石门栈道}` | 全程约 **20.4 公里**，预计约 38 分钟 |

**★ 这两个数字是"修好之后"的**。修之前同一脚本跑出来是 175.2 / 18.6 公里 ——
差在起点坐标：修之前第一档解析是死的，用的是高德地理编码的坐标；修之后用本地地名表。
数字变了，正是 §五.2 那个修复生效的证据。

### 4.4 前端呈现（`probe_route_ui.mjs`）—— 9/9

真浏览器（CDP，1680×1100）在 `/agent` 页问两句，量渲染出来的 DOM：

| 量什么 | 修之前 | 修之后 |
|---|---|---|
| 工具名（`.ans__tool`） | `get_route`（英文原名） | `高德 · 驾车路线` |
| 状态条条数格（`.tstep__meta`） | **"返回 0 条"** | 不渲染 |
| 卡片容器（`.cards` / `.scard`） | 0 / 0（`cards=[]` 没渲染空壳） | 0 / 0 |
| 正文 | 含 175.2 公里 | 含 175.2 公里 |

第二问是**回归**：`帮我规划汉中两日游` → 工具名 `本地数据包 · 行程规划`、
条数格 **"共 2 天"**、行程卡片正常渲染。这条必须跑，因为本次把行程那格的判定
从 `cardKind === 'itinerary'` 改成了 `tool.name === 'plan_itinerary'`（见 §五.3）。

### 4.5 M3 未受影响

本轮只动 `server-ai/app/{amap,tools,llm}.py` 里与路线相关的分支，没碰检索、
语料、`/ai/qa` 的任何路径。M3 的三份回归数字（top1 40/51、含等价 49/51、top5 51/51）
不适用本轮的改动面，未重跑。

## 五、本轮抓到的三个真 bug（★ 核心）

前两个是**第 ① 层全绿时存在的**，第三个是**第 ③ 层全绿时存在的**。

### 5.1 `amap.py` 的路径多写了一个 `/v3`

```python
payload = self._get("/v3/direction/driving", {...})   # ✗
```

这个客户端的约定是 **`base_url` 已含 `/v3`**（`config.amap_base_url` 默认
`https://restapi.amap.com/v3`），所以 `path` 不能再带。结果请求打到
`…/v3/v3/direction/driving`。

**症状极具误导性**：高德**不报 404**，回的是
`10002 SERVICE_NOT_AVAILABLE` —— 看着像"这个 key 没开通驾车路径规划服务"，
而同一个 key 的 geocode 是通的，于是第一反应会去查高德控制台的服务勾选。

隔离方法（`probe_amap_scope.py`）：拿同一组参数**裸 HTTP** 打一次。

```
② v3 driving 最小参数          -> status=1 infocode=10000 info=OK   距离 175213
③ v3 driving 我们代码的参数     -> status=1 infocode=10000 info=OK   距离 175213
```

参数一模一样、裸调用能通、走客户端不通 → 问题只可能在拼 URL 的那一层。
已改，并在 `_get` 上把这个约定写进 docstring（含"高德会回 10002 而不是 404"
这条症状描述），免得下一个人再查一遍控制台。

### 5.2 `tools.py` 的 `_resolve` 与真实解析契约不符 —— **第一档从来没生效过**

```python
resolved = resolver.resolve(name, city=city_name)   # ✗ 真签名是 resolve(query)
...
return resolved.point                                # ✗ 真对象只有 .lng / .lat
```

`PlaceResolver.resolve(query)` **只有一个位置参数、没有 `city`**，返回的
`ResolvedPlace` 坐标在 `.lng` / `.lat`（外加 `.location` 属性给 `"lng,lat"`），
**没有 `.point`**。两处错误都被 `except Exception` 吞掉、**静默降级到
`amap.geocode`** —— 于是"本地地名表优先"这一档**永远不生效**。

**为什么这份验收当时是绿的**：`accept_m4_route.py` 里的 `_FakeResolver`
自己编了 `city=` 参数、自己捏了个带 `.point` 的对象。**假对象比真对象"宽容"，
测试就把错误的契约固化了下来。**

后果不是崩，而是**悄悄变差**：`tools.py:428` 那段注释记着"本模块历史上最贵的一处
修复"—— 高德地理编码对「汉中高铁站」返回的首条是「洋县高铁站(公交站)」，
本地表优先正是为了避开它。这一档死了，等于那个 bug 又回来了。

**三处修复**：

1. `_resolve` 改用真实签名 `resolve(name)`、读 `.lng/.lat`，并注明"别传 `city=`"。
2. **假对象改用真的 `ResolvedPlace`** —— 真类改字段，假对象跟着报错，没有缝。
   同时**刻意不写 `**kwargs`**：多传参数要当场炸，而不是被上层吞掉。
3. **新增 G10**：让本地表和 `amap.geocode` **故意给不同坐标**，断言请求里带的是
   本地表那一组。

第 3 条是关键。原来的 21 条断言**两边给一样的坐标**，所以"谁赢"根本测不出来。
G10 是唯一能发现这类错误的断言 —— 已用"把 bug 注回去"的方式验过它有牙齿：

```
注入旧 bug 后：  FAIL 起点用的是本地地名表的坐标 | 实际 origin=111.111000,22.222000
恢复后：        PASS 起点用的是本地地名表的坐标
```

### 5.3 前端两处漏项 —— **第 ③ 层全绿时存在的**

后端加了工具、前端没跟上。两处都在**助手页渲染出来之后**才看得见：

**① `AGENT_TOOL_LABEL` 没有 `get_route`** → 模板回落 `?? t.tool.name`，
页面上直接显示英文原名 `get_route`（其余工具都是"高德 · 附近搜索"这种中文标签）。

这不是新问题，是**漏了一步常规动作**：`docs/验收记录-M4-行程规划.md:103` 的交付物
清单里就写着"`AGENT_TOOL_LABEL` 加一行"。这次加了后端、写了提示词、跑了三层验收，
唯独漏了它。已补 `get_route: '高德 · 驾车路线'`。

**并且加了编译期兜底**，让下次漏不掉：

```ts
export type AgentToolName = 'knowledge_search' | … | 'get_route' | 'none'

export const AGENT_TOOL_LABEL: Record<string, string> = {
  …
} satisfies Record<AgentToolName, string>   // ← 漏一项就编译不过
```

`Record<string, string>` 的声明保留，是为了能直接用事件里的 `name: string` 索引；
`satisfies` 只做完整性检查、不改类型 —— "索引方便"与"漏项报错"同时成立。
顺带发现 `AgentToolName` 里也漏了 `plan_itinerary`，一并补齐。

**② 状态条把 `count=0` 渲染成了"返回 0 条"** → `get_route` 没有"条数"这回事
（距离与时长在正文里），`count` 恒为 0。原文案：

```vue
{{ t.cardKind === 'itinerary' ? `共 ${t.tool.count} 天` : `返回 ${t.tool.count} 条` }}
```

只看 `count != null` 就会把 0 也渲染出来。改法不是就地加一个 `if`，而是把
**"这个工具的 count 是什么单位"变成一份数据**：

```ts
export const AGENT_TOOL_COUNT_UNIT: Record<string, string | null> = {
  plan_itinerary: '天',   // count 是**天数**，写成"返回 2 条"会让用户以为只查到两个点
  get_route: null,        // 没有条数含义 → 那一格不渲染
}
```

模板改成 `v-if="… && countMeta(t.tool)"`，`countMeta` 是新增的 helper。
**判定也从 `t.cardKind` 改成了 `tool.name`**：`name` 在 tool 事件里就有，
不必等 cards 事件到了才显示对，也就没有"先显示错、再改对"的一帧。
（`cardKind` 没有变成死代码，POI 卡片的插画场景还在用它。）

**为什么这一层必须单独验**：SSE 事件里 `{"name":"get_route","status":"done","count":0}`
**每一项都合法** —— 协议层没有任何可断言的东西是错的。
错的是"前端拿它渲染成了什么"。`probe_route_ui.mjs` 同时覆盖了行程那格的**回归**
（本次改了它的判定方式），实测仍是"共 2 天"。

## 六、顺手修正的一处注释过度声称

`amap.direction_driving` 原来写"返回 `None` 表示**没有路径**（如跨城超出驾车服务范围）"。
实测：**跨到境外不返回 `None`，抛 `AmapError 20011 INSUFFICIENT_ABROAD_PRIVILEGES`**；
参数非法抛 `20000 INVALID_PARAMS`。

**改的是注释，不是实现。** 抛 `AmapError` 并带上 `infocode` 是更正确的做法 ——
它把"为什么没有路线"说清楚了，而 `None` 只能说"没有"。
`_get_route` 的 `None` 分支仍然保留（`route.paths` 为空是另一个真实情形，只是难造），
只是把里面那句"多半是跨城太远"删掉了 —— 那是没实测就下的结论。

## 七、文档回核

按 `post-module-doc-audit` 逐条搜 `get_route` / `阶段三` / `未开始`：

| 文件 | 原写 | 改成 |
|---|---|---|
| `docs/模块划分与依赖.md:44`（模块表 M4 行） | "`get_route` 与落库未开始" | `get_route` 已验收；落库仍未开始；★ 标出"通用两点、没和行程打通" |
| `docs/模块划分与依赖.md:262` | "阶段三（`get_route`）仍未开始" | 已补齐，并指向落地差异表 |
| `docs/模块划分与依赖.md:270` | 表行"三 … 未开始" | ✅ 已验收 + 新增「阶段三的落地差异」三行表 |
| `docs/模块划分与依赖.md:270` 路径 | 高德 `/v3/direction/*` | `/v3/direction/driving`（实际生效的 URL） |
| `docs/验收记录-M4.md` 第九节阶段表 | "三 … 酒店 → 景点" | 保留原行 + 补记"已做但口径不同" |
| `docs/验收记录-M4-行程规划.md:241` | "`get_route`…路线与车程仍交给地图导航" | 保留原句 + 说明"工具做了但行程没调它" |
| `docs/验收记录-M4-互动地图.md:284` | "`get_route` 仍未做" | 保留原句 + 说明"做了但不返回 polyline，地图画路线仍未做" |
| `docs/M4-第一阶段设计分析.md:6 / :130` | 阶段三用 `selected_attraction` 当终点 | 保留原表 + 头部补记"实际没读这个字段" |

**一处刻意没改**：`docs/验收记录-M4.md` 第九节的标题仍是「本轮明确未做」——
那是**当时那一轮的快照**，改了就不是记录了。所以用"补记"的形式加在表格下方。

## 八、已知边界（答辩要能说清）

1. **是通用两点，不是"酒店 → 景点"。** 见 §一。
2. **不返回路径线。** `extensions=base` 只取距离/时长。理由：路径线要前端画才有用，
   没画就是一个 `A → B` 的两行字，与正文重复。所以 `cards=[]`。
3. **`strategy=0`（速度优先）。** 不躲拥堵 —— 演示场景里"躲拥堵"会让同一问题的
   答案随早晚高峰变，反而不好讲。
4. **`None` 分支难造。** 真正会触发的多是 `AmapError`（境外权限、参数错）。
   这条分支保留是为了不出现"没写过的路径"。
5. **模型可能选不中它。** 第 ③ 层验的就是这件事，但它依赖在线模型；
   模型抖动时同一句话可能落到 `knowledge_search`。**没有兜底规则**——
   `_fallback_decision` 只回知识库（离线时的设计，见 `agent.py`）。
6. **`/ai/agent` 没有离线缓存。** 缓存目录里只有 M3 的 `qa_demo.json` 与 M7 的
   `ops_demo.json`，**没有 agent 的回放文件** → 演示 M4 必须联网。
   这是本轮**发现但没修**的问题（不在本次范围内）。
7. **httpx 的 INFO 日志会把 `key=` 打进 URL。** 所有高德调用都这样（不只本模块），
   本地日志里能看到 key 明文。也是**发现但没修**。

## 九、本轮未做

- 前端路线卡片（要不要一张"路线卡"是产品决定，需拍板）。
- 路径线画到互动地图上。
- 把 `get_route` 接到 `plan_itinerary` 的行程里（"逐段车程"）。
- 行程落库 `trip_plan` / `trip_plan_item`（原设计阶段五-b）。
- `website_food_search`（原设计阶段四）。

---

## 附：复现命令

```bash
# ① 工具逻辑（不联网）
server-ai/.venv/Scripts/python.exe .workbuddy-ai/tmp/accept_m4_route.py

# ② 真实高德链路（联网，约 4 次配额）
server-ai/.venv/Scripts/python.exe .workbuddy-ai/tmp/probe_route_live.py

# ③ 端到端路由（联网 + 调模型，进程内 ASGI，不起端口）
server-ai/.venv/Scripts/python.exe .workbuddy-ai/tmp/probe_route_e2e.py

# ④ 前端呈现（联网 + 调模型 + 真浏览器；需前端 5173 与 AI 8000 都在跑）
bash .workbuddy-ai/tmp/cdp.sh .workbuddy-ai/tmp/probe_route_ui.mjs http://127.0.0.1:5173

# 隔离"高德 10002"用的对照（联网）
server-ai/.venv/Scripts/python.exe .workbuddy-ai/tmp/probe_amap_scope.py
```
