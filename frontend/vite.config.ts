import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const proxy = { '/api': loadEnv(mode, '.', 'VITE_').VITE_DEV_API || 'http://127.0.0.1:8080' }
  return {
    plugins: [vue()],
    server: { proxy, strictPort: true },
    preview: { proxy },
    build: {
      rollupOptions: {
        output: {
          manualChunks: { charts: ['echarts/core', 'echarts/components', 'echarts/charts', 'echarts/renderers'] },
        },
      },
    },
  }
})
