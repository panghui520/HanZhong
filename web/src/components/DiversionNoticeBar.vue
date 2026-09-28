<script setup lang="ts">
/**
 * DiversionNoticeBar —— 今日游览建议（内部仍叫"分流公告"，M5 续）
 *
 * **这是本项目里第一个"系统主动对游客说话"的界面。**在此之前游客端全是
 * "你点哪里我给你看什么"；这条是说"我们发现某个点挤了，你可以换个去处"。
 * 所以三件事要克制：
 *
 * 1. **不喊"已满"**。文案一律沿用后端给的原文（"当前客流较高"），
 *    前端不自己加戏。承载率来自合成数据，喊得太满就是在替数据说话。
 * 2. **候选按"就近乡村 → 就近景区"分两组**，组序是后端定的、也是运营定的口径。
 *    界面把组名显式写出来，让"乡村优先"这件事看得见，而不是一个混在一起的列表。
 * 3. **快照与当前两组数分开显示**。公告发布后承载还会变，发布时说 B 村 23%、
 *    现在可能已经 91%。只显示旧数等于骗游客，只显示新数就答不出"为什么是它"。
 *    所以当前与发布时差得多时，两个说法都写出来。
 *
 * ★ 面向游客的用词（2026-09-28）：
 *   徽标从「分流提示」改成「今日游览建议」，chip 里的「承载 23%」改成
 *   「人少 / 人不算多 / 人较多」，组提示「本项目的承接重点」改成「人少、离得近」。
 *   理由是同一条：**"分流""承载""项目"都是我们内部的语言**，
 *   游客站在首页看到它们只会觉得走错了地方。
 *   后端字段名（current_usage / available）不动，只改展示层。
 *
 * ============================================================
 * 2026-09-27 二次重构：从「整幅强调带」改为「一张紧凑卡片」
 * ============================================================
 * 上一版是**贴边整幅带**（`background: warn-50` 铺满视口宽、`padding: 48px 0`），
 * 一条公告就有 ~700px 高，两条叠起来 1400px —— 首页往下滚两屏还看不到
 * "汉中精选目的地"。三个具体病灶，逐条改：
 *
 * **① 正文与候选重复了。** 后端 `DiversionAdvisor.summary()` 就是把每个候选的
 *    `reason` 拼成一段话（"① 甲（距 6.9 km…）；② 乙（距 13.8 km…）"），
 *    而 `reason` 本来就逐条展示在候选上。截图里近一半高度是同一份信息的第二遍。
 *    → 正文**限两行**（`-webkit-line-clamp`），完整原文进 `title` 属性。
 *    **不删正文**：运营可以自己改这段文案（`DiversionServiceImpl` 第 163 行），
 *    删掉就等于把运营写的话吞了；限行则是"版面优先、全文可查"。
 *
 * **② 候选做成了卡片，一张 ~180px 高。** 每个候选只有三个数（名称 / 距离 / 承载），
 *    却占了"标题 + 指标块 + 理由段 + 操作行"四段。→ 改为**紧凑 chip**：
 *    名称一行、`距离 · 承载` 一行，整体约 60px。
 *    chip 的 `title` 放完整 `reason`（含"约 10 分钟车程"与"主打「食用菌、采摘」"）。
 *
 * **③ 贴边整幅带与站点其余部分不在一个体系里。** 首页每个区块都是
 *    `.container`（1240px）内的卡片/栅格，只有公告条是满屏铺色。→ 改为
 *    **容器内的一张卡**：`--r-lg` 圆角 + 左侧 3px 强调条 + 轻阴影，
 *    与 `.card` 同一套语言。**卡片不再自带 `.container` 与上下留白** ——
 *    留白交给父级（首页/详情页各自的 section），这样多条叠起来才不会
 *    每张卡之间空出 96px。
 *
 * **④ 第一次改完后卡片右侧约 45% 是空的。** 上面 ③ 把卡片压进 `.container`
 *    后宽度到了 1176px，而候选只有 3 个、每个 194px —— 剩下两列是空的
 *    （`auto-fill` 会保留空列）。"太高"改完变成了"半张空"。
 *    → 两组**并排**（横向富余、纵向稀缺），每组宽度按候选数分配，
 *    于是跨组的 chip 落在同一套列宽上、铺满整行。**顺带省掉第二组
 *    原本要独占的一整行（≈90px）**。组标签相应从"左侧行头"改回"上方一行"——
 *    并排时它管的是自己那一组，压在组上方比摆在组左侧更清楚。
 *    窄屏（≤720px）再退回上下叠，否则每组只剩 ~150px 放不下 chip。
 *
 * ============================================================
 * 多条公告叠放（首页同时有两处高位时）
 * ============================================================
 * 组件**只吃一条**（`notice`），叠放由父级用一个 grid 容器 + 统一 gap 完成。
 * 不做成"一个组件吃数组、内部画一张大卡"：每条公告的 `expire_at` 与
 * `published_by` 是各自独立的（两条公告多半不是同一个人同一时刻发的），
 * 合并成一张卡就得给每段各写一次这些字段，反而更绕。
 *
 * 视觉上靠三件事让"两条"读起来是一组而不是两页：
 *   · 卡片**同宽同形**（都在 `.container` 里、同一套圆角与强调条）
 *   · 卡间距 `--sp-3`（12px）—— 远小于它们与下一区块的距离，
 *     按接近性原则自然归为一组
 *   · 每张卡**自带完整的标题与来源**，不靠上一张的上下文
 */

