#!/usr/bin/env python
"""构建 Chroma 知识库。

用法（在仓库根目录执行）：
    python scripts/build_kb.py                # 用离线向量化重建知识库
    python scripts/build_kb.py --api          # 用厂商 embedding 接口（需配 LLM_EMBEDDING_API_KEY）
    python scripts/build_kb.py --dump 20      # 只打印前 20 条切片，不建库（核对语料用）
    python scripts/build_kb.py --city hanzhong

知识库是**离线产物**：语料来自 citypack/，向量落盘到 data/vectorstore/。
改完数据包或 docs/ 之后必须重跑本脚本，否则 /ai/health 会报"语料已变化"。

注意：换了向量化实现（离线 ↔ 厂商、或换 embedding 模型）等于换了特征空间，
**必须重跑本脚本**。集合名带实现指纹，不重建的话服务会按新指纹去找集合、
找不到、然后报"还没有构建知识库"。

解释器用哪个：本项目在 Windows 上开发，建议用独立的虚拟环境，
不要污染系统 Python：
    <venv>/Scripts/python.exe scripts/build_kb.py
"""

from __future__ import annotations

import argparse
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server-ai"))

from app.config import get_settings  # noqa: E402
from app.corpus import SOURCE_DATASET, build_chunks  # noqa: E402
from app.embed import fit_embedder  # noqa: E402
from app.store import KbStore  # noqa: E402

# --api 且没配 LLM_EMBEDDING_MODEL 时的兜底模型名。
# 硅基流动上的中文嵌入模型，1024 维。选它的理由：中文强、成熟稳定、
# **不需要指令前缀**——Qwen3-Embedding 系列要区分查询/文档两种前缀才发挥最佳效果，
# 而 ApiEmbedder 现在 embed() 与 embed_many() 走同一条路径，加前缀要多一处逻辑。
DEFAULT_EMBEDDING_MODEL = "BAAI/bge-m3"


def main() -> int:
    parser = argparse.ArgumentParser(description="构建汉游智脑知识库")
    parser.add_argument("--city", help="城市编码，默认取 CITY_PACK 环境变量")
    parser.add_argument("--dump", type=int, metavar="N", help="只打印前 N 条切片，不建库")
    parser.add_argument("--api", action="store_true", help="强制使用厂商 embedding 接口")
    args = parser.parse_args()

    settings = get_settings()
    if args.city:
        settings = settings.__class__(**{**settings.__dict__, "city": args.city})

    city_dir = settings.city_dir
    if not city_dir.is_dir():
        print(f"[build_kb] 找不到城市数据包：{city_dir}", file=sys.stderr)
        return 2

    chunks = build_chunks(city_dir)
    if not chunks:
        print(f"[build_kb] {city_dir} 下没有可用的语料", file=sys.stderr)
        return 2

    by_type = Counter(c.doc_type for c in chunks)
    print(f"[build_kb] 语料目录 {city_dir}")
    print(f"[build_kb] 切片 {len(chunks)} 条，来自 {len({c.doc_id for c in chunks})} 篇文档")
    for doc_type, count in sorted(by_type.items()):
        print(f"           {doc_type:<12} {count}")

    # 来源盘点。按性质分开看，因为"没有外链"的含义完全不同：
    #   dataset —— 体验与产品署"汉游智脑数据包"，本来就没有外部出处，属预期
    #   detail / site —— 标着可核对来源却没有链接，那才是真问题
    # 混在一起报会让人学会忽略警告，等真的缺链接时也就看不见了。
    by_kind = Counter(c.source_kind for c in chunks)
    print("[build_kb] 来源性质 " + "  ".join(f"{k}={v}" for k, v in sorted(by_kind.items())))
    unexpected = [
        c.chunk_id
        for c in chunks
        if not c.source_url and c.source_kind != SOURCE_DATASET
    ]
    if unexpected:
        print(
            f"[build_kb] 警告：{len(unexpected)} 条切片标为可核对来源却没有链接，"
            f"例如 {unexpected[:3]}",
            file=sys.stderr,
        )

    if args.dump:
        limit = args.dump
        print(f"\n--- 前 {min(limit, len(chunks))} 条切片 ---")
        for chunk in chunks[:limit]:
            head = chunk.text.replace("\n", " ")[:110]
            print(f"\n[{chunk.chunk_id}] {chunk.title}  ({len(chunk.text)} 字)")
            print(f"    {head}…")
            print(f"    来源：{chunk.source_url or '（无）'}")
        return 0

    texts = [c.text for c in chunks]
    if args.api:
        # --api 时 key 是必需的。缺 key 的话 fit_embedder 会**静默**退回离线实现，
        # 建出来的库和你要的不是一个东西，而日志上只会看到签名不同——很容易忽略。
        # 这里直接拦住，把问题说清楚。
        if not settings.llm_embedding_api_key:
            print(
                "[build_kb] --api 需要 LLM_EMBEDDING_API_KEY，但它是空的。\n"
                "           请在仓库根的 .env 里填写（模板见 .env.example）。\n"
                "           嵌入用硅基流动的 key，跟对话模型的 LLM_API_KEY 不是同一个。",
                file=sys.stderr,
            )
            return 2
        settings = settings.__class__(
            **{
                **settings.__dict__,
                "llm_embedding_model": settings.llm_embedding_model or DEFAULT_EMBEDDING_MODEL,
            }
        )
    embedder = fit_embedder(settings, texts)
    print(f"[build_kb] 向量化实现 {embedder.signature}")

    store = KbStore.rebuild(settings.vectorstore_dir, embedder, chunks)
    manifest = store.manifest()
    coverage = store.lexical.coverage()
    print(f"[build_kb] 已写入 {settings.vectorstore_dir}")
    print(f"[build_kb] 集合 {manifest['collection']} 共 {store.count()} 条向量")
    print(f"[build_kb] 词表 {coverage['terms']} 个特征，平均切片 {coverage['avg_tokens']} 个特征")
    print(f"[build_kb] 指纹 {manifest['fingerprint'][:16]}…")
    print("[build_kb] 检索效果自检：python scripts/eval_retrieval.py")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
