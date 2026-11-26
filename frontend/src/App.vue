<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Archive, ArrowDownUp, BarChart3, Boxes, ChevronRight, ClipboardList, LayoutDashboard, LogOut, Package, ScanLine, Warehouse, Users as UserIcon } from 'lucide-vue-next'
import { api, ApiError, errorMessage, setWarehouse } from './api'
import type { Account, Product, Shelf, Slot, Summary, Warehouse as WarehouseInfo } from './model'
import Dashboard from './components/Dashboard.vue'
import StockForm from './components/StockForm.vue'
import CatalogView from './components/CatalogView.vue'
import History from './components/History.vue'
import Reports from './components/Reports.vue'
import Users from './components/Users.vue'

const showGeneralWarehouse = import.meta.env.VITE_SHOW_GENERAL_WAREHOUSE === 'true'
const warehouses = ref<WarehouseInfo[]>([])
const warehouseId = ref(2)
const warehouse = computed(() => warehouses.value.find(w => w.id === warehouseId.value) ?? { id: 2, name: '鱼胶仓库', type: 'FISH_MAW' as const })
const fish = computed(() => warehouse.value.type === 'FISH_MAW')

const navItems = [
  { id: 'dashboard', name: '工作台', icon: LayoutDashboard },
  { id: 'products', name: '商品管理', icon: Boxes },
  { id: 'shelves', name: '货架管理', icon: Warehouse },
  { id: 'inventory', name: '库存查询', icon: Archive },
  { id: 'operations', name: '库存操作', icon: ArrowDownUp },
  { id: 'history', name: '出入库记录', icon: ClipboardList },
  { id: 'reports', name: '统计报表', icon: BarChart3 },
  { id: 'users', name: '账号管理', icon: UserIcon },
]
const mobileItems = [navItems[0], navItems[4], navItems[3], navItems[5]]
const mobile = ref(false)
const page = ref('dashboard')
const token = ref<string | null>(null)
const preset = ref<'IN' | 'OUT' | 'TRANSFER'>('IN')
const operationKey = ref(0)
const initialSlotId = ref<number | null>(null)
const historySlot = ref('')
const user = ref<Account>()
const summary = ref<Summary>()
const products = ref<Product[]>([])
const shelves = ref<Shelf[]>([])
const slots = ref<Slot[]>([])
const username = ref('')
const password = ref('')
const opening = ref(true)
const busy = ref(false)
const error = ref('')
const dataError = ref('')
let loadVersion = 0

const title = computed(() => fish.value && page.value === 'products' ? '鱼胶规格' : navItems.find(item => item.id === page.value)?.name ?? '工作台')
const navigation = computed(() => navItems.filter(item => item.id !== 'users' || user.value?.role === 'ADMIN'))

