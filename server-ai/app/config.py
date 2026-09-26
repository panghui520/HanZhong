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


def _str(name: str, default: str = "") -> str:
    """读字符串型环境变量。**键存在但值为空，也按"没配"处理。**

    为什么必须有这一条：`os.environ.get("X", default)` 在 .env 里写了 `X=`
    时返回的是**空串**而不是 default —— 因为键确实存在。表现是
    "我明明留空了，它却把 base_url 设成了空串"，请求于是打到
    `/geocode/geo` 这种没有 host 的地址上，报错指向的方向完全是错的。

    而在 .env 里留空是**很自然的写法**（意思就是"用默认值"），
    所以这里统一按没配处理。`_bool` / `_path` 早就是这么做的，
    字符串与数字字段以前漏了。
    """
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip()


def _num(name: str, default, cast):
    """读数字型环境变量。留空用默认值；**写了非数字也不崩**。

    不崩这一条是刻意的：这些字段在 dataclass 的 default_factory 里求值，
    抛异常会让**整个服务在 import 期就起不来**，而原因只在一行回溯里。
    退一步用默认值 + 喊一声，至少服务还能起来、日志能看见。
    """
    raw = _str(name, "")
    if not raw:
        return default
    try:
        return cast(raw)
    except (TypeError, ValueError):
        print(f"[config] {name}={raw!r} 不是合法数字，改用默认值 {default}", file=sys.stderr)
        return default


def _int(name: str, default: int) -> int:
    return _num(name, default, int)


def _float(name: str, default: float) -> float:
    return _num(name, default, float)


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
    city: str = field(default_factory=lambda: _str("CITY_PACK", "hanzhong"))
    citypack_dir: Path = field(
        default_factory=lambda: _path("CITYPACK_DIR", REPO_ROOT / "citypack")
    )

    # ---- 服务 ----
    host: str = field(default_factory=lambda: _str("AI_HOST", "127.0.0.1"))
    port: int = field(default_factory=lambda: _int("AI_SERVER_PORT", 8000))

    # ---- 内部鉴权 ----
    internal_token: str = field(
        default_factory=lambda: _str("INTERNAL_TOKEN", "change_me_internal_token")
    )

    # ---- LLM（对话生成）----
    llm_api_key: str = field(default_factory=lambda: _str("LLM_API_KEY", ""))
    llm_base_url: str = field(
        default_factory=lambda: _str("LLM_BASE_URL", "https://api.deepseek.com/v1").rstrip("/")
    )
    llm_model: str = field(default_factory=lambda: _str("LLM_MODEL", "deepseek-chat"))

    # ---- Embedding（向量化）----
    # 与对话模型**分开配置**，这不是洁癖：DeepSeek 官方只提供 chat/completions，
    # 没有 /embeddings 端点。若沿用 llm_base_url + llm_api_key，嵌入请求会被打到
    # https://api.deepseek.com/v1/embeddings 上返回 404。
    # 本项目对话用 DeepSeek、嵌入用硅基流动的 BAAI/bge-m3，所以两套凭证各自独立。
    llm_embedding_model: str = field(
        default_factory=lambda: _str("LLM_EMBEDDING_MODEL", "")
    )
    llm_embedding_api_key: str = field(
        default_factory=lambda: _str("LLM_EMBEDDING_API_KEY", "")
    )
    llm_embedding_base_url: str = field(
        default_factory=lambda: _str(
            "LLM_EMBEDDING_BASE_URL", "https://api.siliconflow.cn/v1"
        ).rstrip("/")
    )
    llm_timeout_s: float = field(default_factory=lambda: _float("LLM_TIMEOUT_S", 60.0))

    # 演示模式：true 时强制走预生成缓存，不调外部 API
    demo_mode: bool = field(default_factory=lambda: _bool("DEMO_MODE", False))

    # ---- 高德地图（M4 工具调用）----
    # 与 LLM_API_KEY 同一套路：只被 Python 读取，Java 不读 .env，
    # 前端更拿不到 —— 这是"AMAP_KEY 不进前端"这条红线的实现方式。
    #
    # ★ key 必须是**「Web 服务」类型**。高德控制台里 key 分好几种用途，
    #   拿「Web端(JS API)」或「Android/iOS」的 key 调 REST 接口，
    #   返回的是 INVALID_USER_KEY —— 那个报错不会告诉你是 key 类型错了。
    amap_key: str = field(default_factory=lambda: _str("AMAP_KEY", ""))
    amap_base_url: str = field(
        default_factory=lambda: _str(
            "AMAP_BASE_URL", "https://restapi.amap.com/v3"
        ).rstrip("/")
    )
    # 高德是同步 HTTP，正常在几百毫秒内返回。给 8 秒已经非常宽松：
    # 超时太长会让"高德挂了"表现成"整个回答卡住"，用户看不出在等什么。
    amap_timeout_s: float = field(default_factory=lambda: _float("AMAP_TIMEOUT_S", 8.0))

    # ---- 向量库 ----
    vectorstore_dir: Path = field(
        default_factory=lambda: _path("VECTORSTORE_DIR", REPO_ROOT / "data" / "vectorstore")
    )
    top_k: int = field(default_factory=lambda: _int("RAG_TOP_K", 5))

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
        name = self._city_meta().get("name")
        return str(name) if name else self.city

    @property
    def city_center(self) -> tuple[float, float] | None:
        """城市中心坐标 `(lng, lat)`，取自 meta.json 的 `center`。

        用途是**高德周边搜索的兜底中心点**：用户说"汉中附近有什么酒店"却没说
        具体在哪时，总得有个中心点。用数据包里声明的城市中心，比在代码里写死
        一个坐标好 —— 换城市时它会跟着换。
        """
        center = self._city_meta().get("center")
        if not isinstance(center, dict):
            return None
        try:
            return float(center["lng"]), float(center["lat"])
        except (KeyError, TypeError, ValueError):
            return None

    def _city_meta(self) -> dict:
        """读 citypack/<city>/meta.json。文件缺失或坏掉时返回空字典 ——
        元数据只影响文案与兜底坐标，不该让服务起不来。"""
        meta_file = self.city_dir / "meta.json"
        if not meta_file.is_file():
            return {}
        try:
            payload = json.loads(meta_file.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            return {}
        return payload if isinstance(payload, dict) else {}

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

    @property
    def amap_enabled(self) -> bool:
        """配了 AMAP_KEY 才启用高德工具。

        没配时 Agent 不会崩，而是**降级**：工具列表里去掉高德那三个，
        并让模型知道"现在查不了真实地点"。这比整个 /ai/agent 返回 500 好 ——
        没配 key 的人仍然能用知识库问答，只是问不到酒店。
        """
        return bool(self.amap_key)


def get_settings() -> Settings:
    _load_dotenv()
    return Settings()
