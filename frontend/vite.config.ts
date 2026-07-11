import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Dev proxy routes each path prefix to its owning service — no gateway needed locally.
// ponytail: in prod put an API gateway / nginx in front instead of this proxy map.
const svc = (port: number) => ({ target: `http://localhost:${port}`, changeOrigin: true });

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/auth": svc(8081),
      "/groups": svc(8082),
      "/expenses": svc(8082),
      "/balances": svc(8083),
      "/settlements": svc(8083),
      "/personal-debts": svc(8083),
      "/ledger": svc(8083),
      "/notifications": svc(8084),
      "/recurring": svc(8084),
      "/obligations": svc(8084),
    },
  },
});
