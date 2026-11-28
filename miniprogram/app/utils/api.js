const config = require('../config')
const storageKey = 'wms:wechat-session:v1:' + config.baseUrl
let cookies = wx.getStorageSync(storageKey) || {}
let csrf = ''
let csrfRequest = null
let warehouseId = null
let expired = () => {}
let pending = () => {}
let generation = 0

class ApiError extends Error {
  constructor(status, message, code) { super(message); this.status = status; this.code = code }
}

function query(values) {
  const entries = Object.keys(values).filter(key => values[key] !== '' && values[key] !== undefined && values[key] !== null)
  return entries.length ? '?' + entries.map(key => encodeURIComponent(key) + '=' + encodeURIComponent(values[key])).join('&') : ''
}

function clearSession() {
  generation++
  cookies = {}; csrf = ''; csrfRequest = null; warehouseId = null
  wx.removeStorageSync(storageKey)
}

function saveCookies(response) {
  const header = response.header || {}
  const raw = response.cookies && response.cookies.length ? response.cookies :
    (header['Set-Cookie'] || header['set-cookie'] || '')
  const lines = Array.isArray(raw) ? raw : String(raw).split(/,(?=\s*[^;,=\s]+=)/)
  lines.forEach(line => {
    const pair = line.split(';')[0].trim()
    const equals = pair.indexOf('=')
    if (equals < 1) return
    const name = pair.slice(0, equals)
    if (name !== 'JSESSIONID' && name !== 'XSRF-TOKEN') return
    const value = pair.slice(equals + 1)
    if (!value || /max-age\s*=\s*0(?:;|$)/i.test(line)) delete cookies[name]
    else cookies[name] = value
  })
  wx.setStorageSync(storageKey, cookies)
}

function transport(path, data, method = 'GET', headers = {}, notifyExpired = true) {
  const currentGeneration = generation
  return new Promise((resolve, reject) => {
    wx.request({
      url: config.baseUrl + '/api' + path, method, data, timeout: config.timeout,
      header: Object.assign({ Accept: 'application/json', Cookie: Object.keys(cookies).map(key => key + '=' + cookies[key]).join('; ') }, headers),
      success(response) {
        // Requests started before logout/expiration must not resurrect an old session.
        if (generation !== currentGeneration) { reject(new ApiError(401, '登录状态已更新，请重试')); return }
        try { saveCookies(response) } catch (_) { reject(new ApiError(0, '无法保存登录状态，请检查微信存储空间')); return }
        const status = response.statusCode
        if (status >= 200 && status < 300) { resolve(status === 204 ? undefined : response.data); return }
        if (status === 403) csrf = ''
        if (status === 401 && !['/auth/login', '/auth/wechat/login'].includes(path)) { clearSession(); if (notifyExpired) expired() }
        const code = response.data && response.data.code
        if (status === 403 && code === 'ACCOUNT_PENDING') pending()
        const message = path === '/auth/wechat/login' && status === 401
          ? '微信登录服务尚未就绪，请联系管理员更新后端服务'
          : response.data && response.data.message || '请求失败（' + status + '）'
        reject(new ApiError(status, message, code))
      },
      fail() { reject(new ApiError(0, '暂时无法连接服务器，请检查网络与合法域名配置后重试')) },
    })
  })
}

async function csrfToken() {
  if (csrf) return csrf
  if (!csrfRequest) {
    const currentGeneration = generation
    csrfRequest = transport('/auth/csrf').then(result => {
      if (generation !== currentGeneration) throw new ApiError(401, '登录状态已更新，请重试')
      csrf = result.token; return csrf
    }).finally(() => { if (generation === currentGeneration) csrfRequest = null })
  }
  return csrfRequest
}

async function api(path, body, method = body === undefined ? 'GET' : 'POST', form = false) {
  const currentGeneration = generation
  if (!path.startsWith('/auth/') && path !== '/warehouses') {
    if (!warehouseId) throw new ApiError(0, '仓库尚未加载，请返回工作台重试')
    path += (path.includes('?') ? '&' : '?') + 'warehouseId=' + warehouseId
  }
  const headers = {}
  if (method !== 'GET') headers['X-XSRF-TOKEN'] = await csrfToken()
  if (generation !== currentGeneration) throw new ApiError(401, '登录状态已更新，请重试')
  if (body !== undefined) headers['Content-Type'] = form ? 'application/x-www-form-urlencoded' : 'application/json'
  const result = await transport(path, body, method, headers)
  // Spring rotates both session ID and CSRF cookie at authentication boundaries.
  if (['/auth/login', '/auth/wechat/login', '/auth/logout', '/auth/password'].includes(path)) csrf = ''
  return result
}

module.exports = {
  api, query, ApiError, clearSession,
  // Startup probes may recover a missing session without navigating away mid-login.
  restoreSession() { return transport('/auth/me', undefined, 'GET', {}, false) },
  setWarehouse(id) { warehouseId = id },
  onExpired(handler) { expired = handler },
  onPending(handler) { pending = handler },
}
