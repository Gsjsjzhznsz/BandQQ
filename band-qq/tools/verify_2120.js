#!/usr/bin/env node
/** v2.12.0 四分支 rpk 解包断言（页面适配 + 键盘分支原生换装） */
const { execSync } = require('child_process')
const fs = require('fs')
const path = require('path')

const DIR = path.join(__dirname, '..', 'dist-branch')
const PKGS = {
  band: { dw: 192, k: 1.0, kb: 'pill', kbScale: 1.0 },
  bandpro: { dw: 336, k: 1.25, kb: 'rect', kbScale: 336 / 432 },
  xiaomis: { dw: 466, k: 1.35, kb: 'circle', kbScale: 1.0 },
  redmiwatch: { dw: 432, k: 1.2, kb: 'rect', kbScale: 1.0 }
}

let pass = 0, fail = 0
function check(name, cond) {
  if (cond) { pass++; console.log('  PASS ' + name) } else { fail++; console.log('  FAIL ' + name) }
}

// rpk 内文件为 zip 存储；优先 unzip 提取 app.js/manifest.json
function extract(rpk, outDir) {
  fs.rmSync(outDir, { recursive: true, force: true })
  fs.mkdirSync(outDir, { recursive: true })
  execSync(`cd "${outDir}" && unzip -o -q "${rpk}"`, { stdio: 'pipe' })
}

function readAll(outDir) {
  const out = {}
  function walk(d) {
    for (const f of fs.readdirSync(d)) {
      const p = path.join(d, f)
      if (fs.statSync(p).isDirectory()) walk(p)
      else {
        try { out[f] = fs.readFileSync(p, 'utf-8') } catch (e) { /* binary */ }
      }
    }
  }
  walk(outDir)
  return out
}

for (const [tag, spec] of Object.entries(PKGS)) {
  const rpk = path.join(DIR, `bandqq-${tag}-2.12.0.rpk`)
  console.log('== ' + tag + ' ==')
  const outDir = path.join('/tmp/bq-verify', tag)
  extract(rpk, outDir)
  const files = readAll(outDir)
  const all = Object.values(files).join('\n')
  const manifestTxt = files['manifest.json'] || ''
  check('manifest designWidth=' + spec.dw, manifestTxt.includes('"designWidth": ' + spec.dw) || manifestTxt.includes(`"designWidth":${spec.dw}`))
  check('versionName 2.12.0-' + tag, all.includes('2.12.0-' + tag))
  // PROFILE 锁档（编译产物可能为 "tag" 常量）
  check('PROFILE 锁档 ' + tag, all.includes(`"${tag}"`) && (tag === 'band' ? true : all.includes(tag)))
  // 直连标记
  check('直连 get_msg_history 在包', all.includes('get_msg_history'))
  check('直连 direct_config 在包', all.includes('direct_config'))
  check('回到最新按钮在包', all.includes('回到最新'))
  check('加载更早按钮在包', all.includes('加载更早的消息'))
  check('独立线路状态行在包', all.includes('独立线路'))
  // 全量字库标记（v2.12.0：419 音节 27398 字）：编译器剥注释，改用新字库中非 GB2312 字「錒」验证
  check('全量字库标记在包', all.includes('錒'))
  // 样式换算抽查（编译产物样式中 px 值）
  const itemH = Math.round(86 * spec.k)          // index .item
  const bubbleW = Math.round(140 * spec.k)       // chat .bubble max-width
  check('index .item height 换算 ' + itemH, all.includes('height:"' + itemH + 'px"') || all.includes('height: ' + itemH + 'px') || all.includes(itemH + 'px'))
  check('chat .bubble 换算 ' + bubbleW, all.includes(bubbleW + 'px'))
  if (tag !== 'band') {
    // 大屏分支：msg-item width 100% override（编译产物 width:100%）
    check('msg-item width:100% override', /width:"100%"/.test(all) || all.includes('width: 100%'))
  }
  // ===== v2.12.0 键盘分支原生换装断言 =====
  if (spec.kb === 'pill') {
    check('键盘 pill 版（calbtnPill 类在包）', all.includes('calbtnPill'))
    check('键盘 pill 版非 rect/circle 类', !all.includes('calbtnRect') && !all.includes('calbtnfull'))
    check('键盘 screenWidth 常量 192', all.includes('192'))
  } else if (spec.kb === 'rect') {
    check('键盘 rect 版（calbtnRect 类在包）', all.includes('calbtnRect'))
    check('rect 资产 horizontal/ 在包', fs.existsSync(path.join(outDir, 'components', 'InputMethod', 'assets', 'horizontal')))
    check('rect 无 pill/circle 类残留', !all.includes('calbtnPill') && !all.includes('calbtnfull'))
    // 换算后按键宽：round(62×kbScale)
    const keyW = Math.round(62 * spec.kbScale)
    check('rect 按键宽换算 ' + keyW, all.includes(keyW + 'px'))
    // 滚动总宽：round(1054×kbScale)，正则带词边界防 8205（emoji ZWJ）误配
    const total = Math.round(1054 * spec.kbScale)
    check('rect 滚动总宽 ' + total, new RegExp('scrollX\\s*/\\s*' + total + '\\b').test(all))
    // 符号扩充标记（「 与 · 为 v2.12.0 新增键）
    check('rect 符号扩充「· 在包', all.includes('「') && all.includes('·'))
  } else if (spec.kb === 'circle') {
    check('键盘 circle 版（calbtnfull 类在包）', all.includes('calbtnfull'))
    check('circle 资产 full/ 在包', fs.existsSync(path.join(outDir, 'components', 'InputMethod', 'assets', 'full')))
    check('circle 无 pill/rect 类残留', !all.includes('calbtnPill') && !all.includes('calbtnRect'))
    check('circle 466 原生按键宽 40px', all.includes('40px'))
    check('hide 死按钮已移除', !all.includes('handleHide'))
  }
  if (tag === 'xiaomis') {
    check('圆屏列表安全边距 26px', all.includes('26px'))
  }
}
console.log('\nRESULT: pass=' + pass + ' fail=' + fail)
process.exit(fail > 0 ? 1 : 0)
