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

  it('setVisibleContacts 保留仍有消息的临时会话（v2.6.0 防手环消息被清）', async () => {
    const tmp = { type: 'push_message', message_type: 'private', target_id: '200', sender_id: '200', sender_name: '张三', content: '你好', visible: false, time: 1700000000 }
    await store.upsertMessage(tmp)
    assert.equal((await store.getConversations()).length, 1)
    await store.setVisibleContacts([{ id: '100', type: 'private', name: '小明' }])
    const convs = await store.getConversations()
    // v2.6.0：有本地消息的临时会话不再被联系人同步清掉（手环消息消失 bug 根治）
    assert.equal(convs.some((c) => c.id === '200'), true)
    assert.equal(convs.some((c) => c.id === '100' && c.is_temporary === false), true)
    assert.equal((await store.getMessages('200')).length, 1)
  })

  it('setVisibleContacts 仍清除无消息的临时骨架会话', async () => {
    await store.setConversations([{ id: '300', type: 'private', name: '临时骨架', last_msg: '', time: 0, unread: 0, is_temporary: true }])
    assert.equal((await store.getConversations()).some((c) => c.id === '300'), true)
    await store.setVisibleContacts([{ id: '100', type: 'private', name: '小明' }])
    const convs = await store.getConversations()
    assert.equal(convs.some((c) => c.id === '300'), false)
    assert.equal(convs.some((c) => c.id === '100' && c.is_temporary === false), true)
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

describe('store v2（未读/快捷回复/翻页合并/显示字段）', () => {
  it('push_message 携带 unread 时写入会话', async () => {
    await store.setVisibleContacts([{ id: 'u1', type: 'private', name: '小明' }])
    await store.upsertMessage({ type: 'push_message', message_type: 'private', target_id: 'u1', sender_id: 'u1', sender_name: '小明', target_name: '小明', content: '你好', time: 1000, is_self: false, unread: 5 })
    const convs = await store.getConversations()
    const c = convs.find((x) => x.id === 'u1')
    assert.equal(c.unread, 5)
  })

  it('旧端 push（unread=-1）本地保守自增，仅可见会话', async () => {
    await store.setVisibleContacts([{ id: 'u2', type: 'private', name: '小红' }])
    await store.upsertMessage({ type: 'push_message', message_type: 'private', target_id: 'u2', sender_id: 'u2', sender_name: '小红', target_name: '小红', content: 'a', time: 1000, is_self: false, unread: -1 })
    await store.upsertMessage({ type: 'push_message', message_type: 'private', target_id: 'u2', sender_id: 'u2', sender_name: '小红', target_name: '小红', content: 'b', time: 2000, is_self: false, unread: -1 })
    const convs = await store.getConversations()
    assert.equal(convs.find((x) => x.id === 'u2').unread, 2)
  })

  it('setConversations 合并手机端预计算字段', async () => {
    await store.setConversations([{ id: 'u3', type: 'group', name: '测试群', last_msg: 'hello', time: 100, unread: 7, n9: '测试群', achar: '测', hue: 100, prev: 'hello', tstr: '10:00' }])
    const convs = await store.getConversations()
    const c = convs.find((x) => x.id === 'u3')
    assert.equal(c.n9, '测试群')
    assert.equal(c.achar, '测')
    assert.equal(c.unread, 7)
    assert.equal(c.tstr, '10:00')
    assert.ok(typeof c.hueBg === 'string' && c.hueBg[0] === '#')
  })

  it('decorate 兜底：缺失字段本地计算', async () => {
    await store.setConversations([{ id: '88', type: 'private', name: '很长很长的名字啊哈哈哈哈哈哈', last_msg: '内容', time: 5 }])
    const convs = await store.getConversations()
    const c = convs.find((x) => x.id === '88')
    assert.equal(c.n9, '很长很长的名字啊哈…')
    assert.equal(c.achar, '很')
    assert.ok(c.hueBg.length === 7)
  })

  it('prependMessages 去重合并更早消息', async () => {
    await store.setMessages('p1', [
      { message_type: 'private', sender_id: '1', sender_name: 'A', content: 'new', time: 200 },
      { message_type: 'private', sender_id: '1', sender_name: 'A', content: 'mid', time: 300 }
    ])
    const added = await store.prependMessages('p1', [
      { message_type: 'private', sender_id: '1', sender_name: 'A', content: 'old', time: 100 },
      { message_type: 'private', sender_id: '1', sender_name: 'A', content: 'mid', time: 300 }
    ])
    assert.equal(added, 1)
    const msgs = await store.getMessages('p1')
    assert.deepEqual(msgs.map((m) => m.content), ['old', 'new', 'mid'])
  })

  it('prependMessages 全重复时 added=0', async () => {
    await store.setMessages('p2', [
      { message_type: 'private', sender_id: '1', sender_name: 'A', content: 'a', time: 100 }
    ])
    const added = await store.prependMessages('p2', [
      { message_type: 'private', sender_id: '1', sender_name: 'A', content: 'a', time: 100 }
    ])
    assert.equal(added, 0)
  })

  it('快捷回复：默认值 + 手机端下发覆盖 + 持久化', async () => {
    assert.equal(store.getQuickReplies().length, 6)
    store.setQuickReplies([
      { label: '在', content: '[CQ:face,id=74]' },
      { label: '稍后', content: '稍后回复你' }
    ])
    const qr = store.getQuickReplies()
    assert.equal(qr.length, 2)
    assert.equal(qr[0].label, '在')
    assert.equal(qr[0].content, '[CQ:face,id=74]')
    // 持久化后新实例恢复
    const s2 = createStore(mockStorage({ quick_replies: JSON.stringify(qr) }))
    await s2.init()
    assert.equal(s2.getQuickReplies().length, 2)
    assert.equal(s2.getQuickReplies()[1].label, '稍后')
  })

  it('hsl 色彩换算：hue 0/120/240 输出确定 hex', async () => {
    await store.setConversations([
      { id: 'a', type: 'private', name: 'a', last_msg: '', time: 0, hue: 0 },
      { id: 'b', type: 'private', name: 'b', last_msg: '', time: 0, hue: 120 },
      { id: 'c', type: 'private', name: 'c', last_msg: '', time: 0, hue: 240 }
    ])
    const convs = await store.getConversations()
    assert.equal(convs.find((x) => x.id === 'a').hueBg, '#9b3131')
    assert.equal(convs.find((x) => x.id === 'b').hueBg, '#319b31')
    assert.equal(convs.find((x) => x.id === 'c').hueBg, '#31319b')
  })

  it('v2.8.3 修复：at=1（APP Gson 数字形态）实时推送也标记 @我 高亮与会话 cat', async () => {
    // APP 端 toHandBandFrame 用 addProperty("at", 1) 发数字 1（decodePush 从未被调用）
    await store.upsertMessage({ type: 'push_message', message_type: 'group', target_id: '400', sender_id: '2', sender_name: '王', content: '看这里', time: 1700000010, at: 1 })
    const msgs = await store.getMessages('400')
    assert.equal(msgs[0].at, true)
    const convs = await store.getConversations()
    assert.equal(convs.find((x) => x.id === '400').cat, 1)
    // 布尔形态（decodePush 兼容路径）同样生效
    await store.upsertMessage({ type: 'push_message', message_type: 'private', target_id: '401', sender_id: '3', sender_name: '李', content: '在吗', time: 1700000011, at: true })
    assert.equal((await store.getMessages('401'))[0].at, true)
    // 无 at 字段的普通消息不带标志
    await store.upsertMessage({ type: 'push_message', message_type: 'private', target_id: '402', sender_id: '4', sender_name: '赵', content: '普通', time: 1700000012 })
    assert.equal((await store.getMessages('402'))[0].at, undefined)
    assert.equal((await store.getConversations()).find((x) => x.id === '402').cat, 0)
  })

  it('v2.8.3 修复：recall=1（APP Gson 数字形态）撤回帧原位替换而不新增消息', async () => {
    await store.upsertMessage({ type: 'push_message', message_type: 'private', target_id: '500', sender_id: '2', sender_name: 'A', content: '原消息', time: 1700000020 })
    // 撤回同步帧：数字 1 形态
    await store.upsertMessage({ type: 'push_message', message_type: 'private', target_id: '500', sender_id: '2', sender_name: 'A', content: '对方撤回了一条消息', time: 1700000020, recall: 1 })
    const msgs = await store.getMessages('500')
    assert.equal(msgs.length, 1, '撤回帧不新增消息')
    assert.equal(msgs[0].content, '对方撤回了一条消息')
    assert.equal(msgs[0].rc, 1, '原位灰显标志')
  })

  it('v2.9.0 拍一拍：poke=1/true 双形态入库，普通消息不带标志', async () => {
    // APP 端 buildPokeFrame 发数字 1
    await store.upsertMessage({ type: 'push_message', message_type: 'private', target_id: '600', sender_id: '7', sender_name: '马化腾', content: '马化腾 拍了拍你', time: 1700000030, poke: 1 })
    const msgs = await store.getMessages('600')
    assert.equal(msgs[0].poke, true)
    const convs = await store.getConversations()
    assert.equal(convs.find((x) => x.id === '600').last_msg, '马化腾 拍了拍你', '会话预览直接显示拍一拍文案')
    // 布尔形态（decodePush 兼容路径）
    await store.upsertMessage({ type: 'push_message', message_type: 'group', target_id: '601', sender_id: '8', sender_name: '小明', content: '小明 拍了拍你', time: 1700000031, poke: true })
    assert.equal((await store.getMessages('601'))[0].poke, true)
    // 普通消息不带 poke
    await store.upsertMessage({ type: 'push_message', message_type: 'private', target_id: '602', sender_id: '9', sender_name: '赵', content: '普通', time: 1700000032 })
    assert.equal((await store.getMessages('602'))[0].poke, undefined)
  })

  it('v2.9.0 免打扰：toggleMute/isMuted/会话 muted 标志双端同步', async () => {
    await store.upsertMessage({ type: 'push_message', message_type: 'private', target_id: '700', sender_id: '3', sender_name: '李', content: '在吗', time: 1700000040 })
    assert.equal(store.isMuted('700'), false, '默认不免打扰')
    assert.equal(await store.toggleMute('700'), 1, '开启返回 1')
    assert.equal(store.isMuted('700'), true)
    assert.equal(store.getSettings().mute_list, '700', 'mute_list 全量字符串')
    const convs = await store.getConversations()
    assert.equal(convs.find((x) => x.id === '700').muted, 1, '会话出口打 muted 标（红点变灰）')
    assert.equal(await store.toggleMute('700'), 0, '关闭返回 0')
    assert.equal(store.isMuted('700'), false)
    // settings_state 下发（手机端为权威源）覆盖本地
    await store.setSettings({ msg_vibrate: true, emoji_native: true, mute_list: '700,701' })
    assert.equal(store.isMuted('700'), true)
    assert.equal(store.isMuted('701'), true)
  })

  it('v2.9.0 演示模式：injectDemo 注入两个演示会话（含 @我/拍一拍/免打扰）', async () => {
    await store.injectDemo()
    const convs = await store.getConversations()
    assert.equal(convs.length, 2)
    const group = convs.find((x) => x.id === '20001')
    assert.equal(group.cat, 1, '群会话有未读 @我 标')
    const groupMsgs = await store.getMessages('20001')
    assert.equal(groupMsgs.some((m) => m.at === true), true, '演示数据含 @我 消息')
    assert.equal(groupMsgs.some((m) => m.poke === true), true, '演示数据含拍一拍消息')
    const pms = await store.getMessages('10001')
    assert.equal(pms.some((m) => m.poke === true), true, '私聊演示含拍一拍')
    assert.equal(store.isMuted('10001'), true, '马化腾会话演示免打扰（灰点）')
    assert.equal(store.isMuted('20001'), false)
  })
})
