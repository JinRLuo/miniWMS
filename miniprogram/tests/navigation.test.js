const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { fresh, mockWx, instance, loadPage } = require('./helpers')

beforeEach(() => { fresh(); mockWx() })

test('admin/operator tabs keep permissions, ignore hidden destinations and use local icons', () => {
  let definition
  global.Component = value => { definition = value }
  require('../app/custom-tab-bar/index')
  const bar = instance(Object.assign({}, definition.methods, { data: definition.data }))
  const destinations = []
  wx.switchTab = options => destinations.push(options.url)
  bar.update('home', 'ADMIN')
  assert.deepEqual(bar.data.tabs.map(tab => tab.key), ['home', 'operations', 'inventory', 'history'])
  for (const tab of bar.data.tabs) {
    for (const tone of ['active', 'muted']) {
      assert.ok(fs.existsSync(path.join(__dirname, '../app/assets/icons', tab.icon + '-' + tone + '.png')))
    }
  }
  bar.update('home', 'OPERATOR')
  bar.switchTab({ currentTarget: { dataset: { key: 'inventory' } } })
  bar.switchTab({ currentTarget: { dataset: { key: 'settings' } } })
  assert.deepEqual(destinations, [])
  bar.switchTab({ currentTarget: { dataset: { key: 'history' } } })
  assert.deepEqual(destinations, ['/pages/history/index'])
})

test('a failed data load still selects the current navigation tab', async () => {
  const updates = []
  global.getApp = () => ({ user: null, ready: async () => { throw new Error('offline') } })
  const page = instance({ data: { loading: true }, getTabBar: () => ({ update: (...args) => updates.push(args) }) })
  const app = await require('../app/utils/page').guard(page, 'history')
  assert.equal(app, null)
  assert.deepEqual(updates, [['history', null]])
  assert.equal(page.data.error, 'offline')
})

test('settings opens as a regular page and never changes a tab selection', async () => {
  const config = require('../app/app.json')
  assert.ok(config.pages.includes('pages/settings/index'))
  assert.ok(!config.tabBar.list.some(tab => tab.pagePath === 'pages/settings/index'))
  const destinations = []
  wx.navigateTo = options => destinations.push(options.url)
  loadPage('pages/home/index').settings()
  assert.deepEqual(destinations, ['/pages/settings/index'])
  global.getApp = () => ({ user: { role: 'ADMIN' }, preferences: { weightUnit: 'GRAM' }, ready: async () => {} })
  const page = loadPage('pages/settings/index')
  page.getTabBar = () => assert.fail('A secondary page must not access the tab bar')
  await page.onShow()
  assert.equal(page.data.unit, 'GRAM')
})
