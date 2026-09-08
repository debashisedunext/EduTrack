import { Link } from 'react-router-dom'

import { useGetDashboardSummary } from '@/api/generated/dashboard/dashboard'
import { useGetObDashboardSummary } from '@/api/generated/onboarding/onboarding'
import { Chip } from '@/components/ui/chip'
import { Skeleton } from '@/components/ui/skeleton'
import { useAuthStore } from '@/features/auth/authStore'
import { useSignOut } from '@/features/auth/useSignOut'
import { cn } from '@/lib/utils'

/**
 * A-116 · OB-01, the module launcher. Onboarding plan §2.2, and the prototype
 * this reimplements.
 *
 * <h2>Shell-less, like S-03 and for the same reason</h2>
 *
 * A chooser rendered inside the shell would be a page about picking a module
 * framed by a sidebar full of one module's links — a menu of dead ends, which
 * is the argument `/change-password` already makes two lines above it in the
 * router. It is a full-page, centred screen: logo, question, cards, sign out.
 *
 * <h2>Why this lives in its own feature folder</h2>
 *
 * It is the one screen belonging to both modules and neither. Under
 * `features/onboarding/` it would be forbidden from importing
 * `features/tickets` by A-115's rule, and the rule is right — a chooser is not
 * part of either thing it chooses between.
 *
 * <h2>Only the modules you hold are asked about</h2>
 *
 * The cards render from `me.modules`, which A-116 added to `/me`. That is
 * advisory in the sense `permissions` is — A-111's gate decides reachability
 * server-side and answers 404 — but load-bearing differently here: **the
 * summary call for a module you do not hold would itself 404**, so a launcher
 * that asked for both would spend a request to be told what `/me` already said
 * and flash an error while doing it. Hence `enabled` per query.
 *
 * A module you lack still draws its card, greyed and unclickable, saying who to
 * ask. Hiding it would answer "why can I not see onboarding?" with silence.
 */

/** The headline figure a card shows, or null when it is not knowable. */
function useTicketingCount(enabled: boolean) {
  const { data, isPending, isError } = useGetDashboardSummary(undefined, { query: { enabled } })
  if (!enabled) return { value: null, loading: false }
  const cards = data?.data?.cards
  const open = cards?.find((c) => c.key === 'open') ?? cards?.find((c) => c.key === 'total') ?? cards?.[0]
  return { value: isError ? null : (open?.value ?? null), loading: isPending }
}

function useOnboardingCounts(enabled: boolean) {
  const { data, isPending, isError } = useGetObDashboardSummary(undefined, { query: { enabled } })
  if (!enabled) return { ongoing: null, atRisk: null, loading: false }
  const cards = data?.data?.cards
  const pick = (key: string) => cards?.find((c) => c.key === key)?.count ?? null
  return {
    ongoing: isError ? null : (pick('ongoing-projects') ?? cards?.[0]?.count ?? null),
    atRisk: isError ? null : pick('overdue-clients'),
    loading: isPending,
  }
}

interface ModuleCardProps {
  to: string
  title: string
  blurb: string
  /** Emoji, matching the prototype. Decorative — the card's name carries the meaning. */
  glyph: string
  glyphClass: string
  entitled: boolean
  loading: boolean
  children: React.ReactNode
}

function ModuleCard({ to, title, blurb, glyph, glyphClass, entitled, loading, children }: ModuleCardProps) {
  const body = (
    <>
      <span
        aria-hidden="true"
        className={cn(
          'mb-3.5 flex size-11 items-center justify-center rounded-[11px] text-xl',
          glyphClass,
        )}
      >
        {glyph}
      </span>
      <span className="block text-base font-semibold leading-6 text-content">{title}</span>
      <span className="mb-3 mt-1.5 block text-xs leading-4 text-content-muted">{blurb}</span>
      <span className="flex flex-wrap gap-1.5">
        {loading ? <Skeleton className="h-5 w-28 rounded-chip" /> : children}
      </span>
    </>
  )

  const shell =
    'block w-80 max-w-full rounded-card border border-border bg-surface p-6 text-left shadow-rest'

  // Not a disabled <button>: an unentitled card is a statement, not a control,
  // and a disabled control is skipped by the keyboard so the sentence telling
  // somebody who to ask would never be reached by a screen-reader user tabbing
  // the page.
  if (!entitled) {
    return (
      <div className={cn(shell, 'opacity-55')} aria-label={`${title} — no access`}>
        {body}
      </div>
    )
  }

  return (
    <Link
      to={to}
      aria-label={`Open ${title} module`}
      className={cn(
        shell,
        'transition duration-150 ease-out hover:-translate-y-0.5 hover:shadow-modal',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2',
      )}
    >
      {body}
    </Link>
  )
}

