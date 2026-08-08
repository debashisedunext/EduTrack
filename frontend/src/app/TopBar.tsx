import { useState, type KeyboardEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { Search } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { ProjectSwitcher } from './ProjectSwitcher'
import { NotificationBell } from './NotificationBell'
import { ChatBadge } from './ChatBadge'
import { AvatarMenu } from './AvatarMenu'
import { useCommandPaletteStore } from './commandPaletteStore'

const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform)

// Top bar: global search, project switcher, notification bell, chat badge,
// avatar menu — blueprint §7.2. This search box navigates to the filtered
// ticket list; Ctrl+K's command palette (C-006) is the faster jump-to-ticket
// path and is reachable from the hint on the right of this same field.
export function TopBar() {
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const openPalette = useCommandPaletteStore((s) => s.setOpen)

  function onSearchKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter' && query.trim()) {
      navigate(`/tickets?q=${encodeURIComponent(query.trim())}`)
    }
  }

  return (
    <header className="flex h-14 shrink-0 items-center gap-4 border-b border-border bg-surface px-4">
      <div className="relative w-full max-w-sm">
        <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-content-muted" />
        <Input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={onSearchKeyDown}
          placeholder="Search ticket ID, keyword or person…"
          className="pl-9 pr-16"
        />
        <button
          type="button"
          onClick={() => openPalette(true)}
          aria-label="Open command palette"
          className="absolute right-2 top-1/2 flex -translate-y-1/2 items-center gap-0.5 rounded border border-border px-1.5 py-0.5 text-[10px] font-medium text-content-muted transition-colors hover:bg-subtle hover:text-content"
        >
          {isMac ? '⌘' : 'Ctrl'}K
        </button>
      </div>

      <div className="ml-auto flex items-center gap-2">
        <ProjectSwitcher />
        <NotificationBell />
        <ChatBadge />
        <AvatarMenu />
      </div>
    </header>
  )
}
