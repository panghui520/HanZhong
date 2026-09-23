#!/usr/bin/env node
/**
 * 把 citypack/<city> 下的 JSON 同步到 web/public/data/
 * 目的：城市数据包是唯一数据源，Java 导入与前端读取都指向同一份文件。
 * 用法：node scripts/sync-citypack.mjs [city]
 */
import { readdir, copyFile, mkdir } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const city = process.argv[2] || 'hanzhong'
const src = join(root, 'citypack', city)
const dest = join(root, 'web', 'public', 'data')

await mkdir(dest, { recursive: true })

const files = (await readdir(src)).filter((f) => f.endsWith('.json'))
let n = 0
for (const f of files) {
  await copyFile(join(src, f), join(dest, f))
  n++
}
console.log(`[sync-citypack] ${city}: ${n} files -> web/public/data (${files.join(', ')})`)
