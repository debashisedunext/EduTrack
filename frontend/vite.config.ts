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
    // Build outputs are not sources, and watching them is how `npm run dev`
    // dies. A `build-storybook` running alongside the dev server rewrites
    // `storybook-static/` continuously; Windows locks each file as it is
    // written, and chokidar's watcher throws `EBUSY` and takes the whole dev
    // server process down with it — not a reload, a crash. Same for `dist/`
    // and `coverage/` under a concurrent `build` or `test --coverage`. All
    // three are gitignored artefacts.
    watch: { ignored: ['**/storybook-static/**', '**/dist/**', '**/coverage/**'] },
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
