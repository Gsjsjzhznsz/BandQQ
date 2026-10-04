#!/usr/bin/env node
/** v2.23.0 四分支 rpk 解包断言（继承 v2.22.0 全量回归 + 本轮四线）
 *  ①键盘第五轮（用户："键盘有点高，下面有块黑色区域"）：rect 字母区实际内容仅 170px
 *    （60+55+55），scroll 261 预算多出 91px 死区（容器无背景露页面黑底）——
 *    高度链整体收紧：scroll 261→176、容器 321→236、KB_H_RECT 349→264（28+236），
 *    dock/preview 由 branch-release.js 同步写入 264。
 *  ②AstrBot 启动失败根修（用户 10-04 第四份引擎日志定案：uv run main.py 报
 *    "Failed to spawn: No such file or directory"+"outside of a project"）：
 *    start_napcat 主 shell cd "$HOME" 污染工作目录 → launcher 改子 shell 内 cd，
 *    launch_astrbot 的 cd 移到 uv run 之前 + main.py 存在性前置检查。
 *  ③NapCat 检测根修（用户："napcat启动成功但apk没有检测到"）：登录账号只读
 *    onebot11_<uin>.json（模板写齐也不生效）→ ensure_napcat_configs 扫描升级全部
 *    账号配置，变更时自动重启 NapCat 生效。
 *  ④QQ 账号提取（用户："怎么没有提取账号"）+ 密码卡状态变化自动刷新 + 主页日志上移。 */
const { execSync } = require('child_process')
const fs = require('fs')
const path = require('path')

