import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getOrderCount } from '@/api/order'
import { useSessionStore } from './session'

/**
 * 订单角标（M6）。
 *
 * 与 cart store 是同一套写法、同一个理由：只存**数字**，不存订单内容。
 * 订单内容归 `Orders.vue` / `OrderDetail.vue` 自己管，它们要的是完整明细
 * （含快照、物流、评价），跟"顶栏显示几个待办"完全是两回事。
 * 把明细也塞进 store，就会出现"顶栏数字和订单页看到的对不上"
 * 这种经典问题：两处各自维护一份、各自更新。
 *
 * 这里的状态只有一个来源：服务端的 `/api/orders/count`。
 * 任何改变订单状态的动作（下单、付款、取消、确认收货、申请退款）
 * 都调一次 `refresh()`，而不是在本地做 `pending - 1` 猜 ——
 * 猜出来的数字在"操作失败"或"运营那边刚好也改了状态"时会和服务端不一致。
 *
 * **角标显示的是 pending（待付款 + 已发货），不是 total。**
 * 口径由服务端定，前端不自己数（见 OrderCount 的注释）。
 */
export const useOrderStore = defineStore('order', () => {
  const pending = ref(0)
  const total = ref(0)

  /**
   * 向服务端要一次真实计数。
   *
   * 未登录时直接置 0 且**不发请求** —— 后端会返回 4001，
   * 而 4001 会触发全局登出逻辑，让一个还没登录的游客莫名其妙
   * 走一遍"会话失效"流程。这是刻意的分支，不是省事。
   *
   * 请求失败也置 0：角标是装饰性信息，为它弹错误提示只会打扰用户。
   */
  async function refresh() {
    const session = useSessionStore()
    if (!session.isLoggedIn) {
      pending.value = 0
      total.value = 0
      return
    }
    try {
      const c = await getOrderCount()
      pending.value = c?.pending ?? 0
      total.value = c?.total ?? 0
    } catch {
      pending.value = 0
      total.value = 0
    }
  }

  /** 退出登录时清掉，避免下一个登录的人看到上一个人的角标 */
  function reset() {
    pending.value = 0
    total.value = 0
  }

  return { pending, total, refresh, reset }
})
