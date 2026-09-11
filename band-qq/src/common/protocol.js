/**
 * band-qq 手环端协议 v2
 * 性能架构约定：所有重处理（emoji 降级、CQ 码剥离、名字截短、头像色相、
 * 时间格式化、未读计数）均在手机端完成后下发，手环端只做字段透传与渲染。
 * 本文件保留 markEmoji/stripEmoji/degradeContent 仅作旧版手机端兼容回退。
 */
let seq = 0

function nextSeq() {
  seq += 1
  return seq
}

function sendMessage(messageType, targetId, content) {
  return {
    type: 'send_message',
    seq: nextSeq(),
    message_type: messageType,
    target_id: targetId,
    content: content,
    time: Date.now()
  }
}

function getConversations() {
  return { type: 'get_conversations', seq: nextSeq() }
}

function getVisibleContacts() {
  return { type: 'get_visible_contacts', seq: nextSeq() }
}

function getConnectState() {
  return { type: 'get_connect_state', seq: nextSeq() }
}

function getHistory(targetId, limit) {
  return { type: 'get_history', seq: nextSeq(), target_id: targetId, limit: limit || 20 }
}

/** 翻页：拉取 time < before 的更早消息 */
function getHistoryOlder(targetId, before, limit) {
  return { type: 'get_history', seq: nextSeq(), target_id: targetId, limit: limit || 20, before: before || 0 }
}

function clearAllHistory() {
  return { type: 'clear_all_history', seq: nextSeq() }
}

/** 手环打开聊天页时上报已读，手机端清零未读并回推会话列表 */
function readChat(targetId) {
  return { type: 'read_chat', seq: nextSeq(), target_id: targetId }
}

function isEmojiCode(c) {
  return (c >= 0x2600 && c <= 0x27bf) ||
    (c >= 0x2b00 && c <= 0x2bff) ||
    (c >= 0x2b50 && c <= 0x2b55) ||
    c === 0xfe0f || c === 0x200d || c === 0x20e3 ||
    c === 0x3030 || c === 0x303d ||
    (c >= 0xa9c2 && c <= 0xa9ff) ||
    (c >= 0xaa00 && c <= 0xaa5f)
}

function transformEmoji(s, replace) {
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

function stripEmoji(s) {
  return transformEmoji(s, false)
}

function markEmoji(s) {
  return transformEmoji(s, true)
}

/** 常用 QQ face id → emoji 小映射（旧版手机端兼容回退用；新版手机端已下发 emoji） */
const FACE_EMOJI = {
  0: '😮', 1: '😞', 2: '😍', 3: '😐', 4: '😎', 5: '😢', 6: '😊', 7: '🤐',
  8: '😴', 9: '😭', 10: '😅', 11: '😠', 12: '😜', 13: '😁', 14: '🙂', 20: '🤭',
  22: '🙄', 28: '😆', 32: '❓', 39: '👋', 49: '🤗', 63: '🌹', 66: '❤', 67: '💔',
  76: '👍', 77: '👎', 85: '😘', 96: '😰', 99: '👏', 101: '😏', 104: '🥱', 178: '🤣'
}

function degradeContent(raw) {
  if (typeof raw === 'string') return raw
  if (!Array.isArray(raw)) return ''
  return raw.map((seg) => {
    if (seg.type === 'text') return (seg.data && seg.data.text) || ''
    if (seg.type === 'face') {
      const id = seg.data && Number(seg.data.id)
      return (id !== null && FACE_EMOJI[id]) || '[表情]'
    }
    if (seg.type === 'image') return '[图片]'
    if (seg.type === 'record' || seg.type === 'voice') return '[语音]'
    if (seg.type === 'video') return '[视频]'
    if (seg.type === 'file') return '[文件]'
    return '[其他]'
  }).join('')
}

/** 手机端已预算好的字段直接透传；旧版手机端缺失字段时这里做轻量兜底 */
function decodePush(raw) {
  if (!raw || raw.type !== 'push_message') return null
  if (typeof raw.target_id !== 'string' || typeof raw.sender_id !== 'string') return null
  const content = typeof raw.content === 'string' ? raw.content : degradeContent(raw.content)
  return {
    type: 'push_message',
    seq: raw.seq || 0,
    message_type: raw.message_type === 'group' ? 'group' : 'private',
    target_id: raw.target_id,
    sender_id: raw.sender_id,
    sender_name: typeof raw.sender_name === 'string' ? raw.sender_name : '',
    target_name: typeof raw.target_name === 'string' ? raw.target_name : '',
    content: content,
    is_self: raw.is_self === true,
    time: raw.time || Date.now(),
    visible: raw.visible !== false,
    // 手机端计算的该会话未读数（v2 协议），旧端缺省 undefined 由 store 兜底
    unread: typeof raw.unread === 'number' ? raw.unread : -1,
    // at=该消息 @我/全体（聊天页高亮）；recall=撤回同步帧（按 time 原位替换，不新增）
    at: raw.at === 1 || raw.at === true,
    recall: raw.recall === 1 || raw.recall === true
  }
}

/**
 * 会话列表签名：用于渲染前变化检测。
 * 内容无关字段（如对象引用）变化不再触发整表重渲染 —— 抖动根治核心。
 */
function convSignature(list) {
  if (!Array.isArray(list)) return ''
  let sig = ''
  for (let i = 0; i < list.length; i++) {
    const c = list[i]
    sig += (c.id || '') + '|' + (c.name || '') + '|' + (c.prev || c.last_msg || '') + '|' +
      (c.unread || 0) + '|' + (c.tstr || '') + '|' + (c.is_temporary ? 'T' : 'f') +
      (c.cat ? 'A' : '')
    if (i < list.length - 1) sig += ';'
  }
  return sig
}

export default {
  nextSeq,
  sendMessage,
  getConversations,
  getVisibleContacts,
  getConnectState,
  getHistory,
  getHistoryOlder,
  clearAllHistory,
  readChat,
  stripEmoji,
  markEmoji,
  degradeContent,
  decodePush,
  convSignature
}

// 命名导出：供 Node 单测按需引入（快应用侧统一走 default）
export {
  nextSeq,
  sendMessage,
  getConversations,
  getVisibleContacts,
  getConnectState,
  getHistory,
  getHistoryOlder,
  clearAllHistory,
  readChat,
  stripEmoji,
  markEmoji,
  degradeContent,
  decodePush,
  convSignature
}
