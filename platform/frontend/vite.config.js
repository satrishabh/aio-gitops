import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// In dev, proxy API + OAuth endpoints to Spring Boot on :8080
const backend = { target: 'http://localhost:8080', changeOrigin: false }

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': backend,
      '/oauth2': backend,
      '/login/oauth2': backend,
      '/logout': backend,
    },
  },
})
