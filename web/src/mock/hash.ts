/**
 * 确定性伪随机种子（SIMULATED 数据的地基）
 * ------------------------------------------------------------------
 * 设计要点：**每个片段先各自 FNV-1a 散列，再用 fmix32 混合进累积值**。
 *
 * 为什么不能直接对拼接串做 FNV-1a：
 * FNV-1a 每步是 `h = (h ^ c) * F`，若两个输入只在**最后一位**不同
 * （'t0' / 't1' / 't2'），最终 h 只相差 F = 16777619，归一化后仅差 0.004。
 * 后果是 Math.floor(seed * n) 永远落进同一个桶 —— 实测表现为
 * 「同一页 7 天趋势是一条平线」「两条游客评价文字一模一样」。
 * 只补一轮 fmix32 仍有残留（实测 text 仍聚在 0.49–0.50），
 * 因此改为逐段散列混合，让变化位不落在末尾。
 */
function fmix32(h: number): number {
  h ^= h >>> 16
  h = Math.imul(h, 2246822507)
  h ^= h >>> 13
  h = Math.imul(h, 3266489909)
  h ^= h >>> 16
  return h >>> 0
}

function fnv1a(s: string): number {
  let h = 2166136261
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return h >>> 0
}

/** 把任意多个片段散列成 [0,1) 的确定性随机数 */
export function seed(...parts: (string | number)[]): number {
  let h = 2166136261
  for (const p of parts) {
    h = fmix32(h ^ fnv1a(String(p)))
  }
  return fmix32(h) / 4294967296
}

/** 取 [min,max] 闭区间内的整数 */
export function seedInt(min: number, max: number, ...parts: (string | number)[]): number {
  return min + Math.floor(seed(...parts) * (max - min + 1))
}

/** 从数组里确定性地取一项 */
export function pick<T>(arr: T[], ...parts: (string | number)[]): T {
  return arr[Math.floor(seed(...parts) * arr.length) % arr.length]
}
