import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import path from 'node:path'

export default defineConfig({
  plugins: [react()],
  resolve: { alias: { '@': path.resolve(__dirname, './src') } },
  server: {
    port: 5173,
    proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } },
  },
  test: {
    environment: 'jsdom',
    // Node's fetch needs an absolute URL, and MSW resolves relative handler
    // paths against jsdom's origin — so the two must agree or nothing matches
    // and every request reports as unhandled. Pin both rather than relying on
    // jsdom's default.
    environmentOptions: { jsdom: { url: 'http://localhost' } },
    env: { VITE_API_BASE: 'http://localhost/api/v1' },
    setupFiles: './src/test/setup.ts',
    globals: true,
  },
})
