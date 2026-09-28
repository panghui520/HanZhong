# M6 消费与离境复购 · 第三段：到访消费 `TRIP` 链 + 足迹 · 验收记录

> 验收日期：2026-09-27 · 结论：**通过（后端 34/34 + 前端 10/10）**
> 前两段见 `docs/验收记录-M6.md`（两态订单 + 探索页改版，**已被取代**）与
> `docs/验收记录-M6-订单流转.md`（七态流转）。本段是 M6 模块计划里的最后一段：
> "把到访足迹落库 + 在下单时按窗口归到 `TRIP`/`REPURCHASE`"。
>
> 模块目标（`docs/模块划分与依赖.md` M6 行）：**让"一次到访"在系统里是一个查得出来的事实，并据此决定订单是"到访消费"还是"离境复购"。**

## 一、模块职责边界

**做**：
- `trip_checkin` 表落地"我今天到访过这里"，含幂等（同一天同一目标）、
  快照（`poi_name` / `experience_name`）、客户端不可伪造 `source`；
- POI 详情页"我到过这里"按钮 + 游客端"我的足迹"页（按到访时间倒序）；
- 下单时按"近 3 天内有匹配足迹"判定 `orders.channel=TRIP`，否则 `REPURCHASE`；
- 足迹方向：仅体验 / 仅产地 任一命中都判 `TRIP`（**产品口径**——一次到访变持续消费链）。

**不做**：
- 走 AI 给的推荐 —— 渠道判定是纯 SQL + 比较日期窗口，**没有一步过模型**；
- 删除足迹 —— 足迹是发生过的事实，且 `orders.channel` 依赖它；
- 在足迹页下单 —— 那是「乡村好物」的事，不在这里开口子；
- `source=DIVERSION` 的服务端写入方 —— 设计稿 §15 链路 ③ 的"分流贡献量"链路未建（详见本文「五」与 `docs/汉游智脑V2.0-参赛落地方案.md` 行 346「落地差异」表第 3 行）。

**三条设计红线没有被触碰**：
1. **不是电商** —— POI 详情页加按钮不构成"商品入口"；订单渠道归类只是元数据，不创造购买行为；
2. **不是聊天机器人** —— 渠道判定全程 SQL+比较，**没有一步过模型**；
3. **不改 M1–M5 已验收的接口** —— 新增的是 `GET/POST /api/trips/current/checkins`，其它文件只改了一行（`OrderService` 加 `TripCheckinMapper` 字段与判定方法）。

**一条不可违反的边界（与 M8 同源）**：`trip_checkin` **不带 `city_code`** —— 它是运行期业务事实，重启全量重灌会把游客今天的足迹清光，**这是 D1 验收专门守的一条**。

## 二、交付物

| 层 | 文件 |
|---|---|
| 数据库 | `db/V10__m6_trip_chain.sql`（`trip_checkin` 表 + `chk_checkin_traceable` CHECK 约束 + 列数/约束自检 SQL） |
| 后端 entity / mapper | `entity/TripCheckin.java`、`mapper/TripCheckinMapper.java` |
| 后端 vo | `vo/TripCheckinVO.java`（含 `source_label` 中文映射） |
| 后端 service | `service/TripCheckinService.java`（listMine + checkin + 幂等判定） |
| 后端 controller | `controller/TripController.java`（**新增** `checkins` 与 `checkin` 两个端点） |
| 既有改动 | `service/OrderService.java`（**新增** `determineChannel` + `TripCheckinMapper` 字段，**常量** `TRIP_WINDOW_DAYS = 3`）<br>`controller/OrderController.java`（不变 —— 渠道归类由 OrderService 内部调用）<br>`common/ErrorCode.java`（新增 7004 / 7005）<br>`config/SecurityConfig.java`（`/api/trips/current/checkins` 需登录） |
| 前端接口层 | `web/src/api/trips.ts`（新增）、`web/src/types/index.ts`（新增 `TripCheckin` 类型） |
| 前端页面 | `web/src/views/portal/Footprints.vue`（**新**，"我的足迹"页）<br>`web/src/views/portal/PoiDetail.vue`（新增"我到过这里"按钮 + 本地态恢复）<br>`web/src/views/admin/Orders.vue`（新增"渠道"列：TRIP=到访消费 / REPURCHASE=离境复购）<br>`web/src/router/index.ts`（`/footprints` 路由） |
| 验收脚本 | `.workbuddy-ai/tmp/accept_m6_trip.py`（后端接口，34 条断言，A 权限 / B 打卡 / C 渠道 / D 数据层）<br>`.workbuddy-ai/tmp/probe_m6_visual.mjs`（浏览器端，10 条断言，登录→打卡→看足迹→回 POI） |
| 文档 | 本文件；`docs/模块划分与依赖.md` M6 行已改正（"待开发"→ 已验收，附 `trip_checkin` 端点 + 三段状态）；`docs/汉游智脑V2.0-参赛落地方案.md` §15 落地差异表第 3 行已改正（"trip_checkin 未建"→ "部分实现"，附未做项与理由） |

