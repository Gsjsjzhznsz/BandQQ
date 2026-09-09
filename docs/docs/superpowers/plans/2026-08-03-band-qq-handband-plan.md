# 手环端 Vela 快应用实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现小米手环 9 上的 Vela 快应用（band-qq/），通过 interconnect 与手机同步器通信，展示 QQ 会话/消息并发送快捷回复。

**Architecture:** Vela 快应用（app.ux 入口 + 三个页面 index/chat/settings），common/ 下 api.js（interconnect 封装）、protocol.js（协议编解码）、store.js（本地持久化）。纯逻辑部分用 Node 单测（mock system 模块）。

**Tech Stack:** Vela 快应用（JS + HTML/CSS 风格 UX）、`@system.interconnect`、`@system.storage`、`@system.router`；Node 内置 test runner 做单测。

## Global Constraints

- 包名固定为 `com.example.bandqq`，与 Android 端 `applicationId` 一致（互联硬性要求）。
- 设备为小米手环 9：屏幕 192×490 胶囊屏，`config.designWidth = 192`。
- `manifest.json` 中 `features` 必须声明 `system.interconnect`、`system.storage`、`system.router`，否则无法调用。
- 所有 `target_id`/`sender_id` 用字符串类型（避免 JS 大整数精度问题）。
- 非文本消息统一降级为 `[图片]`/`[语音]`/`[视频]`/`[文件]` 文字标签。
- 会话上限 50 个、每会话消息上限 100 条（`store.js` 截断）。
- 代码注释使用中文；不使用任何第三方 JS 依赖。

---

### Task 1: 工程骨架与 manifest 配置

**Files:**
- Create: `band-qq/manifest.json`
- Create: `band-qq/app.ux`
- Create: `band-qq/common/style.css`
- Create: `band-qq/i18n/defaults.json`
- Create: `band-qq/i18n/zh-CN.json`
- Create: `band-qq/package.json`（仅用于 Node 单测，非 Vela 构建依赖）

**Interfaces:**
- Produces: `app.ux` 暴露全局对象，页面通过 `this.$app.$def` 访问 `api`、`protocol`、`store`（后续任务填充实现）。

- [ ] **Step 1: 创建 manifest.json**

```json
{
  "package": "com.example.bandqq",
  "name": "QQ助手",
  "icon": "/Common/icon.png",
  "versionName": "1.0.0",
  "versionCode": 1,
  "minAPILevel": 1,
  "deviceTypeList": ["watch"],
  "features": [
    { "name": "system.interconnect" },
    { "name": "system.storage" },
    { "name": "system.router" }
  ],
  "config": { "designWidth": 192 },
  "router": {
    "entry": "pages/index/index",
    "pages": {
      "pages/index/index": {},
      "pages/chat/chat": {},
      "pages/settings/settings": {}
    }
  }
}
```

- [ ] **Step 2: 创建 app.ux**

```ux
/**
 * 应用入口，暴露全局工具对象
 */
import api from './common/api'
import protocol from './common/protocol'
import store from './common/store'

export default {
  api,
  protocol,
  store
}
```

- [ ] **Step 3: 创建 common/style.css（全局样式）**

```css
.page {
  flex-direction: column;
  background-color: #000000;
}

.title {
  font-size: 24px;
  color: #ffffff;
}

.list {
  width: 192px;
  flex: 1;
}

.item {
  width: 192px;
  height: 56px;
  padding-left: 12px;
  padding-right: 12px;
  flex-direction: row;
  align-items: center;
  border-bottom: 1px solid #222222;
}

.item-name {
  font-size: 22px;
  color: #ffffff;
}

.item-sub {
  font-size: 16px;
  color: #888888;
}

.btn {
  height: 44px;
  border-radius: 22px;
  background-color: #07c160;
  color: #ffffff;
  font-size: 20px;
  text-align: center;
}
```

- [ ] **Step 4: 创建 i18n 文件**

`i18n/defaults.json`:
```json
{ "appName": "QQ助手", "connected": "已连接", "disconnected": "未连接", "settings": "设置" }
```

`i18n/zh-CN.json`:
```json
{ "appName": "QQ助手", "connected": "已连接", "disconnected": "未连接", "settings": "设置" }
```

