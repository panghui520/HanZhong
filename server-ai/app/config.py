"""服务配置。全部来自环境变量，不写死任何城市或模型。

变量名与仓库根目录的 .env.example 保持一致，Java 侧读的是同一份 INTERNAL_TOKEN。
"""

from __future__ import annotations

import json
import os
import sys
from dataclasses import dataclass, field
from pathlib import Path

# 仓库根目录：server-ai/app/config.py -> 上两级
REPO_ROOT = Path(__file__).resolve().parents[2]

_DOTENV_WARNED = False


def _load_dotenv() -> None:
    """把仓库根的 .env 读进环境变量。

    用 python-dotenv（chromadb 的依赖里已经有了），不额外引入配置框架。
    **已经存在的环境变量优先，不会被 .env 覆盖**（override=False）。
    """
    global _DOTENV_WARNED

    env_file = REPO_ROOT / ".env"
    if not env_file.is_file():
        return
    try:
        from dotenv import load_dotenv
    except ImportError:
        # 依赖缺失时退化为"只读系统环境变量"。但这必须**说出来**：
        # 用一个没装 python-dotenv 的解释器跑（比如系统 Python 而不是
        # server-ai/.venv），表现是"填了 .env 但所有配置都是默认值"，
        # 而没有任何报错——排查起来极费时间。所以这里喊一次。
        # 只喊一次：get_settings() 每个请求都会被调用，否则日志会被刷爆。
        if not _DOTENV_WARNED:
            _DOTENV_WARNED = True
            print(
                f"[config] 检测到 {env_file}，但当前解释器没有 python-dotenv，"
                f".env 不会被读取（全部走默认值）。\n"
                f"         请用项目虚拟环境运行：server-ai/.venv/Scripts/python.exe\n"
                f"         当前解释器：{sys.executable}",
                file=sys.stderr,
            )
        return
    load_dotenv(env_file, override=False)


def _bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() in ("1", "true", "yes", "on")


def _path(name: str, default: Path) -> Path:
    """读路径型环境变量。**相对路径按仓库根解析，不按当前工作目录。**

    这条很要紧：.env.example 里写的是 `data/vectorstore` 这种相对路径，
    而服务可能从仓库根启动、脚本也可能从 server-ai/ 启动。按 CWD 解析的话，
    从 server-ai/ 启动会去找 server-ai/data/vectorstore，然后报"还没有构建知识库"——
    而库其实好好地躺在仓库根。这种错最难查，因为提示指向的方向是错的。
    """
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    path = Path(raw.strip())
    return path if path.is_absolute() else (REPO_ROOT / path)


@dataclass(frozen=True)
class Settings:
    # ---- 城市数据包 ----
    city: str = field(default_factory=lambda: os.environ.get("CITY_PACK", "hanzhong"))
    citypack_dir: Path = field(
        default_factory=lambda: _path("CITYPACK_DIR", REPO_ROOT / "citypack")
    )

    # ---- 服务 ----
    host: str = field(default_factory=lambda: os.environ.get("AI_HOST", "127.0.0.1"))
    port: int = field(default_factory=lambda: int(os.environ.get("AI_SERVER_PORT", "8000")))

    # ---- 内部鉴权 ----
    internal_token: str = field(
        default_factory=lambda: os.environ.get("INTERNAL_TOKEN", "change_me_internal_token")
    )

    # ---- LLM（对话生成）----
    llm_api_key: str = field(default_factory=lambda: os.environ.get("LLM_API_KEY", "").strip())
    llm_base_url: str = field(
        default_factory=lambda: os.environ.get("LLM_BASE_URL", "https://api.deepseek.com/v1").rstrip("/")
    )
    llm_model: str = field(default_factory=lambda: os.environ.get("LLM_MODEL", "deepseek-chat"))

    # ---- Embedding（向量化）----
    # 与对话模型**分开配置**，这不是洁癖：DeepSeek 官方只提供 chat/completions，
    # 没有 /embeddings 端点。若沿用 llm_base_url + llm_api_key，嵌入请求会被打到
    # https://api.deepseek.com/v1/embeddings 上返回 404。
    # 本项目对话用 DeepSeek、嵌入用硅基流动的 BAAI/bge-m3，所以两套凭证各自独立。
    llm_embedding_model: str = field(
        default_factory=lambda: os.environ.get("LLM_EMBEDDING_MODEL", "").strip()
    )
    llm_embedding_api_key: str = field(
        default_factory=lambda: os.environ.get("LLM_EMBEDDING_API_KEY", "").strip()
    )
    llm_embedding_base_url: str = field(
        default_factory=lambda: os.environ.get(
            "LLM_EMBEDDING_BASE_URL", "https://api.siliconflow.cn/v1"
        ).rstrip("/")
    )
    llm_timeout_s: float = field(default_factory=lambda: float(os.environ.get("LLM_TIMEOUT_S", "60")))

    # 演示模式：true 时强制走预生成缓存，不调外部 API
    demo_mode: bool = field(default_factory=lambda: _bool("DEMO_MODE", False))

    # ---- 向量库 ----
    vectorstore_dir: Path = field(
        default_factory=lambda: _path("VECTORSTORE_DIR", REPO_ROOT / "data" / "vectorstore")
    )
    top_k: int = field(default_factory=lambda: int(os.environ.get("RAG_TOP_K", "5")))

    # ---- 缓存 ----
    cache_file: Path = field(
        default_factory=lambda: _path(
            "QA_CACHE_FILE", Path(__file__).resolve().parents[1] / "cache" / "qa_demo.json"
        )
    )

    @property
    def city_dir(self) -> Path:
        return self.citypack_dir / self.city

    @property
    def docs_dir(self) -> Path:
        return self.city_dir / "docs"

    @property
    def city_name(self) -> str:
        """城市中文名，取自 citypack 的 meta.json，取不到就退回编码。

        只用于**面向用户的文案**。`city` 是编码（hanzhong），给日志和接口字段用；
        直接把它拼进给用户看的句子里会输出"当前知识库覆盖 hanzhong 的…"，
        用户看不懂，而且一眼就像没做完。
        """
        meta_file = self.city_dir / "meta.json"
        if meta_file.is_file():
            try:
                name = json.loads(meta_file.read_text(encoding="utf-8")).get("name")
                if name:
                    return str(name)
            except (json.JSONDecodeError, OSError):
                # meta.json 坏掉不该让问答挂掉，退回编码即可
                pass
        return self.city

    @property
    def llm_enabled(self) -> bool:
        """有 key 且不在演示模式时，才真正调外部模型"""
        return bool(self.llm_api_key) and not self.demo_mode

    @property
    def embedding_enabled(self) -> bool:
        """配了模型名**且**配了嵌入专用 key 才调厂商 embedding 接口。

        刻意不回落 LLM_API_KEY：两家厂商的 key 不通用，静默回落只会把 401
        藏起来，让人以为是模型名写错了。缺哪一项就明确缺哪一项。
        """
        return bool(self.llm_embedding_model) and bool(self.llm_embedding_api_key)


def get_settings() -> Settings:
    _load_dotenv()
    return Settings()
