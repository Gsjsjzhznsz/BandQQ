#!/usr/bin/env node
/** v2.14.0 四分支 rpk 解包断言（键盘显式根高/恒预留拼音行/渲染器/版本单轨） */
const { execSync } = require('child_process')
const fs = require('fs')
const path = require('path')

const DIR = path.join(__dirname, '..', 'dist-branch')
const VER = '2.14.0'
const PKGS = {
  band: { dw: 192, screentype: 'pill-shaped', scrollConst: '636' },
  bandpro: { dw: 336, screentype: 'rect', scrollConst: '636' },
  xiaomis: { dw: 466, screentype: 'circle', scrollConst: '617' },
  redmiwatch: { dw: 432, screentype: 'rect', scrollConst: '636' }
}

let pass = 0, fail = 0
function check(name, cond) {
  if (cond) { pass++; console.log('  PASS ' + name) } else { fail++; console.log('  FAIL ' + name) }
}

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
  const rpk = path.join(DIR, `bandqq-${tag}-${VER}.rpk`)
  console.log('== ' + tag + ' ==')
  if (!fs.existsSync(rpk)) { check('包存在 ' + rpk, false); continue }
  const outDir = path.join('/tmp/bq-verify-2140', tag)
  extract(rpk, outDir)
  const files = readAll(outDir)
  const all = Object.values(files).join('\n')
  const manifestTxt = files['manifest.json'] || ''
  check('manifest designWidth=' + spec.dw, manifestTxt.includes(String(spec.dw)))
  check('versionName ' + VER + '-' + tag, all.includes(VER + '-' + tag))
  // v2.13.0 键盘体系：screentype 字面量按分支写入
  check('screentype=' + spec.screentype, all.includes('"' + spec.screentype + '"'))
  // v2.14.0 键盘显式根高：kbHpx 数据字段在编译产物中；height:auto 根绑定已移除
  check('kbHpx 显式高度字段在包', all.includes('kbHpx'))
  check('cvalrow 拼音行样式在包', all.includes('cvalrow'))
  // v2.14.0 xiaomis：circle 滚动常量 636→617
  check('circle 滚动常量 ' + spec.scrollConst, all.includes(spec.scrollConst))
  // AGPL 标记
  check('AGPL-3.0 标记', all.includes('AGPL-3.0'))
  // v2.14.0 智能渲染器（手环端兜底版）标记
  check('渲染器 卡片消息标记', all.includes('[卡片消息]'))
  check('渲染器 合并转发标记', all.includes('[合并转发]'))
  check('渲染器 表情包标记', all.includes('[表情包]'))
}

console.log('\n== 通用 debug/release 包（若存在）==')
for (const f of ['bandqq-release-' + VER + '.rpk', 'bandqq-debug-' + VER + '.rpk']) {
  const p = path.join(DIR, f)
  if (!fs.existsSync(p)) { console.log('  SKIP ' + f + '（不在 dist-branch）'); continue }
  const outDir = path.join('/tmp/bq-verify-2140', f.replace('.rpk', ''))
  extract(p, outDir)
  const files = readAll(outDir)
  const all = Object.values(files).join('\n')
  check(f + ' 版本 ' + VER, all.includes(VER))
  check(f + ' 渲染器标记', all.includes('[卡片消息]'))
}

console.log('\nRESULT: ' + pass + ' PASS, ' + fail + ' FAIL')
process.exit(fail > 0 ? 1 : 0)