const DIR = path.join(__dirname, '..', 'dist-branch')
const VER = '2.23.0'
const VC = '64'
const PKGS = {
  band: { dw: 192, screentype: 'pill-shaped', kbH: '333' },
  bandpro: { dw: 336, screentype: 'rect', kbH: '264' },
  xiaomis: { dw: 466, screentype: 'circle', kbH: '312' },
  redmiwatch: { dw: 432, screentype: 'rect', kbH: '264' }
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
  const outDir = path.join('/tmp/bq-verify-2230', tag)
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

  // 既有键盘断言保持
  check('kb-dock bottom:0 锚定', /kb-dock.{0,120}bottom:0/.test(compact))
  check('kb-dock 显式高=' + spec.kbH, compose.includes('height:"' + spec.kbH + 'px"'))
  check('.page 顶部锚定 top:0 在包', /top:\s*"0(px)?"/.test(compose.replace(/\s/g, '')))

  if (spec.screentype === 'rect') {
    // ① v2.23.0 核心：高度链收紧 60+176=236（KB_H_RECT=28+236=264）
    check('rect 容器高 236（高度链闭合）', compose.includes('height:"236px"'))
    check('rect 字母区 scroll 高 176', compose.includes('height:"176px"'))
    check('rect 旧 scroll 261 已退役', !compose.includes('height:"261px"'))
    check('rect 旧容器 321 已退役（rect 段）', !/screentype==="rect"[^}]*height:"321px"/.test(compose))
    const kb67 = (compose.match(/keyboard67"\]\],\{[^}]*\}/) || [])[0] || ''
    check('keyboard67 无 absolute 残留', kb67.length > 0 && !kb67.includes('absolute') && !kb67.includes('"82px"'))
    check('keyboard67 高 176 在包', kb67.includes('"176px"'))
    // v2.22.0 保持：动作行（候选选择栏）在字母区之前 = 选择栏在键盘顶部
    const iScroll = compose.indexOf('__opts__:{id:"keyboard67"')
    const iCw = compose.indexOf('__opts__:{id:"cvalWaiting"')
    const iCn = compose.indexOf('assets/horizontal/cn.png')
    check('rect 动作行位于字母区之前（选择栏置顶）', iScroll > 0 && iCn > 0 && iCn < iScroll && iCw > 0 && iCw < iScroll)
    check('rect 下展候选层覆盖保持', compose.includes('list67'))
    check('rect 三行字母键在包', compose.includes('"Q"') && compose.includes('"Z"'))
  }
  if (spec.screentype === 'pill-shaped') {
    check('pill keyboard66 滚动区在包', compose.includes('keyboard66'))
    check('pill 高度 333 不受影响', compose.includes('height:"333px"'))
  }
  if (spec.screentype === 'circle') {
    check('circle 滚动常量 617（466/480 换算）', compose.includes('617'))
    check('circle 321 经 kbScale 换算为 312', compose.includes('height:"312px"') && !compose.includes('height:"321px"'))
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
  // NapCat 配置链（v2.23.0 重构）
  check('onebot11 配置统一入口', sh.includes('ensure_napcat_configs'))
  check('onebot11 含 HTTP :3000 服务端', sh.includes('"httpServers"') && /"port": 3000/.test(sh))
  check('onebot11 含 WS :3001 服务端', /"port": 3001/.test(sh))
  check('onebot11 保留 AstrBot 桥 :6199', sh.includes('__OB_WS_PORT__') && sh.includes('ASTRBOT_ONEBOT_WS_PORT:-6199'))
  check('端口占位符展开写入', sh.includes('write_ob11_config') && sh.includes('__OB_WS_PORT__') && sh.includes('s/__OB_WS_PORT__/${ASTRBOT_ONEBOT_WS_PORT:-6199}/g'))
  check('账号配置升级入口', sh.includes('upgrade_ob11_file') && sh.includes('onebot11_*.json'))
  check('账号配置升级日志', sh.includes('已升级账号配置'))
  check('webui.json 内容一致跳写', sh.includes('wj_new') && sh.includes('webui.json 已写入'))
  check('start_napcat 配置先行（变更即重启）', /ensure_napcat_configs && cfg_changed=1[\s\S]{0,400}重启 NapCat/.test(sh))
  check('start_napcat 后台拉起（子 shell cd）', sh.includes('start_napcat()') && sh.includes('nohup bash -c "cd \'$HOME\' && exec bash launcher.sh"'))
  check('start_napcat 主 shell 无 cd 污染', !/cd "\$HOME" \|\| return 1/.test(sh))
  check('Xvfb 虚拟显示', sh.includes('Xvfb') && sh.includes('NAPCAT_DISPLAY'))
  check('NapCat 看门狗', sh.includes('start_napcat_watchdog') && sh.includes('napcat-start'))
  check('napcat-start 独立步骤（只启不装）', /napcat-start\)[\s\S]{0,120}ensure_napcat_configs[\s\S]{0,60}start_napcat/.test(sh))
  // ② v2.23.0 核心：cwd 污染根修
  check('uv 前重新 cd INSTALL_DIR', /cd "\$INSTALL_DIR" \|\| \{ echo "AstrBot 目录缺失[\s\S]{0,400}uv run --no-sync main\.py/.test(sh))
  check('main.py 存在性前置检查', sh.includes('main.py 缺失'))
  check('AstrBot 启动前拉起 NapCat', /start_napcat \|\|[\s\S]{0,80}start_napcat_watchdog[\s\S]{0,400}cd "\$INSTALL_DIR"/.test(sh))
  const iNap = sh.indexOf('start_napcat || echo')
  const iCd = sh.indexOf('cd "$INSTALL_DIR" || { echo "AstrBot 目录缺失')
  check('cd 位于 start_napcat 之后（顺序正确）', iNap > 0 && iCd > iNap)
  check('ASTRBOT_ENABLE 开关门（仅 NapCat 模式）', sh.includes('ASTRBOT_ENABLE') && sh.includes('仅 NapCat 模式'))
  check('仅 NapCat 模式容器常驻', sh.includes('while true; do sleep 3600; done'))
  check('stage 90 NapCat 启动阶段', sh.includes('stage 90'))
} else {
  console.log('== astrbot-startup.sh 资产缺失 ==')
  check('astrbot-startup.sh 存在', false)
}

// 全局：APK 侧源码断言（EngineManager / AstrBotScreen / HomeScreen）
console.log('== APK 侧源码 ==')
const emPath = path.join(__dirname, '..', '..', 'android-sync', 'astrbot-engine', 'src', 'main', 'java', 'io', 'github', 'gsjsjzhznsz', 'bandqq', 'astrbot', 'engine', 'EngineManager.kt')
if (fs.existsSync(emPath)) {
  const em = fs.readFileSync(emPath, 'utf-8')
  check('ASTRBOT_ENABLE 透传进容器', em.includes('ASTRBOT_ENABLE='))
  check('开关 prefs 存取', em.includes('engine_start_astrbot') && em.includes('setAstrbotEnabled'))
  check('探测细化 6185/5099', em.includes('portOpen(6185)') && em.includes('portOpen(5099)'))
  check('停止链补 Xvfb/qq 进程', em.includes('"qq --no-sandbox"') && em.includes('"Xvfb"'))
  check('AstrBotSecrets 密码读取', em.includes('object AstrBotSecrets') && em.includes('Initial password:') && em.includes('webui.json'))
  // ④ v2.23.0：QQ 账号提取
  check('QQ 账号提取（配置文件名）', em.includes('qqAccounts') && em.includes('uinFileRe') && em.includes('onebot11_'))
  check('QQ 账号兜底（控制台日志）', em.includes('napcat-console.log') && em.includes('uinLogRes'))
} else { check('EngineManager.kt 存在', false) }
const absPath = path.join(__dirname, '..', '..', 'android-sync', 'app', 'src', 'bundled', 'java', 'io', 'github', 'gsjsjzhznsz', 'bandqq', 'ui', 'AstrBotScreen.kt')
if (fs.existsSync(absPath)) {
  const abs = fs.readFileSync(absPath, 'utf-8')
  check('密码与登录卡在包', abs.includes('密码与登录') && abs.includes('copySecret'))
  check('QQ 账号行+复制', abs.includes('QQ 账号（已登录）') && abs.includes('qqAccounts'))
  check('密码卡状态变化自动刷新', abs.includes('LaunchedEffect(state) { secretsVersion++ }'))
  check(' NapCat WebUI 入口 :5099', abs.includes('127.0.0.1:5099'))
  check('AstrBot 机器人开关 Switch', abs.includes('启动 AstrBot 机器人') && abs.includes('Switch'))
} else { check('AstrBotScreen.kt 存在', false) }
const homePath = path.join(__dirname, '..', '..', 'android-sync', 'app', 'src', 'main', 'java', 'io', 'github', 'gsjsjzhznsz', 'bandqq', 'ui', 'HomeScreen.kt')
if (fs.existsSync(homePath)) {
  const home = fs.readFileSync(homePath, 'utf-8')
  const iLog = home.indexOf('SmallTitle(text = "实时日志")')
  const iQuick = home.indexOf('SmallTitle(text = "快捷操作")')
  check('主页实时日志上移（状态卡后、快捷操作前）', iLog > 0 && iQuick > iLog)
} else { check('HomeScreen.kt 存在', false) }

console.log('\n===== verify_2230 结果: ' + pass + ' PASS / ' + fail + ' FAIL =====')
process.exit(fail > 0 ? 1 : 0)
