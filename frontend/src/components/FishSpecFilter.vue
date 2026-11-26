<script setup lang="ts">
import { computed } from 'vue'
import type { Product } from '../model'
import AppSelect from './AppSelect.vue'

const props = defineProps<{ products: Product[]; variety: string; headCount: number | '' }>()
const emit = defineEmits<{ 'update:variety': [value: string]; 'update:headCount': [value: number | ''] }>()
const varieties = computed(() => [...new Set(props.products.map(product => product.variety).filter((value): value is string => !!value))]
  .sort((a, b) => a.localeCompare(b, 'zh-CN')))
const heads = computed(() => [...new Set(props.products.filter(product => product.variety === props.variety)
  .map(product => product.headCount).filter((value): value is number => value !== null))].sort((a, b) => a - b))
function selectVariety(value: string) {
  emit('update:headCount', '')
  emit('update:variety', value)
}
</script>

<template>
  <label class="field">品种<AppSelect :model-value="variety" label="品种"
    :options="[{ value: '', label: '全部品种' }, ...varieties.map(name => ({ value: name, label: name }))]" @update:model-value="selectVariety" /></label>
  <label class="field">头数<AppSelect :model-value="headCount" :disabled="!variety" label="头数"
    :options="[{ value: '', label: variety ? '全部头数' : '先选择品种' }, ...heads.map(head => ({ value: head, label: head + '头' }))]"
    @update:model-value="emit('update:headCount', $event)" /></label>
</template>
