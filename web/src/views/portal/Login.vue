<script setup lang="ts">
/**
 * Login —— 独立登录 / 注册页（M8 真实认证）
 *
 * 与整个品牌视觉统一：左侧场景画 + 品牌，右侧表单。
 * 不做成后台登录表单的样子（不居中白卡片压灰底）。
 *
 * ---------------------------------------------------------------
 * 与旧版的根本区别
 * ---------------------------------------------------------------
 * 旧版是纯前端演示：账号密码随便填、密码只要 4 位、身份靠点按钮选、
 * 前端自己存一个 demo 验证码然后本地比对。页面看着像登录，服务端毫不知情。
 *
 * 现在：
 *   - 身份不能选。账号是邮箱，角色由后端 `app_user.role` 决定。
 *     旧版那两个"游客 / 运营"按钮已删除 —— 让用户点一下就能变成管理员，
 *     本身就是这个项目最该修掉的问题。
 *   - 验证码由后端生成、后端校验。前端拿不到真值（响应体里没有），
 *     所以也无从"本地判断对不对"。
 *   - 密码下限 8 位，由后端 `validatePassword` 强制，前端只做即时提示。
 *
 * ---------------------------------------------------------------
 * 两段式注册
 * ---------------------------------------------------------------
 * 邮箱验证码 + 设置密码在**同一个表单**里提交（一个请求）。
 * 拆成两步页面会让"验证完了但没设密码"变成一个需要清理的中间态，
 * 而单请求里服务端本来就能顺序做完校验 → 建用户 → 消耗验证码。
 *
 * ---------------------------------------------------------------
 * 倒计时是体验，不是防线
 * ---------------------------------------------------------------
 * "60 秒冷却"真正的判定在后端（`AuthCodeService.sendCode` 读
 * `created_at + cooldownSeconds`）。前端的倒计时只是让用户知道要等多久 ——
 * 就算把它去掉、或者改系统时间绕开，后端该拒还是拒。
 */
import { computed, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import SceneArt from '@/components/SceneArt.vue'
import { useSessionStore } from '@/stores/session'
import { sendRegisterCode } from '@/api/auth'
import { ApiError } from '@/api/http'

const router = useRouter()
const route = useRoute()
const session = useSessionStore()

const mode = ref<'login' | 'register'>('login')

const email = ref('')
const password = ref('')
const nickname = ref('')
const code = ref('')

const error = ref('')
const busy = ref(false)

/** 验证码发送状态：倒计时秒数 + 提示语 */
const cooldown = ref(0)
const codeHint = ref('')
const sendingCode = ref(false)
let timer: number | undefined

const canSendCode = computed(
  () => cooldown.value === 0 && !sendingCode.value && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.value),
)

onUnmounted(() => {
  if (timer) window.clearInterval(timer)
})

function startCooldown(seconds: number) {
  cooldown.value = seconds
  if (timer) window.clearInterval(timer)
  timer = window.setInterval(() => {
    cooldown.value -= 1
    if (cooldown.value <= 0) {
      cooldown.value = 0
      if (timer) window.clearInterval(timer)
    }
  }, 1000)
}

/**
 * 把后端的错误码翻译成人话。
 *
 * 后端返回的 message 已经是中文且可读（如"验证码不正确，还可尝试 3 次"），
 * 优先用它 —— 因为**它比前端更清楚具体发生了什么**（比如剩余次数）。
 * 这里只兜住"网络不通"和"没有 message"的情况。
 */
function describe(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.code === -1) return '暂时连不上服务，请稍后再试'
    if (e.code === -2) return '服务响应异常，请稍后重试'
    if (e.code === 4102) return '该邮箱已注册，请直接登录'
    if (e.code === 4103) return e.message || '发送过于频繁，请稍后再试'
    if (e.code === 4110) return '邮箱或密码不正确'
    if (e.code === 4111) return '账号状态异常，请联系管理员'
    return e.message || '操作失败，请稍后重试'
  }
  return '操作失败，请稍后重试'
}

async function sendCode() {
  error.value = ''
  codeHint.value = ''
  if (!email.value.trim()) {
    error.value = '请先填写邮箱'
    return
  }
  sendingCode.value = true
  try {
    const r = await sendRegisterCode(email.value.trim())
    codeHint.value = r.message || '验证码已发送，请查收邮箱'
    startCooldown(60)
  } catch (e) {
    // 冷却中后端也会拒（4103）。这时照样起倒计时，免得用户反复点。
    if (e instanceof ApiError && e.code === 4103) startCooldown(60)
    error.value = describe(e)
  } finally {
    sendingCode.value = false
  }
}

