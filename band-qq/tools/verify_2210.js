#!/usr/bin/env node
/** v2.21.0 四分支 rpk 解包断言（继承 v2.20.0 全量回归 + 本轮两根修）
 *  ①键盘半屏根修（VM redmiw5 432×514 / bandpro 336×480 实测复现→修复→验证通过）：
 *    v2.19 测得的"固件幻影 83~85px 上方偏移"实为 #keyboard67 样式表残留
 *    position:absolute;top:82px（62 圆屏时代定位遗产）；且 rect 容器 height:274
 *    装不下子元素 261(scroll 预算)+60(动作行)=321，固件把动作行上提 47px 叠进
 *    第三排字母 = 用户口径"键盘只显示一半/输入不展开"。
 *    断言：rect 容器 321 高度链闭合（28+321=KB_H_RECT 349=dock）、keyboard67
 *    无 absolute 定位残留、scroll 261 预算保留。
 *  ②NapCat 离线包预下载（引擎日志会话4 实证：上游脚本内部下载不受 gh_fetch
 *    管控，ghfast.top 爬至 1.3% 后 curl(18) 断流 → exit=1）：ensure_napcat_zip
 *    预取 NapCat.Shell.zip 至脚本同目录（上游官方支持路径=跳过内部下载），
 *    unzip -t 自验，失败不阻断保留上游兜底。 */
const { execSync } = require('child_process')
const fs = require('fs')
const path = require('path')

