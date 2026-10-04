/**
 * BandQQ 手环端数据层 v1.1.1
 * - 消息/会话持久化（storage key: chat_messages / contact_cache / schema_ver）
 * - v1.1.1: messageId 去重（冷启动回放幂等）、自消息 sender 修正、串行写队列防竞态
 * - v1.1: @我标记、未读计数、缩略图缓存、我的资料
 * - v1.0: schema v2 旧数据自动清理
 */

export const SCHEMA_VER = 2

let storageImpl = null // { get(key): Promise<string|null>, set(key, value): Promise<void> }
const memStore = new Map() // Node 测试 / storage 未就绪兜底

function defaultStorage() {
  return {
    get(key) {
      try {
        if (typeof require === 'function') {
          const storage = require('@system.storage')
          return new Promise((resolve) => {
            storage.get({ key, success: (v) => resolve(v == null ? null : v), fail: () => resolve(null) })
          })
        }
      } catch (e) { /* Node 环境 */ }
      return Promise.resolve(memStore.has(key) ? memStore.get(key) : null)
    },
    set(key, value) {
      try {
        if (typeof require === 'function') {
          const storage = require('@system.storage')
          return new Promise((resolve) => {
            storage.set({ key, value, success: () => resolve(), fail: () => resolve() })
          })
        }
      } catch (e) { /* Node 环境 */ }
      memStore.set(key, value)
      return Promise.resolve()
    }
  }
}

export function setStorageAdapter(impl) { storageImpl = impl }

function getStorage() {
  if (!storageImpl) storageImpl = defaultStorage()
  return storageImpl
}

export function normTime(t) {
  if (t == null) return 0
  return t < 100000000000 ? t * 1000 : t // OneBot 秒 -> 毫秒
}

// ============ 状态 ============
export const messages = {}        // targetId -> [ {message_id?, message_type, sender_id, sender_name, content, time(ms), is_self} ]
export const conversations = {}   // targetId -> { id, type, name, lastContent, lastTime, unread, atMe }
export const unreadByTarget = {}  // 内存：未读计数
export const thumbsByTarget = {}  // 内存：缩略图 base64
export const myProfile = { userId: '', nickname: '' }
let activeChat = null
let loaded = false
let saveTimer = null
let saveChain = Promise.resolve()

export function setMyProfile(userId, nickname) {
  if (userId) myProfile.userId = String(userId)
  if (nickname) myProfile.nickname = nickname
}

export function setActiveChat(targetId) {
  activeChat = targetId
  if (targetId && unreadByTarget[targetId]) {
    unreadByTarget[targetId] = 0
    const conv = conversations[targetId]
    if (conv) conv.unread = 0
  }
}

export function markRead(targetId) {
  setActiveChat(targetId)
}

// ============ 旧数据清理（schema v2）============
// 旧版 bug：群会话中出现 private+is_self+sender_id==会话ID 的残留条目，
// 及同一内容相邻双写（|Δt|<=5s，一条 private 一条 group）。删除 private 条目。
export async function sanitizeLegacy() {
  const ver = parseInt((await getStorage().get('schema_ver')) || '0', 10)
  if (ver >= SCHEMA_VER) return false
  let removed = 0
  for (const tid in messages) {
    const list = messages[tid]
    for (let i = list.length - 1; i >= 0; i--) {
      const m = list[i]
      const isGroup = m.message_type === 'group'
      const legacyPrivate = isGroup === false && m.is_self === true && m.sender_id === tid
      if (legacyPrivate) { list.splice(i, 1); removed++; continue }
      // 相邻双写：当前 private + 相邻 group 同内容
      if (m.message_type === 'private') {
        const prev = list[i - 1]
        const next = list[i + 1]
        const near = [prev, next].find((x) => x && x.message_type === 'group' && x.content === m.content && Math.abs(normTime(x.time) - normTime(m.time)) <= 5000)
        if (near) { list.splice(i, 1); removed++ }
      }
    }
  }
  await getStorage().set('schema_ver', String(SCHEMA_VER))
  await scheduleSave()
  return removed > 0
}

// ============ 持久化（串行写队列）============
function serializeMessages() {
  const out = {}
  for (const tid in messages) {
    out[tid] = (messages[tid] || []).slice(-200).map((m) => {
      // 持久化剥离 display-only 字段（thumb 走内存），保留 message_id 用于去重
      const { thumb, ...rest } = m
      return rest
    })
  }
  return JSON.stringify(out)
}

function serializeContacts() {
  const arr = []
  for (const tid in conversations) {
    const c = conversations[tid]
    arr.push({ id: c.id, type: c.type, name: c.name })
  }
  return JSON.stringify(arr)
}

export function scheduleSave() {
  // 300ms 防抖 + 串行链，避免快速消息竞态覆盖
  if (saveTimer) return
  saveTimer = setTimeout(async () => {
    saveTimer = null
    const snapshotMsg = serializeMessages()
    const snapshotContacts = serializeContacts()
    saveChain = saveChain
      .then(() => getStorage().set('chat_messages', snapshotMsg))
      .then(() => getStorage().set('contact_cache', snapshotContacts))
      .catch(() => {})
  }, 300)
}

export async function loadFromStorage() {
  const rawMsg = await getStorage().get('chat_messages')
  const rawContacts = await getStorage().get('contact_cache')
  try {
    if (rawMsg) {
      const root = JSON.parse(rawMsg)
      for (const tid in root) {
        messages[tid] = (root[tid] || []).map((m) => ({ ...m, time: normTime(m.time) }))
      }
    }
    if (rawContacts) {
      const arr = JSON.parse(rawContacts)
      for (const c of arr || []) {
        if (c && c.id) {
          conversations[c.id] = conversations[c.id] || {
            id: c.id, type: c.type || 'group', name: c.name || c.id,
            lastContent: '', lastTime: 0, unread: 0, atMe: false
          }
          conversations[c.id].name = c.name || conversations[c.id].name
          conversations[c.id].type = c.type || conversations[c.id].type
        }
      }
    }
  } catch (e) { /* 损坏数据忽略 */ }
  loaded = true
  await sanitizeLegacy()
  return true
}

