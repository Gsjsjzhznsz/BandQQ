#!/usr/bin/env node
/**
 * v2.12.0 设备分支构建器 —— 按设备系列出独立适配的 rpk（根治"缩放感"+ 键盘分支原生化）
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
 * 每分支五件套（全部构建期完成，finally 恢复现场）：
 *   ① manifest designWidth → 系列物理宽；versionName 追加 -<tag>
 *   ② 全库样式值换算：各 .ux 的 <style> 块与 template 内联 style 中 Npx → round(N×k)，
 *      1px 细线保留（描边语义），deg/%/100% 等非 px 不动 —— 非等比"缩放"，
 *      k 是视觉密度系数（大屏适度放大可读性、一屏显示更多内容）
 *   ③ 键盘分支原生换装（v2.12.0）：非胶囊分支整体替换 InputMethod 组件为对应
 *      原生布局（kb-variants/），再用键盘专属系数 kbScale 重写该组件样式 ——
 *      此前 v2.10.0 只把胶囊版键盘常量数值缩放，方屏/圆屏用户看到的仍是手环键盘
 *   ④ src/common/branch.js PROFILE 锁档（renderCap/pageSize/直连轮询间隔随之生效）
 *   ⑤ about 页版本行分支后缀
 *
 * 分支矩阵（与 src/common/branch.js 保持一致）：
 *   band       192x490 胶囊  k=1.00  键盘 pill（Capsule-For-Xiaomi-Band，原生 192）
 *   bandpro    336x480 方屏  k=1.25  键盘 rect（Cube 系，原生 432→按 336/432 换算）
 *   xiaomis    466x466 圆屏  k=1.35  键盘 circle（QWERTY 圆屏版，原生 466 零换算）
 *   redmiwatch 432x514 方屏  k=1.20  键盘 rect（Cube-For-Redmi-Watch，原生 432 零换算）
 *
 * 键盘上游致谢：AetherZeng1145/Vela-Input-Method-Revise（基于 NEORUAA/Vela_input_method）
 */
const fs = require('fs')
const path = require('path')
const os = require('os')
const { execSync } = require('child_process')

const ROOT = path.join(__dirname, '..')
const MANIFEST = path.join(ROOT, 'src', 'manifest.json')
const BRANCH_FILE = path.join(ROOT, 'src', 'common', 'branch.js')
const ABOUT_FILE = path.join(ROOT, 'src', 'pages', 'about', 'about.ux')
const OUT_DIR = path.join(ROOT, 'dist-branch')

/** 样式换算目标：全部页面 + 键盘组件（style.css 无引用为死文件，跳过） */
// 注：InputMethod.ux 在非 pill 分支会被 kb-variants/<type> 整体换装（v2.12.0）
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

/**
 * 键盘分支变体（v2.12.0）：kb-variants/<type>/ 为完整 InputMethod 组件快照
 * （InputMethod.ux + assets/）。构建期整体替换 src/components/InputMethod/，
 * finally 恢复。kbScale 只作用于键盘组件样式（与页面密度系数 k 解耦）：
 *   pill   胶囊屏原生 192，band 零换算；script 常量沿用 v2.10.0 规则
 *   rect   上游 Cube-For-Redmi-Watch 方屏版，原生 432 标尺（redmiwatch 零换算；
 *          bandpro 按 336/432=0.7778 换算 —— 上游 Pro 分支仅有 README 无代码，
 *          按其 "渲染改成 336*480" 的意图以等比换算实现）
 *   circle 上游 QWERTY 圆屏全键版，原生 466 标尺，xiaomis 零换算
 */
const KB_VARIANTS = {
  band: { type: 'pill', kbScale: 1.0 },
  bandpro: { type: 'rect', kbScale: 336 / 432 },
  xiaomis: { type: 'circle', kbScale: 1.0 },
  redmiwatch: { type: 'rect', kbScale: 1.0 }
}
const IM_DIR = path.join(ROOT, 'src', 'components', 'InputMethod')
// 键盘变体快照放仓库根（band-qq 项目目录之外）：aiot 工具链会扫描项目内全部 .ux，
// 快照留在项目内会被误编译（assets 相对路径 not in src 报错）
const KB_VARIANT_DIR = path.join(ROOT, '..', 'kb-variants')
const RECT_SCROLL_TOTAL = 1054 // rect 键盘最宽符号行：64 + 15×(62+4)，432 原生标尺

