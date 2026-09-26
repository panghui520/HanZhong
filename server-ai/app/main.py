"""汉游智脑 AI 服务入口。

只监听 127.0.0.1，只接受带 X-Internal-Token 的请求——它不直接对公网提供，
所有外部流量都从 Java 业务层代理进来，鉴权与限流都在那一层。

启动时**不**自动建知识库：建库要读全量语料、算向量，放在启动路径上会让
服务启动时间随语料增长，而语料变化远没有服务重启频繁。
知识库没建好时服务照常启动，/ai/health 会明确说"还没有构建知识库"，
并给出构建命令——比启动失败更容易排查。
"""

from __future__ import annotations

import json
import logging
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import StreamingResponse

from .agent import AgentService
from .amap import create_client
from .config import Settings, get_settings
from .corpus import build_chunks
from .embed import create_embedder
from .lexical import warm_up
from .llm import AnswerCache
from .qa import QaService
from .store import IDF_FILE, KbStore

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logger = logging.getLogger("hanyou.ai")

BUILD_HINT = "python scripts/build_kb.py"


def _quiet_jieba() -> None:
    """把 jieba 的日志压到 WARNING。**必须在 warm_up() 之前调用。**

    为什么不能简单地 `setLevel`：jieba 是**延迟导入**的（见 lexical._segment 里
    函数内的 `import jieba`），而它的 __init__ 会执行
    `default_logger.setLevel(logging.DEBUG)` 并挂一个自带 handler。
    所以在导入前设级别会被它覆盖，在 warm_up() 之后设又太晚
    —— 建词典的噪声正是在 warm_up() 里发出来的。

    正确顺序：先 import 触发它的 __init__，再把级别压回去，最后才建词典。
    """
    import jieba  # noqa: F401  # 先导入，让 jieba 完成它自己的 logger 初始化

    jl = logging.getLogger("jieba")
    jl.setLevel(logging.WARNING)
    # 去掉它自带的 stderr handler：那个 handler 没有 formatter，
    # 消息会以裸文本再打一遍，日志里就变成每行出现两次。
    jl.handlers.clear()
    jl.propagate = True


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings = get_settings()
    state: dict[str, Any] = {"settings": settings}

    # 先压 jieba 日志，再建词典。顺序反了噪声就压不住（原因见 _quiet_jieba 的注释）。
    _quiet_jieba()
    # jieba 词典第一次用才构建（约 0.6 秒）。放在启动路径上，
    # 免得第一个提问的用户替所有人付这笔钱。
    warm_up()

    # 知识库是否可用取决于两件事：向量化实现能否就位（离线实现要有 IDF 文件），
    # 以及集合里有没有数据。任一不满足都只是"不可用"，不是启动失败。
    try:
        chunks = build_chunks(settings.city_dir)
        embedder = create_embedder(settings, settings.vectorstore_dir / IDF_FILE)
        store = KbStore.open(settings.vectorstore_dir, embedder, chunks)
        state["store"] = store
        state["stale"] = store.is_stale(chunks)
        logger.info(
            "[AI] 知识库已加载 city=%s chunks=%d embedder=%s 词表=%d 过期=%s",
            settings.city,
            store.count(),
            embedder.signature,
            store.lexical.coverage()["terms"],
            state["stale"],
        )
    except Exception as exc:  # noqa: BLE001 - 知识库坏了也要让服务起来，由 health 报出来
        state["load_error"] = f"{exc}（如未构建请运行：{BUILD_HINT}）"
        logger.warning("[AI] 知识库不可用：%s", exc)

    state["cache"] = AnswerCache(settings.cache_file)
    # 高德客户端不依赖知识库，所以**无条件创建**：知识库没建好时它仍然可用，
    # /ai/health 里也能如实报告"key 配没配"，而不是整块信息缺失。
    state["amap"] = create_client(settings)
    state["service"] = (
        QaService(settings, state["store"], state["cache"]) if "store" in state else None
    )
    # Agent 要同时用到知识库与高德，所以只在知识库可用时创建
    state["agent"] = (
        AgentService(settings, state["store"], state["amap"]) if "store" in state else None
    )

    logger.info(
        "[AI] 工具就绪：高德=%s 模型=%s",
        "已配置 AMAP_KEY" if state["amap"].enabled else "未配置 AMAP_KEY",
        settings.llm_model if settings.llm_enabled else "未配置（离线模式）",
    )

    app.state.ctx = state
    yield


app = FastAPI(title="汉游智脑 AI 服务", version="0.1.0", lifespan=lifespan)


def require_token(
    x_internal_token: str | None = Header(default=None, alias="X-Internal-Token"),
) -> None:
    """内部鉴权。Java 侧与这里读同一个 INTERNAL_TOKEN。"""
    expected = get_settings().internal_token
    if not expected:
        raise HTTPException(status_code=500, detail="服务未配置 INTERNAL_TOKEN")
    if x_internal_token != expected:
        raise HTTPException(status_code=401, detail="内部令牌校验失败")


def _service(request: Request) -> QaService:
    service = request.app.state.ctx.get("service")
    if service is None:
        detail = request.app.state.ctx.get("load_error", "知识库不可用")
        raise HTTPException(status_code=503, detail=detail)
    return service


def _agent_service(request: Request) -> AgentService:
    service = request.app.state.ctx.get("agent")
    if service is None:
        detail = request.app.state.ctx.get("load_error", "知识库不可用")
        raise HTTPException(status_code=503, detail=detail)
    return service


