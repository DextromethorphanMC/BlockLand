import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// In development the backend runs on :8080; /api is proxied so the browser sees one origin.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://127.0.0.1:8080',
    },
  },
})
