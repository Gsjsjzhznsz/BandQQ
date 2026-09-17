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


/**
 * v2.11.0 机型差异内联样式表（唯一可靠通道）。
 * 实证链：aiot 编译器把 class 属性里的混合 token（item-{{dc}}）吞成静态前缀，
 * 动态类绑定在编译期即失效（v2.10.0 分档从未生效的根因）；而内联 style 的
 * mustache 绑定正常工作（头像 hueBg 动态背景色久经验证）。
 * 键名约定见各页面模板；band 档全部为空串 = 完全走 class 基线（零回归）。
 */
const DEVICE_STYLES = {
  wide: {
    topRow: 'height:22px',
    btn: 'width:30px;height:21px',
    list: 'padding:3px 6px 0 6px',
    item: 'height:35px;margin-bottom:3px;border-radius:8px',
    avatar: 'width:24px;height:24px;border-radius:12px;margin-left:5px;margin-right:5px',
    avatarText: 'font-size:11px',
    itemName: 'font-size:12px;max-width:108px',
    itemTag: 'font-size:7px;border-radius:3px;padding:0 3px;margin-left:3px',
    itemAt: 'font-size:7px;border-radius:3px;padding:0 3px;margin-left:3px',
    itemPrev: 'font-size:9px;margin-top:2px;max-width:100px',
    right: 'width:24px;height:35px;padding:4px 5px 0 0',
    itemTime: 'font-size:8px',
    badge: 'min-width:15px;height:13px;border-radius:7px;right:4px;bottom:5px',
    badgeText: 'font-size:8px;padding:0 3px',
    emptyIcon: 'width:40px;height:40px',
    emptyText: 'font-size:11px;margin-top:6px',
    statusBar: 'height:13px',
    statusText: 'font-size:8px',
    menu: 'padding:8px 10px 14px 10px',
    menuTitle: 'font-size:11px;margin-bottom:4px',
    menuRow: 'font-size:13px;padding:9px 0',
    btnBack: 'width:30px;height:21px',
    titleBar: 'height:16px',
    pageTitle: 'font-size:12px;max-width:150px',
    sendStatus: 'height:0px;overflow:hidden',
    olderWrap: 'height:16px',
    olderBtn: 'font-size:9px;padding:2px 8px',
    msgItem: 'padding:0 6px;margin-top:5px',
    msgCol: 'max-width:132px',
    msgName: 'font-size:9px;margin-bottom:2px',
    atBadge: 'font-size:8px;line-height:11px;border-radius:4px;padding:0 4px;margin-bottom:2px',
    bubble: 'max-width:124px;padding:4px 7px;border-radius:8px;font-size:11px;line-height:15px',
    bubbleRc: 'font-size:9px',
    pokeWrap: 'margin:3px 0',
    pokeBubble: 'font-size:10px;line-height:14px;border-radius:10px;padding:3px 8px',
    quickArea: 'height:22px',
    quickRow: 'height:22px;padding:0 4px',
    quickBtn: 'height:17px;border-radius:9px;margin:0 3px;padding:0 9px',
    quickText: 'font-size:9px',
    inputBar: 'height:24px',
    sendToast: 'bottom:56px',
    topBar: 'height:24px;padding:0 6px',
    doneBtn: 'width:58px;height:21px;line-height:21px;font-size:11px;border-radius:11px',
    previewBox: 'padding:4px 8px',
    previewScroll: 'width:100%',
    previewText: 'font-size:13px;line-height:19px',
    confirmBox: 'width:168px;padding:10px;border-radius:10px',
    confirmTitle: 'font-size:12px;margin-bottom:5px',
    confirmDescScroll: 'width:144px;height:92px',
    confirmDesc: 'font-size:10px;line-height:15px',
    confirmBtns: 'margin-top:9px',
    confirmCancel: 'padding:7px 0;font-size:11px;border-radius:9px;margin-right:8px',
    confirmOk: 'padding:7px 0;font-size:11px;border-radius:9px',
    row: 'height:34px;margin:4px 8px;padding:0 10px;border-radius:9px',
    rowLabel: 'font-size:12px',
    rowValue: 'font-size:12px',
    section: 'font-size:8px;padding:8px 10px 1px 10px',
    tailSpace: 'height:8px',
    hero: 'margin:6px 8px 2px 8px',
    icon: 'width:44px;height:44px;border-radius:11px',
    appName: 'font-size:15px;margin-top:4px',
    ver: 'font-size:9px;margin-top:1px',
    demoHint: 'font-size:9px;margin-top:3px',
    card: 'margin:3px 6px;padding:7px 10px;border-radius:10px',
    line: 'font-size:10px;line-height:14px',
    k: 'font-size:8px',
    v: 'font-size:11px;margin-top:1px',
    // v2.11.4 黑屏根修：about/settings 的 scroll 主体与 chat 的发送状态文本引用本表，
    // 键缺失时 style 绑定为 undefined → Vela 渲染层整棵子树渲染失败（页面只剩纯黑背景）；
    // 空串 = 完全走 class 基线，与 main 2.9.3 渲染一致
    body: '',
    sendStatusText: ''
  },
  round: {
    topRow: 'height:20px',
    btn: 'width:28px;height:20px',
    list: 'padding:3px 16px 0 16px',
    item: 'height:33px;margin-bottom:3px;border-radius:8px',
    avatar: 'width:23px;height:23px;border-radius:12px;margin-left:4px;margin-right:5px',
    avatarText: 'font-size:10px',
    itemName: 'font-size:11px;max-width:100px',
    itemTag: 'font-size:7px;border-radius:3px;padding:0 3px;margin-left:3px',
    itemAt: 'font-size:7px;border-radius:3px;padding:0 3px;margin-left:3px',
    itemPrev: 'font-size:9px;margin-top:2px;max-width:96px',
    right: 'width:22px;height:33px;padding:4px 4px 0 0',
    itemTime: 'font-size:8px',
    badge: 'min-width:14px;height:12px;border-radius:6px;right:3px;bottom:5px',
    badgeText: 'font-size:8px;padding:0 3px',
    emptyIcon: 'width:36px;height:36px',
    emptyText: 'font-size:10px;margin-top:5px',
    statusBar: 'height:11px',
    statusText: 'font-size:8px',
    menu: 'padding:7px 12px 30px 12px',
    menuTitle: 'font-size:11px;margin-bottom:3px',
    menuRow: 'font-size:12px;padding:8px 0',
    btnBack: 'width:28px;height:20px',
    titleBar: 'height:14px',
    pageTitle: 'font-size:11px;max-width:140px',
    sendStatus: 'height:0px;overflow:hidden',
    olderWrap: 'height:15px',
    olderBtn: 'font-size:9px;padding:2px 8px',
    msgItem: 'padding:0 14px;margin-top:4px',
    msgCol: 'max-width:128px',
    msgName: 'font-size:8px;margin-bottom:2px',
    atBadge: 'font-size:8px;line-height:10px;border-radius:4px;padding:0 4px;margin-bottom:2px',
    bubble: 'max-width:118px;padding:4px 7px;border-radius:8px;font-size:10px;line-height:14px',
    bubbleRc: 'font-size:9px',
    pokeWrap: 'margin:3px 0',
    pokeBubble: 'font-size:9px;line-height:13px;border-radius:10px;padding:3px 8px',
    quickArea: 'height:20px',
    quickRow: 'height:20px;padding:0 20px',
    quickBtn: 'height:16px;border-radius:8px;margin:0 2px;padding:0 8px',
    quickText: 'font-size:8px',
    inputBar: 'height:20px;border-top:none',
    sendToast: 'bottom:44px',
    topBar: 'height:22px;padding:0 8px',
    doneBtn: 'width:52px;height:19px;line-height:19px;font-size:10px;border-radius:10px',
    previewBox: 'padding:3px 14px',
    previewScroll: 'width:100%',
    previewText: 'font-size:12px;line-height:18px',
    confirmBox: 'width:152px;padding:9px;border-radius:10px',
    confirmTitle: 'font-size:11px;margin-bottom:4px',
    confirmDescScroll: 'width:130px;height:84px',
    confirmDesc: 'font-size:10px;line-height:14px',
    confirmBtns: 'margin-top:8px',
    confirmCancel: 'padding:6px 0;font-size:10px;border-radius:8px;margin-right:7px',
    confirmOk: 'padding:6px 0;font-size:10px;border-radius:8px',
    row: 'height:30px;margin:3px 0;padding:0 9px;border-radius:8px',
    rowLabel: 'font-size:11px',
    rowValue: 'font-size:11px',
    section: 'font-size:8px;padding:7px 9px 0 9px',
    tailSpace: 'height:8px',
    hero: 'margin:4px 8px 0 8px',
    icon: 'width:40px;height:40px;border-radius:10px',
    appName: 'font-size:14px;margin-top:3px',
    ver: 'font-size:8px;margin-top:1px',
    demoHint: 'font-size:8px;margin-top:2px',
    card: 'margin:3px 2px;padding:6px 10px;border-radius:9px',
    line: 'font-size:9px;line-height:13px',
    k: 'font-size:8px',
<<<<<<< HEAD
    v: 'font-size:10px;margin-top:1px'
=======
    v: 'font-size:10px;margin-top:1px',
    // v2.11.4 黑屏根修：同 wide 段，round 直接引用本对象，缺键必须单独补
    body: '',
    sendStatusText: ''
  },
  /**
   * v2.11.0-pro bandpro 档（手环 9/10 Pro 专用分支）：336×480 视口定向设计。
   * 分支 manifest designWidth=336 → design px = VM 物理 px 1:1。
   * 相对 192 基线的取舍：屏幕宽 +75% 高 -2% → 列表项压矮多显一行、
   * 名字/气泡加宽吃满横向空间、顶部按钮与状态栏收缩还空间给内容。
   */
  bandpro: {
    skbRow: 'margin:4px 0',
    skbKey: 'width:29px;height:40px;margin:0 2px;border-radius:9px;line-height:40px;font-size:17px',
    skbFn: 'width:36px;height:40px;margin:0 2px;border-radius:9px;line-height:40px;font-size:14px',
    skbSpace: 'width:100px;height:40px;border-radius:9px;line-height:40px;font-size:15px',
    topRow: 'height:46px;padding-top:4px',
    btn: 'width:60px;height:42px',
    list: 'padding:4px 10px 0 10px',
    item: 'height:70px;margin-bottom:4px;border-radius:14px',
    avatar: 'width:40px;height:40px;border-radius:20px;margin-left:10px;margin-right:8px',
    avatarText: 'font-size:18px',
    itemName: 'font-size:18px;max-width:170px',
    itemTag: 'font-size:11px;border-radius:5px;padding:0 4px;margin-left:5px',
    itemAt: 'font-size:11px;border-radius:5px;padding:1px 5px;margin-left:5px',
    itemPrev: 'font-size:14px;margin-top:3px;max-width:190px',
    right: 'width:56px;height:70px;padding:8px 10px 0 0',
    itemTime: 'font-size:12px',
    badge: 'min-width:22px;height:20px;border-radius:10px;right:6px;bottom:10px',
    badgeText: 'font-size:12px;padding:0 4px',
    emptyIcon: 'width:64px;height:64px',
    emptyText: 'font-size:15px;margin-top:8px',
    statusBar: 'height:44px',
    statusText: 'font-size:17px',
    menu: 'padding:12px 14px 16px 14px',
    menuTitle: 'font-size:15px;margin-bottom:6px',
    menuRow: 'font-size:17px;padding:11px 0',
    btnBack: 'width:60px;height:42px',
    titleBar: 'height:26px',
    pageTitle: 'font-size:19px;max-width:220px',
    sendStatus: 'height:18px',
    olderWrap: 'height:28px',
    olderBtn: 'font-size:13px;padding:3px 10px',
    msgItem: 'width:316px;padding:0 10px;margin-top:7px',
    msgCol: 'max-width:230px',
    msgName: 'font-size:12px;margin-bottom:2px',
    atBadge: 'font-size:11px;line-height:15px;border-radius:5px;padding:1px 5px;margin-bottom:2px',
    bubble: 'max-width:210px;padding:6px 9px;border-radius:10px;font-size:16px;line-height:22px',
    bubbleRc: 'font-size:13px',
    pokeWrap: 'width:316px;margin:4px 0',
    pokeBubble: 'font-size:14px;line-height:19px;border-radius:12px;padding:4px 10px',
    quickArea: 'height:46px',
    quickRow: 'height:46px;padding:0 5px',
    quickBtn: 'height:34px;border-radius:17px;margin:0 3px;padding:0 10px',
    quickText: 'font-size:14px',
    inputBar: 'height:56px',
    sendToast: 'bottom:60px',
    doneBtn: 'width:64px;height:24px;line-height:24px;font-size:14px;border-radius:12px',
    previewBox: 'padding:4px 16px',
    previewScroll: 'width:100%',
    previewText: 'font-size:14px;line-height:20px',
    confirmBox: 'width:210px;padding:11px;border-radius:12px',
    confirmTitle: 'font-size:14px;margin-bottom:5px',
    confirmDescScroll: 'width:180px;height:110px',
    confirmDesc: 'font-size:13px;line-height:18px',
    confirmBtns: 'margin-top:9px',
    confirmCancel: 'padding:7px 0;font-size:13px;border-radius:9px;margin-right:8px',
    confirmOk: 'padding:7px 0;font-size:13px;border-radius:9px',
    row: 'height:44px;margin:3px 0;padding:0 12px;border-radius:10px',
    rowLabel: 'font-size:16px',
    rowValue: 'font-size:16px',
    section: 'font-size:12px;padding:9px 12px 0 12px',
    tailSpace: 'height:10px',
    hero: 'margin:5px 10px 0 10px',
    icon: 'width:52px;height:52px;border-radius:12px',
    appName: 'font-size:20px;margin-top:4px',
    ver: 'font-size:12px;margin-top:2px',
    demoHint: 'font-size:12px;margin-top:3px',
    card: 'margin:4px 4px;padding:8px 12px;border-radius:11px',
    line: 'font-size:13px;line-height:18px',
    k: 'font-size:12px',
    v: 'font-size:14px;margin-top:1px'
>>>>>>> 81cc920 (v2.11.4-pro(vc99): 黑屏根修——DEVICE_STYLES 补 body/sendStatusText 缺失键(style 绑定 undefined 致 Vela 整棵子树渲染失败→纯黑背景)，wide+round 双段补齐，band/bandpro 自动继承)
  }
}

