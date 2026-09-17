import { Link, useLocation } from 'react-router-dom'
import {
  LayoutDashboard, ListChecks, Inbox, Ticket, FolderKanban, MessageSquare,
  BarChart3, CalendarClock, Database, ScrollText, Settings, ChevronsLeft, ChevronsRight,
  Building2, Timer, Mail, ShieldCheck, Layers, ClipboardList, Milestone, Package,
} from 'lucide-react'
import { useAuthStore } from '@/features/auth/authStore'
import { isObImplementor } from '@/features/onboarding/mytasks/myTasks'
import { useSidebarStore } from './sidebarStore'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'

interface NavItem {
  to: string
  label: string
  icon: typeof LayoutDashboard
  adminOnly?: boolean
  /*
    Shown only to the module role that owns onboarding tasks — the one the
    Module Service designer labels **Implementor**. Unlike `adminOnly` this is
    not an approximation: `Me.moduleRoles` carries the real onboarding role now,
    so the row appears for exactly the people whose screen it is.

    Same bargain as every other flag here: this decides what is easy to find,
    never what is permitted. `/onboarding/my-tasks` returns the caller's own
    tasks and has no parameter to say otherwise, so the worst a wrong answer
    does is hide a screen from somebody entitled to it.
  */
  stepOwnerOnly?: boolean
  /*
    Overrides the default prefix match, which lights two rows at once wherever
    one destination sits under another — `/onboarding/clients` and
    `/onboarding/clients/new` being the pair that forced this. Only supplied
    where the default is wrong.
  */
  isActive?: (pathname: string) => boolean
}

/**
 * A labelled break in the list. Renders as a rule when the rail is collapsed.
 *
 * <p>Carries `adminOnly` for the same reason an item does: a heading whose
 * every row is hidden is a heading over nothing, and the filter cannot infer
 * that from the section alone — it sees a flat list, not a tree.
 */
interface NavSection {
  section: string
  adminOnly?: boolean
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

  The design this follows has nine entries, and with Prerequisites master
  (B-124) landed, all nine now do. The rule that got them here stands for
  whatever arrives next: a screen is deliberately absent until it exists
  rather than present-and-dead, because a nav row that lands on a 404 reads
  as a broken product rather than an unfinished one. Each is one line here
  the day its screen lands — Roles & module access (A-117), Module Service
  (the journey-template *list*; C-102 built only the designer, reachable by
  template id, C-123 built this row) and Prerequisites master (B-124) each
  arrived that way, added below rather than left for the next task to notice
  this comment.

  <h2>Three rows are everyone's; the rest is Admin's</h2>

  Dashboard, Projects and Reports are the screens somebody opens this module
  to *work* in, and every holder of the module gets them. Clients and the
  whole Administration section carry `adminOnly`, because they are where the
  module is *configured* — a vocabulary of services, products, prerequisites
  and stages that the people running onboardings read the effects of rather
  than edit.

  <h3>The flag is an approximation, and knowingly so</h3>

  `adminOnly` reads the *platform* role (ADMIN/PM/DEVELOPER/…), while the
  division these rows actually want is the *onboarding* role (OB_ADMIN,
  OB_MANAGER, OB_SALES, …) — a different vocabulary, and one the session
  still does not carry: `Me` has `modules` but no `moduleRoles`, though
  `AccessTokenIssuer` already mints the claim into the token. The two do not
  correspond, so the mismatch is worth naming here rather than leaving to be
  discovered: a platform PM who is an onboarding OB_ADMIN loses these links
  and reaches the screens only by URL.

