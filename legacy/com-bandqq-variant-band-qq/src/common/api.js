/**
 * BandQQ 手环端连接层 v1.1.1
 * - @system.websocketfactory 多形状适配（Vela 各版本 API 差异兜底）
 * - v1.1.1: ensureAlive() 冷启动自愈增强：通道失效重建 + 就绪门控 + 重拉全量状态
 */
let wsImpl = null
let task = null
let connPromise = null
let lastHandlers = null
let ready = false
let closedByUser = false

function loadImpl() {
  if (wsImpl) return wsImpl
  const candidates = ['@system.websocketfactory', '@system.websocket', 'system.websocketfactory']
  for (const name of candidates) {
    try {
      const m = typeof require === 'function' ? require(name) : null
      if (m) { wsImpl = m; break }
    } catch (e) { /* continue */ }
  }
  return wsImpl
}

function notify(handlers, ev, payload) {
  const fn = handlers && handlers['on' + ev]
  if (typeof fn === 'function') {
    try { fn(payload) } catch (e) {}
  }
}

/**
 * 连接（幂等）。返回 Promise<boolean>：resolve(true)=就绪，false=失败。
 * handlers: { onMessage(text), onOpen(), onClose() }
 */
export function connect(url, handlers, timeoutMs) {
  lastHandlers = handlers
  closedByUser = false
  if (connPromise) return connPromise
  ready = false
  connPromise = new Promise((resolve) => {
    const impl = loadImpl()
    if (!impl) { resolve(false); return }
    let settled = false
    const finish = (ok) => { if (!settled) { settled = true; ready = ok; resolve(ok) } }
    const guard = setTimeout(() => finish(false), timeoutMs || 10000)

    const bind = (t) => {
      task = t
      // 兼容两种事件模型：属性回调(onopen/onmessage) 与 on(...) 注册
      if (typeof t.on === 'function' && typeof t.onopen !== 'function') {
        t.on('open', () => { clearTimeout(guard); finish(true); notify(handlers, 'Open') })
        t.on('message', (ev) => { const d = ev && (ev.data !== undefined ? ev.data : ev); notify(handlers, 'Message', typeof d === 'object' && d !== null && 'data' in d ? d.data : d) })
        t.on('close', () => { ready = false; connPromise = null; notify(handlers, 'Close'); if (!closedByUser) scheduleReconnect(url, handlers) })
        t.on('error', () => { clearTimeout(guard); finish(false) })
      } else {
        t.onopen = () => { clearTimeout(guard); finish(true); notify(handlers, 'Open') }
        t.onmessage = (ev) => {
          const raw = ev && (ev.data !== undefined ? ev.data : ev)
          const text = raw && typeof raw === 'object' && 'data' in raw ? raw.data : raw
          notify(handlers, 'Message', typeof text === 'string' ? text : (text && text.data !== undefined ? text.data : text))
        }
        t.onclose = () => { ready = false; connPromise = null; notify(handlers, 'Close'); if (!closedByUser) scheduleReconnect(url, handlers) }
        t.onerror = () => { clearTimeout(guard); finish(false) }
      }
    }

    try {
      // 形状 B：createWebSocket({url, ...})
      if (typeof impl.createWebSocket === 'function') {
        bind(impl.createWebSocket({ url }))
        return
      }
      // 形状 A/C：connect(...) 返回 task 或经 success 回调
      if (typeof impl.connect === 'function') {
        const ret = impl.connect({
          url,
          success: (data) => {
            const t = data && data.ref ? data.ref : data
            if (t && typeof t.send === 'function') { bind(t); return }
            // 连接即成功型
            clearTimeout(guard); finish(true)
          },
          fail: () => { clearTimeout(guard); finish(false) }
        })
        if (ret && (typeof ret.send === 'function' || typeof ret.on === 'function')) { bind(ret); return }
        if (ret === undefined || ret === null) return // success 回调型
        return
      }
      // 形状 D：open(...)
      if (typeof impl.open === 'function') {
        impl.open({ url, success: (data) => { clearTimeout(guard); finish(true) }, fail: () => { clearTimeout(guard); finish(false) } })
        return
      }
      clearTimeout(guard); finish(false)
    } catch (e) {
      clearTimeout(guard); finish(false)
    }
  })
  return connPromise
}

let reconnectTimer = null
function scheduleReconnect(url, handlers) {
  if (reconnectTimer || closedByUser) return
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    connPromise = null
    connect(url, handlers, 10000)
  }, 4000)
}

export function send(text) {
  if (!task || typeof task.send !== 'function' || !ready) return false
  try { task.send(text); return true } catch (e) { return false }
}

export function isConnected() { return ready }

export function disconnect() {
  closedByUser = true
  ready = false
  try { task && task.close && task.close() } catch (e) {}
  task = null
  connPromise = null
}

/**
 * v1.1.1 冷启动自愈：
 * 通道宣称已连接但请求无响应 / onClose 后重连成功，需重拉全量状态。
 * 返回 true 表示这次调用实际建立了新连接（调用方应随后 refresh）。
 */
export async function ensureAlive(url, handlers, sendRefresh) {
  const wasReady = ready
  if (connPromise) {
    const ok = await Promise.race([connPromise, new Promise((r) => setTimeout(() => r(false), 10000))])
    if (ok && ready) {
      // 通道活着：探测一次
      const probeOk = sendRefresh()
      return !wasReady
    }
    connPromise = null
  }
  await connect(url, handlers, 10000)
  return ready
}
