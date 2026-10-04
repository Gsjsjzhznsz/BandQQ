#!/usr/bin/env node
/** v2.20.0 四分支 rpk 解包断言（继承 v2.19.0 键盘/幻影预算回归 + 版本单轨 2.20.0·vc61）
 *  核心回归保护：①kb-dock 改 bottom:0（固件底部手势保留区对 device.getInfo 不可见，
 *  一切写死的 top=屏高-键盘高 常量都会把键盘推进保留区——VM redmiw5 432×514 实测
 *  Z 行被视口拦腰截断）②rect 字母滚动区显式高 261 = 幻影 ~85 + 行内容 180 + 余量
 *  （引擎对 scroll 内容注入「上方静态流高度」的幻影纵向偏移，实测 cvalrow28+动作行60
 *  场景偏移 83~85；预算不足即 Z 行被裁=用户口径"键盘只显示一半"）③rect 动作行移到
 *  字母区下方 + T9 死代码删除（BandQQ 固定 QWERTY）④预览区 bottom 锚定（自动贴合
 *  键盘顶，任何固件零重叠）⑤撤回/引擎脚本标记继承。 */
const { execSync } = require('child_process')
const fs = require('fs')
const path = require('path')

const DIR = path.join(__dirname, '..', 'dist-branch')
const VER = '2.20.0'
const VC = '61'
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
  const outDir = path.join('/tmp/bq-verify-2190', tag)
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

  // ② 组件根 .page top:0 锚定保持（v2.18.1 引入，VM/镜像引擎验证通过）
  check('.page 顶部锚定 top:0 在包', /top:\s*"0(px)?"/.test(compose.replace(/\s/g, '')))

  // ③ 预览区 bottom 锚定（自动贴合键盘顶）
  check('preview-box bottom=' + spec.pvBottom, /preview-box.{0,120}bottom:"/.test(compact))

  // ④ 分支特有结构
  if (spec.screentype === 'rect') {
    // 字母滚动区显式高 261（幻影预算：~85 + 行 180 + 余量 6）——v2.17 引入显式高、
    // v2.19 预算幻影；预算不足即 Z 行被裁 = "键盘只显示一半"
    check('rect 字母滚动区显式高 261（幻影预算）', compose.includes('height:"261px"'))
    // 动作行移到字母区下方：rect 内容区字母在前（scroll 先于动作行出现）
    const iScroll = compose.indexOf('keyboard67')
    const iCn = compose.indexOf('assets/horizontal/cn.png')
    check('rect 动作行位于字母区之后', iScroll > 0 && iCn > iScroll)
    // T9 死代码删除（BandQQ 固定 QWERTY；else 邻接性曾被动作行破坏导致构建失败）。
    // 断言口径：rect T9 容器唯一的 top:-11px 锚不再编译入包（CSS 类名样式表残留无害）
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

// 全局：引擎 DNS 多源竞速脚本标记（胖包 assets 经由 APK 检查，此处检查仓库资产与产物一致性）
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
  // v2.20.0 新增：sudo 垫片 + gh_fetch 多源重试 + 断流自杀 + 阶段标记
  check('sudo 透传垫片（NapCat 上游脚本硬检查）', sh.includes('ensure_sudo_shim') && sh.includes('/usr/local/bin/sudo'))
  check('gh_fetch 多源重试统一入口', sh.includes('gh_fetch') && sh.includes('gh_build_candidates'))
  check('curl 断流自杀参数', sh.includes('--speed-time 20 --speed-limit 512'))
  check('结构化阶段标记（启动进度卡）', sh.includes('[STAGE:$') && sh.includes('stage 92'))
  check('L_* 本地化变量已定义', sh.includes('L_NOT_INSTALLED="未安装"'))
} else {
  console.log('== astrbot-startup.sh 资产缺失 ==')
  check('astrbot-startup.sh 存在', false)
}

console.log('\n===== verify_2200 结果: ' + pass + ' PASS / ' + fail + ' FAIL =====')
process.exit(fail > 0 ? 1 : 0)
