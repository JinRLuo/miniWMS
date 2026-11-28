Page({
  data: { busy: false, error: '' },
  onShow() { return this.connect() },
  async onPullDownRefresh() { try { await this.connect() } finally { wx.stopPullDownRefresh() } },
  async connect() {
    if (this.data.busy) return
    this.setData({ busy: true, error: '' })
    try {
      const app = getApp()
      await app.ready()
      wx.reLaunch({ url: app.destination() })
    } catch (error) { this.setData({ error: error.message }) }
    finally { this.setData({ busy: false }) }
  },
})