import { computed } from 'vue'
import { when } from '@/utils/format'
import type { DiversionCandidate, DiversionNotice } from '@/types'

const props = withDefaults(
  defineProps<{
    notice: DiversionNotice
    /** band = 首页；inline = 资源点详情页（更紧凑一档） */
    variant?: 'band' | 'inline'
  }>(),
  { variant: 'band' }
)

/**
 * 承载占用率 → 游客看得懂的说法。
 *
 * ★ 为什么不直接写百分比：那是运营口径。游客看到"23%"不知道算多算少，
 *   看到"87%"又会以为"满了别去" —— **一个需要用户自己去解释的数字，等于没传达信息**。
 *   换成定性说法，判断成本为零。
 *   阈值 40 / 70 只管"读起来什么感觉"，与后端 `available`（管"还能不能推荐"）
 *   各管一件事，所以两边档位不完全一致也不会自相矛盾。
 *
 * ★ **读不到一律给"—"**，绝不能落进"人少"那一档 —— "不知道"不是"很空"。
 *   这条是原注释就强调过的，换文案时最容易顺手写错。
 */
function usageText(u: number | undefined): string {
  if (u == null) return '—'
  if (u < 0.4) return '人少'
  if (u < 0.7) return '人不算多'
  return '人较多'
}

/** 距离文案：km 保留一位，够用且不啰嗦 */
function kmText(km: number): string {
  return `${km.toFixed(1)} km`
}

/**
 * 当前承载与发布时是否已经明显不同（差 5 个百分点以上才算）。
 * 门槛放在 5pt 是为了不因为四舍五入的抖动把两个数都写出来。
 */
function drifted(c: DiversionCandidate): boolean {
  return c.current_usage != null && Math.abs(c.current_usage - c.usage) >= 0.05
}

/**
 * 分两组：就近乡村、就近景区。
 *
 * 分组顺序就是展示顺序（后端返回的顺序也是这个），所以这里 filter 两次
 * 而不是排序 —— 组内顺序是后端算好的（承载档位 + 距离升序），前端不要重排。
 */
