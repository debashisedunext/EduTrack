import { useState } from 'react'
import { Download } from 'lucide-react'
import { BASE, getAccessToken } from '@/api/http'

/**
 * A-071 · the "export only" half of S-16, on the screen.
 *
 * <h2>Fetched rather than linked, for A-064's reason</h2>
 *
 * <p>The obvious implementation is `<a href="/api/v1/audit-logs?export=xlsx"
 * download>` and it does not work: the access token lives in memory and is
 * attached by `http()`, so a browser-initiated navigation carries no
 * `Authorization` header. The server answers 403, the browser saves the problem
 * body as `audit-log-2026-08-18.xlsx`, and the user gets a download that opens
 * as gibberish instead of an error they can read.
 *
 * <p>So the file is fetched like any other request and handed to a synthetic
 * anchor. Written out here rather than shared with `ReportExportButtons`
 * because that component hardcodes three formats and a report key; extracting a
 * common one is a worthwhile tidy-up and not this task's, and the duplication
 * is fifteen lines rather than a rule that could drift.
 *
 * <h2>Two formats, not three</h2>
 *
 * <p>Reports offer PDF as well. This does not, and the contract agrees: a PDF
 * of an audit extract is a paginated document somebody would reasonably treat
 * as a signed record, and it is nothing of the kind. Excel and CSV both read as
 * what they are — a copy of some rows.
 */

const FORMATS = [
  { format: 'xlsx', label: 'Excel' },
  { format: 'csv', label: 'CSV' },
] as const

export function AuditExportButtons({ params }: { params: URLSearchParams }) {
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function download(format: string) {
    setBusy(format)
    setError(null)
    try {
      // The screen's current filters, verbatim. An export built from anything
      // else is an export that eventually disagrees with the table above it —
      // and on this screen that would be a file somebody quotes as the record.
      const query = new URLSearchParams(params)
      query.delete('cursor')
      query.set('export', format)

      const token = getAccessToken()
      const response = await fetch(`${BASE}/audit-logs?${query}`, {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      })

      if (!response.ok) {
        throw new Error(`The export failed (${response.status}).`)
      }

      const blob = await response.blob()
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = filenameFrom(response.headers.get('Content-Disposition'), format)
      document.body.appendChild(anchor)
      anchor.click()
      anchor.remove()
      URL.revokeObjectURL(url)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The export failed.')
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="mb-4 flex flex-wrap items-center gap-2">
      <span className="text-caption font-medium text-content-muted">Export</span>
      {FORMATS.map(({ format, label }) => (
        <button
          key={format}
          type="button"
          onClick={() => download(format)}
          disabled={busy !== null}
          className="inline-flex items-center gap-1.5 rounded-control border border-border bg-surface px-3 py-1.5 text-caption font-medium text-content transition-colors hover:bg-subtle disabled:opacity-60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        >
          <Download className="h-3.5 w-3.5" aria-hidden />
          {busy === format ? 'Preparing…' : label}
        </button>
      ))}
      {/*
        The cap, stated before the click rather than discovered afterwards. The
        file itself repeats it on the sheet — but somebody who needed the full
        range should learn to narrow the dates here, not from a spreadsheet they
        have already sent on.
      */}
      <span className="text-caption text-content-muted">
        Most recent 10,000 matching entries. Narrow the dates for more.
      </span>
      {error && (
        // role=alert: the download happens without navigation, so a failure that
        // only reached the console leaves the user waiting for a file that is
        // never coming.
        <span role="alert" className="text-caption text-danger-text">
          {error}
        </span>
      )}
    </div>
  )
}

function filenameFrom(disposition: string | null, format: string): string {
  const match = disposition?.match(/filename="?([^"';]+)"?/i)
  return match?.[1] ?? `audit-log.${format}`
}