  That is the acceptable direction of the error, because **this list decides
  what is easy to find, not what is permitted**. `ObModuleRoleFilter` is the
  authority either way and refuses what the caller may not have whether or
  not a row was drawn — so the worst this gating can do is hide a screen from
  somebody entitled to it, never show one to somebody who is not. Exposing
  `moduleRoles` on `Me` is the follow-up that replaces the approximation with
  the real division; it is a contract change and does not belong in a
  navigation task.
*/
const ONBOARDING_NAV: NavEntry[] = [
  { to: '/onboarding/dashboard', label: 'Dashboard', icon: LayoutDashboard },
  /*
    The implementor's own queue, above Projects because for somebody who
    implements it *is* the first screen — the same argument Stage Queue makes
    one module over for QA and Deployment.

    <h3>Shown to every holder of the module, not only to OB_STEP_OWNER</h3>

    It was gated on that role first, because the screen is the implementor's.
    The gate is gone for two reasons, and the second is the stronger one.

    The weak one: it is safe to drop. `/onboarding/my-tasks` answers for the
    caller and has no parameter to say otherwise, so somebody who owns no task
    opens an empty queue — never a colleague's work, whatever role they hold.

    The real one: **a moderator can be assigned a task.** An OB Admin or
    Manager who owns one had no way to reach their own queue while the row was
    hidden from them, and "the screen exists but not for you" is worse than a
    row that is sometimes empty. The one-line way back is `stepOwnerOnly: true`
    on this entry — see the filter below, which still honours the flag.

    `isActive` is the default prefix match and wants no override: nothing else
    lives under `/onboarding/my-tasks` except the focused task, which is this
    row's own child and should light it.
  */
  { to: '/onboarding/my-tasks', label: 'My Tasks', icon: ListChecks },
  /*
    Projects leads, and Clients follows it.

    The order is the reverse of what the alphabet or the data model would
    suggest, and it is the order people work in: a project is what somebody
    opens this module to look at, and a client is a record they visit twice —
    once to add it, once to correct it. `Clients` used to be this row and
    carried the engagement facts itself; those live on the project now, which
    is what made the rename more than a relabel.

    Stays lit on a project's own page, which is where following a row goes, and
    on the New project form.
  */
  {
    to: '/onboarding/projects',
    label: 'Projects',
    icon: FolderKanban,
    isActive: (p) => p.startsWith('/onboarding/projects'),
  },
  /*
    The Clients master — four fields, with add, edit and delete on the list
    itself. This row used to read "New client" and led to the four-step OB-04
    wizard; there is no separate page to point at now, because adding a client
    is a dialog on the list.

    `isActive` excludes `/onboarding/clients/:id/products/...` deliberately:
    that path is a project surface reached from mail links, and lighting the
    Clients row while somebody reads a ribbon would name the wrong section.
  */
  {
    to: '/onboarding/clients',
    label: 'Clients',
    icon: Building2,
    adminOnly: true,
    isActive: (p) => p.startsWith('/onboarding/clients') && !p.includes('/products/'),
  },
  { to: '/onboarding/reports', label: 'Reports', icon: BarChart3 },
  { section: 'Administration', adminOnly: true },
  /*
    C-123 · Module Service leads the section, because it is the one entry the
    others are configured *against*: a Module Service is what a client buys,
    what a journey is instantiated from, and what every prerequisite, TAT and
    notification below it is ultimately attached to. Somebody setting the
    module up starts here, and somebody returning to change how onboarding
    behaves is most often changing a service.
  */
  { to: '/onboarding/journey-templates', label: 'Module Service', icon: Layers, adminOnly: true },
  /*
    OB-07 · Products — what a Module Service is written *for*. Directly under
    Module Service rather than above it, although a product logically comes
    first: Module Service leads the section by decision (see the comment on
    it), and a product is only ever configured so that a service can be
    written for it, so the two sit as a pair with the one somebody returns to
    most on top. Creating a product here is what makes it appear in the
    Module Service form's "For product" picker.
  */
  { to: '/onboarding/products', label: 'Products', icon: Package, adminOnly: true },
  // B-124 · Prerequisites master (OB-14) — the last of the design's nine
  // entries, added the day its screen landed, per the section comment above.
  {
    to: '/onboarding/prereq-master',
    label: 'Prerequisites master',
    icon: ClipboardList,
    adminOnly: true,
  },
  /*
    OB-15 · Implementation Stage — the tenth entry, and the first past the
    design's nine. Added on the same rule the section comment sets rather than
    as an exception to it: the screen exists, so the row does.

    Placed after Prerequisites master and before the two access/behaviour
    entries below, because it belongs to the same group as the two above it —
    Module Service, prerequisites and implementation stages are the *vocabulary*
    an onboarding is configured from, while roles, TAT and templates configure
    how the module behaves around it.
  */
  {
    to: '/onboarding/implementation-stages',
    label: 'Implementation steps',
    icon: Milestone,
    adminOnly: true,
  },
  {
    to: '/onboarding/module-access',
    label: 'Roles & module access',
    icon: ShieldCheck,
    adminOnly: true,
  },
  { to: '/onboarding/settings', label: 'TAT & escalation', icon: Timer, adminOnly: true },
  { to: '/onboarding/templates', label: 'Notification templates', icon: Mail, adminOnly: true },
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
  /*
    The onboarding role, from the same session the platform role comes from.

    This is the division the `adminOnly` note above calls an approximation, now
    that `Me` carries `moduleRoles` — `POST /auth/login` returns it inside the
    session and `authStore` already keeps it, so there is nothing to fetch.
  */
  const isStepOwner = useAuthStore((s) => isObImplementor(s.user))
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

  /*
    Sections are filtered on the same flag rather than waved through, which is
    the change from when only items carried one. An exempt heading was correct
    while no section was gated; with Administration's every row now Admin-only,
    exempting it would leave a label with nothing under it — worse than the row
    it hid, because it names the screens instead of omitting them.
  */
  const entries = (inOnboarding ? ONBOARDING_NAV : TICKETING_NAV).filter(
    (entry) => (!entry.adminOnly || isAdmin) && (!('stepOwnerOnly' in entry) || isStepOwner),
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
        {/*
          Visually hidden rather than dropped while the rail is collapsed, so
          a screen reader still hears which product and module it is in.
        */}
        <span className={collapsed ? 'sr-only' : 'min-w-0'}>
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
      </div>

      {/*
        The expand/collapse control sits above the menu, where it is found
        without scrolling and is the first thing the rail offers whichever
        state it is in. It used to be the last row; on a long onboarding
        Administration section that put it below the fold.
      */}
      <button
        type="button"
        onClick={toggle}
        aria-expanded={!collapsed}
        aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
        title={collapsed ? 'Expand menu' : 'Collapse menu'}
        className={cn(
          'flex h-10 items-center gap-2 border-b border-border text-content-muted transition-colors',
          'hover:bg-subtle hover:text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
          collapsed ? 'justify-center' : 'px-4',
        )}
      >
        {collapsed ? (
          <ChevronsRight className="h-4 w-4" />
        ) : (
          <>
            <ChevronsLeft className="h-4 w-4" />
            <span className="text-xs font-medium">Collapse menu</span>
          </>
        )}
      </button>

      {/*
        One provider for the whole rail: `skipDelayDuration` is what makes the
        second tooltip open at once while the pointer sweeps down the icons,
        instead of each row waiting out its own delay.
      */}
      <TooltipProvider delayDuration={300} skipDelayDuration={500}>
        <nav
          className="flex-1 space-y-1 overflow-y-auto p-2"
          aria-label={inOnboarding ? 'Onboarding' : 'Main'}
        >
          {entries.map((entry) =>
            isSection(entry) ? (
              collapsed ? (
                <div key={entry.section} role="presentation">
                  {/* The rule is the visual break; the name stays for screen readers. */}
                  <hr className="mx-2 my-2 border-t border-border" />
                  <span className="sr-only">{entry.section}</span>
                </div>
              ) : (
                <div
                  key={entry.section}
                  className="px-3 pb-1 pt-4 text-[11px] font-semibold uppercase tracking-wider text-content-muted"
                >
                  {entry.section}
                </div>
              )
            ) : (
              <NavRow
                key={entry.to}
                entry={entry}
                collapsed={collapsed}
                active={isEntryActive(entry, pathname)}
              />
            ),
          )}
        </nav>
      </TooltipProvider>
    </aside>
  )
}

/**
 * One menu row.
 *
 * <p>Collapsed, the label leaves the row but not the DOM: it becomes `sr-only`
 * so the link keeps its accessible name, and a tooltip to the right shows the
 * page name on hover and on keyboard focus. Expanded, the label is drawn inline
 * and there is no tooltip — it would only repeat the text beside it.
 */
function NavRow({
  entry,
  collapsed,
  active,
}: {
  entry: NavItem
  collapsed: boolean
  active: boolean
}) {
  const link = (
    <Link
      to={entry.to}
      /*
        `aria-current` and the highlight are decided together, from one
        predicate. `NavLink` would compute its own for the attribute and accept
        an override only for the class, so a row corrected visually would still
        announce itself as the current page — two of them, on the wizard route.
      */
      aria-current={active ? 'page' : undefined}
      className={cn(
        'flex items-center gap-3 rounded-control py-2 text-sm font-medium transition-colors',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
        collapsed ? 'justify-center px-0' : 'px-3',
        active
          ? 'bg-primary-soft text-primary'
          : 'text-content-muted hover:bg-subtle hover:text-content',
      )}
    >
      <entry.icon className="h-4 w-4 shrink-0" />
      <span className={collapsed ? 'sr-only' : 'truncate'}>{entry.label}</span>
    </Link>
  )

  if (!collapsed) return link

  return (
    <Tooltip>
      <TooltipTrigger asChild>{link}</TooltipTrigger>
      <TooltipContent side="right" className="px-2 py-1 text-sm font-medium">
        {entry.label}
      </TooltipContent>
    </Tooltip>
  )
}