const groups = computed(() =>
  [
    {
      key: 'rural',
      label: '就近乡村',
      // ★ 原来写的是"本项目的承接重点" —— 那是项目内部的定位说法，
      //   游客看到会想"什么项目？"。改成说人少、说距离，这是游客真正在意的两件事。
      hint: '人少、离得近',
      items: props.notice.candidates.filter((c) => c.business_type === 'RURAL_SPOT'),
    },
    {
      key: 'scenic',
      label: '就近景区',
      hint: '换个地方看',
      items: props.notice.candidates.filter((c) => c.business_type !== 'RURAL_SPOT'),
    },
  ].filter((g) => g.items.length > 0)
)
</script>

<template>
  <aside class="dnb" :class="`dnb--${variant}`" role="status">
    <!-- ① 徽标行：分流提示 + 仿真数据标注 -->
    <div class="dnb__top">
      <span class="dnb__badge">
        <!--
          分流图标：一条线分成两股。选它而不是警示三角 ——
          这条公告的语义是"换个去处"，不是"有危险"。
        -->
        <svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true">
          <path
            d="M3 12h6M9 12l4.5-5.5H20M9 12l4.5 5.5H20"
            fill="none"
            stroke="currentColor"
            stroke-width="1.9"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
        今日游览建议
      </span>
      <!--
        仿真数据标注由后端给（notice.synthetic），前端不写死。
        这条文案会被复制、会被截图，标注必须跟着数据源走。
      -->
      <span v-if="notice.synthetic" class="tag tag-gold dnb__sim">演示用仿真数据</span>
    </div>

    <!-- ② 标题 -->
    <h3 class="dnb__title">{{ notice.title }}</h3>

    <!--
      ③ 正文：后端原文，一个字不改，但**限两行**。
      完整原文挂在 title 属性上（鼠标悬停可见），所以运营自己写的长文案
      不会被吞掉 —— 只是默认不占版面。理由见文件头「① 正文与候选重复了」。
    -->
    <p class="dnb__msg" :title="notice.message">{{ notice.message }}</p>

    <!-- ④ 候选：分组并排 + 紧凑 chip -->
    <!--
      两组**并排**而不是上下叠：卡片横向 ~1176px、空间富余，纵向才是稀缺的。
      上下叠时第二组要多花一整行（标签 + chip ≈ 90px），并排后这行被省掉。
      每组宽度按候选数分配（行内 `flex-grow = items.length`），于是
      跨组的 chip 落在同一套列宽上 —— 2 个乡村 + 1 个景区时三张 chip 一样宽，
      不会出现"一组宽、一组窄"或"半张卡空着"。
      阅读顺序仍是乡村在左、景区在右，与后端给的组序（= 运营口径）一致。
    -->
    <div class="dnb__groups">
      <div
        v-for="g in groups"
        :key="g.key"
        class="dnb__group"
        :style="{ flexGrow: g.items.length }"
      >
        <div class="dnb__glabel">
          <span class="dnb__gname">{{ g.label }}</span>
          <span class="dnb__ghint">{{ g.hint }}</span>
        </div>
        <ul class="dnb__chips">
          <li v-for="c in g.items" :key="c.poi_id" class="dnb__chipwrap">
            <!--
              整块就是一个链接，不是"卡片 + 按钮"。
              完整理由（含"约 10 分钟车程"与"主打「…」"）放 title：
              它是可核对的补充说明，不是必须占版面的主信息。
            -->
            <router-link
              class="dnb__chip"
              :class="{ 'is-off': c.available === false }"
              :to="`/poi/${c.poi_id}`"
              :title="c.reason"
            >
              <span class="dnb__chip-top">
                <span class="dnb__chip-name">{{ c.name }}</span>
                <svg class="dnb__chip-go" viewBox="0 0 24 24" width="13" height="13" aria-hidden="true">
                  <path
                    d="M5 12h13M12.5 6l6 6-6 6"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="2"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                  />
                </svg>
              </span>

              <!--
                ★ 这里原来是「12.3 km · 承载 23%」。百分比是运营指标，
                  游客看了不知道算多算少；而且它和"该不该去"之间还隔着一层换算。
                  现在只留一句定性的话（人少 / 人不算多 / 人较多），
                  "承载"这个标签也一并去掉 —— "12.3 km · 人少"已经说完了。
              -->
              <span class="dnb__chip-metrics">
                <span class="num dnb__mv">{{ kmText(c.km) }}</span>
                <i class="dnb__sep" aria-hidden="true" />
                <span class="dnb__mv" :class="{ 'is-unknown': c.current_usage == null }">
                  {{ usageText(c.current_usage) }}
                </span>
                <!-- 读不到承载时说明一句，否则"—"会被当成排版错误 -->
                <span v-if="c.available === false" class="dnb__chip-flag">已不宽裕</span>
              </span>

              <!-- 发布时与当前差得多，两个数都写出来，别拿旧数据骗游客 -->
              <span v-if="drifted(c)" class="dnb__chip-drift">
                发布时{{ usageText(c.usage) }}，现已变化
              </span>
            </router-link>
          </li>
        </ul>
      </div>
    </div>

    <!-- ⑤ 兜底：候选全不宽裕 -->
    <p v-if="notice.available_count === 0" class="dnb__none">
      以上点位当前均已不宽裕，建议错峰出行或咨询景区现场。
    </p>

    <!--
      ⑥ 时间与来源：让"这条提示到什么时候还算数、谁发的"可以被核对。
      公告是有时效的（expire_at），只给标题和正文的话，游客看到一条
      几天前的截图会以为它现在还生效。发布者同理 —— 这是运营审过的版本，
      署上名才对得起"运营审过"这件事。
    -->
    <div class="dnb__meta">
      <span v-if="notice.expire_at" class="dnb__metaitem">
        <svg viewBox="0 0 24 24" width="12" height="12" aria-hidden="true">
          <circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="1.8" />
          <path
            d="M12 7.5V12l3 1.8"
            fill="none"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
        有效至 {{ when(notice.expire_at) }}
      </span>
      <span v-if="notice.published_by" class="dnb__metaitem">
        {{ notice.published_by }} 发布
      </span>
      <span class="dnb__metaitem dnb__metaitem--src">来源：{{ notice.from_poi_name }}</span>
    </div>
  </aside>
