import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // В разработке фронтенд поднят отдельным сервером, а запросы к API уходят
    // на бэкенд через прокси: так в браузере нет ни CORS, ни второго адреса,
    // и код обращается к /api одинаково и при разработке, и в собранном виде.
    proxy: {
      '/api': {
        target: process.env.VITE_PROXY_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
    chunkSizeWarningLimit: 1500,
  },
})
