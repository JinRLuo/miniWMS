<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref } from 'vue'
import { Camera, X } from 'lucide-vue-next'
import { BrowserQRCodeReader, type IScannerControls } from '@zxing/browser'

defineProps<{ label?: string }>()
const emit = defineEmits<{ decoded: [value: string] }>()
const open = ref(false)
const video = ref<HTMLVideoElement>()
const error = ref('')
let scanner: IScannerControls | undefined
let attempt = 0

function stop() {
  attempt++
  scanner?.stop()
  scanner = undefined
  open.value = false
}

async function start() {
  error.value = ''
  if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
    error.value = '摄像头需要 HTTPS。也可使用系统相机打开库位标签，或手动输入库位编号。'
    return
  }
  const current = ++attempt
  open.value = true
  await nextTick()
  try {
    const controls = await new BrowserQRCodeReader().decodeFromConstraints(
      { audio: false, video: { facingMode: { ideal: 'environment' } } }, video.value,
      (result, _error, active) => {
        if (current !== attempt || !result) return
        scanner = active
        stop()
        emit('decoded', result.getText())
      },
    )
    if (current !== attempt) controls.stop()
    else scanner = controls
  } catch {
    if (current !== attempt) return
    stop()
    error.value = '无法打开摄像头，请检查浏览器权限，或手动输入库位编号。'
  }
}
onBeforeUnmount(stop)
</script>

<template>
  <button v-if="!open" type="button" class="secondary scan-button" @click="start"><Camera :size="17" /> {{ label || '扫描货架码' }}</button>
  <div v-else class="camera">
    <video ref="video" playsinline muted></video>
    <button type="button" class="icon-button camera-close" aria-label="关闭摄像头" @click="stop"><X :size="20" /></button>
    <div class="scan-frame" aria-hidden="true"></div>
  </div>
  <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
</template>
