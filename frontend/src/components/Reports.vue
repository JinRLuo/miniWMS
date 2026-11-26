<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Search } from 'lucide-vue-next'
import { api, errorMessage, params } from '../api'
import { type Product, type ProductStats, type Slot, type Trend, type Warehouse } from '../model'
import TrendChart from './TrendChart.vue'
import FishSpecFilter from './FishSpecFilter.vue'
import AppSelect from './AppSelect.vue'
import DateInput from './DateInput.vue'
import { useWeight } from '../weight'

const props = defineProps<{ slots: Slot[]; today: string; warehouse: Warehouse; units: string[]; catalog: Product[] }>()
const { formatWeight, displayUnit } = useWeight()
const defaultFrom = new Date(Date.parse(props.today + 'T00:00:00Z') - 6 * 86400000).toISOString().slice(0, 10)
const from = ref(defaultFrom)
const to = ref(props.today)
const unit = ref(props.units[0] || (props.warehouse.type === 'FISH_MAW' ? '斤' : '件'))
const period = ref('DAY')
const variety = ref('')
const headCount = ref<number | ''>('')
const applied = ref({ from: from.value, to: to.value, period: period.value, unit: unit.value, variety: '', headCount: '' as number | '' })
const scope = computed(() => !applied.value.variety ? '全部品种' : applied.value.variety + ' · ' + (applied.value.headCount === '' ? '全部头数' : applied.value.headCount + '头'))
const trend = ref<Trend[]>([])
const products = ref<ProductStats[]>([])
const expanded = ref<number>()
const error = ref('')
const busy = ref(false)
const ranked = computed(() => products.value.filter(p => p.outbound > 0 && p.unit === applied.value.unit).slice(0, 6))
const largest = computed(() => Math.max(1, ...ranked.value.map(p => p.outbound)))

async function load() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  const query = { from: from.value, to: to.value, period: period.value, unit: unit.value, variety: variety.value, headCount: headCount.value }
  try {
    const [series, stats] = await Promise.all([
      api<Trend[]>('/reports/trend' + params(query)),
      api<ProductStats[]>('/reports/products' + params({ from: query.from, to: query.to, variety: query.variety, headCount: query.headCount })),
    ])
    trend.value = series
    products.value = stats
    applied.value = query
    expanded.value = undefined
  } catch (e) { error.value = errorMessage(e) }
  finally { busy.value = false }
}

onMounted(load)
</script>

<template>
  <section class="panel">
    <form class="filter-form" @submit.prevent="load">
      <div class="filter-fields">
        <label class="field">开始日期<DateInput v-model="from" required /></label>
        <label class="field">结束日期<DateInput v-model="to" required /></label>
        <FishSpecFilter v-if="warehouse.type === 'FISH_MAW'" v-model:variety="variety" v-model:head-count="headCount" :products="catalog" />
        <label class="field">统计维度<AppSelect v-model="period" label="统计维度" :options="[{value: 'DAY', label: '按日'}, {value: 'WEEK', label: '按周'}, {value: 'MONTH', label: '按月'}]" /></label>
        <label class="field">趋势与排行单位<AppSelect v-model="unit" label="趋势与排行单位" :options="(units.length ? units : [unit]).map(u => ({ value: u, label: displayUnit(u) }))" /></label>
      </div>
      <div class="filter-actions"><button class="primary" :disabled="busy"><Search :size="17" /> {{ busy ? '计算中…' : '查看统计' }}</button></div>
    </form>
    <p v-if="warehouse.type === 'FISH_MAW'" class="report-scope" aria-live="polite">当前统计：{{ scope }}</p>
    <p v-if="error" class="error panel-message" role="alert">{{ error }}</p>
  </section>
  <div class="dashboard-grid">
    <section class="panel">
      <div class="panel-head"><div><h2>出入库趋势</h2><p>按有效出入库流水统计 · {{ displayUnit(applied.unit) }}</p></div><div class="legend"><span>入库</span><span>出库</span></div></div>
      <TrendChart :data="trend" :unit="applied.unit" />
    </section>
    <section class="panel">
      <div class="panel-head"><div><h2>商品出库排行</h2><p>所选期间</p></div></div>
      <div v-if="ranked.length" class="rank-list">
        <div v-for="product in ranked" :key="product.id">
          <div><span>{{ product.name }}</span><strong>{{ formatWeight(product.outbound, product.unit) }} {{ displayUnit(product.unit) }}</strong></div>
          <div class="bar-track"><i :style="{ width: (100 * product.outbound / largest) + '%' }"></i></div>
        </div>
      </div>
      <p v-else class="empty">所选期间没有商品出库。</p>
    </section>
  </div>
  <section class="panel">
    <div class="panel-head"><h2>商品出入库汇总</h2></div>
    <div class="table-scroll">
      <table v-if="products.length">
        <thead><tr><th>商品</th><th>期间入库</th><th>期间出库</th><th>净入库</th><th>当前库存</th><th>库位</th></tr></thead>
        <tbody><template v-for="p in products" :key="p.id">
          <tr><td>{{ p.name }}<small class="code">{{ p.sku }}</small></td><td>{{ formatWeight(p.inbound, p.unit) }} {{ displayUnit(p.unit) }}</td>
            <td>{{ formatWeight(p.outbound, p.unit) }} {{ displayUnit(p.unit) }}</td><td>{{ formatWeight(p.inbound - p.outbound, p.unit) }} {{ displayUnit(p.unit) }}</td><td>{{ formatWeight(p.quantity, p.unit) }} {{ displayUnit(p.unit) }}</td>
            <td><button class="text-button" :aria-expanded="expanded === p.id" @click="expanded = expanded === p.id ? undefined : p.id">库位分布</button></td></tr>
          <tr v-if="expanded === p.id" class="distribution"><td colspan="6">
            <div v-for="slot in slots.filter(s => s.productId === p.id)" :key="slot.id"><span class="code">{{ slot.code }}</span> {{ formatWeight(slot.quantity, p.unit) }} {{ displayUnit(p.unit) }}</div>
            <span v-if="!slots.some(s => s.productId === p.id)" class="muted">暂无绑定库位。</span>
          </td></tr>
        </template></tbody>
      </table>
      <p v-else class="empty">没有符合筛选条件的商品。</p>
    </div>
  </section>
</template>
