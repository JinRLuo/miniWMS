<script setup lang="ts">
import { ref, watch } from 'vue'
import { Check, KeyRound, Scale } from 'lucide-vue-next'
import { api, errorMessage } from '../api'
import type { Preferences, WeightUnit } from '../weight'

const props = defineProps<{ preferences: Preferences }>()
const emit = defineEmits<{ saved: [preferences: Preferences]; passwordChanged: [] }>()
const unit = ref<WeightUnit>(props.preferences.weightUnit)
const busy = ref(false)
const error = ref('')
const saved = ref(false)
const passwordForm = ref({ currentPassword: '', newPassword: '', confirmPassword: '' })
const passwordBusy = ref(false)
const passwordError = ref('')
watch(() => props.preferences.weightUnit, value => { unit.value = value })
watch(unit, () => { saved.value = false })
async function save() {
  if (busy.value || passwordBusy.value) return
  busy.value = true; error.value = ''; saved.value = false
  try {
    const result = await api<Preferences>('/auth/preferences', { weightUnit: unit.value }, 'PUT')
    emit('saved', result)
    saved.value = true
  } catch (e) { error.value = errorMessage(e) }
  finally { busy.value = false }
}
async function changePassword() {
  if (busy.value || passwordBusy.value) return
  passwordError.value = ''
  const { currentPassword, newPassword, confirmPassword } = passwordForm.value
  if (newPassword.length < 12) { passwordError.value = '新密码至少需要 12 个字符'; return }
  if (new TextEncoder().encode(newPassword).length > 72) { passwordError.value = '新密码最多支持 72 字节，请减少字符长度'; return }
  if (newPassword !== confirmPassword) { passwordError.value = '两次输入的新密码不一致'; return }
  if (newPassword === currentPassword) { passwordError.value = '新密码不能与当前密码相同'; return }
  passwordBusy.value = true
  try {
    await api('/auth/password', { currentPassword, newPassword, confirmPassword }, 'PUT')
    passwordForm.value = { currentPassword: '', newPassword: '', confirmPassword: '' }
    emit('passwordChanged')
  } catch (e) { passwordError.value = errorMessage(e) }
  finally { passwordBusy.value = false }
}
</script>

<template>
  <section class="panel preferences-panel">
    <div class="panel-head"><div><h2>重量单位</h2><p>仅对当前账号生效，电脑和手机共用你的设置。</p></div><Scale :size="23" /></div>
    <form @submit.prevent="save">
      <fieldset :disabled="busy || passwordBusy" class="weight-options">
        <label v-for="choice in [{ value: 'JIN', title: '斤', description: '默认单位' }, { value: 'GRAM', title: '克', description: '1 斤 = 500 克' }] as const"
          :key="choice.value" :class="['weight-option', { selected: unit === choice.value }]">
          <input v-model="unit" type="radio" name="weight-unit" :value="choice.value"><span><strong>{{ choice.title }}</strong><small>{{ choice.description }}</small></span>
        </label>
      </fieldset>
      <p class="preferences-note">鱼胶库存、出入库、报表和 CSV 导出会使用所选单位。切换单位会自动换算重量，头数仍表示每斤的个数。克单位最小支持 0.5 克。</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <div class="preferences-actions"><span v-if="saved" class="preferences-saved" role="status"><Check :size="17" />已保存，仅对当前账号生效</span>
        <button class="primary" :disabled="busy || passwordBusy">{{ busy ? '保存中…' : '保存设置' }}</button></div>
    </form>
  </section>
  <section class="panel preferences-panel">
    <div class="panel-head"><div><h2>修改密码</h2><p>至少 12 个字符。修改成功后，当前及其他设备上的旧登录将失效。</p></div><KeyRound :size="23" /></div>
    <form class="form-stack" @submit.prevent="changePassword">
      <fieldset :disabled="passwordBusy || busy">
        <label class="field">当前密码<input v-model="passwordForm.currentPassword" type="password" autocomplete="current-password" required maxlength="72" placeholder="请输入当前密码"></label>
        <label class="field">新密码<input v-model="passwordForm.newPassword" type="password" autocomplete="new-password" required minlength="12" maxlength="72" placeholder="至少 12 个字符"></label>
        <label class="field">确认新密码<input v-model="passwordForm.confirmPassword" type="password" autocomplete="new-password" required minlength="12" maxlength="72" placeholder="请再次输入新密码"></label>
      </fieldset>
      <p v-if="passwordError" class="error" role="alert">{{ passwordError }}</p>
      <div class="preferences-actions"><button class="primary" :disabled="passwordBusy || busy">{{ passwordBusy ? '正在修改…' : '修改密码并重新登录' }}</button></div>
    </form>
  </section>
</template>
