import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { createApi } from '../src/common/api.js'

function mockInterconnect() {
  let conn
  const instance = () => {
    if (!conn) {
      conn = {
        diagnosisArgs: null,
        sendArgs: null,
        onmessage: null,
        onopen: null,
        onclose: null,
        onerror: null,
        send(opt) { this.sendArgs = opt; opt.success && opt.success({}) },
        diagnosis(opt) { this.diagnosisArgs = opt; opt.success && opt.success({ status: 0 }) }
      }
    }
    return conn
  }
  return { instance, getConn: () => conn }
}

describe('api', () => {
  it('send 传对象 payload 作为 data', async () => {
    const m = mockInterconnect()
    const api = createApi(m)
    api.init({})
    m.getConn().onopen({})
    await api.send({ type: 'send_message', seq: 1 })
    assert.deepEqual(m.getConn().sendArgs.data, { type: 'send_message', seq: 1 })
  })

  it('connectStatus 对 status=0 返回 true', async () => {
    const m = mockInterconnect()
    const api = createApi(m)
    api.init({})
    m.getConn().onopen({})
    assert.equal(await api.connectStatus(), true)
  })

  it('onmessage 赋值式注册并分发 JSON', async () => {
    const m = mockInterconnect()
    const api = createApi(m)
    let got = null
    api.init({ onMessage: (msg) => { got = msg } })
    assert.equal(typeof m.getConn().onmessage, 'function')
    m.getConn().onmessage({ data: JSON.stringify({ type: 'push_message' }) })
    assert.deepEqual(got, { type: 'push_message' })
  })

  it('send 在未 onopen 时等待就绪后才发送（门控）', async () => {
    const m = mockInterconnect()
    const api = createApi(m)
    api.init({})
    const raw = m.getConn()
    raw.sendArgs = null
    const p = api.send({ type: 'get_conversations', seq: 1 })
    await new Promise((r) => setTimeout(r, 20))
    assert.equal(raw.sendArgs, null, 'onopen 前不应发送')
    raw.onopen({})
    await p
    assert.deepEqual(raw.sendArgs.data, { type: 'get_conversations', seq: 1 })
  })

  it('send 等待期间触发 onopen 后正常发送（同源多条等待共用就绪）', async () => {
    const m = mockInterconnect()
    const api = createApi(m)
    api.init({})
    const raw = m.getConn()
    const p1 = api.send({ type: 'get_visible_contacts', seq: 1 })
    const p2 = api.send({ type: 'get_conversations', seq: 2 })
    raw.onopen({})
    await Promise.all([p1, p2])
    assert.equal(raw.sendArgs.data.type, 'get_conversations')
  })
})