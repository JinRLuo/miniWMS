const { api } = require('../../utils/api')
const { guard } = require('../../utils/page')
const { weight, unitLabel, slotView } = require('../../utils/model')
Page({
  data: { loading: true, error: '', tab: 'slot', query: '', shelf: '', shelfIndex: 0, shelves: [{ id: '', name: '全部货架' }], lowOnly: false, shown: [], count: 0, hasMore: false },
  onShow() { this.load() },
  async onPullDownRefresh() { try { await this.load() } finally { wx.stopPullDownRefresh() } },
  async load() {
    const version = this._version = (this._version || 0) + 1
    this.setData({ loading: true, error: '' })
    const app = await guard(this, 'inventory', true)
    if (!app) return
    try {
      const [products, slots, shelves] = await Promise.all([api('/products'), api('/slots'), api('/shelves')])
      if (version !== this._version) return
      const preference = app.preferences.weightUnit
      this._products = products.map(product => Object.assign({}, product, {
        amount: weight(product.quantity, preference) + ' ' + unitLabel(preference),
        warning: product.warningLevel === null ? '未设置' : weight(product.warningLevel, preference) + ' ' + unitLabel(preference),
        low: product.active && product.warningLevel !== null && product.quantity < product.warningLevel,
        estimate: Math.round(product.quantity * (product.headCount || 0) + 1e-8),
      }))
      this._slots = slots.map(slot => slotView(slot, preference))
      const choices = [{ id: '', name: '全部货架' }].concat(shelves)
      const index = Math.max(0, choices.findIndex(shelf => shelf.id === this.data.shelf))
      this.setData({ shelves: choices, shelfIndex: index, shelf: choices[index].id })
      this.filter()
    } catch (error) { if (version === this._version) this.setData({ error: error.message, shown: [] }) }
    finally { if (version === this._version) this.setData({ loading: false }) }
  },
  tab(event) { this.setData({ tab: event.currentTarget.dataset.tab }); this.filter() },
  search(event) { this.setData({ query: event.detail.value }); this.filter() },
  low(event) { this.setData({ lowOnly: event.detail.value }); this.filter() },
  shelf(event) { const index = Number(event.detail.value); this.setData({ shelf: this.data.shelves[index].id, shelfIndex: index }); this.filter() },
  filter() {
    const { tab, query, shelf, lowOnly } = this.data
    const text = query.trim().toLowerCase()
    const rows = (tab === 'slot' ? this._slots || [] : this._products || []).filter(row =>
      (tab !== 'slot' || !shelf || row.shelfId === shelf) &&
      (tab !== 'product' || !lowOnly || row.low) &&
      (!text || [row.name, row.productName, row.sku, row.code, row.shelfCode, row.variety, row.headCount]
        .some(value => String(value || '').toLowerCase().includes(text))))
    this._filtered = rows
    this.setData({ count: rows.length, shown: rows.slice(0, 40), hasMore: rows.length > 40 })
  },
  onReachBottom() { this.more() },
  more() {
    const limit = this.data.shown.length + 40
    this.setData({ shown: (this._filtered || []).slice(0, limit), hasMore: (this._filtered || []).length > limit })
  },
  operation(event) { getApp().openOperation({ slotId: event.currentTarget.dataset.id, mode: event.currentTarget.dataset.mode }) },
  history(event) {
    getApp().historySlot = event.currentTarget.dataset.id
    wx.switchTab({ url: '/pages/history/index' })
  },
})
