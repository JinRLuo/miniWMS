Page({
  data: { busy: false, error: '', notice: '' },
  onShow() {
    const app = getApp()
    this.setData({ notice: app.loginNotice || '' })
    app.loginNotice = ''
  },
  async login() {
    if (this.data.busy) return
    this.setData({ busy: true, error: '', notice: '' })
    try {
      const app = getApp()
      await app.login()
      wx.reLaunch({ url: app.destination() })
    } catch (error) { this.setData({ error: error.message }) }
    finally { this.setData({ busy: false }) }
  },
})
