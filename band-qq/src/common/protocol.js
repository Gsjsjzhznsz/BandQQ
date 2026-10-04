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

/** v2.8.0 双端互通：拉取手机端设置快照（返回 settings_state 帧） */
function getSettings() {
  return { type: 'get_settings', seq: nextSeq() }
}

/**
 * v2.8.0 双端互通：手环设置页改动回传（settings_update）。
 * 只带变化的字段（undefined 不序列化），手机端 applyBandSettings 兼容空字段。
 */
function updateSettings(patch) {
  const frame = { type: 'settings_update', seq: nextSeq() }
  if (typeof patch.msg_vibrate === 'boolean') frame.msg_vibrate = patch.msg_vibrate
  if (typeof patch.emoji_native === 'boolean') frame.emoji_native = patch.emoji_native
  // v2.9.0：免打扰会话集合全量上报（逗号分隔 ID，手环长按菜单开关后上报）
  if (typeof patch.mute_list === 'string') frame.mute_list = patch.mute_list
  return frame
}

/** 手环打开聊天页时上报已读，手机端清零未读并回推会话列表 */
function readChat(targetId) {
  return { type: 'read_chat', seq: nextSeq(), target_id: targetId }
}

// ============ v2.13.0 OneBot v11 扩展动作（点赞/拍一拍/群签到/消息动作/资料） ============
// 手机端 MessageBroker 路由到 send_like / friend_poke / group_poke / send_group_sign /
// delete_msg / set_msg_emoji_like / get_stranger_info / get_group_member_info，
// 结果以 action_result 帧（toast）与 user_info 帧回推手环。

/** 点赞（私聊对象；times 上限依协议端，默认 10） */
function sendLike(targetId, times) {
  return { type: 'send_like', seq: nextSeq(), target_id: targetId, times: times || 10 }
}

/** 主动拍一拍（chat_type: 'private' | 'group'） */
function sendPoke(targetId, chatType) {
  return { type: 'send_poke', seq: nextSeq(), target_id: targetId, chat_type: chatType || 'private' }
}

/** 群签到（send_group_sign，部分协议端支持） */
function groupSign(targetId) {
  return { type: 'group_sign', seq: nextSeq(), target_id: targetId }
}

/**
 * 消息级动作：sub_action 'emoji'（表情回应）| 'delete'（撤回自己消息）。
 * emojiId 为 QQ 表情回应 unicode 码点字符串（如 128077 = 👍）。
 */
function messageAction(subAction, messageId, emojiId) {
  const f = { type: 'message_action', seq: nextSeq(), sub_action: subAction, message_id: messageId }
  if (subAction === 'emoji') f.emoji_id = emojiId
  return f
}

