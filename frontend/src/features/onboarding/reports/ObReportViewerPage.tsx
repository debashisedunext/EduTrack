import { Link, useParams, useSearchParams } from 'react-router-dom'
import { ArrowLeft, Info } from 'lucide-react'
import { useListObReports, useRunObReport } from '@/api/generated/onboarding/onboarding'
import { EmptyState } from '@/components/ui/empty-state'
import type { ObRag } from '@/api/generated/model'

import { ObReportChart } from './ObReportChart'
import { ObReportExportButtons } from './ObReportExportButtons'
import { ObReportFilterBar } from './ObReportFilterBar'
import { ObReportTable } from './ObReportTable'

/**
 * The mockup's six tabs (`vReports()`), in its order, keyed to the
 * catalogue's report keys. A tab renders only when the catalogue names its
 * key as available — the vocabulary of what exists stays server-side, the
 * mockup only contributes the short labels and the order.
 */
const MOCKUP_TABS: readonly { key: string; label: string }[] = [
  { key: 'journey-funnel', label: 'Funnel' },
  { key: 'tat-compliance', label: 'TAT compliance' },
  { key: 'time-to-live', label: 'Time to live' },
  { key: 'sales-pipeline', label: 'Sales pipeline' },
  { key: 'stuck-and-aging', label: 'Stuck & aging' },
  { key: 'signoff-pending', label: 'Sign-offs pending' },
]

/**
 * B-122 · OB-10's parameterised viewer, aligned to the mockup's reports
 * screen: a "Reports" page head with the pre-aggregated-summaries caption and
 * the export button, a tab row over the six core reports, and the current
 * report's body in a card beneath. The hub + per-report routing structure is
 * kept — each tab is a link to `/onboarding/reports/{key}`, so a tab is a
 * bookmarkable report exactly as before.
 *
 * <h2>Filter state lives in the URL</h2>
 *
 * The same decision as the ticketing hub and the client list, for the same
 * reason: a report somebody has narrowed to one product and a quarter is a
 * thing they send to a colleague or bookmark, and both break the moment the
 * filters are held in React. It also makes the back button step through filter
 * changes, which on a reporting screen is what people expect.
 *
 * <h2>The filter bar is drawn from the descriptor</h2>
 *
 * Not from a constant here. The server says which filters a report honours, and
 * drawing one it ignores is worse than omitting it.
 */
