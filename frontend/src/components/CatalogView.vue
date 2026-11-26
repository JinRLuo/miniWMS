<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { Boxes, Download, Grid2X2, List, Plus, Printer, QrCode, Search, X } from 'lucide-vue-next'
import { api, errorMessage } from '../api'
import FishShelfBoard from './FishShelfBoard.vue'
import AppSelect from './AppSelect.vue'
import { includes, number, requestId, type Account, type Product, type Shelf, type Slot, type Warehouse } from '../model'

const props = defineProps<{
  page: string; products: Product[]; shelves: Shelf[]; slots: Slot[]; user: Account; mobile: boolean; warehouse: Warehouse
}>()
const emit = defineEmits<{ changed: []; history: [slot: string]; stock: [slotId: number, mode: 'IN' | 'OUT'] }>()
const fish = computed(() => props.warehouse.type === 'FISH_MAW')
const slotLabel = computed(() => fish.value ? '格子' : '货架层')
const defaultLayerCount = fish.value ? 3 : 4
const defaultCellsPerLayer = 2
const layerCells = ref<number[]>([])
const cellsPerLayer = ref(defaultCellsPerLayer)
const search = ref('')
const shelfFilter = ref<number | ''>('')
const slotState = ref('ALL')
const lowOnly = ref(false)
const visual = ref(fish.value)
const inventoryMode = ref<'product' | 'slot'>(fish.value ? 'slot' : 'product')
const expanded = ref<number | null>(null)
const busy = ref(false)
const error = ref('')
const editor = ref<'product' | 'shelf' | 'slot' | 'qr' | null>(null)
const dialog = ref<HTMLDialogElement>()
const editId = ref<number | null>(null)
const editCode = ref('')
const qrSlots = ref<Slot[]>([])
const printSheet = ref<HTMLDivElement>()
const printing = ref(false)
const productForm = ref({ sku: '', name: '', barcode: '', category: '', specification: '', unit: '件', imageUrl: '', warningLevel: null as number | null | '', active: true, note: '', variety: '', headCount: null as number | null })
const shelfForm = ref({ code: '', name: '', layerCount: defaultLayerCount })
const slotForm = ref({ productId: null as number | null, active: true })
watch(() => shelfForm.value.layerCount, layers => {
  layerCells.value = Array.from({ length: Math.max(0, Math.min(100, Number(layers) || 0)) }, (_, i) => layerCells.value[i] ?? cellsPerLayer.value)
})
const admin = computed(() => props.user.role === 'ADMIN' && !props.mobile)
const isLow = (product: Product) => product.active && product.warningLevel !== null && product.quantity < product.warningLevel
const filteredProducts = computed(() => props.products.filter(p =>
  (!lowOnly.value || isLow(p)) && includes(search.value, p.sku, p.name, p.barcode, p.category, p.specification)))
const shelfNames = computed(() => new Map(props.shelves.map(shelf => [shelf.id, shelf.name])))
const filteredSlots = computed(() => props.slots.filter(s =>
  (!shelfFilter.value || s.shelfId === shelfFilter.value) && includes(search.value, s.code, s.sku, s.productName, s.variety, shelfNames.value.get(s.shelfId) ?? '') &&
  (props.page !== 'shelves' || !fish.value || slotState.value === 'ALL' ||
    (slotState.value === 'STOCKED' && s.active && s.quantity > 0) ||
    (slotState.value === 'BOUND' && s.active && s.productId !== null && s.quantity === 0) ||
    (slotState.value === 'EMPTY' && s.active && s.productId === null) ||
    (slotState.value === 'INACTIVE' && !s.active))))
const visibleShelves = computed(() => props.shelves.filter(s => !shelfFilter.value || s.id === shelfFilter.value))
watch(() => props.page, () => { search.value = ''; shelfFilter.value = ''; lowOnly.value = false; slotState.value = 'ALL'; expanded.value = null })