export function isLoaded() { return loaded }

// ============ 消息入库 ============
function touchConversation(m) {
  const conv = conversations[m.targetId] || (conversations[m.targetId] = {
    id: m.targetId, type: m.chatType, name: '', lastContent: '', lastTime: 0, unread: 0, atMe: false
  })
  conv.type = m.chatType || conv.type
  // 名称：优先事件携带的 sender_name（群名片），保持稳定（回放不覆盖已有名）
  if (!conv.name && m.senderName) conv.name = m.senderName
  if (m.isSelf && !conv.name && m.senderName) conv.name = m.senderName
  return conv
}

/**
 * 消息入库（推送 / 回放 / 乐观回显共用）。
 * @param m {messageId?, chatType, targetId, senderId, senderName, content, time, isSelf, atMe?, thumb?}
 * @return 'new' | 'dup' | 'updated'
 */
export function upsertMessage(m) {
  const list = messages[m.targetId] || (messages[m.targetId] = [])
  // 1) messageId 去重：回放与实时重复投递幂等
  if (m.messageId && m.messageId > 0) {
    const dup = list.find((x) => x.message_id === m.messageId && x.message_id > 0)
    if (dup) {
      // 已看过 -> 仅补缩略图，不加未读
      if (m.thumb && !thumbsByTarget[m.targetId]) thumbsByTarget[m.targetId] = m.thumb
      return 'dup'
    }
  }
  // 1.5) self-echo 合并：自己发的内容，服务器回显（message_id>0）应并入 5s 内同内容乐观条目
  if (m.isSelf && m.messageId && m.messageId > 0) {
    const t0 = normTime(m.time)
    const cand = list.find((x) => x.is_self && x.content === (m.content || '') && Math.abs(x.time - t0) <= 5000 && (!x.message_id || x.message_id === 0))
    if (cand) {
      cand.message_id = m.messageId
      cand.time = t0
      scheduleSave()
      return 'updated'
    }
  }
  // 2) 自消息 sender 修正：is_self 时用我的资料，避免 sender_id 被填成会话ID（旧 bug）
  const msg = {
    message_id: m.messageId || 0,
    message_type: m.chatType,
    sender_id: m.isSelf ? (myProfile.userId || '') : m.senderId,
    sender_name: m.isSelf ? (myProfile.nickname || '我') : (m.senderName || m.senderId || ''),
    content: m.content || '',
    time: normTime(m.time),
    is_self: !!m.isSelf,
    at_me: !!m.atMe && !m.isSelf
  }
  list.push(msg)
  if (list.length > 200) list.splice(0, list.length - 200)

  // 3) 会话摘要 + 未读 + @我
  const conv = touchConversation({ ...m, senderName: msg.sender_name })
  const wasLatest = msg.time >= conv.lastTime
  if (wasLatest) {
    conv.lastTime = msg.time
    conv.lastContent = msg.content
    if (m.atMe && !m.isSelf) conv.atMe = true
  }
  if (!m.isSelf) {
    if (activeChat !== m.targetId) {
      unreadByTarget[m.targetId] = (unreadByTarget[m.targetId] || 0) + 1
      conv.unread = unreadByTarget[m.targetId]
    } else {
      unreadByTarget[m.targetId] = 0
      conv.unread = 0
    }
  }
  if (m.thumb) thumbsByTarget[m.targetId] = m.thumb
  scheduleSave()
  return 'new'
}

// ============ 会话列表 ============
export function getConversationList() {
  const arr = []
  for (const tid in conversations) {
    const c = conversations[tid]
    const list = messages[tid] || []
    const last = list.length ? list[list.length - 1] : null
    arr.push({
      id: c.id,
      type: c.type,
      name: c.name || c.id,
      lastContent: last ? last.content : (c.lastContent || ''),
      lastTime: last ? last.time : (c.lastTime || 0),
      lastSender: last ? last.sender_name : '',
      unread: unreadByTarget[tid] || 0,
      atMe: !!c.atMe
    })
  }
  // 稳定排序：时间降序，同秒按 id 保证不抖动
  arr.sort((a, b) => (b.lastTime - a.lastTime) || (a.id < b.id ? -1 : 1))
  return arr
}

export function getMessages(targetId) {
  return messages[targetId] || []
}

/** 翻页合并：mode=older 时将更早消息按 messageId 去重插入头部 */
export function prependOlder(targetId, incoming) {
  const list = messages[targetId] || (messages[targetId] = [])
  let added = 0
  for (const m of incoming || []) {
    const t = normTime(m.time)
    const mid = m.message_id || 0
    if (mid > 0 && list.some((x) => x.message_id === mid)) continue
    if (list.some((x) => x.time === t && x.content === m.content && x.sender_id === (m.sender_id || m.senderId))) continue
    list.push({
      message_id: mid,
      message_type: m.message_type || m.chatType,
      sender_id: m.sender_id || m.senderId || '',
      sender_name: m.sender_name || m.senderName || '',
      content: m.content || '',
      time: t,
      is_self: !!m.is_self,
      at_me: !!m.at_me
    })
    added++
  }
  list.sort((a, b) => a.time - b.time)
  scheduleSave()
  return added
}

export function resetAll() {
  for (const k in messages) delete messages[k]
  for (const k in conversations) delete conversations[k]
  for (const k in unreadByTarget) delete unreadByTarget[k]
  scheduleSave()
}
