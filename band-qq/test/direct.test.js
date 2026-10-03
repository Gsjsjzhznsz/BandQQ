import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { cqText, normalizeHistoryMessage, createDirect } from '../src/common/direct.js'
import { createStore } from '../src/common/store.js'

function mockStorage(initial) {
  const map = new Map(Object.entries(initial))
  return {
    get: (o) => { o.success(map.get(o.key) ?? '') },
    set: (o) => { map.set(o.key, o.value); o.success({}) }
  }
}

describe('direct.cqText', () => {
  it('纯文本原样', () => {
    const r = cqText('你好世界')
    assert.equal(r.text, '你好世界')
    assert.equal(r.at, false)
    assert.equal(r.poke, false)
  })
  it('CQ:at 检测 @我', () => {
    const r = cqText('[CQ:at,qq=12345] 看这个')
    assert.equal(r.at, true)
    assert.equal(r.text, ' 看这个')
  })
  it('CQ:poke 拍一拍', () => {
    const r = cqText('[CQ:poke,qq=123,action=戳了戳]')
    assert.equal(r.poke, true)
    assert.equal(r.text, '拍了拍你')
  })
  it('图片/表情/语音/文件降级占位', () => {
    const r = cqText('看图[CQ:image,file=abc.jpg]哈[CQ:face,id=1]语音[CQ:record,file=x]文件[CQ:file,name=y]')
    assert.equal(r.text, '看图[图片]哈[表情]语音[语音]文件[文件]')
  })
})

describe('direct.normalizeHistoryMessage', () => {
  it('OneBot 消息 → BandQQ 格式（毫秒时间/card 优先）', () => {
    const m = normalizeHistoryMessage({
      time: 1700000000,
      sender: { user_id: 111, nickname: 'nick', card: 'cardName' },
      raw_message: 'hello'
    }, 'group', 999)
    assert.equal(m.message_type, 'group')
    assert.equal(m.sender_id, '111')
    assert.equal(m.sender_name, 'cardName')
    assert.equal(m.content, 'hello')
    assert.equal(m.is_self, false)
    assert.equal(m.time, 1700000000000)
  })
  it('自己消息 is_self + @我 不标', () => {
    const m = normalizeHistoryMessage({
      time: 1700000000,
      sender: { user_id: 999, nickname: 'me' },
      raw_message: '[CQ:at,qq=999] 我自己'
    }, 'private', 999)
    assert.equal(m.is_self, true)
    assert.equal(m.at, undefined)
  })
  it('他人 @我 高亮', () => {
    const m = normalizeHistoryMessage({
      time: 1700000000,
      sender: { user_id: 111, nickname: 'other' },
      raw_message: '[CQ:at,qq=999] 来'
    }, 'group', 999)
    assert.equal(m.at, true)
  })
})

