const { api } = require('../../utils/api')
const { guard } = require('../../utils/page')
Page({
  data: { loading: true, error: '', busy: false, user: null, unit: 'JIN', saved: false },
  async onShow() {
    const app = await guard(this)
    if (app) this.setData({ loading: false, user: app.user, unit: app.preferences.weightUnit })
  },
  unit(event) { if (!this.data.busy) this.setData({ unit: event.currentTarget.dataset.unit, saved: false }) },
  input(event) { if (!this.data.busy) this.setData({ [event.currentTarget.dataset.field]: event.detail.value }) },
  async save() {
    if (this.data.busy) return
    this.setData({ busy: true, error: '', saved: false })
    try {
      const result = await api('/auth/preferences', { weightUnit: this.data.unit }, 'PUT')
      getApp().preferences = result
      this.setData({ saved: true })
    } catch (error) { this.setData({ error: error.message }) }
    finally { this.setData({ busy: false }) }
  },
})
