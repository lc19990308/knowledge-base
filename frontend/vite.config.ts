import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: false,
      },
      '/oauth2': { target: 'http://127.0.0.1:8080', changeOrigin: false },
      // 只代理 OAuth2 回调；SPA 的登录页路由 /login 不能被劫持到后端。
      '/login/oauth2': { target: 'http://127.0.0.1:8080', changeOrigin: false },
    },
  },
})