- [ ] **Step 5: 创建 package.json（单测用）**

```json
{ "name": "band-qq", "type": "module", "private": true }
```

- [ ] **Step 6: 验证**

运行：`node --version`
预期：输出 Node 版本号（>=18）。

- [ ] **Step 7: 提交**

```bash
git add band-qq/manifest.json band-qq/app.ux band-qq/common/style.css band-qq/i18n/ band-qq/package.json
git commit -m "feat(band): 工程骨架与 manifest 配置"
```

---

### Task 2: protocol.js 协议编解码

**Files:**
- Create: `band-qq/common/protocol.js`
- Test: `band-qq/test/protocol.test.js`

**Interfaces:**
- Consumes: 无（纯逻辑，不依赖 system 模块）。
- Produces:
  - `nextSeq(): number` — 返回自增 seq。
  - `sendMessage(messageType, targetId, content): object` — 构造 `send_message` 帧。
  - `getConversations(): object` — 构造 `get_conversations` 帧。
  - `degradeContent(raw): string` — 非文本段降级为 `[图片]` 等标签。
  - `decodePush(raw): object` — 校验 `push_message` 并规范化字段。

- [ ] **Step 1: 写失败测试**

`band-qq/test/protocol.test.js`:
```js
import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { nextSeq, sendMessage, getConversations, getHistory, degradeContent, decodePush } from '../common/protocol.js'

describe('protocol', () => {
  it('seq 自增', () => {
    assert.equal(nextSeq(), 1)
    assert.equal(nextSeq(), 2)
  })

  it('构造 send_message 帧', () => {
    const msg = sendMessage('group', '123', '收到')
    assert.equal(msg.type, 'send_message')
    assert.equal(msg.message_type, 'group')
    assert.equal(msg.target_id, '123')
    assert.equal(msg.content, '收到')
  })

  it('构造 get_conversations 帧', () => {
    const msg = getConversations()
    assert.equal(msg.type, 'get_conversations')
  })

  it('构造 get_history 帧', () => {
    const msg = getHistory('123', 30)
    assert.equal(msg.type, 'get_history')
    assert.equal(msg.target_id, '123')
    assert.equal(msg.limit, 30)
  })

  it('降级非文本段', () => {
    assert.equal(degradeContent([{ type: 'text', data: { text: 'hi' } }, { type: 'image' }]), 'hi[图片]')
  })

  it('解析 push_message 并规范化', () => {
    const raw = { type: 'push_message', seq: 1, message_type: 'group', target_id: '9', sender_id: '8', sender_name: '张三', content: '你好', time: 1700000000 }
    const msg = decodePush(raw)
    assert.equal(msg.sender_id, '8')
    assert.equal(msg.content, '你好')
  })

  it('push_message 缺字段返回 null', () => {
    assert.equal(decodePush({ type: 'push_message' }), null)
  })
})
```

- [ ] **Step 2: 运行测试验证失败**

运行：`node --test band-qq/test/protocol.test.js`
预期：FAIL，`Cannot find module '../common/protocol.js'`。

- [ ] **Step 3: 实现 protocol.js**

```js
let seq = 0

export function nextSeq() {
  seq += 1
  return seq
}

export function sendMessage(messageType, targetId, content) {
  return {
    type: 'send_message',
    seq: nextSeq(),
    message_type: messageType,
    target_id: targetId,
    content: content
  }
}

export function getConversations() {
  return { type: 'get_conversations', seq: nextSeq() }
}

export function getHistory(targetId, limit) {
  return {
    type: 'get_history',
    seq: nextSeq(),
    target_id: targetId,
    limit: limit || 50
  }
}

export function degradeContent(raw) {
  if (typeof raw === 'string') return raw
  if (!Array.isArray(raw)) return ''
  return raw.map((seg) => {
    if (seg.type === 'text') return (seg.data && seg.data.text) || ''
    if (seg.type === 'image') return '[图片]'
    if (seg.type === 'record' || seg.type === 'voice') return '[语音]'
    if (seg.type === 'video') return '[视频]'
    if (seg.type === 'file') return '[文件]'
    return '[其他]'
  }).join('')
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
    sender_name: raw.sender_name || '',
    content: typeof raw.content === 'string' ? raw.content : degradeContent(raw.content),
    time: raw.time || Date.now()
  }
}
```