export function ObReportViewerPage() {
  const { reportKey = '' } = useParams()
  const [params] = useSearchParams()

  const productId = params.get('productId')
  const obClientId = params.get('obClientId')
  const ownerUserId = params.get('ownerUserId')
  const rag = params.get('rag')

  // The catalogue is already cached by the hub in the ordinary case — this is a
  // second consumer of one query, not a second request. Fetched here at all
  // because the viewer is a bookmarkable URL somebody can land on directly.
  const catalogue = useListObReports()
  const descriptor = catalogue.data?.data.reports.find((r) => r.key === reportKey)

  const report = useRunObReport(reportKey, {
    from: params.get('from') ?? undefined,
    to: params.get('to') ?? undefined,
    productId: productId ? Number(productId) : undefined,
    obClientId: obClientId ? Number(obClientId) : undefined,
    /*
      Sent, and the server may ignore it. For an OB_STEP_OWNER `runObReport`
      overrules this to the caller themselves rather than refusing — answering
      it would let one implementor read a colleague's scorecard by guessing a
      user id. `meta.appliedScope` below is what says so on screen; without that
      line, a filter that did nothing and a filter that matched nothing look
      identical.
    */
    ownerUserId: ownerUserId ? Number(ownerUserId) : undefined,
    rag: (rag as ObRag | null) ?? undefined,
  })

  const availableReports = catalogue.data?.data.reports.filter((r) => r.available) ?? []
  const tabs = MOCKUP_TABS.filter((tab) => availableReports.some((r) => r.key === tab.key))

  if (catalogue.isLoading) {
    return <div className="p-6 text-sm text-content-muted">Loading…</div>
  }

  // Unknown key, and one the server declares unbuilt. Both are a 404 from the
  // runner; the catalogue is what lets this screen say which.
  if (!descriptor) {
    return (
      <div className="p-6">
        <BackLink />
        <EmptyState
          title="No such report"
          description="This report does not exist. It may have been renamed since the link was saved."
        />
      </div>
    )
  }

  if (!descriptor.available) {
    return (
      <div className="p-6">
        <BackLink />
        <h1 className="mb-2 text-h2 font-semibold text-content">{descriptor.title}</h1>
        <EmptyState title="Not built yet" description={descriptor.unavailableReason ?? ''} />
      </div>
    )
  }

  /*
    The generated type is `ObReportResponse | Blob`, because the contract
    declares both a JSON body and an octet-stream for 200 — `?export=` streams a
    file. This screen never sends `export` (the buttons fetch it themselves, to
    keep the token in a header), so the Blob arm is unreachable here; it is
    narrowed rather than cast away so that a future caller has to handle the
    other arm deliberately instead of finding it already erased.
  */
  const body = report.data
  const payload = body instanceof Blob ? undefined : body

  const rows = (payload?.data?.rows ?? []) as Record<string, unknown>[]
  const columns = payload?.data?.columns ?? []
  const appliedScope = payload?.meta?.appliedScope

  return (
    <div className="p-6">
      <BackLink />

      {/* ── the mockup's page head: Reports · caption · export ─────────── */}
      <header className="mb-4 flex flex-wrap items-start gap-3">
        <div>
          <h1 className="text-h2 font-semibold text-content">Reports</h1>
          <p className="mt-1 text-caption text-content-muted">
            All figures from pre-aggregated summaries — dashboards never run live counts.
          </p>
        </div>
        <div className="ml-auto">
          <ObReportExportButtons reportKey={descriptor.key} params={params} />
        </div>
      </header>

      {/* ── the mockup's tab row over the six core reports ─────────────── */}
      {tabs.length > 0 && (
        <nav
          role="tablist"
          aria-label="Onboarding reports"
          className="mb-4 flex flex-wrap gap-1 border-b border-border"
        >
          {tabs.map((tab) => {
            const active = tab.key === reportKey
            return (
              <Link
                key={tab.key}
                to={`/onboarding/reports/${tab.key}`}
                role="tab"
                aria-selected={active}
                className={
                  '-mb-px rounded-t-control border-b-2 px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary '
                  + (active
                    ? 'border-primary font-medium text-primary'
                    : 'border-transparent text-content-muted hover:bg-subtle hover:text-content')
                }
              >
                {tab.label}
              </Link>
            )
          })}
        </nav>
      )}

      <div className="rounded-card border border-border bg-surface p-5">
        <h2 className="text-h3 font-semibold text-content">{descriptor.title}</h2>
        {descriptor.description && (
          <p className="mb-3 mt-0.5 text-caption text-content-muted">{descriptor.description}</p>
        )}

        <ObReportFilterBar filters={descriptor.filters} />

        {appliedScope && (
          <p className="mb-4 flex items-center gap-2 text-caption text-content-muted">
            <Info className="h-3.5 w-3.5 shrink-0" aria-hidden />
            Showing {appliedScope}.
          </p>
        )}

        {report.isLoading && <p className="text-sm text-content-muted">Running the report…</p>}

        {report.isError && (
          <p role="alert" className="text-sm text-danger-text">
            This report could not be run. Check the filters, or refresh to try again.
          </p>
        )}

        {!report.isLoading && !report.isError && rows.length === 0 && (
          <EmptyState
            title="Nothing to show"
            description="No data was recorded for this filter and date range."
          />
        )}

        {rows.length > 0 && (
          <>
            {/*
              Chart AND table, never the chart alone — a chart cannot be read for
              an exact value, and this is the screen people open to get a number
              they intend to quote. A table-only report (chart === null) simply
              renders no chart.
            */}
            {descriptor.chart && (
              <ObReportChart
                chart={descriptor.chart}
                columns={columns}
                rows={rows}
                title={descriptor.title}
              />
            )}
            <ObReportTable columns={columns} rows={rows} />
          </>
        )}
      </div>
    </div>
  )
}

function BackLink() {
  return (
    <Link
      to="/onboarding/reports"
      className="mb-4 inline-flex items-center gap-1 text-caption text-content-muted hover:text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
    >
      <ArrowLeft className="h-3.5 w-3.5" aria-hidden />
      All reports
    </Link>
  )
}
