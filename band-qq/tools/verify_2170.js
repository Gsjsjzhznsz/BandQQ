#!/usr/bin/env node
/** v2.17.0 四分支 rpk 解包断言（键盘内层 scroll 显式高补全 / 渲染器智能化 v2.17 / message_id 撤回链 / 版本单轨） */
const { execSync } = require('child_process')
const fs = require('fs')
const path = require('path')

const DIR = path.join(__dirname, '..', 'dist-branch')
const VER = '2.17.0'
const PKGS = {
  band: { dw: 192, screentype: 'pill-shaped', scrollConst: '636', kbH: '333', kbScroll: 170 },
  bandpro: { dw: 336, screentype: 'rect', scrollConst: '636', kbH: '283', kbScroll: 170 },
  xiaomis: { dw: 466, screentype: 'circle', scrollConst: '617', kbH: '312', kbScroll: 165 },
  redmiwatch: { dw: 432, screentype: 'rect', scrollConst: '636', kbH: '283', kbScroll: 170 }
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
  const outDir = path.join('/tmp/bq-verify-2170', tag)
  extract(rpk, outDir)
  const files = readAll(outDir)
  const all = Object.values(files).join('\n')
  const manifestTxt = files['manifest.json'] || ''
  const compose = files['compose.js'] || files['pages/compose/compose.js'] || ''
  check('manifest designWidth=' + spec.dw, manifestTxt.includes(String(spec.dw)))
  check('versionName ' + VER + '-' + tag, all.includes(VER + '-' + tag))
  check('versionCode 57', manifestTxt.includes('57'))
  check('screentype=' + spec.screentype, compose.includes('"' + spec.screentype + '"'))
  // v2.16.0 键盘宿主去 flex 化（回归保护）
  check('kb-dock 绝对贴底锚', compose.includes('kb-dock') && compose.includes('bottom:0'))
  check('kb-dock 显式高=' + spec.kbH, compose.includes('height:"' + spec.kbH + 'px"'))
  check('preview-box 显式高在包', /preview-box.{0,80}height:"\d+px"/.test(compose.replace(/\s/g, '')))
  check('preview-text padding-bottom=' + spec.kbH, compose.includes('paddingBottom:"' + spec.kbH + 'px"'))
  check('preview-box 不再 flex:1', !/preview-box.{0,60}flex:1/.test(compose.replace(/\s/g, '')))
  // v2.17.0 键盘内层字母键 scroll 显式高（组件编译进 compose.js；xiaomis 经 kbScale 换算 170→165）
  const kbScrollCount = (compose.match(/height:"1[67][05]px"/g) || []).length
  check('键盘内层 scroll 显式高≥2处(=' + spec.kbScroll + ')', kbScrollCount >= 2 && compose.includes('height:"' + spec.kbScroll + 'px"'))
  // v2.15 kbHpx 高度链（回归保护）
  const kbHpxCount = (all.match(/kbHpx/g) || []).length
  check('kbHpx 显式高度字段在包（≥3 处绑定）', kbHpxCount >= 3)
  // v2.16.0 直连 v11 动作翻译（回归保护）
  check('直连 delete_msg 翻译', all.includes('delete_msg'))
  check('直连 set_msg_emoji_like 翻译', all.includes('set_msg_emoji_like'))
  check('直连 friend_poke/group_poke 翻译', all.includes('friend_poke') && all.includes('group_poke'))
  check('直连 send_group_sign 翻译', all.includes('send_group_sign'))
  check('直连 get_stranger_info/get_group_member_info', all.includes('get_stranger_info') && all.includes('get_group_member_info'))
  // v2.17.0 message_id 撤回链：直连历史消息透传（压缩器单字母变量名，用结构特征匹配）+ 直连发送回填
  check('直连历史 message_id 透传', /null!==[a-z]\.message_id&&""!==[a-z]\.message_id/.test(all))
  check('直连发送 bindMessageId 回填', all.includes('bindMessageId'))
  // v2.17.0 渲染器智能化标记
  check('渲染器 QQ红包识别', all.includes('[QQ红包]'))
  check('渲染器 转发摘要标记', all.includes('[转发] '))
  check('渲染器 合并转发占位保留', all.includes('[合并转发]'))
  check('渲染器 wcpay 特征在包', all.includes('wcpay'))
  // v2.17.0 演示模式新形态
  check('演示模式 转发摘要形态', all.includes('周末组织去爬山，报名接龙'))
  check('演示模式 文件大小形态', all.includes('BandQQ-使用手册.pdf'))
  check('演示模式 表格压平形态', all.includes('机型适配清单'))
  check('演示模式 撤回灰显标记（回归）', all.includes('王姐 撤回了一条消息'))
  // v2.13.0 xiaomis：circle 滚动常量 636→617
  check('circle 滚动常量 ' + spec.scrollConst, all.includes(spec.scrollConst))
  check('AGPL-3.0 标记', all.includes('AGPL-3.0'))
  // v2.14/2.15/2.16 渲染器回归保护
  check('渲染器 卡片消息标记', all.includes('[卡片消息]'))
}

console.log('\n== 通用 universal 包（若存在）==')
{
  const p = path.join(DIR, 'bandqq-universal-' + VER + '.rpk')
  if (fs.existsSync(p)) {
    const outDir = path.join('/tmp/bq-verify-2170', 'universal')
    extract(p, outDir)
    const files = readAll(outDir)
    const all = Object.values(files).join('\n')
    check('universal 版本 ' + VER, all.includes(VER))
    check('universal 渲染器标记', all.includes('[卡片消息]'))
    check('universal v2.17 红包识别', all.includes('[QQ红包]'))
    check('universal kb-dock 锚', all.includes('kb-dock'))
  } else {
    console.log('  SKIP universal（未构建）')
  }
}

console.log('\nRESULT: ' + pass + ' PASS, ' + fail + ' FAIL')
process.exit(fail > 0 ? 1 : 0)
