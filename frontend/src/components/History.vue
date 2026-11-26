<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ChevronLeft, ChevronRight, Download, Search } from 'lucide-vue-next'
import { api, errorMessage, params, currentWarehouse } from '../api'
import FishSpecFilter from './FishSpecFilter.vue'
import AppSelect from './AppSelect.vue'
import DateInput from './DateInput.vue'
import { useWeight } from '../weight'
import { showTransfers } from '../features'
import { dateTime, typeLabel, type Movement, type Page, type ProductChoice, type SlotChoice, type Transfer, type Warehouse } from '../model'

const props = defineProps<{ zone: string; mobile: boolean; ownOnly: boolean; initialSlot: string; products: ProductChoice[]; slots: SlotChoice[]; warehouse: Warehouse }>()
const { formatWeight, displayUnit } = useWeight()
const slotLabel = computed(() => props.warehouse.type === 'FISH_MAW' ? '格子' : '货架层')
const tab = ref<'movements' | 'transfers'>('movements')
const form = ref({
  from: '', to: '', variety: '', headCount: '' as number | '', productId: '' as number | '',
  slotId: props.slots.find(slot => slot.code === props.initialSlot)?.id ?? '' as number | '',
  type: '', operator: '', document: '', sourceSlotId: '' as number | '', targetSlotId: '' as number | '',
})
const applied = ref({ ...form.value })
const movements = ref<Movement[]>([])
const transfers = ref<Transfer[]>([])
const page = ref(1)
const more = ref(false)
const loading = ref(false)
const error = ref('')
let loadVersion = 0
const filters = computed(() => params({ ...applied.value, warehouseId: currentWarehouse(), mine: props.ownOnly ? true : undefined }))
const exportLink = computed(() => '/api/stock/movements.csv' + filters.value)
const hasRows = computed(() => tab.value === 'movements' ? movements.value.length > 0 : transfers.value.length > 0)
const slotOptions = computed(() => [{ value: '' as const, label: '全部' + slotLabel.value },
  ...props.slots.map(slot => ({ value: slot.id, label: slot.code + (slot.active ? '' : '（已停用）') }))])

async function load() {
  const version = ++loadVersion
  loading.value = true
  error.value = ''
  try {
    const query = params({ ...applied.value, warehouseId: currentWarehouse(), mine: props.ownOnly ? true : undefined, page: page.value })
    if (tab.value === 'movements') {
      const result = await api<Page<Movement>>('/stock/movements' + query)
      if (loadVersion !== version) return
      movements.value = result.rows
      more.value = result.more
    } else {
      const result = await api<Page<Transfer>>('/stock/transfers' + query)
      if (loadVersion !== version) return
      transfers.value = result.rows
      more.value = result.more
    }
  } catch (e) { if (loadVersion === version) error.value = errorMessage(e) }
  finally { if (loadVersion === version) loading.value = false }
}
function apply() { applied.value = { ...form.value }; page.value = 1; void load() }
function choose(value: typeof tab.value) { tab.value = value; apply() }
function paginate(delta: number) { page.value += delta; void load() }
watch(() => props.initialSlot, code => {
  form.value.slotId = props.slots.find(slot => slot.code === code)?.id ?? ''
  choose('movements')
})
onMounted(load)
</script>

