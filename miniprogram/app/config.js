// Only develop may use a local HTTP backend. Trial/release always use production.
const servers = {
  develop: 'https://wms.piuiq.com',
  trial: 'https://wms.piuiq.com',
  release: 'https://wms.piuiq.com',
}
let environment = 'release'
try { environment = wx.getAccountInfoSync().miniProgram.envVersion || 'release' } catch (_) {}
const baseUrl = servers[environment] || servers.release
module.exports = { baseUrl, timeout: 20000 }
