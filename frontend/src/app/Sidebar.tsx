import { NavLink } from 'react-router-dom'
import {
  LayoutDashboard, ListChecks, Ticket, FolderKanban, MessageSquare,
  BarChart3, Database, ScrollText, Settings, ChevronsLeft, ChevronsRight,
} from 'lucide-react'
import { useAuthStore } from '@/features/auth/authStore'
import { useSidebarStore } from './sidebarStore'
import { cn } from '@/lib/utils'

interface NavItem {
  to: string
  label: string
  icon: typeof LayoutDashboard
  adminOnly?: boolean
}

// Left sidebar, collapsible 240px — blueprint §7.2.
const NAV_ITEMS: NavItem[] = [
  { to: '/dashboard', label: 'Dashboard', icon: LayoutDashboard },
  { to: '/my-tasks', label: 'My Tasks', icon: ListChecks },
  { to: '/tickets', label: 'Tickets', icon: Ticket },
  { to: '/projects', label: 'Projects', icon: FolderKanban },
  { to: '/chat', label: 'Chat', icon: MessageSquare },
  { to: '/reports', label: 'Reports', icon: BarChart3 },
  { to: '/masters', label: 'Masters', icon: Database, adminOnly: true },
  // A-071 · S-16. adminOnly like Masters, and for a stronger reason: `audit.view`
  // is Admin's alone in §2, so for every other role this link is a 403 waiting to
  // happen. Hidden rather than disabled — a greyed entry advertises a screen
  // somebody cannot have, and the server still refuses it either way.
  { to: '/audit-logs', label: 'Audit log', icon: ScrollText, adminOnly: true },
  { to: '/settings', label: 'Settings', icon: Settings },
]

export function Sidebar() {
  const collapsed = useSidebarStore((s) => s.collapsed)
  const toggle = useSidebarStore((s) => s.toggle)
  /*
    The role comes from the session the user signed in with, not from a second
    request for it.

    This used to call `useGetMe()`, and **`GET /api/v1/me` has never been
    implemented** — the contract declares it, `MeController` serves only
    `/me/password` and the 2FA routes, and `ContractConformanceTest` reports
    coverage rather than failing on it. So the call 404s, `me` is undefined,
    `isAdmin` is false for everybody, and every `adminOnly` entry below is
    hidden from every user including Admins. Masters has been unreachable from
    this sidebar for as long as it has existed; A-071's Audit log arrived into
    the same hole.

    The fix does not need the endpoint. `POST /auth/login` already returns the
    user inside its `Session`, `authStore` already keeps it, and `Me` carries
    `role` — so the answer was in memory the whole time and was being asked for
    again over HTTP. Reading it here is also the *more* correct source: it is
    the identity this session was issued for, so it cannot disagree with the
    token the requests are being made with.

    `GET /me` is still worth building — it is on the contract and a token
    outliving a role change is a real (if narrow) staleness window. That is its
    own task, not a prerequisite for the navigation working.
  */
  const isAdmin = useAuthStore((s) => s.user?.role) === 'ADMIN'

  const items = NAV_ITEMS.filter((item) => !item.adminOnly || isAdmin)

  return (
    <aside
      className={cn(
        'flex h-full flex-col border-r border-border bg-surface transition-[width]',
        collapsed ? 'w-[72px]' : 'w-[240px]',
      )}
    >
      <div className="flex h-14 items-center gap-2 border-b border-border px-4">
        <div className="h-6 w-6 shrink-0 rounded bg-primary" aria-hidden />
        {!collapsed && <span className="text-sm font-semibold tracking-wide text-content">EDUTRACK</span>}
      </div>

      <nav className="flex-1 space-y-1 overflow-y-auto p-2" aria-label="Main">
        {items.map(({ to, label, icon: Icon }) => (
          <NavLink
            key={to}
            to={to}
            title={collapsed ? label : undefined}
            className={({ isActive }) =>
              cn(
                'flex items-center gap-3 rounded-control px-3 py-2 text-sm font-medium transition-colors',
                'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
                isActive
                  ? 'bg-primary-soft text-primary'
                  : 'text-content-muted hover:bg-subtle hover:text-content',
              )
            }
          >
            <Icon className="h-4 w-4 shrink-0" />
            {!collapsed && <span className="truncate">{label}</span>}
          </NavLink>
        ))}
      </nav>

      <button
        type="button"
        onClick={toggle}
        aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
        className="flex h-10 items-center justify-center gap-2 border-t border-border text-content-muted transition-colors hover:bg-subtle hover:text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
      >
        {collapsed ? <ChevronsRight className="h-4 w-4" /> : <ChevronsLeft className="h-4 w-4" />}
      </button>
    </aside>
  )
}
