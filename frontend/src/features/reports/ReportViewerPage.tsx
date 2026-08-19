import { Link, useParams, useSearchParams } from 'react-router-dom'
import { ArrowLeft, Info } from 'lucide-react'
import { useListReports } from '@/api/generated/reports/reports'
import { useRunReport } from '@/api/generated/reports/reports'
import { EmptyState } from '@/components/ui/empty-state'

import { ReportChart } from './ReportChart'
import { ReportExportButtons } from './ReportExportButtons'
import { ReportFilterBar } from './ReportFilterBar'
import { ReportTable } from './ReportTable'

/**
 * A-063 · S-27's parameterised viewer.
 *
 * <h2>Filter state lives in the URL</h2>
 *
 * <p>Same decision as S-05 and the ticket list, for the same reason: a report
 * somebody has narrowed to one project and a fortnight is a thing they send to
 * a colleague or bookmark, and both break the moment the filters are held in
 * React. It also means the back button steps through filter changes, which on a
 * reporting screen is what people expect.
 *
 * <h2>The filter bar is drawn from the descriptor</h2>
 *
 * <p>Not from a constant here. The server says which filters a report honours,
 * and drawing one it ignores would be worse than omitting it — the user sets
 * it, nothing changes, and the only available conclusion is that the screen is
 * broken.
 */
export function ReportViewerPage() {
  const { reportKey = '' } = useParams()
  const [params] = useSearchParams()

  const from = params.get('from') ?? undefined
  const to = params.get('to') ?? undefined
  const projectId = params.get('projectId')
  const resourceId = params.get('resourceId')
  const clientId = params.get('clientId')

  // The catalogue is already cached by the hub in the ordinary case — this is
  // a second consumer of one query, not a second request. Fetched here at all
  // because the viewer is a bookmarkable URL somebody can land on directly.
  const catalogue = useListReports()
  const descriptor = catalogue.data?.data.reports.find((r) => r.key === reportKey)

  const report = useRunReport(reportKey, {
    from,
    to,
    projectId: projectId ? Number(projectId) : undefined,
    resourceId: resourceId ? Number(resourceId) : undefined,
    // B-060 · sent whatever the descriptor declares, like the two above it.
    // Which reports honour it is the server's statement — a report that does
    // not declare CLIENT has no control to set it, so the parameter is only
    // ever present when the filter bar put it in the URL.
    clientId: clientId ? Number(clientId) : undefined,
  })

  if (catalogue.isLoading) {
    return <div className="p-6 text-sm text-content-muted">Loading…</div>
  }

  // Unknown key, or one the server declares unbuilt. Both are a 404 from the
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
    The generated type is `ReportResponse | Blob`, because the contract declares
    both a JSON body and an octet-stream for 200 — `?export=` streams a file.
    This screen never sends `export`, so the Blob arm is unreachable here; it is
    narrowed rather than cast away so that A-064, which will send it, has to
    handle the other arm deliberately instead of finding it already erased.
  */
  const body = report.data
  const payload = body instanceof Blob ? undefined : body

  const rows = payload?.data?.rows ?? []
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

      <ReportFilterBar filters={descriptor.filters} />

      {/*
        A-064 · below the filters, because an export is of what the filters
        currently select — putting it in the header would suggest it exports the
        report rather than this view of it.
      */}
      <ReportExportButtons reportKey={descriptor.key} params={params} />

      {/*
        What the server actually narrowed to. Shown because a delivery role's
        resourceId is discarded silently: without this line, "the filter did
        nothing" and "the filter matched nothing" look identical on screen, and
        only one of them is a statement about the data.
      */}
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
            <ReportChart chart={descriptor.chart} columns={columns} rows={rows} title={descriptor.title} />
          )}
          <ReportTable columns={columns} rows={rows} />
        </>
      )}
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
      All reports
    </Link>
  )
}