## 三、接口定义

| 端点 | 方法 | 鉴权 | 行为 |
|---|---|---|---|
| `/api/trips/current/checkins` | `GET` | 登录 | 拉当前用户的足迹，按 `checkin_at DESC`；`limit` 默认 50、上限 200、非法回落默认 |
| `/api/trips/current/checkins` | `POST` | 登录 | 打卡；body 至少 `poi_id` 或 `experience_id` 之一；幂等（同一天同目标返回上一条）；**客户端传的 `source` 被忽略**，写库恒为 `REAL` |

**错误码**（`common/ErrorCode.java`）：
- `7004` 空体或两个目标都为空（`note` 不算打卡）
- `7005` 资源点 / 体验不存在

## 四、核心常量

`server-java/src/main/java/com/hanyou/brain/service/OrderService.java`

```java
/** "还在这次到访里"的窗口：足迹距下单不超过 3 天，算 TRIP。*/
private static final int TRIP_WINDOW_DAYS = 3;
```

**为什么是 3 天**（写在常量上方注释）：汉中乡村体验的典型行程是 2–3 天，3 天覆盖"一次到访期间 + 返程当天"，再往后就是离开之后的复购了。

## 五、已知缺口与边界

| 项 | 现状 | 何时做 |
|---|---|---|
| 分流公告 → `trip_checkin.source=DIVERSION` 写入方 | **未做**（设计稿 §15 链路 ③ 未实现段） | 待评估。把公告作用对象记为到访会让 `DIVERSION` 与 `REAL` 互相稀释 —— `DiversionController` 与 `TripController` 之间**故意没有回调** |
| 运营端"分流贡献量"数字面板 | **未做**（同上） | 待评估 |
| 资源点级口碑评分 | ~~**未做**~~ **✅ 已完成（2026-09-28，M10 续）** —— 新建 `poi_comment` 表提供，不是聚合 `order_review` | M6 评价域 → **实际由 M10 续落地** |
| 游客端"来过的人怎么说" | ~~**未做**~~ **✅ 已完成（2026-09-28，M10 续）** —— `mock/reviews.ts` 已删，改走 `GET /api/pois/{id}/comments` | M6 评价域 → **实际由 M10 续落地** |

## 六、口径审计（项目回核）

按 `post-module-doc-audit` 七条搜索过一遍（详见 `docs/.workbuddy-ai/...` 笔记），**两处过期**已改正：

1. `docs/模块划分与依赖.md` M6 行 —— "到访消费 `TRIP` 链与足迹待开发" 已改为"已验收"；"`visitor` `trip_checkin`（待开发）" 已改为"`visitor` 不另建表，复用 M8 `app_user`"；
2. `docs/汉游智脑V2.0-参赛落地方案.md` §15 落地差异表第 3 行 —— "未实现：`trip_checkin` 未建" 已改为"部分实现"，附未做项与故意不做的理由。

其余口径一致（窗口 3 天、`source` 服务端恒写 REAL、幂等同日同目标、CHECK 约束 `chk_checkin_traceable`）—— 没找到过期。

## 七、提交

本批改动（含本文件）**未提交**，等用户复核后一并提交（"改 → 看 → 可以才提交"）。

---

> 一句话答辩词：**到访足迹 = 订单渠道归类的事实基础**。近 3 天有匹配足迹 → `TRIP`（到访消费）；否则 `REPURCHASE`（离境复购）。判定走 SQL+日期比较，**没有一步过模型**。