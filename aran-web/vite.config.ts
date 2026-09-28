import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath, URL } from 'node:url'
import tailwindcss from '@tailwindcss/vite'

// ===========================================================================
//  Vite configuration — Aran React frontend
//
//  The dev server proxies /api to the Spring Boot application on :8081 so the
//  SPA and API share an origin in development (no CORS config needed locally).
// ===========================================================================
export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
  ],

  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },

  // Targets are env-driven so the Playwright suite can point a second dev
  // server at its own isolated backend. Defaults are the dev ports, so
  // `make frontend` behaves exactly as before.
  server: {
    // `??` only falls back on null/undefined, so a *set-but-empty*
    // VITE_DEV_PORT (`Number('') === 0`) or a non-numeric value
    // (`Number('x') === NaN`) would bind port 0 or crash. `||` falls back on
    // any falsy result — including 0 and NaN — to the dev default, 5173.
    port: Number(process.env.VITE_DEV_PORT) || 5173,
    proxy: {
      '/api': {
        target: process.env.VITE_API_TARGET ?? 'http://localhost:8081',
        changeOrigin: true,
      },
      '/actuator': {
        target: process.env.VITE_API_TARGET ?? 'http://localhost:8081',
        changeOrigin: true,
      },
      '/auth': {
        target: process.env.VITE_AUTH_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path: string) => path.replace(/^\/auth/, ''),
      },
    },
  },
  test: {
    include: ['src/**/*.{test,spec}.{js,mjs,cjs,ts,mts,cts,jsx,tsx}'],
  }
} as import('vite').UserConfig)
