/**
 * 本地预览专用配置：npx vite --config vite.config.mock.ts
 * 和主配置的区别——不挂 PWA（SW 缓存会挡热更新），不配 proxy（后端由 mock 中间件顶上），端口另开 3001。
 */
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { traderMock } from './mock/traderMock';

export default defineConfig({
  plugins: [react(), tailwindcss(), traderMock()],
  define: {
    global: 'globalThis',
  },
  server: {
    port: 3001,
    // /ws 没人接，stompClient 会一直重连报错——不影响页面，控制台里忽略即可
  },
});
