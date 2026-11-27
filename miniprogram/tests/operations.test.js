const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
const { fresh, mockWx, loadPage } = require('./helpers')
let app
const slot = { id: 5, productId: 7, code: 'YJ01-01-01', quantity: 5, active: true, productActive: true,
  warehouseId: 2, unit: '斤', variety: '白花', headCount: 33, productName: '白花 · 33头' }
beforeEach(() => {
  fresh(); mockWx()
  app = { user: { id: 3, role: 'OPERATOR' }, warehouse: { id: 2 }, preferences: { weightUnit: 'JIN' },
    ready: async () => {}, operation: null }
  global.getApp = () => app
})
const event = (field, value) => ({ currentTarget: { dataset: { field } }, detail: { value } })
async function setup() {
  require('../app/utils/api').setWarehouse(2)
  wx.request = options => {
    if (options.url.includes('/stock/context')) options.success({ statusCode: 200, data: { locations: [slot] } })
    else if (options.url.includes('/auth/csrf')) options.success({ statusCode: 200, data: { token: 'csrf' } })
    else throw new Error('Unexpected request: ' + options.url)
  }
  const page = loadPage('pages/operations/index.js')
  await page.load(); page.select(slot)
  page.input(event('quantity', '1.125'))
  return page
}
test('network loss persists a request and retries exactly the same payload after page recreation', async () => {
  const page = await setup()
  const api = require('../app/utils/api')
  api.setWarehouse(2)
  const original = wx.request
  let first
  wx.request = options => {
    if (options.url.includes('/stock/in?')) { first = structuredClone(options.data); options.fail({ errMsg: 'timeout' }) }
    else original(options)
  }
  await page.submit()
  assert.equal(first.quantity, 1.125)
  assert.ok(page.data.pending)
  const saved = require('../app/utils/pending').read(3)
  assert.deepEqual(saved.body, first)
  page.input(event('quantity', '9'))
  assert.equal(page.data.quantity, '1.125')
  page.mode({ currentTarget: { dataset: { mode: 'OUT' } } })
  assert.equal(page.data.mode, 'IN')
  delete require.cache[require.resolve('../app/pages/operations/index')]
  const recreated = loadPage('pages/operations/index.js')
  await recreated.load()
  assert.deepEqual(recreated.data.pending.body, first)
  wx.request = options => {
    assert.deepEqual(options.data, first)
    options.success({ statusCode: 200, data: { documentNo: 'IN-test', changes: [{ afterQty: 6.125 }] } })
  }
  await recreated.send()
  assert.equal(recreated.data.result.amount, '6.125 斤')
  assert.equal(recreated.data.pending, null)
  assert.equal(require('../app/utils/pending').read(3), null)
  assert.equal(recreated.data.quantity, '')
})
test('pending requests stay isolated between accounts', async () => {
  const store = require('../app/utils/pending')
  store.save(3, { requestId: 'request-a' })
  assert.equal(store.read(4), null)
  assert.deepEqual(store.read(3), { requestId: 'request-a' })
})
test('switching mode clears weight and validation rejects overdraw, inactive and unbound cells', async () => {
  const page = await setup()
  page.mode({ currentTarget: { dataset: { mode: 'OUT' } } })
  assert.equal(page.data.quantity, '')
  page.input(event('quantity', '6'))
  assert.throws(() => page.validate(), /不能超过/)
  page.setData({ selected: Object.assign({}, slot, { active: false }) })
  assert.throws(() => page.validate(), /已停用/)
  page.setData({ selected: Object.assign({}, slot, { productId: null }) })
  assert.throws(() => page.validate(), /尚未绑定/)
})
test('storage failure prevents sending a new stock operation', async () => {
  const page = await setup()
  let sent = false
  wx.setStorageSync = () => { throw new Error('存储空间不足') }
  wx.request = () => { sent = true }
  await page.submit()
  assert.equal(sent, false)
  assert.match(page.data.error, /存储空间不足/)
})
test('gram input sends jin quantity and a different warehouse cannot be selected', async () => {
  const page = await setup()
  page._preference = 'GRAM'
  page.input(event('quantity', '0.5'))
  assert.equal(page.validate(), 0.001)
  assert.throws(() => page.select(Object.assign({}, slot, { warehouseId: 1 })), /暂未开放/)
})
