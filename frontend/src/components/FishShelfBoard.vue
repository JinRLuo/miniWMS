<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { ArrowDownLeft, ArrowUpRight, Boxes, ClipboardList, Pencil, Printer, QrCode, X } from 'lucide-vue-next'
import { decimal, number, type Shelf, type Slot } from '../model'
import { useWeight } from '../weight'
const { unitLabel, formatWeight } = useWeight()

const props = defineProps<{
  shelves: Shelf[]; slots: Slot[]; matchingSlots: Slot[]; filtering: boolean; admin: boolean
}>()
const emit = defineEmits<{
  editShelf: [shelf: Shelf]; editSlot: [slot: Slot]; print: [slots: Slot[]]
  history: [code: string]; stock: [slotId: number, mode: 'IN' | 'OUT']
}>()
const selectedId = ref<number>()
const drawer = ref<HTMLDialogElement>()
const selected = computed(() => props.slots.find(slot => slot.id === selectedId.value))
const matchingIds = computed(() => new Set(props.matchingSlots.map(slot => slot.id)))
const slotsByShelf = computed(() => {
  const grouped = new Map<number, Slot[]>()
  for (const slot of props.slots) {
    const cells = grouped.get(slot.shelfId) ?? []
    cells.push(slot)
    grouped.set(slot.shelfId, cells)
  }
  return grouped
})
const racks = computed(() => props.shelves.map(shelf => {
  const slots = slotsByShelf.value.get(shelf.id) ?? []
  return {
    shelf, slots,
    layers: Array.from({ length: shelf.layerCount }, (_, index) => {
      const no = shelf.layerCount - index
      return { no, slots: slots.filter(slot => slot.layerNo === no).sort((a, b) => a.cellNo - b.cellNo) }
    }),
    occupied: slots.filter(slot => slot.quantity > 0).length,
    weight: decimal(slots.reduce((total, slot) => total + slot.quantity, 0)),
  }
}).filter(rack => !props.filtering || rack.slots.some(slot => matchingIds.value.has(slot.id))))
const shownSlots = computed(() => racks.value.flatMap(rack => rack.slots))
const occupied = computed(() => shownSlots.value.filter(slot => slot.quantity > 0).length)
const weight = computed(() => decimal(shownSlots.value.reduce((sum, slot) => sum + slot.quantity, 0)))
const status = (slot: Slot) => !slot.active ? '已停用' : slot.quantity > 0 ? '有库存' : slot.productId ? '待入库' : '空格'
const cellLabel = (slot: Slot) => [slot.code, slot.productName || '空格', slot.productId ? formatWeight(slot.quantity) + unitLabel.value : '', status(slot)].filter(Boolean).join('，')

async function inspect(slot: Slot) {
  selectedId.value = slot.id
  await nextTick()
  drawer.value?.showModal()
}
function close() { drawer.value?.close() }
function editSelected() { if (selected.value) { close(); emit('editSlot', selected.value) } }
function printSelected() { if (selected.value) { close(); emit('print', [selected.value]) } }
function historySelected() { if (selected.value) { close(); emit('history', selected.value.code) } }
function stockSelected(mode: 'IN' | 'OUT') { if (selected.value) { close(); emit('stock', selected.value.id, mode) } }
</script>

