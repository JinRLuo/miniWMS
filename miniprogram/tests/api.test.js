const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
const { fresh, mockWx } = require('./helpers')
beforeEach(fresh)
const ok = (options, data, cookies = [], statusCode = 200) => options.success({ statusCode, data, cookies, header: {} })

test('login carries CSRF cookie and form data, then accepts rotated session and token', async () => {
  const requests = []
  mockWx(options => {
    requests.push(options)
    if (options.url.endsWith('/csrf')) ok(options, { token: requests.length === 1 ? 'before' : 'after' }, ['XSRF-TOKEN=t; Path=/'])
    else if (options.url.endsWith('/login')) ok(options, '', ['JSESSIONID=new-session; HttpOnly', 'XSRF-TOKEN=; Max-Age=0; Path=/'], 204)
    else ok(options, { weightUnit: 'GRAM' })
  })
  const { api } = require('../app/utils/api')
  await api('/auth/login', { username: 'operator', password: 'demo' }, 'POST', true)
  assert.equal(requests[1].header['X-XSRF-TOKEN'], 'before')
  assert.equal(requests[1].header.Cookie, 'XSRF-TOKEN=t')
  assert.equal(requests[1].header['Content-Type'], 'application/x-www-form-urlencoded')
  await api('/auth/preferences', { weightUnit: 'GRAM' }, 'PUT')
  assert.equal(requests[2].header.Cookie, 'JSESSIONID=new-session')
  assert.equal(requests[3].header['X-XSRF-TOKEN'], 'after')
})
test('warehouse comes from server metadata and query values are encoded', async () => {
  let request
  mockWx(options => { request = options; ok(options, []) })
  const { api, setWarehouse, query } = require('../app/utils/api')
  await assert.rejects(api('/slots'), /仓库尚未加载/)
  setWarehouse(27)
  await api('/stock/movements' + query({ mine: true, variety: '白花 & 黄花', headCount: '', page: 2 }))
  assert.match(request.url, /mine=true&variety=%E7%99%BD%E8%8A%B1%20%26%20%E9%BB%84%E8%8A%B1&page=2&warehouseId=27$/)
})
test('concurrent writes share one CSRF request', async () => {
  let csrfCalls = 0
  mockWx(options => {
    if (options.url.endsWith('/csrf')) { csrfCalls++; setImmediate(() => ok(options, { token: 't' })) }
    else ok(options, {})
  })
  const { api } = require('../app/utils/api')
  await Promise.all([api('/auth/preferences', {}, 'PUT'), api('/auth/preferences', {}, 'PUT')])
  assert.equal(csrfCalls, 1)
})
test('header cookie fallback handles Expires commas and does not echo attributes', async () => {
  let last
  mockWx(options => {
    last = options
    options.success({ statusCode: 200, data: {}, header: { 'Set-Cookie': 'JSESSIONID=s; Expires=Sun, 29 Nov 2026 00:00:00 GMT; HttpOnly, XSRF-TOKEN=t; Path=/' } })
  })
  const { api } = require('../app/utils/api')
  await api('/auth/me'); await api('/auth/me')
  assert.equal(last.header.Cookie, 'JSESSIONID=s; XSRF-TOKEN=t')
})
test('401 clears authentication; late responses cannot resurrect old cookies', async () => {
  const queue = []
  const storage = mockWx(options => queue.push(options))
  const { api, onExpired } = require('../app/utils/api')
  let expired = 0
  onExpired(() => expired++)
  const first = api('/auth/me')
  const second = api('/auth/preferences')
  ok(queue[0], { message: '请先登录' }, [], 401)
  await assert.rejects(first, error => error.status === 401)
  ok(queue[1], {}, ['JSESSIONID=old; HttpOnly'])
  await assert.rejects(second, error => error.status === 401)
  assert.equal(expired, 1)
  assert.equal(storage.size, 0)
})
test('403 invalidates CSRF for the next explicit write; network errors stay errors', async () => {
  let csrfCalls = 0
  let writes = 0
  mockWx(options => {
    if (options.url.endsWith('/csrf')) { csrfCalls++; ok(options, { token: 't' }) }
    else { writes++; ok(options, { message: '权限不足' }, [], 403) }
  })
  const { api } = require('../app/utils/api')
  await assert.rejects(api('/auth/preferences', {}, 'PUT'), error => error.status === 403)
  await assert.rejects(api('/auth/preferences', {}, 'PUT'), error => error.status === 403)
  assert.equal(csrfCalls, 2); assert.equal(writes, 2)
  wx.request = options => options.fail({ errMsg: 'timeout' })
  await assert.rejects(api('/auth/me'), error => error.status === 0)
})

test('a write waiting for CSRF cannot use a different login session', async () => {
  let pendingCsrf
  let writes = 0
  mockWx(options => {
    if (options.url.endsWith('/csrf')) pendingCsrf = options
    else { writes++; ok(options, {}) }
  })
  const { api, clearSession } = require('../app/utils/api')
  const write = api('/auth/preferences', {}, 'PUT')
  ok(pendingCsrf, { token: 'old' })
  clearSession()
  await assert.rejects(write, error => error.status === 401)
  assert.equal(writes, 0)
})

test('an old backend rejecting the WeChat route reports a service upgrade instead of asking to log in again', async () => {
  mockWx(options => {
    if (options.url.endsWith('/csrf')) ok(options, { token: 'csrf' })
    else ok(options, { message: '请先登录' }, [], 401)
  })
  const { api, onExpired } = require('../app/utils/api')
  let expired = 0
  onExpired(() => expired++)
  await assert.rejects(api('/auth/wechat/login', { loginCode: 'login-code', phoneCode: 'phone-code' }),
    error => error.status === 401 && error.message.includes('更新后端服务'))
  assert.equal(expired, 0)
})
