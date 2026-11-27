<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Plus, X } from 'lucide-vue-next'
import { api, errorMessage } from '../api'
import type { Account, Role } from '../model'
import AppSelect from './AppSelect.vue'

const emit = defineEmits<{ changed: [] }>()
const users = ref<Account[]>([])
const error = ref('')
const formError = ref('')
const busy = ref(false)
const editId = ref<number>()
const dialog = ref<HTMLDialogElement>()
const form = ref({ username: '', displayName: '', password: '', active: true, approved: true, role: 'OPERATOR' as Role })
const selected = ref<Account>()
const search = ref('')
const pendingOnly = ref(false)
const pendingCount = computed(() => users.value.filter(user => user.active && !user.approved).length)
const rows = computed(() => users.value.filter(user => (!pendingOnly.value || (user.active && !user.approved)) &&
  [user.username, user.displayName, user.phone || ''].some(value => value.toLowerCase().includes(search.value.trim().toLowerCase())))
  .sort((a, b) => Number(a.approved) - Number(b.approved) || b.id - a.id))

async function load() {
  try { users.value = await api<Account[]>('/admin/users'); error.value = '' }
  catch (e) { error.value = errorMessage(e) }
}
function open(user?: Account, authorize = false) {
  selected.value = user
  editId.value = user?.id
  formError.value = ''
  form.value = { username: user?.username ?? '', displayName: user?.displayName ?? '', password: '', role: user?.role ?? 'OPERATOR',
    active: user?.active ?? true, approved: authorize || (user?.approved ?? true) }
  dialog.value?.showModal()
}
async function save() {
  if (busy.value) return
  busy.value = true
  formError.value = ''
  try {
    await api('/admin/users' + (editId.value ? '/' + editId.value : ''),
      { ...form.value, password: form.value.password || null }, editId.value ? 'PUT' : 'POST')
    dialog.value?.close()
    await load()
    emit('changed')
  } catch (e) { formError.value = errorMessage(e) }
  finally { busy.value = false }
}
onMounted(load)
</script>

<template>
  <section class="panel">
    <div class="toolbar"><h2>仓库账号</h2><button class="primary toolbar-end" @click="open()"><Plus :size="17" /> 新增账号</button></div>
    <div class="filter-form"><label class="field">查找用户<input v-model="search" placeholder="姓名、手机号或账号"></label>
      <label class="check"><input v-model="pendingOnly" type="checkbox"> 仅待授权（{{ pendingCount }}）</label>
      <p class="muted">微信新用户完成手机号授权后会出现在这里，授予仓库权限后才可访问业务数据。</p>
    </div>
    <p v-if="error" class="error panel-message" role="alert">{{ error }}</p>
    <div class="table-scroll">
      <table>
        <thead><tr><th>显示名称 / 账号</th><th>手机号</th><th>登录方式</th><th>角色</th><th>状态</th><th>操作</th></tr></thead>
        <tbody><tr v-for="user in rows" :key="user.id"><td>{{ user.displayName }}<small class="code">{{ user.username }}</small></td><td>{{ user.phone || '—' }}</td>
          <td>{{ user.wechatBound ? '微信登录' : '账号密码' }}</td>
          <td>{{ !user.approved ? '待授权' : user.role === 'ADMIN' ? '管理员' : '仓库操作员' }}</td>
          <td><span :class="['badge', user.active && user.approved ? 'in' : 'neutral']">{{ !user.active ? '已停用' : user.approved ? '已授权' : '待授权' }}</span></td>
          <td><button class="text-button" @click="open(user, user.active && !user.approved)">{{ user.active && !user.approved ? '授权' : '编辑' }}</button></td>
        </tr></tbody>
      </table>
      <p v-if="!rows.length && !error" class="empty">没有符合条件的用户。</p>
    </div>
  </section>
  <dialog ref="dialog" @cancel="event => { if (busy) event.preventDefault() }">
    <div class="dialog-head"><h2>{{ selected?.wechatBound ? '微信用户授权' : editId ? '编辑账号' : '新增账号' }}</h2>
      <button class="icon-button" aria-label="关闭" :disabled="busy" @click="dialog?.close()"><X :size="20" /></button>
    </div>
    <form class="form-stack dialog-form" @submit.prevent="save">
      <fieldset :disabled="busy">
        <label v-if="selected?.wechatBound" class="field">微信授权手机号<input :value="selected.phone || ''" readonly><small>手机号由微信校验。授权仅对当前微信账号生效。</small></label>
        <label class="field">账号<input v-model.trim="form.username" :readonly="selected?.wechatBound" required pattern="[A-Za-z0-9._\-]{2,64}" maxlength="64" autocomplete="off"></label>
        <label class="field">显示名称<input v-model.trim="form.displayName" required maxlength="100"></label>
        <label v-if="!selected?.wechatBound" class="field">{{ editId ? '重置密码（选填）' : '初始密码' }}<input v-model="form.password" type="password" :required="!editId" minlength="12" maxlength="72" autocomplete="new-password"></label>
        <label class="field">角色<AppSelect v-model="form.role" label="角色" :options="[{ value: 'OPERATOR', label: '仓库操作员' }, { value: 'ADMIN', label: '管理员' }] as const" /></label>
        <label class="check"><input v-model="form.active" type="checkbox"> 启用账号</label>
        <label class="check"><input v-model="form.approved" type="checkbox"> 允许访问仓库（按所选角色授权）</label>
      </fieldset>
      <p v-if="formError" class="error" role="alert">{{ formError }}</p>
      <div class="dialog-foot"><button class="primary" :disabled="busy">{{ busy ? '保存中…' : selected?.wechatBound ? '保存授权' : '保存账号' }}</button></div>
    </form>
  </dialog>
</template>