async function submit() {
  error.value = ''
  const mail = email.value.trim()

  if (!mail) {
    error.value = '请输入邮箱'
    return
  }
  if (mode.value === 'register' && !code.value.trim()) {
    error.value = '请输入邮箱验证码'
    return
  }
  if (mode.value === 'register' && password.value.length < 8) {
    error.value = '密码至少 8 位'
    return
  }
  if (!password.value) {
    error.value = '请输入密码'
    return
  }

  busy.value = true
  try {
    if (mode.value === 'login') {
      await session.login(mail, password.value)
    } else {
      await session.register(mail, code.value.trim(), password.value, nickname.value.trim() || undefined)
    }
    const redirect = route.query.redirect as string | undefined
    router.replace(redirect || (session.isAdmin ? '/admin/dashboard' : '/'))
  } catch (e) {
    error.value = describe(e)
  } finally {
    busy.value = false
  }
}

function switchMode(m: 'login' | 'register') {
  mode.value = m
  error.value = ''
  codeHint.value = ''
  code.value = ''
}
</script>

<template>
  <div class="login">
    <!-- 左：场景画 -->
    <aside class="login__art">
      <!-- 左侧是窄高容器（约 1:1.6），SceneArt 用 slice 裁切后主体会被截掉，
           所以这里用竖构图更友好的 rapeseed（油菜花田，层次集中在中下部），
           而不是横向铺开的古建群。 -->
      <SceneArt variant="rapeseed" ratio="auto" class="login__scene" />
      <div class="login__veil" />
      <div class="login__art-inner">
        <router-link to="/" class="brand brand--light">
          <span class="brand__seal">汉</span>
          <span class="brand__text">
            <span class="brand__name">汉游智脑</span>
            <span class="brand__sub">HANYOU BRAIN</span>
          </span>
        </router-link>
        <div class="login__claim">
          <span class="eyebrow eyebrow--light">智慧文旅 · 乡村振兴</span>
          <p class="login__claim-title">把游客的一次到访，<br />变成一条持续的乡村消费链</p>
          <p class="login__claim-desc">
            面向游客的智能规划与知识问答，面向运营方的承载力预警、分流工单与消费归因。
          </p>
        </div>
        <div class="login__badges">
          <span class="login__badge">AI 智能行程规划</span>
          <span class="login__badge">乡村体验预约</span>
          <span class="login__badge">离境复购追踪</span>
        </div>
      </div>
    </aside>

    <!-- 右：表单 -->
    <main class="login__panel">
      <div class="login__form-wrap">
        <router-link to="/" class="login__back btn-text">返回首页</router-link>

        <h1 class="h1 login__title">
          {{ mode === 'login' ? '欢迎回来' : '创建账号' }}
        </h1>
        <p class="login__sub">
          {{
            mode === 'login'
              ? '登录后可保存行程、追踪乡村体验与复购记录'
              : '使用邮箱注册，验证码将发送到你的邮箱'
          }}
        </p>

        <form class="login__form" @submit.prevent="submit">
          <label class="field">
            <span class="field__label">邮箱</span>
            <input
              v-model="email"
              class="field__input"
              type="email"
              placeholder="例如：you@example.com"
              autocomplete="username"
            />
          </label>

          <label v-if="mode === 'register'" class="field">
            <span class="field__label">邮箱验证码</span>
            <div class="field__row">
              <input
                v-model="code"
                class="field__input"
                inputmode="numeric"
                maxlength="6"
                placeholder="6 位数字"
                autocomplete="one-time-code"
              />
              <button
                class="btn btn-ghost field__btn"
                type="button"
                :disabled="!canSendCode"
                @click="sendCode"
              >
                {{ cooldown > 0 ? `${cooldown} 秒后重发` : sendingCode ? '发送中…' : '发送验证码' }}
              </button>
            </div>
            <span v-if="codeHint" class="field__hint">{{ codeHint }}</span>
          </label>

          <label class="field">
            <span class="field__label">密码</span>
            <input
              v-model="password"
              class="field__input"
              type="password"
              :placeholder="mode === 'register' ? '至少 8 位' : '请输入密码'"
              :autocomplete="mode === 'register' ? 'new-password' : 'current-password'"
            />
          </label>

          <label v-if="mode === 'register'" class="field">
            <span class="field__label">昵称（选填）</span>
            <input
              v-model="nickname"
              class="field__input"
              placeholder="不填则用邮箱前缀"
              autocomplete="nickname"
            />
          </label>

          <p v-if="error" class="login__error">{{ error }}</p>

          <button class="btn btn-primary btn-lg login__submit" type="submit" :disabled="busy">
            {{ busy ? '处理中…' : mode === 'login' ? '登录' : '注册并进入' }}
          </button>
        </form>

        <div class="login__foot">
          <button
            class="btn-text"
            @click="switchMode(mode === 'login' ? 'register' : 'login')"
          >
            {{ mode === 'login' ? '还没有账号？立即注册' : '已有账号？返回登录' }}
          </button>
          <p class="login__note">
            登录状态由服务端签发的令牌确认，密码经 BCrypt 哈希后存储，不会以明文保存。
            运营管理端需由管理员账号访问，普通注册无法获得该权限。
          </p>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.login {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 1.05fr 1fr;
  background: var(--paper);
}

