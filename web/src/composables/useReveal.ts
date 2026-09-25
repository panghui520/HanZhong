import { nextTick, onUnmounted, watch, type Ref } from 'vue'

/**
 * 滚动进入视口淡入（`.reveal` → `.is-in`）。
 *
 * 抽成 composable 是因为这套机制有三个**必须同时成立**的细节，
 * 抄第二遍就一定会有人漏掉其中一条，然后表现为"页面上有一块是空的"：
 *
 * 1. **阈值不能用固定比例**。`threshold: 0.12` 对小元素没问题，
 *    但页面里有 500px 高的大区块，在 1050px 高的视口里即使完全可见
 *    也未必达到那个交叉比 —— 元素会永远停在 `opacity: 0`。
 *    正确做法是 `threshold: 0` + 负 `rootMargin`（"刚进视口就算"，延迟交给 rootMargin 控制）。
 *
 * 2. **元素可能在数据到达之前还不存在**。这些 `.reveal` 大多在
 *    `v-else` / `v-if="!loading"` 分支里，`onMounted` 时 `loading` 还是 `true`，
 *    此时 `querySelectorAll` 拿到的是空集，观察器等于没挂。
 *
 * 3. **元素可能在数据到达之后才出现**（这一条最容易漏）。一个页面可能同时发
 *    多个互不相关的请求，先到的那个把 `loading` 置了 false、触发了重新扫描，
 *    而由**后到的**请求渲染出来的区块从没被扫到过。
 *    踩过一次真实的：/assistant 的「快捷问题」依赖 `getSuggestions()`，
 *    它比 `getCityPack()` 晚返回，那一块就永远停在 opacity: 0。
 *
 * 所以第 3 条不能靠"多传几个信号"来补 —— 那要求调用方穷举所有异步源，
 * 漏一个就复现一次。这里改用 **MutationObserver 盯着根节点**：
 * 任何新插入的 `.reveal` 都会被自动接管。信号（loading / extra）保留，
 * 但只是"提前扫一次"的优化，正确性由观察器兜底。
 *
 * 用法：
 * ```ts
 * const root = ref<HTMLElement | null>(null)
 * useReveal(root)
 * ```
 * 模板根元素绑 `ref="root"`，要淡入的元素加 `class="reveal"`。
 *
 * @param root    页面根元素（在它内部 `querySelectorAll('.reveal')`）
 * @param loading 可选的异步加载状态；由 true → false 时提前扫一次
 * @param extra   可选的额外"该重新扫描了"信号（如筛选条件变化）
 */
export function useReveal(
  root: Ref<HTMLElement | null>,
  loading?: Ref<boolean>,
  extra?: Ref<unknown>
) {
  let io: IntersectionObserver | undefined
  let mo: MutationObserver | undefined
  let queued = false

  /** 把当前在视口内的元素立刻点亮。
   *  observer 首次回调有延迟，且元素比视口还高时交叉判定可能不符直觉 */
  function lightVisible() {
    root.value?.querySelectorAll<HTMLElement>('.reveal:not(.is-in)').forEach((el) => {
      const r = el.getBoundingClientRect()
      if (r.top < window.innerHeight && r.bottom > 0) el.classList.add('is-in')
    })
  }

  function scan() {
    const els = root.value?.querySelectorAll<HTMLElement>('.reveal:not(.is-in)')
    if (!els || !els.length) return

    // 环境不支持（或 SSR）时直接点亮，宁可没有动画也不要留白屏
    if (typeof IntersectionObserver === 'undefined') {
      els.forEach((el) => el.classList.add('is-in'))
      return
    }

    const vh = window.innerHeight
    io = new IntersectionObserver(
      (entries) => {
        entries.forEach((e) => {
          if (e.isIntersecting) {
            e.target.classList.add('is-in')
            io?.unobserve(e.target)
          }
        })
      },
      { threshold: 0, rootMargin: `0px 0px -${Math.max(60, Math.round(vh * 0.1))}px 0px` }
    )
    els.forEach((el) => io!.observe(el))

    requestAnimationFrame(lightVisible)
  }

  /** 合并同一帧内的多次变更，避免频繁重扫 */
  function scheduleScan() {
    if (queued) return
    queued = true
    requestAnimationFrame(() => {
      queued = false
      scan()
    })
  }

  function start() {
    // 每次重扫前断开旧的：否则已销毁的节点会留在观察列表里
    io?.disconnect()
    scan()

    if (!mo && root.value && typeof MutationObserver !== 'undefined') {
      mo = new MutationObserver(scheduleScan)
      // 只盯 childList：给元素加 is-in 是改 attribute，不会触发这里，因此不会自激
      mo.observe(root.value, { childList: true, subtree: true })
    }
  }

  if (loading) {
    watch(loading, (v) => {
      if (!v) nextTick(start)
    })
  }
  if (extra) {
    watch(extra, () => nextTick(start))
  }
  nextTick(start)

  onUnmounted(() => {
    io?.disconnect()
    mo?.disconnect()
  })

  return { observe: start }
}
