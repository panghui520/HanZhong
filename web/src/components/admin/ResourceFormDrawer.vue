<script setup lang="ts">
/**
 * 资源编辑抽屉（景点 / 餐饮 / 住宿 / 农产品共用）
 *
 * 用抽屉而不是整页表单：运营的动线是"看到一行 → 改一下 → 回到列表"，
 * 整页跳转会把列表滚回顶部、丢掉筛选条件，改一条要重新找一遍位置。
 *
 * ★ **数据包资源的"编辑"是接管，不是解锁**（`§二十一` 的权限红线）。
 *   这里把确认弹窗放在**打开抽屉之前**，而不是保存时：
 *   保存时才弹的话，用户已经填完一整张表单，点"确定"才发现这一步不可逆 ——
 *   这时他要么接受、要么白填。先说清后果再让他动手，是唯一合理的顺序。
 *
 * ★ **但"接管"只对景点成立。** 农产品（product）后端走 `requireAdminOwned`，
 *   PACK 行必被 1108 拒 —— 前端若照样弹"接管后即可编辑"，就是**承诺一个后端
 *   不认的能力**（这正是收口审查第 ③ 条）。所以这里按 `isPoi` 分叉：
 *   农产品 PACK 行**只提示、不进表单**。按钮上的 disabled 只是 UI 层的，
 *   键盘回车/程序触发绕得过，守卫必须在这一层。
 *
 * ★ 下拉数据（景点 / 体验 / 分类）**在本组件内取一次**。四个页面都要它，
 *   放在页面里就是复制四份；放在这里，只有真正打开过编辑的页面才会发这几个请求。
 */
import { computed, ref } from 'vue'
import { getExperiences, getPois, getProductCategories } from '@/api/citypack'
import { SCENE_LABELS, SCENE_VARIANTS } from '@/utils/scene'
import {
  BUSINESS_LABEL,
  type AdminPoi,
  type AdminProduct,
  type Experience,
  type Poi,
  type ProductCategory,
} from '@/types'

const props = withDefaults(
  defineProps<{
    /** 'poi' 走景点/餐饮/住宿那套字段；'product' 走农产品那套 */
    isPoi: boolean
    /** 业态默认值。新建时用（景点页 SCENIC、餐饮页 FOOD、住宿页 LODGING） */
    defaultBizType: string
    /**
     * 业态可选范围。**必须收窄到本页负责的业态** ——
     * 在住宿页把业态改成"景区"，会建出一条立刻从本页消失的资源，
     * 运营会以为没建成功。
     */
    bizOptions?: string[]
    busy?: boolean
  }>(),
  { bizOptions: () => [], busy: false }
)

const emit = defineEmits<{
  (e: 'save', body: Record<string, unknown>, editingId: string | null): void
}>()

const SCENES = SCENE_VARIANTS.map((value) => ({ value, label: SCENE_LABELS[value] }))

const open = ref(false)
const editingId = ref<string | null>(null)

interface PoiForm {
  name: string
  business_type: string
  district: string
  address: string
  level: string
  phone: string
  lng: string
  lat: string
  ticket_price: string
  open_hours: string
  duration_min: string
  capacity: string
  warning_threshold: string
  tags: string
  summary: string
  detail: string
  scene: string
}

interface ProductForm {
  name: string
  category_code: string
  spec: string
  price: string
  origin_village: string
  stock: string
  poi_id: string
  experience_id: string
  tags: string
  story: string
  scene: string
}

function emptyPoiForm(): PoiForm {
  return {
    name: '',
    // 不写死 'SCENIC'：在餐饮/住宿页新建时默认值必须是本页业态，
    // 否则会建出一条跑到别的页面去的资源
    business_type: props.defaultBizType,
    district: '',
    address: '',
    level: '',
    phone: '',
    lng: '',
    lat: '',
    ticket_price: '',
    open_hours: '',
    duration_min: '',
    capacity: '',
    warning_threshold: '',
    tags: '',
    summary: '',
    detail: '',
    scene: 'qinling',
  }
}

function emptyProductForm(): ProductForm {
  return {
    name: '',
    category_code: '',
    spec: '',
    price: '',
    origin_village: '',
    stock: '',
    poi_id: '',
    experience_id: '',
    tags: '',
    story: '',
    scene: 'terrace',
  }
}

const poiForm = ref<PoiForm>(emptyPoiForm())
const productForm = ref<ProductForm>(emptyProductForm())

// ---- 下拉数据（首次打开时才取）----
const allPois = ref<Poi[]>([])
const experiences = ref<Experience[]>([])
const categories = ref<ProductCategory[]>([])
const loadedOptions = ref(false)