async function open(kind: typeof editor.value) {
  error.value = ''
  editor.value = kind
  await nextTick()
  if (dialog.value && !dialog.value.open) dialog.value.showModal()
}
async function print() {
  if (printing.value || !printSheet.value) return
  printing.value = true
  error.value = ''
  try {
    await Promise.all([...printSheet.value.querySelectorAll('img')].map(image => image.decode()))
    window.print()
  } catch {
    error.value = '二维码加载失败，请重新打开标签后再打印。'
  } finally {
    printing.value = false
  }
}
function close() { if (!busy.value) dialog.value?.close() }
function editProduct(p?: Product) {
  editId.value = p?.id ?? null
  productForm.value = { sku: p?.sku ?? '', name: p?.name ?? '', barcode: p?.barcode ?? '', category: p?.category ?? '',
    specification: p?.specification ?? '', unit: p?.unit ?? (fish.value ? '斤' : '件'), imageUrl: p?.imageUrl ?? '',
    warningLevel: p?.warningLevel ?? null, active: p?.active ?? true, note: p?.note ?? '', variety: p?.variety ?? '', headCount: p?.headCount ?? null }
  void open('product')
}
function editShelf(s?: Shelf) {
  editId.value = s?.id ?? null
  if (!s) cellsPerLayer.value = defaultCellsPerLayer
  shelfForm.value = { code: s?.code ?? '', name: s?.name ?? '', layerCount: s?.layerCount ?? defaultLayerCount }
  layerCells.value = Array.from({ length: s?.layerCount ?? defaultLayerCount }, (_, i) => s ? props.slots.filter(slot => slot.shelfId === s.id && slot.layerNo === i + 1).length || 1 : cellsPerLayer.value)
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
  const body = editor.value === 'product' ? { ...productForm.value, warehouseId: props.warehouse.id,
    sku: productForm.value.sku || (fish.value ? 'YJ-' + requestId().slice(0, 12).toUpperCase() : ''),
    name: fish.value ? productForm.value.variety + ' · ' + productForm.value.headCount + '头' : productForm.value.name, warningLevel: productForm.value.warningLevel === '' ? null : productForm.value.warningLevel }
    : editor.value === 'shelf' ? { ...shelfForm.value, warehouseId: props.warehouse.id, cellCounts: fish.value ? layerCells.value : undefined } : slotForm.value
  try {
    await api(path + (editId.value ? '/' + editId.value : ''), body, editId.value ? 'PUT' : 'POST')
    dialog.value?.close()
    emit('changed')
  } catch (e) { error.value = errorMessage(e) }
  finally { busy.value = false }
}
</script>

