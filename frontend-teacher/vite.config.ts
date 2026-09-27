import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { fileURLToPath, URL } from 'node:url';

// 模板来源：AGENT_INSTRUCTIONS.md 2.0 —— 三处不得改动
// ① plugins: [vue()] 缺失则 Vite 不认识 .vue 文件，构建直接失败
// ② 代理路径 /api 必须与 request.ts 的 baseURL 一致
// ③ target 端口 8080 必须与后端 server.port 一致
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
