import protocol from './protocol.js'
const { degradeContent } = protocol

const MAX_CONVERSATIONS = 50
const MAX_MESSAGES = 100
const CACHE_CONVERSATIONS = 10
const CACHE_MESSAGES = 30
const CONV_KEY = 'conv_cache'
const MSG_PREFIX = 'msg_cache_'
const VISIBLE_KEY = 'visible_contacts'
const QR_KEY = 'quick_replies'
const SETTINGS_KEY = 'band_settings'

/**
 * v2.8.0 双端互通设置（手机端为权威源，本地缓存加速启动）：
 * - msg_vibrate：新消息手环震动
 * - emoji_native：表情原生渲染（手环端 degradeContent 兑底时用）
 */
const DEFAULT_SETTINGS = { msg_vibrate: true, emoji_native: true, mute_list: '' }

/** 内置默认快捷回复（手机端 v2 协议会下发覆盖） */
const DEFAULT_QUICK_REPLIES = [
  { label: '收到', content: '收到' },
  { label: '好的', content: '好的' },
  { label: '稍后回', content: '稍后回复' },
  { label: '在忙哦', content: '在忙哦' },
  { label: '谢谢', content: '谢谢' },
  { label: 'OK', content: 'OK' }
]

/** hue(0-359) -> #rrggbb（手机端只发 hue，色彩换算一次性完成在这里） */
function hueToColor(h, s, l) {
  h = ((h % 360) + 360) % 360
  s = Math.min(Math.max(s, 0), 100) / 100
  l = Math.min(Math.max(l, 0), 100) / 100
  const c = (1 - Math.abs(2 * l - 1)) * s
  const x = c * (1 - Math.abs(((h / 60) % 2) - 1))
  const m = l - c / 2
  let r = 0, g = 0, b = 0
  if (h < 60) { r = c; g = x } else if (h < 120) { r = x; g = c } else if (h < 180) { g = c; b = x } else if (h < 240) { g = x; b = c } else if (h < 300) { r = x; b = c } else { r = c; b = x }
  const to = (v) => ('0' + Math.round((v + m) * 255).toString(16)).slice(-2)
  return '#' + to(r) + to(g) + to(b)
}

/** 为会话补齐渲染字段（手机端已下发的直接用，缺失时本地兜底一次） */
function decorate(c) {
  if (!c || typeof c !== 'object') return c
  if (typeof c.n9 !== 'string' || c.n9 === '') {
    const n = (c.name || '').trim()
    c.n9 = n.length <= 10 ? n : n.slice(0, 9) + '…'
  }
  if (typeof c.achar !== 'string' || c.achar === '') {
    c.achar = ((c.name || c.id || '?').trim().charAt(0)) || '?'
  }
  const hue = typeof c.hue === 'number' ? c.hue : 210
  if (typeof c.hueBg !== 'string') c.hueBg = hueToColor(hue, 52, 40)
  if (typeof c.prev !== 'string') {
    const lm = (c.last_msg || '').replace(/\n/g, ' ').trim()
    c.prev = lm.length <= 18 ? lm : lm.slice(0, 17) + '…'
  }
  if (typeof c.unread !== 'number' || c.unread < 0) c.unread = c.unread === 0 ? 0 : (c.unread || 0)
  return c
}

/** v2.9.0：免打扰会话 ID 集合（逗号分隔字符串存储，手环长按会话开关，双端同步） */
function mutedIds(st) {
  return typeof st.mute_list === 'string' && st.mute_list
    ? st.mute_list.split(',').map((s) => s.trim()).filter((s) => s !== '')
    : []
}

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