</template>

<style scoped>
/*
  ============================================================
  卡片本体
  ============================================================
  与站点 `.card` 同一套语言（圆角 / 轻阴影 / 细边框），
  只多一条左侧 3px 强调条 —— 那是"这是提示、不是普通内容"的唯一标记。
  用 border-left 而不是绝对定位的色块：绝对定位的方块遇到圆角容器
  会被 overflow:hidden 切出一个直角缺口，在小圆角上尤其明显。
*/
.dnb {
  position: relative;
  padding: var(--sp-4) var(--sp-5);
  background: var(--warn-50);
  border: 1px solid var(--gold-300);
  border-left: 3px solid var(--warn);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-1);
}

/* 详情页嵌在正文里，比首页再紧一档 */
.dnb--inline {
  padding: var(--sp-3) var(--sp-4) var(--sp-4);
}

/* ---------- ① 徽标行 ---------- */

.dnb__top {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sp-2);
}

.dnb__badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 22px;
  padding: 0 8px 0 7px;
  border-radius: var(--r-sm);
  background: var(--warn);
  color: #fff;
  font-size: var(--fs-cap);
  font-weight: 600;
  letter-spacing: 0.04em;
}

.dnb__badge svg {
  flex: none;
}

/* 仿真标注压到与徽标同高、同字号，避免两个胶囊高度不一 */
.dnb__sim {
  height: 22px;
  padding: 0 8px;
}

/* ---------- ② 标题 ---------- */

