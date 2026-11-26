<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { init, use, type EChartsType } from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { Trend } from '../model'
import { useWeight } from '../weight'

use([BarChart, GridComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{ data: Trend[]; unit?: string }>()
const { preference, displayWeight, displayUnit } = useWeight()
const element = ref<HTMLDivElement>()
let chart: EChartsType | undefined
let observer: ResizeObserver | undefined

function render() {
  chart?.setOption({
    animationDuration: 300,
    grid: { top: 12, bottom: 24, left: 12, right: 14, containLabel: true },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, renderMode: 'richText' },
    xAxis: {
      type: 'category', data: props.data.map(value => value.date.slice(5).replace('-', '/')),
      axisTick: { show: false }, axisLabel: { color: '#65766f' }, axisLine: { lineStyle: { color: '#dae4df' } },
    },
    yAxis: {
      type: 'value', minInterval: props.unit === '斤' ? (preference.value === 'GRAM' ? 0.5 : 0.001) : 1, min: 0,
      max: (range: { max: number }) => range.max > 0 ? null : 10,
      axisLabel: { color: '#65766f' }, splitLine: { lineStyle: { color: '#e6eeea', type: 'dashed' } },
    },
    series: [
      { name: '入库（' + (displayUnit(props.unit) || '数量') + '）', type: 'bar', data: props.data.map(value => displayWeight(value.inbound, props.unit)), barMaxWidth: 28,
        itemStyle: { color: '#178066', borderRadius: [4, 4, 0, 0] } },
      { name: '出库（' + (displayUnit(props.unit) || '数量') + '）', type: 'bar', data: props.data.map(value => displayWeight(value.outbound, props.unit)), barMaxWidth: 28,
        itemStyle: { color: '#b8d9cc', borderRadius: [4, 4, 0, 0] } },
    ],
  })
}

onMounted(() => {
  if (!element.value) return
  chart = init(element.value)
  render()
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(element.value)
})
watch(() => [props.data, props.unit, preference.value], render)
onBeforeUnmount(() => { observer?.disconnect(); chart?.dispose() })
</script>

<template><div ref="element" class="trend-chart" role="img" aria-label="出入库数量趋势柱状图"></div></template>
