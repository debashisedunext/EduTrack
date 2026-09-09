import { Link, useLocation } from 'react-router-dom'
import {
  LayoutDashboard, ListChecks, Inbox, Ticket, FolderKanban, MessageSquare,
  BarChart3, CalendarClock, Database, ScrollText, Settings, ChevronsLeft, ChevronsRight,
  Building2, PlusCircle, Timer, Mail, ShieldCheck,
} from 'lucide-react'
import { useAuthStore } from '@/features/auth/authStore'
import { useSidebarStore } from './sidebarStore'
import { cn } from '@/lib/utils'

interface NavItem {
  to: string
  label: string
  icon: typeof LayoutDashboard
  adminOnly?: boolean
  /*
    Overrides the default prefix match, which lights two rows at once wherever
    one destination sits under another — `/onboarding/clients` and
    `/onboarding/clients/new` being the pair that forced this. Only supplied
    where the default is wrong.
  */
  isActive?: (pathname: string) => boolean
}

/** A labelled break in the list. Renders as a rule when the rail is collapsed. */
interface NavSection {
  section: string
}

type NavEntry = NavItem | NavSection

const isSection = (entry: NavEntry): entry is NavSection => 'section' in entry

// Left sidebar, collapsible 240px — blueprint §7.2.
const TICKETING_NAV: NavEntry[] = [
  { to: '/dashboard', label: 'Dashboard', icon: LayoutDashboard },
  { to: '/my-tasks', label: 'My Tasks', icon: ListChecks },
  /*
    C-062 · S-31. Beside My Tasks rather than below Tickets, because for QA and
    Deployment it *is* My Tasks — §17 item 12: "QA and Deployment are
    queue-driven teams, not assignment-driven ones", and `LandingRoutes` already
    sends those two roles here on sign-in.

    Shown to every role and not gated to those two. A Developer watching the QA
    queue is how they see their own handoff land, which is the walkthrough §16
    describes, and `StageQueueSubscriptionScope` allows exactly that on the
    matching WebSocket room for the same reason. The stage picker defaults to
    the viewer's own team, so nobody has to know which queue is theirs.
  */
  { to: '/stages/queue', label: 'Stage Queue', icon: Inbox },
  { to: '/tickets', label: 'Tickets', icon: Ticket },
  { to: '/projects', label: 'Projects', icon: FolderKanban },
  { to: '/chat', label: 'Chat', icon: MessageSquare },
  { to: '/reports', label: 'Reports', icon: BarChart3 },
  /*
    B-063 · §21. Not `adminOnly`: every role has a week of their own to look at,
    and the three delivery roles are the ones who log the hours. Whose week they
    may open beyond their own is the server's decision, not this list's.
  */
  { to: '/timesheet', label: 'Timesheet', icon: CalendarClock },
  /*
    B-109 · the entry every `/onboarding/**` route's own comment has deferred
    to this task since B-112 — "there is no onboarding nav section yet". A
    single flat item, on the same call every other row here already makes:
    this list has no sub-menu concept to extend, and building one is a bigger
    change than one wizard task should make unilaterally to a file every
    stream's screens render inside. Points at the client list (OB-03) rather
    than the module launcher (`/launcher`) — that screen is the dual-module
    *chooser* shown once at sign-in, not a destination to return to, the same
    reason `/tickets` is this list's entry rather than a ticketing launcher.

    Not `adminOnly`, and there is no `onboardingOnly` to reach for: the
    onboarding module's roles (OB_ADMIN, OB_SALES, …) are a separate,
    server-only vocabulary this sidebar cannot see. Every route this points at
    already accepts that: `ObModuleGuard` answers a caller with no entitlement
    404, same as every other onboarding route today, and hiding the link for
    everyone would not change who can reach the screen — only whether they can
    find it.

    A-129 · the *module* half of that has since gained a client-side signal —
    `me.modules`, which A-116 added for the launcher — so this row now leads
    into a nav section rather than to a lone screen. The *role* half has not,
    and is what still keeps this entry ungated.
  */
  { to: '/onboarding/clients', label: 'Onboarding', icon: Building2 },
  { to: '/masters', label: 'Masters', icon: Database, adminOnly: true },
  // A-071 · S-16. adminOnly like Masters, and for a stronger reason: `audit.view`
  // is Admin's alone in §2, so for every other role this link is a 403 waiting to
  // happen. Hidden rather than disabled — a greyed entry advertises a screen
  // somebody cannot have, and the server still refuses it either way.
  { to: '/audit-logs', label: 'Audit log', icon: ScrollText, adminOnly: true },
  { to: '/settings', label: 'Settings', icon: Settings },
]

