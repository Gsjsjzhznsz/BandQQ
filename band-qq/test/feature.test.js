import { test } from 'node:test'
import assert from 'node:assert/strict'
import * as store from '../src/common/store.js'
import * as protocol from '../src/common/protocol.js'

function freshStore() {
  store.resetAll()
  store.setMyProfile('', '')
}

const BASE = {
  messageId: 1001,
  chatType: 'group',
  targetId: '802072961',
  senderId: '555',
  senderName: '小明',
  content: '你好',
  time: 1700000000, // 秒（OneBot）
  isSelf: false
}

test('normalizeTime: OneBot 秒 -> 毫秒统一', () => {
  assert.equal(store.normTime(1700000000), 1700000000000)
  assert.equal(store.normTime(1700000000000), 1700000000000)
})

test('upsertMessage 基本入库与会话摘要', () => {
  freshStore()
  const r = store.upsertMessage(BASE)
  assert.equal(r, 'new')
  const convs = store.getConversationList()
  assert.equal(convs.length, 1)
  assert.equal(convs[0].id, '802072961')
  assert.equal(convs[0].lastContent, '你好')
  assert.equal(convs[0].lastTime, 1700000000000)
  assert.equal(convs[0].name, '小明')
  assert.equal(convs[0].unread, 1)
})

test('messageId 去重：回放幂等（v1.1.1 冷启动修复）', () => {
  freshStore()
  store.upsertMessage(BASE)
  const r = store.upsertMessage(BASE) // 重复投递
  assert.equal(r, 'dup')
  assert.equal(store.getMessages('802072961').length, 1)
  const convs = store.getConversationList()
  assert.equal(convs[0].unread, 1) // 未读不重复累加
})

test('self-echo 合并：服务器回显并入乐观回显', () => {
  freshStore()
  const t = Date.now()
  // 乐观回显（messageId=0）
  store.upsertMessage({ ...BASE, messageId: 0, content: '测试', time: t, isSelf: true })
  // 服务器推回（messageId>0, time 接近）
  const r = store.upsertMessage({ ...BASE, messageId: 555, content: '测试', time: t + 500, isSelf: true })
  assert.equal(r, 'updated')
  const msgs = store.getMessages('802072961')
  assert.equal(msgs.length, 1)
  assert.equal(msgs[0].message_id, 555)
})

test('自消息 sender 修正：不使用会话ID（旧 bug）', () => {
  freshStore()
  store.setMyProfile('2308534727', '一秋')
  store.upsertMessage({ ...BASE, content: '6', time: Date.now(), isSelf: true })
  const msg = store.getMessages('802072961')[0]
  assert.equal(msg.sender_id, '2308534727')
  assert.equal(msg.sender_name, '一秋')
  assert.notEqual(msg.sender_id, '802072961')
})

test('@我：会话标记 + 消息标记 + 未读', () => {
  freshStore()
  store.upsertMessage({ ...BASE, atMe: true })
  const convs = store.getConversationList()
  assert.equal(convs[0].atMe, true)
  const msg = store.getMessages('802072961')[0]
  assert.equal(msg.at_me, true)
  assert.equal(convs[0].unread, 1)
})

test('活跃会话不累计未读，markRead 清零', () => {
  freshStore()
  store.setActiveChat('802072961')
  store.upsertMessage(BASE)
  assert.equal(store.getConversationList()[0].unread, 0)
  store.setActiveChat(null)
  store.upsertMessage({ ...BASE, messageId: 1002 })
  assert.equal(store.getConversationList()[0].unread, 1)
  store.markRead('802072961')
  assert.equal(store.getConversationList()[0].unread, 0)
})

