import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { fileURLToPath, URL } from 'node:url';

// 模板来源：AGENT_INSTRUCTIONS.md 2.0 —— 三处不得改动
// ① plugins: [vue()] 缺失则 Vite 不认识 .vue 文件，构建直接失败
// ② 代理路径 /api 必须与 request.ts 的 baseURL 一致
// ③ target 端口 8080 必须与后端 server.port 一致
//
// 与模板的差异（均不涉及上面那三处锁定项）：
// ① dev 端口由 5173 改为 5174 —— 同一 Monorepo 内 frontend-teacher/ 已占用 5173，
//    两个前端共用端口会互相抢占、`npm run dev` 报 EADDRINUSE。
// ② 增加 preview.proxy（C3.2）—— 见下方注释。
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
  // C3.2 修缺陷：preview 有独立的 proxy 配置，server.proxy 只作用于 dev。
  // 缺了这段，用构建产物演示/验收（npm run preview）时所有 /api 请求会被当成静态文件去找，
  // 直接 404 —— 现象是"接口全挂"而不是构建失败，很容易被误判成后端没起。
  // 路径与 target 与上面完全一致，不改动模板锁定的那三处。
  preview: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
