const { api, ApiError } = require('../../utils/api')
const { guard } = require('../../utils/page')
const { scanToken, quantityInput, weight, unitLabel, requestId, slotView, decimal } = require('../../utils/model')
const pendingStore = require('../../utils/pending')

Page({
  data: { loading: true, busy: false, locating: false, error: '', mode: 'IN', query: '', quantity: '', note: '',
    unit: '斤', slots: [], options: [], selected: null, preview: '', validation: '', pending: null, result: null },
  onShow() { this.load() },
  async onPullDownRefresh() {
    try { if (!this.data.busy) await this.load() } finally { wx.stopPullDownRefresh() }
  },
  async load() {
    if (this.data.busy || this.data.locating) return
    const version = this._version = (this._version || 0) + 1
    this.setData({ loading: true, error: '' })
    const app = await guard(this, 'operations')
    if (!app || version !== this._version) return
    this._owner = app.user.id
    this._preference = app.preferences.weightUnit
    try {
      const pending = pendingStore.read(this._owner)
      this.setData({ pending, unit: unitLabel(this._preference), quantity: '', note: '', preview: '', validation: '', result: null })
      const context = await api('/stock/context')
      if (version !== this._version) return
      const slots = context.locations.map(slot => slotView(slot, this._preference))
      const selected = this.data.selected && slots.find(slot => slot.id === this.data.selected.id) || null
      this.setData({ slots, selected, options: slots, query: '' })
      const entry = app.operation
      app.operation = null
      if (pending) {
        this.setData({ mode: pending.mode, selected: pending.slot, error: '有一笔操作尚未确认结果，请先重试原请求。' })
      } else if (entry) {
        this.setData({ mode: entry.mode === 'OUT' ? 'OUT' : 'IN', selected: null })
        if (entry.token) await this.resolve(entry.token)
        else if (entry.slotId) this.select(slots.find(slot => slot.id === entry.slotId))
        if (entry.scan) await this.scan()
      }
    } catch (error) { if (version === this._version) this.setData({ error: error.message }) }
    finally { if (version === this._version) this.setData({ loading: false }) }
  },
  locked() { return this.data.busy || this.data.locating || !!this.data.pending },
  mode(event) {
    if (this.locked()) return
    const mode = event.currentTarget.dataset.mode
    if (mode !== this.data.mode) this.setData({ mode, quantity: '', preview: '', validation: '', result: null, error: '' })
  },
  input(event) {
    if (this.locked()) return
    const field = event.currentTarget.dataset.field
    this.setData({ [field]: event.detail.value, result: null })
    if (field === 'query') {
      const query = event.detail.value.trim().toLowerCase()
      this.setData({ options: this.data.slots.filter(slot => [slot.code, slot.productName, slot.sku].some(value => String(value || '').toLowerCase().includes(query))) })
    }
    if (field === 'quantity') this.updatePreview()
  },
  pick(event) { if (!this.locked()) this.select(this.data.options[Number(event.detail.value)]) },
  select(slot) {
    if (!slot) return
    if (slot.warehouseId !== getApp().warehouse.id) throw new Error('该二维码对应的仓库暂未开放，请扫描鱼胶格子二维码')
    this.setData({ selected: slotView(slot, this._preference), error: '', result: null, quantity: '', preview: '', validation: '' })
  },
  async resolve(token) { this.select(await api('/slots/resolve/' + scanToken(token))) },
  async locate() {
    if (this.locked()) return
    const code = this.data.query.trim()
    if (!code) { this.setData({ error: '请输入完整格子编号' }); return }
    this.setData({ locating: true, error: '' })
    try { this.select(await api('/slots/by-code/' + encodeURIComponent(code))) }
    catch (error) { this.setData({ error: error.message }) }
    finally { this.setData({ locating: false }) }
  },
  async scan() {
    if (this.locked()) return
    this.setData({ locating: true, error: '' })
    try {
      const value = await new Promise((resolve, reject) => wx.scanCode({ scanType: ['qrCode'], success: resolve, fail: reject }))
      await this.resolve(value.result || value.path)
    } catch (error) {
      if (!String(error.errMsg || '').includes('cancel')) this.setData({ error: error.message || '扫码失败，请允许相机权限或输入完整格子编号' })
    } finally { this.setData({ locating: false }) }
  },
  validate() {
    const { selected, mode, quantity } = this.data
    if (!selected) throw new Error('请先扫描或选择格子')
    if (!selected.active) throw new Error('该格子已停用')
    if (!selected.productId) throw new Error('该格子尚未绑定规格，请在电脑端货架管理中绑定')
    if (mode === 'IN' && !selected.productActive) throw new Error('规格已停用，无法入库')
    const value = quantityInput(quantity, this._preference)
    if (mode === 'OUT' && value > selected.quantity) throw new Error('出库重量不能超过当前库存 ' + selected.amount)
    if (mode === 'IN' && selected.quantity + value > 2147483647) throw new Error('入库后库存超出允许范围')
    return value
  },
  updatePreview() {
    if (!this.data.quantity || !this.data.selected) { this.setData({ preview: '', validation: '' }); return }
    try {
      const value = this.validate()
      const after = decimal(this.data.selected.quantity + (this.data.mode === 'IN' ? value : -value))
      this.setData({ preview: weight(after, this._preference) + ' ' + this.data.unit, validation: '' })
    } catch (error) { this.setData({ preview: '', validation: error.message }) }
  },
  async submit() {
    if (this.locked() || this.data.loading) return
    try {
      const quantity = this.validate()
      const pending = { mode: this.data.mode, slot: this.data.selected, preference: this._preference,
        warehouseId: getApp().warehouse.id, path: '/stock/' + this.data.mode.toLowerCase(),
        body: { requestId: requestId(), slotId: this.data.selected.id, productId: this.data.selected.productId,
          quantity, note: this.data.note.trim() } }
      // Persist before sending, so a closed page/process can safely recover the same request.
      pendingStore.save(this._owner, pending)
      this.setData({ pending, error: '', result: null })
      await this.send()
    } catch (error) { this.setData({ error: error.message }) }
  },
  async send() {
    if (this.data.busy || !this.data.pending) return
    const pending = this.data.pending
    const app = getApp()
    if (!app.user || app.user.id !== this._owner || pending.warehouseId !== app.warehouse.id) {
      this.setData({ error: '请使用原操作账号和仓库确认这笔请求' }); return
    }
    this.setData({ busy: true, error: '' })
    try {
      const result = await api(pending.path, pending.body)
      pendingStore.clear(this._owner)
      const change = result.changes[0]
      const selected = Object.assign({}, pending.slot, { quantity: change.afterQty })
      const slots = this.data.slots.map(slot => slot.id === selected.id ? slotView(selected, app.preferences.weightUnit) : slot)
      this.setData({ pending: null, result: { documentNo: result.documentNo,
        amount: weight(change.afterQty, app.preferences.weightUnit) + ' ' + unitLabel(app.preferences.weightUnit) },
        selected: slotView(selected, app.preferences.weightUnit), slots, options: slots, query: '',
        quantity: '', note: '', preview: '', validation: '' })
    } catch (error) {
      if (error instanceof ApiError && error.status >= 400 && error.status < 500 && ![401, 403, 408, 429].includes(error.status)) {
        pendingStore.clear(this._owner)
        this.setData({ pending: null, error: error.message })
        // Refresh the selected slot after a stock conflict without changing the entered quantity.
        try {
          const slot = await api('/slots/by-code/' + encodeURIComponent(pending.slot.code))
          this.setData({ selected: slotView(slot, this._preference) }); this.updatePreview()
        } catch (_) {}
      } else {
        this.setData({ error: '未确认操作结果。请重试原请求，系统会使用同一请求编号，避免重复记账。' })
      }
    } finally { this.setData({ busy: false }) }
  },
})
