#!/usr/bin/env node
/**
 * v2.13.0 设备分支构建器 —— 按设备系列出独立适配的 rpk（根治"缩放感"+ 键盘全分支 NEORUAA 统一）
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
 *   ③ 键盘分支适配（v2.13.0）：全分支统一 NEORUAA/Vela_input_method 上游单组件
 *      （单文件内置 circle/rect/pill-shaped 三屏形布局），构建期只做两件事：
 *      a. compose.ux 的 screentype 字面量替换为分支对应屏形；
 *      b. InputMethod.ux 按 kbScale 换算（仅 xiaomis：circle 布局 480 标尺 → 466 物理宽）。
 *      v2.12.0 的 kb-variants/ 三快照整体退役（Revise fork 分发维护成本高、
 *      上游单组件自适配套路已覆盖全部分支需求）。
 *   ④ src/common/branch.js PROFILE 锁档（renderCap/pageSize/直连轮询间隔随之生效）
 *      + about 页版本行分支后缀
 *
 * 分支矩阵（与 src/common/branch.js 保持一致）：
 *   band       192x490 胶囊  k=1.00  键盘 pill-shaped（NEORUAA 192 原生，运行时自适应）
 *   bandpro    336x480 方屏  k=1.25  键盘 rect（NEORUAA rect 标准态 336，width:100% 自适应）
 *   xiaomis    466x466 圆屏  k=1.35  键盘 circle（NEORUAA 480 标尺 × 466/480≈0.9708 换算）
 *   redmiwatch 432x514 方屏  k=1.20  键盘 rect（NEORUAA rect width:100% 自适应，零换算）
 *
 * 键盘上游致谢：NEORUAA/Vela_input_method（BSD-3-Clause，Apache-2.0 词库数据）
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
const COMPOSE_FILE = 'src/pages/compose/compose.ux'

const BRANCHES = {
  band: { tag: 'band', note: 'Smart Band 8/9/10/11 胶囊屏', designWidth: 192, k: 1.0 },
  bandpro: { tag: 'bandpro', note: 'Smart Band 8Pro/9Pro/10Pro 方屏', designWidth: 336, k: 1.25 },
  xiaomis: { tag: 'xiaomis', note: 'Xiaomi Watch S3/S4/S5 圆屏', designWidth: 466, k: 1.35 },
  redmiwatch: { tag: 'redmiwatch', note: 'REDMI Watch 5/5eSIM/6 方屏', designWidth: 432, k: 1.2 }
}

/**
 * 键盘分支适配（v2.13.0）：全分支统一 NEORUAA 上游单组件，构建期两件事：
 *   - screentype：compose.ux 源码树默认 "pill-shaped"，非胶囊分支构建期替换字面量
 *   - kbScale：InputMethod.ux 样式换算系数（1.0 跳过不碰文件）
 *     xiaomis 是唯一 ≠1.0 的分支：circle 布局写死 480 标尺，466 物理宽下会横向溢出，
 *     466/480≈0.9708 等比缩进全部样式值 + script 滚动常量 636→617。
 *     pill 布局 (screenWidth-192)/2 与 rect 布局 width:100% 均为运行时自适应（
 *     device.getInfo screenWidth），构建期零换算。
 */
const KB_VARIANTS = {
  band: { screentype: 'pill-shaped', kbScale: 1.0 },
  bandpro: { screentype: 'rect', kbScale: 1.0 },
  xiaomis: { screentype: 'circle', kbScale: 466 / 480 },
  redmiwatch: { screentype: 'rect', kbScale: 1.0 }
}
const CIRCLE_SCROLL_TOTAL = 636 // circle QWERTY 键盘滚动总宽（480 标尺，handelScroll）

/** 构建期 compose.ux screentype 字面量替换（每分支从原始现场出发，幂等） */
function applyScreenType(src, kb) {
  const re = /screentype="[^"]*"/
  if (!re.test(src)) throw new Error('compose.ux 未找到 screentype 标记')
  return src.replace(re, 'screentype="' + kb.screentype + '"')
}

