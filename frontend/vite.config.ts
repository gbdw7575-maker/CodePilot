import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// 开发服务器代理 /api 与 /ws 到本地后端（接口文档第 1 节）
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": "http://127.0.0.1:8080",
      "/ws": { target: "ws://127.0.0.1:8080", ws: true },
    },
  },
});