/** 资料查询：私聊 get_stranger_info；群聊带 group_id 走 get_group_member_info */
function getUserInfo(targetId, chatType, groupId) {
  const f = { type: 'get_user_info', seq: nextSeq(), target_id: targetId, chat_type: chatType || 'private' }
  if (groupId) f.group_id = groupId
  return f
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

/**
 * v2.14.0 智能自动渲染器（手环端兜底版，与手机端 OneBotParser 同步降级规则）：
 * 正常链路手机端已渲染完成，此函数仅在旧版手机端下发未渲染数组时兜底。
 * 覆盖 20+ 段型：json/xml 卡片摘要、markdown 降纯文本、合并转发/表情包/GIF/
 * 文件名/位置/分享等，与手机端同款 800 字符长度护栏。
 */
const RENDER_MAX_LEN = 800

/** OneBot v11 CQ 码反转义 */
function cqUnescape(s) {
  return String(s || '')
    .replace(/&#91;/g, '[')
    .replace(/&#93;/g, ']')
    .replace(/&#44;/g, ',')
    .replace(/&amp;/g, '&')
}

function capLen(s) {
  return s.length > RENDER_MAX_LEN ? s.slice(0, RENDER_MAX_LEN) + '…' : s
}

/** JSON 卡片摘要：meta.prompt → meta.{news,music,app}.title(+describe) → title/desc → 兜底 */
function jsonCardSummary(jsonStr) {
  try {
    // v2.17.0：QQ 红包识别（wcpay 字段特征），优先于通用卡片摘要
    if (jsonStr.indexOf('wcpay') >= 0) return '[QQ红包]'
    const obj = JSON.parse(jsonStr)
    if (obj && typeof obj === 'object') {
      const meta = obj.meta || {}
      if (meta.prompt && String(meta.prompt).trim()) return String(meta.prompt).trim()
      for (const key of ['news', 'music', 'app', 'software', 'structured']) {
        const m = meta[key]
        if (m && m.title && String(m.title).trim()) {
          const d = m.describe || m.singer || m.from
          return '[卡片] ' + String(m.title).trim() + (d && d !== m.title ? ' · ' + d : '')
        }
      }
      const t = obj.title && String(obj.title).trim()
      const d = (obj.desc || obj.description || '').trim()
      if (t) return '[卡片] ' + t + (d && d !== t ? ' · ' + d : '')
      if (d) return '[卡片] ' + d
      const cfg = obj.config || {}
      const cd = (cfg.desc || cfg.from || '').trim()
      if (cd) return '[卡片] ' + cd
    }
  } catch (e) { /* 非法 JSON 走兜底 */ }
  return '[卡片消息]'
}

/** XML 卡片摘要：brief 属性 / <title> 文本 */
function xmlCardSummary(xml) {
  try {
    // v2.17.0：QQ 红包识别（wcpayinfo 特征，老版 xml 红包卡）
    if (xml.indexOf('wcpayinfo') >= 0) return '[QQ红包]'
    const brief = /brief="([^"]+)"/.exec(xml)
    const title = /<title[^>]*>([\s\S]*?)<\/title>/i.exec(xml)
    const best = [title && title[1] && title[1].trim(), brief && brief[1]]
      .find((x) => x && String(x).trim())
    if (best) return '[卡片] ' + String(best).slice(0, 60)
  } catch (e) { /* 兜底 */ }
  return '[卡片消息]'
}

/** markdown 降纯文本（AstrBot 回复常用） */
function markdownToPlain(md) {
  let s = String(md || '')
  s = s.replace(/```/g, '\n')
  s = s.replace(/^#{1,6}\s*/gm, '')
  s = s.replace(/!\[([^\]]*)\]\([^)]*\)/g, '[图片]')
  s = s.replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
  s = s.replace(/\*\*([^*]+)\*\*/g, '$1')
  s = s.replace(/__([^_]+)__/g, '$1')
  s = s.replace(/\*([^*\n]+)\*/g, '$1')
  s = s.replace(/`([^`\n]+)`/g, '$1')
  // v2.17.0：表格压平（与手机端同规则：单元格「；」拼接，分隔行丢弃）
  s = s.replace(/^[ \t]*\|(.+)\|[ \t]*$/gm, (m, inner) =>
    inner.split('|')
      .map((c) => c.trim())
      .filter((c) => c && !/^:?-+:?$/.test(c))
      .join('；')
  )
  s = s.replace(/^[ \t]*[-*+]\s+/gm, '· ')
  s = s.replace(/\n{3,}/g, '\n\n')
  return s.trim()
}

function fileLabel(name, fallback) {
  const n = name && String(name).trim()
  if (!n) return fallback
  return fallback + ' ' + (n.length > 24 ? n.slice(0, 24) + '…' : n)
}

/** v2.17.0：文件段大小人类可读后缀（与手机端同规则，缺省/非法返回空串） */
function fileSizeSuffix(sizeStr) {
  const n = Number(sizeStr)
  if (!Number.isFinite(n) || n <= 0) return ''
  if (n >= 1073741824) return ' · ' + (n / 1073741824).toFixed(1) + 'GB'
  if (n >= 1048576) return ' · ' + (n / 1048576).toFixed(1) + 'MB'
  if (n >= 1024) return ' · ' + Math.round(n / 1024) + 'KB'
  return ' · ' + n + 'B'
}

/** 转发节点 content 提取文本（数组段 text 拼接 / 嵌套 content 对象递归 / CQ 字符串剥离） */
function extractForwardText(content) {
  if (Array.isArray(content)) {
    let out = ''
    for (const seg of content) {
      if (seg && seg.type === 'text' && seg.data && seg.data.text) out += seg.data.text
    }
    return out.trim()
  }
  // v2.17.1：嵌套形态 {content:[...]}（NapCat 转发节点包裹层）递归提取
  if (content && typeof content === 'object' && Array.isArray(content.content)) {
    return extractForwardText(content.content)
  }
  if (typeof content === 'string') {
    return content.replace(/\[CQ:[^\]]*\]/g, '').trim()
  }
  return ''
}

/**
 * v2.17.0：合并转发智能摘要（与手机端 forwardSummary 同规则）：
 * 取前两条节点文本拼「[转发] xxx／yyy」，解析失败回退「[合并转发]」。
 */
function forwardSummary(contentJson) {
  try {
    const arr = JSON.parse(contentJson)
    if (!Array.isArray(arr)) return '[合并转发]'
    const parts = []
    for (const node of arr) {
      if (parts.length >= 2) break
      if (!node || typeof node !== 'object') continue
      const text = extractForwardText(node.content)
      if (text) parts.push(text.length > 40 ? text.slice(0, 40) + '…' : text)
    }
    return parts.length ? '[转发] ' + parts.join('／') : '[合并转发]'
  } catch (e) {
    return '[合并转发]'
  }
}

function degradeContent(raw) {
  if (typeof raw === 'string') return capLen(raw)
  if (!Array.isArray(raw)) return ''
  return capLen(raw.map((seg) => {
    const data = (seg && seg.data) || {}
    switch (seg && seg.type) {
      case 'text': return data.text || ''
      case 'face': {
        const id = Number(data.id)
        return (Number.isFinite(id) && FACE_EMOJI[id]) || '[表情]'
      }
      case 'image': {
        const urlish = (data.url || '') + (data.file || '')
        return urlish.indexOf('.gif') >= 0 ? '[GIF]' : '[图片]'
      }
      case 'record':
      case 'voice':
      case 'tts':
        return seg.type === 'tts' ? (data.text || '[语音]') : '[语音]'
      case 'video': return '[视频]'
      case 'file': return fileLabel(data.name || data.file, '[文件]') + fileSizeSuffix(data.size || data.file_size)
      case 'reply': return '[回复]'
      case 'json': return jsonCardSummary(String(data.data || ''))
      case 'xml': return xmlCardSummary(String(data.data || ''))
      case 'markdown': return markdownToPlain(data.content || '')
      case 'forward': return forwardSummary(String(data.content || ''))
      case 'mface': return '[表情包]'
      case 'poke': return '[戳一戳]'
      case 'dice': return '[骰子]'
      case 'rps': return '[猜拳]'
      case 'share': {
        const t = [data.title, data.content].filter(Boolean).join(' · ')
        return t ? '[链接] ' + t : '[链接]'
      }
      case 'location': return fileLabel(data.title, '[位置]')
      case 'contact': return '[联系人]'
      case 'gift': return '[礼物]'
      case 'at': {
        const qq = String(data.qq != null ? data.qq : '')
        const name = data.name || ''
        if (qq === 'all') return '@全体成员'
        if (name) return '@' + name
        if (qq) return '@' + qq
        return '@某人'
      }
      default: return '[其他]'
    }
  }).join(''))
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
    // poke=拍一拍消息（v2.9.0：聊天页居中特效气泡 + 长震）
    at: raw.at === 1 || raw.at === true,
    recall: raw.recall === 1 || raw.recall === true,
    poke: raw.poke === 1 || raw.poke === true
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
    // v2.9.4：签名纳入 type（群/私聊）—— 列表新增「群」徽章后类型参与渲染，
    // 类型变化必须触发重渲染
    sig += (c.id || '') + '|' + (c.name || '') + '|' + (c.prev || c.last_msg || '') + '|' +
      (c.unread || 0) + '|' + (c.tstr || '') + '|' + (c.type === 'group' ? 'G' : 'P') +
      (c.is_temporary ? 'T' : 'f') +
      (c.cat ? 'A' : '') + (c.muted ? 'M' : '')
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
  getSettings,
  updateSettings,
  sendLike,
  sendPoke,
  groupSign,
  messageAction,
  getUserInfo,
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
  getSettings,
  updateSettings,
  sendLike,
  sendPoke,
  groupSign,
  messageAction,
  getUserInfo,
  stripEmoji,
  markEmoji,
  degradeContent,
  decodePush,
  convSignature
}
