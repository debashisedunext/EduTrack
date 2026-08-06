import React from 'react'
import ReactDOM from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import App from './App'
import './styles/tokens.css'

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
        <App />
      </QueryClientProvider>
    </React.StrictMode>,
  )
})
