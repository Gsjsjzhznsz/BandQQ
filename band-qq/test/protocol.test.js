import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { nextSeq, sendMessage, getConversations, getHistory, degradeContent, decodePush, getVisibleContacts, getConnectState, stripEmoji, markEmoji } from '../src/common/protocol.js'
import protocol from '../src/common/protocol.js'

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

  it('degradeContent 文本 emoji 透传 + face 段映射为 emoji（v2.6.0 表情支持）', () => {
    assert.equal(degradeContent([{ type: 'text', data: { text: '早😊' } }, { type: 'face', data: { id: '178' } }]), '早😊🤣')
  })

  it('degradeContent 未收录 face id 回退 [表情]', () => {
    assert.equal(degradeContent([{ type: 'face', data: { id: '99999' } }, { type: 'text', data: { text: '好' } }]), '[表情]好')
  })

  it('decodePush v2：手机端已降级的字段直接透传（热路径零扫描）', () => {
    const raw = { type: 'push_message', message_type: 'group', target_id: '9', sender_id: '8', sender_name: '阿杰', target_name: '群名', content: '注意[表情]', time: 1700000000, unread: 3 }
    const msg = decodePush(raw)
    assert.equal(msg.sender_name, '阿杰')
    assert.equal(msg.target_name, '群名')
    assert.equal(msg.content, '注意[表情]')
    assert.equal(msg.unread, 3)
  })

  it('decodePush 旧端兼容：非字符串内容仍走 degradeContent', () => {
    const raw = { type: 'push_message', message_type: 'group', target_id: '9', sender_id: '8', sender_name: '张三', content: [{ type: 'text', data: { text: '早' } }, { type: 'image' }] }
    const msg = decodePush(raw)
    assert.equal(msg.content, '早[图片]')
  })

  it('decodePush unread 缺省为 -1（旧端），store 会保守自增兜底', () => {
    const raw = { type: 'push_message', message_type: 'private', target_id: '9', sender_id: '8', content: 'hi' }
    const msg = decodePush(raw)
    assert.equal(msg.unread, -1)
  })

  it('convSignature：内容相同签名一致，未读/预览变化签名不同', () => {
    const { convSignature } = protocol
    const a = [{ id: '1', name: 'A', prev: 'hi', unread: 0, tstr: '10:00', is_temporary: false }]
    const b = [{ id: '1', name: 'A', prev: 'hi', unread: 0, tstr: '10:00', is_temporary: false }]
    assert.equal(convSignature(a), convSignature(b))
    const c = [{ id: '1', name: 'A', prev: 'hi', unread: 2, tstr: '10:00', is_temporary: false }]
    assert.notEqual(convSignature(a), convSignature(c))
    const d = [{ id: '1', name: 'A', prev: 'hello', unread: 0, tstr: '10:00', is_temporary: false }]
    assert.notEqual(convSignature(a), convSignature(d))
    assert.equal(convSignature([]), '')
    assert.equal(convSignature(null), '')
  })

  // ===== v2.14.0 智能自动渲染器（手环端兜底版与手机端 OneBotParser 同规则）=====
  it('渲染器 v2.14：json 卡片 meta.prompt 优先（QQ 分享人话摘要）', () => {
    const card = { meta: { prompt: '[分享]我看到一个很棒的视频，快来看!' } }
    assert.equal(
      degradeContent([{ type: 'json', data: { data: JSON.stringify(card) } }]),
      '[分享]我看到一个很棒的视频，快来看!'
    )
  })

  it('渲染器 v2.14：json 音乐卡片 title + singer 组合', () => {
    const card = { meta: { music: { title: '晴天', singer: '周杰伦' } } }
    assert.equal(
      degradeContent([{ type: 'json', data: { data: JSON.stringify(card) } }]),
      '[卡片] 晴天 · 周杰伦'
    )
  })

  it('渲染器 v2.14：非法 json 回退 [卡片消息]', () => {
    assert.equal(degradeContent([{ type: 'json', data: { data: 'not-json{{' } }]), '[卡片消息]')
  })

  it('渲染器 v2.14：xml 卡片取 <title>，无 title 取 brief', () => {
    assert.equal(
      degradeContent([{ type: 'xml', data: { data: '<msg><title>红包来袭</title></msg>' } }]),
      '[卡片] 红包来袭'
    )
    assert.equal(
      degradeContent([{ type: 'xml', data: { data: '<msg brief="签到成功"></msg>' } }]),
      '[卡片] 签到成功'
    )
  })

  it('渲染器 v2.14：markdown 降纯文本（AstrBot 回复可读）', () => {
    const md = '## 今日天气\n**北京** 晴\n- 最低 12°C\n[详情](https://t.cn/x)'
    const out = degradeContent([{ type: 'markdown', data: { content: md } }])
    assert.ok(!out.includes('#'))
    assert.ok(!out.includes('**'))
    assert.ok(out.includes('北京 晴'))
    assert.ok(out.includes('详情'))
    assert.ok(out.includes('· 最低 12°C'))
  })

  it('渲染器 v2.14：文件带文件名（超长截 24 字符）', () => {
    assert.equal(
      degradeContent([{ type: 'file', data: { name: '年度报告.pdf' } }]),
      '[文件] 年度报告.pdf'
    )
    const long = 'a'.repeat(30) + '.pdf'
    const out = degradeContent([{ type: 'file', data: { name: long } }])
    assert.ok(out.startsWith('[文件] aaaa'))
    assert.ok(out.endsWith('…'))
  })

  it('渲染器 v2.14：GIF 识别 / 合并转发 / 表情包 / 戳一戳 / 骰子', () => {
    assert.equal(degradeContent([{ type: 'image', data: { url: 'https://x/a.gif' } }]), '[GIF]')
    assert.equal(degradeContent([{ type: 'forward', data: { id: '1' } }]), '[合并转发]')
    assert.equal(degradeContent([{ type: 'mface', data: { emoji_id: '1' } }]), '[表情包]')
    assert.equal(degradeContent([{ type: 'poke' }]), '[戳一戳]')
    assert.equal(degradeContent([{ type: 'dice' }]), '[骰子]')
  })

  it('渲染器 v2.14：分享/位置带标题，联系人/礼物占位', () => {
    assert.equal(
      degradeContent([{ type: 'share', data: { title: '某文章', content: '摘要' } }]),
      '[链接] 某文章 · 摘要'
    )
    assert.equal(degradeContent([{ type: 'location', data: { title: '北京站' } }]), '[位置] 北京站')
    assert.equal(degradeContent([{ type: 'contact' }]), '[联系人]')
    assert.equal(degradeContent([{ type: 'gift' }]), '[礼物]')
  })

  it('渲染器 v2.14：超长内容 800 字符护栏', () => {
    const out = degradeContent([{ type: 'text', data: { text: 'x'.repeat(2000) } }])
    assert.equal(out.length, 801)
    assert.ok(out.endsWith('…'))
  })

  it('渲染器 v2.14：字符串输入同样过长度护栏', () => {
    assert.equal(degradeContent('y'.repeat(900)).length, 801)
  })

  it('渲染器 v2.17：json 红包 wcpay 识别', () => {
    assert.equal(
      degradeContent([{ type: 'json', data: { data: JSON.stringify({ prompt: '恭喜发财', wcpay: { title: 'QQ红包' } }) } }]),
      '[QQ红包]'
    )
  })

  it('渲染器 v2.17：xml 红包 wcpayinfo 识别', () => {
    assert.equal(
      degradeContent([{ type: 'xml', data: { data: '<msg><title>红包</title><wcpayinfo/></msg>' } }]),
      '[QQ红包]'
    )
  })

  it('渲染器 v2.17：forward 转发摘要取前两条文本', () => {
    const content = JSON.stringify([
      { content: { content: [{ type: 'text', data: { text: '周末组织去爬山，报名接龙' } }] } },
      { content: '群相册已更新 30 张新照片' }
    ])
    assert.equal(
      degradeContent([{ type: 'forward', data: { content } }]),
      '[转发] 周末组织去爬山，报名接龙／群相册已更新 30 张新照片'
    )
  })

  it('渲染器 v2.17：forward 无 content 回退占位', () => {
    assert.equal(degradeContent([{ type: 'forward', data: {} }]), '[合并转发]')
  })

  it('渲染器 v2.17：file 段带大小后缀', () => {
    assert.equal(
      degradeContent([{ type: 'file', data: { name: '需求文档.pdf', size: 1572864 } }]),
      '[文件] 需求文档.pdf · 1.5MB'
    )
  })

  it('渲染器 v2.17：markdown 表格压平', () => {
    const out = degradeContent([{ type: 'markdown', data: { content: '| 机型 | 状态 |\n| --- | --- |\n| 手环10 | 通过 |' } }])
    assert.equal(out.indexOf('|'), -1)
    assert.ok(out.indexOf('机型；状态') >= 0)
    assert.ok(out.indexOf('手环10；通过') >= 0)
  })
})