export function LauncherPage() {
  const user = useAuthStore((s) => s.user)
  const signOut = useSignOut()

  const modules = user?.modules ?? []
  const hasTicketing = modules.includes('TICKETING')
  const hasOnboarding = modules.includes('ONBOARDING')

  const ticketing = useTicketingCount(hasTicketing)
  const onboarding = useOnboardingCounts(hasOnboarding)

  // First name only, as the prototype greets. Falls back to a question with no
  // name rather than "Where would you like to work, undefined?".
  const firstName = user?.displayName?.trim().split(/\s+/)[0]
  const count = modules.length

  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-6 bg-app p-6">
      <div className="flex items-center gap-2.5 text-lg font-bold text-content">
        <span
          aria-hidden="true"
          className="flex size-[34px] items-center justify-center rounded-[9px] bg-primary text-base font-extrabold text-white"
        >
          E
        </span>
        EduTrack Platform
      </div>

      <div className="text-center">
        <h1 className="text-balance text-2xl font-semibold leading-8 text-content">
          {firstName ? `Where would you like to work, ${firstName}?` : 'Where would you like to work?'}
        </h1>
        <p className="mt-1.5 text-xs leading-4 text-content-muted">
          {count > 0
            ? `You're entitled to ${count} module${count > 1 ? 's' : ''}. Access is granted per module by the admin.`
            : 'You have not been granted a module yet. Access is granted per module by the admin.'}
        </p>
      </div>

      <div className="flex flex-wrap justify-center gap-5">
        <ModuleCard
          to="/dashboard"
          title="Ticketing"
          blurb="Tasks, tickets, cycles and the Workflow Ribbon for delivery teams."
          glyph="🎫"
          glyphClass="bg-level-medium-soft"
          entitled={hasTicketing}
          loading={ticketing.loading}
        >
          {hasTicketing ? (
            <Chip variant="info">
              {/* An em dash, not a zero: "could not count" and "the count is
                  zero" are different facts and must not render identically. */}
              {ticketing.value === null ? '—' : ticketing.value.toLocaleString()} open tickets
            </Chip>
          ) : (
            <Chip variant="neutral">No access — ask your admin</Chip>
          )}
        </ModuleCard>

        <ModuleCard
          to="/onboarding/dashboard"
          title="Client Onboarding"
          blurb="Board new clients through a step-by-step journey to Live."
          glyph="🧭"
          glyphClass="bg-primary-soft"
          entitled={hasOnboarding}
          loading={onboarding.loading}
        >
          {hasOnboarding ? (
            <>
              <Chip className="bg-primary-soft text-primary">
                {onboarding.ongoing === null ? '—' : onboarding.ongoing.toLocaleString()} ongoing
                projects
              </Chip>
              {onboarding.atRisk !== null && onboarding.atRisk > 0 && (
                <Chip variant="danger">{onboarding.atRisk} overdue</Chip>
              )}
            </>
          ) : (
            <Chip variant="neutral">No access — ask your admin</Chip>
          )}
        </ModuleCard>
      </div>

      <button
        type="button"
        onClick={signOut}
        className="rounded-control border border-transparent bg-transparent px-3.5 py-2 font-medium text-primary hover:bg-subtle focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
      >
        Sign out
      </button>
    </div>
  )
}
