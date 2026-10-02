/**
 * v2.10.0 设备分支档案（构建期锁定）
 * 通用包 PROFILE='auto'（= band 形态参数兜底）；分支包由 tools/branch-release.js
 * 构建期把 PROFILE 替换为 'band'/'bandpro'/'xiaomis'/'redmiwatch'。
 *
 * 分支矩阵（与 branch-release.js BRANCHES 保持一致）：
 *   band       —— Xiaomi Smart Band 8/9/10/11 系（192x490 胶囊，designWidth 192，1css=1物理px）
 *   bandpro    —— Smart Band 8Pro/9Pro/10Pro 系（336x480 方屏，designWidth 336）
 *   xiaomis    —— Xiaomi Watch S3/S4/S5 系（466x466 圆屏，designWidth 466）
 *   redmiwatch —— REDMI Watch 5/5 eSIM/6 系（432x514 方屏，designWidth 432）
 *
 * 为什么不用单一 designWidth：固件换算公式为 物理 px = css px × 物理屏宽 / designWidth。
 * 192 标尺在 RW5E(432 宽) 上一切尺寸被放大 2.25 倍 = 用户实测「界面缩放异常」本体；
 * 分支包把 designWidth 对准各系列物理宽后 1css≈1物理px，再按分支系数(k)重写样式值
 * （k>1 适度放大可读性并显示更多内容，非等比缩放），由构建期换算器完成。
 */
export const PROFILE = 'auto'

const PROFILES = {
  auto: { tag: '', designWidth: 192, renderCap: 30, pageSize: 15, pollChatMs: 4000, pollListMs: 20000 },
  band: { tag: 'band', designWidth: 192, renderCap: 30, pageSize: 15, pollChatMs: 4000, pollListMs: 20000 },
  bandpro: { tag: 'bandpro', designWidth: 336, renderCap: 42, pageSize: 18, pollChatMs: 4000, pollListMs: 20000 },
  xiaomis: { tag: 'xiaomis', designWidth: 466, renderCap: 40, pageSize: 18, pollChatMs: 4000, pollListMs: 20000 },
  redmiwatch: { tag: 'redmiwatch', designWidth: 432, renderCap: 45, pageSize: 18, pollChatMs: 4000, pollListMs: 20000 }
}

/** 取当前分支档案；未知 PROFILE 回退 auto（防构建器改写异常导致 undefined 崩溃） */
export function profile() {
  return PROFILES[PROFILE] || PROFILES.auto
}

// 命名导出：供 Node 单测按需引入（快应用侧统一走 default/命名皆可）
export { PROFILES }
