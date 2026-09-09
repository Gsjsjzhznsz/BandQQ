import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { nextSeq, sendMessage, getConversations, getHistory, degradeContent, decodePush, getVisibleContacts, getConnectState, stripEmoji, markEmoji } from '../src/common/protocol.js'

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

  it('构造 get_visible_contacts 帧', () => {
    const msg = getVisibleContacts()
    assert.equal(msg.type, 'get_visible_contacts')
  })

  it('构造 get_connect_state 帧', () => {
    const msg = getConnectState()
    assert.equal(msg.type, 'get_connect_state')
  })

  it('decodePush 透传 target_name', () => {
    const raw = { type: 'push_message', message_type: 'group', target_id: '9', sender_id: '8', sender_name: '张三', target_name: '群名', content: '你好', time: 1700000000 }
    const msg = decodePush(raw)
    assert.equal(msg.target_name, '群名')
  })

  it('decodePush 无 target_name 时为空串', () => {
    const raw = { type: 'push_message', message_type: 'group', target_id: '9', sender_id: '8', sender_name: '张三', content: '你好', time: 1700000000 }
    const msg = decodePush(raw)
    assert.equal(msg.target_name, '')
  })

  it('decodePush 透传 visible', () => {
    const raw = { type: 'push_message', message_type: 'group', target_id: '9', sender_id: '8', sender_name: '张三', content: '你好', time: 1700000000, visible: false }
    const msg = decodePush(raw)
    assert.equal(msg.visible, false)
  })

  it('decodePush 默认 visible 为 true', () => {
    const raw = { type: 'push_message', message_type: 'group', target_id: '9', sender_id: '8', sender_name: '张三', content: '你好', time: 1700000000 }
    const msg = decodePush(raw)
    assert.equal(msg.visible, true)
  })

  it('stripEmoji 剔除名称中的 emoji', () => {
    assert.equal(stripEmoji('😊张三👍'), '张三')
    assert.equal(stripEmoji('王🌹'), '王')
  })

  it('markEmoji 将文本中的 emoji 替换为 [表情]', () => {
    assert.equal(markEmoji('hi😊wow👋'), 'hi[表情]wow[表情]')
  })

  it('degradeContent 将文本 emoji 降级为 [表情]', () => {
    assert.equal(degradeContent([{ type: 'text', data: { text: '早😊' } }, { type: 'face', data: { id: '178' } }]), '早[表情][表情]')
  })

  it('decodePush 剔除名称并保留内容 emoji 标记', () => {
    const raw = { type: 'push_message', message_type: 'group', target_id: '9', sender_id: '8', sender_name: '🌟阿杰', target_name: '群名🌺', content: '注意😄', time: 1700000000 }
    const msg = decodePush(raw)
    assert.equal(msg.sender_name, '阿杰')
    assert.equal(msg.target_name, '群名')
    assert.equal(msg.content, '注意[表情]')
  })
})
