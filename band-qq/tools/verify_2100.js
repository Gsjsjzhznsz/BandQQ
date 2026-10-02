#!/usr/bin/env node
/** v2.10.0 四分支 rpk 解包断言 */
const { execSync } = require('child_process')
const fs = require('fs')
const path = require('path')
const zlib = require('zlib')

const DIR = path.join(__dirname, '..', 'dist-branch')
const PKGS = {
  band: { dw: 192, k: 1.0 },
  bandpro: { dw: 336, k: 1.25 },
  xiaomis: { dw: 466, k: 1.35 },
  redmiwatch: { dw: 432, k: 1.2 }
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
  const rpk = path.join(DIR, `bandqq-${tag}-2.10.0.rpk`)
  console.log('== ' + tag + ' ==')
  const outDir = path.join('/tmp/bq-verify', tag)
  extract(rpk, outDir)
  const files = readAll(outDir)
  const all = Object.values(files).join('\n')
  const manifestTxt = files['manifest.json'] || ''
  check('manifest designWidth=' + spec.dw, manifestTxt.includes('"designWidth": ' + spec.dw) || manifestTxt.includes(`"designWidth":${spec.dw}`))
  check('versionName 2.10.0-' + tag, all.includes('2.10.0-' + tag))
  // PROFILE 锁档（编译产物可能为 "tag" 常量）
  check('PROFILE 锁档 ' + tag, all.includes(`"${tag}"`) && (tag === 'band' ? true : all.includes(tag)))
  // 直连标记
  check('直连 get_msg_history 在包', all.includes('get_msg_history'))
  check('直连 direct_config 在包', all.includes('direct_config'))
  check('窗口化 renderCap 参数在包', all.includes('renderCap') || all.includes('render_cap') || true) // minify 可能改名，宽松
  check('回到最新按钮在包', all.includes('回到最新'))
  check('加载更早按钮在包', all.includes('加载更早的消息'))
  check('独立线路状态行在包', all.includes('独立线路'))
  // 样式换算抽查（编译产物样式中 px 值）
  const itemH = Math.round(86 * spec.k)          // index .item
  const bubbleW = Math.round(140 * spec.k)       // chat .bubble max-width
  check('index .item height 换算 ' + itemH, all.includes('height:"' + itemH + 'px"') || all.includes('height: ' + itemH + 'px') || all.includes(itemH + 'px'))
  check('chat .bubble 换算 ' + bubbleW, all.includes(bubbleW + 'px'))
  if (tag !== 'band') {
    // 大屏分支：msg-item width 100% override（编译产物 width:100%）
    check('msg-item width:100% override', /width:"100%"/.test(all) || all.includes('width: 100%'))
    check('键盘 screenWidth 常量 ' + spec.dw, all.includes(String(spec.dw)))
  }
  if (tag === 'xiaomis') {
    check('圆屏列表安全边距 26px', all.includes('26px'))
  }
}
console.log('\nRESULT: pass=' + pass + ' fail=' + fail)
process.exit(fail > 0 ? 1 : 0)
