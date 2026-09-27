#!/usr/bin/env python
"""生成 M7 运营解读的离线演示缓存（`server-ai/cache/ops_demo.json`）。

用法（在仓库根目录执行，需要后端已启动）：
    <venv>/Scripts/python.exe scripts/build_ops_demo.py

**为什么这份缓存必须由脚本生成，而不能像 M3 的 `qa_demo.json` 那样手写：**
M3 的缓存答案是"知识库原文的摘要"，语料不变它就不变；而 M7 的解读
**全是数字**（"9 处景区承载超 80%"、"乡村点平均承载 31.1%"）——
手写的那一份第二天就和面板对不上了。所以这里跑一次**真实调用**再存下来，
并记下 `metrics_digest`：数据换了以后回放，响应里 `stale=true`，
界面会说明"回放的是 X 那批数据"。宁可口径说清楚，
也不要让一段过期数字冒充当前解读。

流程：

    1. 登录运营端，拉一份 `/api/admin/ops/snapshot`
       —— 指标由 **Java** 算（Python 不连业务库，这是 M3 起的分工）
    2. 对 6 个关注点各调一次模型，解析成三段式
    3. 全部成功后才写文件

退出码：模型没配好、快照拉不到、任一关注点解析失败 → 1。
**失败时不写半份缓存** —— 少一个关注点的缓存，在演示时表现为"点了某个
按钮没反应"，比整个没有更难查（见 M5 那次 `v-else-if` 的教训：
缺东西的症状常常长得像"功能坏了"）。

环境变量（都可省，默认值就是本仓库的演示账号）：
    OPS_BACKEND          后端地址，默认 http://127.0.0.1:8080
    OPS_ADMIN_EMAIL      运营账号
    OPS_ADMIN_PASSWORD   运营密码
"""

from __future__ import annotations

import asyncio
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server-ai"))

from app.config import get_settings  # noqa: E402
from app.llm import complete_llm  # noqa: E402
from app.ops_analysis import (  # noqa: E402
    FOCUSES,
    MAX_TOKENS,
    TEMPERATURE,
    build_messages,
    metrics_digest,
    parse_sections,
    slice_metrics,
)

CST = timezone(timedelta(hours=8))

BACKEND = os.environ.get("OPS_BACKEND", "http://127.0.0.1:8080")
EMAIL = os.environ.get("OPS_ADMIN_EMAIL", "admin@hanyou.local")
PASSWORD = os.environ.get("OPS_ADMIN_PASSWORD", "hanyou2026")


def _post(url: str, payload: dict[str, Any], token: str | None = None) -> dict[str, Any]:
    request = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"), method="POST")
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def _get(url: str, token: str) -> dict[str, Any]:
    request = urllib.request.Request(url)
    request.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


async def build() -> int:
    settings = get_settings()
    if not settings.llm_enabled:
        print(
            "[ops-demo] 模型未配置（缺 LLM_API_KEY 或 DEMO_MODE=true），"
            "无法生成真实解读。先把 .env 里的模型配好再跑。",
            file=sys.stderr,
        )
        return 1

    # ---- 1) 登录 + 拉快照 ----
    try:
        login = _post(f"{BACKEND}/api/auth/login", {"email": EMAIL, "password": PASSWORD})
    except urllib.error.URLError as exc:
        print(f"[ops-demo] 连不上后端 {BACKEND}：{exc}", file=sys.stderr)
        return 1
    if login.get("code") != 0:
        print(f"[ops-demo] 登录失败：{login}", file=sys.stderr)
        return 1
    token = login["data"]["token"]

    snapshot = _get(f"{BACKEND}/api/admin/ops/snapshot", token)
    if snapshot.get("code") != 0:
        print(f"[ops-demo] 快照拉取失败：{snapshot}", file=sys.stderr)
        return 1
    metrics: dict[str, Any] = snapshot["data"]
    print(f"[ops-demo] 快照周期：{metrics.get('period_label')}")
    print(f"[ops-demo] 模型：{settings.llm_model}（{settings.llm_base_url}）")
    print("-" * 72)

    # ---- 2) 逐关注点生成 ----
    analyses: dict[str, dict[str, Any]] = {}
    for key, focus in FOCUSES.items():
        sliced = slice_metrics(focus, metrics)
        try:
            raw = await complete_llm(
                settings,
                build_messages(settings, focus, sliced),
                max_tokens=MAX_TOKENS,
                temperature=TEMPERATURE,
            )
            sections = parse_sections(raw)
        except Exception as exc:  # noqa: BLE001 - 失败要中止整次构建
            print(f"[ops-demo] ★ {key}（{focus.label}）生成失败：{exc}", file=sys.stderr)
            return 1

        analyses[key] = {
            "focus": key,
            "focus_label": focus.label,
            "sections": sections,
            # ★ 摘要只覆盖**该关注点用到的切片**：与运行时 `analyze()` 算的
            #   是同一份输入，所以"数据换没换"的判断才有意义。
            #   若对整份快照算摘要，任何一处数字变动都会让 6 个关注点全部标成 stale。
            "metrics_digest": metrics_digest(sliced),
            "model": settings.llm_model,
            "generated_at": datetime.now(CST).isoformat(timespec="seconds"),
            "period_label": metrics.get("period_label"),
        }
        print(f"[ops-demo]   {key:<10} {focus.label:<12} {len(sections)} 段，"
              f"切片 {len(sliced)} 个字段")

    # ---- 3) 全部成功才写 ----
    out = Path(settings.cache_file).parent / "ops_demo.json"
    payload = {
        "meta": {
            "generated_at": datetime.now(CST).isoformat(timespec="seconds"),
            "model": settings.llm_model,
            "city": settings.city,
            "period_label": metrics.get("period_label"),
            "focus_count": len(analyses),
        },
        "analyses": analyses,
    }
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("-" * 72)
    print(f"[ops-demo] 已写入 {out}（{len(analyses)} 个关注点）")
    print("[ops-demo] 提醒：改过 citypack 或重灌过仿真数据后要重跑本脚本，"
          "否则回放出来的解读会和面板上的数字对不上。")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(build()))
