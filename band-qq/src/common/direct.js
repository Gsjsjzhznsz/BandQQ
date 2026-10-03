/**
 * v2.10.0 eSIM 独立通讯线路（直连 NapCat HTTP 服务器）
 *
 * 背景：互联通道（interconnect）依赖手机 APP 做网关；Redmi Watch 5 eSIM /
 * Watch S4 eSIM / Xiaomi 15th 等 eSIM 机型自带 LTE 独立联网能力，手机不在旁时
 * 可直连 NapCat 的 HTTP 服务器收发（参考 CoraTech-Wear/merqury-vela 的表端直连
 * 实践：@system.fetch + Bearer token + OneBot HTTP API）。
 *
 * 通道编排（app.ux sendUpstream）：
 *   互联已连接 → 互联发送（失败回退直连）；未连接且有直连配置 → 直连；都无 → 原互联错误路径。
 * 直连配置来源：手机端连接时经 direct_config 帧下发 NapCat HTTP 地址+token（手环持久化），
 * 免手输。接收侧为前台轮询（chat 4s / index 20s，onHide 停止），无后台 push 通道。
 *
 * OneBot 11 HTTP API（与手机端 OneBotClient 同一协议面）：
 *   POST {url}/{action}，SnowLuma 类实现从路径解析 action；
 *   NapCat 等实现若 404 则回退 {url}/api/{action}。
 */
let fetchImpl = null
function getFetch() {
  if (!fetchImpl) {
    try {
      fetchImpl = require('@system.fetch')
    } catch (e) {
      fetchImpl = null
    }
  }
  return fetchImpl
}

const REQ_TIMEOUT_MS = 8000

/**
 * 单次 OneBot HTTP 调用，resolve(OneBot data 字段)。
 * @returns Promise<any>
 */
function callOneBot(cfg, action, params) {
  return new Promise((resolve, reject) => {
    const f = getFetch()
    if (!f) { reject({ code: 1001, msg: 'fetch unavailable' }); return }
    if (!cfg || !cfg.url) { reject({ code: 1002, msg: 'direct cfg missing' }); return }
    const base = cfg.url.replace(/\/+$/, '')
    const header = { 'Content-Type': 'application/json' }
    if (cfg.token) header['Authorization'] = 'Bearer ' + cfg.token
    const body = JSON.stringify(params || {})
    const timer = setTimeout(() => reject({ code: 1003, msg: 'direct timeout' }), REQ_TIMEOUT_MS)
    const done = (fn, arg) => {
      clearTimeout(timer)
      fn(arg)
    }
    f.fetch({
      url: base + '/' + action,
      method: 'POST',
      data: body,
      header: header,
      success: (res) => {
        try {
          const data = typeof res.data === 'string' ? JSON.parse(res.data) : res.data
          if (data && (data.retcode === 0 || data.status === 'ok')) {
            done(resolve, data.data)
          } else {
            // 路径形态不匹配（SnowLuma /api 前缀）时回退重试一次
            if (!base.endsWith('/api') && data && (data.retcode === 404 || data.status === 'failed')) {
              const timer2 = setTimeout(() => reject({ code: 1003, msg: 'direct timeout' }), REQ_TIMEOUT_MS)
              f.fetch({
                url: base + '/api/' + action,
                method: 'POST',
                data: body,
                header: header,
                success: (res2) => {
                  clearTimeout(timer2)
                  try {
                    const d2 = typeof res2.data === 'string' ? JSON.parse(res2.data) : res2.data
                    if (d2 && (d2.retcode === 0 || d2.status === 'ok')) resolve(d2.data)
                    else reject({ code: 1004, msg: 'direct retcode', data: d2 })
                  } catch (e) { reject({ code: 1005, msg: 'direct parse' }) }
                },
                fail: (err, code) => { clearTimeout(timer2); reject({ code: code, msg: 'direct api fallback fail' }) }
              })
            } else {
              done(reject, { code: 1004, msg: 'direct retcode', data: data })
            }
          }
        } catch (e) {
          done(reject, { code: 1005, msg: 'direct parse' })
        }
      },
      fail: (err, code) => done(reject, { code: code, msg: 'direct fail' })
    })
  })
}