/** 构建期键盘组件整体换装（非 pill 分支）；首次换装前把原件暂存到系统临时目录 */
let imStash = null
function swapKeyboard(kb) {
  if (kb.type === 'pill') return
  if (!imStash) {
    imStash = fs.mkdtempSync(path.join(os.tmpdir(), 'bq-im-'))
    fs.cpSync(IM_DIR, imStash, { recursive: true })
  }
  fs.rmSync(IM_DIR, { recursive: true, force: true })
  fs.cpSync(path.join(KB_VARIANT_DIR, kb.type), IM_DIR, { recursive: true })
}

function restoreKeyboard() {
  if (!imStash) return
  fs.rmSync(IM_DIR, { recursive: true, force: true })
  fs.cpSync(imStash, IM_DIR, { recursive: true })
  fs.rmSync(imStash, { recursive: true, force: true })
  imStash = null
}

/** 键盘 script 常量换算：按变体类型分派（v2.10.0 的 pill 规则 + v2.12.0 rect 规则） */
function scaleKeyboardConsts(src, b, kb) {
  if (kb.type === 'pill') {
    const kbw = Math.round(192 * b.k)
    const total = Math.round(633 * b.k)
    let out = src
      .replace('screenWidth: 192,', 'screenWidth: ' + b.designWidth + ',')
      .replace(/\(screenWidth - 192\)/g, '(screenWidth - ' + kbw + ')')
      .replace(/event\.scrollX \/ 633/g, 'event.scrollX / ' + total)
    return out
  }
  if (kb.type === 'rect') {
    // 滚动总宽按 kbScale 换算（kbScale=1.0 时原值保留）
    const total = Math.round(RECT_SCROLL_TOTAL * kb.kbScale)
    return src.replace(
      'event.scrollX / ' + RECT_SCROLL_TOTAL,
      'event.scrollX / ' + total
    )
  }
  // circle：466 原生，零换算
  return src
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
      const kb = KB_VARIANTS[key]
      console.log('[' + key + '] ' + b.note + ' designWidth=' + b.designWidth + ' k=' + b.k + ' keyboard=' + kb.type + '/' + kb.kbScale.toFixed(3))

      // ① manifest：designWidth + versionName 后缀
      const manifest = JSON.parse(originalManifest)
      manifest.versionName = versionName + '-' + b.tag
      manifest.config = manifest.config || {}
      manifest.config.designWidth = b.designWidth
      fs.writeFileSync(MANIFEST, JSON.stringify(manifest, null, 2))

      // ② 键盘变体换装（v2.12.0，pill 分支跳过）
      swapKeyboard(kb)

      // ③ 全库样式换算（每分支从原始现场出发，幂等）
      for (const rel of UX_FILES) {
        let src = originals[rel]
        if (rel === 'src/components/InputMethod/InputMethod.ux') {
          if (kb.type !== 'pill') {
            // 变体键盘：以换装后的变体文件为基线，用键盘专属系数换算
            src = fs.readFileSync(path.join(ROOT, rel), 'utf-8')
          }
          src = scaleWholeFile(src, kb.kbScale)
          src = scaleKeyboardConsts(src, b, kb)
        } else {
          src = scaleWholeFile(src, b.k)
        }
        fs.writeFileSync(path.join(ROOT, rel), src)
      }

      // ④ 分支 override（基于换算后的现场做精确替换）
      for (const rel of UX_FILES) handles[rel] = mkHandle(rel)
      applyOverrides(handles, overridesFor(key))

      // ⑤ branch.js PROFILE 锁档 + about 版本行分支后缀
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

      // ⑥ 构建 release（立即收集，aiot release 会清空 dist）
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
    // 现场恢复（源码树零污染；键盘组件先整体还原再写回原 .ux）
    restoreKeyboard()
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
