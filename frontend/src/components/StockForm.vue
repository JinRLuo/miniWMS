<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { ArrowDownLeft, ArrowLeftRight, ArrowUpRight, Check, PackageCheck } from 'lucide-vue-next'
import { api, ApiError, errorMessage } from '../api'
import { includes, number, requestId, decimal, estimate, type Product, type Slot, type StockResult, type Warehouse } from '../model'
import AppSelect from './AppSelect.vue'

const ScanCamera = defineAsyncComponent(() => import('./ScanCamera.vue'))
const props = defineProps<{
  slots: Slot[]; products: Product[]; mobile: boolean; initialMode: 'IN' | 'OUT' | 'TRANSFER'; token: string | null; warehouse: Warehouse; initialSlotId?: number | null
}>()
const emit = defineEmits<{ changed: [] }>()
const mode = ref(props.initialMode)
const source = ref<number | ''>(props.initialSlotId ?? '')
const fish = computed(() => props.warehouse.type === 'FISH_MAW')
const slotLabel = computed(() => fish.value ? '格子' : '货架层')
const target = ref<number | ''>('')
const quantity = ref<number | ''>(1)
const documentNo = ref('')
const note = ref('')
const sourceSearch = ref('')
const targetSearch = ref('')
const resolved = ref<Slot>()
const initialLoading = ref(false)
const busy = ref(false)
const error = ref('')
const result = ref<StockResult>()
const pending = ref<{ path: string; body: Record<string, unknown> }>()

const slots = computed(() => resolved.value ? [
  ...props.slots.filter(slot => slot.id !== resolved.value?.id), resolved.value,
].sort((a, b) => a.shelfCode.localeCompare(b.shelfCode) || a.layerNo - b.layerNo || a.cellNo - b.cellNo) : props.slots)
const from = computed(() => slots.value.find(slot => slot.id === source.value))
const to = computed(() => slots.value.find(slot => slot.id === target.value))
const qty = computed(() => quantity.value !== '' && Number.isFinite(Number(quantity.value)) ? Number(quantity.value) : 0)
const isTransfer = computed(() => mode.value === 'TRANSFER')
const selectableSlots = computed(() => slots.value.filter(slot => !fish.value || slot.productId !== null))
const sourceOptions = computed(() => selectableSlots.value.filter(s => s.id === source.value ||
  includes(sourceSearch.value, s.code, s.sku, s.productName)))
const targetOptions = computed(() => selectableSlots.value.filter(s => s.id === target.value ||
  (s.id !== source.value && includes(targetSearch.value, s.code, s.sku, s.productName))))
const maximum = computed(() => {
  if (!from.value) return 0
  if (mode.value === 'OUT') return from.value.quantity
  if (isTransfer.value) return Math.min(from.value.quantity, 2147483647 - (to.value?.quantity ?? 0))
  return 2147483647 - from.value.quantity
})
const remaining = computed(() => from.value ? decimal(from.value.quantity + qty.value * (mode.value === 'IN' ? 1 : -1)) : 0)
const validation = computed(() => {
  if (!from.value) return ''
  if (qty.value > 0 && (fish.value ? Math.abs(qty.value * 1000 - Math.round(qty.value * 1000)) > 1e-7 : !Number.isInteger(qty.value))) return fish.value ? '重量最多支持三位小数' : '操作数量必须为整数'
  if (!from.value.active) return '该库位已停用'
  if (from.value.productId === null) return '该库位尚未绑定商品，请在货架管理中绑定'
  if (mode.value === 'IN' && !from.value.productActive) return '商品已停用，无法入库'
  if (isTransfer.value && to.value) {
    if (to.value.id === from.value.id) return '请选择不同的来源和目标库位'
    if (!to.value.active) return '目标库位已停用'
    if (to.value.productId !== null && to.value.productId !== from.value.productId) return '目标已绑定其他商品'
    if (to.value.productId === null && !from.value.productActive) return '停用商品不能绑定到空货架层'
  }
  if (maximum.value === 0) return '当前库位没有可用操作数量'
  if (qty.value > maximum.value) return '可操作数量最多为 ' + number(maximum.value)
  return ''
})
const canSubmit = computed(() => from.value && qty.value > 0 && !validation.value && (!isTransfer.value || to.value))
const total = computed(() => props.products.find(p => p.id === from.value?.productId)?.quantity)

function operations(next: 'IN' | 'OUT' | 'TRANSFER') {
  mode.value = next
  error.value = ''
  result.value = undefined
}

async function locate(destination = false) {
  error.value = ''
  try {
    const code = destination ? targetSearch.value : sourceSearch.value
    if (!code.trim()) throw new Error('请输入完整库位编号')
    const slot = await api<Slot>('/slots/by-code/' + encodeURIComponent(code.trim()))
    resolved.value = slot
    if (destination) target.value = slot.id
    else source.value = slot.id
  } catch (e) { error.value = errorMessage(e) }
}