describe('direct.createDirect 注入 mock', () => {
  const cfg = { url: 'http://10.0.0.5:3000', token: 'abc' }

  function withCall(handler) {
    return createDirect({ callOneBot: (c, action, params) => handler(c, action, params) })
  }

  it('send_message 私聊 → send_private_msg 参数', async () => {
    let captured = null
    const d = withCall((c, action, params) => {
      captured = { action, params }
      return Promise.resolve({ message_id: 7 })
    })
    const ok = await d.sendPayload(cfg, { type: 'send_message', message_type: 'private', target_id: '111', content: 'hi' })
    assert.equal(ok, true)
    assert.equal(captured.action, 'send_private_msg')
    assert.equal(captured.params.user_id, 111)
    assert.equal(captured.params.message, 'hi')
  })
  it('send_message 群聊 → send_group_msg 参数', async () => {
    let captured = null
    const d = withCall((c, action, params) => {
      captured = { action, params }
      return Promise.resolve({ message_id: 7 })
    })
    await d.sendPayload(cfg, { type: 'send_message', message_type: 'group', target_id: '20001', content: '群发' })
    assert.equal(captured.action, 'send_group_msg')
    assert.equal(captured.params.group_id, 20001)
  })
  it('get_visible_contacts 合并好友+群', async () => {
    const d = withCall((c, action) => {
      if (action === 'get_friend_list') return Promise.resolve([{ user_id: 1, nickname: '甲' }, { user_id: 2, remark: '乙乙' }])
      if (action === 'get_group_list') return Promise.resolve([{ group_id: 20001, group_name: '体验群' }])
      return Promise.reject({ code: -1 })
    })
    const contacts = await d.sendPayload(cfg, { type: 'get_visible_contacts' })
    assert.equal(contacts.length, 3)
    assert.deepEqual(contacts[0], { id: '1', type: 'private', name: '甲' })
    assert.deepEqual(contacts[1], { id: '2', type: 'private', name: '乙乙' })
    assert.deepEqual(contacts[2], { id: '20001', type: 'group', name: '体验群' })
  })
  it('get_history 走 get_msg_history 且时间升序', async () => {
    const d = withCall((c, action, params) => {
      if (action === 'get_login_info') return Promise.resolve({ user_id: 999 })
      if (action === 'get_msg_history') {
        assert.equal(params.user_id, 111)
        assert.equal(params.count, 15)
        return Promise.resolve({ messages: [
          { time: 1700000002, sender: { user_id: 111, nickname: 'a' }, raw_message: '第二条' },
          { time: 1700000001, sender: { user_id: 111, nickname: 'a' }, raw_message: '第一条' }
        ] })
      }
      return Promise.reject({ code: -1 })
    })
    const list = await d.sendPayload(cfg, { type: 'get_history', target_id: '111', limit: 15 })
    assert.equal(list.length, 2)
    assert.equal(list[0].content, '第一条')
    assert.equal(list[1].content, '第二条')
  })
  it('get_connect_state 本地构造直连态（不走 HTTP）', async () => {
    let called = false
    const d = withCall(() => { called = true; return Promise.resolve({}) })
    const s = await d.sendPayload(cfg, { type: 'get_connect_state' })
    assert.equal(called, false)
    assert.deepEqual(s, { band: true, protocol: true, direct: true })
  })
  it('read_chat 静默成功', async () => {
    const d = withCall(() => Promise.reject({ code: -1 }))
    await d.sendPayload(cfg, { type: 'read_chat', target_id: '1' })
  })
  it('不支持的帧拒绝', async () => {
    const d = withCall(() => Promise.resolve({}))
    await assert.rejects(() => d.sendPayload(cfg, { type: 'clear_all_history' }))
  })
})

describe('store.directCfg 持久化', () => {
  it('未配置返回 null', async () => {
    const s = createStore(mockStorage({}))
    await s.init()
    assert.equal(s.getDirectCfg(), null)
  })
  it('setDirectCfg 落地并可读回（重开 store 仍在）', async () => {
    const map = new Map()
    const st = mockStorage({})
    const s1 = createStore(st)
    await s1.init()
    await s1.setDirectCfg({ url: 'http://10.0.0.5:3000', token: 'tk' })
    assert.deepEqual(s1.getDirectCfg(), { url: 'http://10.0.0.5:3000', token: 'tk' })
    // 模拟重启：用同一个底层存储重开
    const s2 = createStore(st)
    await s2.init()
    assert.deepEqual(s2.getDirectCfg(), { url: 'http://10.0.0.5:3000', token: 'tk' })
  })
  it('空 url 视为清除配置', async () => {
    const s = createStore(mockStorage({}))
    await s.init()
    await s.setDirectCfg({ url: 'http://a', token: '' })
    await s.setDirectCfg({ url: '', token: '' })
    assert.equal(s.getDirectCfg(), null)
  })
})

describe('store 消息容量与排序幂等', () => {
  it('容量上限 120（窗口化后数据层余量）', async () => {
    const s = createStore(mockStorage({}))
    await s.init()
    const base = 1700000000
    for (let i = 0; i < 130; i++) {
      await s.upsertMessage({ type: 'push_message', message_type: 'private', target_id: '9', sender_id: '1', sender_name: 'A', content: 'm' + i, time: base + i * 1000 })
    }
    const msgs = await s.getMessages('9')
    assert.equal(msgs.length, 120)
    assert.equal(msgs[0].content, 'm10')
    assert.equal(msgs[119].content, 'm129')
  })
  it('getMessages 二次读取不重复排序（_sorted 幂等）', async () => {
    const s = createStore(mockStorage({}))
    await s.init()
    await s.upsertMessage({ type: 'push_message', message_type: 'private', target_id: '9', sender_id: '1', sender_name: 'A', content: 'a', time: 1000 })
    const m1 = await s.getMessages('9')
    const m2 = await s.getMessages('9')
    assert.equal(m2, m1)
  })
})

