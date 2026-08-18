import { AlertCircle, CheckCircle2, Download, Loader2, XCircle } from 'lucide-react'
import { Link } from 'react-router-dom'

import { useGetImportBatch } from '@/api/generated/imports/imports'
import type { ImportBatch } from '@/api/generated/model'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

import {
  batchPollInterval,
  errorReportRefusal,
  isTerminal,
  progressPercent,
  useDownloadImportErrorReport,
} from './importQueries'

/**
 * S-34 step 5 — the commit, running. B-035, blueprint §4B.3.
 *
 * ## What this screen is for once the button has been pressed
 *
 * Nothing here is a decision. The user has already approved the preview and the
 * writing has started, so the only job left is to say truthfully how far it has
 * got and to stop when it stops. That sounds trivial and is where this kind of
 * screen usually goes wrong: a bar that sits at zero and then jumps is
 * indistinguishable from a job that has died, and a spinner with no end state
 * leaves a tab open all afternoon.
 *
 * ## The batch is polled, and the poll stops
 *
 * `refetchInterval` returns `false` once the status is terminal, so a finished
 * import is not still asking every two seconds an hour later. While it runs the
 * cost is close to nothing: the route carries an `ETag` and the server flushes
 * its counters every fifty rows, so most polls are a `304` with no body.
 *
 * ## The counts on screen are the server's, never the preview's
 *
 * They will agree, and reading them off the preview would still be wrong: the
 * preview describes what a commit *would* do and this describes what one *did*,
 * and the two differ exactly when something went wrong at write time — which is
 * the moment a user most needs the real number.
 */
export function CommitStep({
  batchId,
  fileName,
  onStartAnother,
}: {
  batchId: number
  /** Named on screen so a finished run says which file it was. */
  fileName: string
  onStartAnother: () => void
}) {
  const batch = useGetImportBatch(batchId, {
    query: {
      refetchInterval: (query) => batchPollInterval(query.state.data?.data.status),
      // A run that has finished is finished. Refetching it when the window
      // regains focus would be a request whose answer provably has not moved.
      refetchOnWindowFocus: (query) => !isTerminal(query.state.data?.data.status),
    },
  })

  if (batch.isError) {
    return (
      <p
        role="alert"
        className="flex items-start gap-2 rounded-control border border-danger bg-surface p-3 text-sm text-danger-text"
      >
        <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
        <span>
          The import was started, but its progress could not be read. It is still
          running — reload this page to check on it. Import #{batchId}.
        </span>
      </p>
    )
  }

  if (!batch.data) {
    return (
      <p className="flex items-center gap-2 text-sm text-content-muted" role="status">
        <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
        Starting the import…
      </p>
    )
  }

  return <CommitProgress batch={batch.data.data} fileName={fileName} onStartAnother={onStartAnother} />
}

function CommitProgress({
  batch,
  fileName,
  onStartAnother,
}: {
  batch: ImportBatch
  fileName: string
  onStartAnother: () => void
}) {
  const done = isTerminal(batch.status)
  const failed = batch.status === 'FAILED'
  const percent = progressPercent(batch.processed, batch.total)

  return (
    <div className="space-y-5">
      {/* ── the state, in one sentence ─────────────────────────────────── */}
      <div className="flex items-start gap-3">
        <StatusIcon status={batch.status} />
        <div className="min-w-0">
          <p className="text-sm font-medium text-content">{headline(batch, fileName)}</p>
          <p className="mt-0.5 text-sm text-content-muted">
            {/*
              The promise every step before this one made, closed out honestly.
              Steps 1 to 4 all said "nothing has been written"; saying nothing at
              all here would leave the user to wonder whether it still held.
            */}
            {done
              ? failed
                ? 'Some rows may have been written before it stopped. Re-importing the same file is safe — rows are matched on Client Code and updated, never duplicated.'
                : 'Rows are matched on Client Code: existing clients were updated, not duplicated.'
              : 'You can leave this page — the import keeps running. Import #' +
                batch.batchId +
                '.'}
          </p>
        </div>
      </div>

      {/* ── the bar ─────────────────────────────────────────────────────── */}
      <div>
        <div
          role="progressbar"
          aria-valuenow={percent}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-label={`Importing ${fileName}`}
          className="h-2 w-full overflow-hidden rounded-chip bg-subtle"
        >
          <div
            className={cn(
              'h-full transition-all duration-500',
              failed ? 'bg-danger' : done ? 'bg-success' : 'bg-primary',
            )}
            style={{ width: `${percent}%` }}
          />
        </div>
        {/*
          The numbers as well as the bar, and `role="status"` so a screen reader
          is told when they move rather than having to be pointed at the bar.
          `aria-valuenow` alone announces a percentage; "1,204 of 4,000 rows" is
          what a person watching an import actually wants to know.
        */}
        <p className="mt-2 text-sm text-content-muted" role="status">
          {batch.processed.toLocaleString()} of {batch.total.toLocaleString()}{' '}
          {batch.total === 1 ? 'row' : 'rows'} · {percent}%
        </p>
      </div>

      {/* ── the counts ──────────────────────────────────────────────────── */}
      <ul className="grid gap-3 sm:grid-cols-3" aria-label="Import results">
        <Count label="Created" value={batch.created} tone="success" />
        <Count label="Updated" value={batch.updated} tone="info" />
        <Count label="Skipped" value={batch.rejected} tone="danger" />
      </ul>

      {/* ── the error report · B-036 ────────────────────────────────────── */}
      {done && batch.rejected > 0 && <ErrorReport batch={batch} />}

      {done && (
        <div className="flex flex-wrap items-center gap-3 border-t border-border pt-4">
          <Button asChild>
            <Link to="/masters/clients">See the clients</Link>
          </Button>
          <Button type="button" variant="secondary" onClick={onStartAnother}>
            Import another file
          </Button>
        </div>
      )}
    </div>
  )
}

