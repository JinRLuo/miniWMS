export interface Warehouse { id: number; name: string; type: 'GENERAL' | 'FISH_MAW' }

export type Role = 'ADMIN' | 'OPERATOR'
export type Type = 'IN' | 'OUT' | 'TRANSFER_OUT' | 'TRANSFER_IN'

export interface Account {
  id: number
  username: string
  displayName: string
  role: Role
  active: boolean
}
export interface Product {
  id: number
  sku: string
  name: string
  barcode: string | null
  category: string | null
  specification: string | null
  unit: string
  imageUrl: string | null
  warningLevel: number | null
  active: boolean
  note: string | null
  quantity: number
  warehouseId: number
  variety: string | null
  headCount: number | null
  estimatedCount: number | null
}
export interface Shelf {
  id: number
  code: string
  name: string
  layerCount: number
  warehouseId: number
}
export interface Slot {
  id: number
  shelfId: number
  shelfCode: string
  code: string
  layerNo: number
  productId: number | null
  sku: string | null
  productName: string | null
  unit: string | null
  specification: string | null
  quantity: number
  active: boolean
  productActive: boolean
  warehouseId: number
  cellNo: number
  variety: string | null
  headCount: number | null
  estimatedCount: number | null
}
export interface Summary {
  products: number
  occupiedSlots: number
  slots: number
  lowStock: number
  units: { unit: string; quantity: number; inbound: number; outbound: number }[]
  zone: string
  today: string
}
export interface Movement {
  id: number
  createdAt: number
  type: Type
  documentNo: string
  sku: string
  product: string
  slot: string
  quantity: number
  beforeQty: number
  afterQty: number
  operator: string
  note: string | null
  unit: string
  headCount?: number | null
}
export interface Transfer {
  id: number
  createdAt: number
  transferNo: string
  sku: string
  product: string
  source: string
  target: string
  quantity: number
  operator: string
  note: string | null
  unit: string
  headCount?: number | null
}
export interface Page<T> { rows: T[]; more: boolean }
export interface Trend { date: string; inbound: number; outbound: number }
export interface ProductStats {
  id: number
  sku: string
  name: string
  unit: string
  inbound: number
  outbound: number
  quantity: number
}
export interface StockResult {
  documentNo: string
  changes: { slotId: number; type: Type; beforeQty: number; afterQty: number }[]
}
export const typeLabel: Record<Type, string> = {
  IN: '入库', OUT: '出库', TRANSFER_IN: '移库转入', TRANSFER_OUT: '移库转出',
}

export const estimate = (quantity: number, heads: number | null | undefined) => heads == null ? null : Math.round(quantity * heads + 1e-8)
export const decimal = (value: number) => Math.round(value * 1000) / 1000

export const number = (value: number) => new Intl.NumberFormat('zh-CN').format(value)
export const includes = (query: string, ...values: (string | null | undefined)[]) =>
  values.some(value => value?.toLowerCase().includes(query.trim().toLowerCase()))

export function dateTime(value: number, zone: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: zone, month: '2-digit', day: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(value)
}

export function requestId() {
  // getRandomValues is also available on the PC development server over HTTP.
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 15) | 64
  bytes[8] = (bytes[8] & 63) | 128
  const hex = [...bytes].map(b => b.toString(16).padStart(2, '0')).join('')
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join('-')
}
