const { test } = require('node:test')
const assert = require('node:assert/strict')
const { fresh, mockWx, loadPage } = require('./helpers')

function workspace(role, today = () => '2026-11-28') {
  fresh()
  const requests = []
  mockWx(options => {
    const url = new URL(options.url)
    requests.push(url)
    let data
    if (url.pathname.endsWith('/stock/context')) data = {
      historyProducts: role === 'ADMIN' ? [{ id: 9, variety: '他人品种', headCount: 33 }] : [],
      historySlots: role === 'ADMIN' ? [{ id: 19, code: 'OTHER-01' }] : [], zone: 'Asia/Shanghai', today: today(),
      historyOperators: role === 'ADMIN' ? [{ id: 3, displayName: '其他操作员', active: true },
        { id: 4, displayName: '其他操作员', active: false }] : [{ id: 5, displayName: '本人', active: true }],
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
  assert.equal(history.data.operators[0].label, '全部操作人')
  assert.equal(history.data.form.from, '2026-11-23')
  assert.equal(history.data.form.to, '2026-11-29')
  assert.match(history.data.operators[2].label, /#4.*已停用/)
  history.operator({ detail: { value: '2' } })
  await history.apply()
  await history.load(2)
  const pages = requests.filter(url => url.pathname.endsWith('/stock/movements'))
  assert.ok(pages.every(url => url.searchParams.get('mine') === 'false'))
  assert.equal(pages.at(-1).searchParams.get('operatorId'), '4')
  assert.ok(!pages.at(-1).searchParams.has('operator'))
  assert.equal(pages.at(-1).searchParams.get('page'), '2')
  assert.ok(pages.every(url => url.searchParams.get('from') === '2026-11-23' && url.searchParams.get('to') === '2026-11-29'))
  await history.initialize()
  assert.equal(history.data.operatorIndex, 2)
  history.data.form.from = '2026-10-01'
  history.data.form.to = '2026-10-31'
  await history.reset()
  assert.equal(history.data.form.from, '2026-11-23')
  assert.equal(history.data.form.to, '2026-11-29')
  assert.equal(history.data.operatorIndex, 0)
  assert.equal(history.data.form.operatorId, '')
  assert.ok(!requests.at(-1).searchParams.has('operatorId'))
  await loadPage('pages/home/index').load()
  assert.equal(requests.filter(url => url.pathname.endsWith('/stock/movements')).at(-1).searchParams.get('mine'), 'false')
})

test('history rolls untouched defaults into the new week and preserves custom dates', async () => {
  let today = '2026-11-29'
  const requests = workspace('ADMIN', () => today)
  const history = loadPage('pages/history/index')
  await history.initialize()
  today = '2026-11-30'
  await history.initialize()
  assert.equal(history.data.form.from, '2026-11-30')
  assert.equal(history.data.form.to, '2026-12-06')
  history.data.form.from = '2026-10-01'
  history.data.form.to = '2026-10-31'
  await history.initialize()
  await history.load(2)
  assert.equal(requests.at(-1).searchParams.get('from'), '2026-10-01')
  assert.equal(requests.at(-1).searchParams.get('to'), '2026-10-31')
  history.data.form.from = history.data.form.to = ''
  await history.apply()
  assert.equal(requests.at(-1).searchParams.get('from'), '2026-11-30')
  assert.equal(requests.at(-1).searchParams.get('to'), '2026-12-06')
})

test('operator history and dashboard remain personal and discard an old administrator filter', async () => {
  const requests = workspace('OPERATOR')
  const history = loadPage('pages/history/index')
  history.data.form.operatorId = 4
  await history.initialize()
  assert.equal(history.data.admin, false)
  assert.equal(history.data.form.operatorId, '')
  assert.equal(history.data.operators.length, 1)
  await loadPage('pages/home/index').load()
  const pages = requests.filter(url => url.pathname.endsWith('/stock/movements'))
  assert.ok(pages.every(url => url.searchParams.get('mine') === 'true'))
  assert.ok(pages.every(url => !url.searchParams.has('operatorId')))
  assert.ok(!requests.some(url => url.pathname.startsWith('/api/reports/')))
})