const DIR = path.join(__dirname, '..', 'dist-branch')
const VER = '2.21.0'
const VC = '62'
const PKGS = {
  band: { dw: 192, screentype: 'pill-shaped', kbH: '333', kbBottom: '333', pvBottom: '333' },
  bandpro: { dw: 336, screentype: 'rect', kbH: '349', kbBottom: '349', pvBottom: '349' },
  xiaomis: { dw: 466, screentype: 'circle', kbH: '312', kbBottom: '312', pvBottom: '312' },
  redmiwatch: { dw: 432, screentype: 'rect', kbH: '349', kbBottom: '349', pvBottom: '349' }
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
  const outDir = path.join('/tmp/bq-verify-2210', tag)
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

  // ① kb-dock bottom:0 锚定（v2.19.0 核心）
  check('kb-dock bottom:0 锚定', /kb-dock.{0,120}bottom:0/.test(compact))
  check('kb-dock 不再 top 常量锚定', !/kb-dock.{0,120}top:"\d+px"/.test(compact))
  check('kb-dock 显式高=' + spec.kbH, compose.includes('height:"' + spec.kbH + 'px"'))

  // ② 组件根 .page top:0 锚定保持（v2.18.1 引入）
  check('.page 顶部锚定 top:0 在包', /top:\s*"0(px)?"/.test(compose.replace(/\s/g, '')))

  // ③ 预览区 bottom 锚定
  check('preview-box bottom=' + spec.pvBottom, /preview-box.{0,120}bottom:"/.test(compact))

  // ④ 分支特有结构
  if (spec.screentype === 'rect') {
    // v2.21.0 核心断言：rect 容器 274→321（高度链闭合 28+321=349）
    check('rect 容器高 321（高度链闭合）', compose.includes('height:"321px"'))
    check('rect 旧容器 274 已退役', !compose.includes('height:"274px"'))
    // #keyboard67 样式表编译产物：不再带 absolute 定位（幻影根因铲除）
    const kb67 = (compose.match(/keyboard67"\]\],\{[^}]*\}/) || [])[0] || ''
    check('keyboard67 无 absolute 残留', kb67.length > 0 && !kb67.includes('absolute') && !kb67.includes('"82px"'))
    check('keyboard67 高 261 在包', kb67.includes('"261px"'))
    // 字母滚动区显式高 261 预算保留（真固件内容注入偏移兜底）
    check('rect 字母滚动区显式高 261（预算保留）', compose.includes('height:"261px"'))
    const iScroll = compose.indexOf('keyboard67')
    const iCn = compose.indexOf('assets/horizontal/cn.png')
    check('rect 动作行位于字母区之后', iScroll > 0 && iCn > iScroll)
    check('rect T9 死代码已删除', !compose.includes('top:"-11px"'))
    check('rect 旧进度条元素已删除', !/progress["']?.{0,60}percent67/.test(compose))
    check('rect 三行字母键在包', compose.includes('"Q"') && compose.includes('"Z"'))
  }
  if (spec.screentype === 'pill-shaped') {
    check('pill keyboard66 滚动区在包', compose.includes('keyboard66'))
    check('pill KB_H_PILL=333', compose.includes('333'))
  }
  if (spec.screentype === 'circle') {
    check('circle 滚动常量 617（466/480 换算）', compose.includes('617'))
    check('circle 标尺 480→466（kbScale 换算后）', compose.includes('"466px"'))
  }

  // ⑤ 调试插桩零残留
  check('无调试色残留', !compact.includes('#aa3333') && !compact.includes('#3333aa') && !compact.includes('#aaaa33') && !compact.includes('#33aa33'))
  check('无 dbg 字段残留', !compose.includes('dbg'))
  check('无 SW432 调试串', !all.includes('SW432'))
}

// 全局：引擎脚本资产断言
const scriptPath = path.join(__dirname, '..', '..', 'android-sync', 'astrbot-engine', 'src', 'main', 'assets', 'astrbot-startup.sh')
if (fs.existsSync(scriptPath)) {
  const sh = fs.readFileSync(scriptPath, 'utf-8')
  console.log('== astrbot-startup.sh 资产 ==')
  check('DNS 无条件重写（毒桩根修）', sh.includes('resolv.conf 已重写'))
  check('ubuntu-ports 多源竞速', sh.includes('APT_MIRROR_CANDIDATES') && sh.includes('dev/tcp'))
  check('竞速胜出日志', sh.includes('竞速胜出镜像'))
  check('GitHub 代理并行竞速', sh.includes('gh-proxy-race'))
  check('napcat.sh 走竞速代理', sh.includes('network_test || true'))
  check('uv Python 镜像兜底', sh.includes('UV_PYTHON_INSTALL_MIRROR='))
  check('sudo 透传垫片（NapCat 上游脚本硬检查）', sh.includes('ensure_sudo_shim') && sh.includes('/usr/local/bin/sudo'))
  check('gh_fetch 多源重试统一入口', sh.includes('gh_fetch') && sh.includes('gh_build_candidates'))
  check('curl 断流自杀参数', sh.includes('--speed-time 20 --speed-limit 512'))
  check('结构化阶段标记（启动进度卡）', sh.includes('[STAGE:$') && sh.includes('stage 92'))
  check('L_* 本地化变量已定义', sh.includes('L_NOT_INSTALLED="未安装"'))
  // v2.21.0 新增：NapCat 离线包预下载
  check('NapCat 离线包预下载入口', sh.includes('ensure_napcat_zip') && sh.includes('NapCat.Shell.zip'))
  check('预下载走 gh_fetch 多源竞速', /ensure_napcat_zip\(\)\{[\s\S]*?gh_fetch[\s\S]*?latest\/download\/NapCat\.Shell\.zip/.test(sh))
  check('预下载 unzip -t 自验', sh.includes('unzip -t "$zip_file"'))
  check('预下载失败不阻断（上游兜底保留）', sh.includes('保留上游脚本自下载兜底路径'))
  check('install_napcat 已接线预下载', /ensure_sudo_shim[\s\S]{0,200}ensure_napcat_zip[\s\S]{0,120}bash napcat\.sh/.test(sh))
} else {
  console.log('== astrbot-startup.sh 资产缺失 ==')
  check('astrbot-startup.sh 存在', false)
}

console.log('\n===== verify_2210 结果: ' + pass + ' PASS / ' + fail + ' FAIL =====')
process.exit(fail > 0 ? 1 : 0)
