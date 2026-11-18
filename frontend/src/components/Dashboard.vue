<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Archive, ArrowDownLeft, ArrowRight, ArrowUpRight, ArrowLeftRight, Boxes, MapPin, ScanLine, TriangleAlert } from 'lucide-vue-next'
import { api, errorMessage } from '../api'
import { dateTime, number, typeLabel, type Movement, type Page, type Summary, type Trend } from '../model'
import TrendChart from './TrendChart.vue'

const props = defineProps<{ summary: Summary; mobile: boolean }>()
defineEmits<{ navigate: [page: string]; action: [type: 'IN' | 'OUT' | 'TRANSFER'] }>()
const trend = ref<Trend[]>([])
const recent = ref<Movement[]>([])
const error = ref('')
const metrics = computed(() => [
  { label: '当前总库存', value: props.summary.quantity, icon: Archive, color: 'green' },
  { label: '启用商品', value: props.summary.products, icon: Boxes, color: 'blue' },
  { label: '今日入库', value: props.summary.todayInbound, icon: ArrowDownLeft, color: 'green' },
  { label: '今日出库', value: props.summary.todayOutbound, icon: ArrowUpRight, color: 'sand' },
])
onMounted(async () => {
  try {
    const [series, history] = await Promise.all([
      api<Trend[]>('/reports/trend'), api<Page<Movement>>('/stock/movements' + (props.mobile ? '?mine=true' : '')),
    ])
    trend.value = series
    recent.value = history.rows.slice(0, 6)
  } catch (e) { error.value = errorMessage(e) }
})
</script>

<template>
  <section class="hero">
    <div>
      <p class="hero-meta"><MapPin :size="15" /> {{ summary.today }}</p>
      <h2>今日仓储概览</h2>
      <button class="hero-button" @click="$emit('action', 'IN')">
        <component :is="mobile ? ScanLine : ArrowDownLeft" :size="18" />
        {{ mobile ? '开始扫码' : '办理入库' }} <ArrowRight :size="16" />
      </button>
    </div>
    <div class="hero-graphic" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i><i></i></div>
  </section>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
  <div class="metrics">
    <article v-for="metric in metrics" :key="metric.label" class="panel metric">
      <span :class="['metric-icon', metric.color]"><component :is="metric.icon" :size="19" /></span>
      <span>{{ metric.label }}</span><strong>{{ number(metric.value) }}</strong>
    </article>
  </div>
  <div class="dashboard-grid">
    <section class="panel">
      <div class="panel-head">
        <div><h2>出入库趋势</h2><p>最近 7 天 · 按仓库时区</p></div>
        <div class="legend"><span>入库</span><span>出库</span></div>
      </div>
      <TrendChart :data="trend" />
    </section>
    <section class="panel">
      <div class="panel-head"><div><h2>快速操作</h2></div></div>
      <div class="quick-list">
        <button v-for="item in [{ key: 'IN', label: '商品入库', icon: ArrowDownLeft }, { key: 'OUT', label: '商品出库', icon: ArrowUpRight }, { key: 'TRANSFER', label: '库位移库', icon: ArrowLeftRight }] as const"
          :key="item.key" @click="$emit('action', item.key)">
          <span class="metric-icon green"><component :is="item.icon" :size="18" /></span>{{ item.label }}<ArrowRight :size="17" />
        </button>
      </div>
      <button v-if="summary.lowStock" class="warning-link" @click="$emit('navigate', 'inventory')"><TriangleAlert :size="17" /> {{ summary.lowStock }} 个商品低于预警值</button>
    </section>
  </div>
  <section class="panel">
    <div class="panel-head">
      <h2>最近操作</h2><button class="text-button" @click="$emit('navigate', 'history')">查看记录 <ArrowRight :size="16" /></button>
    </div>
    <div class="table-scroll">
      <table v-if="recent.length" role="table">
        <thead role="rowgroup"><tr role="row"><th scope="col">操作</th><th scope="col">商品</th><th scope="col">货架层</th><th scope="col">数量</th><th scope="col">操作人</th><th scope="col">时间</th></tr></thead>
        <tbody role="rowgroup"><tr v-for="row in recent" :key="row.id" role="row">
          <td role="cell"><span :class="['badge', row.type.toLowerCase()]">{{ typeLabel[row.type] }}</span></td>
          <td role="cell" data-label="商品">{{ row.product }}<small>{{ row.sku }}</small></td>
          <td role="cell" data-label="货架层" class="code">{{ row.slot }}</td>
          <td role="cell" data-label="数量">{{ row.afterQty > row.beforeQty ? '+' : '−' }}{{ row.quantity }}</td>
          <td role="cell" data-label="操作人">{{ row.operator }}</td>
          <td role="cell" data-label="时间">{{ dateTime(row.createdAt, summary.zone) }}</td>
        </tr></tbody>
      </table>
      <p v-else class="empty">暂无库存操作。</p>
    </div>
  </section>
</template>