/*
  18px 而不是上一版的 24px（--fs-h2）：24px 是首页区块标题的档位，
  公告借它就和"汉中精选目的地"抢层级了。它比正文大、比区块标题小，
  才是它该在的位置。
*/
.dnb__title {
  margin: var(--sp-2) 0 0;
  font-family: var(--font-display);
  font-size: var(--fs-h3);
  font-weight: 600;
  color: var(--ink-900);
  line-height: 1.5;
  letter-spacing: 0.01em;
}

.dnb--inline .dnb__title {
  font-size: var(--fs-body);
  line-height: 1.55;
}

/* ---------- ③ 正文（限两行） ---------- */

/*
  ★ `-webkit-line-clamp` 三件套缺一不可：
  `display:-webkit-box` + `-webkit-box-orient:vertical` 才会触发多行截断，
  `overflow:hidden` 才会把超出的行藏掉。少了任何一条，文本会照常铺开。
  `-webkit-` 前缀看着像过时写法，但它现在是事实标准（Chromium / WebKit / Firefox 都认），
  没有无前缀替代品。
*/
.dnb__msg {
  margin: var(--sp-2) 0 0;
  max-width: 76ch;
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--ink-500);
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
}

/* ---------- ④ 候选（两组并排） ---------- */

/*
  ★ 组并排，宽度按候选数分配（行内 `flex-grow = items.length`）。
  `flex-basis: 0` + `flex-grow: N` → 宽度严格正比于候选数，
  于是每组内部"平分宽度"的结果恰好等于"全卡所有 chip 平分宽度"，
  跨组的 chip 自然同宽。
*/
.dnb__groups {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-5);
  margin-top: var(--sp-3);
}

.dnb__group {
  flex: 1 1 0;
  min-width: 0;
}

/*
  组标签：组名 + 一句口径说明，横排一行压在 chip 上方，下面一道细线归拢。
  ★ 不换行。两个组的标签一旦一个折成两行，另一个还是单行，
  下面两排 chip 就不在同一水平线上 —— 并排布局最怕这个。
  hint 留 `min-width:0` + 省略号兜底：正常宽度下（≥150px 一组）它不会触发。
*/
.dnb__glabel {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
  margin-bottom: var(--sp-2);
  padding-bottom: 5px;
  border-bottom: 1px solid var(--line);
}

.dnb__gname {
  flex: none;
  font-size: var(--fs-cap);
  font-weight: 600;
  color: var(--ink-700);
  letter-spacing: 0.02em;
  white-space: nowrap;
}

