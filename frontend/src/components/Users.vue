<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Plus, X } from 'lucide-vue-next'
import { api, errorMessage } from '../api'
import type { Account, Role } from '../model'

const emit = defineEmits<{ changed: [] }>()
const users = ref<Account[]>([])
const error = ref('')
const formError = ref('')
const busy = ref(false)
const editId = ref<number>()
const dialog = ref<HTMLDialogElement>()
const form = ref({ username: '', displayName: '', password: '', active: true, role: 'OPERATOR' as Role })

async function load() {
  try { users.value = await api<Account[]>('/admin/users'); error.value = '' }
  catch (e) { error.value = errorMessage(e) }
}
function open(user?: Account) {
  editId.value = user?.id
  formError.value = ''
  form.value = { username: user?.username ?? '', displayName: user?.displayName ?? '', password: '', role: user?.role ?? 'OPERATOR', active: user?.active ?? true }
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
    <p v-if="error" class="error panel-message" role="alert">{{ error }}</p>
    <div class="table-scroll">
      <table>
        <thead><tr><th>显示名称</th><th>账号</th><th>角色</th><th>状态</th><th>操作</th></tr></thead>
        <tbody><tr v-for="user in users" :key="user.id"><td>{{ user.displayName }}</td><td>{{ user.username }}</td>
          <td>{{ user.role === 'ADMIN' ? '管理员' : '仓库操作员' }}</td>
          <td><span :class="['badge', user.active ? 'in' : 'neutral']">{{ user.active ? '启用' : '已停用' }}</span></td>
          <td><button class="text-button" @click="open(user)">编辑</button></td>
        </tr></tbody>
      </table>
    </div>
  </section>
  <dialog ref="dialog" @cancel="event => { if (busy) event.preventDefault() }">
    <div class="dialog-head"><h2>{{ editId ? '编辑账号' : '新增账号' }}</h2>
      <button class="icon-button" aria-label="关闭" :disabled="busy" @click="dialog?.close()"><X :size="20" /></button>
    </div>
    <form class="form-stack dialog-form" @submit.prevent="save">
      <fieldset :disabled="busy">
        <label class="field">账号<input v-model.trim="form.username" required pattern="[A-Za-z0-9._\-]{2,64}" maxlength="64" autocomplete="off"></label>
        <label class="field">显示名称<input v-model.trim="form.displayName" required maxlength="100"></label>
        <label class="field">{{ editId ? '重置密码（选填）' : '初始密码' }}<input v-model="form.password" type="password" :required="!editId" minlength="12" maxlength="72" autocomplete="new-password"></label>
        <label class="field">角色<select v-model="form.role"><option value="OPERATOR">仓库操作员</option><option value="ADMIN">管理员</option></select></label>
        <label class="check"><input v-model="form.active" type="checkbox"> 启用账号</label>
      </fieldset>
      <p v-if="formError" class="error" role="alert">{{ formError }}</p>
      <div class="dialog-foot"><button class="primary" :disabled="busy">{{ busy ? '保存中…' : '保存账号' }}</button></div>
    </form>
  </dialog>
</template>
