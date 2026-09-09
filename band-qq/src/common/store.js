import protocol from './protocol.js'
const { degradeContent, stripEmoji } = protocol

const MAX_CONVERSATIONS = 50
const MAX_MESSAGES = 100
// v1.1.1：持久化上限加大（此前仅 30 条/10 会话，冷启动后丢消息明显）
const CACHE_CONVERSATIONS = 20
const CACHE_MESSAGES = 60
const CONV_KEY = 'conv_cache'
const MSG_PREFIX = 'msg_cache_'
const VISIBLE_KEY = 'visible_contacts'
const SCHEMA_KEY = 'store_schema_ver'
const PROFILE_KEY = 'my_profile'
const SCHEMA_VER = 2  // v2: 清理旧版群消息误判 private 的重复记录

function createStorageAdapter(storageImpl) {
  const st = storageImpl || null
  return {
    get(key, def) {
      return new Promise((resolve) => {
        if (st) { st.get({ key: key, default: def, success: (v) => resolve(v), fail: () => resolve(def) }); return }
        resolveSystemStorage().then((sys) => {
          sys.get({ key: key, default: def, success: (v) => resolve(v), fail: () => resolve(def) })
        }).catch(() => resolve(def))
      })
    },
    set(key, value) {
      return new Promise((resolve) => {
        if (st) { st.set({ key: key, value: value, success: () => resolve(), fail: () => resolve() }); return }
        resolveSystemStorage().then((sys) => {
          sys.set({ key: key, value: value, success: () => resolve(), fail: () => resolve() })
        }).catch(() => resolve())
      })
    }
  }
}

let systemStorage = null
function resolveSystemStorage() {
  if (!systemStorage) {
    try {
      systemStorage = require('@system.storage')
    } catch (e) {
      systemStorage = null
    }
  }
  return Promise.resolve(systemStorage)
}

// v1.2.0 性能治理：@system.storage 是同步 IO，每条消息全量 JSON.stringify + 写盘
// 会在群聊高峰期阻塞渲染线程（死机重启主因）。改为节流持久化：
// 同一 key 400ms 内多次写入合并为最后一次；flushPersist() 提供退出前立即落盘。
const PERSIST_DEBOUNCE_MS = 400
// 缩略图 base64 内存上限（LRU）：base64 字符串每张 3~8KB，不设上限会持续吃内存
const MAX_THUMBS = 20

