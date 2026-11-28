<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Archive, ArrowDownLeft, ArrowRight, ArrowUpRight, ArrowLeftRight, Boxes, MapPin, ScanLine, TriangleAlert } from 'lucide-vue-next'
import { api, errorMessage } from '../api'
import { dateTime, typeLabel, type Movement, type Page, type Summary, type Trend, type Warehouse } from '../model'
import TrendChart from './TrendChart.vue'
import AppSelect from './AppSelect.vue'
import { useWeight } from '../weight'
import { showTransfers } from '../features'

const props = defineProps<{ summary?: Summary; restricted: boolean; today: string; zone: string; mobile: boolean; warehouse: Warehouse }>()
const { formatWeight, displayUnit } = useWeight()
const quickActions = [
  { key: 'IN', label: '商品入库', icon: ArrowDownLeft },
  { key: 'OUT', label: '商品出库', icon: ArrowUpRight },
  { key: 'TRANSFER', label: '库位移库', icon: ArrowLeftRight },
] as const
defineEmits<{ navigate: [page: string]; action: [type: 'IN' | 'OUT' | 'TRANSFER'] }>()
const trend = ref<Trend[]>([])
const recent = ref<Movement[]>([])
const error = ref('')
const unit = ref(props.summary?.units[0]?.unit || (props.warehouse.type === 'FISH_MAW' ? '斤' : '件'))
const unitText = (field: 'quantity' | 'inbound' | 'outbound') => props.summary?.units.map(u => formatWeight(u[field], u.unit) + ' ' + displayUnit(u.unit)).join(' / ') || '0'
const metrics = computed(() => [
  { label: '当前总库存', value: unitText('quantity'), icon: Archive, color: 'green' },
  { label: '启用商品', value: String(props.summary?.products ?? 0), icon: Boxes, color: 'blue' },
  { label: '今日入库', value: unitText('inbound'), icon: ArrowDownLeft, color: 'green' },
  { label: '今日出库', value: unitText('outbound'), icon: ArrowUpRight, color: 'sand' },
])
onMounted(async () => {
  try {
    if (props.restricted) {
      recent.value = (await api<Page<Movement>>('/stock/movements?mine=true')).rows.slice(0, 6)
      return
    }
    const [series, history] = await Promise.all([
      api<Trend[]>('/reports/trend?unit=' + encodeURIComponent(unit.value)), api<Page<Movement>>('/stock/movements'),
    ])
    trend.value = series
    recent.value = history.rows.slice(0, 6)
  } catch (e) { error.value = errorMessage(e) }
})
watch(unit, async value => {
  if (props.restricted) return
  try { trend.value = await api<Trend[]>('/reports/trend?unit=' + encodeURIComponent(value)) }
  catch (e) { error.value = errorMessage(e) }
})
</script>

<template>
  <section class="hero">
    <div>
      <p class="hero-meta"><MapPin :size="15" /> {{ today }}</p>
      <h2>{{ restricted ? '仓库工作台' : '今日仓储概览' }}</h2>
      <button class="hero-button" @click="$emit('action', 'IN')">
        <component :is="mobile ? ScanLine : ArrowDownLeft" :size="18" />
        {{ mobile ? '开始扫码' : '办理入库' }} <ArrowRight :size="16" />
      </button>
    </div>
    <div class="hero-graphic" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i><i></i></div>
  </section>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
  <div v-if="!restricted && summary" class="metrics">
    <article v-for="metric in metrics" :key="metric.label" class="panel metric">
      <span :class="['metric-icon', metric.color]"><component :is="metric.icon" :size="19" /></span>
      <span>{{ metric.label }}</span><strong>{{ metric.value }}</strong>
    </article>
  </div>
  <div :class="{ 'dashboard-grid': !restricted }">
    <section v-if="!restricted && summary" class="panel">
      <div class="panel-head">
        <div><h2>出入库趋势</h2><p>最近 7 天 · {{ displayUnit(unit) }} · 按仓库时区</p></div>
        <AppSelect v-if="summary.units.length > 1" v-model="unit" class="unit-select" label="趋势计量单位" :options="summary.units.map(u => ({ value: u.unit, label: displayUnit(u.unit) }))" /><div class="legend"><span>入库</span><span>出库</span></div>
      </div>
      <TrendChart :data="trend" :unit="unit" />
    </section>
    <section class="panel">
      <div class="panel-head"><div><h2>快速操作</h2></div></div>
      <div class="quick-list">
        <button v-for="item in quickActions.filter(item => (showTransfers && !restricted) || item.key !== 'TRANSFER')"
          :key="item.key" @click="$emit('action', item.key)">
          <span class="metric-icon green"><component :is="item.icon" :size="18" /></span>{{ item.label }}<ArrowRight :size="17" />
        </button>
      </div>
      <button v-if="!restricted && summary?.lowStock" class="warning-link" @click="$emit('navigate', 'inventory')"><TriangleAlert :size="17" /> {{ summary.lowStock }} 个商品低于预警值</button>
    </section>
  </div>
  <section class="panel">
    <div class="panel-head">
      <div><h2>{{ restricted ? '我的最近操作' : '最近操作' }}</h2><p>{{ restricted ? '本周本人操作的出入库记录。' : '本周所有人的最近出入库操作。' }}</p></div><button class="text-button" @click="$emit('navigate', 'history')">查看记录 <ArrowRight :size="16" /></button>
    </div>
    <div class="table-scroll">
      <table v-if="recent.length" role="table">
        <thead role="rowgroup"><tr role="row"><th scope="col">操作</th><th scope="col">商品</th><th scope="col">{{ warehouse.type === 'FISH_MAW' ? '格子' : '货架层' }}</th><th scope="col">数量</th><th scope="col">操作人</th><th scope="col">时间</th></tr></thead>
        <tbody role="rowgroup"><tr v-for="row in recent" :key="row.id" role="row">
          <td role="cell"><span :class="['badge', row.type.toLowerCase()]">{{ typeLabel[row.type] }}</span></td>
          <td role="cell" data-label="商品">{{ row.product }}<small>{{ row.sku }}</small></td>
          <td role="cell" data-label="货架层" class="code">{{ row.slot }}</td>
          <td role="cell" data-label="数量">{{ row.afterQty > row.beforeQty ? '+' : '−' }}{{ formatWeight(row.quantity, row.unit) }} {{ displayUnit(row.unit) }}</td>
          <td role="cell" data-label="操作人">{{ row.operator }}</td>
          <td role="cell" data-label="时间">{{ dateTime(row.createdAt, zone) }}</td>
        </tr></tbody>
      </table>
      <p v-else class="empty">本周暂无库存操作。</p>
    </div>
  </section>
</template>
