import { Link } from 'react-router-dom'
import {
  ArrowRight,
  Bell,
  Building2,
  CalendarClock,
  Flag,
  FolderKanban,
  ShieldCheck,
  Tag,
  Users,
  Workflow,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'

import { useAuthStore } from '@/features/auth/authStore'

/**
 * B-067 · the index the sidebar's Masters entry has led to since A-030 and
 * never actually reached — `/masters` rendered `ScreenPlaceholder` with a
 * hand-made list of links "until the masters index arrives with the rest of
 * M3". M3 has landed; this is that arrival, replacing the placeholder's list
 * one for one rather than inventing a new shape for it.
 *
 * <h2>Every entry the placeholder already had, none it didn't</h2>
 *
 * `/masters/modules` has no page of its own — B-064 built it as a picker
 * source for the ticket form, not a screen — so it is not a card here, the
 * same way the placeholder never linked to it. The workflow template designer
 * (`/masters/workflow/designer/:templateId`) is reached from tab 3 of
 * Statuses & workflow, not from here, per B-043's own note: a nav entry
 * beside it would read as a second, competing master.
 *
 * <h2>Permission-filtered, per the task — and what that turns out to mean</h2>
 *
 * The sidebar already hides `/masters` itself unless the signed-in role is
 * Admin, per blueprint §7.2 ("Masters (Admin)"). But that is a nav-link
 * convenience, not a route guard — `RequireAuth` opens every route to every
 * authenticated role on purpose (gating there would be frontend
 * authorisation, the thing its own comment warns against) — so a PM or
 * Support user who types `/masters` still reaches this page, and its cards
 * must not offer one that 403s.
 *
 * Checking every master controller's list route settles which cards that is.
 * Eight of the nine are `@PreAuthorize("isAuthenticated()")` — resources,
 * roles, projects, priorities, task types, statuses, the calendar and clients
 * are all readable by any signed-in role, the same "all six roles" argument
 * `ModuleController`'s javadoc makes for modules. Only notification templates
 * is `hasAuthority('master.write')` end to end, list route included — an
 * Admin-authoring screen with no reader role at all. That is the one card
 * `requiresPermission` gates; the other eight render for everyone this page
 * itself lets in.
 */

interface MasterCard {
  to: string
  label: string
  description: string
  icon: LucideIcon
  /** A capability code from the `permissions[]` JWT claim. Omitted means every signed-in role. */
  requiresPermission?: string
}

const MASTER_CARDS: MasterCard[] = [
  {
    to: '/masters/resources',
    label: 'Resources',
    description: 'People, their roles and reporting lines.',
    icon: Users,
  },
  {
    to: '/masters/roles',
    label: 'Roles & permissions',
    description: 'What each of the six roles may do.',
    icon: ShieldCheck,
  },
  {
    to: '/masters/projects',
    label: 'Projects',
    description: 'Project codes, teams, SLA matrices and settings.',
    icon: FolderKanban,
  },
  {
    to: '/masters/priorities',
    label: 'Priority levels',
    description: 'The priority a ticket can be raised at.',
    icon: Flag,
  },
  {
    to: '/masters/task-types',
    label: 'Task types',
    description: 'The kinds of work a ticket can be raised as.',
    icon: Tag,
  },
  {
    to: '/masters/statuses',
    label: 'Statuses & workflow',
    description: 'Stage names, transitions and the template designer.',
    icon: Workflow,
  },
  {
    to: '/masters/calendar',
    label: 'Working calendar',
    description: 'Weekends, holidays and leave that SLA maths honours.',
    icon: CalendarClock,
  },
  {
    to: '/masters/notification-templates',
    label: 'Notification templates',
    description: 'What each event, channel and recipient is sent.',
    icon: Bell,
    requiresPermission: 'master.write',
  },
  {
    to: '/masters/clients',
    label: 'Clients',
    description: 'Client records, contacts and the Excel import.',
    icon: Building2,
  },
]

export function MastersIndexPage() {
  const permissions = useAuthStore((s) => s.user?.permissions) ?? []
  const cards = MASTER_CARDS.filter(
    (card) => !card.requiresPermission || permissions.includes(card.requiresPermission),
  )

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6 p-6">
      <header>
        <h1 className="text-2xl font-semibold text-content">Masters</h1>
        <p className="mt-1 max-w-2xl text-sm text-content-muted">
          The reference data every ticket is built from.
        </p>
      </header>

      <ul className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
        {cards.map((card) => (
          <MasterCardTile key={card.to} card={card} />
        ))}
      </ul>
    </div>
  )
}

function MasterCardTile({ card }: { card: MasterCard }) {
  const Icon = card.icon
  return (
    <li>
      {/* The whole card is the link, not a div with an onClick, for the same
          reason ProjectIndexPage's cards are: an anchor keeps keyboard reach,
          the announced role and open-in-new-tab. */}
      <Link
        to={card.to}
        className="group flex h-full flex-col gap-2 rounded-card border border-border bg-surface p-4 transition-colors hover:border-primary"
      >
        <div className="flex items-start justify-between gap-2">
          <span className="flex items-center gap-2 font-medium text-content">
            <Icon className="size-4 shrink-0 text-content-muted" aria-hidden="true" />
            {card.label}
          </span>
          <ArrowRight
            className="size-4 shrink-0 text-content-muted transition-transform group-hover:translate-x-0.5"
            aria-hidden="true"
          />
        </div>
        <p className="text-sm text-content-muted">{card.description}</p>
      </Link>
    </li>
  )
}