.dnb__ghint {
  min-width: 0;
  font-size: 11px;
  line-height: 1.45;
  color: var(--warm-500);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/*
  ★ `auto-fit` 而不是上一版的 `auto-fill`。
  组已经很窄（一组只占全卡的一部分），`auto-fill` 会保留空列、把 chip 挤在
  左边留下一片空白 —— 上一版整卡右侧 45% 是空的，就是这个原因。
  这里要的是"组内 chip 平分该组宽度"，所以用 `auto-fit`（空轨道塌掉、空间还给
  在用的轨道）。候选多到一行放不下时自动换行，不会横向溢出。
*/
.dnb__chips {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: var(--sp-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.dnb__chipwrap {
  min-width: 0;
}

/* ---------- chip：名称 / 指标（/ 变化） ---------- */

.dnb__chip {
  display: flex;
  flex-direction: column;
  gap: 3px;
  height: 100%;
  padding: 8px 10px 9px 12px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  text-decoration: none;
  transition: border-color var(--dur-2) var(--ease), box-shadow var(--dur-2) var(--ease),
    transform var(--dur-2) var(--ease);
}

.dnb__chip:hover {
  border-color: var(--brand-400);
  box-shadow: var(--sh-2);
  transform: translateY(-1px);
}

.dnb__chip-top {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
  min-width: 0;
}

.dnb__chip-name {
  flex: 1;
  min-width: 0;
  font-size: var(--fs-sm);
  font-weight: 600;
  line-height: 1.5;
  color: var(--ink-900);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 箭头默认很淡，悬停才亮 —— 一排 chip 都顶着箭头会吵 */
.dnb__chip-go {
  flex: none;
  color: var(--warm-400);
  transform: translateX(-2px);
  transition: color var(--dur-2) var(--ease), transform var(--dur-2) var(--ease);
}

.dnb__chip:hover .dnb__chip-go {
  color: var(--brand-500);
  transform: translateX(1px);
}

.dnb__chip-metrics {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-cap);
  line-height: 1.5;
  color: var(--warm-500);
}

.dnb__mv {
  color: var(--brand-700);
  font-weight: 600;
}

/* 读不到承载时用弱化的"—"，不要让它看起来像一个数值 */
.dnb__mv.is-unknown {
  color: var(--warm-400);
  font-weight: 400;
}

.dnb__sep {
  width: 1px;
  height: 9px;
  background: var(--line);
}

/* `.dnb__mk`（原来是"承载"两个字的小标签）已随百分比一起删除 ——
   现在那一格是"人少 / 人不算多 / 人较多"，自解释，不需要前缀标签。 */

.dnb__chip-flag {
  margin-left: auto;
  flex: none;
  color: var(--warn);
  font-weight: 500;
}

.dnb__chip-drift {
  font-size: var(--fs-cap);
  line-height: 1.5;
  color: var(--warn);
}

/* 当前已不宽裕的候选：整卡弱化，但仍然可点 ——
   游客可能就是想看一眼这个点，不该因为"不适合推荐"就不给入口 */
.dnb__chip.is-off {
  background: var(--paper-2);
  border-style: dashed;
}

.dnb__chip.is-off .dnb__mv {
  color: var(--warm-500);
}

.dnb__chip.is-off:hover {
  transform: none;
  box-shadow: var(--sh-1);
}

/* ---------- ⑤ 兜底 ---------- */

.dnb__none {
  margin: var(--sp-3) 0 0;
  padding: var(--sp-2) var(--sp-3);
  border-radius: var(--r-sm);
  background: rgba(255, 255, 255, 0.72);
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--ink-600);
}

/* ---------- ⑥ 时间 / 来源 ---------- */

.dnb__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sp-1) var(--sp-4);
  margin-top: var(--sp-3);
  padding-top: var(--sp-2);
  border-top: 1px dashed var(--line);
}

.dnb__metaitem {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--fs-cap);
  line-height: 1.6;
  color: var(--warm-500);
}

.dnb__metaitem svg {
  flex: none;
  opacity: 0.85;
}

/* 来源单独用墨色：它是这条提示的"主语"（哪个点挤了），不是元信息 */
.dnb__metaitem--src {
  color: var(--ink-500);
  font-weight: 500;
}

/* ---------- 响应式 ---------- */

@media (max-width: 720px) {
  .dnb {
    padding: var(--sp-3) var(--sp-4) var(--sp-4);
  }

  /*
    窄屏恢复"上下叠"：并排时每组只剩约 150px，chip 放不下、
    名字会被截断，反而更难读。
    ★ `flex: 0 0 auto` 是关键 —— 宽屏那条 `.dnb__group { flex: 1 1 0 }`
    里 `flex-basis: 0` 在纵向排列时管的是**高度**，不改回来每组会塌成 0 高。
    行内那句 `flex-grow: <候选数>` 此时无害：纵向容器高度由内容决定，
    没有剩余空间可分配，grow 不起作用。
  */
  .dnb__groups {
    flex-direction: column;
    align-items: stretch;
    gap: var(--sp-3);
  }

  .dnb__group {
    flex: 0 0 auto;
  }

  /* 窄屏两列，再窄一列 —— 交给 auto-fit 会挤成 150px 以下的窄条 */
  .dnb__chips {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 460px) {
  .dnb__chips {
    grid-template-columns: 1fr;
  }
}
</style>
