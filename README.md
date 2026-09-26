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
| M3 文旅知识问答（FastAPI + RAG） | ✅ 已验收（含真实模型联调：DeepSeek 对话 + 硅基流动 BAAI/bge-m3 嵌入）。架构是**知识库增强的助手**而非知识库回答器：DeepSeek 负责作答，知识库提供依据；与汉中无关的问题直接由模型回答，本地事实则受"不得编造"约束 |
| M4 行程规划 | ⬜ 未开始 |
| M5 承载力与分流 | ⬜ 未开始 |
| M6 消费与离境复购 | ✅ 离境复购链已验收（两轮：两态闭环 + 七态订单流转）；到访消费链未做 |
| M7 运营分析 | ⬜ 未开始 |
| M8 认证与权限 | ✅ 已验收 |
| M9 媒体管理 | ✅ 已验收 |

关于 `/goods` 页面：**它不是商城**。农产品按**产地**而不是品类陈列，每件都显示它挂靠的
那次乡村体验或那个 POI，并且可以点回详情。没有店铺、没有商家、没有搜索、没有商品分类
导航。它是"一次到访之后还能带走点什么"的延续，不是一条独立的零售货架。

订单流程（2026-09-25 细化）走**七态状态机**：

```
待付款 ──付款──▶ 待发货 ──运营填物流发货──▶ 已发货 ──确认收货──▶ 已完成 ──▶ 评价
  │                  │                        │
  └─30 分钟超时─▶ 已取消   └────── 申请退款 ──────┘
                              ▼
                        退款中 ──运营同意──▶ 已退款（终态）
                              └─运营拒绝──▶ 退回申请前的状态
```

**支付与物流都是演示级**：不接真实支付通道（"付款"就是点一下按钮）、物流由运营手工
填写、系统不查快递接口。详见 [`docs/验收记录-M6-订单流转.md`](docs/验收记录-M6-订单流转.md)。

## 数据库脚本

`db/V1..V6` 按顺序执行，**可重复执行、不丢数据**（全部用 `CREATE TABLE IF NOT EXISTS`
与幂等插入）。结构演进只能新开 V 文件写 `ALTER`，改老文件不会生效 —— 原因写在每个
文件顶部。需要清掉运行期测试数据时用 `db/reset-runtime-data.sql`（会保留账号、配图、轮播）。

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

## 本地启动

```bash
# 1. 配置凭证（模板见 .env.example）
cp .env.example .env
#   然后在 .env 里填两个 key（两者不通用，是两家厂商）：
#     LLM_API_KEY            对话模型（DeepSeek）
#     LLM_EMBEDDING_API_KEY  嵌入模型（硅基流动）

# 2. 构建知识库（M3 用）。换嵌入模型或改别名表后必须重跑
python scripts/build_kb.py --api

# 3. 起 AI 服务（无状态、不连业务库）
cd server-ai && .venv/Scripts/python.exe run_dev.py        # 等价于下面这条
# cd server-ai && .venv/Scripts/python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
# 需要改代码自动重启时加 --reload（只盯 app/ 目录，不扫 .venv）
#
# 为什么有个 run_dev.py：app/main.py 里没有 `if __name__ == "__main__"` 入口块，
# 在 IDE 里右键 Run main.py 会「导入完立刻退出、退出码 0、控制台无输出」，
# 看起来像跑不起来。run_dev.py 补上入口，让 IDE 的绿色三角可以直接点。
# PyCharm 用户：直接点 run_dev.py 左侧行号旁的绿色三角即可。
# （运行配置「AI 服务 (uvicorn :8000)」已生成在本地 .idea/ 里，会出现在
#   右上角下拉框；但 .idea/ 不进版本库，换机器要重新生成一份。）

# 4. 起业务后端与前端
cd server-java && mvn spring-boot:run      # 8080
cd web && npm run dev                      # 5173
```

⚠️ **`.env` 只被 Python 读**（Java 的 `application.yml` 读系统环境变量）。
在里面改 `INTERNAL_TOKEN` 会让两边不一致，`/api/ai/**` 全部返回 `3001`。

⚠️ **用 `server-ai/.venv/` 里的 Python**。托管/系统 Python 可能没装 `python-dotenv`，
表现为"填了 `.env` 但配置全是默认值、零报错"（已加一次性警告堵住这个坑）。

检索效果自检：`python scripts/eval_retrieval.py`
（26 条正例 + 10 条反例 + 5 条推荐问题，**任一指标不达标退出码 1**）。

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
