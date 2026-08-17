import React from 'react'
import ReactDOM from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import App from './App'
import { AuthProvider } from './features/auth/AuthProvider'
import { applyTheme, useThemeStore } from './app/theme/themeStore'
import './styles/tokens.css'

/*
  D-15 · normally a no-op: index.html's inline script has already put the class
  on <html> before this bundle was fetched, which is what avoids a flash of the
  light theme.

  Kept anyway because the inline script belongs to *that* HTML file, and the app
  is mounted from more than one page — Storybook builds its own preview shell,
  and any future host would too. Reconciling here means the document always
  agrees with the store, whoever served the HTML.
*/
applyTheme(useThemeStore.getState().theme)

/**
 * Start the mock API before the app renders.
 *
 * On by default in development (D-004) so `npm run dev` works with no backend
 * running at all — which is what lets Streams B and C build entire screens
 * before the endpoints exist. Set VITE_USE_MOCKS=false to talk to a real
 * backend through the Vite proxy instead.
 */
async function startMocks() {
  if (!import.meta.env.DEV || import.meta.env.VITE_USE_MOCKS === 'false') return
  const { worker } = await import('./mocks/browser')
  await worker.start({
    onUnhandledRequest: 'bypass',
    quiet: false,
    serviceWorker: { url: '/mockServiceWorker.js' },
  })
   
  console.info('%cEduTrack mock API active', 'color:#4F46E5;font-weight:600')
}

const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 30_000, refetchOnWindowFocus: false } },
})

startMocks().then(() => {
  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      <QueryClientProvider client={queryClient}>
        {/*
          Above the router, not inside it — A-030. `AuthProvider` restores the
          session with one `POST /auth/refresh` on mount and needs no navigation
          of its own: `RequireAuth` reacts to the store, so a session that ends
          redirects without anything here calling `useNavigate`. Keeping it above
          `App` also guarantees the startup refresh is issued once per load
          rather than once per route match — under A-024's rotation a second
          call would consume a second token from the family and read as reuse.
        */}
        <AuthProvider>
          <App />
        </AuthProvider>
      </QueryClientProvider>
    </React.StrictMode>,
  )
})