- [ ] **Step 4: 运行测试验证通过**

运行：`node --test band-qq/test/protocol.test.js`
预期：全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add band-qq/common/protocol.js band-qq/test/protocol.test.js
git commit -m "feat(band): 协议编解码 protocol.js"
```

---

### Task 3: store.js 会话与消息缓存（内存 + 少量本地兜底）

**Files:**
- Create: `band-qq/common/store.js`
- Test: `band-qq/test/store.test.js`

**Interfaces:**
- Consumes: `protocol.degradeContent`。
- Produces:
  - `init(): Promise` — 从 storage 载入少量兜底缓存。
  - `setConversations(list): Promise` — 覆盖会话列表（来自同步器推送），并落少量缓存。
  - `getConversations(): Promise<Array>` — 返回会话数组。
  - `getMessages(targetId): Promise<Array>` — 返回某会话消息（内存）。
  - `setMessages(targetId, list): Promise` — 覆盖某会话消息（来自 get_history 响应），并落少量缓存。
  - `upsertMessage(msg): Promise` — 写入消息并更新会话（内存 + 缓存）。
  - `getQuickReplies(): Promise<Array>` — 快捷回复词。
  - `addQuickReply(text): Promise` — 新增快捷回复词。
  - `removeQuickReply(index): Promise` — 删除快捷回复词。
  - `getDefaultQuickReplies(): Array` — 内置默认快捷词（首次进入聊天页时播种）。

> **数据归属**：手机同步器为主存储；手环端内存态为主，另用 `system.storage` 兜底缓存**少量**数据（会话 ≤10、每会话 ≤30 条），断连后可展示最近记录，不承担完整存储。

- [ ] **Step 1: 写失败测试（注入 mock storage）**

`band-qq/test/store.test.js`:
```js
import { describe, it, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { createStore } from '../common/store.js'

function mockStorage(initial) {
  const map = new Map(Object.entries(initial))
  return {
    get: (o) => { o.success(map.get(o.key) ?? '') },
    set: (o) => { map.set(o.key, o.value); o.success({}) }
  }
}

let store
beforeEach(async () => {
  store = createStore(mockStorage({}))
  await store.init()
})

describe('store', () => {
  it('初始化空列表', async () => {
    assert.deepEqual(await store.getConversations(), [])
    assert.deepEqual(await store.getMessages('100'), [])
  })

  it('写入消息后更新会话', async () => {
    const msg = { type: 'push_message', message_type: 'group', target_id: '100', sender_id: '1', sender_name: 'A', content: 'hi', time: 1700000000 }
    await store.upsertMessage(msg)
    const convs = await store.getConversations()
    assert.equal(convs.length, 1)
    assert.equal(convs[0].id, '100')
    assert.equal(convs[0].last_msg, 'hi')
    const msgs = await store.getMessages('100')
    assert.equal(msgs.length, 1)
    assert.equal(msgs[0].sender_name, 'A')
  })

  it('setMessages 覆盖历史', async () => {
    const list = [{ message_type: 'group', sender_id: '2', sender_name: 'B', content: '旧', time: 1700000000 }]
    await store.setMessages('200', list)
    assert.equal((await store.getMessages('200')).length, 1)
    await store.setMessages('200', [])
    assert.deepEqual(await store.getMessages('200'), [])
  })

  it('setConversations 覆盖列表', async () => {
    const convs = [{ id: '300', type: 'group', name: '群', last_msg: 'x', time: 1700000000 }]
    await store.setConversations(convs)
    assert.equal((await store.getConversations()).length, 1)
  })

  it('兜底缓存重载', async () => {
    const storage = mockStorage({})
    const s1 = createStore(storage)
    await s1.init()
    await s1.setConversations([{ id: '9', type: 'group', name: '群', last_msg: 'x', time: 1 }])
    const s2 = createStore(storage)
    await s2.init()
    assert.equal((await s2.getConversations()).length, 1)
    assert.equal((await s2.getConversations())[0].id, '9')
  })

  it('快捷回复增删', async () => {
    await store.addQuickReply('好的')
    await store.addQuickReply('收到')
    assert.deepEqual(await store.getQuickReplies(), ['好的', '收到'])
    await store.removeQuickReply(0)
    assert.deepEqual(await store.getQuickReplies(), ['收到'])
  })

  it('默认快捷词非空', () => {
    assert.ok(store.getDefaultQuickReplies().length > 0)
  })
})
```

- [ ] **Step 2: 运行测试验证失败**

运行：`node --test band-qq/test/store.test.js`
预期：FAIL，`Cannot find module '../common/store.js'`。

- [ ] **Step 3: 实现 store.js（内存为主 + storage 少量兜底）**

```js
import { degradeContent } from './protocol.js'

const MAX_CONVERSATIONS = 50
const MAX_MESSAGES = 100
const CACHE_CONVERSATIONS = 10
const CACHE_MESSAGES = 30
const CONV_KEY = 'conv_cache'
const MSG_PREFIX = 'msg_cache_'
const QUICK_KEY = 'quick_replies'

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

let systemStoragePromise = null
function resolveSystemStorage() {
  if (!systemStoragePromise) {
    systemStoragePromise = import('@system.storage').then((m) => m.default).catch(() => null)
  }
  return systemStoragePromise
}

export function createStore(storageImpl) {
  const cache = createStorageAdapter(storageImpl)
  let conversations = []
  let quickReplies = []
  const messagesByTarget = {}

  return {
    async init() {
      const convRaw = await cache.get(CONV_KEY, '[]')
      try { conversations = JSON.parse(convRaw) } catch (e) { conversations = [] }
      const quickRaw = await cache.get(QUICK_KEY, '[]')
      try { quickReplies = JSON.parse(quickRaw) } catch (e) { quickReplies = [] }
    },
    async setConversations(list) {
      conversations = Array.isArray(list) ? list : []
      const slice = conversations.slice(0, CACHE_CONVERSATIONS)
      await cache.set(CONV_KEY, JSON.stringify(slice))
    },
    async getConversations() {
      return conversations
    },
    async getMessages(targetId) {
      const msgs = messagesByTarget[targetId]
      if (msgs) return msgs
      const raw = await cache.get(MSG_PREFIX + targetId, '[]')
      try {
        messagesByTarget[targetId] = JSON.parse(raw)
      } catch (e) {
        messagesByTarget[targetId] = []
      }
      return messagesByTarget[targetId]
    },
    async setMessages(targetId, list) {
      const sliced = Array.isArray(list) ? list.slice(0, MAX_MESSAGES) : []
      messagesByTarget[targetId] = sliced
      await cache.set(MSG_PREFIX + targetId, JSON.stringify(sliced.slice(0, CACHE_MESSAGES)))
    },
    async upsertMessage(msg) {
      const content = typeof msg.content === 'string' ? msg.content : degradeContent(msg.content)
      const key = msg.target_id
      const messages = messagesByTarget[key] || []
      messages.push({
        message_type: msg.message_type,
        sender_id: msg.sender_id,
        sender_name: msg.sender_name || '',
        content: content,
        time: msg.time || Date.now()
      })
      while (messages.length > MAX_MESSAGES) messages.shift()
      messagesByTarget[key] = messages
      await cache.set(MSG_PREFIX + key, JSON.stringify(messages.slice(0, CACHE_MESSAGES)))

      const idx = conversations.findIndex((c) => c.id === key)
      const conv = {
        id: key,
        type: msg.message_type,
        name: msg.sender_name || key,
        last_msg: content,
        time: msg.time || Date.now()
      }
      if (idx >= 0) conversations.splice(idx, 1)
      conversations.unshift(conv)
      while (conversations.length > MAX_CONVERSATIONS) conversations.pop()
      await cache.set(CONV_KEY, JSON.stringify(conversations.slice(0, CACHE_CONVERSATIONS)))
    },
    getDefaultQuickReplies() {
      return ['好的', '收到', '稍等', '马上到', '嗯嗯', '哈哈哈']
    },
    async getQuickReplies() {
      return quickReplies
    },
    async addQuickReply(text) {
      if (!text) return
      quickReplies.push(text)
      await cache.set(QUICK_KEY, JSON.stringify(quickReplies))
    },
    async removeQuickReply(index) {
      if (index < 0 || index >= quickReplies.length) return
      quickReplies.splice(index, 1)
      await cache.set(QUICK_KEY, JSON.stringify(quickReplies))
    }
  }
}

const store = createStore()
export default store
```

> 说明：`createStore(storageImpl)` 注入 mock storage 供 Node 测试；未注入时（真机默认导出）通过惰性动态 `import('@system.storage')` 解析系统模块，Node 测试仅用工厂注入路径，不触发裸模块解析。

- [ ] **Step 4: 运行测试验证通过**

运行：`node --test band-qq/test/store.test.js`
预期：全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add band-qq/common/store.js band-qq/test/store.test.js
git commit -m "feat(band): 会话与消息持久化 store.js"
```

---

### Task 4: api.js interconnect 封装

**Files:**
- Create: `band-qq/common/api.js`
- Test: `band-qq/test/api.test.js`

**Interfaces:**
- Consumes: 无（注入 interconnect 实现）。
- Produces:
  - `init(handlers): void` — 注册 onmessage/onopen/onclose/onerror。
  - `send(payload): Promise` — 发送 JSON。
  - `connectStatus(): Promise<boolean>` — diagnosis 判断连接（status === 0 为 OK）。
  - `isConnected(): boolean` — 当前连接状态。
  - `createApi(interconnectImpl, storageImpl)` — 工厂（单测用）。

- [ ] **Step 1: 写失败测试**

`band-qq/test/api.test.js`:
```js
import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { createApi } from '../common/api.js'

function mockInterconnect() {
  let conn
  const instance = () => {
    if (!conn) {
      conn = {
        handlers: {},
        diagnosisArgs: null,
        sendArgs: null,
        send(opt) { this.sendArgs = opt; opt.success && opt.success({}) },
        diagnosis(opt) { this.diagnosisArgs = opt; opt.success && opt.success({ status: 0 }) }
      }
      conn.onmessage = (fn) => { conn.handlers.onmessage = fn }
      conn.onopen = (fn) => { conn.handlers.onopen = fn }
      conn.onclose = (fn) => { conn.handlers.onclose = fn }
      conn.onerror = (fn) => { conn.handlers.onerror = fn }
    }
    return conn
  }
  return { instance, getConn: () => conn }
}

describe('api', () => {
  it('send 序列化 payload 为 JSON 字符串', async () => {
    const m = mockInterconnect()
    const api = createApi(m)
    api.init({})
    await api.send({ type: 'send_message', seq: 1 })
    assert.equal(m.getConn().sendArgs.data, JSON.stringify({ type: 'send_message', seq: 1 }))
  })

  it('connectStatus 对 status=0 返回 true', async () => {
    const m = mockInterconnect()
    const api = createApi(m)
    api.init({})
    assert.equal(await api.connectStatus(), true)
  })

  it('onmessage 分发 JSON', async () => {
    const m = mockInterconnect()
    const api = createApi(m)
    let got = null
    api.init({ onMessage: (msg) => { got = msg } })
    m.getConn().handlers.onmessage({ data: JSON.stringify({ type: 'push_message' }) })
    assert.deepEqual(got, { type: 'push_message' })
  })
})
```

- [ ] **Step 2: 运行测试验证失败**

运行：`node --test band-qq/test/api.test.js`
预期：FAIL，`Cannot find module '../common/api.js'`。

- [ ] **Step 3: 实现 api.js**

```js
import interconnect from '@system.interconnect'

export function createApi(interconnectImpl) {
  const ic = interconnectImpl || interconnect
  const conn = ic.instance()
  let connected = false
  let messageHandler = null

  function init(handlers) {
    messageHandler = handlers.onMessage || null
    conn.onmessage = (res) => {
      try {
        const msg = JSON.parse(res.data)
        if (messageHandler) messageHandler(msg)
      } catch (e) {
        console.error('onmessage parse error', e)
      }
    }
    conn.onopen = (data) => {
      connected = true
      if (handlers.onOpen) handlers.onOpen(data)
    }
    conn.onclose = (data) => {
      connected = false
      if (handlers.onClose) handlers.onClose(data)
    }
    conn.onerror = (data) => {
      connected = false
      if (handlers.onError) handlers.onError(data)
    }
  }

  function send(payload) {
    return new Promise((resolve, reject) => {
      conn.send({
        data: JSON.stringify(payload),
        success: () => resolve(),
        fail: (data, code) => reject({ data, code })
      })
    })
  }

  function connectStatus() {
    return new Promise((resolve, reject) => {
      conn.diagnosis({
        success: (data) => resolve(data.status === 0),
        fail: (data, code) => reject({ data, code })
      })
    })
  }

  function isConnected() {
    return connected
  }

  return { init, send, connectStatus, isConnected }
}

const api = createApi()
export default api
```

- [ ] **Step 4: 运行测试验证通过**

运行：`node --test band-qq/test/api.test.js`
预期：全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add band-qq/common/api.js band-qq/test/api.test.js
git commit -m "feat(band): interconnect 封装 api.js"
```

---

### Task 5: 会话列表页 index/index.ux

**Files:**
- Create: `band-qq/pages/index/index.ux`

**Interfaces:**
- Consumes: `this.$app.$def.api`、`this.$app.$def.store`；`router.push` 传 `targetId`、`name`。
- Produces: 页面路由到 `/pages/chat/chat`，params: `{ targetId, name }`。

- [ ] **Step 1: 写页面**

```ux
<template>
  <div class="page">
    <text class="title" onclick="goSettings">{{statusText}} · {{appName}}</text>
    <list class="list" onscrollbottom="">
      <list-item type="conv" for="{{conversations}}" onclick="openChat({{$idx}})">
        <div class="item">
          <div class="avatar">
            <text class="avatar-text">{{$item.name.charAt(0)}}</text>
          </div>
          <div class="item-body">
            <text class="item-name">{{$item.name}}</text>
            <text class="item-sub">{{$item.last_msg}}</text>
          </div>
        </div>
      </list-item>
    </list>
    <text class="empty" if="{{!conversations.length}}">暂无会话</text>
  </div>
</template>

<style>
  .page { flex-direction: column; background-color: #000000; }
  .title { height: 36px; line-height: 36px; font-size: 20px; color: #07c160; padding-left: 12px; }
  .list { width: 192px; flex: 1; }
  .item { width: 192px; height: 56px; flex-direction: row; align-items: center; }
  .avatar { width: 36px; height: 36px; border-radius: 18px; background-color: #1f1f1f; margin-right: 10px; margin-left: 8px; justify-content: center; align-items: center; }
  .avatar-text { font-size: 18px; color: #ffffff; }
  .item-body { flex: 1; flex-direction: column; }
  .item-name { font-size: 20px; color: #ffffff; }
  .item-sub { font-size: 14px; color: #888888; max-lines: 1; }
  .empty { margin-top: 80px; text-align: center; font-size: 18px; color: #555555; }
</style>

<script>
  import router from '@system.router'

  export default {
    private: {
      appName: 'QQ助手',
      statusText: '未连接',
      conversations: []
    },
    onShow() {
      this.refreshStatus()
      this.loadConversations()
    },
    async refreshStatus() {
      const api = this.$app.$def.api
      try {
        const ok = await api.connectStatus()
        this.statusText = ok ? '已连接' : '未连接'
      } catch (e) {
        this.statusText = '未连接'
      }
    },
    async loadConversations() {
      const store = this.$app.$def.store
      const api = this.$app.$def.api
      this.conversations = await store.getConversations()
      api.send({ type: 'get_conversations', seq: Date.now() % 100000 })
    },
    openChat(idx) {
      const conv = this.conversations[idx]
      router.push({
        uri: '/pages/chat/chat',
        params: { targetId: conv.id, name: conv.name }
      })
    },
    goSettings() {
      router.push({ uri: '/pages/settings/settings' })
    }
  }
</script>
```

- [ ] **Step 2: 语法自检（node 校验 script 块语法）**

用脚本抽取 `<script>` 块后用 `node --check` 校验（AIoT-IDE 中最终编译验证）。

- [ ] **Step 3: 提交**

```bash
git add band-qq/pages/index/index.ux
git commit -m "feat(band): 会话列表页 index"
```

---

### Task 6: 聊天详情页 chat/chat.ux

**Files:**
- Create: `band-qq/pages/chat/chat.ux`

**Interfaces:**
- Consumes: `router` params `targetId`/`name`；`this.$app.$def.api`/`store`/`protocol`。
- Produces: 点击快捷回复词调用 `protocol.sendMessage` → `api.send`。

- [ ] **Step 1: 写页面**

```ux
<template>
  <div class="page">
    <text class="title">{{name}}</text>
    <scroll class="msg-area" scroll-y="true">
      <div class="msg-item {{msg.sender_id === myId ? 'self' : 'other'}}" for="{{messages}}">
        <text class="bubble {{msg.sender_id === myId ? 'bubble-self' : 'bubble-other'}}">{{msg.content}}</text>
      </div>
    </scroll>
    <div class="quick-area">
      <swiper class="swiper" indicator="none">
        <swiper-item for="{{quickGroups}}">
          <div class="quick-row" for="{{$item}}">
            <text class="quick-word" onclick="sendQuick('{{$item}}')">{{$item}}</text>
          </div>
        </swiper-item>
      </swiper>
    </div>
  </div>
</template>

<style>
  .page { flex-direction: column; background-color: #000000; }
  .title { height: 36px; line-height: 36px; font-size: 20px; color: #ffffff; padding-left: 12px; }
  .msg-area { width: 192px; flex: 1; }
  .msg-item { width: 192px; padding: 4px 8px; flex-direction: row; }
  .self { justify-content: flex-end; }
  .other { justify-content: flex-start; }
  .bubble { max-width: 150px; padding: 8px 10px; border-radius: 10px; font-size: 18px; }
  .bubble-self { background-color: #07c160; color: #ffffff; }
  .bubble-other { background-color: #1f1f1f; color: #ffffff; }
  .quick-area { height: 88px; }
  .swiper { width: 192px; height: 88px; }
  .quick-row { width: 192px; height: 44px; flex-direction: row; }
  .quick-word { width: 92px; height: 40px; margin: 2px; text-align: center; line-height: 40px; font-size: 16px; color: #07c160; background-color: #101010; border-radius: 8px; }
</style>

<script>
  import router from '@system.router'

  export default {
    protected: {
      targetId: '',
      name: '',
      messages: [],
      quickReplies: [],
      quickGroups: [],
      myId: 'me'
    },
    onInit(params) {
      this.targetId = (params && params.targetId) || ''
      this.name = (params && params.name) || '聊天'
      this.requestHistory()
      this.loadMessages()
      this.loadQuickReplies()
    },
    onShow() {
      this.loadMessages()
    },
    requestHistory() {
      const protocol = this.$app.$def.protocol
      const api = this.$app.$def.api
      api.send(protocol.getHistory(this.targetId, 50))
    },
    async loadMessages() {
      const store = this.$app.$def.store
      this.messages = await store.getMessages(this.targetId)
    },
    async loadQuickReplies() {
      const store = this.$app.$def.store
      let replies = await store.getQuickReplies()
      if (!replies.length) {
        replies = store.getDefaultQuickReplies()
        this.quickReplies = replies
      } else {
        this.quickReplies = replies
      }
      this.quickGroups = this.chunk(this.quickReplies, 2)
    },
    chunk(arr, size) {
      const out = []
      for (let i = 0; i < arr.length; i += size) out.push(arr.slice(i, i + size))
      return out
    },
    async sendQuick(content) {
      const protocol = this.$app.$def.protocol
      const api = this.$app.$def.api
      const msg = protocol.sendMessage(this.conversationType(), this.targetId, content)
      try {
        await api.send(msg)
        const store = this.$app.$def.store
        await store.upsertMessage({
          type: 'push_message',
          message_type: msg.message_type,
          target_id: msg.target_id,
          sender_id: 'me',
          sender_name: '我',
          content: content,
          time: Date.now()
        })
        this.loadMessages()
      } catch (e) {
        console.error('send failed', e)
      }
    },
    conversationType() {
      const store = this.$app.$def.store
      return store.getConversations().then((convs) => {
        const c = convs.find((x) => x.id === this.targetId)
        return c ? c.type : 'private'
      })
    }
  }
</script>
```

- [ ] **Step 2: 提交**

```bash
git add band-qq/pages/chat/chat.ux
git commit -m "feat(band): 聊天详情页 chat"
```

---

### Task 7: 设置页 settings/settings.ux

**Files:**
- Create: `band-qq/pages/settings/settings.ux`

**Interfaces:**
- Consumes: `this.$app.$def.api`/`store`。
- Produces: 快捷词管理（增删）、连接状态展示。

- [ ] **Step 1: 写页面**

```ux
<template>
  <div class="page">
    <text class="title">设置</text>
    <text class="row">互联状态：{{statusText}}</text>
    <text class="row">NapCat：{{napcatText}}</text>
    <text class="section">快捷回复词</text>
    <list class="list">
      <list-item type="quick" for="{{quickReplies}}">
        <div class="quick-item">
          <text class="quick-text">{{$item}}</text>
          <text class="quick-del" onclick="removeQuick({{$idx}})">删</text>
        </div>
      </list-item>
    </list>
    <div class="add-row">
      <input class="input" type="text" value="{{newWord}}" onchange="onInput"/>
      <text class="btn" onclick="addQuick">新增</text>
    </div>
  </div>
</template>

<style>
  .page { flex-direction: column; background-color: #000000; }
  .title { height: 36px; line-height: 36px; font-size: 20px; color: #ffffff; padding-left: 12px; }
  .row { font-size: 16px; color: #cccccc; padding: 6px 12px; }
  .section { font-size: 18px; color: #07c160; padding: 8px 12px; }
  .list { width: 192px; flex: 1; }
  .quick-item { width: 192px; height: 40px; flex-direction: row; align-items: center; padding: 0 12px; }
  .quick-text { flex: 1; font-size: 18px; color: #ffffff; }
  .quick-del { font-size: 16px; color: #ff5a5a; }
  .add-row { flex-direction: row; align-items: center; padding: 8px 12px; }
  .input { flex: 1; height: 40px; background-color: #1f1f1f; color: #ffffff; font-size: 16px; border-radius: 8px; }
  .btn { margin-left: 8px; height: 40px; line-height: 40px; padding: 0 16px; background-color: #07c160; color: #ffffff; font-size: 16px; border-radius: 8px; }
</style>

<script>
  export default {
    private: {
      statusText: '未连接',
      napcatText: '未知',
      quickReplies: [],
      newWord: ''
    },
    onShow() {
      this.refreshStatus()
      this.loadQuick()
    },
    async refreshStatus() {
      const api = this.$app.$def.api
      try {
        const ok = await api.connectStatus()
        this.statusText = ok ? '已连接' : '未连接'
      } catch (e) {
        this.statusText = '未连接'
      }
    },
    async loadQuick() {
      const store = this.$app.$def.store
      this.quickReplies = await store.getQuickReplies()
    },
    onInput(e) {
      this.newWord = e.value
    },
    async addQuick() {
      const store = this.$app.$def.store
      await store.addQuickReply(this.newWord)
      this.newWord = ''
      this.loadQuick()
    },
    async removeQuick(idx) {
      const store = this.$app.$def.store
      await store.removeQuickReply(idx)
      this.loadQuick()
    }
  }
</script>
```

- [ ] **Step 2: 提交**

```bash
git add band-qq/pages/settings/settings.ux
git commit -m "feat(band): 设置页 settings"
```

---

### Task 8: 运行全部单测并整理文档

**Files:**
- Create: `band-qq/README.md`（AIoT-IDE 导入与真机调试说明）

- [ ] **Step 1: 运行全部测试**

运行：`node --test band-qq/test/`
预期：protocol/store/api 三个测试文件全部 PASS。

- [ ] **Step 2: 编写 README.md**

简要说明：AIoT-IDE 导入 `band-qq/` 目录、真机调试步骤、与 Android 同步器配对要求（包名/签名一致）。

- [ ] **Step 3: 提交**

```bash
git add band-qq/README.md
git commit -m "docs(band): 手环端调试说明与测试"
```
