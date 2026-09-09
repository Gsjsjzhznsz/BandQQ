import { describe, it, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { createStore } from '../src/common/store.js'

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

  it('setMessages 按 time+content 合并去重', async () => {
    const list = [{ message_type: 'group', sender_id: '2', sender_name: 'B', content: '旧', time: 1700000000 }]
    await store.setMessages('200', list)
    assert.equal((await store.getMessages('200')).length, 1)
    // 已有消息 + 空历史：合并保留已有，不覆盖丢失
    await store.setMessages('200', [])
    assert.equal((await store.getMessages('200')).length, 1)
    // 同 time+content 的历史不产生重复
    await store.setMessages('200', list)
    assert.equal((await store.getMessages('200')).length, 1)
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

  it('clearAllMessages 清空消息与会话', async () => {
    const msg = { type: 'push_message', message_type: 'group', target_id: '100', sender_id: '1', sender_name: 'A', content: 'hi', time: 1700000000 }
    await store.upsertMessage(msg)
    assert.equal((await store.getConversations()).length, 1)
    await store.clearAllMessages()
    assert.deepEqual(await store.getConversations(), [])
    assert.deepEqual(await store.getMessages('100'), [])
  })

  it('visibleContacts 持久化', async () => {
    await store.setVisibleContacts([{ id: '100', type: 'private', name: '小明' }])
    assert.deepEqual(await store.getVisibleContacts(), [{ id: '100', type: 'private', name: '小明' }])
  })

  it('isVisible 判定', async () => {
    await store.setVisibleContacts([{ id: '100', type: 'private', name: '小明' }])
    assert.equal(store.isVisible('100'), true)
    assert.equal(store.isVisible('200'), false)
  })

  it('未添加联系人消息标记临时', async () => {
    await store.setVisibleContacts([{ id: '100', type: 'private', name: '小明' }])
    const msg = { type: 'push_message', message_type: 'private', target_id: '200', sender_id: '200', sender_name: '张三', content: '你好', visible: false, time: 1700000000 }
    await store.upsertMessage(msg)
    const convs = await store.getConversations()
    assert.equal(convs[0].is_temporary, true)
  })

  it('已添加联系人消息非临时', async () => {
    await store.setVisibleContacts([{ id: '100', type: 'private', name: '小明' }])
    const msg = { type: 'push_message', message_type: 'private', target_id: '100', sender_id: '100', sender_name: '小明', content: '你好', visible: true, time: 1700000000 }
    await store.upsertMessage(msg)
    const convs = await store.getConversations()
    assert.equal(convs[0].is_temporary, false)
  })

  it('setVisibleContacts 清除临时会话', async () => {
    const tmp = { type: 'push_message', message_type: 'private', target_id: '200', sender_id: '200', sender_name: '张三', content: '你好', visible: false, time: 1700000000 }
    await store.upsertMessage(tmp)
    assert.equal((await store.getConversations()).length, 1)
    await store.setVisibleContacts([{ id: '100', type: 'private', name: '小明' }])
    const convs = await store.getConversations()
    assert.equal(convs.some((c) => c.id === '200'), false)
    assert.equal(convs.some((c) => c.id === '100' && c.is_temporary === false), true)
    assert.deepEqual(await store.getMessages('200'), [])
  })

  it('空 conversation_list 不清空可见联系人骨架', async () => {
    await store.setVisibleContacts([{ id: '100', type: 'private', name: '小明' }])
    await store.setConversations([])
    const convs = await store.getConversations()
    assert.equal(convs.length, 1)
    assert.equal(convs[0].id, '100')
    assert.equal(convs[0].name, '小明')
  })

  it('conversation_list 后到不覆盖可见联系人骨架', async () => {
    await store.setVisibleContacts([{ id: '100', type: 'private', name: '小明' }, { id: '101', type: 'group', name: '群' }])
    await store.setConversations([{ id: '101', type: 'group', name: '群', last_msg: 'x', time: 1700000000 }])
    const convs = await store.getConversations()
    assert.equal(convs.some((c) => c.id === '100'), true)
    assert.equal(convs.some((c) => c.id === '101'), true)
  })

  it('先会话后联系人仍保留骨架', async () => {
    await store.setConversations([{ id: '101', type: 'group', name: '群', last_msg: 'x', time: 1700000000 }])
    await store.setVisibleContacts([{ id: '100', type: 'private', name: '小明' }, { id: '101', type: 'group', name: '群' }])
    const convs = await store.getConversations()
    assert.equal(convs.some((c) => c.id === '100'), true)
    assert.equal(convs.some((c) => c.id === '101'), true)
  })

  it('setConnectState 保存并可读取 band/protocol', () => {
    store.setConnectState({ type: 'connect_state', band: true, protocol: false })
    const s = store.getConnectState()
    assert.equal(s.band, true)
    assert.equal(s.protocol, false)
  })

  it('群消息会话名优先用 target_name', async () => {
    const msg = { type: 'push_message', message_type: 'group', target_id: '100', sender_id: '1', sender_name: '张三', target_name: '技术交流群', content: 'hi', time: 1700000000 }
    await store.upsertMessage(msg)
    const convs = await store.getConversations()
    assert.equal(convs[0].name, '技术交流群')
  })

  it('无 target_name 时会话名回退为 sender_name', async () => {
    const msg = { type: 'push_message', message_type: 'private', target_id: '200', sender_id: '9', sender_name: '李四', content: 'hi', time: 1700000000 }
    await store.upsertMessage(msg)
    const convs = await store.getConversations()
    assert.equal(convs[0].name, '李四')
  })

  it('upsert 乱序到达时消息按时间升序', async () => {
    await store.upsertMessage({ type: 'push_message', message_type: 'private', target_id: '500', sender_id: '1', sender_name: 'A', content: 'c', time: 300 })
    await store.upsertMessage({ type: 'push_message', message_type: 'private', target_id: '500', sender_id: '1', sender_name: 'A', content: 'a', time: 100 })
    await store.upsertMessage({ type: 'push_message', message_type: 'private', target_id: '500', sender_id: '1', sender_name: 'A', content: 'b', time: 200 })
    const msgs = await store.getMessages('500')
    assert.deepEqual(msgs.map((m) => m.content), ['a', 'b', 'c'])
  })

  it('getMessages 对乱序缓存兜底排序', async () => {
    const storage = mockStorage({})
    const s = createStore(storage)
    await s.init()
    // 绕过 setMessages 的排序，直接向缓存写入乱序数据
    await storage.set({
      key: 'msg_cache_600',
      value: JSON.stringify([
        { message_type: 'private', sender_id: '1', sender_name: 'A', content: 'b', time: 200 },
        { message_type: 'private', sender_id: '1', sender_name: 'A', content: 'a', time: 100 }
      ]),
      success: () => {}
    })
    const msgs = await s.getMessages('600')
    assert.deepEqual(msgs.map((m) => m.content), ['a', 'b'])
  })
})
