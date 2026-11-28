const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
const { fresh, mockWx, instance, loadPage } = require('./helpers')
let app, user, calls, routes, storage, authenticated
beforeEach(() => {
  fresh()
  calls = []; routes = []; authenticated = false
  user = { id: 5, displayName: '微信用户5', role: 'OPERATOR', approved: false, active: true, wechatBound: true }
  storage = mockWx(options => {
    const path = new URL(options.url).pathname
    calls.push({ path, body: options.data })
    let data
    if (path.endsWith('/auth/me') && !authenticated) { options.success({ statusCode: 401, data: { message: '请先登录' } }); return }
    if (path.endsWith('/auth/wechat/login')) authenticated = true
    if (path.endsWith('/csrf')) data = { token: 'csrf' }
    else if (path.endsWith('/auth/wechat/login') || path.endsWith('/auth/me')) data = { ...user }
    else if (path.endsWith('/warehouses')) data = [{ id: 2, type: 'FISH_MAW', name: '鱼胶仓库' }]
    else if (path.endsWith('/preferences')) data = { weightUnit: 'JIN' }
    else assert.fail('Unexpected business request: ' + path)
    options.success({ statusCode: 200, data, cookies: ['JSESSIONID=test-session; HttpOnly'] })
  })
  wx.login = options => options.success({ code: 'wx-login-code' })
  wx.reLaunch = options => routes.push(options.url)
  global.getCurrentPages = () => [{ route: 'pages/login/index' }]
  let definition
  global.App = value => { definition = value }
  require('../app/app')
  app = instance(definition)
  global.getApp = () => app
  app.onLaunch()
})

