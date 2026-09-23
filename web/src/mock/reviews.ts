/**
 * 游客评价（SIMULATED）
 * 由资源 id 派生，同一资源每次刷新评价一致。后端评价模块上线后删除本文件。
 */
import type { BusinessType } from '@/types'
import { pick, seed, seedInt } from './hash'

export interface Review {
  author: string
  rating: number
  date: string
  text: string
  tag: string
}

const AUTHORS = ['秦**', '李**', '王**', '陈**', '刘**', '杨**', '赵**', '周**', '吴**', '徐**']

const TEMPLATES: Record<string, string[]> = {
  SCENIC: [
    '早上八点半到的，人还不算多，栈道走起来很舒服。带孩子来的，讲解牌做得不错。',
    '风景确实好，就是停车位紧张，建议早一点来。整体值得一逛。',
    '文化底蕴比想象中厚，请了个讲解，两个小时下来信息量很大。',
    '门票价格还算合理，配套设施完整，卫生间和休息点都够用。',
  ],
  RURAL_SPOT: [
    '本来只是顺路来的，结果待了一下午。采茶体验比想象中有意思，孩子很喜欢。',
    '村里的民宿很安静，早上能听到鸟叫。老板人实在，早餐是自家种的菜。',
    '体验完直接买了当地的东西寄回家，比景区门口的特产店靠谱多了。',
    '路不太好找，但到了之后觉得很值。建议自驾来，公共交通不太方便。',
  ],
  FOOD: [
    '本地朋友推荐的，味道正宗，价格实惠。早上来要排队。',
    '分量足，口味偏辣，不能吃辣的要提前说。环境一般但干净。',
    '来汉中必吃，和外面连锁店完全不是一回事。',
  ],
  LODGING: [
    '位置方便，房间干净，前台服务态度好。性价比不错。',
    '住了一晚，隔音还可以，早餐种类不多但味道可以。',
    '环境安静，适合家庭出行。停车方便。',
  ],
  TRANSPORT: [
    '出站就能打到车，指引清楚。去市区大概二十分钟。',
    '班次比较密，换乘方便，站内秩序不错。',
  ],
  SHOPPING: ['东西挺全，价格公道。老板会介绍产地，不硬推销。'],
}

const TAGS: Record<string, string[]> = {
  SCENIC: ['景色好', '适合亲子', '文化厚重', '交通便利', '停车紧张'],
  RURAL_SPOT: ['体验感强', '适合亲子', '民宿舒适', '值得复购', '路况一般'],
  FOOD: ['味道正宗', '价格实惠', '分量足', '需要排队'],
  LODGING: ['干净卫生', '服务好', '位置方便', '性价比高'],
  TRANSPORT: ['换乘方便', '指引清晰'],
  SHOPPING: ['产地直供', '价格公道'],
}

export function reviewsOf(poiId: string, type: BusinessType): Review[] {
  const pool = TEMPLATES[type] ?? TEMPLATES.SCENIC
  const tagPool = TAGS[type] ?? TAGS.SCENIC
  const n = seedInt(2, 3, poiId, 'review-count')

  // 正文与标签按"同一资源内不重复"抽取，避免出现两条一模一样的评价
  const texts = [...pool]
  const tags = [...tagPool]

  return Array.from({ length: Math.min(n, texts.length) }, (_, i) => {
    const ti = Math.floor(seed(poiId, 'text', i) * texts.length)
    const [text] = texts.splice(ti, 1)
    const gi = Math.floor(seed(poiId, 'tag', i) * tags.length)
    const [tag] = tags.length ? tags.splice(gi, 1) : [tagPool[0]]

    const month = seedInt(1, 8, poiId, 'month', i)
    const day = seedInt(1, 27, poiId, 'day', i)

    return {
      author: pick(AUTHORS, poiId, 'author', i),
      rating: seed(poiId, 'rating', i) > 0.7 ? 5 : 4,
      date: `2026-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`,
      text,
      tag,
    }
  })
}
