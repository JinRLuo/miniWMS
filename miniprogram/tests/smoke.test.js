const { test } = require('node:test')
const assert = require('node:assert/strict')
const { spawn } = require('node:child_process')
const { existsSync } = require('node:fs')
const path = require('node:path')
const { setTimeout: delay } = require('node:timers/promises')
const { fresh, mockWx, instance, loadPage } = require('./helpers')

test('native client against an isolated Spring backend: session, units, retry, QR, role scope and password',
  { skip: process.env.WMS_MINIPROGRAM_SMOKE !== '1', timeout: 60000 }, async t => {
    const root = path.resolve(__dirname, '../..')
    const jar = path.join(root, 'backend/target/miniwms-1.1.0.jar')
    assert.ok(existsSync(jar), 'First run: mvn -f backend/pom.xml package -DskipTests')
    const server = spawn('java', ['-jar', jar, '--server.port=0', '--server.address=127.0.0.1',
      '--spring.profiles.active=default', '--spring.datasource.url=jdbc:h2:mem:miniprogram_smoke;MODE=MySQL;DATABASE_TO_LOWER=TRUE',
      '--spring.datasource.username=sa', '--spring.datasource.password=', '--wms.demo=true', '--wms.admin-password='],
    { cwd: root, stdio: ['ignore', 'pipe', 'pipe'] })
    t.after(() => server.kill('SIGTERM'))
    let logs = ''
    server.stdout.on('data', data => { logs += data.toString() })
    server.stderr.on('data', data => { logs += data.toString() })
    let port
    for (let i = 0; i < 200; i++) {
      const match = logs.match(/Tomcat started on port (\d+)/)
      if (match) { port = Number(match[1]); break }
      if (server.exitCode !== null) assert.fail('Demo backend failed: ' + logs.slice(-3000))
      await delay(100)
    }
    assert.ok(port, 'Backend did not start: ' + logs.slice(-3000))
    fresh()
    const requests = []
    let loseNextWrite = false
    const storage = mockWx(async options => {
      try {
        const url = new URL(options.url)
        requests.push(url.pathname)
        const headers = options.header
        const body = options.method === 'GET' || options.data === undefined ? undefined :
          headers['Content-Type'] === 'application/x-www-form-urlencoded' ? new URLSearchParams(options.data).toString() : JSON.stringify(options.data)
        const response = await fetch('http://127.0.0.1:' + port + url.pathname + url.search, { method: options.method, headers, body })
        const raw = await response.text()
        if (loseNextWrite && url.pathname === '/api/stock/in') {
          loseNextWrite = false; options.fail({ errMsg: 'request:fail timeout after commit' }); return
        }
        options.success({ statusCode: response.status, data: raw ? JSON.parse(raw) : '', cookies: response.headers.getSetCookie(), header: {} })
      } catch (error) { options.fail(error) }
    })
    let redirects = 0
    wx.reLaunch = () => { redirects++ }
    global.getCurrentPages = () => [{ route: 'pages/home/index' }]
    let definition
    global.App = value => { definition = value }
    require('../app/app')
    const app = instance(definition)
    global.getApp = () => app
    app.onLaunch()
    // Only this HTTP regression fixture uses password accounts; the shipped mini program uses WeChat.
    const { api, clearSession } = require('../app/utils/api')
    async function fixtureLogin(username, password) {
      clearSession()
      await api('/auth/login', { username, password }, 'POST', true)
      // Seed only business-page fixtures; production App.ready rejects password sessions.
      app.user = await api('/auth/me')
      await app.loadSettings()
      app._ready = Promise.resolve(app.user)
    }
    // The HTTP listener is ready slightly before Bootstrap finishes seeding the demo admin.
    for (let i = 0; i < 40; i++) {
      try { await fixtureLogin('admin', 'miniwms-demo-2026'); break }
      catch (error) { if (error.status !== 401 || i === 39) throw error; await delay(100) }
    }
    assert.equal(app.user.role, 'ADMIN')
    assert.equal(app.warehouse.type, 'FISH_MAW')
    const home = loadPage('pages/home/index')
    await home.load()
    assert.equal(home.data.admin, true)
    assert.equal(home.data.metrics.length, 4)
    assert.equal(home.data.error, '')
    const inventory = loadPage('pages/inventory/index')
    await inventory.load()
    assert.ok(inventory.data.shown.length > 0)
    const settings = loadPage('pages/settings/index')
    await settings.onShow()
    settings.unit({ currentTarget: { dataset: { unit: 'GRAM' } } })
    await settings.save()
    assert.equal(app.preferences.weightUnit, 'GRAM')
    assert.equal((await api('/auth/preferences')).weightUnit, 'GRAM')
    const operation = loadPage('pages/operations/index')
    await operation.load()
    const slot = operation.data.slots.find(row => row.quantity > 0)
    assert.ok(slot)
    operation.select(slot)
    operation.input({ currentTarget: { dataset: { field: 'quantity' } }, detail: { value: '0.5' } })
    loseNextWrite = true
    await operation.submit()
    assert.ok(operation.data.pending)
    assert.equal(operation.data.pending.body.quantity, 0.001)
    delete require.cache[require.resolve('../app/pages/operations/index')]
    const retry = loadPage('pages/operations/index')
    await retry.load()
    await Promise.all([retry.send(), retry.send()])
    assert.equal(retry.data.pending, null)
    assert.equal(retry.data.error, '')
    const after = await api('/slots/by-code/' + slot.code)
    assert.equal(after.quantity, Number((slot.quantity + 0.001).toFixed(3)))
    assert.equal(retry.data.quantity, '')
    const qr = await fetch('http://127.0.0.1:' + port + '/api/slots/' + slot.id + '/qrcode', {
      headers: { Cookie: [...storage.values()][0].JSESSIONID ? 'JSESSIONID=' + [...storage.values()][0].JSESSIONID : '' },
    })
    assert.equal(qr.status, 200)
    const history = loadPage('pages/history/index')
    await history.initialize()
    assert.ok(history.data.rows.length > 0)
    await api('/admin/users', { username: 'mp-smoke-operator', displayName: '小程序验收操作员', role: 'OPERATOR', active: true, password: 'operator-demo-2026' })
    await api('/auth/logout', undefined, 'POST')
    await fixtureLogin('mp-smoke-operator', 'operator-demo-2026')
    const start = requests.length
    await home.load()
    assert.equal(home.data.admin, false)
    assert.equal(home.data.recent.length, 0)
    assert.ok(!requests.slice(start).includes('/api/reports/summary'))
    const beforeInventory = requests.length
    await inventory.load()
    assert.equal(requests.length, beforeInventory, 'Operator inventory guard must not request admin data')
    await assert.rejects(api('/products'), error => error.status === 403)
    operation._owner = app.user.id
    await operation.load()
    operation.select(slot)
    operation.input({ currentTarget: { dataset: { field: 'quantity' } }, detail: { value: '0.001' } })
    await operation.submit()
    assert.equal(operation.data.error, '')
    await history.initialize()
    assert.equal(history.data.rows.length, 1)
    assert.equal(history.data.rows[0].operator, '小程序验收操作员')
    await api('/auth/password', { currentPassword: 'operator-demo-2026', newPassword: 'operator-updated-2026', confirmPassword: 'operator-updated-2026' }, 'PUT')
    await assert.rejects(api('/auth/me'), error => error.status === 401)
    assert.ok(redirects > 0)
    await fixtureLogin('mp-smoke-operator', 'operator-updated-2026')
    assert.equal(app.user.role, 'OPERATOR')
    await api('/auth/logout', undefined, 'POST')
  })
