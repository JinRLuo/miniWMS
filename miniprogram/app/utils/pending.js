const { baseUrl } = require('../config')
const key = userId => 'wms:pending:' + baseUrl + ':' + userId
module.exports = {
  read(userId) { return wx.getStorageSync(key(userId)) || null },
  save(userId, request) { wx.setStorageSync(key(userId), request) },
  clear(userId) { wx.removeStorageSync(key(userId)) },
}
