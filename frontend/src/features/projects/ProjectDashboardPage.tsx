import { Link, useParams } from 'react-router-dom'
import { ArrowLeft, Info } from 'lucide-react'

import { useGetDashboardSummary } from '@/api/generated/dashboard/dashboard'
import type { ProjectDetail, ProjectMember, TicketSummary } from '@/api/generated/model'
import { useGetProject, useListProjectMembers } from '@/api/generated/projects/projects'
import { useListTickets } from '@/api/generated/tickets/tickets'
import { EmptyState } from '@/components/ui/empty-state'
import { DashboardWidgets } from '@/features/dashboard/DashboardWidgets'
import { KpiCard, KpiCardSkeleton } from '@/features/dashboard/KpiCard'
import { ticketPath } from '@/features/tickets/detail/entityLinks'

/**
 * A-077 · the project dashboard — the screen `PROJECT_ROUTE` has pointed at
 * since C-019 and which rendered a `ScreenPlaceholder` until now.
 *
 * <h2>No blueprint screen number, which is why the shape is argued here</h2>
 *
 * §7.9's screen list has S-10 (the project *master*, Stream B's) and S-28 (the
 * resource 360, A-069's) and nothing between them. This screen exists because
 * S-20's traceability rule makes every entity in the ticket summary panel a
 * link, and `TicketDetailPage` assigned the destinations: the resource 360 and
 * the project dashboard to Stream A, the client 360 to Stream B. A-069 built its
 * sibling; this one was still a stand-in and named in no backlog until Debashis
 * raised it.
 *
 * <h2>Four calls, not one — A-069's argument, reused because it still holds</h2>
 *
 * Nothing here is a new endpoint:
 *
 * - `GET /projects/{id}` — the header (B-014)
 * - `GET /dashboard/summary?projectId=` — the KPI row (A-054/A-055)
 * - `GET /dashboard/widgets?projectId=` — the charts, in one batched call (A-073)
 * - `GET /tickets?projectId=` — recent work, already paged and scoped (C-014)
 * - `GET /projects/{id}/members` — the team (B-016)
 *
 * The dashboard endpoints have accepted `?projectId=` since A-054. What A-077
 * adds on the server is not a filter — it is an *answer* for the case where the
 * filter names a project the caller is not on.
 *
 * <h2>The one thing that is not just composition</h2>
 *
 * Every dashboard query ANDs the caller's scope with the requested project, so
 * an out-of-scope project has always matched no rows. That is safe and it was
 * never a leak. But no rows renders as six cards reading 0 and ten empty charts,
 * which *states* that the project has no work — false about a project with five
 * hundred tickets, and exactly the failure A-056 named: "an empty series renders
 * as 'no tickets matched', which is a factual claim about the data and is false".
 *
 * That case was unreachable while `?projectId=` was only ever set from a picker
 * listing the caller's own projects. This screen makes it routine, because it is
 * reached by clicking a project name on a ticket and the project master is
 * deliberately **not** row-scoped — `ProjectController` explains that a PM who
 * could not see a project's name could not read the tickets they do own.
 *
 * So the server now returns `unavailableReason` on both the summary and the
 * widgets, and this page renders that sentence instead of zeroes. Decided
 * server-side although `GET /me` carries `projectIds` and the SPA could work it
 * out: CLAUDE.md scopes server-side and never by a frontend filter, and A-056
 * gave the same reason for `unavailableReason` itself — saying it explicitly is
 * what stops the client re-deriving a rule that would then drift.
 *
 * <h2>Not a 404, and that is a departure worth naming</h2>
 *
 * A-069 made a refusal and a missing resource the same 404, so ids could not be
 * walked to learn who exists. This screen does the opposite, because the
 * underlying decision is already the opposite: project identity is public to
 * every authenticated caller by Stream B's deliberate choice, visible in four
 * pickers and on every ticket. A 404 here would hide nothing and would contradict
 * the screen that linked here.
 */
/** §S-05 colours widgets 4 and 5; the rest are neutral. DashboardPage's map, verbatim. */
const TONE: Record<string, 'critical' | 'delayed'> = {
  critical: 'critical',
  delayed: 'delayed',
}

