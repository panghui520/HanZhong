# 汉游智脑 HanYou Brain

**面向"智慧文旅 + 乡村振兴"的文旅消费链智能延伸与管控平台**

> 参赛：第十二届中国研究生智慧城市技术与创意设计大赛 · 定向赛道 (5) 智慧文旅与乡村振兴
> 作品提交截止：2026-10-25 12:00

## 一句话定位

把游客的一次到访，变成一条持续的乡村消费链。

## 核心闭环

```
游客需求 → AI 行程规划 → 多业态资源协同 → 承载力驱动乡村分流
  → 乡村体验 → 特色农产品消费 → 离境后线上复购
  → 运营数据沉淀 → AI 分析 → 管理决策 → 优化资源匹配（回到起点）
```

## 三条设计红线

1. **核心不是电商** —— 农产品必须挂靠体验或产地，不设独立商城页面。
2. **核心不是聊天机器人** —— AI 参与规划、分流、推荐、运营归因、决策。
3. **技术不是创新点** —— LangChain / LangGraph / RAG / Multi-Agent 只是实现手段。

完整方案见 [`docs/汉游智脑V2.0-参赛落地方案.md`](docs/汉游智脑V2.0-参赛落地方案.md)。

## 模块进度

按纵切模块推进，上一模块验收通过才启动下一个。验收记录见 `docs/验收记录-M*.md`。

| 模块 | 状态 |
|---|---|
| M1 统一资源（POI / 区县 / 知识） | ✅ 已验收 |
| M2 乡村体验与农产品 | ✅ 已验收 |
| M3 文旅知识问答（FastAPI + RAG） | 🟡 功能可用，验收记录待补 |
| M4 行程规划 | ⬜ 未开始 |
| M5 承载力与分流 | ⬜ 未开始 |
| M6 消费与离境复购 | 🟡 离境复购链已验收（到访消费链未做） |
| M7 运营分析 | ⬜ 未开始 |
| M8 认证与权限 | ✅ 已验收 |
| M9 媒体管理 | ✅ 已验收 |

关于 `/goods` 页面：**它不是商城**。农产品按**产地**而不是品类陈列，每件都显示它挂靠的
那次乡村体验或那个 POI，并且可以点回详情。没有店铺、没有商家、没有搜索、没有商品分类
导航、没有支付、没有物流。它是"一次到访之后还能带走点什么"的延续，
不是一条独立的零售货架。

## 模块结构

| 目录 | 内容 | 技术栈 |
|---|---|---|
| `citypack/` | 城市数据包（汉中为默认示范城市，可整包替换） | JSONL + Markdown |
| `server-java/` | 业务后端：数据、权限、规则引擎、工单闭环 | Spring Boot 3.2 / Java 17 / MyBatis-Plus |
| `server-ai/` | AI 服务：RAG、多智能体、运营归因 | FastAPI / LangChain / LangGraph / Chroma |
| `web/` | 游客端 + 管理端 | Vue 3 / TypeScript / Element Plus / ECharts |
| `scripts/` | 数据生成、导入、评测、压测 | Python |
| `deploy/` | 一键部署 | Docker Compose |
| `docs/` | 比赛文档 + 设计文档 + 评测报告 | Markdown |

## 数据合规

- POI 与知识文本来自公开渠道（汉中市文旅局官网等），均标注 `source_url`。
- 客流、订单、评价为**基于规则的仿真数据**，字段标记 `synthetic=true`，
  并在 `city_profile.disclaimer` 与项目说明书中声明"非真实统计"。
- 禁止抓取商业平台（携程 / 马蜂窝 / 美团）数据。

## Commit 规范（分模块提交）

格式：`<type>(<scope>): <subject>`，subject 用英文，正文可用中文。

| type | 用途 |
|---|---|
| `feat` | 新功能 |
| `fix` | 修 bug |
| `refactor` | 重构（不改行为） |
| `docs` | 文档 |
| `chore` | 构建、依赖、脚手架 |

| scope | 模块 |
|---|---|
| `web` | 前端 |
| `java` | Spring Boot 后端 |
| `ai` | Python AI 服务 |
| `citypack` | 城市数据包 |
| `scripts` | 脚本 |
| `deploy` | 部署 |
| `schema` | 数据库表结构 |

示例：
```
feat(java): add risk rule engine and work order flow
feat(ai): implement rural diversion node in LangGraph
docs: add V2.0 competition plan
```
