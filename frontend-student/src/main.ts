import { createApp } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import { ElMessage } from 'element-plus';
import 'element-plus/dist/index.css';

import App from './App.vue';
import router from './router';
import './styles/global.css';

const app = createApp(App);

app.use(createPinia());
app.use(router);
app.use(ElementPlus, { locale: zhCn });

/**
 * 全局渲染/生命周期异常兜底（C3.1）。
 *
 * 目的：单个组件在渲染或生命周期里抛错时，用户至少能看到一句可读提示，而不是停在
 * 半渲染状态（视觉上等于白屏）。Vue 的默认行为只是把异常打到控制台，界面上什么都不说。
 *
 * 边界说明：HTTP 层错误不走这里 —— request.ts 拦截器已经统一翻译并弹过提示，
 * 在这里再挂 unhandledrejection 会造成同一故障弹两次，所以刻意不加。
 */
app.config.errorHandler = (err, _instance, info) => {
  // 保留原始错误与出错阶段，便于排查；不要只弹提示不留痕迹
  console.error('[global errorHandler]', info, err);
  ElMessage.error('页面出现异常，请刷新后重试');
};

try {
  app.mount('#app');
} catch (error) {
  // 挂载期异常：此时 Vue 自己的 errorHandler 也可能来不及接管（错误直接抛出 mount()）。
  // 直接渲染一段最小兜底文案，保证「异常时不是白屏」这条验收在任何路径上都成立。
  console.error('[mount failed]', error);
  const root = document.getElementById('app');
  if (root) {
    const box = document.createElement('div');
    box.textContent = '页面加载失败，请检查网络后刷新重试';
    box.style.cssText = 'padding:48px 16px;text-align:center;font-size:14px;color:#64748b;';
    root.replaceChildren(box);
  }
}
