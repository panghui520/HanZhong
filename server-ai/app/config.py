"""服务配置。全部来自环境变量，不写死任何城市或模型。

变量名与仓库根目录的 .env.example 保持一致，Java 侧读的是同一份 INTERNAL_TOKEN。
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

# 仓库根目录：server-ai/app/config.py -> 上两级
REPO_ROOT = Path(__file__).resolve().parents[2]


def _load_dotenv() -> None:
    """把仓库根的 .env 读进环境变量。

    用 python-dotenv（chromadb 的依赖里已经有了），不额外引入配置框架。
    已经存在的环境变量优先，不会被 .env 覆盖。
    """
    env_file = REPO_ROOT / ".env"
    if not env_file.is_file():
        return
    try:
        from dotenv import load_dotenv
    except ImportError:  # pragma: no cover - 依赖缺失时退化为纯环境变量
        return
    load_dotenv(env_file, override=False)


def _bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() in ("1", "true", "yes", "on")


@dataclass(frozen=True)
class Settings:
    # ---- 城市数据包 ----
    city: str = field(default_factory=lambda: os.environ.get("CITY_PACK", "hanzhong"))
    citypack_dir: Path = field(
        default_factory=lambda: Path(os.environ.get("CITYPACK_DIR", str(REPO_ROOT / "citypack")))
    )

    # ---- 服务 ----
    host: str = field(default_factory=lambda: os.environ.get("AI_HOST", "127.0.0.1"))
    port: int = field(default_factory=lambda: int(os.environ.get("AI_SERVER_PORT", "8000")))

    # ---- 内部鉴权 ----
    internal_token: str = field(
        default_factory=lambda: os.environ.get("INTERNAL_TOKEN", "change_me_internal_token")
    )

    # ---- LLM ----
    llm_api_key: str = field(default_factory=lambda: os.environ.get("LLM_API_KEY", "").strip())
    llm_base_url: str = field(
        default_factory=lambda: os.environ.get("LLM_BASE_URL", "https://api.deepseek.com/v1").rstrip("/")
    )
    llm_model: str = field(default_factory=lambda: os.environ.get("LLM_MODEL", "deepseek-chat"))
    llm_embedding_model: str = field(
        default_factory=lambda: os.environ.get("LLM_EMBEDDING_MODEL", "").strip()
    )
    llm_timeout_s: float = field(default_factory=lambda: float(os.environ.get("LLM_TIMEOUT_S", "60")))

    # 演示模式：true 时强制走预生成缓存，不调外部 API
    demo_mode: bool = field(default_factory=lambda: _bool("DEMO_MODE", False))

    # ---- 向量库 ----
    vectorstore_dir: Path = field(
        default_factory=lambda: Path(
            os.environ.get("VECTORSTORE_DIR", str(REPO_ROOT / "data" / "vectorstore"))
        )
    )
    top_k: int = field(default_factory=lambda: int(os.environ.get("RAG_TOP_K", "5")))

    # ---- 缓存 ----
    cache_file: Path = field(
        default_factory=lambda: Path(
            os.environ.get("QA_CACHE_FILE", str(Path(__file__).resolve().parents[1] / "cache" / "qa_demo.json"))
        )
    )

    @property
    def city_dir(self) -> Path:
        return self.citypack_dir / self.city

    @property
    def docs_dir(self) -> Path:
        return self.city_dir / "docs"

    @property
    def llm_enabled(self) -> bool:
        """有 key 且不在演示模式时，才真正调外部模型"""
        return bool(self.llm_api_key) and not self.demo_mode


def get_settings() -> Settings:
    _load_dotenv()
    return Settings()
