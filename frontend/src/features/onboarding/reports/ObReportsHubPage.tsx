import { Link } from 'react-router-dom'
import { AlertCircle, BarChart3, Info } from 'lucide-react'
import { useListObReports } from '@/api/generated/onboarding/onboarding'
import type { ObReportCategory, ObReportDescriptor } from '@/api/generated/model'

/**
 * B-122 · OB-10's card grid.
 *
 * <h2>The cards come from the server</h2>
 *
 * A hardcoded list here would be a second copy of the backend's vocabulary, and
 * this catalogue is *known* to arrive in parts — six reports are built, five are
 * held as OB4b pending a product decision, and one is waiting on B-124's tables.
 * A constant in this file would have to be edited on the day each of those
 * lands, by somebody who does not know it exists, and the failure is a report
 * that is runnable by URL and invisible on the only screen that lists reports.
 *
 * <h2>An unavailable report is shown, not hidden</h2>
 *
 * Hiding it would make "not built yet" indistinguishable from "does not exist",
 * which for the OB4b five is precisely the question somebody is trying to
 * decide. So the card renders greyed, carrying the server's own sentence about
 * why — and as a `group` rather than a link, because a dead link to a 404 reads
 * as a bug rather than as a plan.
 */

/** The order groups appear in — roughly how often they are opened, not alphabetical. */
const CATEGORY_ORDER: ObReportCategory[] = ['DELIVERY', 'CLIENT', 'QUALITY', 'PIPELINE']

const CATEGORY_LABEL: Record<ObReportCategory, string> = {
  DELIVERY: 'Delivery',
  CLIENT: 'Clients',
  QUALITY: 'Quality',
  PIPELINE: 'Pipeline',
}

export function ObReportsHubPage() {
  const { data, isLoading, isError } = useListObReports()

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
        Said once above the grid rather than repeated on twelve cards. It is
        here at all because a Step Owner reading "TAT compliance by service and
        owner" would reasonably expect to pick a colleague — plan §3 gives them
        their own services, and the honest place to say so is before they open a
        report rather than after.
      */}
      {scopeNote && (
        <p className="mb-6 flex items-center gap-2 rounded-control bg-subtle px-3 py-2 text-caption text-content-muted">
          <Info className="h-4 w-4 shrink-0" aria-hidden />
          {scopeNote}
        </p>
      )}

      {CATEGORY_ORDER.map((category) => {
        const inGroup = reports.filter((report) => report.category === category)
        if (inGroup.length === 0) return null

        return (
          <section key={category} className="mb-8" aria-labelledby={`ob-reports-${category}`}>
            <h2
              id={`ob-reports-${category}`}
              className="mb-3 text-caption font-semibold uppercase tracking-wide text-content-muted"
            >
              {CATEGORY_LABEL[category]}
            </h2>
            <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {inGroup.map((report) => (
                <li key={report.key}>
                  <ObReportCard report={report} />
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
      {/* The mockup's caption, verbatim — it is a promise about how the figures
          are computed, and the never-live-counts rule is CLAUDE.md's own. */}
      <p className="mt-1 text-sm text-content-muted">
        All figures from pre-aggregated summaries — dashboards never run live counts.
      </p>
    </header>
  )
}

function ObReportCard({ report }: { report: ObReportDescriptor }) {
  if (!report.available) {
    return (
      <div
        className="flex h-full flex-col rounded-control border border-border bg-subtle p-4 opacity-75"
        // Not a link and not a button — there is nothing to activate. Marked as
        // a group so the reason is announced with the title rather than as a
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
      to={`/onboarding/reports/${report.key}`}
      className="flex h-full flex-col rounded-control border border-border bg-surface p-4 transition-colors hover:border-primary hover:bg-primary-soft focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
    >
      <div className="mb-1 flex items-start gap-2">
        <BarChart3 className="mt-0.5 h-4 w-4 shrink-0 text-primary" aria-hidden />
        <h3 className="text-sm font-medium text-content">{report.title}</h3>
      </div>
      {report.description && (
        <p className="text-caption text-content-muted">{report.description}</p>
      )}
    </Link>
  )
}
