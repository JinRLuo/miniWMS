const { test } = require('node:test')
const assert = require('node:assert/strict')
const { fresh, mockWx, loadPage } = require('./helpers')

function workspace(role) {
  fresh()
  const requests = []
  mockWx(options => {
    const url = new URL(options.url)
    requests.push(url)
    let data
    if (url.pathname.endsWith('/stock/context')) data = {
      historyProducts: role === 'ADMIN' ? [{ id: 9, variety: '他人品种', headCount: 33 }] : [],
      historySlots: role === 'ADMIN' ? [{ id: 19, code: 'OTHER-01' }] : [], zone: 'Asia/Shanghai', today: '2026-11-28',
    }
    else if (url.pathname.endsWith('/stock/movements')) data = { rows: [], more: true }
    else if (url.pathname.endsWith('/reports/summary')) data = { units: [], products: 0, lowStock: 0 }
    else if (url.pathname.endsWith('/reports/trend')) data = []
    else assert.fail('Unexpected request: ' + url.pathname)
    options.success({ statusCode: 200, data })
  })
  require('../app/utils/api').setWarehouse(2)
  global.getApp = () => ({ user: { role, approved: true }, ready: async () => {}, preferences: { weightUnit: 'JIN' },
    warehouse: { id: 2, name: '鱼胶仓库' } })
  return requests
}

test('administrator history and dashboard default to all operators, including filters and pagination', async () => {
  const requests = workspace('ADMIN')
  const history = loadPage('pages/history/index')
  await history.initialize()
  assert.equal(history.data.admin, true)
  assert.ok(history.data.varieties.includes('他人品种'))
  assert.ok(history.data.slots.some(slot => slot.code === 'OTHER-01'))
  history.input({ currentTarget: { dataset: { field: 'operator' } }, detail: { value: '其他操作员' } })
  await history.apply()
  await history.load(2)
  const pages = requests.filter(url => url.pathname.endsWith('/stock/movements'))
  assert.ok(pages.every(url => url.searchParams.get('mine') === 'false'))
  assert.equal(pages.at(-1).searchParams.get('operator'), '其他操作员')
  assert.equal(pages.at(-1).searchParams.get('page'), '2')
  await loadPage('pages/home/index').load()
  assert.equal(requests.filter(url => url.pathname.endsWith('/stock/movements')).at(-1).searchParams.get('mine'), 'false')
})

test('operator history and dashboard remain personal and discard an old administrator filter', async () => {
  const requests = workspace('OPERATOR')
  const history = loadPage('pages/history/index')
  history.data.form.operator = '其他操作员'
  await history.initialize()
  assert.equal(history.data.admin, false)
  assert.equal(history.data.form.operator, '')
  await loadPage('pages/home/index').load()
  const pages = requests.filter(url => url.pathname.endsWith('/stock/movements'))
  assert.ok(pages.every(url => url.searchParams.get('mine') === 'true'))
  assert.ok(pages.every(url => !url.searchParams.has('operator')))
  assert.ok(!requests.some(url => url.pathname.startsWith('/api/reports/')))
})