test('sanitizeLegacy: 群会话 private+is_self+sender_id==会话ID 残留被清理', async () => {
  freshStore()
  // 模拟旧 bug 数据
  store.messages['194636275'] = [
    { message_id: 1, message_type: 'private', sender_id: '194636275', sender_name: 'x', content: '测式', time: 1700000000000, is_self: true },
    { message_id: 2, message_type: 'group', sender_id: '777', sender_name: '我的世界一秋小镇', content: '测式', time: 1700000005000, is_self: false }
  ]
  // schema_ver 未设置 -> 会清理
  const changed = await store.sanitizeLegacy()
  assert.equal(changed, true)
  const list = store.messages['194636275']
  assert.equal(list.length, 1)
  assert.equal(list[0].message_type, 'group')
})

test('prependOlder: 翻页合并去重', () => {
  freshStore()
  store.upsertMessage(BASE)
  const added = store.prependOlder('802072961', [
    { message_id: 900, message_type: 'group', sender_id: '1', sender_name: 'a', content: '旧1', time: 1699999000, is_self: false },
    { message_id: 1001, message_type: 'group', sender_id: '555', sender_name: '小明', content: '你好', time: 1700000000, is_self: false } // 与现有重复
  ])
  assert.equal(added, 1)
  const list = store.getMessages('802072961')
  assert.equal(list.length, 2)
  assert.equal(list[0].content, '旧1') // 时间升序
})

test('protocol.decodePush: message/history/list/state', () => {
  const m = protocol.decodePush(JSON.stringify({
    type: 'message', message_id: 9, chat_type: 'group', target_id: '100',
    sender_id: '1', sender_name: 'n', content: 'hi', time: 1700000000,
    is_self: false, at_me: true, thumb: 'AAAA'
  }))
  assert.equal(m.type, 'message')
  assert.equal(m.data.messageId, 9)
  assert.equal(m.data.atMe, true)
  assert.equal(m.data.thumb, 'AAAA')
  assert.equal(store.normTime(m.data.time), 1700000000000)

  const h = protocol.decodePush(JSON.stringify({
    type: 'history', mode: 'older', target_id: '100', has_more: true,
    messages: [{ message_id: 1, message_type: 'group', sender_id: '1', sender_name: 'a', content: 'x', time: 1700000000000, is_self: false }]
  }))
  assert.equal(h.type, 'history')
  assert.equal(h.data.mode, 'older')
  assert.equal(h.data.hasMore, true)

  const l = protocol.decodePush(JSON.stringify({ type: 'list', contacts: [{ id: '1', type: 'group', name: 'g' }] }))
  assert.equal(l.data.length, 1)

  const s = protocol.decodePush(JSON.stringify({ type: 'state', connected: true }))
  assert.equal(s.data.connected, true)
})

test('protocol.needTimeSplit: 5 分钟阈值（Stapxs 逻辑）', () => {
  assert.equal(protocol.needTimeSplit(0, 1700000000000), true)
  assert.equal(protocol.needTimeSplit(1700000000000, 1700000000000 + 5 * 60 * 1000), true)
  assert.equal(protocol.needTimeSplit(1700000000000, 1700000000000 + 5 * 60 * 1000 - 1), false)
})

test('protocol.formatTime: 今天/昨天', () => {
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 9, 30).getTime()
  assert.ok(/^\d{2}:\d{2}$/.test(protocol.formatTime(today)))
  const yesterday = today - 86400000
  assert.equal(protocol.formatTime(yesterday), '昨天')
})

test('encodeGetHistory 翻页参数', () => {
  const f = JSON.parse(protocol.encodeGetHistory('100', 'group', 20, { older: true, beforeTime: 1700000000000 }))
  assert.equal(f.older, true)
  assert.equal(f.before_time, 1700000000000)
  assert.equal(f.limit, 20)
})

test('会话列表稳定排序（同秒按 id，不抖动）', () => {
  freshStore()
  store.upsertMessage({ ...BASE, targetId: '200', time: 1700000000 })
  store.upsertMessage({ ...BASE, targetId: '100', time: 1700000000 })
  const convs = store.getConversationList()
  assert.equal(convs[0].id, '100')
  assert.equal(convs[1].id, '200')
})
