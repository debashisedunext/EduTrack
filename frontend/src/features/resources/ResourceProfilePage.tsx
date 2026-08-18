import { Link, useParams, useSearchParams } from 'react-router-dom'
import { ArrowLeft, Info } from 'lucide-react'

import { useGetUserProfile360 } from '@/api/generated/users/users'
import { useListTickets } from '@/api/generated/tickets/tickets'
import { useRunReport } from '@/api/generated/reports/reports'
import { EmptyState } from '@/components/ui/empty-state'
import { ReportChart } from '@/features/reports/ReportChart'
import { ticketPath } from '@/features/tickets/detail/entityLinks'

/**
 * A-069 · S-28, the Resource 360° profile.
 *
 * <h2>Four calls, not one</h2>
 *
 * §S-28 asks for five things: current open tickets, the complete historical
 * list, an effort timeline, a velocity chart and a scorecard. Only the scorecard
 * is bespoke — the rest already exist as surfaces:
 *
 * - `GET /users/{id}/profile-360` — the scorecard and the stage load (A-069)
 * - `GET /tickets?assigneeId=` — the lists, already paginated and scoped (C-014)
 * - `GET /reports/resource-velocity?resourceId=` — closed *and* effort per week,
 *   which is both the velocity chart and the effort timeline (A-066)
 *
 * That is deliberately the opposite of A-052's "one call, not a waterfall of
 * six". A-052 is about a ticket detail whose parts are all small and all needed
 * at once; two of the five parts here are unbounded lists, and the honest way to
 * show a "complete historical ticket list" is the screen that already knows how
 * to page one.
 *
 * <h2>Reachable in one click, which is the point of the screen</h2>
 *
 * §S-28's last clause — "reachable by a Reporting Manager in one click from
 * anywhere their name appears" — is already satisfied by `resourcePath()`, which
 * C-019 put in `entityLinks` against the day this landed. Nothing else needed to
 * change for every assignee name in the product to become a link here.
 */
