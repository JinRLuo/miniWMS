<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ChevronLeft, ChevronRight, Download, Search } from 'lucide-vue-next'
import { api, errorMessage, params } from '../api'
import { dateTime, typeLabel, type Movement, type Page, type Transfer } from '../model'

const props = defineProps<{ zone: string; mobile: boolean; initialSlot: string }>()
const tab = ref<'movements' | 'transfers'>('movements')
const form = ref({ from: '', to: '', product: '', shelf: props.initialSlot, type: '', operator: '', document: '', source: '', target: '' })
const applied = ref({ ...form.value })
const movements = ref<Movement[]>([])
const transfers = ref<Transfer[]>([])
const page = ref(1)
const more = ref(false)
const loading = ref(false)
const error = ref('')
let loadVersion = 0
const filters = computed(() => params({ ...applied.value, mine: props.mobile ? true : undefined }))
const exportLink = computed(() => '/api/stock/movements.csv' + filters.value)
const hasRows = computed(() => tab.value === 'movements' ? movements.value.length > 0 : transfers.value.length > 0)

async function load() {
  const version = ++loadVersion
  loading.value = true
  error.value = ''
  try {
    const query = params({ ...applied.value, mine: props.mobile ? true : undefined, page: page.value })
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
watch(() => props.initialSlot, slot => { form.value.shelf = slot; choose('movements') })
onMounted(load)
</script>

<template>
  <section class="panel">
    <div class="toolbar">
      <div class="tabs compact">
        <button :class="{ selected: tab === 'movements' }" @click="choose('movements')">库存流水</button>
        <button :class="{ selected: tab === 'transfers' }" @click="choose('transfers')">移库单</button>
      </div>
      <a v-if="tab === 'movements' && !mobile" class="secondary toolbar-end" :href="exportLink"><Download :size="17" /> 导出 CSV</a>
    </div>
    <form class="filter-grid" @submit.prevent="apply">
      <label class="field">开始日期<input v-model="form.from" type="date"></label>
      <label class="field">结束日期<input v-model="form.to" type="date"></label>
      <label class="field">商品<input v-model.trim="form.product" placeholder="名称 / SKU / 条码" maxlength="160"></label>
      <template v-if="tab === 'movements'">
        <label class="field">货架层<input v-model.trim="form.shelf" placeholder="如 S01-4" maxlength="160"></label>
        <label class="field">操作类型<select v-model="form.type"><option value="">全部</option>
          <option v-for="(label, key) in typeLabel" :key="key" :value="key">{{ label }}</option></select></label>
      </template>
      <template v-else>
        <label class="field">来源货架层<input v-model.trim="form.source" placeholder="如 S01-4" maxlength="160"></label>
        <label class="field">目标货架层<input v-model.trim="form.target" placeholder="如 S02-2" maxlength="160"></label>
      </template>
      <label v-if="!mobile" class="field">操作人<input v-model.trim="form.operator" placeholder="姓名或账号" maxlength="160"></label>
      <label class="field">单据号<input v-model.trim="form.document" maxlength="160"></label>
      <button class="primary" :disabled="loading"><Search :size="17" /> 查询</button>
    </form>
    <p v-if="error" class="error panel-message" role="alert">{{ error }}</p>
    <p v-else-if="loading" class="empty" role="status">正在读取记录…</p>
    <p v-else-if="!hasRows" class="empty">没有符合条件的记录。</p>
    <div v-else class="table-scroll">
      <table v-if="tab === 'movements'">
        <thead><tr><th>时间 / 单据号</th><th>操作</th><th>商品</th><th>货架层</th><th>数量</th><th>库存变化</th><th>操作人 / 备注</th></tr></thead>
        <tbody><tr v-for="m in movements" :key="m.id">
          <td>{{ dateTime(m.createdAt, zone) }}<small class="document" :title="m.documentNo">{{ m.documentNo }}</small></td>
          <td><span :class="['badge', m.type.toLowerCase()]">{{ typeLabel[m.type] }}</span></td>
          <td>{{ m.product }}<small class="code">{{ m.sku }}</small></td><td class="code">{{ m.slot }}</td>
          <td>{{ m.afterQty > m.beforeQty ? '+' : '−' }}{{ m.quantity }}</td><td>{{ m.beforeQty }} → {{ m.afterQty }}</td>
          <td>{{ m.operator }}<small class="break">{{ m.note }}</small></td>
        </tr></tbody>
      </table>
      <table v-else>
        <thead><tr><th>时间 / 移库单号</th><th>商品</th><th>来源</th><th>目标</th><th>数量</th><th>操作人 / 备注</th></tr></thead>
        <tbody><tr v-for="m in transfers" :key="m.id">
          <td>{{ dateTime(m.createdAt, zone) }}<small class="document" :title="m.transferNo">{{ m.transferNo }}</small></td>
          <td>{{ m.product }}<small class="code">{{ m.sku }}</small></td><td class="code">{{ m.source }}</td><td class="code">{{ m.target }}</td>
          <td>{{ m.quantity }}</td><td>{{ m.operator }}<small class="break">{{ m.note }}</small></td>
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
