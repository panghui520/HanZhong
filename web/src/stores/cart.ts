import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getCartCount } from '@/api/order'
import { useSessionStore } from './session'

/**
 * 购物车角标（M6）。
 *
 * 只存**一个数字**，不存购物车内容 —— 内容归 `Cart.vue` 自己管，
 * 它需要的是完整明细（含失效行、库存、锚点），跟"顶栏显示几件"完全是两回事。
 * 把明细也塞进 store，就会出现"顶栏的数字和购物车页面看到的对不上"
 * 这种经典问题：两处各自维护一份、各自更新。
 *
 * 这里的状态只有一个来源：服务端的 `/api/cart/count`。
 * 任何改变购物车的动作（加购、改数量、删除、清空、下单成功）
 * 都调一次 `refresh()`，而不是在本地做 `count + 1` 猜。
 * 猜出来的数字在"加购失败"或"库存被扣到 0"时会和服务端不一致。
 */
export const useCartStore = defineStore('cart', () => {
  const count = ref(0)

  /**
   * 向服务端要一次真实件数。
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
      count.value = 0
      return
    }
    try {
      count.value = await getCartCount()
    } catch {
      count.value = 0
    }
  }

  /** 退出登录时清掉，避免下一个登录的人看到上一个人的角标 */
  function reset() {
    count.value = 0
  }

  return { count, refresh, reset }
})
