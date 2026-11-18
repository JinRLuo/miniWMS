<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { Boxes, Download, Grid2X2, List, Plus, Printer, QrCode, Search, X } from 'lucide-vue-next'
import { api, errorMessage } from '../api'
import { includes, number, type Account, type Product, type Shelf, type Slot } from '../model'

const props = defineProps<{
  page: string; products: Product[]; shelves: Shelf[]; slots: Slot[]; user: Account; mobile: boolean
}>()
const emit = defineEmits<{ changed: []; history: [slot: string] }>()
const search = ref('')
const shelfFilter = ref<number | ''>('')
const lowOnly = ref(false)
const visual = ref(false)
const inventoryMode = ref<'product' | 'slot'>('product')
const expanded = ref<number | null>(null)
const busy = ref(false)
const error = ref('')
const editor = ref<'product' | 'shelf' | 'slot' | 'qr' | null>(null)
const dialog = ref<HTMLDialogElement>()
const editId = ref<number | null>(null)
const editCode = ref('')
const qrSlots = ref<Slot[]>([])
const productForm = ref({ sku: '', name: '', barcode: '', category: '', specification: '', unit: '件', imageUrl: '', warningLevel: null as number | null | '', active: true, note: '' })
const shelfForm = ref({ code: '', name: '', layerCount: 4 })
const slotForm = ref({ productId: null as number | null, active: true })
const admin = computed(() => props.user.role === 'ADMIN' && !props.mobile)
const isLow = (product: Product) => product.active && product.warningLevel !== null && product.quantity < product.warningLevel
const filteredProducts = computed(() => props.products.filter(p =>
  (!lowOnly.value || isLow(p)) && includes(search.value, p.sku, p.name, p.barcode, p.category, p.specification)))
const filteredSlots = computed(() => props.slots.filter(s =>
  (!shelfFilter.value || s.shelfId === shelfFilter.value) && includes(search.value, s.code, s.sku, s.productName)))
const visibleShelves = computed(() => props.shelves.filter(s => !shelfFilter.value || s.id === shelfFilter.value))
watch(() => props.page, () => { search.value = ''; shelfFilter.value = ''; lowOnly.value = false; expanded.value = null })

async function open(kind: typeof editor.value) {
  error.value = ''
  editor.value = kind
  await nextTick()
  if (dialog.value && !dialog.value.open) dialog.value.showModal()
}
function print() { window.print() }
function close() { if (!busy.value) dialog.value?.close() }
function editProduct(p?: Product) {
  editId.value = p?.id ?? null
  productForm.value = { sku: p?.sku ?? '', name: p?.name ?? '', barcode: p?.barcode ?? '', category: p?.category ?? '',
    specification: p?.specification ?? '', unit: p?.unit ?? '件', imageUrl: p?.imageUrl ?? '',
    warningLevel: p?.warningLevel ?? null, active: p?.active ?? true, note: p?.note ?? '' }
  void open('product')
}
function editShelf(s?: Shelf) {
  editId.value = s?.id ?? null
  shelfForm.value = { code: s?.code ?? '', name: s?.name ?? '', layerCount: s?.layerCount ?? 4 }
  void open('shelf')
}
function editSlot(s: Slot) {
  editId.value = s.id
  editCode.value = s.code
  slotForm.value = { productId: s.productId, active: s.active }
  void open('slot')
}
function printLabels(slots: Slot[]) {
  qrSlots.value = slots
  void open('qr')
}
async function save() {
  if (busy.value || !editor.value || editor.value === 'qr') return
  busy.value = true
  error.value = ''
  const path = ({ product: '/products', shelf: '/shelves', slot: '/slots' })[editor.value]
  const body = editor.value === 'product' ? { ...productForm.value, warningLevel: productForm.value.warningLevel === '' ? null : productForm.value.warningLevel }
    : editor.value === 'shelf' ? shelfForm.value : slotForm.value
  try {
    await api(path + (editId.value ? '/' + editId.value : ''), body, editId.value ? 'PUT' : 'POST')
    dialog.value?.close()
    emit('changed')
  } catch (e) { error.value = errorMessage(e) }
  finally { busy.value = false }
}
</script>