/* ---------- 左：场景 ---------- */
.login__art {
  position: relative;
  overflow: hidden;
  background: var(--brand-900);
}
.login__scene {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  border-radius: 0;
}
.login__veil {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    180deg,
    rgba(11, 33, 25, 0.72) 0%,
    rgba(11, 33, 25, 0.42) 45%,
    rgba(11, 33, 25, 0.8) 100%
  );
}
.login__art-inner {
  position: relative;
  height: 100%;
  padding: var(--sp-8) var(--sp-9);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  color: #fff;
}
.brand--light .brand__name {
  color: #fff;
}
.brand--light .brand__sub {
  color: rgba(255, 255, 255, 0.6);
}
.login__claim {
  max-width: 440px;
}
.login__claim-title {
  margin-top: var(--sp-5);
  font-family: var(--font-display);
  font-size: 30px;
  line-height: 1.5;
  letter-spacing: 0.02em;
}
.login__claim-desc {
  margin-top: var(--sp-5);
  font-size: var(--fs-sm);
  line-height: 1.9;
  color: rgba(255, 255, 255, 0.74);
}
.login__badges {
  display: flex;
  gap: var(--sp-2);
  flex-wrap: wrap;
}
.login__badge {
  padding: 5px 12px;
  font-size: var(--fs-cap);
  letter-spacing: 0.04em;
  color: rgba(255, 255, 255, 0.86);
  border: 1px solid rgba(255, 255, 255, 0.26);
  border-radius: var(--r-sm);
}

/* ---------- 右：表单 ---------- */
.login__panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--sp-9) var(--sp-7);
}
.login__form-wrap {
  width: 100%;
  max-width: 400px;
}
.login__back {
  margin-bottom: var(--sp-7);
}

.login__title {
  color: var(--ink-900);
}
.login__sub {
  margin-top: var(--sp-3);
  font-size: var(--fs-sm);
  line-height: 1.8;
  color: var(--ink-500);
}

.login__form {
  margin-top: var(--sp-7);
  display: flex;
  flex-direction: column;
  gap: var(--sp-5);
}
.field {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
.field__label {
  font-size: var(--fs-xs);
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--ink-600);
}
.field__input {
  height: 46px;
  padding: 0 var(--sp-4);
  background: #fff;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  font-size: var(--fs-sm);
  outline: none;
  transition: border-color var(--dur-1) var(--ease), box-shadow var(--dur-1) var(--ease);
}
.field__input::placeholder {
  color: var(--warm-400);
}
.field__input:focus {
  border-color: var(--brand-500);
  box-shadow: 0 0 0 3px rgba(42, 111, 91, 0.1);
}

/* 验证码一行：输入框自适应 + 按钮定宽 */
.field__row {
  display: flex;
  gap: var(--sp-3);
}
.field__row .field__input {
  flex: 1;
  min-width: 0;
}
.field__btn {
  flex: 0 0 auto;
  height: 46px;
  padding: 0 var(--sp-4);
  white-space: nowrap;
}
.field__btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.field__hint {
  font-size: var(--fs-cap);
  line-height: 1.6;
  color: var(--brand-600);
}

.login__error {
  font-size: var(--fs-xs);
  color: var(--danger);
}
.login__submit {
  width: 100%;
  margin-top: var(--sp-1);
}

.login__foot {
  margin-top: var(--sp-6);
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}
.login__note {
  font-size: var(--fs-cap);
  line-height: 1.7;
  color: var(--warm-500);
  padding-top: var(--sp-4);
  border-top: 1px solid var(--line-soft);
}

/* ---------- 响应式 ---------- */
@media (max-width: 1080px) {
  .login {
    grid-template-columns: 1fr;
  }
  .login__art {
    display: none;
  }
  .login__panel {
    padding: var(--sp-7) var(--sp-5);
  }
}

/* 1366×768：压低纵向节奏，保证表单完整可见 */
@media (max-height: 820px) and (min-width: 1081px) {
  .login__art-inner {
    padding: var(--sp-7) var(--sp-8);
  }
  .login__claim-title {
    font-size: 26px;
  }
  .login__panel {
    padding: var(--sp-7) var(--sp-6);
  }
  .login__back {
    margin-bottom: var(--sp-5);
  }
  .login__form {
    margin-top: var(--sp-5);
    gap: var(--sp-4);
  }
}
</style>