<template>
  <section class="panel">
    <div class="toolbar">
      <div v-if="showTransfers" class="tabs compact">
        <button :class="{ selected: tab === 'movements' }" @click="choose('movements')">库存流水</button>
        <button :class="{ selected: tab === 'transfers' }" @click="choose('transfers')">移库单</button>
      </div>
      <h2 v-else>库存流水</h2>
      <a v-if="tab === 'movements' && !mobile" class="secondary toolbar-end" :href="exportLink"><Download :size="17" /> 导出 CSV</a>
    </div>
    <p v-if="ownOnly" class="record-scope" role="note">仅显示本人操作的出入库记录，查询和导出均不包含其他账号的记录。</p>
    <form class="filter-form" @submit.prevent="apply">
      <div class="filter-fields">
        <label class="field">开始日期<DateInput v-model="form.from" /></label>
        <label class="field">结束日期<DateInput v-model="form.to" /></label>
        <FishSpecFilter v-if="warehouse.type === 'FISH_MAW'" v-model:variety="form.variety" v-model:head-count="form.headCount" :products="products" />
        <label v-else class="field">商品<AppSelect v-model="form.productId" label="商品"
          :options="[{value: '', label: '全部商品'}, ...products.map(p => ({value: p.id, label: p.sku + ' · ' + p.name + (p.active ? '' : '（已停用）')}))]" /></label>
        <template v-if="tab === 'movements'">
          <label class="field">{{ slotLabel }}<AppSelect v-model="form.slotId" :label="slotLabel" :options="slotOptions" /></label>
          <label class="field">操作类型<AppSelect v-model="form.type" label="操作类型"
            :options="[{ value: '', label: '全部操作' }, ...Object.entries(typeLabel).filter(([value]) => showTransfers || value === 'IN' || value === 'OUT').map(([value, label]) => ({ value, label }))]" /></label>
        </template>
        <template v-else>
          <label class="field">来源{{ slotLabel }}<AppSelect v-model="form.sourceSlotId" :label="'来源' + slotLabel" :options="slotOptions" /></label>
          <label class="field">目标{{ slotLabel }}<AppSelect v-model="form.targetSlotId" :label="'目标' + slotLabel" :options="slotOptions" /></label>
        </template>
        <label v-if="!ownOnly" class="field">操作人<input v-model.trim="form.operator" placeholder="姓名或账号" maxlength="160"></label>
        <label class="field">单据号<input v-model.trim="form.document" placeholder="输入单据号" maxlength="160"></label>
      </div>
      <div class="filter-actions"><button class="primary" :disabled="loading"><Search :size="17" /> 查询</button></div>
    </form>
    <p v-if="error" class="error panel-message" role="alert">{{ error }}</p>
    <p v-else-if="loading" class="empty" role="status">正在读取记录…</p>
    <p v-else-if="!hasRows" class="empty">没有符合条件的记录。</p>
    <div v-else class="table-scroll">
      <table v-if="tab === 'movements'" role="table">
        <thead role="rowgroup"><tr role="row"><th scope="col">时间 / 单据号</th><th scope="col">操作</th><th scope="col">商品</th><th scope="col">{{ slotLabel }}</th><th scope="col">数量</th><th scope="col">库存变化</th><th scope="col">操作人 / 备注</th></tr></thead>
        <tbody role="rowgroup"><tr v-for="m in movements" :key="m.id" role="row">
          <td role="cell" data-label="时间 / 单据号">{{ dateTime(m.createdAt, zone) }}<small class="document" :title="m.documentNo">{{ m.documentNo }}</small></td>
          <td role="cell" data-label="操作"><span :class="['badge', m.type.toLowerCase()]">{{ typeLabel[m.type] }}</span></td>
          <td role="cell" data-label="商品">{{ m.product }}<small class="code">{{ m.sku }}</small></td>
          <td role="cell" :data-label="slotLabel" class="code">{{ m.slot }}</td>
          <td role="cell" data-label="数量">{{ m.afterQty > m.beforeQty ? '+' : '−' }}{{ formatWeight(m.quantity, m.unit) }} {{ displayUnit(m.unit) }}</td>
          <td role="cell" data-label="库存变化">{{ formatWeight(m.beforeQty, m.unit) }} → {{ formatWeight(m.afterQty, m.unit) }} {{ displayUnit(m.unit) }}</td>
          <td role="cell" data-label="操作人 / 备注">{{ m.operator }}<small class="break">{{ m.note }}</small></td>
        </tr></tbody>
      </table>
      <table v-else role="table">
        <thead role="rowgroup"><tr role="row"><th scope="col">时间 / 移库单号</th><th scope="col">商品</th><th scope="col">来源</th><th scope="col">目标</th><th scope="col">数量</th><th scope="col">操作人 / 备注</th></tr></thead>
        <tbody role="rowgroup"><tr v-for="m in transfers" :key="m.id" role="row">
          <td role="cell" data-label="时间 / 移库单号">{{ dateTime(m.createdAt, zone) }}<small class="document" :title="m.transferNo">{{ m.transferNo }}</small></td>
          <td role="cell" data-label="商品">{{ m.product }}<small class="code">{{ m.sku }}</small></td>
          <td role="cell" data-label="来源" class="code">{{ m.source }}</td>
          <td role="cell" data-label="目标" class="code">{{ m.target }}</td>
          <td role="cell" data-label="数量">{{ formatWeight(m.quantity, m.unit) }} {{ displayUnit(m.unit) }}</td>
          <td role="cell" data-label="操作人 / 备注">{{ m.operator }}<small class="break">{{ m.note }}</small></td>
        </tr></tbody>
      </table>
    </div>
    <div class="pagination">
      <button class="icon-button" :disabled="page === 1 || loading" aria-label="上一页" @click="paginate(-1)"><ChevronLeft :size="19" /></button>
      <span>第 {{ page }} 页</span>
      <button class="icon-button" :disabled="!more || loading" aria-label="下一页" @click="paginate(1)"><ChevronRight :size="19" /></button>
    </div>
  </section>
</template>
