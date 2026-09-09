import { test } from 'node:test'
import assert from 'node:assert'
import { createStore } from '../src/common/store.js'
import protocol from '../src/common/protocol.js'

/** 内存存储 Fake（与 store.test.js 同款约定） */
function makeFakeStorage() {
  const map = new Map()
  return {
    map,
    get({ key, default: def, success }) { success(map.has(key) ? map.get(key) : def) },
    set({ key, value, success }) { map.set(key, value); success && success() }
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

test('v1.2.0 节流持久化：写入合并，flush 立即落盘', async () => {
  const st = makeFakeStorage()
  const store = createStore(st)
  await store.setConversations([{ id: 'g1', type: 'group', name: '群', last_msg: 'a', time: Date.now() }])
  // 节流期间：多次写只保留待写标记，磁盘上可能还是旧值
  await store.upsertMessage({
    message_type: 'group', target_id: 'g1', sender_id: 'u1', sender_name: '甲',
    content: 'm1', is_self: false, time: 1788875415000, visible: true
  })
  await store.upsertMessage({
    message_type: 'group', target_id: 'g1', sender_id: 'u2', sender_name: '乙',
    content: 'm2', is_self: false, time: 1788875416000, visible: true
  })
  // 未到 debounce 期限，待写队列应有积压（证明没有每条消息立即写盘）
  assert.ok(store._pendingPersistCount() >= 1, '节流队列应有待写项')
  // flush 后立即落盘且数据完整
  store.flushPersist()
  assert.strictEqual(store._pendingPersistCount(), 0, 'flush 后队列应清空')
  const raw = JSON.parse(st.map.get('msg_cache_g1'))
  assert.strictEqual(raw.length, 2)
  assert.strictEqual(raw[0].content, 'm1')
  assert.strictEqual(raw[1].content, 'm2')
})

test('v1.2.0 节流持久化：等 debounce 到期自动落盘', async () => {
  const st = makeFakeStorage()
  const store = createStore(st)
  await store.upsertMessage({
    message_type: 'private', target_id: 'p1', sender_id: 'p1', sender_name: '朋',
    content: 'hi', is_self: false, time: 1788875417000, visible: true
  })
  assert.ok(store._pendingPersistCount() >= 1)
  await sleep(600)
  assert.strictEqual(store._pendingPersistCount(), 0, 'debounce 到期应自动落盘')
  const raw = JSON.parse(st.map.get('msg_cache_p1'))
  assert.strictEqual(raw.length, 1)
})

test('v1.2.0 缩略图 LRU：超过上限淘汰最旧', async () => {
  const st = makeFakeStorage()
  const store = createStore(st)
  for (let i = 0; i < 25; i++) {
    await store.upsertMessage({
      message_type: 'group', target_id: 'g2', sender_id: 'u' + i, sender_name: 'u' + i,
      content: 'msg' + i, is_self: false, time: 1788875418000 + i, visible: true,
      thumb: 'data:image/jpeg;base64,thumb' + i
    })
  }
  // 最早 5 张应被淘汰（上限 20），最新 20 张保留
  assert.strictEqual(store.getThumb('g2', 1788875418000), null, '最旧的应被 LRU 淘汰')
  assert.strictEqual(store.getThumb('g2', 1788875418019), 'data:image/jpeg;base64,thumb19', '最新的应保留')
})

test('v1.2.0 protocol.cleanCqCodes：清洗 CQ 码保留正文', () => {
  assert.strictEqual(protocol.cleanCqCodes('[CQ:image,file=abc.image]你好'), '你好')
  assert.strictEqual(protocol.cleanCqCodes('前面[CQ:at,qq=123] 后面'), '前面 后面')
  assert.strictEqual(protocol.cleanCqCodes('普通文本 [图片] 占位不误伤'), '普通文本 [图片] 占位不误伤')
  assert.strictEqual(protocol.cleanCqCodes(''), '')
})

test('v1.2.0 protocol.degradeContent：字符串 CQ 码消息被清洗', () => {
  const out = protocol.degradeContent('[CQ:face,id=1]哈哈[CQ:image,file=x]')
  assert.strictEqual(out, '哈哈')
})

test('v1.2.0 decodePush：字符串消息也走 CQ 清洗', () => {
  const frame = protocol.decodePush({
    type: 'push_message', message_type: 'group', target_id: '10086', sender_id: 'u9',
    sender_name: '甲', target_name: '群', content: '看[CQ:image,file=url.jpg]图',
    is_self: false, time: 1788875419000
  })
  assert.ok(frame)
  assert.strictEqual(frame.content, '看图')
})
