<script setup lang="ts" generic="T extends string | number | null">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch, type CSSProperties } from 'vue'
import { Check, ChevronDown, Search } from 'lucide-vue-next'

defineOptions({ inheritAttrs: false })
const props = defineProps<{
  modelValue: T
  options: readonly { value: NoInfer<T>; label: string; description?: string; disabled?: boolean }[]
  label: string
  id?: string
  name?: string
  required?: boolean
  disabled?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: T] }>()
const uid = useId()
const trigger = ref<HTMLButtonElement>()
const popup = ref<HTMLDivElement>()
const destination = ref<HTMLElement | string>('body')
const open = ref(false)
const search = ref('')
const active = ref(-1)
const invalid = ref(false)
const fieldsetDisabled = ref(false)
const position = ref<CSSProperties>({})
const disabled = computed(() => !!props.disabled || fieldsetDisabled.value)
const selected = computed(() => props.options.find(option => option.value === props.modelValue))
const searchable = computed(() => props.options.length > 8)
const filtered = computed(() => props.options.map((option, index) => ({ option, index }))
  .filter(({ option }) => (option.label + ' ' + (option.description || '')).toLocaleLowerCase().includes(search.value.trim().toLocaleLowerCase())))
const enabled = computed(() => filtered.value.filter(({ option }) => !option.disabled))
const proxyValue = computed(() => encode(props.options.findIndex(option => option.value === props.modelValue)))
let observer: MutationObserver | undefined
let typeahead = ''
let typeaheadTimer: ReturnType<typeof setTimeout> | undefined

function encode(index: number) {
  const value = props.options[index]?.value
  return index < 0 || value === '' || value === null ? '' : 'option-' + index
}
function place() {
  if (!open.value || !trigger.value || !popup.value) return
  const rect = trigger.value.getBoundingClientRect()
  const viewport = window.visualViewport
  const leftEdge = viewport?.offsetLeft || 0
  const topEdge = viewport?.offsetTop || 0
  const width = viewport?.width || window.innerWidth
  const height = viewport?.height || window.innerHeight
  if (rect.bottom < topEdge || rect.top > topEdge + height) { close(); return }
  const below = topEdge + height - rect.bottom - 16
  const above = rect.top - topEdge - 16
  const upwards = below < Math.min(240, popup.value.scrollHeight) && above > below
  const menuWidth = Math.min(Math.max(rect.width, 210), width - 24)
  position.value = {
    width: menuWidth + 'px',
    left: Math.max(leftEdge + 12, Math.min(rect.left, leftEdge + width - menuWidth - 12)) + 'px',
    top: upwards ? 'auto' : rect.bottom + 7 + 'px',
    bottom: upwards ? window.innerHeight - rect.top + 7 + 'px' : 'auto',
    maxHeight: Math.max(80, Math.min(340, upwards ? above : below)) + 'px',
  }
}
async function reveal(direction = 1) {
  if (disabled.value || trigger.value?.matches(':disabled')) return
  destination.value = trigger.value?.closest('dialog') || 'body'
  search.value = ''
  open.value = true
  const current = props.options.findIndex(option => option.value === props.modelValue && !option.disabled)
  active.value = current >= 0 ? current : (direction === 1 ? enabled.value[0]?.index : enabled.value.at(-1)?.index) ?? -1
  await nextTick()
  if (!open.value || !popup.value) return
  // The browser top layer keeps menus above dialogs and outside clipped panels.
  popup.value.showPopover?.()
  place()
  await nextTick()
  scrollActive()
}
function close(restoreFocus = false) {
  if (!open.value) return
  popup.value?.hidePopover?.()
  open.value = false
  if (restoreFocus) trigger.value?.focus({ preventScroll: true })
}
function choose(index: number) {
  const option = props.options[index]
  if (!option || option.disabled || disabled.value || trigger.value?.matches(':disabled')) return
  emit('update:modelValue', option.value)
  invalid.value = false
  close(true)
}
function scrollActive() {
  popup.value?.querySelector<HTMLElement>(`[data-index="${active.value}"]`)?.scrollIntoView({ block: 'nearest' })
}
async function move(direction: number) {
  const index = enabled.value.findIndex(item => item.index === active.value)
  active.value = enabled.value[(index + direction + enabled.value.length) % enabled.value.length]?.index ?? -1
  await nextTick()
  scrollActive()
}
function keydown(event: KeyboardEvent) {
  if (event.key === 'Tab') { close(true); return }
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    if (!open.value) void reveal(event.key === 'ArrowDown' ? 1 : -1)
    else void move(event.key === 'ArrowDown' ? 1 : -1)
  } else if ((event.key === 'Enter' || event.key === ' ') && event.target === trigger.value) {
    event.preventDefault()
    if (open.value) choose(active.value)
    else void reveal()
  } else if (event.key === 'Enter' && open.value) {
    event.preventDefault()
    choose(active.value)
  } else if (open.value && (event.key === 'Home' || event.key === 'End') && event.target === trigger.value) {
    event.preventDefault()
    active.value = (event.key === 'Home' ? enabled.value[0] : enabled.value.at(-1))?.index ?? -1
    scrollActive()
  } else if (event.target === trigger.value && event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
    typeahead += event.key.toLocaleLowerCase()
    clearTimeout(typeaheadTimer)
    typeaheadTimer = setTimeout(() => { typeahead = '' }, 700)
    const match = props.options.findIndex(option => !option.disabled && option.label.toLocaleLowerCase().startsWith(typeahead))
    if (match >= 0) {
      if (open.value) { active.value = match; scrollActive() }
      else emit('update:modelValue', props.options[match]!.value)
    }
  }
}
function escape(event: KeyboardEvent) {
  if (open.value && event.key === 'Escape') {
    event.preventDefault()
    event.stopImmediatePropagation()
    close(true)
  }
}
function outside(event: PointerEvent) {
  if (!trigger.value?.contains(event.target as Node) && !popup.value?.contains(event.target as Node)) close()
}
function scroll(event: Event) {
  if (!popup.value?.contains(event.target as Node)) place()
}
function nativeChange(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  choose(props.options.findIndex((_, index) => encode(index) === value))
}
watch(search, async () => { active.value = enabled.value[0]?.index ?? -1; await nextTick(); place(); scrollActive() })
watch(disabled, value => { if (value) close() })
watch(() => props.modelValue, () => { invalid.value = false })
watch(() => props.options, () => { if (open.value) close() })
onMounted(() => {
  const fieldset = trigger.value?.closest('fieldset')
  if (fieldset) {
    const sync = () => { fieldsetDisabled.value = fieldset.disabled }
    sync()
    observer = new MutationObserver(sync)
    observer.observe(fieldset, { attributes: true, attributeFilter: ['disabled'] })
  }
  document.addEventListener('pointerdown', outside, true)
  document.addEventListener('keydown', escape, true)
  window.addEventListener('resize', place)
  window.addEventListener('scroll', scroll, true)
  window.visualViewport?.addEventListener('resize', place)
  window.visualViewport?.addEventListener('scroll', place)
})
onBeforeUnmount(() => {
  close()
  observer?.disconnect()
  clearTimeout(typeaheadTimer)
  document.removeEventListener('pointerdown', outside, true)
  document.removeEventListener('keydown', escape, true)
  window.removeEventListener('resize', place)
  window.removeEventListener('scroll', scroll, true)
  window.visualViewport?.removeEventListener('resize', place)
  window.visualViewport?.removeEventListener('scroll', place)
})
</script>

