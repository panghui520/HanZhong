/**
 * 高德地图 JS API 2.0 的加载器（互动地图页）。
 *
 * 为什么不用 `@amap/amap-jsapi-loader`：
 *   它就是"插一个 script 标签 + 处理安全密钥"，而这里多出来的需求它满足不了 ——
 *   ① 失败要能**重试**（断网演示时网络恢复后重开页面必须能起来）；
 *   ② 失败要给出**能读的原因**（key 没配 / 网络不可达 / 域名未加白名单），
 *      而不是一个 undefined；
 *   ③ 超时要兜底，不能永远转圈。
 *   这三件事一共二十来行，比引一个包再包一层更好调试。
 *
 * 两个容易踩的点，都写在下面代码里：
 *   ★ `window._AMapSecurityConfig` **必须在插入脚本之前**赋值。
 *     JS API 2.0 起，2021-12-02 之后申请的 key 都要配安全密钥，
 *     顺序反了的表现是地图白屏 + 控制台 INVALID_USER_SCODE。
 *   ★ key 必须以 `VITE_` 开头才会被 Vite 内联进产物（见 `.env.example` 的说明）。
 *     写成 `JS_API_KEY` 这类名字，`import.meta.env.JS_API_KEY` 恒为 undefined，
 *     症状是"地图白屏 + 报 key 无效"，很容易误判成 key 本身有问题。
 */

/** 脚本加载超时。断网时 `onerror` 不一定触发（可能一直挂着），所以要有兜底 */
const LOAD_TIMEOUT_MS = 15000

/** 进行中的加载。模块级单例 —— 同一个页面里多个组件要地图时只加载一次 */
let pending: Promise<typeof AMap> | null = null

/** 前端用的 JS API key（「Web 端(JS API)」类型，与 Python 侧的 AMAP_KEY 不同） */
export function amapKey(): string {
  return (import.meta.env.VITE_AMAP_JS_KEY ?? '').trim()
}

/** key 是否已配置。页面据此决定"画地图"还是"显示一句说明" */
export function amapConfigured(): boolean {
  return amapKey().length > 0
}

/**
 * 加载 JS API，resolve 出 `AMap` 命名空间。
 *
 * 失败时**清掉 pending**，这样用户刷新或重试能重新发起 ——
 * 不清的话一次网络抖动就变成"这个页面永久坏了，只能重启开发服务器"。
 */
export function loadAmap(): Promise<typeof AMap> {
  if (pending) return pending

  const key = amapKey()
  if (!key) {
    return Promise.reject(new Error('未配置高德 JS key'))
  }

  pending = new Promise<typeof AMap>((resolve, reject) => {
    // 已经加载过（比如从别的页面切回来）就直接用，不再插第二个 script
    if (window.AMap) {
      resolve(window.AMap)
      return
    }

    // ★ 必须在脚本之前设置，见文件头说明
    const security = (import.meta.env.VITE_AMAP_JS_SECURITY_CODE ?? '').trim()
    if (security) {
      window._AMapSecurityConfig = { securityJsCode: security }
    }

    let settled = false
    const finish = (fn: () => void) => {
      if (settled) return
      settled = true
      window.clearTimeout(timer)
      fn()
    }

    const timer = window.setTimeout(() => {
      finish(() => reject(new Error('高德地图加载超时，请检查网络后重试')))
    }, LOAD_TIMEOUT_MS)

    const script = document.createElement('script')
    // v=2.0 固定住大版本：1.4 与 2.0 的 API 形状不同，跟着默认值走会某天突然变
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}`
    script.async = true
    script.onload = () => {
      finish(() => {
        if (window.AMap) resolve(window.AMap)
        else reject(new Error('高德脚本已加载，但没有挂上 window.AMap'))
      })
    }
    script.onerror = () => {
      finish(() => reject(new Error('高德地图脚本加载失败：网络不可达，或该域名未加入 key 的白名单')))
    }
    document.head.appendChild(script)
  })

  // 失败后允许重试（这条 catch 同时把 rejection 标记为已处理，避免控制台噪音）
  pending.catch(() => {
    pending = null
  })

  return pending
}

/**
 * 逆地理编码：坐标 → 可读地址。
 *
 * 这是**可选增强**，不是必需路径：数据包里的 POI 只有区县，没有门牌级地址。
 * 拿不到就保持区县，卡片不会因此空一块或转圈。
 *
 * 用 `AMap.plugin` 加载 Geocoder —— 它不属于核心类，不 plugin 直接 new 会报错。
 * plugin 的回调只保证"插件可用"，**不保证授权通过**；真正的失败在
 * `getAddress` 的 status 里，所以两处都要兜。
 */
export function reverseGeocode(lng: number, lat: number): Promise<string> {
  return loadAmap()
    .then(
      (AMap) =>
        new Promise<string>((resolve) => {
          AMap.plugin(['AMap.Geocoder'], () => {
            try {
              const geocoder = new AMap.Geocoder({ radius: 300 })
              geocoder.getAddress([lng, lat], (status, result) => {
                const addr = result?.regeocode?.formattedAddress
                resolve(status === 'complete' && addr ? addr : '')
              })
            } catch {
              resolve('')
            }
          })
        })
    )
    .catch(() => '')
}