<template>
  <section class="panel">
    <div class="toolbar">
      <label class="searchbox"><Search :size="17" /><input v-model="search" :placeholder="page === 'products' || (page === 'inventory' && inventoryMode === 'product') ? '搜索商品名、SKU 或条码' : '搜索货架编号、商品或 SKU'" aria-label="搜索"></label>
      <select v-if="page === 'shelves' || (page === 'inventory' && inventoryMode === 'slot')" v-model="shelfFilter" aria-label="按货架筛选">
        <option value="">全部货架</option><option v-for="s in shelves" :key="s.id" :value="s.id">{{ s.code }} · {{ s.name }}</option>
      </select>
      <label v-if="page === 'inventory' && inventoryMode === 'product'" class="check"><input v-model="lowOnly" type="checkbox"> 仅低库存</label>
      <div class="toolbar-end">
        <div v-if="page === 'inventory'" class="tabs compact">
          <button :class="{ selected: inventoryMode === 'product' }" @click="inventoryMode = 'product'">按商品</button>
          <button :class="{ selected: inventoryMode === 'slot' }" @click="inventoryMode = 'slot'">按货架层</button>
        </div>
        <button v-if="page === 'products' && admin" class="primary" @click="editProduct()"><Plus :size="17" /> 新增商品</button>
        <template v-if="page === 'shelves'">
          <button class="secondary" @click="visual = !visual"><component :is="visual ? List : Grid2X2" :size="16" />{{ visual ? '列表' : '货架视图' }}</button>
          <button v-if="admin" class="secondary" :disabled="!filteredSlots.length" @click="printLabels(filteredSlots)"><Printer :size="16" />打印标签</button>
          <button v-if="admin" class="primary" @click="editShelf()"><Plus :size="17" /> 新增货架</button>
        </template>
      </div>
    </div>
    <div v-if="page === 'shelves' && visibleShelves.length" class="shelf-strip">
      <div v-for="s in visibleShelves" :key="s.id">
        <strong>{{ s.code }}</strong><span>{{ s.name }} · {{ s.layerCount }} 层</span>
        <button v-if="admin" class="text-button" @click="editShelf(s)">编辑</button>
      </div>
    </div>
    <div v-if="page === 'products' || (page === 'inventory' && inventoryMode === 'product')" class="table-scroll">
      <table v-if="filteredProducts.length" role="table">
        <thead role="rowgroup"><tr role="row"><th scope="col">商品</th><th scope="col">条码</th><th scope="col">分类 / 规格</th><th scope="col">总库存</th><th scope="col">预警值</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead>
        <tbody role="rowgroup">
          <template v-for="p in filteredProducts" :key="p.id">
            <tr role="row">
              <td role="cell"><div class="object-cell"><img v-if="p.imageUrl" :src="p.imageUrl" alt="" loading="lazy" referrerpolicy="no-referrer">
                <span v-else class="product-icon"><Boxes :size="17" /></span><div>{{ p.name }}<small class="code">{{ p.sku }}</small></div></div></td>
              <td role="cell" data-label="条码">{{ p.barcode || '—' }}</td>
              <td role="cell" data-label="分类 / 规格">{{ [p.category, p.specification].filter(Boolean).join(' / ') || '—' }}</td>
              <td role="cell" data-label="总库存"><strong>{{ number(p.quantity) }} {{ p.unit }}</strong></td>
              <td role="cell" data-label="预警值">{{ p.warningLevel ?? '—' }}</td>
              <td role="cell" data-label="状态"><span :class="['badge', !p.active ? 'neutral' : isLow(p) ? 'out' : 'in']">{{ !p.active ? '已停用' : isLow(p) ? '低库存' : '启用' }}</span></td>
              <td role="cell"><div class="row-actions"><button class="text-button" :aria-expanded="expanded === p.id" @click="expanded = expanded === p.id ? null : p.id">库位分布</button>
                <button v-if="page === 'products' && admin" class="text-button" @click="editProduct(p)">编辑</button></div></td>
            </tr>
            <tr v-if="expanded === p.id" class="distribution" role="row"><td colspan="7" role="cell">
              <strong>货架层分布</strong>
              <div v-for="slot in slots.filter(s => s.productId === p.id)" :key="slot.id"><span class="code">{{ slot.code }}</span> {{ number(slot.quantity) }} {{ p.unit }}</div>
              <span v-if="!slots.some(s => s.productId === p.id)" class="muted">暂无绑定货架层。</span>
            </td></tr>
          </template>
        </tbody>
      </table>
      <p v-else class="empty">没有符合条件的商品。</p>
    </div>
    <div v-else-if="page === 'shelves' && visual && filteredSlots.length" class="shelf-visual">
      <div v-for="s in visibleShelves.filter(s => filteredSlots.some(slot => slot.shelfId === s.id))" :key="s.id" class="shelf-rack">
        <h3>{{ s.code }} · {{ s.name }}</h3>
        <div v-for="slot in filteredSlots.filter(slot => slot.shelfId === s.id)" :key="slot.id" class="rack-layer">
          <strong class="code">{{ slot.code }}</strong><span>{{ slot.productName ?? '未绑定' }}</span><b>{{ slot.quantity }} {{ slot.unit || '' }}</b>
          <button v-if="admin" class="text-button" @click="editSlot(slot)">编辑</button>
        </div>
      </div>
    </div>
    <div v-else class="table-scroll">
      <table v-if="filteredSlots.length" role="table">
        <thead role="rowgroup"><tr role="row"><th scope="col">货架层</th><th scope="col">商品</th><th scope="col">当前库存</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead>
        <tbody role="rowgroup"><tr v-for="slot in filteredSlots" :key="slot.id" role="row">
          <td role="cell" class="code">{{ slot.code }}</td>
          <td role="cell" data-label="商品">{{ slot.productName ?? '—' }}<small v-if="slot.sku">{{ slot.sku }}</small></td>
          <td role="cell" data-label="当前库存"><strong>{{ number(slot.quantity) }} {{ slot.unit || '' }}</strong></td>
          <td role="cell" data-label="状态"><span :class="['badge', slot.active && slot.productId !== null ? 'in' : 'neutral']">{{ !slot.active ? '已停用' : slot.productId === null ? '空闲' : '已绑定' }}</span></td>
          <td role="cell"><div class="row-actions">
            <button class="text-button" @click="$emit('history', slot.code)">流水</button>
            <button v-if="page === 'shelves' && admin" class="text-button" @click="editSlot(slot)">{{ slot.productId === null ? '绑定商品' : '编辑' }}</button>
            <button v-if="page === 'shelves' && !mobile" class="text-button" @click="printLabels([slot])"><QrCode :size="16" /> 二维码</button>
          </div></td>
        </tr></tbody>
      </table>
      <p v-else class="empty">没有符合条件的货架层。</p>
    </div>
  </section>

  <dialog ref="dialog" :class="{ 'qr-dialog': editor === 'qr' }" @close="editor = null" @cancel="event => { if (busy) event.preventDefault() }">
    <div class="dialog-head">
      <h2>{{ editor === 'product' ? (editId ? '编辑商品' : '新增商品') : editor === 'shelf' ? (editId ? '编辑货架' : '新增货架') : editor === 'slot' ? '货架层 ' + editCode : '货架二维码标签' }}</h2>
      <button class="icon-button" aria-label="关闭" :disabled="busy" @click="close"><X :size="20" /></button>
    </div>
    <template v-if="editor === 'qr'">
      <div class="label-grid">
        <article v-for="slot in qrSlots" :key="slot.id" class="qr-label">
          <img :src="'/api/slots/' + slot.id + '/qrcode'" :alt="slot.code + ' 二维码'">
          <strong class="code">{{ slot.code }}</strong><span>{{ slot.productName ?? '未绑定' }}</span>
          <a class="text-button qr-download" :href="'/api/slots/' + slot.id + '/qrcode'" :download="slot.code + '.png'"><Download :size="15" /> 下载</a>
        </article>
      </div>
      <div class="dialog-foot"><button class="secondary" @click="close">关闭</button><button class="primary" @click="print"><Printer :size="17" /> 打印标签</button></div>
    </template>
    <form v-else class="form-stack dialog-form" @submit.prevent="save">
      <fieldset :disabled="busy">
        <template v-if="editor === 'product'">
          <div class="form-columns">
            <label class="field">SKU 编号<input v-model.trim="productForm.sku" required maxlength="64"></label>
            <label class="field">商品名称<input v-model.trim="productForm.name" required maxlength="160"></label>
            <label class="field">商品条码<input v-model.trim="productForm.barcode" maxlength="80"></label>
            <label class="field">分类<input v-model.trim="productForm.category" maxlength="80"></label>
            <label class="field">规格<input v-model.trim="productForm.specification" maxlength="100"></label>
            <label class="field">单位<input v-model.trim="productForm.unit" required maxlength="16"></label>
            <label class="field">库存预警值<input v-model.number="productForm.warningLevel" type="number" min="0" max="2147483647" step="1"></label>
            <label class="field">商品图片 URL<input v-model.trim="productForm.imageUrl" type="url" maxlength="512" placeholder="https://…"></label>
          </div>
          <label class="field">备注<textarea v-model.trim="productForm.note" rows="2" maxlength="500"></textarea></label>
          <label class="check"><input v-model="productForm.active" type="checkbox"> 启用此商品</label>
        </template>
        <template v-if="editor === 'shelf'">
          <label class="field">货架编号<input v-model.trim="shelfForm.code" :readonly="!!editId" maxlength="32" pattern="[A-Za-z0-9]+" placeholder="S01" required></label>
          <label class="field">货架名称<input v-model.trim="shelfForm.name" maxlength="100" required></label>
          <label class="field">层数<input v-model.number="shelfForm.layerCount" type="number" min="1" max="100" step="1" required></label>
        </template>
        <template v-if="editor === 'slot'">
          <label class="field">绑定商品
            <select v-model="slotForm.productId">
              <option :value="null">未绑定</option>
              <option v-for="p in products.filter(p => p.active || p.id === slotForm.productId)" :key="p.id" :value="p.id">{{ p.sku }} · {{ p.name }}</option>
            </select>
          </label>
          <label class="check"><input v-model="slotForm.active" type="checkbox"> 启用此货架层</label>
        </template>
      </fieldset>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <div class="dialog-foot"><button type="button" class="secondary" :disabled="busy" @click="close">取消</button><button class="primary" :disabled="busy">{{ busy ? '保存中…' : '保存' }}</button></div>
    </form>
  </dialog>
</template>
