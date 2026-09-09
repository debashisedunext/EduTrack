import { Link } from 'react-router-dom'

import { Chip } from '@/components/ui/chip'
import { cn } from '@/lib/utils'

import { usePortalAuthStore } from './auth/portalAuthStore'
import { usePortalSignOut } from './auth/usePortalSignOut'

/**
 * CP-02 · the module chooser — `LauncherPage`'s shape, one principal type
 * over. Ticketing and Onboarding cards, only one of which is guaranteed:
 * {@link ClientPrincipal}'s own doc says at least one of `hasTicketing`/
 * `hasOnboarding` is always true, never both guaranteed.
 *
 * The Ticketing card links to `/portal/tickets` — CP-06/07, C-122's
 * `PortalTicketListPage`/`PortalTicketDetailPage`, registered on the same
 * branch with no change needed here, exactly as this comment anticipated
 * while that route did not yet exist.
 */
export function PortalModuleChooserPage() {
  const user = usePortalAuthStore((state) => state.user)
  const signOut = usePortalSignOut()

  const hasTicketing = user?.hasTicketing ?? false
  const hasOnboarding = user?.hasOnboarding ?? false
  const firstName = user?.displayName?.trim().split(/\s+/)[0]

  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-6 bg-app p-6">
      <div className="flex items-center gap-2.5 text-lg font-bold text-content">
        <span
          aria-hidden="true"
          className="flex size-[34px] items-center justify-center rounded-[9px] bg-primary text-base font-extrabold text-white"
        >
          E
        </span>
        EduTrack
      </div>

      <div className="text-center">
        <h1 className="text-balance text-2xl font-semibold leading-8 text-content">
          {firstName ? `Welcome back, ${firstName}` : 'Welcome back'}
        </h1>
        <p className="mt-1.5 text-xs leading-4 text-content-muted">Where would you like to go?</p>
      </div>

      <div className="flex flex-wrap justify-center gap-5">
        <ModuleCard
          to="/portal/tickets"
          title="Ticketing"
          blurb="View your own tickets and their progress."
          glyph="🎫"
          glyphClass="bg-level-medium-soft"
          entitled={hasTicketing}
        />
        <ModuleCard
          to="/portal/onboarding"
          title="Onboarding"
          blurb="Complete your prerequisites and track your journey to Live."
          glyph="🧭"
          glyphClass="bg-primary-soft"
          entitled={hasOnboarding}
        />
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

function ModuleCard({
  to,
  title,
  blurb,
  glyph,
  glyphClass,
  entitled,
}: {
  to: string
  title: string
  blurb: string
  glyph: string
  glyphClass: string
  entitled: boolean
}) {
  const shell = 'block w-80 max-w-full rounded-card border border-border bg-surface p-6 text-left shadow-rest'
  const body = (
    <>
      <span
        aria-hidden="true"
        className={cn('mb-3.5 flex size-11 items-center justify-center rounded-[11px] text-xl', glyphClass)}
      >
        {glyph}
      </span>
      <span className="block text-base font-semibold leading-6 text-content">{title}</span>
      <span className="mb-3 mt-1.5 block text-xs leading-4 text-content-muted">{blurb}</span>
      {!entitled ? <Chip variant="neutral">Not available on your account</Chip> : null}
    </>
  )

  if (!entitled) {
    return (
      <div className={cn(shell, 'opacity-55')} aria-label={`${title} — not available`}>
        {body}
      </div>
    )
  }

  return (
    <Link
      to={to}
      aria-label={`Open ${title}`}
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
