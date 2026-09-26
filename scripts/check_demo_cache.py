#!/usr/bin/env python
"""校验离线演示缓存（`server-ai/cache/qa_demo.json`）。

用法（在仓库根目录执行）：
    <venv>/Scripts/python.exe scripts/check_demo_cache.py

断网或 `DEMO_MODE=true` 时，服务走的是**缓存回放**：
`llm_enabled=False` → `cache.lookup(question)` → 命中就回放，未命中掉到
`extractive`（摘录原文）。所以这份缓存就是**现场演示的第一印象**。

查两件事，各自对应一类会**静默失败**的问题：

1. **每个 `sources` 里的 doc_id 在语料里都存在。**
   缓存只存 doc_id，来源名称与链接是**运行时**用 `metadata_for_docs` 现查的
   （这个设计本身是对的：改了数据包不会留下过期链接）。
   代价是：语料删了文档、改了 doc_id，而缓存没跟着改，**不会有任何报错** ——
   只会让来源卡片少一条、甚至整块变空。演示时最难发现的就是这种。
2. **首屏推荐问题 100% 命中缓存。**
   观众最先点的就是那几个。任何一个命中不了，就会掉到摘录或拒答。

另外提醒一句：**扩充语料之后必须回头读一遍缓存答案**。
缓存里常写着「知识库里没有记载 X」这类话，一旦 X 被写进新文档，
这句话就变成假的 —— 而且它是**回放出来的**，模型没有机会纠正。

退出码：任一检查不通过返回 1。
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server-ai"))

from app.config import get_settings  # noqa: E402
from app.corpus import build_chunks  # noqa: E402
from app.llm import AnswerCache  # noqa: E402


def main() -> int:
    settings = get_settings()
    chunks = build_chunks(settings.city_dir)
    known = {c.doc_id for c in chunks}
    titles: dict[str, str] = {}
    for chunk in chunks:
        titles.setdefault(chunk.doc_id, chunk.title)

    cache_path = Path(settings.cache_file)
    if not cache_path.is_file():
        print(f"[cache] 找不到缓存文件 {cache_path}", file=sys.stderr)
        return 2
    raw = json.loads(cache_path.read_text(encoding="utf-8"))
    cache = AnswerCache(cache_path)

    print(f"[cache] 文件 {cache_path}")
    print(f"[cache] 语料 {len(chunks)} 片 / {len(known)} 篇；"
          f"缓存 {len(raw.get('answers', []))} 组 / {len(cache)} 条问法")
    print("-" * 72)

    dangling: list[str] = []
    for entry in raw.get("answers", []):
        docs = list(entry.get("sources", []))
        missing = [d for d in docs if d not in known]
        if missing:
            dangling.append(entry["id"])
        names = "；".join(titles.get(d, f"??{d}") for d in docs)
        print(f"{'★' if missing else ' '} {entry['id']:<24} {len(docs)} 篇  {names}")
        for doc_id in missing:
            print(f"      ★ 悬空 doc_id：{doc_id} —— 这一条来源会在运行时消失")

    print("-" * 72)
    suggestions = cache.meta.get("suggestions") or []
    missed = [q for q in suggestions if cache.lookup(q) is None]
    print(f"[cache] 首屏推荐问题 {len(suggestions) - len(missed)}/{len(suggestions)} 命中缓存")
    for question in missed:
        print(f"  ★ MISS {question}")

    print("-" * 72)
    if dangling:
        print(f"悬空 doc_id 的条目：{'、'.join(dangling)}")
    if missed:
        print(f"未命中的推荐问题：{'、'.join(missed)}")
    ok = not dangling and not missed
    print(f"{'通过' if ok else '★ 不达标'}：悬空 doc_id {len(dangling)} 组，"
          f"推荐问题未命中 {len(missed)} 条")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