export function ResourceProfilePage() {
  const { userId = '' } = useParams()
  const [params] = useSearchParams()
  const id = Number(userId)

  const from = params.get('from') ?? undefined
  const to = params.get('to') ?? undefined

  const profile = useGetUserProfile360(id, { from, to })
  const person = profile.data?.data

  // Ten is a sample, not a page. The link beneath it goes to the real list,
  // which is the screen that knows how to page and filter properly.
  const openTickets = useListTickets({ assigneeId: id, excludeClosed: true, limit: 10 })
  const velocity = useRunReport('resource-velocity', { resourceId: id, from, to })

  if (profile.isLoading) {
    return <div className="p-6 text-sm text-content-muted">Loading profile…</div>
  }

  /*
    404 covers both "no such person" and "not yours to see" — the server does
    not distinguish them, deliberately, so that ids cannot be walked to learn
    who exists. The screen must not invent a distinction either.
  */
  if (profile.isError || !person) {
    return (
      <div className="p-6">
        <BackLink />
        <EmptyState
          title="Profile not available"
          description="This resource does not exist, or is not one you can view."
        />
      </div>
    )
  }

  const velocityBody = velocity.data
  const velocityPayload = velocityBody instanceof Blob ? undefined : velocityBody
  const velocityRows = velocityPayload?.data?.rows ?? []
  const velocityColumns = velocityPayload?.data?.columns ?? []

  const tickets = openTickets.data?.data ?? []

  return (
    <div className="p-6">
      <BackLink />

      <header className="mb-5">
        <h1 className="text-h2 font-semibold text-content">{person.person.fullName}</h1>
        <p className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-caption text-content-muted">
          <span>{person.person.role}</span>
          {person.person.designation && <span>· {person.person.designation}</span>}
          {person.person.department && <span>· {person.person.department}</span>}
          {/* By name, never a link — a link would be a second profile the viewer
              may not be entitled to open, and checking that here is a permission
              question a header has no business asking. */}
          {person.person.managerName && <span>· reports to {person.person.managerName}</span>}
          {person.person.active === false && (
            <span className="rounded-chip bg-subtle px-2 py-0.5 text-content-muted">Inactive</span>
          )}
        </p>
      </header>

      {/*
        The window is printed rather than assumed. Every tile except "Open now"
        is measured over it, and a scorecard that did not say so would leave a
        reader quoting a figure for a period they had guessed.
      */}
      <p className="mb-4 flex items-center gap-2 text-caption text-content-muted">
        <Info className="h-3.5 w-3.5 shrink-0" aria-hidden />
        Figures cover {person.from} to {person.to}. “Open now” is current.
      </p>

      <section aria-label="Scorecard" className="mb-6 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
        <Tile label="Open now" value={person.openNow} />
        <Tile label="Closed" value={person.closedInWindow} />
        <Tile label="Effort" value={person.effortHours} suffix="h" />
        <Tile label="SLA met" value={person.slaCompliancePct} suffix="%" />
        <Tile label="Rework rate" value={person.reworkRatePct} suffix="%" />
      </section>

      {(person.currentStages ?? []).length > 0 && (
        <section aria-labelledby="stage-load" className="mb-6">
          <h2 id="stage-load" className="mb-2 text-caption font-semibold uppercase tracking-wide text-content-muted">
            Where their open work is sitting
          </h2>
          <ul className="flex flex-wrap gap-2">
            {(person.currentStages ?? []).map((s) => (
              <li
                key={s.stage}
                className="rounded-chip border border-border bg-surface px-3 py-1 text-caption text-content"
              >
                {s.stage} · <span className="font-semibold">{s.openCount}</span>
              </li>
            ))}
          </ul>
        </section>
      )}

      {velocityRows.length > 0 && (
        <section aria-labelledby="velocity" className="mb-6">
          <h2 id="velocity" className="mb-2 text-caption font-semibold uppercase tracking-wide text-content-muted">
            Closed and effort per week
          </h2>
          <ReportChart
            chart="line"
            columns={velocityColumns}
            rows={velocityRows}
            title={`${person.person.fullName} — velocity`}
          />
        </section>
      )}

      <section aria-labelledby="open-tickets">
        <h2 id="open-tickets" className="mb-2 text-caption font-semibold uppercase tracking-wide text-content-muted">
          Open tickets
        </h2>

        {tickets.length === 0 ? (
          <p className="text-sm text-content-muted">Nothing open.</p>
        ) : (
          <ul className="mb-3 divide-y divide-border rounded-control border border-border bg-surface">
            {tickets.map((t) => (
              <li key={t.ticketCode} className="px-3 py-2 text-sm">
                <Link
                  to={ticketPath(t.ticketCode)}
                  className="text-primary hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                >
                  {t.ticketCode}
                </Link>
                <span className="ml-2 text-content">{t.title}</span>
                <span className="ml-2 text-caption text-content-muted">{t.status}</span>
              </li>
            ))}
          </ul>
        )}

        {/* §S-28's "complete historical ticket list" — the real list screen,
            which pages and filters, rather than a second one embedded here. */}
        <div className="flex flex-wrap gap-3 text-caption">
          <Link
            to={`/tickets?assigneeId=${id}&excludeClosed=true`}
            className="text-primary hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          >
            All open tickets →
          </Link>
          <Link
            to={`/tickets?assigneeId=${id}`}
            className="text-primary hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          >
            Complete history →
          </Link>
        </div>
      </section>
    </div>
  )
}

/**
 * One scorecard figure.
 *
 * Renders an em dash for null rather than 0. The server sends null when the
 * question has no answer for the window — nothing closed, so nothing could have
 * been on time — and 0% is a measurement, which is a different claim.
 */
function Tile({ label, value, suffix }: { label: string; value: number | null | undefined; suffix?: string }) {
  return (
    <div className="rounded-control border border-border bg-surface p-3">
      <p className="text-caption text-content-muted">{label}</p>
      <p className="mt-1 text-h3 font-semibold text-content">
        {value === null || value === undefined ? '—' : `${value}${suffix ?? ''}`}
      </p>
    </div>
  )
}

function BackLink() {
  return (
    <Link
      to="/reports"
      className="mb-4 inline-flex items-center gap-1 text-caption text-content-muted hover:text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
    >
      <ArrowLeft className="h-3.5 w-3.5" aria-hidden />
      Reports
    </Link>
  )
}
