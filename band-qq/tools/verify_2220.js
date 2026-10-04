#!/usr/bin/env node
/** v2.22.0 四分支 rpk 解包断言（继承 v2.21.0 全量回归 + 本轮三线）
 *  ①键盘第四轮（用户："键盘选择栏怎么在下面了"）：rect 分支动作行（语言/候选选择栏/删除）
 *    与字母区换序——动作行回到键盘顶部（对齐 circle/pill 分支），高度链 60+261=321 不变。
 *  ②NapCat 启动链（用户 10-04 第三份引擎日志定案：v2.21 全链生效后唯一断点 =
 *    NapCat 只装不启，3001/3000 永不监听）：ensure_napcat_configs（onebot11.json 补
 *    HTTP :3000 / WS :3001 服务端 + webui.json 固定端口 Token）+ start_napcat（Xvfb +
 *    launcher.sh 后台拉起）+ 看门狗 + napcat-start 步骤。
 *  ③「启动 AstrBot 机器人」开关：ASTRBOT_ENABLE 环境变量透传，关闭 = 仅 NapCat 模式
 *    （脚本 while 常驻），EngineManager prefs + AstrBotScreen Switch + 密码卡。 */
const { execSync } = require('child_process')
const fs = require('fs')
const path = require('path')

const DIR = path.join(__dirname, '..', 'dist-branch')
const VER = '2.22.0'
const VC = '63'
const PKGS = {
  band: { dw: 192, screentype: 'pill-shaped', kbH: '333' },
  bandpro: { dw: 336, screentype: 'rect', kbH: '349' },
  xiaomis: { dw: 466, screentype: 'circle', kbH: '312' },
  redmiwatch: { dw: 432, screentype: 'rect', kbH: '349' }
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
  const outDir = path.join('/tmp/bq-verify-2220', tag)
  extract(rpk, outDir)
  const files = readAll(outDir)
  const all = Object.values(files).join('\n')
  const compact = all.replace(/\s/g, '')
  const manifestTxt = files['manifest.json'] || ''
  const compose = files['compose.js'] || files['pages/compose/compose.js'] || ''

  check('manifest designWidth=' + spec.dw, manifestTxt.includes(String(spec.dw)))
  check('versionName ' + VER + '-' + tag, all.includes(VER + '-' + tag))
  check('versionCode ' + VC, manifestTxt.includes(VC))
  check('screentype=' + spec.screentype, compose.includes('"' + spec.screentype + '"'))

  // v2.19-21 既有键盘断言保持
  check('kb-dock bottom:0 锚定', /kb-dock.{0,120}bottom:0/.test(compact))
  check('kb-dock 显式高=' + spec.kbH, compose.includes('height:"' + spec.kbH + 'px"'))
  check('.page 顶部锚定 top:0 在包', /top:\s*"0(px)?"/.test(compose.replace(/\s/g, '')))

  if (spec.screentype === 'rect') {
    check('rect 容器高 321（高度链闭合）', compose.includes('height:"321px"'))
    check('rect 旧容器 274 已退役', !compose.includes('height:"274px"'))
    const kb67 = (compose.match(/keyboard67"\]\],\{[^}]*\}/) || [])[0] || ''
    check('keyboard67 无 absolute 残留', kb67.length > 0 && !kb67.includes('absolute') && !kb67.includes('"82px"'))
    check('keyboard67 高 261 在包', kb67.includes('"261px"'))
    // ① v2.22.0 核心：动作行（cn.png 动作行）在字母区（keyboard67）之前 = 选择栏在键盘顶部
    //    注意取样须用渲染树节点（__opts__:{id:"..."}），keyboard67 在样式表段更早出现会误判
    const iScroll = compose.indexOf('__opts__:{id:"keyboard67"')
    const iCw = compose.indexOf('__opts__:{id:"cvalWaiting"')
    const iCn = compose.indexOf('assets/horizontal/cn.png')
    check('rect 动作行位于字母区之前（选择栏置顶）', iScroll > 0 && iCn > 0 && iCn < iScroll && iCw > 0 && iCw < iScroll)
    check('rect 下展候选层覆盖保持', compose.includes('list67'))
    check('rect 三行字母键在包', compose.includes('"Q"') && compose.includes('"Z"'))
  }
  if (spec.screentype === 'pill-shaped') {
    check('pill keyboard66 滚动区在包', compose.includes('keyboard66'))
  }
  if (spec.screentype === 'circle') {
    check('circle 滚动常量 617（466/480 换算）', compose.includes('617'))
  }

  check('无调试色残留', !compact.includes('#aa3333') && !compact.includes('#3333aa'))
  check('无 dbg 字段残留', !compose.includes('dbg'))
}

// 全局：引擎脚本资产断言
const scriptPath = path.join(__dirname, '..', '..', 'android-sync', 'astrbot-engine', 'src', 'main', 'assets', 'astrbot-startup.sh')
if (fs.existsSync(scriptPath)) {
  const sh = fs.readFileSync(scriptPath, 'utf-8')
  console.log('== astrbot-startup.sh 资产 ==')
  check('DNS 无条件重写（毒桩根修）', sh.includes('resolv.conf 已重写'))
  check('ubuntu-ports 多源竞速', sh.includes('APT_MIRROR_CANDIDATES') && sh.includes('dev/tcp'))
  check('GitHub 代理并行竞速', sh.includes('gh-proxy-race'))
  check('sudo 透传垫片', sh.includes('ensure_sudo_shim'))
  check('gh_fetch 多源重试统一入口', sh.includes('gh_fetch') && sh.includes('gh_build_candidates'))
  check('curl 断流自杀参数', sh.includes('--speed-time 20 --speed-limit 512'))
  check('结构化阶段标记', sh.includes('[STAGE:$') && sh.includes('stage 92'))
  check('NapCat 离线包预下载', sh.includes('ensure_napcat_zip') && sh.includes('NapCat.Shell.zip'))
  // ② v2.22.0 NapCat 启动链
  check('onebot11 配置统一入口', sh.includes('ensure_napcat_configs'))
  check('onebot11 含 HTTP :3000 服务端', sh.includes('"httpServers"') && /"port": 3000/.test(sh))
  check('onebot11 含 WS :3001 服务端', /"port": 3001/.test(sh))
  check('onebot11 保留 AstrBot 桥 :6199', sh.includes('ASTRBOT_ONEBOT_WS_PORT:-6199'))
  check('旧默认空 server 形状升级补齐', sh.includes('"httpServers": \\[\\]'))
  check('webui.json 固定端口 Token', sh.includes('webui.json') && sh.includes('NAPCAT_WEBUI_PORT') && sh.includes('NAPCAT_WEBUI_TOKEN'))
  check('start_napcat 后台拉起', sh.includes('start_napcat()') && sh.includes('nohup bash launcher.sh'))
  check('Xvfb 虚拟显示', sh.includes('Xvfb') && sh.includes('NAPCAT_DISPLAY'))
  check('NapCat 看门狗', sh.includes('start_napcat_watchdog') && sh.includes('napcat-start'))
  check('napcat-start 独立步骤（只启不装）', /napcat-start\)[\s\S]{0,120}ensure_napcat_configs[\s\S]{0,60}start_napcat/.test(sh))
  check('AstrBot 启动前拉起 NapCat', /start_napcat \|\|[\s\S]{0,80}start_napcat_watchdog[\s\S]{0,120}uv run --no-sync main\.py/.test(sh))
  check('ASTRBOT_ENABLE 开关门（仅 NapCat 模式）', sh.includes('ASTRBOT_ENABLE') && sh.includes('仅 NapCat 模式'))
  check('仅 NapCat 模式容器常驻', sh.includes('while true; do sleep 3600; done'))
  check('stage 90 NapCat 启动阶段', sh.includes('stage 90'))
} else {
  console.log('== astrbot-startup.sh 资产缺失 ==')
  check('astrbot-startup.sh 存在', false)
}

// 全局：APK 侧源码断言（EngineManager / AstrBotScreen）
console.log('== APK 侧源码 ==')
const emPath = path.join(__dirname, '..', '..', 'android-sync', 'astrbot-engine', 'src', 'main', 'java', 'io', 'github', 'gsjsjzhznsz', 'bandqq', 'astrbot', 'engine', 'EngineManager.kt')
if (fs.existsSync(emPath)) {
  const em = fs.readFileSync(emPath, 'utf-8')
  check('ASTRBOT_ENABLE 透传进容器', em.includes('ASTRBOT_ENABLE='))
  check('开关 prefs 存取', em.includes('engine_start_astrbot') && em.includes('setAstrbotEnabled'))
  check('探测细化 6185/5099', em.includes('portOpen(6185)') && em.includes('portOpen(5099)'))
  check('停止链补 Xvfb/qq 进程', em.includes('"qq --no-sandbox"') && em.includes('"Xvfb"'))
  check('AstrBotSecrets 密码读取', em.includes('object AstrBotSecrets') && em.includes('Initial password:') && em.includes('webui.json'))
} else { check('EngineManager.kt 存在', false) }
const absPath = path.join(__dirname, '..', '..', 'android-sync', 'app', 'src', 'bundled', 'java', 'io', 'github', 'gsjsjzhznsz', 'bandqq', 'ui', 'AstrBotScreen.kt')
if (fs.existsSync(absPath)) {
  const abs = fs.readFileSync(absPath, 'utf-8')
  check('密码与登录卡在包', abs.includes('密码与登录') && abs.includes('copySecret'))
  check(' NapCat WebUI 入口 :5099', abs.includes('127.0.0.1:5099'))
  check('AstrBot 机器人开关 Switch', abs.includes('启动 AstrBot 机器人') && abs.includes('Switch'))
} else { check('AstrBotScreen.kt 存在', false) }

console.log('\n===== verify_2220 结果: ' + pass + ' PASS / ' + fail + ' FAIL =====')
process.exit(fail > 0 ? 1 : 0)
