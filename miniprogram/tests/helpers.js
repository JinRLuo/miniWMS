const path = require('node:path')
const root = path.resolve(__dirname, '../app')
function fresh() {
  for (const key of Object.keys(require.cache)) if (key.startsWith(root)) delete require.cache[key]
}
function mockWx(request) {
  const storage = new Map()
  global.wx = {
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'develop' } }),
    getStorageSync: key => structuredClone(storage.get(key)),
    setStorageSync: (key, value) => storage.set(key, structuredClone(value)),
    removeStorageSync: key => storage.delete(key),
    request,
    reLaunch() {}, switchTab() {}, stopPullDownRefresh() {},
  }
  return storage
}
function instance(definition) {
  const page = Object.assign({}, definition, { data: structuredClone(definition.data || {}) })
  page.setData = values => {
    for (const [key, value] of Object.entries(values)) {
      const segments = key.split('.')
      let object = page.data
      while (segments.length > 1) object = object[segments.shift()]
      object[segments[0]] = value
    }
  }
  return page
}
function loadPage(file) {
  let definition
  global.Page = value => { definition = value }
  require(path.join(root, file))
  return instance(definition)
}
module.exports = { fresh, mockWx, instance, loadPage }
