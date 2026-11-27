Page({
  data: { loading: true, busy: false, error: '', accountId: '', name: '' },
  onShow() { this.refresh() },
  async onPullDownRefresh() { try { await this.refresh() } finally { wx.stopPullDownRefresh() } },
  async refresh() {
    if (this.data.busy) return
    this.setData({ busy: true, error: '' })
    try {
      const app = getApp()
      const user = await app.refreshAccess()
      if (user.approved !== false) { wx.reLaunch({ url: app.destination() }); return }
      this.setData({ accountId: user.id, name: user.displayName })
    } catch (error) { this.setData({ error: error.message }) }
    finally { this.setData({ loading: false, busy: false }) }
  },
  async logout() {
    if (this.data.busy) return
    this.setData({ busy: true, error: '' })
    try { await getApp().logout() }
    catch (error) { this.setData({ error: error.message }) }
    finally { this.setData({ busy: false }) }
  },
})
