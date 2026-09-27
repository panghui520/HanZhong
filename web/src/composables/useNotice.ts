import { onUnmounted, ref } from 'vue'

/**
 * 管理端的操作提示条（成功 / 失败），自动消失。
 *
 * 为什么抽出来而不是各页写一份：M6 的「订单处理」页先内联了一份，
 * M5 的「风险与工单」页与驾驶舱也要用。三份各自维护的结果是
 * **消失时长会不一样**（一处 4 秒、一处忘了写 setTimeout 就一直挂着），
 * 而这种差异没人会当成 bug 报上来。
 *
 * 为什么不用 Element Plus 的 ElMessage：项目装了 element-plus，
 * 但管理端这几个页面从头到尾没用过它的组件 —— 提示条的配色是跟着
 * 本项目的深色主题调的（见各页 `.notice--ok` 的样式），
 * 混用两套视觉语言会让同一页上出现两种"成功"的绿。
 *
 * 样式仍需各页自己写（Vue 的 scoped style 不进组合式函数），
 * 但**结构是固定的**：`<div v-if="notice" class="notice" :class="`notice--${notice.type}`">`。
 */
export function useNotice(durationMs = 4000) {
  const notice = ref<{ type: 'ok' | 'err'; text: string } | null>(null)
  let timer: number | undefined

  function say(type: 'ok' | 'err', text: string) {
    notice.value = { type, text }
    // 先清掉上一个计时器：连着两次操作时，若不清理，
    // 第一条提示的计时器会把第二条提示提前关掉
    if (timer !== undefined) window.clearTimeout(timer)
    timer = window.setTimeout(() => (notice.value = null), durationMs)
  }

  /** 手动关掉（用于"我知道了"这类需要用户确认的场景） */
  function dismiss() {
    if (timer !== undefined) window.clearTimeout(timer)
    notice.value = null
  }

  // 组件卸载后计时器还在跑的话，回调里会写一个已销毁组件的 ref。
  // 不报错，但属于泄漏；顺手清掉。
  onUnmounted(() => {
    if (timer !== undefined) window.clearTimeout(timer)
  })

  return { notice, say, dismiss }
}
