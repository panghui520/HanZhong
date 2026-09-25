"""语料构建：把 citypack 变成可入库的文档与切片。

语料有两个来源，各自解决不同问题：

1. **手写城市知识**（`citypack/<city>/docs/*.md`）
   地理、气候、历史、生态这些"数据包里没有、但游客一定会问"的内容。
   每篇的 front-matter 里只有一个 source_url——刻意不做多来源混排，
   否则一段文字挂在错误的来源上，答辩时被追问就说不清了。

2. **数据包派生**（pois / experiences / products）
   每个资源点、体验、产品各生成一段。好处是知识库跟着数据走：改了数据包、
   重建一次知识库，两者不会各说各话。体验与产品在数据包里没有 source_url，
   按规则**继承所属资源点的来源**——它们的公开信息本来就来自同一个官方页面。

切片按段落聚合到 500 字、相邻切片重叠 50 字，避免答案正好被切在边界上。
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path

CHUNK_SIZE = 500
CHUNK_OVERLAP = 50

_FRONT_MATTER = re.compile(r"^---\s*\n(.*?)\n---\s*\n", re.DOTALL)

# 资源业态 -> 中文，用于生成文档时的可读表述
BUSINESS_LABEL = {
    "SCENIC": "景区",
    "RURAL_SPOT": "乡村旅游点",
    "FOOD": "餐饮",
    "LODGING": "住宿",
    "TRANSPORT": "交通",
    "SHOPPING": "购物",
}

EXPERIENCE_LABEL = {
    "TEA": "茶事体验",
    "PICKING": "采摘体验",
    "FOLK": "非遗手作",
    "HOMESTAY": "民宿住宿",
    "FOOD_MAKING": "农事美食",
    "NATURE": "自然观察",
}


@dataclass
class Doc:
    """一篇完整文档（切片前的单位）"""

    doc_id: str
    title: str
    text: str
    doc_type: str
    source_url: str = ""
    source_name: str = ""
    data_origin: str = "PUBLIC"
    extra: dict = field(default_factory=dict)


@dataclass
class Chunk:
    """入库的最小单位"""

    chunk_id: str
    doc_id: str
    title: str
    text: str
    doc_type: str
    source_url: str
    source_name: str
    data_origin: str
    seq: int
    extra: dict = field(default_factory=dict)


# ----------------------------------------------------------------------
# 手写城市知识
# ----------------------------------------------------------------------


def parse_front_matter(raw: str) -> tuple[dict, str]:
    """解析 `---` 包裹的 `key: value` 头。只用标准库，不引入 YAML 依赖。"""
    match = _FRONT_MATTER.match(raw)
    if not match:
        return {}, raw

    meta: dict[str, str] = {}
    for line in match.group(1).splitlines():
        line = line.strip()
        if not line or line.startswith("#") or ":" not in line:
            continue
        key, value = line.split(":", 1)
        meta[key.strip()] = value.strip()

    return meta, raw[match.end() :]


def load_hand_docs(docs_dir: Path) -> list[Doc]:
    if not docs_dir.is_dir():
        return []

    docs: list[Doc] = []
    for path in sorted(docs_dir.glob("*.md")):
        meta, body = parse_front_matter(path.read_text(encoding="utf-8"))
        body = body.strip()
        if not body:
            continue
        docs.append(
            Doc(
                doc_id=f"doc:{path.stem}",
                title=meta.get("title") or path.stem,
                text=body,
                doc_type="city_doc",
                source_url=meta.get("source_url", ""),
                source_name=meta.get("source_name", ""),
                data_origin=meta.get("data_origin", "PUBLIC"),
                extra={"file": path.name},
            )
        )
    return docs


# ----------------------------------------------------------------------
# 数据包派生
# ----------------------------------------------------------------------


def _read_json(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    return json.loads(path.read_text(encoding="utf-8"))


def load_generated_docs(city_dir: Path) -> list[Doc]:
    """从数据包派生文档。产品与体验的来源继承所属资源点。"""
    pois = _read_json(city_dir / "pois.json")
    experiences = _read_json(city_dir / "experiences.json")
    products = _read_json(city_dir / "products.json")

    poi_by_id = {p["id"]: p for p in pois}
    exp_by_id = {e["id"]: e for e in experiences}

    docs: list[Doc] = []

    # ---- 资源点 ----
    for poi in pois:
        label = BUSINESS_LABEL.get(poi.get("business_type", ""), "文旅资源")
        lines = [
            f"# {poi['name']}（{label}）",
            "",
            f"{poi['name']}位于汉中市{poi.get('district', '')}，属于{label}类资源。",
            poi.get("summary", ""),
        ]
        if poi.get("level"):
            lines.append(f"等级：{poi['level']}。")
        if poi.get("ticket_price") is not None:
            price = poi["ticket_price"]
            lines.append("门票：免费开放。" if not price else f"门票：{price} 元。")
        if poi.get("open_hours"):
            lines.append(f"开放时间：{poi['open_hours']}。")
        if poi.get("duration_min"):
            lines.append(f"建议游览时长约 {poi['duration_min']} 分钟。")
        if poi.get("tags"):
            lines.append("特色标签：" + "、".join(poi["tags"]) + "。")

        docs.append(
            Doc(
                doc_id=f"poi:{poi['id']}",
                title=poi["name"],
                text="\n".join(x for x in lines if x),
                doc_type="poi",
                source_url=poi.get("source_url", ""),
                source_name="汉中市文化和旅游局",
                data_origin=poi.get("data_origin", "PUBLIC"),
                extra={
                    "poi_id": poi["id"],
                    "business_type": poi.get("business_type", ""),
                    "district": poi.get("district", ""),
                },
            )
        )

    # ---- 乡村体验 ----
    for exp in experiences:
        parent = poi_by_id.get(exp.get("poi_id", ""), {})
        label = EXPERIENCE_LABEL.get(exp.get("type", ""), "乡村体验")
        lines = [
            f"# {exp['name']}（{label}）",
            "",
            f"{exp['name']}是{parent.get('name', '汉中')}提供的一项{label}，"
            f"位于汉中市{parent.get('district', '')}。",
            exp.get("desc", ""),
        ]
        if exp.get("duration_min"):
            lines.append(f"体验时长约 {exp['duration_min']} 分钟。")
        if exp.get("price") is not None:
            price = exp["price"]
            lines.append("价格：免费。" if not price else f"人均价格 {price} 元。")
        if exp.get("season"):
            lines.append(f"适宜季节：{exp['season']}。")
        if exp.get("capacity"):
            lines.append(f"单场可接待 {exp['capacity']} 人。")
        if exp.get("tags"):
            lines.append("体验内容：" + "、".join(exp["tags"]) + "。")

        docs.append(
            Doc(
                doc_id=f"exp:{exp['id']}",
                title=exp["name"],
                text="\n".join(x for x in lines if x),
                doc_type="experience",
                # 体验在数据包里没有 source_url，继承所属乡村点的来源
                source_url=parent.get("source_url", ""),
                source_name="汉中市文化和旅游局",
                data_origin=exp.get("data_origin", "PUBLIC"),
                extra={
                    "experience_id": exp["id"],
                    "poi_id": exp.get("poi_id", ""),
                    "exp_type": exp.get("type", ""),
                    "district": parent.get("district", ""),
                },
            )
        )

    # ---- 农产品 ----
    for prod in products:
        parent = poi_by_id.get(prod.get("poi_id", ""), {})
        anchor = exp_by_id.get(prod.get("experience_id", ""), {})
        lines = [
            f"# {prod['name']}（{prod.get('category', '')}）",
            "",
            f"{prod['name']}产自{prod.get('origin_village', parent.get('name', '汉中'))}，"
            f"属于{prod.get('category', '')}类乡村产品。",
        ]
        if prod.get("spec"):
            lines.append(f"规格：{prod['spec']}。")
        if prod.get("price") is not None:
            lines.append(f"售价 {prod['price']} 元。")
        if prod.get("story"):
            lines.append(prod["story"])
        if anchor:
            lines.append(f"它与「{anchor['name']}」这次乡村体验锚定，游客可以在体验之后带走。")
        if prod.get("tags"):
            lines.append("产品特点：" + "、".join(prod["tags"]) + "。")

        docs.append(
            Doc(
                doc_id=f"prd:{prod['id']}",
                title=prod["name"],
                text="\n".join(x for x in lines if x),
                doc_type="product",
                source_url=parent.get("source_url", ""),
                source_name="汉中市文化和旅游局",
                data_origin=prod.get("data_origin", "PUBLIC"),
                extra={
                    "product_id": prod["id"],
                    "poi_id": prod.get("poi_id", ""),
                    "experience_id": prod.get("experience_id", ""),
                    "category": prod.get("category", ""),
                    "district": parent.get("district", ""),
                },
            )
        )

    return docs


# ----------------------------------------------------------------------
# 切片
# ----------------------------------------------------------------------


def split_text(text: str, size: int = CHUNK_SIZE, overlap: int = CHUNK_OVERLAP) -> list[str]:
    """按段落聚合切片。

    先按空行拆段，再把段落累加到接近 size。段落本身就超长时硬切，
    保证没有切片超过 size 太多。相邻切片保留 overlap 个字符的重叠，
    这样答案正好落在边界上时，至少有一个切片是完整的。
    """
    paragraphs = [p.strip() for p in re.split(r"\n\s*\n", text) if p.strip()]
    if not paragraphs:
        return []

    pieces: list[str] = []
    for para in paragraphs:
        if len(para) <= size:
            pieces.append(para)
            continue
        # 超长段落按句子再切，句号/换行都当作边界
        buffer = ""
        for sentence in re.split(r"(?<=[。！？；\n])", para):
            if not sentence:
                continue
            if len(buffer) + len(sentence) > size and buffer:
                pieces.append(buffer)
                buffer = sentence
            else:
                buffer += sentence
        if buffer:
            pieces.append(buffer)

    chunks: list[str] = []
    current = ""
    for piece in pieces:
        if current and len(current) + len(piece) + 1 > size:
            chunks.append(current)
            # 重叠：把上一片的尾部接到下一片开头
            tail = current[-overlap:] if overlap else ""
            current = f"{tail}\n{piece}" if tail else piece
        else:
            current = f"{current}\n{piece}" if current else piece
    if current:
        chunks.append(current)

    return chunks


def chunk_docs(docs: list[Doc]) -> list[Chunk]:
    chunks: list[Chunk] = []
    for doc in docs:
        for seq, text in enumerate(split_text(doc.text)):
            chunks.append(
                Chunk(
                    chunk_id=f"{doc.doc_id}#{seq}",
                    doc_id=doc.doc_id,
                    title=doc.title,
                    text=text,
                    doc_type=doc.doc_type,
                    source_url=doc.source_url,
                    source_name=doc.source_name,
                    data_origin=doc.data_origin,
                    seq=seq,
                    extra=doc.extra,
                )
            )
    return chunks


def build_chunks(city_dir: Path) -> list[Chunk]:
    """加载全部语料并切片。返回的切片是构建知识库的唯一输入。"""
    docs = load_hand_docs(city_dir / "docs") + load_generated_docs(city_dir)
    return chunk_docs(docs)
