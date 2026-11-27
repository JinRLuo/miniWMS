const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
const { fresh, mockWx, instance, loadPage } = require('./helpers')
let app, user, calls, routes, storage
beforeEach(() => {
  fresh()
  calls = []; routes = []
  user = { id: 5, displayName: '微信用户1111', role: 'OPERATOR', approved: false, active: true, phone: '13800001111', wechatBound: true }
  storage = mockWx(options => {
    const path = new URL(options.url).pathname
    calls.push({ path, body: options.data })
    let data
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

test('phone authorization denial never starts authentication', async () => {
  const page = loadPage('pages/login/index')
  await page.login({ detail: { errMsg: 'getPhoneNumber:fail user deny' } })
  assert.match(page.data.error, /授权微信手机号/)
  assert.equal(calls.length, 0)
  await assert.rejects(app.login(''), /请先授权微信手机号/)
})
test('login sends two independent codes and leaves a new user without any business requests', async () => {
  const page = loadPage('pages/login/index')
  await page.login({ detail: { code: 'phone-authorization-code' } })
  assert.equal(page.data.error, '')
  const request = calls.find(call => call.path === '/api/auth/wechat/login')
  assert.deepEqual(request.body, { loginCode: 'wx-login-code', phoneCode: 'phone-authorization-code' })
  assert.ok(!calls.some(call => call.path === '/api/auth/login'))
  assert.ok(!calls.some(call => call.path === '/api/warehouses'))
  assert.equal(app.user.approved, false)
  assert.equal(routes.at(-1), '/pages/pending/index')
  assert.ok(!JSON.stringify([...storage.values()]).includes(user.phone), 'Phone must not be persisted in client storage')
})
test('pending users cannot load pages even though their stored role is OPERATOR', async () => {
  app.operation = { token: 'keep-this-qr-for-after-approval' }
  const page = instance({ data: { loading: true } })
  const guarded = await require('../app/utils/page').guard(page, 'operations')
  assert.equal(guarded, null)
  assert.equal(routes.at(-1), '/pages/pending/index')
  assert.deepEqual(calls.map(call => call.path), ['/api/auth/me'])
  assert.ok(app.operation)
})
test('refreshing approval obtains current permissions and resumes the original scan', async () => {
  await app.login('phone-code')
  app.operation = { token: 'original-scan' }
  const page = loadPage('pages/pending/index')
  await page.refresh()
  assert.equal(page.data.phone, '138****1111')
  assert.equal(app.warehouse, null)
  user.approved = true
  await page.refresh()
  assert.equal(app.user.approved, true)
  assert.equal(app.warehouse.id, 2)
  assert.equal(routes.at(-1), '/pages/operations/index')
})
test('revocation sends an authenticated user back to approval without losing their session', async () => {
  user.approved = true
  await app.login('phone-code')
  wx.request = options => options.success({ statusCode: 403, data: { code: 'ACCOUNT_PENDING', message: '待授权' } })
  await assert.rejects(require('../app/utils/api').api('/stock/context'), error => error.code === 'ACCOUNT_PENDING')
  assert.equal(app.user.approved, false)
  assert.equal(app.warehouse, null)
  assert.equal(routes.at(-1), '/pages/pending/index')
  assert.ok([...storage.values()].some(value => value.JSESSIONID === 'test-session'))
})

test('a legacy password session cannot open the mini program workspace', async () => {
  user.wechatBound = false; user.approved = true
  global.getCurrentPages = () => [{ route: 'pages/home/index' }]
  await assert.rejects(app.ready(), /请使用微信手机号登录/)
  assert.equal(app.user, null)
  assert.equal(routes.at(-1), '/pages/login/index')
  assert.ok(!calls.some(call => call.path === '/api/warehouses'))
})