export function ProjectDashboardPage() {
  const { projectId = '' } = useParams()
  const id = Number(projectId)

  const project = useGetProject(id)
  const detail = project.data?.data

  const summary = useGetDashboardSummary({ projectId: id })
  const cards = summary.data?.data?.cards ?? []
  const withheld = summary.data?.data?.unavailableReason ?? null

  const members = useListProjectMembers(id, { query: { enabled: !withheld } })

  // Ten is a sample, not a page — A-069's rule. The link beneath goes to the
  // real list, which is the screen that knows how to filter, sort and page.
  const tickets = useListTickets(
    { projectId: id, limit: 10 },
    { query: { enabled: !withheld } },
  )

  if (project.isPending) {
    return <ProjectDashboardSkeleton />
  }

  // A project that genuinely does not exist. Distinct from the withheld case
  // below, which is a project that exists and whose figures are not this
  // caller's — conflating them would either leak or mislead.
  if (project.isError || !detail) {
    return (
      <div className="p-6">
        <EmptyState
          title="Project not found"
          description="No project with this id. It may have been removed."
        />
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-6 p-6">
      <Link
        to="/tickets"
        className="inline-flex items-center gap-1 text-sm text-content-muted hover:text-content"
      >
        <ArrowLeft className="size-4" aria-hidden="true" />
        Back to tickets
      </Link>

      <ProjectHeader detail={detail} />

      {withheld ? (
        /*
         * The whole reason the server was changed. Rendering the header above
         * this is deliberate: the project's name is not the secret, its figures
         * are, and showing a bare refusal with no context would leave somebody
         * who clicked a project name unsure they had even arrived.
         */
        <EmptyState
          title="Figures not shown for this project"
          description={withheld}
        />
      ) : (
        <>
          <section aria-label="Project summary">
            <div className="grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-6">
              {summary.isPending
                ? Array.from({ length: 6 }, (_, i) => <KpiCardSkeleton key={i} />)
                : cards.map((card) => (
                    <KpiCard
                      key={card.key}
                      label={card.label ?? card.key ?? ''}
                      value={card.value ?? 0}
                      deltaPct={card.deltaPct}
                      sparkline={card.sparkline ?? []}
                      drillDown={card.drillDown ?? '/tickets'}
                      tone={TONE[card.key ?? ''] ?? 'neutral'}
                    />
                  ))}
            </div>
          </section>

          {/*
           * Not a different dashboard — the dashboard with one filter pinned.
           * DashboardWidgets picks its own key list from useDashboardVariant, so
           * a delivery role gets their short list here exactly as on S-05 rather
           * than this screen inventing a second answer to "which widgets is this
           * person shown". That decision has moved twice already (A-057, A-059)
           * and a copy here would be the third place it could disagree.
           */}
          <section aria-label="Project charts">
            <DashboardWidgets params={{ projectId: id }} />
          </section>

          <div className="grid gap-6 lg:grid-cols-3">
            <RecentTickets projectId={id} rows={tickets.data?.data ?? []} />
            <TeamPanel members={members.data?.data ?? []} />
          </div>
        </>
      )}
    </div>
  )
}

function ProjectHeader({ detail }: { detail: ProjectDetail }) {
  return (
    <header className="rounded-card border border-border bg-surface p-5">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-xl font-semibold text-content">{detail.name}</h1>
        {/*
         * The code, not the id. It is the ticket-ID prefix every ticket in this
         * project carries, so it is the string somebody arriving from PAY-26-0142
         * is looking to confirm they are in the right place.
         */}
        <span className="rounded bg-subtle px-2 py-0.5 font-mono text-sm text-content-muted">
          {detail.projectCode}
        </span>
        {detail.status && (
          <span className="text-sm text-content-muted">{detail.status}</span>
        )}
      </div>

      <dl className="mt-3 flex flex-wrap gap-x-6 gap-y-1 text-sm text-content-muted">
        {detail.clientName && (
          <div className="flex gap-1">
            <dt>Client</dt>
            <dd className="text-content">{detail.clientName}</dd>
          </div>
        )}
        {detail.projectManager && (
          <div className="flex gap-1">
            <dt>Project manager</dt>
            <dd className="text-content">{detail.projectManager.displayName}</dd>
          </div>
        )}
        {detail.startDate && (
          <div className="flex gap-1">
            <dt>Started</dt>
            <dd className="text-content">{detail.startDate}</dd>
          </div>
        )}
      </dl>
    </header>
  )
}

function RecentTickets({ projectId, rows }: { projectId: number; rows: TicketSummary[] }) {
  return (
    <section className="lg:col-span-2 rounded-card border border-border bg-surface p-5" aria-label="Recent tickets">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-content">Recent tickets</h2>
        <Link
          to={`/tickets?projectId=${projectId}`}
          className="text-sm text-primary hover:underline"
        >
          Open full list
        </Link>
      </div>

      {rows.length === 0 ? (
        <p className="mt-3 text-sm text-content-muted">
          No tickets have been raised in this project yet.
        </p>
      ) : (
        <ul className="mt-3 divide-y divide-border">
          {rows.map((t) => (
            <li key={t.ticketId} className="flex items-center gap-3 py-2 text-sm">
              <Link to={ticketPath(t.ticketId)} className="font-mono text-primary hover:underline">
                {t.ticketId}
              </Link>
              <span className="min-w-0 flex-1 truncate text-content">{t.title}</span>
              <span className="text-content-muted">{t.level}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function TeamPanel({ members }: { members: ProjectMember[] }) {
  return (
    <section className="rounded-card border border-border bg-surface p-5" aria-label="Project team">
      <h2 className="text-sm font-semibold text-content">Team</h2>
      {members.length === 0 ? (
        <p className="mt-3 flex items-start gap-2 text-sm text-content-muted">
          <Info className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
          Nobody is assigned to this project yet.
        </p>
      ) : (
        <ul className="mt-3 space-y-2 text-sm">
          {members.map((m) => (
            <li key={m.userId} className="flex items-center justify-between gap-2">
              <span className="truncate text-content">{m.displayName}</span>
              {/*
               * projectRole falls back to the global role rather than printing
               * nothing. The generated model says null here means "same as their
               * global role" and is the common case, so an empty cell would read
               * as a person with no role at all on most rows.
               */}
              <span className="shrink-0 text-content-muted">{m.projectRole ?? m.role}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function ProjectDashboardSkeleton() {
  return (
    <div className="flex flex-col gap-6 p-6" aria-busy="true">
      <div className="h-24 animate-pulse rounded-card bg-subtle" />
      <div className="grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-6">
        {Array.from({ length: 6 }, (_, i) => (
          <KpiCardSkeleton key={i} />
        ))}
      </div>
    </div>
  )
}
