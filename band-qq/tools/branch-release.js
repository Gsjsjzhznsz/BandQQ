#!/usr/bin/env node
/**
 * v2.10.0 设备分支构建器 —— 按设备系列出独立适配的 rpk（根治"缩放感"）
 *
 * 用法：
 *   node tools/branch-release.js            # 一次出全部分支包
 *   node tools/branch-release.js band       # 只出指定分支
 *
 * 背景（用户实测：单一 designWidth=192 在 RW5E 等大屏上"界面缩放异常"）：
 *   Vela 固件换算 物理 px = css px × 物理屏宽 / designWidth。192 标尺在 432 宽的
 *   RW5E 上一切尺寸放大 2.25 倍（元素巨大、一屏内容极少）。此前 v0.1.x 实验已证：
 *   designWidth 对准物理宽（1css=1物理px）+ 按分支系数重写样式值 = 每个系列原生观感。
 *
 * 每分支四件套（全部构建期完成，finally 恢复现场）：
 *   ① manifest designWidth → 系列物理宽；versionName 追加 -<tag>
 *   ② 全库样式值换算：各 .ux 的 <style> 块与 template 内联 style 中 Npx → round(N×k)，
 *      1px 细线保留（描边语义），deg/%/100% 等非 px 不动 —— 非等比"缩放"，
 *      k 是视觉密度系数（大屏适度放大可读性、一屏显示更多内容）
 *   ③ InputMethod 键盘常量：screenWidth 上报值→物理宽、键盘设计宽 192→round(192×k)、
 *      滚动总宽 633→round(633×k)（键盘按键物理手感与 band 一致）
 *   ④ src/common/branch.js PROFILE 锁档（renderCap/pageSize/直连轮询间隔随之生效）
 *
 * 分支矩阵（与 src/common/branch.js 保持一致）：
 *   band       192x490 胶囊  k=1.00（现状真机验证值原样）
 *   bandpro    336x480 方屏  k=1.25
 *   xiaomis    466x466 圆屏  k=1.35（圆屏安全边距 override）
 *   redmiwatch 432x514 方屏  k=1.20
 */
const fs = require('fs')
const path = require('path')
const { execSync } = require('child_process')

const ROOT = path.join(__dirname, '..')
const MANIFEST = path.join(ROOT, 'src', 'manifest.json')
const BRANCH_FILE = path.join(ROOT, 'src', 'common', 'branch.js')
const ABOUT_FILE = path.join(ROOT, 'src', 'pages', 'about', 'about.ux')
const OUT_DIR = path.join(ROOT, 'dist-branch')

/** 样式换算目标：全部页面 + 键盘组件（style.css 无引用为死文件，跳过） */
const UX_FILES = [
  'src/pages/index/index.ux',
  'src/pages/chat/chat.ux',
  'src/pages/settings/settings.ux',
  'src/pages/compose/compose.ux',
  'src/pages/about/about.ux',
  'src/components/InputMethod/InputMethod.ux'
]

const BRANCHES = {
  band: { tag: 'band', note: 'Smart Band 8/9/10/11 胶囊屏', designWidth: 192, k: 1.0 },
  bandpro: { tag: 'bandpro', note: 'Smart Band 8Pro/9Pro/10Pro 方屏', designWidth: 336, k: 1.25 },
  xiaomis: { tag: 'xiaomis', note: 'Xiaomi Watch S3/S4/S5 圆屏', designWidth: 466, k: 1.35 },
  redmiwatch: { tag: 'redmiwatch', note: 'REDMI Watch 5/5eSIM/6 方屏', designWidth: 432, k: 1.2 }
}

/** px 换算：1px 细线保留；其余 round(N×k)，负值保持符号 */
function scalePxValue(numStr, k) {
  const v = parseFloat(numStr)
  if (v === 1) return '1px'
  const r = Math.round(v * k)
  return r + 'px'
}

/** 文本中 style 值域内的 Npx 换算（<style> 块整体；template 里仅 style="..." 属性内） */
function scaleStyleText(text, k) {
  const pxRe = /(-?\d+(?:\.\d+)?)px/g
  return text.replace(pxRe, (m, n) => scalePxValue(n, k))
}

function scaleWholeFile(src, k) {
  // ① <style>...</style> 块整体换算
  let out = src.replace(/<style>[\s\S]*?<\/style>/, (m) => scaleStyleText(m, k))
  // ② template 内联 style="..." 属性值换算（不含 script 字符串，避免误伤 JS 逻辑）
  out = out.replace(/style="([^"]*)"/g, (m, val) => 'style="' + scaleStyleText(val, k) + '"')
  return out
}

/** InputMethod 键盘 script 常量：screenWidth 上报 / 键盘设计宽 / 滚动总宽 */
function scaleKeyboardConsts(src, b) {
  const kbw = Math.round(192 * b.k)
  const total = Math.round(633 * b.k)
  let out = src
    .replace('screenWidth: 192,', 'screenWidth: ' + b.designWidth + ',')
    .replace(/\(screenWidth - 192\)/g, '(screenWidth - ' + kbw + ')')
    .replace(/event\.scrollX \/ 633/g, 'event.scrollX / ' + total)
  return out
}

