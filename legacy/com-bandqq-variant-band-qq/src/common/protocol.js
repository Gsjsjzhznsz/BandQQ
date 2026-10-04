/**
 * BandQQ 手环端协议层 v1.1.1
 * 帧格式：单条 JSON 文本。
 * 手机->手环：message / list / history(mode=recent|older) / state / login_info / send_failed
 * 手环->手机：hello / send / get_history / refresh
 */

import * as store from './store.js'

// ---------- 时间工具 ----------
export function needTimeSplit(prevMs, curMs) {
  if (!prevMs) return true
  return curMs - prevMs >= 5 * 60 * 1000 // Stapxs: >=5 分钟分隔
}

function pad2(n) { return n < 10 ? '0' + n : '' + n }

export function formatTime(ms) {
  if (!ms) return ''
  const d = new Date(ms)
  const now = new Date()
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  if (ms >= todayStart) return pad2(d.getHours()) + ':' + pad2(d.getMinutes())
  if (ms >= todayStart - 86400000) return '昨天'
  return (d.getMonth() + 1) + '月' + d.getDate() + '日'
}

export function formatListTime(ms) {
  if (!ms) return ''
  return formatTime(ms)
}

// ---------- 解析（手机 -> 手环）----------
/**
 * @return {type:'message', data} | {type:'list', data} | {type:'history', data} |
 *         {type:'state'|'login_info'|'send_failed', data} | null
 */
export function decodePush(text) {
  let json
  try { json = typeof text === 'string' ? JSON.parse(text) : text } catch (e) { return null }
  const type = json.type
  switch (type) {
    case 'message':
      return {
        type: 'message',
        data: {
          messageId: json.message_id || 0,
          chatType: json.chat_type || 'group',
          targetId: json.target_id,
          senderId: json.sender_id,
          senderName: json.sender_name,
          content: json.content,
          time: json.time,
          isSelf: !!json.is_self,
          atMe: !!json.at_me,
          thumb: json.thumb
        }
      }
    case 'list':
      return { type: 'list', data: json.contacts || [] }
    case 'history': {
      const mode = json.mode === 'older' ? 'older' : 'recent'
      return {
        type: 'history',
        data: {
          mode,
          targetId: json.target_id,
          hasMore: !!json.has_more,
          messages: json.messages || []
        }
      }
    }
    case 'state':
      return { type: 'state', data: { connected: !!json.connected } }
    case 'login_info':
      return { type: 'login_info', data: { userId: json.user_id, nickname: json.nickname || json.user_id } }
    case 'send_failed':
      return { type: 'send_failed', data: { targetId: json.target_id } }
    default:
      return null
  }
}

// ---------- 编码（手环 -> 手机）----------
export function encodeHello() {
  return JSON.stringify({ type: 'hello' })
}

export function encodeSend(targetId, chatType, content) {
  return JSON.stringify({ type: 'send', target_id: String(targetId), chat_type: chatType, content })
}

export function encodeGetHistory(targetId, chatType, limit, opts) {
  const frame = { type: 'get_history', target_id: String(targetId), chat_type: chatType, limit: limit || 20 }
  if (opts && opts.older) {
    frame.older = true
    if (opts.beforeTime) frame.before_time = opts.beforeTime
  }
  return JSON.stringify(frame)
}

export function encodeRefresh() {
  return JSON.stringify({ type: 'refresh' })
}