async function ensureOptions() {
  if (loadedOptions.value) return
  loadedOptions.value = true
  try {
    const [pois, exps, cats] = await Promise.all([
      getPois(),
      getExperiences(),
      getProductCategories(),
    ])
    allPois.value = pois
    experiences.value = exps
    categories.value = cats
  } catch {
    /* 忽略：表单下拉留空，不影响列表与上下架 */
  }
}

const ruralPois = computed(() => allPois.value.filter((p) => p.business_type === 'RURAL_SPOT'))

/** 农产品表单里，选中产地后只显示该产地下的体验 —— 挂一个不相干的体验是常见误操作 */
const experienceOptions = computed(() => {
  const pid = productForm.value.poi_id
  return pid ? experiences.value.filter((e) => e.poi_id === pid) : experiences.value
})

/**
 * 打开抽屉。
 *
 * `row = null` 表示新建。传行进来时会先做一次**接管确认**（仅景点类的 PACK 行），
 * 用户点"取消"就什么都不发生。
 */
async function openFor(row: AdminPoi | AdminProduct | null) {
  void ensureOptions()

  if (row && row.source === 'PACK') {
    if (!props.isPoi) {
      // ★ 农产品**没有接管机制**（只有景点有）。后端 `updateProduct` 走
      //   `requireAdminOwned`，PACK 行必被 1108 拒。按钮本身已经 disabled，
      //   但 disabled 只是 UI 层的 —— 键盘回车、程序触发都可能走到这里。
      //   所以再挡一道：**只提示，不进表单**。绝不能让运营填完一整张表单，
      //   点保存时才收到"不支持编辑农产品"。
      window.alert(
        `「${row.name}」来自城市数据包，当前版本不支持编辑农产品。\n\n` +
          `· 农产品没有"接管"机制（景点才有），后端会拒绝保存\n` +
          `· 想改内容请改城市数据包；想让它从游客端消失，请用「下架」`
      )
      return
    }
    const ok = window.confirm(
      `「${row.name}」来自城市数据包。\n\n` +
        `继续编辑会把它接管为运营维护的资源：\n` +
        `· 修改会保存到数据库，重启后端也不会被覆盖\n` +
        `· 此后数据包更新这条资源的信息，不会再同步过来\n` +
        `· 资源编码不变（仍是 ${row.id}），订单与足迹的引用不受影响\n\n` +
        `确定要接管并编辑吗？`
    )
    if (!ok) return
  }

  editingId.value = row?.id ?? null

  if (props.isPoi) {
    if (!row) {
      poiForm.value = emptyPoiForm()
    } else {
      const p = row as AdminPoi
      poiForm.value = {
        name: p.name,
        business_type: p.business_type,
        district: p.district ?? '',
        address: p.address ?? '',
        level: p.level ?? '',
        phone: p.phone ?? '',
        lng: p.lng == null ? '' : String(p.lng),
        lat: p.lat == null ? '' : String(p.lat),
        ticket_price: p.ticket_price == null ? '' : String(p.ticket_price),
        open_hours: p.open_hours ?? '',
        duration_min: p.duration_min == null ? '' : String(p.duration_min),
        capacity: p.capacity == null ? '' : String(p.capacity),
        warning_threshold: p.warning_threshold == null ? '' : String(p.warning_threshold),
        tags: (p.tags ?? []).join(', '),
        summary: p.summary ?? '',
        detail: p.detail ?? '',
        scene: p.scene ?? 'qinling',
      }
    }
  } else if (!row) {
    productForm.value = emptyProductForm()
  } else {
    const d = row as AdminProduct
    productForm.value = {
      name: d.name,
      category_code: d.category_code ?? '',
      spec: d.spec ?? '',
      price: d.price == null ? '' : String(d.price),
      origin_village: d.origin_village ?? '',
      stock: d.stock == null ? '' : String(d.stock),
      poi_id: d.poi_id ?? '',
      experience_id: d.experience_id ?? '',
      tags: (d.tags ?? []).join(', '),
      story: d.story ?? '',
      scene: d.scene ?? 'terrace',
    }
  }
  open.value = true
}

function close() {
  open.value = false
  editingId.value = null
}

/** 逗号分隔（中英文都认）→ 数组。空数组会让后端把 tags 存成 NULL，这是期望的 */
function splitTags(raw: string): string[] {
  return raw
    .split(/[,，]/)
    .map((s) => s.trim())
    .filter(Boolean)
}

function numOrNull(raw: string): number | null {
  return raw.trim() === '' ? null : Number(raw)
}

