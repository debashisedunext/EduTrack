import { useState, type KeyboardEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { Search } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { ProjectSwitcher } from './ProjectSwitcher'
import { NotificationBell } from './NotificationBell'
import { ChatBadge } from './ChatBadge'
import { AvatarMenu } from './AvatarMenu'

// Top bar: global search, project switcher, notification bell, chat badge,
// avatar menu — blueprint §7.2. Jump-to-ticket via Ctrl+K is C-006's job;
// this is the plain-text fallback that always works.
export function TopBar() {
  const navigate = useNavigate()
  const [query, setQuery] = useState('')

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
          className="pl-9"
        />
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