function readLocation() {
  const currentUrl = new URL(location.href)
  const selected = Number(currentUrl.searchParams.get('warehouse') || localStorage.getItem('wms:warehouse') || 2)
  warehouseId.value = showGeneralWarehouse && selected === 1 ? 1 : 2
  setWarehouse(warehouseId.value)
  localStorage.setItem('wms:warehouse', String(warehouseId.value))
  if (currentUrl.searchParams.has('warehouse') && currentUrl.searchParams.get('warehouse') !== String(warehouseId.value)) {
    currentUrl.searchParams.set('warehouse', String(warehouseId.value))
    history.replaceState({}, '', currentUrl.pathname + currentUrl.search + currentUrl.hash)
  }
  const parts = location.pathname.split('/').filter(Boolean)
  mobile.value = parts[0] === 'm' || (parts.length === 0 && matchMedia('(max-width: 760px)').matches)
  token.value = mobile.value && parts[1] === 'slot' ? parts[2] || null : null
  const requested = token.value ? 'operations' : parts[1] || 'dashboard'
  const options = mobile.value ? mobileItems : navigation.value
  page.value = options.some(item => item.id === requested) || (requested === 'users' && !user.value && !mobile.value)
    ? requested : 'dashboard'
  if (page.value !== 'history') historySlot.value = ''
}
function url(next: string) {
  const base = mobile.value ? '/m' : '/admin'
  return (next === 'dashboard' ? base : base + '/' + next) + '?warehouse=' + warehouseId.value
}
function navigate(next: string) {
  if (page.value === next && !token.value) return
  if (next !== 'history') historySlot.value = ''
  if (next === 'operations') { preset.value = 'IN'; initialSlotId.value = null }
  history.pushState({}, '', url(next))
  readLocation()
  window.scrollTo(0, 0)
}
async function switchWarehouse(id: number) {
  warehouseId.value = id
  localStorage.setItem('wms:warehouse', String(id))
  setWarehouse(id)
  token.value = null
  initialSlotId.value = null
  historySlot.value = ''
  history.pushState({}, '', url('dashboard'))
  page.value = 'dashboard'
  summary.value = undefined
  products.value = []; shelves.value = []; slots.value = []
  await refresh()
}
async function prepareWarehouse() {
  warehouses.value = (await api<WarehouseInfo[]>('/warehouses')).filter(w => showGeneralWarehouse || w.type === 'FISH_MAW')
  let qrError = ''
  if (token.value) {
    const slot = await api<Slot>('/slots/resolve/' + token.value)
    if (warehouses.value.some(w => w.id === slot.warehouseId)) {
      warehouseId.value = slot.warehouseId
      setWarehouse(slot.warehouseId)
      localStorage.setItem('wms:warehouse', String(slot.warehouseId))
    } else {
      token.value = null
      history.replaceState({}, '', url('operations'))
      qrError = '该二维码对应的仓库暂未开放，请扫描鱼胶格子二维码。'
    }
  }
  await refresh()
  if (qrError) dataError.value = qrError
}
async function locationChanged() {
  readLocation()
  summary.value = undefined
  try { await prepareWarehouse() } catch (e) { dataError.value = errorMessage(e) }
}
function action(mode: typeof preset.value) {
  initialSlotId.value = null
  navigate('operations')
  preset.value = mode
  operationKey.value++
}
function openStock(slotId: number, mode: 'IN' | 'OUT') {
  action(mode)
  initialSlotId.value = slotId
}
function openHistory(slot: string) {
  historySlot.value = slot
  navigate('history')
}
function expired() {
  if (!user.value) return
  loadVersion++
  user.value = undefined
  summary.value = undefined
  products.value = []
  shelves.value = []
  slots.value = []
  error.value = '登录已过期，请重新登录。'
}
async function refresh() {
  if (!user.value) return
  const version = ++loadVersion
  try {
    const result = await Promise.all([
      api<Summary>('/reports/summary'), api<Product[]>('/products'), api<Shelf[]>('/shelves'), api<Slot[]>('/slots'),
    ])
    if (loadVersion !== version) return
    const [nextSummary, nextProducts, nextShelves, nextSlots] = result
    summary.value = nextSummary
    products.value = nextProducts
    shelves.value = nextShelves
    slots.value = nextSlots
    dataError.value = ''
  } catch (e) { if (loadVersion === version) dataError.value = errorMessage(e) }
}
async function login() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await api('/auth/login', new URLSearchParams({ username: username.value.trim(), password: password.value }))
    user.value = await api<Account>('/auth/me')
    password.value = ''
    readLocation()
    await prepareWarehouse()
  } catch (e) {
    if (user.value) dataError.value = errorMessage(e)
    else error.value = errorMessage(e)
  }
  finally { busy.value = false }
}
async function logout() {
  try { await api('/auth/logout', undefined, 'POST'); expired(); error.value = '' }
  catch (e) { dataError.value = errorMessage(e) }
}
async function refreshUser() {
  try { user.value = await api<Account>('/auth/me') }
  catch (e) { if (e instanceof ApiError && e.status === 401) expired(); else dataError.value = errorMessage(e) }
}
onMounted(async () => {
  readLocation()
  window.addEventListener('popstate', locationChanged)
  window.addEventListener('wms:session-expired', expired)
  try {
    user.value = await api<Account>('/auth/me')
    readLocation()
    await prepareWarehouse()
  } catch (e) {
    if (!(e instanceof ApiError) || e.status !== 401) {
      if (user.value) dataError.value = errorMessage(e)
      else error.value = errorMessage(e)
    }
  }
  finally { opening.value = false }
})
onBeforeUnmount(() => {
  window.removeEventListener('popstate', locationChanged)
  window.removeEventListener('wms:session-expired', expired)
})
</script>

