import { useState } from 'react'
import { Download } from 'lucide-react'
import { BASE, getAccessToken } from '@/api/http'

/**
 * A-064 · §7.8's "export to Excel/CSV/PDF", on the viewer.
 *
 * <h2>Why this is a fetch and not a link</h2>
 *
 * The obvious implementation is `<a href="/api/v1/reports/date-wise?export=xlsx"
 * download>`, and it does not work here: the access token lives in memory and is
 * attached by `http()`, so a browser-initiated navigation carries no
 * `Authorization` header and the server answers 401. The failure is also the
 * confusing kind — the browser saves the 401 body as a file called
 * `date-wise-2026-08-17.xlsx`, so the user gets a download that opens as
 * gibberish rather than an error they can read.
 *
 * So the file is fetched like any other request, turned into an object URL, and
 * handed to a synthetic anchor. The token never leaves the header, and a failure
 * is a message on screen instead of a corrupt download.
 *
 * <h2>The filename comes from the server</h2>
 *
 * `Content-Disposition` already carries `date-wise-2026-08-17.xlsx`, built by
 * `ReportExportService`. Re-deriving it here would be a second naming rule to
 * keep in step, and the one that drifted would be the one nobody noticed —
 * files would simply start arriving with yesterday's convention.
 */

const FORMATS = [
  { format: 'xlsx', label: 'Excel' },
  { format: 'csv', label: 'CSV' },
  { format: 'pdf', label: 'PDF' },
] as const

export function ReportExportButtons({
  reportKey,
  params,
}: {
  reportKey: string
  /** The viewer's current filters — an export must match what is on screen. */
  params: URLSearchParams
}) {
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function download(format: string) {
    setBusy(format)
    setError(null)
    try {
      const query = new URLSearchParams(params)
      query.set('export', format)

      const token = getAccessToken()
      const response = await fetch(`${BASE}/reports/${encodeURIComponent(reportKey)}?${query}`, {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      })

      if (!response.ok) {
        // Deliberately not saved to disk. A 400 or 500 body written out as a
        // .xlsx is the failure mode this whole component exists to avoid.
        throw new Error(`The export failed (${response.status}).`)
      }

      const blob = await response.blob()
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = filenameFrom(response.headers.get('Content-Disposition'), reportKey, format)
      document.body.appendChild(anchor)
      anchor.click()
      anchor.remove()
      // Without this the blob is held for the life of the document, so a user
      // exporting a dozen reports keeps a dozen files in memory.
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
      {error && (
        // role=alert, because the download happens without navigation — a
        // failure that only appeared in the console would leave the user
        // waiting for a file that is never coming.
        <span role="alert" className="text-caption text-danger-text">
          {error}
        </span>
      )}
    </div>
  )
}

/**
 * Reads the server's filename, falling back to the same shape if the header is
 * missing — which it is under a proxy that strips it, and in a test.
 */
function filenameFrom(disposition: string | null, reportKey: string, format: string): string {
  const match = disposition?.match(/filename="?([^"';]+)"?/i)
  return match?.[1] ?? `${reportKey}.${format}`
}
