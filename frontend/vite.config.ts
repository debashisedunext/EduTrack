import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import path from 'node:path'

export default defineConfig({
  plugins: [react()],
  resolve: { alias: { '@': path.resolve(__dirname, './src') } },
  // sockjs-client (D-015) is a pre-bundler-era library and reads the bare
  // `global` 53 times. Vite does not define it, so without this the realtime
  // client throws "global is not defined" the moment it opens a socket — in the
  // browser only, never in vitest, whose jsdom environment provides it.
  define: { global: 'globalThis' },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      // The STOMP handshake. `ws: true` is what lets the upgrade through;
      // without it the dev server answers the SockJS negotiation itself and
      // realtime works in production but not on any developer's machine.
      '/ws': { target: 'http://localhost:8080', changeOrigin: true, ws: true },
    },
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
