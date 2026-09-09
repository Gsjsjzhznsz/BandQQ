import { describe, it, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { createStore } from '../src/common/store.js'
import protocol from '../src/common/protocol.js'

function mockStorage(initial) {
  const map = new Map(Object.entries(initial))
  return {
    get: (o) => { o.success(map.get(o.key) ?? '') },
    set: (o) => { map.set(o.key, o.value); o.success({}) },
    dump: () => map
  }
}

describe('旧数据自动清理（schema v2）', () => {
  it('清除群会话中 private 误判的 is_self 残留记录', async () => {
    // 模拟旧版 bug 产生的数据：群 194636275 中 private+group 双写「测式」
    const legacyMsgs = [
      { message_type: 'group', sender_id: '2308534727', sender_name: '一秋 小号', content: '执行atb', time: 1788875450000, is_self: false },
      { message_type: 'private', sender_id: '194636275', sender_name: '194636275', content: '测式', time: 1788875535241, is_self: true },
      { message_type: 'group', sender_id: '194636275', sender_name: '我的世界一秋小镇', content: '测式', time: 1788875535300, is_self: true }
    ]
    const convs = [{ id: '194636275', type: 'group', name: '测试群', last_msg: '测式', time: 1788875535300 }]
    const storage = mockStorage({
      store_schema_ver: '1',
      conv_cache: JSON.stringify(convs),
      msg_cache_194636275: JSON.stringify(legacyMsgs)
    })
    const store = createStore(storage)
    await store.init()
    const msgs = await store.getMessages('194636275')
    assert.equal(msgs.length, 2)
    assert.ok(!msgs.some((m) => m.message_type === 'private'))
    assert.ok(msgs.some((m) => m.content === '执行atb'))
    assert.ok(msgs.some((m) => m.message_type === 'group' && m.content === '测式'))
    // 清理后 schema 版本已升级，不会重复清理
    assert.equal(storage.dump().get('store_schema_ver'), '2')
  })

  it('相邻 is_self 双写（一 private 一 group）也清除', async () => {
    const legacy = [
      { message_type: 'private', sender_id: 'x', sender_name: 'x', content: '重复', time: 1000, is_self: true },
      { message_type: 'group', sender_id: 'y', sender_name: 'y', content: '重复', time: 3000, is_self: true }
    ]
    const storage = mockStorage({
      store_schema_ver: '1',
      conv_cache: JSON.stringify([{ id: 'g1', type: 'group', name: 'g', last_msg: '', time: 3000 }]),
      msg_cache_g1: JSON.stringify(legacy)
    })
    const store = createStore(storage)
    await store.init()
    const msgs = await store.getMessages('g1')
    assert.equal(msgs.length, 1)
    assert.equal(msgs[0].message_type, 'group')
  })
})

describe('未读计数与 [@我] 标记', () => {
  let store
  beforeEach(async () => {
    store = createStore(mockStorage({}))
    await store.init()
  })

  it('非自己发的消息累计未读', async () => {
    await store.upsertMessage({ message_type: 'group', target_id: '100', sender_id: '1', sender_name: 'A', content: 'hi', time: 1 })
    await store.upsertMessage({ message_type: 'group', target_id: '100', sender_id: '1', sender_name: 'A', content: 'hi2', time: 2 })
    const convs = await store.getConversations()
    assert.equal(convs[0].unread, 2)
  })

  it('进会话清零未读；查看中/自己发的不累计', async () => {
    await store.upsertMessage({ message_type: 'group', target_id: '100', sender_id: '1', sender_name: 'A', content: 'hi', time: 1 })
    assert.equal(store.getUnread('100'), 1)
    // 进入会话：未读清零
    store.setActiveChat('100')
    assert.equal(store.getUnread('100'), 0)
    // 查看中来的消息、自己发的消息都不累计
    await store.upsertMessage({ message_type: 'group', target_id: '100', sender_id: '1', sender_name: 'A', content: '看的时候来', time: 2 })
    await store.upsertMessage({ message_type: 'group', target_id: '100', sender_id: 'me', sender_name: '我', content: '我发的', time: 3, is_self: true })
    assert.equal(store.getUnread('100'), 0)
    store.markRead('100')
    assert.equal(store.getUnread('100'), 0)
  })

  it('at_me 标记到会话并可清除', async () => {
    await store.upsertMessage({ message_type: 'group', target_id: '100', sender_id: '1', sender_name: 'A', content: '@我', time: 1, at_me: true })
    let convs = await store.getConversations()
    assert.equal(convs[0].at_me, true)
    store.clearAtMe('100')
    convs = await store.getConversations()
    assert.equal(convs[0].at_me, false)
  })
})

describe('协议扩展字段与时间工具', () => {
  it('decodePush 透传 at_me 与 thumb', () => {
    const frame = protocol.decodePush({
      type: 'push_message', message_type: 'group', target_id: '100', sender_id: '1',
      sender_name: 'A', target_name: '群', content: 'x', time: 5, is_self: false,
      at_me: true, thumb: 'data:image/jpeg;base64,QUJD'
    })
    assert.equal(frame.at_me, true)
    assert.equal(frame.thumb, 'data:image/jpeg;base64,QUJD')
    const plain = protocol.decodePush({
      type: 'push_message', message_type: 'group', target_id: '100', sender_id: '1',
      sender_name: 'A', target_name: '群', content: 'x', time: 5, is_self: false
    })
    assert.equal(plain.at_me, false)
    assert.equal(plain.thumb, '')
  })

  it('needTimeSplit 按 5 分钟阈值', () => {
    assert.equal(protocol.needTimeSplit(0, 1000), true)
    assert.equal(protocol.needTimeSplit(1000, 1000 + 4 * 60 * 1000), false)
    assert.equal(protocol.needTimeSplit(1000, 1000 + 5 * 60 * 1000), true)
  })

  it('formatListTime 今天/昨天/日期', () => {
    const now = new Date()
    const dayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
    const hm = protocol.formatListTime(dayStart + 3600 * 1000 * 9)
    assert.match(hm, /^\d{2}:\d{2}$/)
    assert.equal(protocol.formatListTime(dayStart - 86400000 + 1000), '昨天')
    assert.match(protocol.formatListTime(dayStart - 3 * 86400000), /^\d+\/\d+$/)
  })

  it('getHistory 支持翻页参数且不影响普通帧', () => {
    const normal = protocol.getHistory('100', 20)
    assert.equal(normal.older, undefined)
    const older = protocol.getHistory('100', 20, { older: true, beforeTime: 12345 })
    assert.equal(older.older, true)
    assert.equal(older.before_time, 12345)
  })
})