test('opening the mini program authenticates automatically and leaves a new user pending', async () => {
  wx.getPhoneNumber = () => assert.fail('Phone authorization must never be requested')
  wx.getPrivacySetting = () => assert.fail('Phone privacy authorization must not block login')
  app.onShow({})
  const page = loadPage('pages/login/index')
  await page.onShow()
  assert.equal(page.data.error, '')
  const request = calls.find(call => call.path === '/api/auth/wechat/login')
  assert.deepEqual(request.body, { loginCode: 'wx-login-code' })
  assert.ok(!calls.some(call => call.path === '/api/auth/login'))
  assert.ok(!calls.some(call => !call.path.startsWith('/api/auth/')))
  assert.equal(app.user.approved, false)
  assert.equal(routes.at(-1), '/pages/pending/index')
  assert.ok(!JSON.stringify([...storage.values()]).includes('wx-login-code'))
})
test('a valid saved WeChat session opens the workspace without another native login', async () => {
  authenticated = true; user.approved = true
  wx.login = () => assert.fail('A valid session should be reused')
  const page = loadPage('pages/login/index')
  await page.onShow()
  assert.equal(app.warehouse.id, 2)
  assert.equal(routes.at(-1), '/pages/home/index')
  assert.ok(!calls.some(call => call.path.endsWith('/wechat/login')))
})
test('missing or failed native credentials stop automatically and allow an explicit reconnect', async () => {
  const page = loadPage('pages/login/index')
  wx.login = options => options.success({})
  await page.onShow()
  assert.match(page.data.error, /未获得微信登录凭证/)
  wx.login = options => options.fail({ errMsg: 'login:fail' })
  await page.connect()
  assert.match(page.data.error, /微信登录失败/)
  assert.equal(page.data.busy, false)
  assert.ok(calls.every(call => call.path === '/api/auth/me'))
  wx.login = options => options.success({ code: 'fresh-login-code' })
  await page.connect()
  assert.equal(page.data.error, '')
  assert.equal(routes.at(-1), '/pages/pending/index')
})
test('opening overlapping pages shares one automatic authentication request', async () => {
  let finish
  let nativeCalls = 0
  wx.login = options => { nativeCalls++; finish = options.success }
  const page = loadPage('pages/login/index')
  const first = page.onShow()
  const second = app.ready()
  await new Promise(resolve => setImmediate(resolve))
  await page.onShow()
  assert.equal(nativeCalls, 1)
  finish({ code: 'wx-login-code' })
  await Promise.all([first, second])
  assert.equal(calls.filter(call => call.path === '/api/auth/wechat/login').length, 1)
})
test('network failure does not start native login or loop between pages', async () => {
  const page = loadPage('pages/login/index')
  wx.request = options => options.fail({ errMsg: 'request:fail offline' })
  wx.login = () => assert.fail('Network failure is not an expired session')
  await page.onShow()
  assert.match(page.data.error, /无法连接服务器/)
  assert.equal(page.data.busy, false)
  assert.deepEqual(routes, [])
})
test('a disabled account stops at a clear error instead of repeatedly logging in', async () => {
  const original = wx.request
  let attempts = 0
  wx.request = options => {
    if (options.url.endsWith('/auth/wechat/login')) {
      attempts++; options.success({ statusCode: 403, data: { message: '账号已停用，请联系管理员' } }); return
    }
    original(options)
  }
  const page = loadPage('pages/login/index')
  await page.onShow()
  assert.match(page.data.error, /已停用/)
  assert.equal(attempts, 1)
  assert.equal(app.user, null)
  assert.deepEqual(routes, [])
})
test('pending users cannot load business pages even though their stored role is OPERATOR', async () => {
  app.operation = { token: 'keep-this-qr-for-after-approval' }
  const page = instance({ data: { loading: true } })
  const guarded = await require('../app/utils/page').guard(page, 'operations')
  assert.equal(guarded, null)
  assert.equal(routes.at(-1), '/pages/pending/index')
  assert.ok(calls.every(call => call.path.startsWith('/api/auth/')))
  assert.ok(app.operation)
})
test('refreshing approval resumes the original QR entry after automatic login', async () => {
  const token = '11111111-1111-4111-8111-111111111111'
  app.onShow({ query: { token } })
  await app.ready()
  const page = loadPage('pages/pending/index')
  await page.refresh()
  assert.equal(page.data.accountId, 5)
  assert.equal(app.warehouse, null)
  assert.equal(app.operation.token, token)
  user.approved = true
  await page.refresh()
  assert.equal(app.user.approved, true)
  assert.equal(app.warehouse.id, 2)
  assert.equal(routes.at(-1), '/pages/operations/index')
})
test('revocation sends a user back to approval while retaining their WeChat session', async () => {
  user.approved = true
  await app.ready()
  wx.request = options => options.success({ statusCode: 403, data: { code: 'ACCOUNT_PENDING', message: '待授权' } })
  await assert.rejects(require('../app/utils/api').api('/stock/context'), error => error.code === 'ACCOUNT_PENDING')
  assert.equal(app.user.approved, false)
  assert.equal(app.warehouse, null)
  assert.equal(routes.at(-1), '/pages/pending/index')
  assert.ok([...storage.values()].some(value => value.JSESSIONID === 'test-session'))
})
test('foregrounding refreshes permission state instead of reusing an old grant', async () => {
  user.approved = true; authenticated = true
  await app.ready()
  user.approved = false
  app.onShow({})
  const page = instance({ data: { loading: true } })
  assert.equal(await require('../app/utils/page').guard(page, 'home'), null)
  assert.equal(routes.at(-1), '/pages/pending/index')
  assert.equal(app.warehouse, null)
})
test('an expired write session reconnects automatically without replaying the write', async () => {
  user.approved = true
  await app.ready()
  global.getCurrentPages = () => [{ route: 'pages/home/index' }]
  const original = wx.request
  let writes = 0
  wx.request = options => {
    if (options.method === 'PUT') {
      writes++; authenticated = false
      options.success({ statusCode: 401, data: { message: '请先登录' } }); return
    }
    original(options)
  }
  await assert.rejects(require('../app/utils/api').api('/auth/preferences', { weightUnit: 'GRAM' }, 'PUT'), error => error.status === 401)
  assert.equal(routes.at(-1), '/pages/login/index')
  await loadPage('pages/login/index').onShow()
  assert.equal(routes.at(-1), '/pages/home/index')
  assert.equal(writes, 1)
  assert.equal(calls.filter(call => call.path === '/api/auth/wechat/login').length, 2)
})
test('a legacy browser session is replaced with the actual WeChat identity before reading warehouse data', async () => {
  authenticated = true
  const original = wx.request
  let first = true
  wx.request = options => {
    if (first && options.url.endsWith('/auth/me')) {
      first = false
      options.success({ statusCode: 200, data: { id: 99, role: 'ADMIN', approved: true, wechatBound: false } }); return
    }
    original(options)
  }
  await loadPage('pages/login/index').onShow()
  assert.equal(app.user.id, 5)
  assert.equal(app.user.approved, false)
  assert.equal(routes.at(-1), '/pages/pending/index')
  assert.ok(!calls.some(call => call.path === '/api/warehouses'))
})
