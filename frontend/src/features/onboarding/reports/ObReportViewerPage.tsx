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
 * B-122 · OB-10's parameterised viewer.
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

      <header className="mb-4">
        <h1 className="text-h2 font-semibold text-content">{descriptor.title}</h1>
        {descriptor.description && (
          <p className="mt-1 text-sm text-content-muted">{descriptor.description}</p>
        )}
      </header>

      <ObReportFilterBar filters={descriptor.filters} />

      {/*
        Below the filters, because an export is of what the filters currently
        select — putting it in the header would suggest it exports the report
        rather than this view of it.
      */}
      <div className="mb-4">
        <ObReportExportButtons reportKey={descriptor.key} params={params} />
      </div>

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