/**
 * v2.16.0：OneBot v11 规范里 message_id/user_id/group_id 是 int32（JSON number）。
 * NapCat 内部按数值 key 索引消息（MessageUnique Map），字符串 key 查不到 → 动作
 * 静默失败。safe-integer 范围内转数字下发；超长字符串形态 id（LLOneBot 等实现）
 * Number 转换会丢精度，保留字符串原样。
 */
function numericId(v) {
  if (v === undefined || v === null || v === '') return v
  const n = Number(v)
  return (Number.isSafeInteger(n) && String(n) === String(v)) ? n : v
}

/** CQ 码轻量降级（直连模式下 raw_message → 纯文本 + at/poke 检测） */
function cqText(raw) {
  if (typeof raw !== 'string') return { text: '', at: false, poke: false }
  let at = false
  let poke = false
  const text = raw
    .replace(/\[CQ:poke[^\]]*\]/g, () => { poke = true; return '拍了拍你' })
    .replace(/\[CQ:at,qq=(\d+)[^\]]*\]/g, (m, qq) => { if (qq !== '0') at = true; return '' })
    .replace(/\[CQ:(image|flash)[^\]]*\]/g, '[图片]')
    .replace(/\[CQ:face[^\]]*\]/g, '[表情]')
    .replace(/\[CQ:(record)[^\]]*\]/g, '[语音]')
    .replace(/\[CQ:(video)[^\]]*\]/g, '[视频]')
    .replace(/\[CQ:(file)[^\]]*\]/g, '[文件]')
    .replace(/\[CQ:[^\]]*\]/g, '[其他]')
  return { text: text, at: at, poke: poke }
}

/** OneBot 私聊/群聊历史消息 → BandQQ 消息格式（selfId 用于 is_self/at 判定） */
function normalizeHistoryMessage(m, chatType, selfId) {
  if (!m || typeof m !== 'object') return null
  const sender = m.sender || {}
  const senderId = String(sender.user_id != null ? sender.user_id : (m.user_id != null ? m.user_id : ''))
  const isSelf = selfId != null && senderId === String(selfId)
  const decoded = cqText(m.raw_message != null ? m.raw_message : '')
  const item = {
    message_type: chatType,
    sender_id: senderId,
    sender_name: sender.card || sender.nickname || senderId,
    content: decoded.text,
    is_self: isSelf,
    time: (m.time || 0) * 1000
  }
  // v2.17.0 修复：直连历史消息透传 message_id（此前丢弃 → 直连模式撤回/表情回应
  // 菜单 id 恒空整体不可用）。保留 OneBot 原始形态（number），发送链 numericId 兜底
  if (m.message_id !== undefined && m.message_id !== null && m.message_id !== '') {
    item.message_id = m.message_id
  }
  if (decoded.at && !isSelf) item.at = true
  if (decoded.poke) item.poke = true
  return item
}