/**
 * B-036 · §4B.3's closing promise — "fix and re-upload just those rows".
 *
 * ## The button stays visible when there is no report
 *
 * Disabled rather than hidden, which is the shape it has had since B-035 and for
 * the reason recorded then: hiding it makes the screen look finished and leaves
 * the user with no account of the rows that did not land. What changed is the
 * sentence beside it — a run whose report could not be stored is a real state
 * (the object store was unreachable at the end of the run) and says so, rather
 * than promising a feature that has now shipped.
 *
 * ## The download is a mutation, and the failure is rendered
 *
 * A download is an event, not cached state — `importQueries` carries the whole
 * argument. And a fetch that 404s has to say something: the user is looking at a
 * count of skipped rows they were just told they could recover, so silence here
 * reads as a broken button.
 */
function ErrorReport({ batch }: { batch: ImportBatch }) {
  const download = useDownloadImportErrorReport()
  const url = batch.errorReportUrl

  return (
    <div className="space-y-2 rounded-control border border-border bg-subtle p-3">
      <div className="flex flex-wrap items-center gap-3">
        <Button
          type="button"
          variant="secondary"
          size="sm"
          disabled={!url || download.isPending}
          title={
            url
              ? undefined
              : 'No error report was produced for this import. Re-upload the file to see the rejected rows again.'
          }
          onClick={() => url && download.mutate(url)}
        >
          {download.isPending ? (
            <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
          ) : (
            <Download className="h-4 w-4" aria-hidden="true" />
          )}
          {download.isPending ? 'Preparing…' : 'Download error report'}
        </Button>
        <p className="text-sm text-content-muted">
          {batch.rejected.toLocaleString()}{' '}
          {batch.rejected === 1 ? 'row was' : 'rows were'} skipped.{' '}
          {url
            ? 'The report lists each one with the reason, so you fix and re-upload only those rows.'
            : 'The report could not be produced for this run — re-upload the file to see the rejected rows again.'}
        </p>
      </div>

      {download.isError && (
        <p
          role="alert"
          className="flex items-start gap-2 text-sm text-danger-text"
        >
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
          <span>{errorReportRefusal(download.error)}</span>
        </p>
      )}
    </div>
  )
}

/**
 * The headline sentence.
 *
 * A function rather than three ternaries in the JSX because the FAILED case has
 * to say a different *kind* of thing — not "0 rows imported" but "it stopped" —
 * and the distinction is the one this screen must not blur. A run that rejected
 * half a file completed; a run that died did not.
 */
function headline(batch: ImportBatch, fileName: string): string {
  const written = batch.created + batch.updated

  switch (batch.status) {
    case 'QUEUED':
      return `${fileName} is queued and will start in a moment.`
    case 'RUNNING':
      return `Importing ${fileName}…`
    case 'FAILED':
      return `The import of ${fileName} stopped before it finished.`
    default:
      return written === 1
        ? `Imported 1 row from ${fileName}.`
        : `Imported ${written.toLocaleString()} rows from ${fileName}.`
  }
}

function StatusIcon({ status }: { status: string }) {
  const className = 'mt-0.5 h-5 w-5 shrink-0'
  if (status === 'COMPLETED') {
    return <CheckCircle2 className={cn(className, 'text-success')} aria-hidden="true" />
  }
  if (status === 'FAILED') {
    return <XCircle className={cn(className, 'text-danger')} aria-hidden="true" />
  }
  return <Loader2 className={cn(className, 'animate-spin text-primary')} aria-hidden="true" />
}

/**
 * Tone → token classes, the same indirection `ValidationStep` uses and for the
 * same reason: blueprint §12.1 admits no colour that is not a token, and one
 * mapping is what keeps that checkable.
 */
function Count({
  label,
  value,
  tone,
}: {
  label: string
  value: number
  tone: 'success' | 'info' | 'danger'
}) {
  const text = { success: 'text-success-text', info: 'text-info-text', danger: 'text-danger-text' }[
    tone
  ]
  return (
    <li className="rounded-card border border-border bg-surface p-3">
      <p className={cn('text-h2 tabular-nums', text)}>{value.toLocaleString()}</p>
      <p className="text-sm font-medium text-content">{label}</p>
    </li>
  )
}