describe('direct v2.16.0 OneBot v11 扩展动作直连翻译', () => {
  const cfg = { url: 'http://10.0.0.5:3000', token: 'abc' }
  function withCall(handler) {
    return createDirect({ callOneBot: (c, action, params) => handler(c, action, params) })
  }
  it('send_like 数字 user_id + times', async () => {
    let cap = null
    const d = withCall((c, action, params) => { cap = { action, params }; return Promise.resolve({}) })
    await d.sendPayload(cfg, { type: 'send_like', target_id: '111', times: 10 })
    assert.equal(cap.action, 'send_like')
    assert.equal(cap.params.user_id, 111)
    assert.equal(cap.params.times, 10)
  })
  it('send_poke 私聊 friend_poke / 群聊 group_poke', async () => {
    let cap = null
    const d = withCall((c, action, params) => { cap = { action, params }; return Promise.resolve({}) })
    await d.sendPayload(cfg, { type: 'send_poke', target_id: '111', chat_type: 'private' })
    assert.equal(cap.action, 'friend_poke')
    await d.sendPayload(cfg, { type: 'send_poke', target_id: '20001', chat_type: 'group' })
    assert.equal(cap.action, 'group_poke')
    assert.equal(cap.params.group_id, 20001)
  })
  it('group_sign → send_group_sign', async () => {
    let cap = null
    const d = withCall((c, action, params) => { cap = { action, params }; return Promise.resolve({}) })
    await d.sendPayload(cfg, { type: 'group_sign', target_id: '20001' })
    assert.equal(cap.action, 'send_group_sign')
    assert.equal(cap.params.group_id, 20001)
  })
  it('message_action delete → delete_msg 数值 message_id（NapCat 按 number 索引）', async () => {
    let cap = null
    const d = withCall((c, action, params) => { cap = { action, params }; return Promise.resolve({}) })
    await d.sendPayload(cfg, { type: 'message_action', sub_action: 'delete', message_id: '123456' })
    assert.equal(cap.action, 'delete_msg')
    assert.equal(cap.params.message_id, 123456)
    assert.equal(typeof cap.params.message_id, 'number')
  })
  it('message_action emoji → set_msg_emoji_like 数值 id + 字符串 emoji_id', async () => {
    let cap = null
    const d = withCall((c, action, params) => { cap = { action, params }; return Promise.resolve({}) })
    await d.sendPayload(cfg, { type: 'message_action', sub_action: 'emoji', message_id: '777', emoji_id: '128077' })
    assert.equal(cap.action, 'set_msg_emoji_like')
    assert.equal(cap.params.message_id, 777)
    assert.equal(cap.params.emoji_id, '128077')
  })
  it('超长字符串 message_id（LLOneBot 形态）保留字符串不丢精度', async () => {
    let cap = null
    const d = withCall((c, action, params) => { cap = { action, params }; return Promise.resolve({}) })
    const longId = '7352845278123456789'
    await d.sendPayload(cfg, { type: 'message_action', sub_action: 'delete', message_id: longId })
    assert.equal(cap.params.message_id, longId)
    assert.equal(typeof cap.params.message_id, 'string')
  })
  it('get_user_info 私聊 stranger / 群聊 member', async () => {
    let cap = null
    const d = withCall((c, action, params) => { cap = { action, params }; return Promise.resolve({ nickname: 'x' }) })
    await d.sendPayload(cfg, { type: 'get_user_info', target_id: '111', chat_type: 'private' })
    assert.equal(cap.action, 'get_stranger_info')
    await d.sendPayload(cfg, { type: 'get_user_info', target_id: '111', chat_type: 'group', group_id: '20001' })
    assert.equal(cap.action, 'get_group_member_info')
    assert.equal(cap.params.group_id, 20001)
  })
})