/*
  A-129 · the Onboarding module's own navigation.

  <h2>Only the screens that exist</h2>

  The design this follows has nine entries; two of them point at screens no
  task has built yet — Module Service (the journey-template *list*; C-102 built
  the designer, which is reachable only by template id) and Prerequisites
  master (B-124). They are deliberately absent rather than present-and-dead: a nav row
  that lands on a 404 is worse than no row, because it reads as a broken
  product rather than an unfinished one. Each is one line here the day its
  screen lands.

  <h2>Ungated, like the entry that leads here</h2>

  No row carries `adminOnly`. That flag reads the *platform* role
  (ADMIN/PM/DEVELOPER/…), and the entries below divide on the *onboarding*
  role (OB_ADMIN, OB_MANAGER, OB_SALES, …) — a different vocabulary, and one
  the session does not carry: `Me` has `modules` but no `moduleRoles`, though
  `AccessTokenIssuer` already mints the claim into the token. Gating on the
  platform role would be worse than not gating, since the two do not
  correspond: an onboarding OB_VIEWER may well be a platform ADMIN.

  So the Administration section is shown to everyone holding the module, and
  `ObModuleRoleFilter` refuses what the caller may not have — the same bargain
  the ticketing Onboarding entry already documents above. Exposing
  `moduleRoles` on `Me` is the follow-up that makes real gating possible; it is
  a contract change and does not belong in a navigation task.
*/
const ONBOARDING_NAV: NavEntry[] = [
  { to: '/onboarding/dashboard', label: 'Dashboard', icon: LayoutDashboard },
  {
    to: '/onboarding/clients',
    label: 'Clients',
    icon: Building2,
    // Stays lit on a client's detail page, which is where following a row goes.
    isActive: (p) => p.startsWith('/onboarding/clients') && p !== '/onboarding/clients/new',
  },
  {
    to: '/onboarding/clients/new',
    label: 'New client',
    icon: PlusCircle,
    isActive: (p) => p === '/onboarding/clients/new',
  },
  { to: '/onboarding/reports', label: 'Reports', icon: BarChart3 },
  { section: 'Administration' },
  { to: '/onboarding/module-access', label: 'Roles & module access', icon: ShieldCheck },
  { to: '/onboarding/settings', label: 'TAT & escalation', icon: Timer },
  { to: '/onboarding/templates', label: 'Notification templates', icon: Mail },
]

/** The module a path belongs to. The URL is the source of truth, not a store. */
const ONBOARDING_PREFIX = '/onboarding'

/**
 * Whether a row is the page being looked at.
 *
 * <p>The default reproduces what `NavLink` did before this file computed it
 * itself — the exact path, or anything nested under it, so `/masters` stays
 * current on `/masters/resources`. Rows whose destination contains another
 * row's say so themselves.
 */
function isEntryActive(entry: NavItem, pathname: string): boolean {
  if (entry.isActive) return entry.isActive(pathname)
  return pathname === entry.to || pathname.startsWith(`${entry.to}/`)
}

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
  const modules = useAuthStore((s) => s.user?.modules) ?? []
  const { pathname } = useLocation()

  /*
    A-129 · which navigation to draw.

    Keyed on the route rather than on a "current module" store, so the two
    cannot disagree — a deep link, a browser back button and a bookmark all
    arrive with the answer already in the URL, and there is no state to
    initialise or reset on sign-out.

    The entitlement is checked as well as the path: a caller without the
    onboarding grant who reaches an `/onboarding/**` URL gets 404s from the
    server for the data, and showing them that module's navigation would be
    the frontend disagreeing with the gate about what exists.
  */
  const inOnboarding =
    pathname.startsWith(ONBOARDING_PREFIX) && modules.includes('ONBOARDING')

  const entries = (inOnboarding ? ONBOARDING_NAV : TICKETING_NAV).filter(
    (entry) => isSection(entry) || !entry.adminOnly || isAdmin,
  )

  return (
    <aside
      className={cn(
        'flex h-full flex-col border-r border-border bg-surface transition-[width]',
        collapsed ? 'w-[72px]' : 'w-[240px]',
      )}
    >
      <div className="flex h-14 items-center gap-2 border-b border-border px-4">
        <div className="h-6 w-6 shrink-0 rounded bg-primary" aria-hidden />
        {!collapsed && (
          <span className="min-w-0">
            <span className="block truncate text-sm font-semibold tracking-wide text-content">
              EDUTRACK
            </span>
            {/* Which module you are in, said once, where the product is named. */}
            {inOnboarding && (
              <span className="block truncate text-[11px] leading-tight text-content-muted">
                Client Onboarding
              </span>
            )}
          </span>
        )}
      </div>

      <nav
        className="flex-1 space-y-1 overflow-y-auto p-2"
        aria-label={inOnboarding ? 'Onboarding' : 'Main'}
      >
        {entries.map((entry) =>
          isSection(entry) ? (
            collapsed ? (
              <hr key={entry.section} className="mx-2 my-2 border-t border-border" />
            ) : (
              <div
                key={entry.section}
                className="px-3 pb-1 pt-4 text-[11px] font-semibold uppercase tracking-wider text-content-muted"
              >
                {entry.section}
              </div>
            )
          ) : (
            <Link
              key={entry.to}
              to={entry.to}
              title={collapsed ? entry.label : undefined}
              /*
                `aria-current` and the highlight are decided together, from one
                predicate. `NavLink` would compute its own for the attribute
                and accept an override only for the class, so a row corrected
                visually would still announce itself as the current page —
                two of them, on the wizard route.
              */
              aria-current={isEntryActive(entry, pathname) ? 'page' : undefined}
              className={cn(
                'flex items-center gap-3 rounded-control px-3 py-2 text-sm font-medium transition-colors',
                'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
                isEntryActive(entry, pathname)
                  ? 'bg-primary-soft text-primary'
                  : 'text-content-muted hover:bg-subtle hover:text-content',
              )}
            >
              <entry.icon className="h-4 w-4 shrink-0" />
              {!collapsed && <span className="truncate">{entry.label}</span>}
            </Link>
          ),
        )}
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
