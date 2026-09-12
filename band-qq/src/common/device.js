/**
 * v2.10.0 机型分档适配（band / wide / round）
 *
 * 依据小米 Vela 官方多屏设备表：
 *   - 胶囊/窄矩形手环（band）  ：192×490（手环9/10/11 等）—— 原有布局，零回归
 *   - 矩形大屏手表（wide）     ：432×514（Redmi Watch 5 / 6）—— 视口 192×228，紧凑化覆盖
 *   - 圆形手表（round）        ：466×466（Watch S3/S4）、480×480（Watch S5/S1 Pro）—— 视口 192×192，圆弧安全区覆盖
 *
 * device.getInfo() 返回 screenWidth/screenHeight（物理像素）与 screenShape（circle 等）。
 * 自定义分辨率虚拟机可能不上报 screenShape，故按长宽比兜底推断。
 * designWidth 恒为 192：px 单位随屏宽等比缩放，排版相对比例全机型一致，
 * 分档只覆盖结构性尺寸（行高/边距/键盘），不动字体比例。
 */

const profile = {
  cls: 'band',        // band | wide | round
  shape: '',          // 官方 screenShape 原始值
  w: 0,               // 物理宽
  h: 0,               // 物理高
  kbType: 'pill-shaped', // InputMethod screentype：band=pill-shaped / wide=rect / round=circle
  kbScale: 1,            // 键盘缩放系数（band=1 不包装）
  kbW: 192,              // 键盘包装内层宽（design px）
  kbH: 305,              // 键盘包装内层高（design px）
  kbLeft: 0,             // 内层 left 偏移（design px）
  kbOrigin: 'bottom left',
  kbSlotH: 305           // 键盘包装占位高（design px，transform 后视觉高度）
}

let inited = false

function classify(shape, w, h) {
  let eff = shape || ''
  if (!eff && w && h) {
    const ar = w / h
    eff = ar > 0.88 ? 'circle' : (ar >= 0.75 ? 'rect' : 'pill-shaped')
  }
  if (eff === 'circle') return 'round'
  // Redmi Watch 5/6：432×514（AR 0.84）；8 Pro/9 Pro 336×480（AR 0.70）仍按 band 渲染不回归
  if (w && h && w >= 380 && (w / h) >= 0.75) return 'wide'
  if (eff === 'rect' && w && h && (w / h) >= 0.75) return 'wide'
  return 'band'
}

/** 键盘包装参数：InputMethod 的 rect/circle 变体按「designWidth≈物理宽」设计内尺寸，
 *  在 designWidth=192 的大屏上会放大 w/192 倍 → 等比 scale(192/w) 收回，
 *  圆屏额外缩到 0.34 并居中（底部圆弧越靠下越窄，全宽键盘必被弧线裁切）。 */
function kbParams(cls, w) {
  if (cls === 'wide' && w) {
    const s = +(192 / w).toFixed(4)
    return { kbType: 'rect', kbScale: s, kbW: w, kbH: 255, kbLeft: 0, kbOrigin: 'bottom left', kbSlotH: Math.round(255 * s) }
  }
  if (cls === 'round') {
    return { kbType: 'circle', kbScale: 0.34, kbW: 480, kbH: 321, kbLeft: -144, kbOrigin: 'bottom center', kbSlotH: 109 }
  }
  return { kbType: 'pill-shaped', kbScale: 1, kbW: 192, kbH: 305, kbLeft: 0, kbOrigin: 'bottom left', kbSlotH: 305 }
}

export default {
  init(onChange) {
    if (inited) return
    inited = true
    try {
      const device = require('@system.device')
      device.getInfo({
        success: (d) => {
          const w = d.screenWidth || 0
          const h = d.screenHeight || 0
          const cls = classify(d.screenShape || '', w, h)
          const kb = kbParams(cls, w)
          Object.assign(profile, { cls, shape: d.screenShape || '', w, h }, kb)
          console.log('[BANDQQ] device profile:', JSON.stringify(profile))
          if (typeof onChange === 'function') onChange(profile)
        },
        fail: (e) => {
          console.error('[BANDQQ] device.getInfo fail', e && e.message)
          if (typeof onChange === 'function') onChange(profile)
        }
      })
    } catch (e) {
      console.error('[BANDQQ] device init error', e && e.message)
    }
  },
  get() {
    return profile
  },
  cls() {
    return profile.cls
  }
}