/** 分支 override（换算后追加的精确替换；每项 [文件, 正则, 替换]） */
function overridesFor(tag) {
  const rules = []
  const all = ['bandpro', 'xiaomis', 'redmiwatch']
  if (all.indexOf(tag) >= 0) {
    // 大屏分支：消息行宽度从固定 192 系值改为 100%（铺满内容区，self 气泡贴右缘）
    rules.push(['src/pages/chat/chat.ux', /\.msg-item \{ width: \d+px;/, '.msg-item { width: 100%;'])
    rules.push(['src/pages/chat/chat.ux', /\.poke-wrap \{ width: \d+px;/, '.poke-wrap { width: 100%;'])
  }
  if (tag === 'xiaomis') {
    // 圆屏：列表/聊天左右安全边距加大，防四角裁切
    rules.push(['src/pages/index/index.ux', /(\.list \{[^}]*?padding: )[^;]+;/, '$18px 26px 0 26px;'])
    rules.push(['src/pages/chat/chat.ux', /(\.msg-item \{ width: 100%; padding: )[^;]+;/, '$10 16px;'])
  }
  return rules
}

function applyOverrides(handles, rules) {
  for (const [rel, re, rep] of rules) {
    const h = handles[rel]
    if (!h) continue
    const before = h.get()
    const after = before.replace(re, rep)
    if (after === before) {
      throw new Error('override 未命中: ' + rel + ' ' + re)
    }
    h.set(after)
  }
}

function main() {
  const only = process.argv[2]
  const list = only ? [only] : Object.keys(BRANCHES)
  if (only && !BRANCHES[only]) {
    console.error('未知分支: ' + only + '（可用: ' + Object.keys(BRANCHES).join('|') + '）')
    process.exit(1)
  }

  const originalManifest = fs.readFileSync(MANIFEST, 'utf-8')
  const originalBranch = fs.readFileSync(BRANCH_FILE, 'utf-8')
  const originalAbout = fs.readFileSync(ABOUT_FILE, 'utf-8')
  const originals = {}
  for (const rel of UX_FILES) {
    originals[rel] = fs.readFileSync(path.join(ROOT, rel), 'utf-8')
  }
  const versionName = JSON.parse(originalManifest).versionName

  if (!fs.existsSync(OUT_DIR)) fs.mkdirSync(OUT_DIR, { recursive: true })

  const handles = {}
  const mkHandle = (rel) => ({
    get: () => fs.readFileSync(path.join(ROOT, rel), 'utf-8'),
    set: (v) => fs.writeFileSync(path.join(ROOT, rel), v)
  })

  try {
    for (const key of list) {
      const b = BRANCHES[key]
      console.log('[' + key + '] ' + b.note + ' designWidth=' + b.designWidth + ' k=' + b.k)

      // ① manifest：designWidth + versionName 后缀
      const manifest = JSON.parse(originalManifest)
      manifest.versionName = versionName + '-' + b.tag
      manifest.config = manifest.config || {}
      manifest.config.designWidth = b.designWidth
      fs.writeFileSync(MANIFEST, JSON.stringify(manifest, null, 2))

      // ② 全库样式换算（每分支从原始现场出发，幂等）
      for (const rel of UX_FILES) {
        let src = originals[rel]
        src = scaleWholeFile(src, b.k)
        if (rel === 'src/components/InputMethod/InputMethod.ux') {
          src = scaleKeyboardConsts(src, b)
        }
        fs.writeFileSync(path.join(ROOT, rel), src)
      }

      // ③ 分支 override（基于换算后的现场做精确替换）
      for (const rel of UX_FILES) handles[rel] = mkHandle(rel)
      applyOverrides(handles, overridesFor(key))

      // ④ branch.js PROFILE 锁档 + about 版本行分支后缀
      const branchSrc = originalBranch.replace(
        "export const PROFILE = 'auto'",
        "export const PROFILE = '" + key + "'"
      )
      if (branchSrc === originalBranch) throw new Error('branch.js 改写失败：未找到 PROFILE 标记')
      fs.writeFileSync(BRANCH_FILE, branchSrc)
      const aboutTagged = originalAbout.replace(
        '>v' + versionName + ' · 快应用端<',
        '>v' + versionName + '-' + b.tag + ' · 快应用端<'
      )
      if (aboutTagged === originalAbout) throw new Error('about.ux 版本行改写失败（先同步 manifest 版本）')
      fs.writeFileSync(ABOUT_FILE, aboutTagged)

      // ⑤ 构建 release（立即收集，aiot release 会清空 dist）
      execSync('npx aiot release', { cwd: ROOT, stdio: 'inherit' })
      const distFiles = fs.readdirSync(path.join(ROOT, 'dist')).filter((f) => f.endsWith('.rpk'))
      if (distFiles.length !== 1) throw new Error('dist 产物异常: ' + distFiles.join(','))
      const outFile = path.join(OUT_DIR, 'bandqq-' + b.tag + '-' + versionName + '.rpk')
      fs.copyFileSync(path.join(ROOT, 'dist', distFiles[0]), outFile)
      console.log('   -> ' + outFile)
      // 恢复 manifest（下一分支从原始现场出发）
      fs.writeFileSync(MANIFEST, originalManifest)
    }
  } finally {
    // 现场恢复（源码树零污染）
    fs.writeFileSync(MANIFEST, originalManifest)
    fs.writeFileSync(BRANCH_FILE, originalBranch)
    fs.writeFileSync(ABOUT_FILE, originalAbout)
    for (const rel of UX_FILES) {
      fs.writeFileSync(path.join(ROOT, rel), originals[rel])
    }
    console.log('[branch-release] 现场已恢复')
  }
}

main()
