<script setup lang="ts">
import { CalendarDays } from 'lucide-vue-next'
defineOptions({ inheritAttrs: false })
defineProps<{ modelValue: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
</script>

<template>
  <span class="date-control">
    <input v-bind="$attrs" type="date" :value="modelValue" @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)">
    <CalendarDays :size="18" :stroke-width="1.75" aria-hidden="true" />
  </span>
</template>