function numOrZero(raw: string): number {
  return raw.trim() === '' ? 0 : Number(raw)
}

function submit() {
  if (props.isPoi) {
    const f = poiForm.value
    emit(
      'save',
      {
        name: f.name,
        business_type: f.business_type,
        district: f.district,
        address: f.address,
        level: f.level,
        phone: f.phone,
        lng: numOrNull(f.lng),
        lat: numOrNull(f.lat),
        ticket_price: numOrZero(f.ticket_price),
        open_hours: f.open_hours,
        duration_min: numOrZero(f.duration_min),
        capacity: numOrZero(f.capacity),
        // 空串 → null。后端把 null 当"清空这一列"（回到全局阈值），
        // 而不是"没传" —— 所以必须显式带上这个键，不能靠省略
        warning_threshold: numOrNull(f.warning_threshold),
        tags: splitTags(f.tags),
        summary: f.summary,
        detail: f.detail,
        scene: f.scene,
      },
      editingId.value
    )
  } else {
    const f = productForm.value
    emit(
      'save',
      {
        name: f.name,
        category_code: f.category_code,
        spec: f.spec,
        price: numOrZero(f.price),
        origin_village: f.origin_village,
        stock: numOrZero(f.stock),
        poi_id: f.poi_id,
        experience_id: f.experience_id,
        tags: splitTags(f.tags),
        story: f.story,
        scene: f.scene,
      },
      editingId.value
    )
  }
}

// `close` 也要暴露：保存成功后由**页面**决定何时关（它要等接口回来）。
// 抽屉自己关的话，接口失败时用户会看到抽屉消失、提示条说失败、表单内容全丢。
defineExpose({ openFor, close })
</script>

