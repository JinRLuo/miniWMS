const { api, query } = require('../../utils/api')
const { guard } = require('../../utils/page')
const { movement } = require('../../utils/model')
const empty = () => ({ from: '', to: '', variety: '', headCount: '', slotId: '', type: '', document: '' })
Page({
  data: { loading: true, error: '', rows: [], form: empty(), varieties: ['全部品种'], heads: ['全部头数'],
    slots: [{ id: '', code: '全部格子' }], types: [{ value: '', label: '全部操作' }, { value: 'IN', label: '入库' }, { value: 'OUT', label: '出库' }],
    typeIndex: 0, varietyIndex: 0, headIndex: 0, slotIndex: 0, page: 1, more: false, expanded: false },
  onShow() { this.initialize() },
  async onPullDownRefresh() { try { await this.initialize() } finally { wx.stopPullDownRefresh() } },
  async initialize() {
    this.setData({ loading: true, error: '' })
    const app = await guard(this, 'history')
    if (!app) return
    try {
      const context = await api('/stock/context')
      this._products = context.historyProducts
      this._zone = context.zone
      const varieties = ['全部品种'].concat([...new Set(this._products.map(product => product.variety).filter(Boolean))].sort())
      const slots = [{ id: '', code: '全部格子' }].concat(context.historySlots)
      const initial = app.historySlot
      app.historySlot = null
      const form = Object.assign({}, this.data.form)
      if (initial) form.slotId = initial
      // Keep a selected inventory cell visible even if it has no personal history yet.
      if (form.slotId && !slots.some(slot => slot.id === form.slotId)) {
        slots.push({ id: form.slotId, code: '所选格子（暂无本人记录）' })
      }
      const heads = ['全部头数'].concat([...new Set(this._products.filter(product => product.variety === form.variety)
        .map(product => product.headCount).filter(value => value !== null))].sort((a, b) => a - b))
      this.setData({ varieties, slots, form, heads,
        varietyIndex: Math.max(0, varieties.indexOf(form.variety)), headIndex: Math.max(0, heads.indexOf(form.headCount)),
        slotIndex: Math.max(0, slots.findIndex(slot => slot.id === form.slotId)) })
      this._preference = app.preferences.weightUnit
      await this.apply()
    } catch (error) { this.setData({ error: error.message, loading: false }) }
  },
  toggle() { this.setData({ expanded: !this.data.expanded }) },
  input(event) { this.setData({ ['form.' + event.currentTarget.dataset.field]: event.detail.value }) },
  variety(event) {
    const index = Number(event.detail.value)
    const variety = index ? this.data.varieties[index] : ''
    const heads = ['全部头数'].concat([...new Set((this._products || []).filter(product => product.variety === variety)
      .map(product => product.headCount).filter(value => value !== null))].sort((a, b) => a - b))
    this.setData({ 'form.variety': variety, 'form.headCount': '', varietyIndex: index, heads, headIndex: 0 })
  },
  head(event) { const index = Number(event.detail.value); this.setData({ 'form.headCount': index ? this.data.heads[index] : '', headIndex: index }) },
  slot(event) { const index = Number(event.detail.value); this.setData({ 'form.slotId': this.data.slots[index].id, slotIndex: index }) },
  type(event) { const index = Number(event.detail.value); this.setData({ 'form.type': this.data.types[index].value, typeIndex: index }) },
  async reset() {
    this.setData({ form: empty(), varietyIndex: 0, headIndex: 0, slotIndex: 0, typeIndex: 0, heads: ['全部头数'] })
    await this.apply()
  },
  async apply() {
    if (this.data.form.from && this.data.form.to && this.data.form.from > this.data.form.to) {
      this.setData({ error: '开始日期不能晚于结束日期', loading: false }); return
    }
    this._applied = Object.assign({}, this.data.form)
    await this.load(1)
  },
  async load(page) {
    const version = this._version = (this._version || 0) + 1
    this.setData({ loading: true, error: '' })
    try {
      const result = await api('/stock/movements' + query(Object.assign({}, this._applied, { mine: true, page })))
      if (version !== this._version) return
      this.setData({ rows: result.rows.map(row => movement(row, this._preference, this._zone)), more: result.more, page })
    } catch (error) { if (version === this._version) this.setData({ error: error.message, rows: [] }) }
    finally { if (version === this._version) this.setData({ loading: false }) }
  },
  previous() { if (!this.data.loading && this.data.page > 1) this.load(this.data.page - 1) },
  next() { if (!this.data.loading && this.data.more) this.load(this.data.page + 1) },
})