@app.get("/ai/health")
async def health(request: Request, _: None = Depends(require_token)) -> dict[str, Any]:
    ctx = request.app.state.ctx
    service: QaService | None = ctx.get("service")
    if service is None:
        return {"ok": False, "city": ctx["settings"].city, "error": ctx.get("load_error", "")}

    payload = service.status()
    payload["stale"] = ctx.get("stale", False)
    if payload["stale"]:
        payload["stale_hint"] = f"语料已变化，建议重建知识库：{BUILD_HINT}"

    # M4 工具能力。**如实报告**：key 没配就说没配，不要装作工具可用 ——
    # 前端据此决定要不要把"找酒店"这类入口露出来。
    amap = ctx.get("amap")
    payload["amap"] = {"configured": bool(amap and amap.enabled)}
    agent = ctx.get("agent")
    if agent is not None:
        payload["agent"] = agent.status()

    return payload


@app.get("/ai/suggestions")
async def suggestions(request: Request, _: None = Depends(require_token)) -> list[str]:
    """推荐问题。知识库不可用时返回空列表，让前端隐藏推荐区而不是报错。"""
    service: QaService | None = request.app.state.ctx.get("service")
    if service is None:
        return []
    return service.suggestions()


def _sse(events) -> StreamingResponse:
    """把一个事件流包成 SSE 响应。

    两个接口（`/ai/qa` 与 `/ai/agent`）共用这一处，是为了保证**帧格式完全一致**：
    前端只实现了一套 `\\n\\n` 分帧逻辑，两边格式一旦不同，就会表现为
    "某个页面偶尔丢帧"，那种 bug 极难定位。
    """

    async def event_stream() -> AsyncIterator[str]:
        async for event in events:
            # ensure_ascii=False：中文原样输出，抓包与日志里可读。
            # json.dumps 会把字符串里的换行转义成 \n 两个字符，
            # 所以不会出现"内容里的换行被前端当成帧边界"这种事故。
            yield f"data: {json.dumps(event, ensure_ascii=False)}\n\n"

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            # 反向代理若做缓冲会把流式效果吃掉，显式关掉
            "X-Accel-Buffering": "no",
        },
    )


async def _body_of(request: Request) -> dict[str, Any]:
    """读请求体，**空体与非 JSON 都当成空字典**。

    不这么做的话，`await request.json()` 在空体上抛 `JSONDecodeError`，
    FastAPI 把它变成 500 —— 而 500 是"服务坏了"的意思，实际发生的事是
    "你没发请求体"。两者对排查的人是完全不同的指引。

    两个流式端点都用它，是为了让它们的容错行为一致：只修一个的话，
    同一个客户端在 `/ai/qa` 上拿到 500、在 `/ai/agent` 上拿到
    "问题不能为空"，会以为是自己用错了接口。
    """
    try:
        body = await request.json()
    except Exception:  # noqa: BLE001 - 任何解析失败都按"没有请求体"处理
        return {}
    return body if isinstance(body, dict) else {}


@app.post("/ai/qa")
async def qa(request: Request, _: None = Depends(require_token)) -> StreamingResponse:
    body = await _body_of(request)
    question = str(body.get("question", ""))
    return _sse(_service(request).stream(question))


@app.post("/ai/agent")
async def agent(request: Request, _: None = Depends(require_token)) -> StreamingResponse:
    """M4 工具调用问答。

    与 `/ai/qa` 是**两条独立的链路**，刻意不合并：
    `/ai/qa` 是 M3 已验收的知识库问答（无状态、不调外部数据源、有预生成缓存），
    而这里是"模型自己决定调哪个工具"的 Agent。合并会让 M3 的
    `route`/`mode`/`sources` 契约多出一批只属于 Agent 的事件类型，
    已经写好的 eval 与前端分支都得跟着改 —— 而它们本来不需要知道工具的存在。

    事件协议（每个 `data:` 是一行 JSON）：

        {"type":"meta",  "mode":"llm", "generator":"deepseek-chat",
                         "city":"汉中", "amap":true, "tools":[...]}
        {"type":"tool",  "name":"search_nearby", "status":"running", "label":"..."}
        {"type":"tool",  "name":"search_nearby", "status":"done", "count":12,
                         "elapsed_ms":340, "error":""}
        {"type":"cards", "kind":"hotel", "items":[...]}
        {"type":"delta", "text":"..."}
        {"type":"done",  "mode":"llm", "tool":"search_nearby", "elapsed_ms":3200}
        {"type":"error", "message":"..."}

    `cards` 里的字段**全部来自高德返回**，模型不参与生成 ——
    这是"不让模型编造真实旅游数据"这条红线的实现方式。

    第二阶段起请求体多一个可选的 `context`（本次行程上下文）：

        {"question": "这附近有什么好吃的",
         "context": {"destination": "汉中",
                     "selected_hotel": {"poi_id": "...", "name": "...",
                                        "address": "...",
                                        "location": "107.020000,33.070000",
                                        "source": "amap"},
                     "hotel_booking_status": "not_booked"}}

    它由 Java 侧从 `trip_context` 读出来，**前端传不了** —— 否则任何人都能
    伪造"我住在某某酒店"去影响检索结果。缺省或为空时，整条链路与第一阶段
    完全一致（未登录用户走的就是这条路）。
    """
    body = await _body_of(request)
    question = str(body.get("question", ""))
    context = body.get("context")
    return _sse(
        _agent_service(request).stream(
            question, context if isinstance(context, dict) else None
        )
    )