<template>
  <Transition name="adm-fade">
    <div v-if="open" class="drawer" @click.self="close">
      <div class="drawer__box drawer__box--wide">
        <div class="drawer__head">
          <h3 class="h3">
            {{ editingId ? '编辑' : '新增' }}{{ isPoi ? '资源' : '农产品' }}
            <span v-if="editingId" class="muted small">{{ editingId }}</span>
          </h3>
          <button class="btn btn-ghost btn-sm" @click="close">关闭</button>
        </div>

        <div class="drawer__body stack-3">
          <template v-if="isPoi">
            <label class="fld">
              <span class="fld__label">名称 *</span>
              <input v-model="poiForm.name" class="field" placeholder="如：石门栈道风景区" />
            </label>
            <div class="fld__pair">
              <label class="fld">
                <span class="fld__label">业态</span>
                <select v-model="poiForm.business_type" class="field">
                  <option v-for="k in props.bizOptions" :key="k" :value="k">
                    {{ BUSINESS_LABEL[k as keyof typeof BUSINESS_LABEL] }}
                  </option>
                </select>
              </label>
              <label class="fld">
                <span class="fld__label">区县</span>
                <input v-model="poiForm.district" class="field" placeholder="如：留坝县" />
              </label>
            </div>
            <div class="fld__pair">
              <label class="fld">
                <span class="fld__label">详细地址</span>
                <input
                  v-model="poiForm.address"
                  class="field"
                  placeholder="如：汉台区河东店镇石门栈道风景区"
                />
              </label>
              <label class="fld">
                <span class="fld__label">联系电话</span>
                <input v-model="poiForm.phone" class="field" placeholder="如：0916-1234567" />
              </label>
            </div>
            <div class="fld__pair">
              <label class="fld">
                <span class="fld__label">等级 / 称号</span>
                <input v-model="poiForm.level" class="field" placeholder="如：4A 级景区" />
              </label>
              <label class="fld">
                <span class="fld__label">开放时间</span>
                <input v-model="poiForm.open_hours" class="field" placeholder="如：08:30-17:30" />
              </label>
            </div>
            <div class="fld__pair">
              <label class="fld">
                <span class="fld__label">经度</span>
                <input v-model="poiForm.lng" class="field" placeholder="107.03" />
              </label>
              <label class="fld">
                <span class="fld__label">纬度</span>
                <input v-model="poiForm.lat" class="field" placeholder="33.07" />
              </label>
            </div>
            <div class="fld__pair">
              <label class="fld">
                <span class="fld__label">门票 / 人均（元）</span>
                <input v-model="poiForm.ticket_price" class="field" placeholder="0" />
              </label>
              <label class="fld">
                <span class="fld__label">建议时长（分钟）</span>
                <input v-model="poiForm.duration_min" class="field" placeholder="90" />
              </label>
            </div>
            <div class="fld__pair">
              <label class="fld">
                <span class="fld__label">承载上限（人）</span>
                <input v-model="poiForm.capacity" class="field" placeholder="2000" />
              </label>
              <label class="fld">
                <span class="fld__label">预警阈值（承载率）</span>
                <input
                  v-model="poiForm.warning_threshold"
                  class="field"
                  placeholder="留空 = 用全局阈值"
                />
              </label>
            </div>
            <p class="fld__hint">
              预警阈值填 0.01~1.00 之间的小数（0.8 表示到八成触发预警）。留空则沿用
              运营分析里配置的全局阈值。它只影响「承载接近上限」这一档告警；
              「已超载」那一档永远按 100% 判，不受这里影响。
            </p>
            <label class="fld">
              <span class="fld__label">标签（逗号分隔）</span>
              <input v-model="poiForm.tags" class="field" placeholder="栈道, 三国, 亲子" />
            </label>
            <label class="fld">
              <span class="fld__label">简介（一句话，列表与卡片用）</span>
              <textarea v-model="poiForm.summary" class="field field--area" rows="3" />
            </label>
            <label class="fld">
              <span class="fld__label">详细介绍（详情页正文）</span>
              <textarea
                v-model="poiForm.detail"
                class="field field--area"
                rows="6"
                placeholder="历史沿革、看点、游览建议等。留空则详情页只显示上面的简介。"
              />
            </label>
            <label class="fld">
              <span class="fld__label">封面插画（未上传配图时的兜底画面）</span>
              <select v-model="poiForm.scene" class="field">
                <option v-for="s in SCENES" :key="s.value" :value="s.value">{{ s.label }}</option>
              </select>
            </label>
          </template>

          <template v-else>
            <label class="fld">
              <span class="fld__label">名称 *</span>
              <input v-model="productForm.name" class="field" placeholder="如：留坝西洋参片" />
            </label>
            <div class="fld__pair">
              <label class="fld">
                <span class="fld__label">分类 *</span>
                <select v-model="productForm.category_code" class="field">
                  <option value="">请选择</option>
                  <option v-for="c in categories" :key="c.code" :value="c.code">
                    {{ c.name }}
                  </option>
                </select>
              </label>
              <label class="fld">
                <span class="fld__label">规格</span>
                <input v-model="productForm.spec" class="field" placeholder="如：100g / 罐" />
              </label>
            </div>
            <div class="fld__pair">
              <label class="fld">
                <span class="fld__label">产地资源点</span>
                <select v-model="productForm.poi_id" class="field">
                  <option value="">（不指定）</option>
                  <option v-for="p in ruralPois" :key="p.id" :value="p.id">{{ p.name }}</option>
                </select>
              </label>
              <label class="fld">
                <span class="fld__label">体验锚点</span>
                <select v-model="productForm.experience_id" class="field">
                  <option value="">（不指定）</option>
                  <option v-for="e in experienceOptions" :key="e.id" :value="e.id">
                    {{ e.name }}
                  </option>
                </select>
              </label>
            </div>
            <p class="fld__hint">
              产地与体验锚点至少填一项 —— 农产品必须能追溯到一次乡村体验或一个产地，
              这是本项目的设计红线，数据库也有同样的约束。
            </p>
            <div class="fld__triple">
              <label class="fld">
                <span class="fld__label">售价（元）</span>
                <input v-model="productForm.price" class="field" placeholder="0" />
              </label>
              <label class="fld">
                <span class="fld__label">库存</span>
                <input v-model="productForm.stock" class="field" placeholder="0" />
              </label>
              <label class="fld">
                <span class="fld__label">产地村</span>
                <input v-model="productForm.origin_village" class="field" placeholder="如：火烧店镇" />
              </label>
            </div>
            <label class="fld">
              <span class="fld__label">标签（逗号分隔）</span>
              <input v-model="productForm.tags" class="field" placeholder="地理标志, 礼盒" />
            </label>
            <label class="fld">
              <span class="fld__label">溯源文案</span>
              <textarea
                v-model="productForm.story"
                class="field field--area"
                rows="3"
                placeholder="这一款和那次体验的关系"
              />
            </label>
            <label class="fld">
              <span class="fld__label">封面插画</span>
              <select v-model="productForm.scene" class="field">
                <option v-for="s in SCENES" :key="s.value" :value="s.value">{{ s.label }}</option>
              </select>
            </label>
          </template>
        </div>

        <div class="drawer__foot">
          <button class="btn btn-ghost btn-sm" :disabled="busy" @click="close">取消</button>
          <button class="btn btn-primary btn-sm" :disabled="busy" @click="submit">
            {{ editingId ? '保存' : '创建' }}
          </button>
        </div>
      </div>
    </div>
  </Transition>
</template>