<template>
  <div v-bind="$attrs" :class="['app-select', { 'is-open': open, 'is-disabled': disabled, 'is-invalid': invalid }]">
    <button :id="id" ref="trigger" type="button" class="select-trigger" role="combobox" :aria-label="label"
      :aria-expanded="open" aria-haspopup="listbox" :aria-controls="uid + '-list'" :aria-required="required || undefined"
      :aria-invalid="invalid || undefined" :aria-activedescendant="open && active >= 0 ? uid + '-option-' + active : undefined"
      :disabled="disabled" @click="open ? close() : reveal()" @keydown="keydown">
      <span class="select-value" :title="selected?.label">{{ selected?.label || '请选择' }}</span>
      <ChevronDown class="select-chevron" :size="18" :stroke-width="1.75" aria-hidden="true" />
    </button>
    <select class="select-native" :value="proxyValue" :name="name" :required="required" :disabled="disabled" tabindex="-1" aria-hidden="true"
      @change="nativeChange" @invalid.prevent="invalid = true; trigger?.focus(); reveal()">
      <option v-for="(option, index) in options" :key="index" :value="encode(index)" :disabled="option.disabled">{{ option.label }}</option>
    </select>
  </div>
  <Teleport :to="destination">
    <div v-if="open" ref="popup" class="select-popup" popover="manual" :style="position" @keydown="keydown">
      <div v-if="searchable" class="select-search"><Search :size="16" aria-hidden="true" />
        <input v-model="search" :aria-label="'搜索' + label" placeholder="输入关键词搜索" autocomplete="off" role="searchbox"
          :aria-controls="uid + '-list'" :aria-activedescendant="active >= 0 ? uid + '-option-' + active : undefined">
      </div>
      <div :id="uid + '-list'" class="select-options" role="listbox" :aria-label="label">
        <button v-for="{ option, index } in filtered" :id="uid + '-option-' + index" :key="index" type="button" role="option" tabindex="-1"
          :data-index="index" :aria-selected="option.value === modelValue" :disabled="option.disabled"
          :class="['select-option', { 'is-active': active === index }]" @pointermove="active = index" @mousedown.prevent @click="choose(index)">
          <span><span class="select-option-label">{{ option.label }}</span><small v-if="option.description">{{ option.description }}</small></span>
          <Check v-if="option.value === modelValue" :size="17" :stroke-width="2" aria-hidden="true" />
        </button>
        <p v-if="!filtered.length" class="select-empty">没有匹配的选项</p>
      </div>
    </div>
  </Teleport>
</template>
