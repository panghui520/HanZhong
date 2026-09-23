import { ref, shallowRef, type Ref } from 'vue'

export type AsyncState<T> = {
  data: Ref<T | null>
  loading: Ref<boolean>
  error: Ref<string | null>
  reload: () => Promise<void>
}

/**
 * 统一的异步状态：加载 / 成功 / 失败 / 空数据
 * 页面不各自写 loading 逻辑，保证交互反馈一致。
 */
export function useAsync<T>(fetcher: () => Promise<T>, immediate = true): AsyncState<T> {
  const data = shallowRef<T | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function run() {
    loading.value = true
    error.value = null
    try {
      data.value = await fetcher()
    } catch (e) {
      error.value = e instanceof Error ? e.message : '未知错误'
      data.value = null
    } finally {
      loading.value = false
    }
  }

  if (immediate) void run()
  return { data, loading, error, reload: run }
}

/** 空数据判断：数组为空或对象为 null 都算空 */
export function isEmpty(v: unknown): boolean {
  if (v == null) return true
  if (Array.isArray(v)) return v.length === 0
  if (typeof v === 'object') return Object.keys(v as object).length === 0
  return false
}
