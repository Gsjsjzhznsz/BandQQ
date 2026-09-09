let seq = 0

export function nextSeq() {
  seq += 1
  return seq
}

/**
 * 构造上行发送帧。messageType 必须由调用方传入真实会话类型（private/group），
 * 不允许默认按 private 发送后再补发纠正——群号走 send_private_msg 必然失败。
 */
export function sendMessage(messageType, targetId, content) {
  return {
    type: 'send_message',
    seq: nextSeq(),
    message_type: messageType === 'group' ? 'group' : 'private',
    target_id: targetId,
    content: content,
    time: Date.now()
  }
}

export function getConversations() {
  return { type: 'get_conversations', seq: nextSeq() }
}

export function getVisibleContacts() {
  return { type: 'get_visible_contacts', seq: nextSeq() }
}

export function getConnectState() {
  return { type: 'get_connect_state', seq: nextSeq() }
}

export function getHistory(targetId, limit, opts) {
  const frame = { type: 'get_history', seq: nextSeq(), target_id: targetId, limit: limit || 20 }
  // 翻页：older=true 表示拉取比 before_time 更早的一页（手机端转发 OneBot 分页历史）
  if (opts && opts.older && opts.beforeTime) {
    frame.older = true
    frame.before_time = opts.beforeTime
  }
  return frame
}

export function clearAllHistory() {
  return { type: 'clear_all_history', seq: nextSeq() }
}

export function isEmojiCode(c) {
  return (c >= 0x2600 && c <= 0x27bf) ||
    (c >= 0x2b00 && c <= 0x2bff) ||
    (c >= 0x2b50 && c <= 0x2b55) ||
    c === 0xfe0f || c === 0x200d || c === 0x20e3 ||
    c === 0x3030 || c === 0x303d ||
    (c >= 0xa9c2 && c <= 0xa9ff) ||
    (c >= 0xaa00 && c <= 0xaa5f)
}

export function transformEmoji(s, replace) {
  if (typeof s !== 'string') return ''
  let out = ''
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i)
    if (c >= 0xd83c && c <= 0xd83e) {
      out += replace ? '[表情]' : ''
      i++
      continue
    }
    if (isEmojiCode(c)) {
      out += replace ? '[表情]' : ''
      continue
    }
    out += s[i]
  }
  return out
}

export function stripEmoji(s) {
  return transformEmoji(s, false)
}

export function markEmoji(s) {
  return transformEmoji(s, true)
}

/**
 * v1.2.0：清洗 CQ 码（OneBot 字符串消息格式）。
 * 字符串消息里的 [CQ:image,file=xxx.image][CQ:at,qq=123] 若不清洗会原样透传到手环，
 * 聊天气泡/快捷回复区就会呈现「一堆格式代码」。
 * 只清洗明确的 [CQ: 前缀，避免误伤 [图片] 之类占位文本。
 */
const CQ_CODE_RE = /\[CQ:[^\]]{0,200}\]/g

export function cleanCqCodes(s) {
  if (typeof s !== 'string') return ''
  return s.replace(CQ_CODE_RE, '').replace(/ {2,}/g, ' ')
}

export function degradeContent(raw) {
  if (typeof raw === 'string') return markEmoji(cleanCqCodes(raw))
  if (!Array.isArray(raw)) return ''
  return cleanCqCodes(raw.map((seg) => {
    if (seg.type === 'text') return markEmoji((seg.data && seg.data.text) || '')
    if (seg.type === 'face') return '[表情]'
    if (seg.type === 'image') return '[图片]'
    if (seg.type === 'record' || seg.type === 'voice') return '[语音]'
    if (seg.type === 'video') return '[视频]'
    if (seg.type === 'file') return '[文件]'
    return '[其他]'
  }).join(''))
}

/** 聊天页时间分隔条：与上一条间隔 ≥5 分钟时展示（Stapxs 同款规则） */
export function needTimeSplit(prevTime, curTime) {
  if (!curTime) return false
  if (!prevTime) return true
  return curTime - prevTime >= 5 * 60 * 1000
}

/** 时间戳 → 展示文本：今天 HH:MM / 昨天 HH:MM / M月D日 HH:MM */
export function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  const pad = (n) => (n < 10 ? '0' + n : '' + n)
  const hm = pad(d.getHours()) + ':' + pad(d.getMinutes())
  const now = new Date()
  const dayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  if (ts >= dayStart) return hm
  if (ts >= dayStart - 86400000) return '昨天 ' + hm
  return (d.getMonth() + 1) + '月' + d.getDate() + '日 ' + hm
}

/** 列表时间（简短）：今天 HH:MM / 昨天 / M月D日 */
export function formatListTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  const pad = (n) => (n < 10 ? '0' + n : '' + n)
  const now = new Date()
  const dayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  if (ts >= dayStart) return pad(d.getHours()) + ':' + pad(d.getMinutes())
  if (ts >= dayStart - 86400000) return '昨天'
  return (d.getMonth() + 1) + '/' + d.getDate()
}

export function decodePush(raw) {
  if (!raw || raw.type !== 'push_message') return null
  if (typeof raw.target_id !== 'string' || typeof raw.sender_id !== 'string') return null
  return {
    type: 'push_message',
    seq: raw.seq || 0,
    message_type: raw.message_type === 'group' ? 'group' : 'private',
    target_id: raw.target_id,
    sender_id: raw.sender_id,
    sender_name: stripEmoji(raw.sender_name || ''),
    target_name: stripEmoji(raw.target_name || ''),
    content: typeof raw.content === 'string' ? markEmoji(cleanCqCodes(raw.content)) : degradeContent(raw.content),
    is_self: raw.is_self === true,
    time: raw.time || Date.now(),
    visible: raw.visible !== false,
    // 扩展字段：@我标记 与 图片缩略图（data:image/jpeg;base64,...，仅内存态）
    at_me: raw.at_me === true,
    thumb: typeof raw.thumb === 'string' ? raw.thumb : ''
  }
}

export default {
  nextSeq,
  sendMessage,
  getConversations,
  getVisibleContacts,
  getConnectState,
  getHistory,
  clearAllHistory,
  stripEmoji,
  markEmoji,
  cleanCqCodes,
  degradeContent,
  decodePush,
  needTimeSplit,
  formatTime,
  formatListTime
}