<template>
  <section :class="['panel', { 'shelf-management': page === 'shelves' && fish }]">
    <div class="toolbar">
      <label class="searchbox"><Search :size="17" /><input v-model="search" :placeholder="fish ? '搜索货架、格子编号、品种或头数' : page === 'products' || (page === 'inventory' && inventoryMode === 'product') ? '搜索商品名、SKU 或条码' : '搜索货架编号、商品或 SKU'" aria-label="搜索"></label>
      <AppSelect v-if="page === 'shelves' || (page === 'inventory' && inventoryMode === 'slot')" v-model="shelfFilter" label="按货架筛选"
        :options="[{ value: '', label: '全部货架' }, ...shelves.map(s => ({ value: s.id, label: s.code + ' · ' + s.name }))]" />
      <AppSelect v-if="page === 'shelves' && fish" v-model="slotState" label="按格子状态筛选"
        :options="[{ value: 'ALL', label: '全部状态' }, { value: 'STOCKED', label: '有库存' }, { value: 'BOUND', label: '待入库' }, { value: 'EMPTY', label: '空格' }, { value: 'INACTIVE', label: '已停用' }]" />
      <label v-if="page === 'inventory' && inventoryMode === 'product'" class="check"><input v-model="lowOnly" type="checkbox"> 仅低库存</label>
      <div class="toolbar-end">
        <div v-if="page === 'inventory'" class="tabs compact">
          <button :class="{ selected: inventoryMode === 'product' }" @click="inventoryMode = 'product'">按商品</button>
          <button :class="{ selected: inventoryMode === 'slot' }" @click="inventoryMode = 'slot'">按{{ slotLabel }}</button>
        </div>
        <button v-if="page === 'products' && admin" class="primary" @click="editProduct()"><Plus :size="17" /> {{ fish ? '新增鱼胶规格' : '新增商品' }}</button>
        <template v-if="page === 'shelves'">
          <button class="secondary" @click="visual = !visual"><component :is="visual ? List : Grid2X2" :size="16" />{{ visual ? '列表' : '货架图' }}</button>
          <button v-if="admin" class="secondary" :disabled="!filteredSlots.length" @click="printLabels(filteredSlots)"><Printer :size="16" />打印标签</button>
          <button v-if="admin" class="primary" @click="editShelf()"><Plus :size="17" /> 新增货架</button>
        </template>
      </div>
    </div>
    <div v-if="page === 'shelves' && !fish && visibleShelves.length" class="shelf-strip">
      <div v-for="s in visibleShelves" :key="s.id">
        <strong>{{ s.code }}</strong><span>{{ s.name }} · {{ s.layerCount }} 层<span v-if="fish"> · {{ slots.filter(slot => slot.shelfId === s.id).length }} 格</span></span>
        <button v-if="admin" class="text-button" @click="editShelf(s)">编辑</button>
      </div>
    </div>
    <div v-if="page === 'products' || (page === 'inventory' && inventoryMode === 'product')" class="table-scroll">
      <table v-if="filteredProducts.length" role="table">
        <thead role="rowgroup"><tr role="row"><th scope="col">商品</th><th scope="col">条码</th><th scope="col">{{ fish ? '品种 / 头数' : '分类 / 规格' }}</th><th scope="col">总库存</th><th scope="col">预警值</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead>
        <tbody role="rowgroup">
          <template v-for="p in filteredProducts" :key="p.id">
            <tr role="row">
              <td role="cell"><div class="object-cell"><img v-if="p.imageUrl" :src="p.imageUrl" alt="" loading="lazy" referrerpolicy="no-referrer">
                <span v-else class="product-icon"><Boxes :size="17" /></span><div>{{ p.name }}<small class="code">{{ p.sku }}</small></div></div></td>
              <td role="cell" data-label="条码">{{ p.barcode || '—' }}</td>
              <td role="cell" data-label="分类 / 规格">{{ fish ? p.variety + ' / ' + p.headCount + '头' : [p.category, p.specification].filter(Boolean).join(' / ') || '—' }}</td>
              <td role="cell" data-label="总库存"><strong>{{ number(p.quantity) }} {{ p.unit }}</strong><small v-if="fish">约{{ number(p.estimatedCount ?? 0) }}个</small></td>
              <td role="cell" data-label="预警值">{{ p.warningLevel ?? '—' }}</td>
              <td role="cell" data-label="状态"><span :class="['badge', !p.active ? 'neutral' : isLow(p) ? 'out' : 'in']">{{ !p.active ? '已停用' : isLow(p) ? '低库存' : '启用' }}</span></td>
              <td role="cell"><div class="row-actions"><button class="text-button" :aria-expanded="expanded === p.id" @click="expanded = expanded === p.id ? null : p.id">库位分布</button>
                <button v-if="page === 'products' && admin" class="text-button" @click="editProduct(p)">编辑</button></div></td>
            </tr>
            <tr v-if="expanded === p.id" class="distribution" role="row"><td colspan="7" role="cell">
              <strong>{{ slotLabel }}分布</strong>
              <div v-for="slot in slots.filter(s => s.productId === p.id)" :key="slot.id"><span class="code">{{ slot.code }}</span> {{ number(slot.quantity) }} {{ p.unit }}</div>
              <span v-if="!slots.some(s => s.productId === p.id)" class="muted">暂无绑定{{ slotLabel }}。</span>
            </td></tr>
          </template>
        </tbody>
      </table>
      <p v-else class="empty">没有符合条件的商品。</p>
    </div>
    <FishShelfBoard v-else-if="page === 'shelves' && visual && fish" :shelves="visibleShelves" :slots="slots"
      :matching-slots="filteredSlots" :filtering="!!search.trim() || slotState !== 'ALL'" :admin="admin"
      @edit-shelf="editShelf" @edit-slot="editSlot" @print="printLabels" @history="code => $emit('history', code)" @stock="(id, mode) => $emit('stock', id, mode)" />
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
        <thead role="rowgroup"><tr role="row"><th scope="col">{{ slotLabel }}</th><th scope="col">商品</th><th scope="col">当前库存</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead>
        <tbody role="rowgroup"><tr v-for="slot in filteredSlots" :key="slot.id" role="row">
          <td role="cell" class="code">{{ slot.code }}</td>
          <td role="cell" data-label="商品">{{ slot.productName ?? '—' }}<small v-if="slot.sku">{{ slot.sku }}</small></td>
          <td role="cell" data-label="当前库存"><strong>{{ number(slot.quantity) }} {{ slot.unit || '' }}</strong><small v-if="fish && slot.headCount">约{{ number(slot.estimatedCount ?? 0) }}个</small></td>
          <td role="cell" data-label="状态"><span :class="['badge', slot.active && slot.productId !== null ? 'in' : 'neutral']">{{ !slot.active ? '已停用' : slot.productId === null ? '空闲' : '已绑定' }}</span></td>
          <td role="cell"><div class="row-actions">
            <button class="text-button" @click="$emit('history', slot.code)">流水</button>
            <button v-if="page === 'shelves' && admin" class="text-button" @click="editSlot(slot)">{{ slot.productId === null ? '绑定商品' : '编辑' }}</button>
            <button v-if="page === 'shelves' && !mobile" class="text-button" @click="printLabels([slot])"><QrCode :size="16" /> 二维码</button>
          </div></td>
        </tr></tbody>
      </table>
      <p v-else class="empty">没有符合条件的{{ slotLabel }}。</p>
    </div>
  </section>

  <dialog ref="dialog" :class="{ 'qr-dialog': editor === 'qr' }" @close="editor = null" @cancel="event => { if (busy) event.preventDefault() }">
    <div class="dialog-head">
      <h2>{{ editor === 'product' ? (editId ? (fish ? '编辑鱼胶规格' : '编辑商品') : (fish ? '新增鱼胶规格' : '新增商品')) : editor === 'shelf' ? (editId ? '编辑货架' : '新增货架') : editor === 'slot' ? slotLabel + ' ' + editCode : '货架二维码标签' }}</h2>
      <button class="icon-button" aria-label="关闭" :disabled="busy" @click="close"><X :size="20" /></button>
    </div>
    <template v-if="editor === 'qr'">
      <div class="label-grid">
        <article v-for="slot in qrSlots" :key="slot.id" class="qr-label">
          <img :src="'/api/slots/' + slot.id + '/qrcode'" :alt="slot.code + ' 二维码'">
          <strong class="code">{{ slot.code }}</strong>
          <a class="text-button qr-download" :href="'/api/slots/' + slot.id + '/qrcode'" :download="slot.code + '.png'"><Download :size="15" /> 下载</a>
        </article>
      </div>
      <p v-if="error" class="error panel-message" role="alert">{{ error }}</p>
      <div class="dialog-foot"><button class="secondary" @click="close">关闭</button><button class="primary" :disabled="printing" @click="print"><Printer :size="17" /> {{ printing ? '正在准备…' : '打印标签' }}</button></div>
    </template>
    <form v-else class="form-stack dialog-form" @submit.prevent="save">
      <fieldset :disabled="busy">
        <template v-if="editor === 'product'">
          <div class="form-columns">
            <label class="field">{{ fish ? '规格编号（留空自动生成）' : 'SKU 编号' }}<input v-model.trim="productForm.sku" :required="!fish" maxlength="64"></label>
            <label v-if="!fish" class="field">商品名称<input v-model.trim="productForm.name" required maxlength="160"></label>
            <label v-if="!fish" class="field">商品条码<input v-model.trim="productForm.barcode" maxlength="80"></label>
            <label v-if="!fish" class="field">分类<input v-model.trim="productForm.category" maxlength="80"></label>
            <label v-if="!fish" class="field">规格<input v-model.trim="productForm.specification" maxlength="100"></label>
            <label v-if="!fish" class="field">单位<input v-model.trim="productForm.unit" required maxlength="16"></label>
            <label v-if="fish" class="field">品种<input v-model.trim="productForm.variety" required maxlength="100" placeholder="如白花"></label>
            <label v-if="fish" class="field">头数（个 / 斤）<input v-model.number="productForm.headCount" type="number" min="1" max="100000" step="1" required placeholder="如33"></label>
            <label class="field">{{ fish ? '库存预警值（斤）' : '库存预警值' }}<input v-model.number="productForm.warningLevel" type="number" min="0" max="2147483647" :step="fish ? 0.001 : 1"></label>
            <label class="field">商品图片 URL<input v-model.trim="productForm.imageUrl" type="url" maxlength="512" placeholder="https://…"></label>
          </div>
          <label class="field">备注<textarea v-model.trim="productForm.note" rows="2" maxlength="500"></textarea></label>
          <label class="check"><input v-model="productForm.active" type="checkbox"> 启用此商品</label>
        </template>
        <template v-if="editor === 'shelf'">
          <label class="field">货架编号<input v-model.trim="shelfForm.code" :readonly="!!editId" maxlength="32" pattern="[A-Za-z0-9]+" placeholder="S01" required></label>
          <label class="field">货架名称<input v-model.trim="shelfForm.name" maxlength="100" required></label>
          <label class="field">层数<input v-model.number="shelfForm.layerCount" type="number" min="1" max="100" step="1" required></label>
          <template v-if="fish">
            <div class="code-entry"><label class="field">每层默认格数<input v-model.number="cellsPerLayer" type="number" min="1" max="100" step="1"></label><button type="button" class="secondary" @click="layerCells = layerCells.map(() => cellsPerLayer)">应用到全部层</button></div>
            <div class="form-columns"><label v-for="(_, i) in layerCells" :key="i" class="field">第 {{ i + 1 }} 层格数<input v-model.number="layerCells[i]" type="number" min="1" max="100" step="1" required></label></div>
            <small>格子编码按货架、层、格自动生成。已有格子可停用，不能删除或重新编号。</small>
          </template>
        </template>
        <template v-if="editor === 'slot'">
          <label class="field">绑定商品
            <AppSelect v-model="slotForm.productId" label="绑定商品" :options="[{ value: null, label: '未绑定' },
              ...products.filter(p => p.active || p.id === slotForm.productId).map(p => ({ value: p.id, label: p.name, description: p.sku }))]" />
          </label>
          <label class="check"><input v-model="slotForm.active" type="checkbox"> 启用此{{ slotLabel }}</label>
        </template>
      </fieldset>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <div class="dialog-foot"><button type="button" class="secondary" :disabled="busy" @click="close">取消</button><button class="primary" :disabled="busy">{{ busy ? '保存中…' : '保存' }}</button></div>
    </form>
  </dialog>
  <Teleport to="body">
    <div v-if="editor === 'qr'" ref="printSheet" class="qr-print-sheet" aria-hidden="true">
      <article v-for="slot in qrSlots" :key="slot.id" class="qr-print-page">
        <img :src="'/api/slots/' + slot.id + '/qrcode'" alt="">
        <strong>{{ slot.code }}</strong>
      </article>
    </div>
  </Teleport>
</template>
