const { api, clearSession, setWarehouse, onExpired, onPending } = require('./utils/api')
const { entryToken } = require('./utils/model')

App({
  user: null,
  preferences: { weightUnit: 'JIN' },
  warehouse: null,
  operation: null,
  historySlot: null,
  entryError: '',
  onLaunch() {
    onExpired(() => this.expire())
    onPending(() => this.openPending())
  },
  onShow(options) {
    try {
      const token = entryToken(options)
      if (token) {
        this.operation = { token, mode: 'IN' }
        if (this.user && this.user.approved !== false) wx.switchTab({ url: '/pages/operations/index' })
      }
    } catch (error) { this.entryError = error.message }
  },
  ready() {
    if (!this._ready) {
      this._ready = (async () => {
        this.user = await api('/auth/me')
        if (!this.user.wechatBound) {
          clearSession()
          this.expire('请使用微信手机号登录')
          throw new Error('请使用微信手机号登录')
        }
        if (this.user.approved !== false) await this.loadSettings()
        else { this.warehouse = null; setWarehouse(null) }
        return this.user
      })().catch(error => { this._ready = null; throw error })
    }
    return this._ready
  },
  async loadSettings() {
    const [warehouses, preferences] = await Promise.all([api('/warehouses'), api('/auth/preferences')])
    const warehouse = warehouses.find(item => item.type === 'FISH_MAW')
    if (!warehouse) throw new Error('当前没有可用的鱼胶仓库，请联系管理员')
    this.warehouse = warehouse
    this.preferences = preferences
    setWarehouse(warehouse.id)
  },
  async login(phoneCode) {
    if (!phoneCode) throw new Error('请先授权微信手机号')
    const loginCode = await new Promise((resolve, reject) => wx.login({
      success(result) { result.code ? resolve(result.code) : reject(new Error('未获得微信登录凭证，请重试')) },
      fail() { reject(new Error('微信登录失败，请稍后重试')) },
    }))
    clearSession()
    this.user = null; this.warehouse = null
    this._ready = null
    this.user = await api('/auth/wechat/login', { loginCode, phoneCode })
    if (this.user.approved !== false) await this.ready()
    return this.user
  },
  destination() {
    return this.user && this.user.approved === false ? '/pages/pending/index' :
      this.operation ? '/pages/operations/index' : '/pages/home/index'
  },
  async refreshAccess() {
    this._ready = null
    return this.ready()
  },
  openPending() {
    if (this.user) this.user.approved = false
    this.warehouse = null; this._ready = null
    setWarehouse(null)
    const pages = getCurrentPages()
    if (!pages.length || pages[pages.length - 1].route !== 'pages/pending/index') {
      wx.reLaunch({ url: '/pages/pending/index' })
    }
  },
  expire(notice = '登录已过期，请重新登录') {
    this.user = null; this.warehouse = null; this.preferences = { weightUnit: 'JIN' }; this._ready = null
    this.loginNotice = notice
    const pages = getCurrentPages()
    if (!pages.length || pages[pages.length - 1].route !== 'pages/login/index') {
      wx.reLaunch({ url: '/pages/login/index' })
    }
  },
  async logout() {
    await api('/auth/logout', undefined, 'POST')
    clearSession()
    this.operation = null; this.historySlot = null
    this.expire('')
  },
  openOperation(options) {
    this.operation = options
    wx.switchTab({ url: '/pages/operations/index' })
  },
})
