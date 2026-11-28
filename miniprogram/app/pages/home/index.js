const { api } = require('../../utils/api')
const { guard } = require('../../utils/page')
const { weight, unitLabel, movement } = require('../../utils/model')
Page({
  data: { loading: true, error: '', admin: false, name: '', warehouse: '', today: '', metrics: [], recent: [], lowStock: 0, trend: [] },
  onShow() { this.load() },
  async onPullDownRefresh() { try { await this.load() } finally { wx.stopPullDownRefresh() } },
  async load() {
    const version = this._version = (this._version || 0) + 1
    this.setData({ loading: true, error: '' })
    const app = await guard(this, 'home')
    if (!app) return
    if (app.operation) { wx.switchTab({ url: '/pages/operations/index' }); return }
    const admin = app.user.role === 'ADMIN'
    this.setData({ admin, name: app.user.displayName, warehouse: app.warehouse.name })
    try {
      const [context, history, trend] = await Promise.all([
        api(admin ? '/reports/summary' : '/stock/context'),
        api('/stock/movements?mine=' + !admin),
        admin ? api('/reports/trend?unit=' + encodeURIComponent('斤')) : Promise.resolve([]),
      ])
      if (this._version !== version) return
      const preference = app.preferences.weightUnit
      const total = admin ? context.units.find(unit => unit.unit === '斤') || {} : {}
      const max = Math.max(1, ...trend.map(row => Math.max(row.inbound, row.outbound)))
      this.setData({ today: context.today, lowStock: context.lowStock || 0,
        metrics: admin ? [
          { label: '当前总库存', value: weight(total.quantity || 0, preference), unit: unitLabel(preference) },
          { label: '启用规格', value: context.products, unit: '种' },
          { label: '今日入库', value: weight(total.inbound || 0, preference), unit: unitLabel(preference) },
          { label: '今日出库', value: weight(total.outbound || 0, preference), unit: unitLabel(preference) },
        ] : [],
        recent: history.rows.slice(0, 5).map(row => movement(row, preference, context.zone)),
        trend: trend.map(row => ({ date: row.date.slice(5), inHeight: Math.max(2, row.inbound / max * 100),
          outHeight: Math.max(2, row.outbound / max * 100),
          label: row.date + '，入库 ' + weight(row.inbound, preference) + unitLabel(preference) + '，出库 ' + weight(row.outbound, preference) + unitLabel(preference) })),
        error: app.entryError || '',
      })
      app.entryError = ''
    } catch (error) { if (version === this._version) this.setData({ error: error.message }) }
    finally { if (version === this._version) this.setData({ loading: false }) }
  },
  action(event) { getApp().openOperation({ mode: event.currentTarget.dataset.mode || 'IN', scan: !!event.currentTarget.dataset.scan }) },
  history() { wx.switchTab({ url: '/pages/history/index' }) },
  settings() { wx.navigateTo({ url: '/pages/settings/index' }) },
  inventory() { wx.switchTab({ url: '/pages/inventory/index' }) },
})
