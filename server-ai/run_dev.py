#!/usr/bin/env python
"""本地开发入口：用 uvicorn 把 AI 服务跑起来。

**为什么需要这个文件**

`app/main.py` 里只有一个模块级的 `app = FastAPI(...)`，**刻意没有**
`if __name__ == "__main__":` 入口块 —— 服务在部署时由 uvicorn 直接拉起，
不需要自启动逻辑。代价是：在 IDE 里右键 Run `main.py`，会「导入完立刻退出、
退出码 0、控制台什么都不打印」，看起来像"跑不起来"，实际是**根本没有入口**。

本文件补上这个入口，让 IDE 的绿色三角按钮可以直接用。

**用法**

    PyCharm 里点本文件左侧行号旁的绿色三角
    命令行：<venv>/Scripts/python.exe run_dev.py
    需要热重载时：<venv>/Scripts/python.exe run_dev.py --reload

host / port 取自配置（`.env` 的 `AI_HOST` / `AI_SERVER_PORT`，默认
127.0.0.1:8000），与下面这条命令完全等价：

    python -m uvicorn app.main:app --host 127.0.0.1 --port 8000

**注意**：本脚本只负责"起服务"。知识库是**离线产物**，不在这里构建 ——
没建库服务照样能起来，只是 `/ai/health` 会报"还没有构建知识库"。
首次或改了语料之后，先跑：

    <venv>/Scripts/python.exe scripts/build_kb.py --api
"""

from __future__ import annotations

import sys
from pathlib import Path

# 把脚本所在目录（server-ai/）加入 sys.path，保证 `app` 包一定可导入。
# 这样就不依赖 IDE 的 "Sources Root" 设置，也不依赖当前工作目录 ——
# 从仓库根跑 `python server-ai/run_dev.py` 同样成立。
sys.path.insert(0, str(Path(__file__).resolve().parent))

import uvicorn  # noqa: E402

from app.config import get_settings  # noqa: E402


def main() -> None:
    settings = get_settings()
    reload = "--reload" in sys.argv[1:]

    print(
        f"[dev] 启动 AI 服务 http://{settings.host}:{settings.port} "
        f"city={settings.city} reload={reload}",
        flush=True,
    )

    # 统一用「导入字符串」而不是直接传 app 对象：--reload 要求 uvicorn
    # 在子进程里自行导入，传对象会在重载时失效。两种模式走同一条路径，
    # 行为一致，少一个只在特定模式生效的分支。
    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=reload,
        # 只监听 app/ 目录：默认会盯整个工作目录，连 .venv 一起扫，
        # 白白吃掉 CPU（而且改一行注释就重启，反而干扰调试）
        reload_dirs=[str(Path(__file__).resolve().parent / "app")] if reload else None,
    )


if __name__ == "__main__":
    main()
