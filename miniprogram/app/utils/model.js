const labels = { IN: '入库', OUT: '出库', TRANSFER_IN: '移库转入', TRANSFER_OUT: '移库转出' }
const unitLabel = preference => preference === 'GRAM' ? '克' : '斤'
const decimal = value => Math.round(Number(value) * 1000) / 1000
function weight(value, preference, unit = '斤') {
  return String(Number((Number(value) * (preference === 'GRAM' && unit === '斤' ? 500 : 1)).toFixed(3)))
}
function quantityInput(raw, preference) {
  const value = String(raw).trim()
  if (!/^\d+(?:\.\d+)?$/.test(value)) throw new Error('请输入大于 0 的操作重量')
  const number = Number(value)
  const base = preference === 'GRAM' ? number / 500 : number
  if (!Number.isFinite(base) || base <= 0 || base > 2147483647) throw new Error('操作重量超出允许范围')
  const fraction = (value.split('.')[1] || '').replace(/0+$/, '')
  if (preference === 'GRAM' ? fraction !== '' && fraction !== '5' : fraction.length > 3) {
    throw new Error(preference === 'GRAM' ? '重量最小精度为 0.5 克' : '重量最多支持三位小数')
  }
  return decimal(base)
}
function requestId() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.floor(Math.random() * 16)
    return (c === 'x' ? r : (r & 3) | 8).toString(16)
  })
}
function decode(raw) { try { return decodeURIComponent(raw) } catch (_) { return raw } }
function scanToken(raw) {
  const text = decode(String(raw || '').trim())
  const uuid = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
  const match = text.match(new RegExp('(?:^|/m/slot/|[?&](?:token|scene)=)(' + uuid + ')(?:$|[?#&])', 'i'))
  if (!match) throw new Error('未识别到 Mini WMS 格子二维码，请扫描格子标签')
  return match[1].toLowerCase()
}
function entryToken(options) {
  const query = options && options.query || {}
  const raw = query.q || query.token || query.scene
  if (!raw) return ''
  return scanToken(raw)
}
function dateTime(value, zone = 'Asia/Shanghai') {
  try {
    return new Intl.DateTimeFormat('zh-CN', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value))
  } catch (_) {
    // WeChat JSCore versions without Intl: explicitly show the Shanghai timezone.
    const date = new Date(Number(value) + 8 * 3600000)
    const pad = n => String(n).padStart(2, '0')
    return date.getUTCFullYear() + '/' + pad(date.getUTCMonth() + 1) + '/' + pad(date.getUTCDate()) + ' ' +
      pad(date.getUTCHours()) + ':' + pad(date.getUTCMinutes()) + '（上海）'
  }
}
function movement(row, preference, zone) {
  const unit = row.unit === '斤' ? unitLabel(preference) : row.unit
  return Object.assign({}, row, { label: labels[row.type], time: dateTime(row.createdAt, zone),
    amount: (row.afterQty > row.beforeQty ? '+' : '−') + weight(row.quantity, preference, row.unit) + ' ' + unit,
    balance: weight(row.beforeQty, preference, row.unit) + ' → ' + weight(row.afterQty, preference, row.unit) + ' ' + unit,
    inbound: row.afterQty > row.beforeQty })
}
function slotView(slot, preference) {
  return Object.assign({}, slot, {
    amount: weight(slot.quantity, preference) + ' ' + unitLabel(preference),
    label: slot.code + ' · ' + (slot.productName || '未绑定规格'),
    estimate: Math.round(Number(slot.quantity) * Number(slot.headCount || 0) + 1e-8),
  })
}
function passwordError(current, next, confirm) {
  if (!current) return '请输入当前密码'
  if (next.length < 12) return '新密码至少需要 12 个字符'
  // encodeURIComponent counts UTF-8 bytes and is available in the mini program runtime.
  let bytes
  try { bytes = encodeURIComponent(next).replace(/%[0-9A-F]{2}|./g, 'x').length } catch (_) { return '密码包含无效字符' }
  if (bytes > 72) return '新密码最多支持 72 字节'
  if (next !== confirm) return '两次输入的新密码不一致'
  if (current === next) return '新密码不能与当前密码相同'
  return ''
}
module.exports = { unitLabel, decimal, weight, quantityInput, requestId, scanToken, entryToken, dateTime, movement, slotView, passwordError }
