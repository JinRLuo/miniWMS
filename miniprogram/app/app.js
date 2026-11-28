const { api, restoreSession, clearSession, setWarehouse, onExpired, onPending } = require('./utils/api')
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
    if (!this._initializing) this._ready = null
    try {
      const token = entryToken(options)
      if (token) {
        this.operation = { token, mode: 'IN' }
        if (this.user && this.user.approved !== false) wx.switchTab({ url: '/pages/operations/index' })
      }
    } catch (error) { this.entryError = error.message }
  },
  ready() {
    if (this._initializing) return this._initializing
    if (!this._ready) {
      const request = (async () => {
        let user
        try { user = await restoreSession() }
        catch (error) {
          if (error.status !== 401) throw error
          user = await this.authenticate()
        }
        // A browser password session must never grant access to a different WeChat identity.
        if (!user.wechatBound) user = await this.authenticate()
        this.user = user
        if (user.approved !== false) {
          try { await this.loadSettings() }
          catch (error) { if (error.code !== 'ACCOUNT_PENDING') throw error }
        }
        else { this.warehouse = null; setWarehouse(null) }
        return this.user
      })().catch(error => {
        if (this._ready === request) this._ready = null
        throw error
      }).finally(() => { if (this._initializing === request) this._initializing = null })
      this._ready = this._initializing = request
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
  async authenticate() {
    clearSession()
    this.user = null; this.warehouse = null; this.preferences = { weightUnit: 'JIN' }
    const loginCode = await new Promise((resolve, reject) => wx.login({
      success(result) { result.code ? resolve(result.code) : reject(new Error('未获得微信登录凭证，请重试')) },
      fail() { reject(new Error('微信登录失败，请稍后重试')) },
    }))
    this.user = await api('/auth/wechat/login', { loginCode })
    return this.user
  },
  destination() {
    return this.user && this.user.approved === false ? '/pages/pending/index' :
      this.operation ? '/pages/operations/index' : '/pages/home/index'
  },
  async refreshAccess() {
    if (!this._initializing) this._ready = null
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
  expire() {
    this.user = null; this.warehouse = null; this.preferences = { weightUnit: 'JIN' }; this._ready = null
    setWarehouse(null)
    const pages = getCurrentPages()
    if (!pages.length || pages[pages.length - 1].route !== 'pages/login/index') {
      wx.reLaunch({ url: '/pages/login/index' })
    }
  },
  openOperation(options) {
    this.operation = options
    wx.switchTab({ url: '/pages/operations/index' })
  },
})
