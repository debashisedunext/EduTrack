import { Link } from 'react-router-dom'
import { MessageCircle } from 'lucide-react'
import { useListChatThreads } from '@/api/generated/chat/chat'

// Chat badge — unread count summed across threads — blueprint §7.2.
export function ChatBadge() {
  const { data } = useListChatThreads()
  const unreadCount = (data?.data ?? []).reduce((sum, t) => sum + (t.unreadCount ?? 0), 0)

  return (
    <Link
      to="/chat"
      aria-label={`Chat${unreadCount > 0 ? ` (${unreadCount} unread)` : ''}`}
      className="relative flex h-9 w-9 shrink-0 items-center justify-center rounded-control text-content-muted transition-colors hover:bg-subtle hover:text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
    >
      <MessageCircle className="h-4 w-4" />
      {unreadCount > 0 && (
        <span className="absolute right-1 top-1 flex h-4 min-w-4 items-center justify-center rounded-chip bg-danger px-1 text-[10px] font-semibold text-white">
          {unreadCount > 9 ? '9+' : unreadCount}
        </span>
      )}
    </Link>
  )
}
