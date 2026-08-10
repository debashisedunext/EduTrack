import { Outlet } from 'react-router-dom'
import { Sidebar } from './Sidebar'
import { TopBar } from './TopBar'
import { CommandPalette } from './CommandPalette'
import { Toaster } from '@/components/ui/toaster'
// D-043/D-044 (Stream D). Renders nothing — it subscribes the shell to the
// user's own queue so notifications toast and the bell badge stays live.
// Mounted here because it must outlive every route, and the Toaster it feeds
// is already at this level.
import { NotificationStream } from '@/features/notifications/NotificationStream'

// The chrome every screen renders inside — blueprint §7.2. Owned by C-005.
export function AppShell() {
  return (
    <div className="flex h-screen overflow-hidden bg-app">
      <Sidebar />
      <div className="flex min-w-0 flex-1 flex-col">
        <TopBar />
        <main className="flex-1 overflow-y-auto">
          <Outlet />
        </main>
      </div>
      <Toaster />
      <NotificationStream />
      <CommandPalette />
    </div>
  )
}
