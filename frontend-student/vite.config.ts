import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { fileURLToPath, URL } from 'node:url';

// 模板来源：AGENT_INSTRUCTIONS.md 2.0 —— 三处不得改动
// ① plugins: [vue()] 缺失则 Vite 不认识 .vue 文件，构建直接失败
// ② 代理路径 /api 必须与 request.ts 的 baseURL 一致
// ③ target 端口 8080 必须与后端 server.port 一致
//
// 与模板的唯一差异：dev 端口由 5173 改为 5174。
// 理由：同一 Monorepo 内 frontend-teacher/ 已占用 5173（见其 README），
// 两个前端若共用端口会互相抢占、`npm run dev` 报 EADDRINUSE。
// 该端口不在"不得改动"的三处之内（那三处是 plugins / 代理路径 / target 端口）。
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5174,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
