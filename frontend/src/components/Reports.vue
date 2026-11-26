<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Search } from 'lucide-vue-next'
import { api, errorMessage, params } from '../api'
import { number, type ProductStats, type Slot, type Trend, type Warehouse } from '../model'
import TrendChart from './TrendChart.vue'

const props = defineProps<{ slots: Slot[]; today: string; warehouse: Warehouse; units: string[] }>()
const defaultFrom = new Date(Date.parse(props.today + 'T00:00:00Z') - 6 * 86400000).toISOString().slice(0, 10)
const from = ref(defaultFrom)
const to = ref(props.today)
const unit = ref(props.units[0] || (props.warehouse.type === 'FISH_MAW' ? '斤' : '件'))
const period = ref('DAY')
const trend = ref<Trend[]>([])
const products = ref<ProductStats[]>([])
const expanded = ref<number>()
const error = ref('')
const busy = ref(false)
const ranked = computed(() => products.value.filter(p => p.outbound > 0 && p.unit === unit.value).slice(0, 6))
const largest = computed(() => Math.max(1, ...ranked.value.map(p => p.outbound)))

async function load() {
  busy.value = true
  error.value = ''
  try {
    const [series, stats] = await Promise.all([
      api<Trend[]>('/reports/trend' + params({ from: from.value, to: to.value, period: period.value, unit: unit.value })),
      api<ProductStats[]>('/reports/products' + params({ from: from.value, to: to.value })),
    ])
    trend.value = series
    products.value = stats
  } catch (e) { error.value = errorMessage(e) }
  finally { busy.value = false }
}

onMounted(load)
</script>

<template>
  <section class="panel">
    <form class="filter-grid" @submit.prevent="load">
      <label class="field">开始日期<input v-model="from" type="date" required></label>
      <label class="field">结束日期<input v-model="to" type="date" required></label>
      <label class="field">统计维度<select v-model="period"><option value="DAY">按日</option><option value="WEEK">按周</option><option value="MONTH">按月</option></select></label>
      <label class="field">趋势与排行单位<select v-model="unit"><option v-for="u in units.length ? units : [unit]" :key="u">{{ u }}</option></select></label>
      <button class="primary" :disabled="busy"><Search :size="17" /> {{ busy ? '计算中…' : '查看统计' }}</button>
    </form>
    <p v-if="error" class="error panel-message" role="alert">{{ error }}</p>
  </section>
  <div class="dashboard-grid">
    <section class="panel">
      <div class="panel-head"><div><h2>出入库趋势</h2><p>按有效出入库流水统计 · {{ unit }}</p></div><div class="legend"><span>入库</span><span>出库</span></div></div>
      <TrendChart :data="trend" :unit="unit" />
    </section>
    <section class="panel">
      <div class="panel-head"><div><h2>商品出库排行</h2><p>所选期间</p></div></div>
      <div v-if="ranked.length" class="rank-list">
        <div v-for="product in ranked" :key="product.id">
          <div><span>{{ product.name }}</span><strong>{{ number(product.outbound) }} {{ product.unit }}</strong></div>
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
          <tr><td>{{ p.name }}<small class="code">{{ p.sku }}</small></td><td>{{ number(p.inbound) }} {{ p.unit }}</td>
            <td>{{ number(p.outbound) }} {{ p.unit }}</td><td>{{ number(p.inbound - p.outbound) }} {{ p.unit }}</td><td>{{ number(p.quantity) }} {{ p.unit }}</td>
            <td><button class="text-button" :aria-expanded="expanded === p.id" @click="expanded = expanded === p.id ? undefined : p.id">库位分布</button></td></tr>
          <tr v-if="expanded === p.id" class="distribution"><td colspan="6">
            <div v-for="slot in slots.filter(s => s.productId === p.id)" :key="slot.id"><span class="code">{{ slot.code }}</span> {{ number(slot.quantity) }} {{ p.unit }}</div>
            <span v-if="!slots.some(s => s.productId === p.id)" class="muted">暂无绑定库位。</span>
          </td></tr>
        </template></tbody>
      </table>
      <p v-else class="empty">暂无商品可统计。</p>
    </div>
  </section>
</template>