async function scan(value: string, destination: boolean) {
  error.value = ''
  try {
    const raw = value.trim()
    const token = raw.match(/(?:^|\/m\/slot\/)([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?:$|[?#])/i)?.[1]
    if (!token) throw new Error('未识别到 Mini WMS 货架二维码')
    const slot = await api<Slot>('/slots/resolve/' + token.toLowerCase())
    if (slot.warehouseId !== props.warehouse.id) throw new Error('该二维码不属于当前仓库，请扫描当前仓库的库位二维码')
    resolved.value = slot
    if (destination) target.value = slot.id
    else source.value = slot.id
  } catch (e) { error.value = errorMessage(e) }
}

watch(() => props.slots, () => { resolved.value = undefined })
watch(() => props.token, async token => {
  if (!token) return
  initialLoading.value = true
  await scan(token, false)
  initialLoading.value = false
}, { immediate: true })

async function submit() {
  if (busy.value || pending.value || !canSubmit.value || !from.value) return
  result.value = undefined
  error.value = ''
  const path = '/stock/' + mode.value.toLowerCase()
  const common = { requestId: requestId(), productId: from.value.productId, quantity: qty.value, note: note.value.trim() }
  const body = isTransfer.value
    ? { ...common, sourceSlotId: from.value.id, targetSlotId: to.value?.id }
    : { ...common, slotId: from.value.id, documentNo: documentNo.value.trim() }
  pending.value = { path, body }
  await send()
}

async function send() {
  if (busy.value || !pending.value) return
  const request = pending.value
  error.value = ''
  busy.value = true
  try {
    result.value = await api<StockResult>(request.path, request.body)
    pending.value = undefined
    documentNo.value = ''
    note.value = ''
    quantity.value = ''
    emit('changed')
  } catch (e) {
    if (e instanceof ApiError && e.status >= 400 && e.status < 500) {
      pending.value = undefined
      error.value = errorMessage(e)
      emit('changed')
    } else {
      error.value = '未收到操作结果。重试会沿用原请求编号，请先确认该笔操作。'
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="operation-grid">
    <section class="panel form-panel">
      <div class="tabs" aria-label="库存操作类型">
        <button v-for="choice in [{ key: 'IN', label: '入库', icon: ArrowDownLeft }, { key: 'OUT', label: '出库', icon: ArrowUpRight }, { key: 'TRANSFER', label: '移库', icon: ArrowLeftRight }] as const"
          :key="choice.key" :class="{ selected: mode === choice.key }" :disabled="busy || !!pending" @click="operations(choice.key)">
          <component :is="choice.icon" :size="18" />{{ choice.label }}
        </button>
      </div>
      <form class="form-stack" @submit.prevent="submit">
        <fieldset :disabled="busy || initialLoading || !!pending">
          <div class="field">
            <label for="source-slot">{{ isTransfer ? '来源' + slotLabel : slotLabel }}</label>
            <ScanCamera v-if="mobile" :label="'扫描' + slotLabel + '码'" :key="'source-' + mode" @decoded="value => scan(value, false)" />
            <div class="code-entry"><input v-model="sourceSearch" :aria-label="'输入' + slotLabel + '编号'" :placeholder="fish ? '输入格子编号，如 YJ01-02-03' : '输入货架层编号或搜索商品'" @keydown.enter.prevent="locate()"><button type="button" class="secondary" @click="locate()">定位</button></div>
            <AppSelect id="source-slot" v-model="source" :label="isTransfer ? '来源' + slotLabel : slotLabel" required
              :options="[{ value: '', label: '请选择' + slotLabel, disabled: true }, ...sourceOptions.map(slot => ({ value: slot.id, label: slot.code,
                description: (slot.productName || '未绑定') + ' · ' + number(slot.quantity) + ' ' + (slot.unit || '') + (slot.active ? '' : ' · 已停用') }))]" />
            <small v-if="initialLoading">正在读取二维码对应的{{ slotLabel }}…</small>
            <small v-else-if="fish && !selectableSlots.length">暂无已绑定商品的格子，请先在货架管理中绑定鱼胶规格。</small>
          </div>
          <div v-if="isTransfer" class="field">
            <label for="target-slot">目标{{ slotLabel }}</label>
            <ScanCamera v-if="mobile" :label="'扫描' + slotLabel + '码'" @decoded="value => scan(value, true)" />
            <div class="code-entry"><input v-model="targetSearch" :aria-label="'输入目标' + slotLabel + '编号'" placeholder="输入目标库位完整编号" @keydown.enter.prevent="locate(true)"><button type="button" class="secondary" @click="locate(true)">定位</button></div>
            <AppSelect id="target-slot" v-model="target" :label="'目标' + slotLabel" required
              :options="[{ value: '', label: '请选择目标' + slotLabel, disabled: true }, ...targetOptions.map(slot => ({ value: slot.id, label: slot.code,
                description: (slot.productName || '未绑定') + ' · ' + number(slot.quantity) + ' ' + (slot.unit || '') + (slot.active ? '' : ' · 已停用') }))]" />
            <small v-if="to && to.active && to.productId === null">移库成功后将自动绑定来源商品。</small>
          </div>
          <div v-if="fish && from?.productId" class="fish-stock-info">
            <strong>{{ from.variety }} · {{ from.headCount }}头</strong>
            <span>当前 {{ number(from.quantity) }}斤 · 约{{ number(from.estimatedCount ?? 0) }}个</span>
            <small>个数按重量估算，出入库以实际斤数为准。</small>
          </div>
          <div class="form-columns">
            <label class="field">{{ fish ? '操作重量（斤）' : '操作数量' }}<input v-model.number="quantity" type="number" :inputmode="fish ? 'decimal' : 'numeric'" :min="fish ? 0.001 : 1" :step="fish ? 0.001 : 1" :max="maximum" required></label>
            <label v-if="!isTransfer" class="field">单据编号（选填）<input v-model="documentNo" maxlength="80" placeholder="如订单号"></label>
          </div>
          <label class="field">备注（选填）<textarea v-model="note" maxlength="500" rows="2" placeholder="填写操作说明"></textarea></label>
        </fieldset>
        <div v-if="mobile && from && canSubmit && !result" class="mobile-stock-preview" aria-live="polite">
          <strong>预计库存</strong>
          <span>{{ from.code }} · {{ number(from.quantity) }} → {{ number(remaining) }} {{ from.unit }}</span>
          <span v-if="isTransfer && to">{{ to.code }} · {{ number(to.quantity) }} → {{ number(decimal(to.quantity + qty)) }} {{ from.unit }}</span>
          <span v-if="fish && canSubmit">预计约{{ number(estimate(remaining, from.headCount) ?? 0) }}个</span>
          <span v-if="isTransfer && total !== undefined">商品总库存 · {{ number(total) }} {{ from.unit }}</span>
        </div>
        <p v-if="error || validation" class="error" role="alert">{{ error || validation }}</p>
        <div v-if="result" class="success" role="status">
          <Check :size="19" /><div><strong>操作成功</strong><small class="break">{{ result.documentNo }}</small>
          <span v-for="change in result.changes" :key="change.slotId" class="result-change">
            {{ slots.find(slot => slot.id === change.slotId)?.code }} {{ number(change.beforeQty) }} → {{ number(change.afterQty) }} {{ from?.unit }}
          </span></div>
        </div>
        <button v-if="pending" type="button" class="primary" :disabled="busy" @click="send">{{ busy ? '正在提交…' : '重试并确认结果' }}</button>
        <button v-else class="primary" :disabled="!canSubmit || busy || initialLoading">
          {{ busy ? '正在提交…' : '确认' + ({ IN: '入库', OUT: '出库', TRANSFER: '移库' }[mode]) }}
        </button>
      </form>
    </section>
    <aside v-if="!mobile" class="panel preview">
      <span class="metric-icon green"><PackageCheck :size="22" /></span>
      <h2>库存预览</h2>
      <p v-if="!from" class="muted">选择{{ slotLabel }}后查看当前库存和预计变动。</p>
      <template v-else>
        <h3>{{ from.productName || '未绑定商品' }}</h3>
        <p class="muted">{{ [from.sku, from.specification].filter(Boolean).join(' · ') }}</p>
        <dl class="detail-list">
          <div><dt>{{ from.code }} 当前库存</dt><dd>{{ number(from.quantity) }} {{ from.unit }}</dd></div>
          <div v-if="canSubmit"><dt>{{ from.code }} 预计库存</dt><dd>{{ number(remaining) }} {{ from.unit }}</dd></div>
          <div v-if="isTransfer && to"><dt>{{ to.code }} 当前库存</dt><dd>{{ number(to.quantity) }} {{ from.unit }}</dd></div>
          <div v-if="isTransfer && to && canSubmit"><dt>{{ to.code }} 预计库存</dt><dd>{{ number(decimal(to.quantity + qty)) }} {{ from.unit }}</dd></div>
          <div v-if="fish && canSubmit"><dt>预计估算个数</dt><dd>约{{ number(estimate(remaining, from.headCount) ?? 0) }}个</dd></div>
          <div v-if="total !== undefined"><dt>商品总库存</dt><dd>{{ number(total) }} {{ from.unit }}</dd></div>
        </dl>
      </template>
    </aside>
  </div>
</template>