export function createDirect(impl) {
  const call = impl && impl.callOneBot ? impl.callOneBot : callOneBot

  /** 连接探测：get_login_info（直连可用性 + self_id 缓存依据） */
  function probe(cfg) {
    return call(cfg, 'get_login_info', {})
  }

  /**
   * 拉取指定会话最新历史（count 条，time 升序返回）。
   * 直连模式不做 before 翻页（OneBot 历史锚为 message_seq 与手环时间锚不同构），
   * 更早内容由本地 store 窗口承载（120 条数据层容量）。
   */
  function fetchHistory(cfg, targetId, limit, isGroup) {
    const idNum = Number(targetId)
    const params = isGroup
      ? { message_type: 'group', group_id: idNum, count: limit || 15 }
      : { message_type: 'private', user_id: idNum, count: limit || 15 }
    let selfId = null
    return call(cfg, 'get_login_info', {})
      .then((d) => { if (d && d.user_id != null) selfId = d.user_id; return call(cfg, 'get_msg_history', params) })
      .then((d) => {
        const arr = (d && (d.messages || d.message)) || []
        const list = []
        for (let i = 0; i < arr.length; i++) {
          const m = normalizeHistoryMessage(arr[i], isGroup ? 'group' : 'private', selfId)
          if (m && m.content) list.push(m)
        }
        list.sort((a, b) => (a.time || 0) - (b.time || 0))
        return list
      })
  }

  /** 发送消息（BandQQ send_message 协议帧 → OneBot send_private/group_msg），resolve=是否成功入队 */
  function sendMessage(cfg, messageType, targetId, content) {
    const idNum = Number(targetId)
    const params = messageType === 'group'
      ? { group_id: idNum, message: content }
      : { user_id: idNum, message: content }
    return call(cfg, messageType === 'group' ? 'send_group_msg' : 'send_private_msg', params)
      .then((d) => (d && (d.message_id != null)))
  }

  /** 联系人骨架（好友+群）：BandQQ visible_contacts 形态 */
  function fetchContacts(cfg) {
    const friends = call(cfg, 'get_friend_list', {}).then((d) => {
      const arr = Array.isArray(d) ? d : []
      return arr.map((f) => ({
        id: String(f.user_id), type: 'private',
        name: f.remark || f.nickname || String(f.user_id)
      }))
    }).catch(() => [])
    const groups = call(cfg, 'get_group_list', {}).then((d) => {
      const arr = Array.isArray(d) ? d : []
      return arr.map((g) => ({
        id: String(g.group_id), type: 'group',
        name: g.group_name || String(g.group_id)
      }))
    }).catch(() => [])
    return Promise.all([friends, groups]).then((r) => r[0].concat(r[1]))
  }

  /** 上行协议帧分发（app.ux sendUpstream 直连路径）：返回 Promise，失败 reject {code} */
  function sendPayload(cfg, payload) {
    if (!payload || typeof payload !== 'object') return Promise.reject({ code: 1010, msg: 'bad payload' })
    switch (payload.type) {
      case 'send_message':
        return sendMessage(cfg, payload.message_type, payload.target_id, payload.content)
      case 'get_visible_contacts':
        return fetchContacts(cfg)
      case 'get_history':
        // 直连模式：忽略 before 锚（本地窗口翻页承载），只拉最新一页
        return fetchHistory(cfg, payload.target_id, payload.limit || 15, payload.message_type === 'group' || false)
      case 'get_connect_state':
        // 直连模式本地构造：无互联时 band/protocol 语义以直连兜底
        return Promise.resolve({ band: true, protocol: true, direct: true })
      case 'read_chat':
      case 'get_conversations':
      case 'get_settings':
      case 'ping':
        // 无直连对应的静默成功（读上报/会话聚合/设置以互联与本地为准）
        return Promise.resolve(null)
      // v2.16.0 OneBot v11 扩展动作直连翻译：eSIM 独立线路（互联断开）时
      // 点赞/拍一拍/群签到/撤回/表情回应/资料查询不再整体失效。
      // 结果 toast 在互联模式下由手机端 action_result 帧承担；直连模式动作
      // 生效为准（成功静默，失败 reject 交上游 catch）。
      case 'send_like':
        return call(cfg, 'send_like', { user_id: numericId(payload.target_id), times: payload.times || 10 })
      case 'send_poke':
        return payload.chat_type === 'group'
          ? call(cfg, 'group_poke', { group_id: numericId(payload.target_id), user_id: numericId(payload.target_id) })
          : call(cfg, 'friend_poke', { user_id: numericId(payload.target_id) })
      case 'group_sign':
        return call(cfg, 'send_group_sign', { group_id: numericId(payload.target_id) })
      case 'message_action':
        if (payload.sub_action === 'delete') {
          return call(cfg, 'delete_msg', { message_id: numericId(payload.message_id) })
        }
        return call(cfg, 'set_msg_emoji_like', {
          message_id: numericId(payload.message_id),
          emoji_id: String(payload.emoji_id || '128077')
        })
      case 'get_user_info':
        return payload.chat_type === 'group' && payload.group_id
          ? call(cfg, 'get_group_member_info', { group_id: numericId(payload.group_id), user_id: numericId(payload.target_id) })
          : call(cfg, 'get_stranger_info', { user_id: numericId(payload.target_id) })
      default:
        return Promise.reject({ code: 1011, msg: 'unsupported direct frame: ' + payload.type })
    }
  }

  return { callOneBot: call, probe, fetchHistory, fetchContacts, sendMessage, sendPayload }
}

const direct = createDirect()
export default direct

// 命名导出：供 Node 单测按需引入
export { callOneBot, cqText, normalizeHistoryMessage }