/** 键盘 script 常量换算：circle 滚动总宽 + v2.14.0 键盘总高常量按 kbScale（仅 xiaomis 命中） */
const KB_H_CONSTS = { KB_H_CIRCLE: 321, KB_H_RECT: 264, KB_H_PILL: 333 }
function scaleKeyboardConsts(src, kb) {
  if (kb.kbScale === 1.0) return src
  let out = src
  const total = Math.round(CIRCLE_SCROLL_TOTAL * kb.kbScale)
  out = out.replace(
    'event.scrollX / ' + CIRCLE_SCROLL_TOTAL,
    'event.scrollX / ' + total
  )
  for (const [name, val] of Object.entries(KB_H_CONSTS)) {
    const scaled = Math.round(val * kb.kbScale)
    out = out.replace('var ' + name + ' = ' + val, 'var ' + name + ' = ' + scaled)
  }
  return out
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
    // v2.16.0 compose 去 flex 化：preview-text padding-bottom 源值 333（band 基准），
    // 换算后与本分支键盘物理高对齐（键盘 kbHpx：bandpro/redmiwatch 283、xiaomis 312）
    const kbFinal = { bandpro: 264, xiaomis: 312, redmiwatch: 264 }
    // preview-box 显式高 = 屏高 - top-bar 物理高（absolute 容器零高裁剪同防）
    // v2.19.0 kb-dock 锚定改为 bottom:0（compose.ux 源码级，全分支统一）。
    // v2.18.0/2.18.1 的 top=视口高-键盘高 构建期常量在 redmiw5 VM（432×514）实测翻车：
    // 固件底部有 ~49px 手势保留区，device.getInfo 完全不可见（SH 仍报 514），写死的
    // top 把键盘整体推进保留区 → Z 行被视口拦腰截断（用户口径"键盘只显示一半"）。
    // bottom:0 由引擎按页面可用底解析，天然适配全部机型/镜像/保留区高度。
    rules.push([
      'src/pages/compose/compose.ux',
      /(\.preview-box \{[^}]*?bottom: )\d+px/,
      '$1' + kbFinal[tag] + 'px'
    ])
    rules.push([
      'src/pages/compose/compose.ux',
      /(\.kb-dock \{[^}]*?height: )\d+px/,
      '$1' + kbFinal[tag] + 'px'
    ])
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
  const originalCompose = fs.readFileSync(path.join(ROOT, COMPOSE_FILE), 'utf-8')
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
      console.log('[' + key + '] ' + b.note + ' designWidth=' + b.designWidth + ' k=' + b.k + ' screentype=' + kb.screentype + '/' + kb.kbScale.toFixed(3))

      // ① manifest：designWidth + versionName 后缀
      const manifest = JSON.parse(originalManifest)
      manifest.versionName = versionName + '-' + b.tag
      manifest.config = manifest.config || {}
      manifest.config.designWidth = b.designWidth
      fs.writeFileSync(MANIFEST, JSON.stringify(manifest, null, 2))

      // ② 键盘分支适配（v2.13.0）：compose.ux screentype 字面量（幂等，从原始现场出发）
      const composeTagged = applyScreenType(originalCompose, kb)
      if (composeTagged === originalCompose && kb.screentype !== 'pill-shaped') {
        throw new Error('compose.ux screentype 改写失败')
      }
      fs.writeFileSync(path.join(ROOT, COMPOSE_FILE), composeTagged)

      // ③ 全库样式换算（每分支从原始现场出发，幂等）
      for (const rel of UX_FILES) {
        let src = originals[rel]
        // v2.13.0：compose.ux 以带 screentype 的版本为基线（否则步骤②被原始内容覆盖）
        if (rel === COMPOSE_FILE) src = composeTagged
        if (rel === 'src/components/InputMethod/InputMethod.ux') {
          // 键盘专属系数 kbScale（与页面密度 k 解耦）；1.0 时零换算保持上游原样
          src = scaleWholeFile(src, kb.kbScale)
          src = scaleKeyboardConsts(src, kb)
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
      // v2.13.0：版本行正则匹配（行尾可能带许可证后缀「· AGPL-3.0」等，不再精确匹配整行）
      // v2.25.0 加固：版本号通配（\d[\d.]*）——manifest 版本 bump 后 about.ux 漏同步
      // 不再炸构建（CI 首跑实测 v2.24.0 行 + 2.25.0 manifest → 硬失败），此处自动对齐
      const verLineRe = />v\d[\d.]* · 快应用端[^<]*</
      const aboutTagged = originalAbout.replace(
        verLineRe,
        '>v' + versionName + '-' + b.tag + ' · 快应用端 · AGPL-3.0<'
      )
      if (aboutTagged === originalAbout) throw new Error('about.ux 版本行缺失（需存在 ">vX.Y.Z · 快应用端" 行）')
      fs.writeFileSync(ABOUT_FILE, aboutTagged)

      // ⑥ 构建 release（立即收集，aiot release 会清空 dist）
      // BQ_JSC=1 时追加 --enable-jsc（VM 20250716 镜像固件只加载 .jsc 字节码，
      // 真机固件 .js/.jsc 均可——VM 验证构建需 BQ_JSC=1，发布构建保持默认）
      const jscFlag = process.env.BQ_JSC === '1' ? ' --enable-jsc' : ''
      execSync('npx aiot release' + jscFlag, { cwd: ROOT, stdio: 'inherit' })
      const distFiles = fs.readdirSync(path.join(ROOT, 'dist')).filter((f) => f.endsWith('.rpk'))
      if (distFiles.length !== 1) throw new Error('dist 产物异常: ' + distFiles.join(','))
      const outFile = path.join(OUT_DIR, 'bandqq-' + b.tag + '-' + versionName + '.rpk')
      fs.copyFileSync(path.join(ROOT, 'dist', distFiles[0]), outFile)
      console.log('   -> ' + outFile)
      // 恢复 manifest 与 compose（下一分支从原始现场出发）
      fs.writeFileSync(MANIFEST, originalManifest)
      fs.writeFileSync(path.join(ROOT, COMPOSE_FILE), originalCompose)
    }
  } finally {
    // 现场恢复（源码树零污染；键盘为单组件不再需要快照换装）
    fs.writeFileSync(MANIFEST, originalManifest)
    fs.writeFileSync(BRANCH_FILE, originalBranch)
    fs.writeFileSync(ABOUT_FILE, originalAbout)
    fs.writeFileSync(path.join(ROOT, COMPOSE_FILE), originalCompose)
    for (const rel of UX_FILES) {
      fs.writeFileSync(path.join(ROOT, rel), originals[rel])
    }
    console.log('[branch-release] 现场已恢复')
  }
}

main()
