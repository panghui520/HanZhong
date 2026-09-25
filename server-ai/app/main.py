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
    state["service"] = (
        QaService(settings, state["store"], state["cache"]) if "store" in state else None
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
    return payload


@app.get("/ai/suggestions")
async def suggestions(request: Request, _: None = Depends(require_token)) -> list[str]:
    """推荐问题。知识库不可用时返回空列表，让前端隐藏推荐区而不是报错。"""
    service: QaService | None = request.app.state.ctx.get("service")
    if service is None:
        return []
    return service.suggestions()


@app.post("/ai/qa")
async def qa(request: Request, _: None = Depends(require_token)) -> StreamingResponse:
    body = await request.json()
    question = str(body.get("question", ""))
    service = _service(request)

    async def event_stream() -> AsyncIterator[str]:
        async for event in service.stream(question):
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