<template>
  <div class="rack-board">
    <div class="rack-board-overview">
      <div class="rack-board-totals" aria-live="polite">
        <span><strong>{{ racks.length }}</strong> 个货架</span>
        <span><strong>{{ shownSlots.length }}</strong> 个格子</span>
        <span><strong>{{ occupied }}</strong> 个有库存</span>
        <span class="rack-board-weight">合计 <strong>{{ formatWeight(weight) }}</strong> {{ unitLabel }}</span>
        <span v-if="filtering" class="rack-match-count">{{ matchingSlots.length }} 格匹配</span>
      </div>
      <div class="rack-board-legend" aria-label="格子状态">
        <span><i class="stocked"></i>有库存</span><span><i class="bound"></i>待入库</span><span><i></i>空格</span><span><i class="inactive"></i>停用</span>
      </div>
    </div>
    <div v-if="racks.length" class="rack-board-grid">
      <article v-for="rack in racks" :key="rack.shelf.id" class="rack-card" :aria-label="rack.shelf.name">
        <header class="rack-card-header">
          <h3>{{ rack.shelf.name }}</h3><span class="rack-code">{{ rack.shelf.code }}</span>
          <div v-if="admin" class="rack-card-actions">
            <button class="icon-button" :aria-label="'编辑' + rack.shelf.name" title="编辑货架" @click="$emit('editShelf', rack.shelf)"><Pencil :size="14" /></button>
            <button class="icon-button" :aria-label="'打印' + rack.shelf.name + '标签'" title="打印本架标签" @click="$emit('print', rack.slots)"><Printer :size="15" /></button>
          </div>
        </header>
        <div class="rack-card-layers">
          <div v-for="layer in rack.layers" :key="layer.no" class="rack-card-layer">
            <span class="rack-layer-number">{{ layer.no }}层</span>
            <div class="rack-card-cells" :style="{ gridTemplateColumns: 'repeat(' + Math.max(layer.slots.length, 1) + ', minmax(0, 1fr))' }">
              <button v-for="slot in layer.slots" :key="slot.id" type="button"
                :class="['rack-cell', { stocked: slot.quantity > 0, bound: slot.productId && slot.quantity === 0, inactive: !slot.active, 'not-matched': filtering && !matchingIds.has(slot.id) }]"
                :aria-label="cellLabel(slot)" :title="cellLabel(slot) + ' · 点击查看详情'" @click="inspect(slot)">
                <span class="rack-cell-index">{{ String(slot.cellNo).padStart(2, '0') }}</span>
                <template v-if="slot.productId">
                  <span class="rack-cell-name">{{ slot.variety }}<small>{{ slot.headCount }}头</small></span>
                  <strong class="rack-cell-weight">{{ formatWeight(slot.quantity) }}<small>{{ unitLabel }}</small></strong>
                </template>
                <span v-else class="rack-cell-empty">{{ slot.active ? '空格' : '停用' }}</span>
              </button>
            </div>
          </div>
        </div>
        <footer class="rack-card-footer"><span>{{ rack.shelf.layerCount }}层 · {{ rack.slots.length }}格</span><span>{{ rack.occupied }}/{{ rack.slots.length }} 有货</span><strong>{{ formatWeight(rack.weight) }}<small> {{ unitLabel }}</small></strong></footer>
      </article>
    </div>
    <p v-else class="empty">没有符合条件的货架，请调整搜索或筛选条件。</p>
  </div>

  <dialog ref="drawer" class="rack-slot-drawer" aria-labelledby="rack-slot-title" @click="event => { if (event.target === drawer) close() }">
    <template v-if="selected">
      <div class="dialog-head"><div><p class="rack-drawer-eyebrow">格子详情</p><h2 id="rack-slot-title">{{ selected.code }}</h2></div><button class="icon-button" aria-label="关闭格子详情" @click="close"><X :size="20" /></button></div>
      <div class="rack-drawer-body">
        <div class="rack-drawer-location"><span>{{ shelves.find(shelf => shelf.id === selected?.shelfId)?.name }} · 第{{ selected.layerNo }}层 · 第{{ selected.cellNo }}格</span><span class="badge" :class="!selected.active ? 'out' : selected.quantity > 0 ? 'in' : 'neutral'">{{ status(selected) }}</span></div>
        <template v-if="selected.productId">
          <h3 class="rack-drawer-product">{{ selected.productName }}</h3>
          <div class="rack-drawer-stock"><strong>{{ formatWeight(selected.quantity) }}<small>{{ unitLabel }}</small></strong><span>约 {{ number(selected.estimatedCount ?? 0) }} 个</span></div>
          <dl class="detail-list"><div><dt>品种</dt><dd>{{ selected.variety }}</dd></div><div><dt>头数</dt><dd>{{ selected.headCount }} 个 / 斤</dd></div><div><dt>规格编号</dt><dd>{{ selected.sku }}</dd></div></dl>
          <p class="muted rack-drawer-note">个数按重量估算，出入库以实际重量为准。</p>
          <div class="rack-drawer-operations"><button class="primary" :disabled="!selected.active || !selected.productActive" @click="stockSelected('IN')"><ArrowDownLeft :size="17" />入库</button><button class="secondary" :disabled="!selected.active || selected.quantity <= 0" @click="stockSelected('OUT')"><ArrowUpRight :size="17" />出库</button></div>
        </template>
        <div v-else class="rack-drawer-vacant"><Boxes :size="30" /><h3>{{ selected.active ? '这个格子还没有绑定鱼胶' : '这个格子已停用' }}</h3><p>{{ admin ? '绑定品种和头数后，即可按' + unitLabel + '办理出入库。' : '请联系管理员绑定鱼胶规格。' }}</p><button v-if="admin" class="primary" @click="editSelected">{{ selected.active ? '绑定鱼胶规格' : '编辑格子' }}</button></div>
        <div class="rack-drawer-links"><button class="secondary" @click="historySelected"><ClipboardList :size="16" />库存流水</button><button v-if="admin" class="secondary" @click="printSelected"><QrCode :size="16" />二维码标签</button><button v-if="admin && selected.productId" class="secondary" @click="editSelected"><Pencil :size="16" />编辑绑定</button></div>
      </div>
    </template>
  </dialog>
