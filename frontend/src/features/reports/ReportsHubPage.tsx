import { Link } from 'react-router-dom'
import { AlertCircle, BarChart3, Info } from 'lucide-react'
import { useListReports } from '@/api/generated/reports/reports'
import type { ReportCategory, ReportDescriptor } from '@/api/generated/model'

/**
 * A-063 · S-27's card grid.
 *
 * <p>The list of cards comes from the server, not from a constant here. A
 * hardcoded list would be a second copy of the backend's vocabulary — the thing
 * `/me/notification-preferences` refuses in its own contract on the grounds
 * that a newly added event would silently fail to appear. A nineteenth report
 * has the same failure: runnable by URL, invisible on the only screen that
 * lists reports.
 */

/** The order groups appear in. Not alphabetical — this is roughly how often they are opened. */
const CATEGORY_ORDER: ReportCategory[] = ['DELIVERY', 'PEOPLE', 'QUALITY', 'WORKFLOW', 'OPERATIONS']

const CATEGORY_LABEL: Record<ReportCategory, string> = {
  DELIVERY: 'Delivery',
  PEOPLE: 'People',
  QUALITY: 'Quality',
  WORKFLOW: 'Workflow',
  OPERATIONS: 'Operations',
}

export function ReportsHubPage() {
  const { data, isLoading, isError } = useListReports()

  if (isLoading) {
    return (
      <div className="p-6">
        <Header />
        <p className="text-sm text-content-muted">Loading reports…</p>
      </div>
    )
  }

  if (isError || !data?.data) {
    return (
      <div className="p-6">
        <Header />
        <p role="alert" className="text-sm text-danger-text">
          The report catalogue could not be loaded. Refresh to try again.
        </p>
      </div>
    )
  }

  const { reports, scopeNote } = data.data

  return (
    <div className="p-6">
      <Header />

      {/*
        Stated once, above the grid, rather than repeated on eighteen cards.
        It is here at all because a Developer reading "Resource Performance
        Scorecard" would otherwise reasonably expect to pick a colleague — §2
        gives them their own performance, and the honest place to say so is
        before they open a report rather than after.
      */}
      {scopeNote && (
        <p className="mb-6 flex items-center gap-2 rounded-control bg-subtle px-3 py-2 text-caption text-content-muted">
          <Info className="h-4 w-4 shrink-0" aria-hidden />
          {scopeNote}
        </p>
      )}

      {CATEGORY_ORDER.map((category) => {
        const inGroup = reports.filter((r) => r.category === category)
        if (inGroup.length === 0) return null

        return (
          <section key={category} className="mb-8" aria-labelledby={`reports-${category}`}>
            <h2
              id={`reports-${category}`}
              className="mb-3 text-caption font-semibold uppercase tracking-wide text-content-muted"
            >
              {CATEGORY_LABEL[category]}
            </h2>
            <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {inGroup.map((report) => (
                <li key={report.key}>
                  <ReportCard report={report} />
                </li>
              ))}
            </ul>
          </section>
        )
      })}
    </div>
  )
}

function Header() {
  return (
    <header className="mb-6">
      <h1 className="text-h2 font-semibold text-content">Reports</h1>
      <p className="mt-1 text-sm text-content-muted">
        Every report the system can run. Open one to set filters and see its chart and table.
      </p>
    </header>
  )
}

/**
 * One card.
 *
 * <p>An unavailable report renders as a non-interactive card carrying its
 * reason, rather than being hidden or rendered as a dead link. Hiding it would
 * make "not built yet" indistinguishable from "does not exist"; a dead link
 * would send somebody to a 404 that reads as a bug.
 */
function ReportCard({ report }: { report: ReportDescriptor }) {
  if (!report.available) {
    return (
      <div
        className="flex h-full flex-col rounded-control border border-border bg-subtle p-4 opacity-75"
        // Not a link and not a button — there is nothing to activate. Marked as
        // a group so the reason is read with the title rather than as a
        // free-floating sentence.
        role="group"
        aria-label={`${report.title} — not available`}
      >
        <div className="mb-1 flex items-start gap-2">
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-content-muted" aria-hidden />
          <h3 className="text-sm font-medium text-content-muted">{report.title}</h3>
        </div>
        {report.description && (
          <p className="mb-2 text-caption text-content-muted">{report.description}</p>
        )}
        <p className="mt-auto text-caption text-content-muted">{report.unavailableReason}</p>
      </div>
    )
  }

  return (
    <Link
      to={`/reports/${report.key}`}
      className="flex h-full flex-col rounded-control border border-border bg-surface p-4 transition-colors hover:border-primary hover:bg-primary-soft focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
    >
      <div className="mb-1 flex items-start gap-2">
        <BarChart3 className="mt-0.5 h-4 w-4 shrink-0 text-primary" aria-hidden />
        <h3 className="text-sm font-medium text-content">{report.title}</h3>
      </div>
      {report.description && <p className="text-caption text-content-muted">{report.description}</p>}
    </Link>
  )
}
