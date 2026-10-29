import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The browser talks only to this origin; /api is proxied to the Java backend so session and
// CSRF cookies stay same-origin (SameSite=Strict) and no CORS is needed.
const proxy = { '/api': { target: process.env.BACKEND_URL ?? 'http://127.0.0.1:8080', changeOrigin: false } }

export default defineConfig({
  plugins: [react()],
  server: { host: '127.0.0.1', port: 5173, strictPort: true, proxy },
  preview: { host: '127.0.0.1', port: 5173, strictPort: true, proxy },
})
