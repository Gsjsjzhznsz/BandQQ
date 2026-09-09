// 惰性获取系统 interconnect，避免在 Node 测试环境中解析 @system.interconnect（该模块仅存在于 Vela 运行时）
// 使用 Vela 快应用支持的 require() 方式加载系统模块
let systemInterconnect = null
function getSystemInterconnect() {
  if (!systemInterconnect) {
    try {
      systemInterconnect = require('@system.interconnect')
    } catch (e) {
      systemInterconnect = null
    }
  }
  return Promise.resolve(systemInterconnect)
}

export function createApi(interconnectImpl) {
  const ic = interconnectImpl || null
  // 注入实现（单测）时同步建立连接；默认实现惰性加载。连接就绪统一由 onopen 决定。
  let conn = ic ? ic.instance() : null
  let connected = false
  let messageHandler = null
  let connPromise = null

  // 连接就绪门控：真实场景下业务帧需等 onopen 后才发送，避免通道未开导致首拉丢失
  const READY_TIMEOUT_MS = 10000
  let readyPromise = null
  let readyResolve = null
  let readyTimer = null

  function markReady() {
    connected = true
    if (readyResolve) {
      clearTimeout(readyTimer)
      readyResolve()
      readyResolve = null
      readyPromise = null
      readyTimer = null
    }
  }

  function waitReady() {
    if (connected) return Promise.resolve()
    if (!readyPromise) {
      readyPromise = new Promise((resolve) => {
        readyResolve = resolve
        // 超时兜底：长时间未确认 onopen 也继续尝试发送，失败由 send fail 回调反映
        readyTimer = setTimeout(() => {
          if (readyResolve) {
            readyResolve()
            readyResolve = null
          }
          readyPromise = null
          readyTimer = null
        }, READY_TIMEOUT_MS)
      })
    }
    return readyPromise
  }

  function ensureConn() {
    if (conn) return Promise.resolve(conn)
    if (!connPromise) {
      connPromise = getSystemInterconnect().then((impl) => {
        conn = impl.instance()
        // 未注入实现时，init 可能已在连接就绪前被调用，这里补注册
        return conn
      })
    }
    return connPromise
  }

  function registerOn(c, handlers) {
    messageHandler = handlers.onMessage || null
    // Vela interconnect 的事件回调为属性赋值式（connect.onmessage = fn），非方法调用
    c.onmessage = (res) => {
      try {
        const msg = JSON.parse(res.data)
        if (messageHandler) messageHandler(msg)
      } catch (e) {
        console.error('onmessage parse error', e)
      }
    }
    c.onopen = (data) => {
      markReady()
      if (handlers.onOpen) handlers.onOpen(data)
    }
    c.onclose = (data) => {
      connected = false
      if (handlers.onClose) handlers.onClose(data)
    }
    c.onerror = (data) => {
      connected = false
      if (handlers.onError) handlers.onError(data)
    }
  }

  function init(handlers) {
    lastHandlers = handlers || null
    if (conn) {
      // 注入场景：同步注册，确保事件处理器立即可用
      registerOn(conn, handlers)
    } else {
      // 默认场景：异步惰性建立连接后注册
      ensureConn().then((c) => registerOn(c, handlers)).catch((e) => console.error('init connect error', e))
    }
  }

  let lastHandlers = null

  /**
   * 冷启动自愈：互联通道断开（onclose/onerror 后 connected=false）时，
   * 重建实例并重挂回调。长期后台后页面 onShow 调用，避免「再次打开不能用」。
   */
  function ensureAlive() {
    if (connected) return Promise.resolve(true)
    connPromise = null
    conn = null
    return ensureConn()
      .then((c) => {
        if (lastHandlers) registerOn(c, lastHandlers)
        return waitReady().then(() => true, () => false)
      })
      .catch(() => Promise.resolve(false))
  }

  function send(payload) {
    return new Promise((resolve, reject) => {
      // 就绪门控：等 onopen 后再发，避免互联通道未开时首帧被丢弃
      ensureConn()
        .then((c) => waitReady().then(() => c))
        .then((c) => c.send({
          data: payload,
          success: () => resolve(),
          fail: (data, code) => {
            console.log('[BANDQQ] v9 api.send fail', code, JSON.stringify(data && data.ts && {}) )
            reject({ data, code })
          }
        }))
        .catch((e) => { console.log('[BANDQQ] v9 api.send throw', String(e)) ; reject(e) })
    })
  }

  function connectStatus() {
    return new Promise((resolve, reject) => {
      ensureConn()
        .then((c) => waitReady().then(() => c.diagnosis({
          success: (data) => resolve(data.status === 0),
          fail: (data, code) => reject({ data, code })
        })))
        .catch(reject)
    })
  }

  function isConnected() {
    return connected
  }

  return { init, send, connectStatus, isConnected, ensureAlive }
}

const api = createApi()
export default api