export function createStore(storageImpl) {
  const cache = createStorageAdapter(storageImpl)
  let conversations = []
  let visibleContacts = []
  let connectState = null
  let quickReplies = DEFAULT_QUICK_REPLIES.slice()
  let settings = Object.assign({}, DEFAULT_SETTINGS)
  const messagesByTarget = {}
  let initPromise = null

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
          conversations.forEach(decorate)
        } catch (e) {
          conversations = []
        }
        const visibleRaw = await cache.get(VISIBLE_KEY, '[]')
        try {
          visibleContacts = JSON.parse(visibleRaw).filter((c) => c && c.id !== '' && c.id !== null && c.id !== undefined && c.id !== 'undefined')
        } catch (e) {
          visibleContacts = []
        }
        const qrRaw = await cache.get(QR_KEY, '[]')
        try {
          const list = JSON.parse(qrRaw)
          if (Array.isArray(list) && list.length > 0) quickReplies = list
        } catch (e) { /* 用默认 */ }
        const stRaw = await cache.get(SETTINGS_KEY, '{}')
        try {
          const parsed = JSON.parse(stRaw)
          if (parsed && typeof parsed === 'object') settings = Object.assign({}, DEFAULT_SETTINGS, parsed)
        } catch (e) { /* 用默认 */ }
      })()
      return initPromise
    },
    // 确保任何读取前缓存行初始化完成，避免冷启动时拿到空列表并被清空
    async ensureInit() {
      await this.init()
    },
    /** 手机端下发的会话列表（已含预计算字段与未读数）：合并可见联系人骨架后整体替换 */
    async setConversations(list) {
      await this.ensureInit()
      let incoming = Array.isArray(list) ? list.map((c) => Object.assign({}, c)) : []
      incoming = incoming.filter((c) => c.id !== '' && c.id !== null && c.id !== undefined && c.id !== 'undefined')
      incoming.forEach(decorate)
      let merged = incoming.slice()
      const byId = new Map(merged.map((c) => [c.id, c]))
      for (const vc of visibleContacts) {
        if (vc.id && vc.id !== '' && !byId.has(vc.id)) {
          merged.push(decorate({ id: vc.id, type: vc.type, name: vc.name, last_msg: '', time: 0, unread: 0, is_temporary: false }))
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
              .map((vc) => decorate({ id: vc.id, type: vc.type, name: vc.name, last_msg: '', time: 0, unread: 0, is_temporary: false }))
          )
        : conversations.slice()
      // 按最新消息时间降序排序，最新会话置顶
      result.sort((a, b) => (b.time || 0) - (a.time || 0))
      // v2.9.0：免打扰会话打标（列表红点变灰）；缓存层存原始数据，打标只影响渲染出口
      const muted = new Set(mutedIds(settings))
      result.forEach((c) => { c.muted = muted.has(c.id) ? 1 : 0 })
      return result
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
    /** 追加更早的历史消息（翻页），按 time+content 去重合并 */
    async prependMessages(targetId, list) {
      await this.ensureInit()
      if (!Array.isArray(list) || list.length === 0) return 0
      const existing = messagesByTarget[targetId] || []
      const seen = {}
      for (const m of existing) {
        seen[(m && m.time) + '|' + (m && m.content !== undefined ? m.content : '')] = true
      }
      let added = 0
      for (const m of list) {
        const k = (m && m.time) + '|' + (m && m.content !== undefined ? m.content : '')
        if (!seen[k]) {
          seen[k] = true
          existing.push(m)
          added++
        }
      }
      existing.sort((a, b) => (a.time || 0) - (b.time || 0))
      const sliced = existing.slice(-MAX_MESSAGES)
      messagesByTarget[targetId] = sliced
      if (added > 0) await cache.set(MSG_PREFIX + targetId, JSON.stringify(sliced.slice(-CACHE_MESSAGES)))
      return added
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
    /**
     * 手机端 push_message 落库。
     * v2 协议下 content/sender_name/target_name 已在手机端降级为纯文本，
     * 这里仅对非字符串（旧端兼容）走 degradeContent，热路径零字符扫描。
     */
    async upsertMessage(msg) {
      await this.ensureInit()
      const content = typeof msg.content === 'string' ? msg.content : degradeContent(msg.content)
      const senderName = typeof msg.sender_name === 'string' ? msg.sender_name : ''
      const targetName = typeof msg.target_name === 'string' ? msg.target_name : ''
      const key = msg.target_id
      if (!key || key === '' || key === 'undefined') return

      // 撤回同步帧：按 time 原位替换内容，不新增消息、不动未读数（手机端 v2.4.5）
      // v2.4.5 撤回同步帧：APP 端 Gson addProperty("recall", 1) 发的是数字 1，
      // 严格 === true 永不命中 → 撤回帧被当普通新消息追加（震动+入列）。数字/布尔双兼容
      if (msg.recall === true || msg.recall === 1) {
        const messages = messagesByTarget[key] || []
        const hit = messages.find((m) => (m.time || 0) === (msg.time || -1))
        if (hit && hit.content !== content) {
          hit.content = content
          hit.rc = 1 // 与历史帧的撤回标志一致，聊天页灰显
          await cache.set(MSG_PREFIX + key, JSON.stringify(messages.slice(-CACHE_MESSAGES)))
        }
        const idx = conversations.findIndex((c) => c.id === key)
        if (idx >= 0) {
          const conv = conversations[idx]
          if (conv.last_msg !== content) {
            conv.last_msg = content
            const lm = content.replace(/\n/g, ' ').trim()
            conv.prev = lm.length <= 18 ? lm : lm.slice(0, 17) + '…'
            await cache.set(CONV_KEY, JSON.stringify(conversations.slice(0, CACHE_CONVERSATIONS)))
          }
        }
        return
      }

      const isTemp = !(msg.visible !== false && this.isVisible(msg.target_id))
      const messages = messagesByTarget[key] || []
      // push 重复时去重（手机端可能因监听器叠加重复推送同一消息）
      const dk = (msg.time || Date.now()) + '|' + content
      if (!messages.some((m) => (m.time || '') + '|' + m.content === dk)) {
        const item = {
          message_type: msg.message_type,
          sender_id: msg.sender_id,
          sender_name: senderName,
          content: content,
          is_self: msg.is_self === true,
          time: msg.time || Date.now()
        }
        // @我 标志只在为真时存储（省缓存字节），聊天页据此高亮。
        // v2.8.3 修复：APP 端发 at:1（数字），decodePush 从未被调用（死代码），
        // 严格 === true 永不命中 → 实时推送的 @我 高亮从未生效（历史路径原样存 1 反而 truthy）
        if (msg.at === true || msg.at === 1) item.at = true
        // v2.9.0 拍一拍消息：手环聊天页渲染居中特效气泡，会话预览直接显示「XX 拍了拍你」
        if (msg.poke === true || msg.poke === 1) item.poke = true
        messages.push(item)
        // 按时间升序排列，保证消息顺序不乱（秒/毫秒混用也统一比较）
        messages.sort((a, b) => (a.time || 0) - (b.time || 0))
        while (messages.length > MAX_MESSAGES) messages.shift()
        messagesByTarget[key] = messages
        await cache.set(MSG_PREFIX + key, JSON.stringify(messages.slice(-CACHE_MESSAGES)))
      }

      // 未读数：手机端 v2 直接下发权威值；旧端(-1)时本地保守自增（仅可见会话的非自发消息）
      let unread = null
      if (msg.unread !== undefined && msg.unread >= 0) {
        unread = msg.unread
      } else if (!(msg.is_self === true) && !isTemp) {
        const prevConv = conversations.find((c) => c.id === key)
        unread = Math.min(((prevConv && prevConv.unread) || 0) + 1, 99)
      }
      const idx = conversations.findIndex((c) => c.id === key)
      const prevConv = idx >= 0 ? conversations[idx] : null
      const conv = decorate({
        id: key,
        type: msg.message_type,
        name: targetName || (msg.is_self && !targetName ? key : senderName) || key,
        last_msg: content,
        time: msg.time || Date.now(),
        is_temporary: isTemp,
        unread: unread !== null ? unread : ((idx >= 0 && conversations[idx].unread) || 0),
        // @我 未读提示：本条 @我 且非自发 → 置位；其余保持原状（读后由手机端会话帧归零）
        // 数字/布尔双兼容（同 at 存储修复，v2.8.3）
        cat: ((msg.at === true || msg.at === 1) && !(msg.is_self === true))
          ? 1
          : (prevConv && prevConv.cat ? 1 : 0)
      })
      if (idx >= 0) conversations.splice(idx, 1)
      conversations.unshift(conv)
      while (conversations.length > MAX_CONVERSATIONS) conversations.pop()
      await cache.set(CONV_KEY, JSON.stringify(conversations.slice(0, CACHE_CONVERSATIONS)))
    },
    async setVisibleContacts(list) {
      await this.ensureInit()
      const incoming = Array.isArray(list) ? list.map((c) => Object.assign({}, c)) : []
      const filtered = incoming.filter((c) => c.id !== '' && c.id !== null && c.id !== undefined && c.id !== 'undefined')
      // 同步到空列表时不覆盖已存在的联系人骨架，避免未连接/时序问题导致本地联系人被清空
      if (filtered.length === 0 && visibleContacts.length > 0) return
      visibleContacts = filtered
      const visibleIds = new Set(visibleContacts.map((c) => c.id))
      // v2.6.0：只保留「可见联系人」或「仍有本地消息」的会话。
      // 旧逻辑无条件丢弃不在可见联系人里的会话并清空其消息缓存 ——
      // 手机端测试推送等临时会话的消息在下一次联系人同步时被整段清掉
      //（表现为「手环上的消息一会就删除」）。
      conversations = conversations.filter((c) => visibleIds.has(c.id) || (messagesByTarget[c.id] && messagesByTarget[c.id].length > 0))
      for (const c of visibleContacts) {
        if (!conversations.some((x) => x.id === c.id)) {
          conversations.push(decorate({ id: c.id, type: c.type, name: c.name, last_msg: '', time: 0, unread: 0, is_temporary: false }))
        }
      }
      const msgKeys = Object.keys(messagesByTarget)
      msgKeys.forEach((k) => {
        if (!visibleIds.has(k) && (!messagesByTarget[k] || messagesByTarget[k].length === 0)) {
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
    /** 手机端下发的快捷回复 [{label, content}] */
    setQuickReplies(list) {
      if (Array.isArray(list) && list.length > 0) {
        quickReplies = list
          .filter((q) => q && typeof q.content === 'string' && q.content !== '')
          .map((q) => ({
            label: (typeof q.label === 'string' && q.label !== '') ? q.label : String(q.content).slice(0, 6),
            content: q.content
          }))
          .slice(0, 6)
        cache.set(QR_KEY, JSON.stringify(quickReplies))
      }
    },
    getQuickReplies() {
      return quickReplies
    },
    setConnectState(state) {
      connectState = state || null
    },
    getConnectState() {
      return connectState
    },
    /** v2.8.0：settings_state 快照落地（手机端下发；v2.9.0 扩展免打扰集合） */
    async setSettings(st) {
      await this.ensureInit()
      if (!st || typeof st !== 'object') return
      const next = Object.assign({}, settings)
      if (typeof st.msg_vibrate === 'boolean') next.msg_vibrate = st.msg_vibrate
      if (typeof st.emoji_native === 'boolean') next.emoji_native = st.emoji_native
      if (typeof st.mute_list === 'string') next.mute_list = st.mute_list
      settings = next
      await cache.set(SETTINGS_KEY, JSON.stringify(settings))
    },
    getSettings() {
      return settings
    },
    /** v2.9.0：会话是否免打扰（红点变灰、不参与拉起） */
    isMuted(id) {
      return mutedIds(settings).indexOf(id) >= 0
    },
    /**
     * v2.9.0：切换会话免打扰（手环长按菜单入口）。
     * 本地立即生效（红点变灰/不再拉起震动），并经 settings_update 上报手机端
     * （手机端用于拦截快应用自动拉起），手机端回推 settings_state 确认双向一致。
     * @returns {number} 1=已开启免打扰 0=已关闭
     */
    async toggleMute(id) {
      await this.ensureInit()
      const list = mutedIds(settings)
      const idx = list.indexOf(id)
      if (idx >= 0) list.splice(idx, 1)
      else list.push(id)
      settings.mute_list = list.join(',')
      await cache.set(SETTINGS_KEY, JSON.stringify(settings))
      return idx >= 0 ? 0 : 1
    },
    isVisible(id) {
      return visibleContacts.some((c) => c.id === id)
    },
    /**
     * v2.9.0 演示模式：关于页版本号连点 7 次触发（无需手机 APP）。
     * v2.9.1 扩充为 5 个会话铺满 212×520 整页列表（此前仅 2 条致主页面下半空白），
     * 覆盖 @我 高亮、拍一拍特效、免打扰灰点（双会话）、无未读纯时间戳全部形态。
     * 连接手机端后会被真实数据正常覆盖。
     */
    async injectDemo() {
      await this.ensureInit()
      const now = Date.now()
      const min = 60 * 1000
      visibleContacts = [
        { id: '20001', type: 'group', name: 'BandQQ 体验群' },
        { id: '30001', type: 'group', name: '家人群' },
        { id: '40001', type: 'group', name: '项目同步群' },
        { id: '10001', type: 'private', name: '马化腾' },
        { id: '10002', type: 'private', name: '张三' }
      ]
      messagesByTarget['20001'] = [
        { message_type: 'group', sender_id: '10086', sender_name: '小明', content: '今晚八点组队开黑，来吗？', is_self: false, time: now - 48 * min },
        { message_type: 'group', sender_id: '10087', sender_name: '测试喵', content: '手环上看消息太方便了，回复也快', is_self: false, time: now - 35 * min },
        { message_type: 'group', sender_id: '10086', sender_name: '小明', content: '小明 拍了拍你', poke: true, is_self: false, time: now - 18 * min },
        { message_type: 'group', sender_id: '10087', sender_name: '测试喵', content: '刚刚的方案你觉得怎么样？这条是@我演示消息', at: true, is_self: false, time: now - 6 * min }
      ]
      messagesByTarget['30001'] = [
        { message_type: 'group', sender_id: '30002', sender_name: '妈妈', content: '给你炖了汤放冰箱里', is_self: false, time: now - 40 * min },
        { message_type: 'group', sender_id: '30003', sender_name: '老爸', content: '降温了记得加衣服', is_self: false, time: now - 25 * min },
        { message_type: 'group', sender_id: '30002', sender_name: '妈妈', content: '这周末回家吃饭吗？', is_self: false, time: now - 12 * min },
        { message_type: 'group', sender_id: '30003', sender_name: '老爸', content: '老爸 拍了拍你', poke: true, is_self: false, time: now - 5 * min }
      ]
      messagesByTarget['40001'] = [
        { message_type: 'group', sender_id: '40002', sender_name: '李工', content: '新固件已推测试通道，大家帮忙验证', is_self: false, time: now - 60 * min },
        { message_type: 'group', sender_id: '40003', sender_name: '王姐', content: '收到，下午给结果', is_self: false, time: now - 45 * min },
        { message_type: 'group', sender_id: '40002', sender_name: '李工', content: '同步一份会议纪要到群里', is_self: false, time: now - 26 * min }
      ]
      messagesByTarget['10001'] = [
        { message_type: 'private', sender_id: '10001', sender_name: '马化腾', content: '在吗？帮个忙', is_self: false, time: now - 122 * min },
        { message_type: 'private', sender_id: '10001', sender_name: '马化腾', content: '手环QQ 体验群 20001 等你', is_self: false, time: now - 118 * min },
        { message_type: 'private', sender_id: '10001', sender_name: '马化腾', content: '马化腾 拍了拍你', poke: true, is_self: false, time: now - 30 * min }
      ]
      messagesByTarget['10002'] = [
        { message_type: 'private', sender_id: '10002', sender_name: '张三', content: '明天上午十点老地方见', is_self: false, time: now - 90 * min },
        { message_type: 'private', sender_id: '10002', sender_name: '张三', content: '收到，明天见', is_self: false, time: now - 58 * min }
      ]
      conversations = [
        decorate({ id: '20001', type: 'group', name: 'BandQQ 体验群', last_msg: '刚刚的方案你觉得怎么样？', time: now - 6 * min, unread: 3, cat: 1, is_temporary: false }),
        decorate({ id: '30001', type: 'group', name: '家人群', last_msg: '老爸 拍了拍你', time: now - 5 * min, unread: 2, cat: 0, is_temporary: false }),
        decorate({ id: '40001', type: 'group', name: '项目同步群', last_msg: '同步一份会议纪要到群里', time: now - 26 * min, unread: 1, cat: 0, is_temporary: false }),
        decorate({ id: '10001', type: 'private', name: '马化腾', last_msg: '马化腾 拍了拍你', time: now - 30 * min, unread: 1, cat: 0, is_temporary: false }),
        decorate({ id: '10002', type: 'private', name: '张三', last_msg: '收到，明天见', time: now - 58 * min, unread: 0, cat: 0, is_temporary: false })
      ]
      // 双会话演示免打扰（红点变灰）：项目同步群 + 马化腾
      settings.mute_list = '40001,10001'
      await cache.set(VISIBLE_KEY, JSON.stringify(visibleContacts))
      await cache.set(CONV_KEY, JSON.stringify(conversations.slice(0, CACHE_CONVERSATIONS)))
      await cache.set(MSG_PREFIX + '20001', JSON.stringify(messagesByTarget['20001'].slice(-CACHE_MESSAGES)))
      await cache.set(MSG_PREFIX + '30001', JSON.stringify(messagesByTarget['30001'].slice(-CACHE_MESSAGES)))
      await cache.set(MSG_PREFIX + '40001', JSON.stringify(messagesByTarget['40001'].slice(-CACHE_MESSAGES)))
      await cache.set(MSG_PREFIX + '10001', JSON.stringify(messagesByTarget['10001'].slice(-CACHE_MESSAGES)))
      await cache.set(MSG_PREFIX + '10002', JSON.stringify(messagesByTarget['10002'].slice(-CACHE_MESSAGES)))
      await cache.set(SETTINGS_KEY, JSON.stringify(settings))
    },
    async clearAllMessages() {
      const keys = Object.keys(messagesByTarget)
      keys.forEach((k) => { delete messagesByTarget[k] })
      conversations = []
      await cache.set(CONV_KEY, JSON.stringify([]))
      for (const k of keys) await cache.set(MSG_PREFIX + k, JSON.stringify([]))
    }
  }
}

const store = createStore()
export default store