<template>
  <div v-if="opening" class="splash"><span class="brand-icon"><Package :size="24" /></span><p role="status">正在打开工作台…</p></div>
  <main v-else-if="!user" class="login-page">
    <section class="login-brand">
      <div class="brand"><span class="brand-icon"><Package :size="25" /></span><strong>Mini WMS</strong></div>
      <div><h1>{{ showGeneralWarehouse ? '电商仓储' : '鱼胶库存' }}<br>工作台</h1><p>{{ showGeneralWarehouse ? '商品、货架和出入库记录' : '品种、头数、格子和出入库记录' }}</p></div>
      <div class="hero-graphic" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i><i></i></div>
    </section>
    <section class="login-form">
      <form class="form-stack" @submit.prevent="login">
        <h2>{{ showGeneralWarehouse ? '登录仓库' : '登录鱼胶仓库' }}</h2>
        <label class="field">账号<input v-model="username" autocomplete="username" required placeholder="输入账号"></label>
        <label class="field">密码<input v-model="password" autocomplete="current-password" type="password" required placeholder="输入密码"></label>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <button class="primary" :disabled="busy">{{ busy ? '登录中…' : '登录' }} <ChevronRight :size="17" /></button>
      </form>
    </section>
  </main>
  <div v-else :class="['app-shell', { 'mobile-shell': mobile }]">
    <aside v-if="!mobile" class="sidebar">
      <a class="brand" href="/admin" @click.prevent="navigate('dashboard')"><span class="brand-icon"><Package :size="23" /></span>
        <span><strong>Mini WMS</strong><small>{{ showGeneralWarehouse ? '仓储管理系统' : '鱼胶库存管理' }}</small></span></a>
      <div v-if="warehouses.length > 1" class="warehouse-switch" aria-label="切换仓库">
        <button v-for="w in warehouses" :key="w.id" :class="{ selected: warehouseId === w.id }" @click="switchWarehouse(w.id)">
          <Warehouse :size="18" /><span>{{ w.name }}<small>{{ w.type === 'FISH_MAW' ? '按格子 · 斤' : '按货架层' }}</small></span>
        </button>
      </div>
      <nav class="side-nav" aria-label="主导航">
        <a v-for="item in navigation" :key="item.id" :href="url(item.id)" :class="{ active: page === item.id }"
          :aria-current="page === item.id ? 'page' : undefined" @click.prevent="navigate(item.id)"><component :is="item.icon" :size="19" />{{ fish && item.id === 'products' ? '鱼胶规格' : item.name }}</a>
      </nav>
    </aside>
    <div class="workspace">
      <header class="topbar">
        <template v-if="mobile"><a class="brand mobile-brand" href="/m" @click.prevent="navigate('dashboard')"><span class="brand-icon"><Package :size="19" /></span>{{ showGeneralWarehouse ? 'Mini WMS' : warehouse.name }}</a></template>
        <div v-else class="breadcrumb"><span>{{ warehouse.name }}</span><ChevronRight :size="15" /><strong>{{ title }}</strong></div>
        <div class="profile"><span class="avatar">{{ user.displayName.slice(0, 1) }}</span>
          <span class="profile-name"><strong>{{ user.displayName }}</strong><small>{{ user.role === 'ADMIN' ? '管理员' : '仓库操作员' }}</small></span>
          <button class="icon-button" title="退出登录" aria-label="退出登录" @click="logout"><LogOut :size="19" /></button>
        </div>
      </header>
      <main class="page" :key="warehouseId">
        <div v-if="mobile && warehouses.length > 1" class="mobile-warehouse-switch">
          <button v-for="w in warehouses" :key="w.id" :class="{ selected: warehouseId === w.id }" @click="switchWarehouse(w.id)">{{ w.name }}</button>
        </div>
        <h1>{{ title }}</h1>
        <p v-if="dataError" class="error" role="alert">{{ dataError }} <button class="text-button" @click="refresh">重试</button></p>
        <p v-if="!summary && !dataError" class="empty" role="status">正在读取仓库数据…</p>
        <template v-if="summary">
          <Dashboard v-if="page === 'dashboard'" :summary="summary" :mobile="mobile" :warehouse="warehouse" @navigate="navigate" @action="action" />
          <StockForm v-else-if="page === 'operations'" :key="operationKey" :slots="slots" :products="products" :warehouse="warehouse" :mobile="mobile" :initial-slot-id="initialSlotId" :initial-mode="preset" :token="token" @changed="refresh" />
          <CatalogView v-else-if="['products', 'shelves', 'inventory'].includes(page)" :page="page" :products="products" :shelves="shelves"
            :slots="slots" :warehouse="warehouse" :user="user" :mobile="mobile" @changed="refresh" @history="openHistory" @stock="openStock" />
          <History v-else-if="page === 'history'" :warehouse="warehouse" :zone="summary.zone" :mobile="mobile" :initial-slot="historySlot" :products="products" :slots="slots" />
          <Reports v-else-if="page === 'reports'" :catalog="products" :slots="slots" :warehouse="warehouse" :today="summary.today" :units="summary.units.map(u => u.unit)" />
          <Users v-else-if="page === 'users' && user.role === 'ADMIN'" @changed="refreshUser" />
        </template>
      </main>
      <nav v-if="mobile" class="mobile-nav" aria-label="手机主导航">
        <a v-for="item in mobileItems" :key="item.id" :href="url(item.id)" :class="{ active: page === item.id }" :aria-current="page === item.id ? 'page' : undefined" @click.prevent="navigate(item.id)">
          <component :is="item.id === 'operations' ? ScanLine : item.icon" :size="21" />
          {{ ({ dashboard: '首页', operations: '扫码', inventory: '库存', history: '记录' } as Record<string, string>)[item.id] }}
        </a>
      </nav>
    </div>
  </div>
</template>