function stylesFor(cls) {
  const wide = DEVICE_STYLES.wide
  const round = DEVICE_STYLES.round
  if (cls === 'wide') return wide
  if (cls === 'round') return round
  // band：全键空串（模板统一 style="{{ds.xxx}}"，空串=完全走 class 基线）
  const band = {}
  Object.keys(wide).forEach((k) => { band[k] = '' })
  return band
}

let inited = false

function classify(shape, w, h) {
  /**
   * v2.11.0 几何优先判定（VVD 四机实证的根因修复）：
   * vela-watch-5.0 镜像对 212×520 胶囊屏也误报 screenShape='circle'，
   * v2.10.0 的 shape 优先判定导致四个机型全部套上 round 主题
   * （band 行高被压成 round 档 33 设计 px，wide 档从未生效）——
   * 这就是「每个机型观感都怪」的总根因。小米穿戴目录里：
   *   胶囊/长条屏 AR < 0.75（手环全系、8 Pro/9 Pro 336×480=0.70）
   *   方形屏 AR ≥ 0.95 全是圆表（S1~S5 454~480 方屏，无方表）
   *   中间档大宽屏即 RW5/6（432×514=0.84）
   * 故按长宽比三分，官方 shape 只在无尺寸/异常时兜底。
   */
  if (!w || !h) return shape === 'circle' ? 'round' : 'band'
  const ar = w / h
  if (ar < 0.75) return 'band'
  if (ar >= 0.95) return 'round'
  if (w >= 380) return 'wide'
  if (shape === 'circle') return 'round'
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
    // v2.11.3 白屏死机兜底：部分固件 device.getInfo 既不回调 success 也不回调 fail，
    // 800ms 后仍未就绪则强制按 band 基线放行（事件到达后页面自动刷新为真实档位）
    const watchdog = setTimeout(() => {
      if (profile.ok !== true) {
        profile.ok = true
        try { console.error('[BANDQQ] device profile watchdog fired') } catch (x) {}
        if (typeof onChange === 'function') onChange(profile)
      }
    }, 800)
    try {
      const device = require('@system.device')
      device.getInfo({
        success: (d) => {
          clearTimeout(watchdog)
          const w = d.screenWidth || 0
          const h = d.screenHeight || 0
          const cls = classify(d.screenShape || '', w, h)
          const kb = kbParams(cls, w)
          Object.assign(profile, { cls, shape: d.screenShape || '', w, h, ok: true }, kb)
          console.log('[BANDQQ] device profile:', JSON.stringify(profile))
          if (typeof onChange === 'function') onChange(profile)
        },
        fail: (e) => {
          clearTimeout(watchdog)
          console.error('[BANDQQ] device.getInfo fail', e && e.message)
          profile.ok = true
          if (typeof onChange === 'function') onChange(profile)
        }
      })
    } catch (e) {
      clearTimeout(watchdog)
      console.error('[BANDQQ] device init error', e && e.message)
      // v2.11.3 白屏死机兜底：catch 路径必须同样判定就绪（band 基线），
      // 否则 ready() 恒假 → 全部页面根节点 if 门控永不放行 → 整机白屏死机
      profile.ok = true
      if (typeof onChange === 'function') onChange(profile)
    }
  },
  get() {
    return profile
  },
  /** v2.11.0：profile 是否已定型（getInfo 成功或失败兜底后都算就绪）。
   *  引擎只在节点首次创建时解析样式，dc 必须在首次渲染前正确 → 页面用 dcReady 门控挂载 */
  ready() {
    return profile.ok === true
  },
  cls() {
    return profile.cls
  },
  stylesFor
}
