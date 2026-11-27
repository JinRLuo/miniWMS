Page({
  data: { busy: false, error: '', notice: '' },
  onShow() {
    const app = getApp()
    this.setData({ notice: app.loginNotice || '' })
    app.loginNotice = ''
  },
  async login(event) {
    if (this.data.busy) return
    const phoneCode = event && event.detail && event.detail.code
    if (!phoneCode) { this.setData({ error: '需要授权微信手机号才能登录，请重新点击授权按钮。' }); return }
    this.setData({ busy: true, error: '', notice: '' })
    try {
      const app = getApp()
      await app.login(phoneCode)
      wx.reLaunch({ url: app.destination() })
    } catch (error) { this.setData({ error: error.message }) }
    finally { this.setData({ busy: false }) }
  },
})