export function createStore(storageImpl) {
  const cache = createStorageAdapter(storageImpl)
  let conversations = []
  let visibleContacts = []
  let connectState = null
  const messagesByTarget = {}
  let initPromise = null
  // 未读计数：仅内存态（重启清零等同已读，符合手表轻量习惯）
  const unreadByTarget = {}
  // 缩略图内存缓存 key: targetId|time（不持久化，LRU 限容）
  const thumbsByTarget = new Map()
  // 当前正在查看的会话：查看中的新消息不计未读
  let activeChatId = null
  let myProfile = null
  // 节流写盘队列：key -> { value, timer }
  const pendingWrites = {}

  function schedulePersist(key, value) {
    const slot = pendingWrites[key] || (pendingWrites[key] = { timer: null, value: null })
    slot.value = value
    if (slot.timer) return
    slot.timer = setTimeout(() => {
      slot.timer = null
      const v = slot.value
      if (v !== null) cache.set(key, v)
      slot.value = null
    }, PERSIST_DEBOUNCE_MS)
  }

  /** 立即落盘全部待写内容（app onHide / 清空时调用，防丢数据） */
  function flushPersist() {
    for (const key of Object.keys(pendingWrites)) {
      const slot = pendingWrites[key]
      if (slot.timer) { clearTimeout(slot.timer); slot.timer = null }
      if (slot.value !== null) { cache.set(key, slot.value); slot.value = null }
    }
  }

  /**
   * 旧版 bug 数据清理（schema v2）：
   * 旧版 compose 硬编码 private 发送，群会话里残留
   * 「private + is_self + sender_id==会话ID」的错误记录（与紧随的 group 正确记录重复）。
   * 规则：group 会话中，删除 is_self 且 message_type==='private' 且 sender_id===会话ID 的消息；
   * 另对相邻 is_self 重复（同内容、|Δt|≤5s、一 private 一 group）删除 private 那条。
   */
  async function sanitizeLegacy(targetId) {
    const raw = await cache.get(MSG_PREFIX + targetId, '[]')
    let msgs
    try { msgs = JSON.parse(raw) } catch (e) { return }
    if (!Array.isArray(msgs) || msgs.length === 0) return
    const convType = (conversations.find((c) => c.id === targetId) || {}).type
    const cleaned = []
    for (let i = 0; i < msgs.length; i++) {
      const m = msgs[i]
      const next = msgs[i + 1]
      const bogusPrivate = m.is_self && m.message_type === 'private' &&
        ((convType === 'group' && String(m.sender_id) === String(targetId)) ||
         (next && next.is_self && next.message_type === 'group' &&
          next.content === m.content && Math.abs((next.time || 0) - (m.time || 0)) <= 5000))
      if (!bogusPrivate) cleaned.push(m)
    }
    if (cleaned.length !== msgs.length) {
      await cache.set(MSG_PREFIX + targetId, JSON.stringify(cleaned.slice(-CACHE_MESSAGES)))
    }
  }

  return {
    lastTarget: null,
    setLastTarget(t) {
      this.lastTarget = t || null
    },
    getLastTarget() {
      return this.lastTarget
    },
    sendStatus: { text: '', ts: 0 },
    setSendStatus(text) {
      this.sendStatus = { text, ts: Date.now() }
    },
    getSendStatus() {
      return this.sendStatus
    },
    async init() {
      if (initPromise) return initPromise
      initPromise = (async () => {
        const convRaw = await cache.get(CONV_KEY, '[]')
        try {
          conversations = JSON.parse(convRaw).filter((c) => c && c.id !== '' && c.id !== null && c.id !== undefined && c.id !== 'undefined')
        } catch (e) {
          conversations = []
        }
        const visibleRaw = await cache.get(VISIBLE_KEY, '[]')
        try {
          visibleContacts = JSON.parse(visibleRaw).filter((c) => c && c.id !== '' && c.id !== null && c.id !== undefined && c.id !== 'undefined')
        } catch (e) {
          visibleContacts = []
        }
        // schema 版本检查：旧版本数据自动清理一次（升级自动清理）
        const ver = parseInt(await cache.get(SCHEMA_KEY, '1'), 10) || 1
        if (ver < SCHEMA_VER) {
          for (const c of conversations) {
            try { await sanitizeLegacy(c.id) } catch (e) { console.error('sanitize fail', c.id, e) }
          }
          await cache.set(SCHEMA_KEY, String(SCHEMA_VER))
        }
        // 我的资料（@我提醒/账号展示用）
        try { myProfile = JSON.parse(await cache.get(PROFILE_KEY, 'null')) } catch (e) { myProfile = null }
      })()
      return initPromise
    },
    setMyProfile(p) {
      myProfile = p || null
      cache.set(PROFILE_KEY, JSON.stringify(myProfile || null))
    },
    getMyProfile() {
      return myProfile
    },
    setActiveChat(targetId) {
      activeChatId = targetId || null
      if (activeChatId) unreadByTarget[activeChatId] = 0
    },
    markRead(targetId) {
      unreadByTarget[targetId] = 0
    },
    getUnread(targetId) {
      return unreadByTarget[targetId] || 0
    },
    getThumb(targetId, time) {
      const k = targetId + '|' + time
      const v = thumbsByTarget.get(k) || null
      if (v) {
        // LRU 触碰：重新插入到 Map 尾部
        thumbsByTarget.delete(k)
        thumbsByTarget.set(k, v)
      }
      return v
    },
    /** v1.2.0：退出前立即落盘（app onHide 调用） */
    flushPersist() {
      flushPersist()
    },
    /** v1.2.0：持久化统计（测试用） */
    _pendingPersistCount() {
      return Object.keys(pendingWrites).filter((k) => pendingWrites[k].value !== null).length
    },
    clearAtMe(targetId) {
      const c = conversations.find((x) => x.id === targetId)
      if (c) c.at_me = false
    },
    // 确保任何读取前缓存行初始化完成，避免冷启动时拿到空列表并被清空
    async ensureInit() {
      await this.init()
    },
    async setConversations(list) {
      await this.ensureInit()
      let incoming = Array.isArray(list) ? list.map((c) => Object.assign({}, c, { name: stripEmoji(c.name || '') })) : []
      incoming = incoming.filter((c) => c.id !== '' && c.id !== null && c.id !== undefined && c.id !== 'undefined')
      let merged = incoming.slice()
      const byId = new Map(merged.map((c) => [c.id, c]))
      for (const vc of visibleContacts) {
        if (vc.id && vc.id !== '' && !byId.has(vc.id)) {
          merged.push({ id: vc.id, type: vc.type, name: vc.name, last_msg: '', time: 0, is_temporary: false })
        }
      }
      conversations = merged
      conversations.sort((a, b) => (b.time || 0) - (a.time || 0))
      const slice = conversations.slice(0, CACHE_CONVERSATIONS)
      await cache.set(CONV_KEY, JSON.stringify(slice))
    },
    async getConversations() {
      await this.ensureInit()
      const hasUnmatched = visibleContacts.some((vc) => vc.id && vc.id !== '' && !conversations.some((c) => c.id === vc.id))
      let result = hasUnmatched
        ? conversations.concat(
            visibleContacts
              .filter((vc) => vc.id && vc.id !== '' && !conversations.some((c) => c.id === vc.id))
              .map((vc) => ({ id: vc.id, type: vc.type, name: vc.name, last_msg: '', time: 0, is_temporary: false }))
          )
        : conversations.slice()
      // Stapxs 式排序：有消息的会话按最新时间降序（自动置顶最新），空会话垫底
      result.sort((a, b) => {
        const ta = a.time || 0
        const tb = b.time || 0
        if ((ta > 0) !== (tb > 0)) return ta > 0 ? -1 : 1
        return tb - ta
      })
      // 附带未读数与 [@我] 标记（列表角标）
      return result.map((c) => Object.assign({}, c, {
        unread: unreadByTarget[c.id] || 0,
        at_me: !!(c && c.at_me)
      }))
    },
    async getMessages(targetId) {
      await this.ensureInit()
      const msgs = messagesByTarget[targetId]
      if (msgs) {
        // 读取时也按 time 升序兜底，兼容早期缓存里未排序的数据
        if (msgs.length > 1) msgs.sort((a, b) => (a.time || 0) - (b.time || 0))
        return msgs
      }
      const raw = await cache.get(MSG_PREFIX + targetId, '[]')
      try {
        messagesByTarget[targetId] = JSON.parse(raw)
        if (messagesByTarget[targetId].length > 1) {
          messagesByTarget[targetId].sort((a, b) => (a.time || 0) - (b.time || 0))
        }
      } catch (e) {
        messagesByTarget[targetId] = []
      }
      return messagesByTarget[targetId]
    },
    async setMessages(targetId, list) {
      await this.ensureInit()
      // 合并而非覆盖：history_list 可能晚于 push_message 到达，
      // 直接用历史覆盖会丢失刚到的新消息。按 time+content 去重合并，保留全部。
      const existing = messagesByTarget[targetId] || []
      const seen = {}
      const merged = []
      for (const m of existing) {
        const k = (m && m.time) + '|' + (m && m.content !== undefined ? m.content : '')
        if (!seen[k]) { seen[k] = true; merged.push(m) }
      }
      if (Array.isArray(list)) {
        for (const m of list) {
          const k = (m && m.time) + '|' + (m && m.content !== undefined ? m.content : '')
          if (!seen[k]) { seen[k] = true; merged.push(m) }
        }
      }
      merged.sort((a, b) => (a.time || 0) - (b.time || 0))
      const sliced = merged.slice(-MAX_MESSAGES)
      messagesByTarget[targetId] = sliced
      await cache.set(MSG_PREFIX + targetId, JSON.stringify(sliced.slice(-CACHE_MESSAGES)))
    },
    async upsertMessage(msg) {
      await this.ensureInit()
      const content = typeof msg.content === 'string' ? msg.content : degradeContent(msg.content)
      const senderName = stripEmoji(msg.sender_name || '')
      const targetName = stripEmoji(msg.target_name || '')
      // 统一字符串键：防止手机端数字 id 与本地字符串 id 不一致产生双份会话/消息
      const key = msg.target_id === undefined || msg.target_id === null ? '' : String(msg.target_id)
      if (!key || key === '' || key === 'undefined') return
      const isTemp = !(msg.visible !== false && this.isVisible(msg.target_id))
      // 缩略图仅内存态：持久化时剔除；LRU 限容防内存增长（死机诱因之一）
      if (msg.thumb) {
        const tk = key + '|' + (msg.time || 0)
        thumbsByTarget.delete(tk)
        thumbsByTarget.set(tk, msg.thumb)
        while (thumbsByTarget.size > MAX_THUMBS) {
          const oldest = thumbsByTarget.keys().next().value
          thumbsByTarget.delete(oldest)
        }
      }
      const messages = messagesByTarget[key] || []
      // push 重复时去重（手机端可能因监听器叠加重复推送同一消息）
      const dk = (msg.time || Date.now()) + '|' + content
      if (!messages.some((m) => (m.time || '') + '|' + m.content === dk)) {
        messages.push({
          message_type: msg.message_type,
          sender_id: msg.sender_id,
          sender_name: senderName,
          content: content,
          is_self: msg.is_self === true,
          at_me: msg.at_me === true,
          time: msg.time || Date.now()
        })
        // 按时间升序排列，保证消息顺序不乱（秒/毫秒混用也统一比较）
        messages.sort((a, b) => (a.time || 0) - (b.time || 0))
        while (messages.length > MAX_MESSAGES) messages.shift()
        messagesByTarget[key] = messages
        // 持久化时剥离缩略图（thumb 不落盘）；节流合并写盘（v1.2.0 性能治理）
        schedulePersist(MSG_PREFIX + key, JSON.stringify(messages.slice(-CACHE_MESSAGES).map((m) => {
          const c = Object.assign({}, m); delete c.thumb; return c
        })))
        // 未读累计：非自己发的、且不在当前查看会话时 +1
        if (msg.is_self !== true && key !== activeChatId) {
          unreadByTarget[key] = (unreadByTarget[key] || 0) + 1
        }
      } else if (msg.thumb) {
        // 补图：同一消息第二次携带缩略图到达时合并进内存
        const hit = messages.find((m) => (m.time || '') + '|' + m.content === dk)
        if (hit && !hit.thumb) hit.thumb = msg.thumb
      }

      const idx = conversations.findIndex((c) => c.id === key)
      const conv = {
        id: key,
        type: msg.message_type,
        name: targetName || (msg.is_self && !targetName ? key : senderName) || key,
        last_msg: content,
        time: msg.time || Date.now(),
        is_temporary: isTemp,
        // @我 标记（列表显示 [@我] 标签，进会话后清除）
        at_me: msg.at_me === true && msg.is_self !== true
      }
      if (idx >= 0) {
        // 保留既有 at_me（多人在同一会话 @我 时不清除）
        conv.at_me = conv.at_me || conversations[idx].at_me === true
        conversations.splice(idx, 1)
      }
      conversations.unshift(conv)
      while (conversations.length > MAX_CONVERSATIONS) conversations.pop()
      schedulePersist(CONV_KEY, JSON.stringify(conversations.slice(0, CACHE_CONVERSATIONS).map((c) => {
        const cp = Object.assign({}, c); delete cp.unread; delete cp.at_me; return cp
      })))
    },
    async setVisibleContacts(list) {
      await this.ensureInit()
      let incoming = Array.isArray(list)
        ? list.map((c) => Object.assign({}, c, { name: stripEmoji(c.name || ''), id: c && c.id !== undefined && c.id !== null ? String(c.id) : '' }))
        : []
      incoming = incoming.filter((c) => c.id !== '' && c.id !== 'null' && c.id !== 'undefined')
      // 同步到空列表时不覆盖已存在的联系人骨架，避免未连接/时序问题导致本地联系人被清空
      if (incoming.length === 0 && visibleContacts.length > 0) return
      visibleContacts = incoming
      const visibleIds = new Set(visibleContacts.map((c) => String(c.id)))
      const remaining = conversations.filter((c) => !c.is_temporary)
      conversations = remaining.filter((c) => visibleIds.has(String(c.id)))
      for (const c of visibleContacts) {
        if (!conversations.some((x) => x.id === c.id)) {
          conversations.push({ id: c.id, type: c.type, name: c.name, last_msg: '', time: 0, is_temporary: false })
        }
      }
      const msgKeys = Object.keys(messagesByTarget)
      msgKeys.forEach((k) => {
        // v1.1.1 防误删：统一 String 比较（数字/字符串 id 混用曾导致全部本地消息被误清）；
        // 当前查看中的会话消息永不删除
        if (!visibleIds.has(String(k)) && String(k) !== String(activeChatId || '')) {
          delete messagesByTarget[k]
          cache.set(MSG_PREFIX + k, JSON.stringify([]))
        }
      })
      await cache.set(VISIBLE_KEY, JSON.stringify(visibleContacts))
      await cache.set(CONV_KEY, JSON.stringify(conversations.slice(0, CACHE_CONVERSATIONS)))
    },
    async getVisibleContacts() {
      await this.ensureInit()
      return visibleContacts
    },
    setConnectState(state) {
      connectState = state || null
    },
    getConnectState() {
      return connectState
    },
    isVisible(id) {
      // String 归一比较：与 setVisibleContacts/upsertMessage 的字符串键保持一致
      const s = id === undefined || id === null ? '' : String(id)
      return visibleContacts.some((c) => String(c.id) === s)
    },
    async clearAllMessages() {
      const keys = Object.keys(messagesByTarget)
      keys.forEach((k) => { delete messagesByTarget[k] })
      Object.keys(unreadByTarget).forEach((k) => { delete unreadByTarget[k] })
      thumbsByTarget.clear()
      conversations = []
      // 清空是用户显式操作：取消节流直接落盘，防止队列里旧数据回写
      flushPersist()
      await cache.set(CONV_KEY, JSON.stringify([]))
      for (const k of keys) await cache.set(MSG_PREFIX + k, JSON.stringify([]))
    }
  }
}

const store = createStore()
export default store
