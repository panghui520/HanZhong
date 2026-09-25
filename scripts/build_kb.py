#!/usr/bin/env python
"""构建 Chroma 知识库。

用法（在仓库根目录执行）：
    python scripts/build_kb.py                # 用离线向量化重建知识库
    python scripts/build_kb.py --dump 20      # 只打印前 20 条切片，不建库（核对语料用）
    python scripts/build_kb.py --city hanzhong

知识库是**离线产物**：语料来自 citypack/，向量落盘到 data/vectorstore/。
改完数据包或 docs/ 之后必须重跑本脚本，否则 /ai/health 会报"语料已变化"。

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
from app.corpus import build_chunks  # noqa: E402
from app.embed import fit_embedder  # noqa: E402
from app.store import KbStore  # noqa: E402


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

    missing_source = [c.chunk_id for c in chunks if not c.source_url]
    if missing_source:
        print(f"[build_kb] 警告：{len(missing_source)} 条切片没有来源链接，例如 {missing_source[:3]}")

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
        settings = settings.__class__(
            **{**settings.__dict__, "llm_embedding_model": settings.llm_embedding_model or "text-embedding-v3"}
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
