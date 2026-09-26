"""语料构建：把 citypack 变成可入库的文档与切片。

语料有两个来源，各自解决不同问题：

1. **手写城市知识**（`citypack/<city>/docs/*.md`）
   地理、气候、历史、生态这些"数据包里没有、但游客一定会问"的内容。
   每篇的 front-matter 里只有一个 source_url——刻意不做多来源混排，
   否则一段文字挂在错误的来源上，答辩时被追问就说不清了。

2. **数据包派生**（pois / experiences / products）
   每个资源点、体验、产品各生成一段。好处是知识库跟着数据走：改了数据包、
   重建一次知识库，两者不会各说各话。这三类的来源**各自不同**，见下面
   「来源分三档」一段 —— 曾经让体验与产品继承所属资源点的 source_url，
   那会让一段自撰描述挂着文旅局的引用，是编造出处，已改掉。

切片按段落聚合到 500 字、相邻切片重叠 50 字，避免答案正好被切在边界上。

**来源分三档**（`source_kind`，随元数据一路传到前端的来源卡片）：

- `detail` —— 12 篇手写文档，front-matter 里各自带真实深链，可点开核对
- `site`   —— 42 个资源点，只有站点级参考。数据包里的 source_url 全是
  `http://wl.hanzhong.gov.cn/`，而那是文旅局的**新闻/公告门户**，
  站上并没有"汉中热面皮（老字号）"这类独立页面。如实标 site，
  前端显示"站点参考"，而不是把它当成这一条的出处
- `dataset` —— 14 项体验 + 16 款产品，描述文本由本项目编写，
  数据包里连 source_url 都没有，所以署"汉游智脑数据包"，
  前端给的是跳回所属资源点的**站内链接**，而不是一个假外链

**为什么坚持这样分**：来源的全部价值是可核对。一条点进去找不到对应内容的
链接，比明说"只有站点级参考"更糟 —— 它让不可核对的东西看起来可核对。
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

# ----------------------------------------------------------------------
# 来源的性质。前端据此决定显示哪种标签、给不给外链。
#
# 为什么要把"来源"分成三档而不是只给一个 URL：数据包里 72 篇派生文档
# 全部指向文旅局站点首页，如果一律当成"这一条的出处"展示，用户点进去
# 会发现找不到那家店、那个价格 —— 那是**看起来可核对、实际不可核对**，
# 比明说"只有站点级参考"更糟。三档把这件事讲清楚：
#
#   detail  —— 有可核对的具体页面（8 篇手写文档，各自带真实深链）
#   site    —— 只有站点级参考：该站是新闻/公告门户，不提供这一条的独立页面
#   dataset —— 项目数据包自有，外部没有对应出处
# ----------------------------------------------------------------------
SOURCE_DETAIL = "detail"
SOURCE_SITE = "site"
SOURCE_DATASET = "dataset"

# 数据包派生内容的署名。体验与产品的描述文本（desc / story）由本项目编写，
# 不是从某个官方页面摘的，所以不能挂到文旅局名下 —— 那会是一处**编造的引用**，
# 而来源的全部意义就是可核对。
DATASET_SOURCE_NAME = "汉游智脑数据包"

# 来源卡片的介绍长度。太长会把卡片撑开、挤掉标题与链接
SNIPPET_LIMIT = 80


def _snippet(text: str, limit: int = SNIPPET_LIMIT) -> str:
    """给来源卡片用的简短介绍：去 Markdown 标题、压平空白、按字符截断。

    刻意在**构建期**算好写进元数据，而不是查询期从命中正文里现截：
    文档结构是这里生成的，只有这里知道哪一段是正文、哪一段是模板套话。
    放到查询期就得靠正则猜"哪句是套话"，猜错会直接影响用户看到的介绍。
    """
    body = re.sub(r"^#{1,6}.*$", "", text, flags=re.MULTILINE)
    # 顺手去掉行首的列表记号（`- ` / `1. `）。正文里到处是分点，
    # 压平空白后会变成"……平坝。 - 北面与宝鸡市……"，看着像没处理干净。
    body = re.sub(r"^[ \t]*[-*+]\s+", "", body, flags=re.MULTILINE)
    body = re.sub(r"^[ \t]*\d+[.)]\s+", "", body, flags=re.MULTILINE)
    # 去掉强调记号。来源卡片直接显示 snippet，`**面皮**` 会把星号原样印出来。
    # 先配对替换再兜底清残留（未闭合的 `**` 走不到第一句）。
    body = re.sub(r"\*\*(.+?)\*\*", r"\1", body, flags=re.DOTALL)
    body = body.replace("**", "")
    body = re.sub(r"\s+", " ", body).strip()
    if len(body) <= limit:
        return body
    return body[:limit].rstrip() + "…"


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
    source_kind: str = SOURCE_DETAIL
    snippet: str = ""
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
    source_kind: str = SOURCE_DETAIL
    snippet: str = ""
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
                # 手写文档默认按"有具体页面"处理：它们各自的 front-matter 里
                # 带真实深链（政府概况页 / 百度百科词条），可核对。
                # front-matter 也可以显式覆盖，留给以后接入无深链的资料。
                source_kind=meta.get("source_kind", SOURCE_DETAIL),
                snippet=_snippet(body),
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
                # 站点级参考：数据包里 42 个资源点的 source_url 全是
                # http://wl.hanzhong.gov.cn/ —— 那是文旅局的新闻/公告门户，
                # 站上并没有"汉中热面皮（老字号）"这种独立页面。
                # 标成 site，前端会显示"站点参考"而不是假装这是该条的出处。
                source_kind=SOURCE_SITE,
                # 简介直接取数据包里的 summary：它就是为这一条写的一句话，
                # 比从拼装正文里截前 80 字准确（正文开头是"X位于汉中市Y…"的套话）
                snippet=poi.get("summary") or _snippet("\n".join(x for x in lines if x)),
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
                # 不再继承所属乡村点的 source_url。体验的 desc 是本项目编写的
                # 描述文本，数据包里也没有它自己的 source_url —— 挂到文旅局名下
                # 等于给一段自撰文字配一个查不到的引用。宁可如实说"数据包自有"，
                # 站内再给一个跳回该资源点的链接（前端用 poi_id 拼）。
                source_url="",
                source_name=DATASET_SOURCE_NAME,
                data_origin=exp.get("data_origin", "PUBLIC"),
                source_kind=SOURCE_DATASET,
                snippet=exp.get("desc") or _snippet("\n".join(x for x in lines if x)),
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
                # 同体验：story 是本项目编写的产品叙事，数据包里没有 source_url
                source_url="",
                source_name=DATASET_SOURCE_NAME,
                data_origin=prod.get("data_origin", "PUBLIC"),
                source_kind=SOURCE_DATASET,
                snippet=prod.get("story") or _snippet("\n".join(x for x in lines if x)),
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
                    # 来源性质与简介是**文档级**的，不是切片级：来源卡片代表
                    # 一篇文档，所以同一文档的每个切片带同一份简介。
                    source_kind=doc.source_kind,
                    snippet=doc.snippet,
                    extra=doc.extra,
                )
            )
    return chunks


def build_chunks(city_dir: Path) -> list[Chunk]:
    """加载全部语料并切片。返回的切片是构建知识库的唯一输入。"""
    docs = load_hand_docs(city_dir / "docs") + load_generated_docs(city_dir)
    return chunk_docs(docs)