</template>

<style scoped>
.rack-board { min-width: 0; }
.rack-board-overview { display: flex; justify-content: space-between; flex-wrap: wrap; align-items: center; gap: 8px 18px; padding: 10px 14px; border-bottom: 1px solid #e8eeea; background: #fbfcfb; }
.rack-board-totals { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 16px; color: #63776c; font-size: 12px; }
.rack-board-totals strong { color: #264c3c; font-size: 14px; font-variant-numeric: tabular-nums; }
.rack-board-weight { border-left: 1px solid #dce6df; padding-left: 16px; }
.rack-match-count { color: #14785c; }
.rack-board-legend { display: flex; flex-wrap: wrap; gap: 12px; font-size: 11px; color: #6a7b71; }
.rack-board-legend span { display: flex; align-items: center; gap: 5px; }
.rack-board-legend i { display: block; width: 8px; height: 8px; border-radius: 2px; border: 1px solid #c9d6cd; background: #f3f6f4; }
.rack-board-legend i.stocked { background: #208468; border-color: #208468; }
.rack-board-legend i.bound { background: #e4ba6c; border-color: #e4ba6c; }
.rack-board-legend i.inactive { background: #e5e8e6; border-style: dashed; border-color: #97a29c; }
.rack-board-grid { padding: 14px; display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 242px), 1fr)); align-items: start; gap: 12px; }
.rack-card { min-width: 0; overflow: hidden; border: 1px solid #d8e4dc; border-radius: 10px; background: #fff; }
.rack-card-header { min-width: 0; display: flex; align-items: center; gap: 8px; padding: 6px 10px; min-height: 35px; background: #f5f8f6; border-bottom: 1px solid #e4ece6; }
.rack-card-header h3 { min-width: 0; font-size: 14px; font-weight: 740; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rack-code { color: #6e8074; font-size: 10px; font-family: ui-monospace, monospace; font-variant-numeric: tabular-nums; }
.rack-card-actions { display: flex; align-items: center; gap: 2px; margin-left: auto; }
.rack-card-actions .icon-button { width: 26px; height: 25px; color: #6c8374; border-radius: 5px; }
.rack-card-layers { display: grid; gap: 5px; padding: 9px 10px; }
.rack-card-layer { min-width: 0; display: grid; grid-template-columns: 22px minmax(0, 1fr); align-items: center; gap: 5px; }
.rack-layer-number { color: #6a7e70; font-size: 11px; text-align: left; }
.rack-card-cells { display: grid; gap: 5px; min-width: 0; }
.rack-cell { display: flex; align-items: center; gap: 5px; min-width: 0; min-height: 34px; padding: 5px 7px; color: #314c3e; background: #f7f9f7; border: 1px solid #e1e8e2; border-radius: 5px; text-align: left; transition: background .12s, border-color .12s; }
.rack-cell:hover { border-color: #71a88d; background: #edf5ef; }
.rack-cell:focus-visible { outline-offset: 1px; }
.rack-cell-index { color: #7b8d80; font-size: 10px; font-variant-numeric: tabular-nums; flex-shrink: 0; }
.rack-cell-empty { flex: 1; text-align: center; color: #5e7565; font-size: 12px; padding-right: 9px; }
.rack-cell.stocked { background: #eaf6ee; border-color: #a9ceba; border-left: 3px solid #208468; padding-left: 5px; }
.rack-cell.bound { background: #fffbf0; border-color: #e9dcbb; }
.rack-cell.inactive { background: #f1f2f1; border-color: #d5dcd6; border-style: dashed; }
.rack-cell.not-matched { opacity: .35; }
.rack-cell-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; font-weight: 650; }
.rack-cell-name small { display: block; font-size: 10px; font-weight: 450; line-height: 1.2; color: #72877a; }
.rack-cell-weight { margin-left: auto; white-space: nowrap; font-size: 12px; font-weight: 740; font-variant-numeric: tabular-nums; color: #197058; }
.rack-cell-weight small { font-size: 9px; font-weight: 400; margin-left: 1px; color: #64816f; }
.rack-card-footer { display: flex; align-items: center; gap: 10px; padding: 5px 10px; border-top: 1px solid #eef2ef; color: #6b7e70; font-size: 10px; line-height: 1.4; }
.rack-card-footer strong { margin-left: auto; color: #42624f; font-weight: 650; font-size: 11px; font-variant-numeric: tabular-nums; }
.rack-card-footer small { font-size: 10px; font-weight: 400; }
.rack-slot-drawer { position: fixed; inset: 0 0 0 auto; margin: 0; width: min(390px, 100%); max-width: 100%; height: 100dvh; max-height: 100dvh; border-radius: 15px 0 0 15px; border-width: 0 0 0 1px; }
.rack-slot-drawer[open] { display: flex; flex-direction: column; }
.rack-slot-drawer .dialog-head { padding: 20px; }
.rack-drawer-eyebrow { color: #84948a; font-size: 12px; margin-bottom: 7px; }
.rack-slot-drawer h2 { font: 700 21px/1.2 ui-monospace, monospace; }
.rack-drawer-body { flex: 1; min-height: 0; overflow-y: auto; padding: 20px; }
.rack-drawer-location { display: flex; justify-content: space-between; align-items: center; gap: 8px; color: #6c8073; font-size: 12px; }
.rack-drawer-product { font-size: 20px; margin: 28px 0 14px; }
.rack-drawer-stock { display: flex; align-items: baseline; flex-wrap: wrap; gap: 10px; margin-bottom: 24px; color: #6c8274; font-size: 14px; }
.rack-drawer-stock > strong { font-size: 35px; color: #14785c; font-variant-numeric: tabular-nums; }
.rack-drawer-stock small { margin-left: 5px; font-size: 14px; font-weight: 450; }
.rack-drawer-note { font-size: 12px; margin: 14px 0 20px; }
.rack-drawer-operations { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.rack-drawer-links { margin-top: 24px; display: flex; flex-wrap: wrap; gap: 8px; padding-top: 18px; border-top: 1px solid #e6ede8; }
.rack-drawer-links .secondary { font-size: 12px; padding: 7px 9px; }
.rack-drawer-vacant { display: grid; justify-items: center; text-align: center; gap: 12px; padding: 38px 0; color: #7b9383; }
.rack-drawer-vacant h3 { color: #426350; font-size: 16px; }
.rack-drawer-vacant p { font-size: 13px; line-height: 1.7; }
.rack-drawer-vacant .primary { margin-top: 8px; }
@media (max-width: 760px) {
  .rack-board-overview { padding: 10px 12px; }
  .rack-board-totals { gap: 6px 12px; }
  .rack-board-legend { gap: 10px; }
  .rack-board-grid { padding: 10px; gap: 10px; }
  .rack-cell { min-height: 42px; }
  .rack-card-actions .icon-button { width: 32px; height: 32px; }
  .rack-cell-name { font-size: 12px; }
  .rack-cell-name small { font-size: 10px; }
  .rack-cell-index { font-size: 10px; }
}
@media (prefers-reduced-motion: reduce) { .rack-cell { transition: none; } }
</style>
