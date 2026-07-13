import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// One target: the gateway owns the route map, CORS and rate limiting, so the dev server
// just forwards every API prefix to it.
const gateway = {
  target: "http://localhost:8080",
  changeOrigin: true,
  // ponytail: /groups, /settlements, /recurring are also SPA routes. A page refresh is an
  // HTML navigation, not an API call, so serve index.html and let React Router take it.
  bypass: (req: { headers: Record<string, string | undefined> }) =>
    req.headers.accept?.includes("text/html") ? "/index.html" : undefined,
};

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/auth": gateway,
      "/groups": gateway,
      "/expenses": gateway,
      "/balances": gateway,
      "/settlements": gateway,
      "/personal-debts": gateway,
      "/ledger": gateway,
      "/notifications": gateway,
      "/recurring": gateway,
      "/obligations": gateway,
    },
  },
});
