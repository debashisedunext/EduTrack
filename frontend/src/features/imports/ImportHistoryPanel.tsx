import { useState } from 'react'
import { AlertCircle, Download, History, Loader2, RotateCcw, Undo2 } from 'lucide-react'
import { useQueryClient } from '@tanstack/react-query'

import {
  getListImportBatchesQueryOptions,
  useListImportBatches,
  useReverseImportBatch,
} from '@/api/generated/imports/imports'
import type { ImportBatch, ImportReversalResponseData } from '@/api/generated/model'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

import {
  formatRunTime,
  reversalOutcome,
  reversalRefusal,
  reverseDisabledReason,
} from './importHistory'
import { useDownloadImportErrorReport } from './importQueries'
import { type ImportNouns, type ImportWizardConfig } from './importWizard'
import { ReverseImportDialog } from './ReverseImportDialog'

/**
 * B-037 · S-34's import history — **the screen that makes "every import is
 * identified" true of something a person can reach.**
 *
 * Blueprint §4B.3's closing validation rule, and §17's named mitigation for the
 * risk *"Client Excel import silently corrupts the master"*: `import_batch_id`
 * on the row, plus an import batch that can be traced and reversed as a set.
 *
 * Until this panel existed, a batch id was known only to the browser tab that
 * started the run. `import_batches` recorded every import faithfully since
 * B-035, `GET /import-batches/{batchId}` could read one back, and there was no
 * way to find out what a batch id was — so an Admin who closed the wizard after
 * importing the wrong spreadsheet had nothing to go back to.
 *
 * ## Why it lives under the wizard rather than on its own route
 *
 * The two questions this answers — *which import did that?* and *undo it* — are
 * asked by somebody who has just used the wizard, usually within a minute. A
 * separate `/masters/clients/imports` screen would be one more thing to know
 * about at the moment somebody is least inclined to go looking, and the wizard
 * is where the mistake was made.
 *
 * It renders below the steps and stays visible through all five of them, which
 * is deliberate: step 5 replaces the wizard with a progress bar, and the run the
 * user is watching appears in this list as it happens.
 *
 * ## The Reverse button's enabled state comes from the server
 *
 * `batch.reversible`, never re-derived here. See `reverseDisabledReason` — the
 * rules are the server's two refusals, and a copy in TypeScript is a second
 * statement of them that drifts.
 */
export function ImportHistoryPanel({ config }: { config: ImportWizardConfig }) {
  const { entity, nouns } = config
  const queryClient = useQueryClient()
  const history = useListImportBatches({ entity })
  const reverse = useReverseImportBatch()

  /** The batch the confirmation dialog is open for, if any. */
  const [confirming, setConfirming] = useState<ImportBatch | null>(null)
  /**
   * The last reversal's result, kept so the retained rows can be named.
   *
   * Held here rather than read from `reverse.data` so that opening the dialog
   * again clears it: a result panel describing the previous reversal, sitting
   * above a list where a second one is being confirmed, is the same class of
   * defect as the wizard's stale preview.
   */
  const [result, setResult] = useState<ImportReversalResponseData | null>(null)

  const batches = history.data?.data.batches ?? []

  function confirmReverse() {
    const batch = confirming
    if (!batch?.batchId) return

    reverse.mutate(
      { batchId: batch.batchId },
      {
        onSuccess: (response) => {
          setResult(response.data)
          setConfirming(null)
          // The list's counters and every `reversible` flag on it may have moved
          // — this run's certainly has. Invalidating rather than patching the one
          // row, because the server decides `reversible` and a hand-patched row
          // would be this screen deciding it instead.
          void queryClient.invalidateQueries({
            queryKey: getListImportBatchesQueryOptions({ entity }).queryKey,
          })
        },
        /*
          The dialog closes on a refusal too, and that is not tidiness.

          The refusal notice lives on the panel, because its remedies are the
          panel's — wait for the run, refresh the list. A modal is aria-hidden
          from everything behind it, so leaving it open would render an alert
          that a screen reader cannot reach and a user cannot act on without
          first dismissing the dialog that is covering it.

          Pressing Reverse again is one click away, which is the right cost for
          the one action here that deletes rows.
        */
        onError: () => setConfirming(null),
      },
    )
  }

  return (
    <section
      aria-labelledby="import-history-heading"
      className="rounded-card border border-border bg-surface p-5"
    >
      <div className="flex flex-wrap items-center gap-3">
        <History className="h-5 w-5 text-content-muted" aria-hidden="true" />
        <h2 id="import-history-heading" className="text-h3 text-content">
          Previous imports
        </h2>
        <Button
          type="button"
          variant="secondary"
          size="sm"
          className="ml-auto"
          disabled={history.isFetching}
          onClick={() => void history.refetch()}
        >
          {history.isFetching ? (
            <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
          ) : (
            <RotateCcw className="h-4 w-4" aria-hidden="true" />
          )}
          Refresh
        </Button>
      </div>

      <p className="mt-1 max-w-3xl text-sm text-content-muted">
        Every import is recorded, so a bad one can be found again and reversed as
        a set. Reversing deletes the clients an import created; clients it
        updated are not restored.
      </p>

      {result && (
        <ReversalResult result={result} nouns={nouns} onDismiss={() => setResult(null)} />
      )}

      {reverse.isError && <ReversalRefusalNotice error={reverse.error} />}

      {history.isPending ? (
        <p className="mt-4 flex items-center gap-2 text-sm text-content-muted">
          <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
          Loading previous imports…
        </p>
      ) : history.isError ? (
        <p role="alert" className="mt-4 flex items-start gap-2 text-sm text-danger-text">
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
          <span>
            The import history could not be loaded. Everything else on this page
            still works — an import you run now is still recorded.
          </span>
        </p>
      ) : batches.length === 0 ? (
        <p className="mt-4 text-sm text-content-muted">
          No client import has been run yet. The first one will appear here.
        </p>
      ) : (
        <div className="mt-4 overflow-x-auto">
          <table className="w-full text-sm">
            <caption className="sr-only">
              Previous client imports, newest first. Each row shows when it ran,
              who ran it, what it wrote, and whether it can be reversed.
            </caption>
            <thead>
              <tr className="border-b border-border text-left text-xs uppercase tracking-wide text-content-muted">
                <th scope="col" className="py-2 pr-4 font-medium">When</th>
                <th scope="col" className="py-2 pr-4 font-medium">File</th>
                <th scope="col" className="py-2 pr-4 font-medium">By</th>
                <th scope="col" className="py-2 pr-4 text-right font-medium">Created</th>
                <th scope="col" className="py-2 pr-4 text-right font-medium">Updated</th>
                <th scope="col" className="py-2 pr-4 text-right font-medium">Rejected</th>
                <th scope="col" className="py-2 pr-4 font-medium">Status</th>
                <th scope="col" className="py-2 font-medium">
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {batches.map((batch) => (
                <HistoryRow
                  key={batch.batchId}
                  batch={batch}
                  isReversing={reverse.isPending && confirming?.batchId === batch.batchId}
                  onReverse={() => {
                    setResult(null)
                    reverse.reset()
                    setConfirming(batch)
                  }}
                />
              ))}
            </tbody>
          </table>

          {batches.length >= (history.data?.data.limit ?? 0) && (
            /*
              The cap, said out loud. A bounded list that looks unbounded reads as
              "these are all of them" — the server sends `limit` for exactly this
              sentence rather than leaving the screen to hardcode 50.
            */
            <p className="mt-3 text-xs text-content-muted">
              Showing the {history.data?.data.limit} most recent imports.
            </p>
          )}
        </div>
      )}

      <ReverseImportDialog
        batch={confirming}
        nouns={nouns}
        isPending={reverse.isPending}
        onConfirm={confirmReverse}
        onCancel={() => setConfirming(null)}
      />
    </section>
  )
}

function HistoryRow({
  batch,
  isReversing,
  onReverse,
}: {
  batch: ImportBatch
  isReversing: boolean
  onReverse: () => void
}) {
  const download = useDownloadImportErrorReport()
  const disabledReason = reverseDisabledReason(batch)

  return (
    <tr className="border-b border-border last:border-0 align-top">
      <td className="py-2 pr-4 whitespace-nowrap text-content">
        {formatRunTime(batch.startedAt)}
      </td>
      <td className="py-2 pr-4 text-content">
        {batch.fileName ?? <span className="text-content-muted">—</span>}
        <span className="block text-xs text-content-muted">#{batch.batchId}</span>
      </td>
      <td className="py-2 pr-4 text-content">
        {batch.importedByName ?? <span className="text-content-muted">—</span>}
      </td>
      <td className="py-2 pr-4 text-right tabular-nums text-content">
        {batch.created.toLocaleString()}
      </td>
      <td className="py-2 pr-4 text-right tabular-nums text-content">
        {batch.updated.toLocaleString()}
      </td>
      <td className="py-2 pr-4 text-right tabular-nums text-content">
        {batch.rejected > 0 ? (
          <button
            type="button"
            className="underline underline-offset-2 disabled:no-underline disabled:text-content-muted"
            disabled={!batch.errorReportUrl || download.isPending}
            title={
              batch.errorReportUrl
                ? 'Download the rejected rows with a Reason column'
                : 'No error report was produced for this import.'
            }
            onClick={() => batch.errorReportUrl && download.mutate(batch.errorReportUrl)}
          >
            {batch.rejected.toLocaleString()}
            <Download className="ml-1 inline h-3 w-3" aria-hidden="true" />
            <span className="sr-only"> rejected rows — download the error report</span>
          </button>
        ) : (
          batch.rejected.toLocaleString()
        )}
      </td>
      <td className="py-2 pr-4">
        <StatusCell batch={batch} />
      </td>
      <td className="py-2 text-right">
        <Button
          type="button"
          variant="secondary"
          size="sm"
          disabled={!batch.reversible || isReversing}
          title={disabledReason ?? undefined}
          onClick={onReverse}
        >
          {isReversing ? (
            <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
          ) : (
            <Undo2 className="h-4 w-4" aria-hidden="true" />
          )}
          Reverse
        </Button>
      </td>
    </tr>
  )
}

/**
 * Status, plus the reversal if there has been one.
 *
 * The two are stacked rather than merged into a single word, because they are
 * genuinely two facts: `status` is how the run ended and `reversedAt` is what
 * happened to it afterwards. A single "Reversed" chip would lose "completed with
 * 6 rejections" — which is exactly the argument the migration makes for keeping
 * them in separate columns.
 */
function StatusCell({ batch }: { batch: ImportBatch }) {
  const tone =
    batch.status === 'COMPLETED'
      ? 'text-success-text'
      : batch.status === 'FAILED'
        ? 'text-danger-text'
        : 'text-content-muted'

  return (
    <div className="whitespace-nowrap">
      <span className={cn('font-medium', tone)}>{label(batch.status)}</span>
      {batch.reversedAt && (
        <span className="block text-xs text-content-muted">
          Reversed — {batch.reversedRows.toLocaleString()} deleted
          {batch.retainedRows > 0 && `, ${batch.retainedRows.toLocaleString()} kept`}
        </span>
      )}
    </div>
  )
}

function label(status: string): string {
  switch (status) {
    case 'QUEUED':
      return 'Queued'
    case 'RUNNING':
      return 'Running'
    case 'FAILED':
      return 'Stopped'
    default:
      return 'Completed'
  }
}

/**
 * What the reversal actually did.
 *
 * Every retained row is named, with its reason. The dialog deliberately does
 * not promise a count beforehand — see `ReverseImportDialog` — so this is the
 * first and only place the user learns which ones survived, and a count without
 * names would leave them diffing a spreadsheet against the master to find out.
 */
function ReversalResult({
  result,
  nouns,
  onDismiss,
}: {
  result: ImportReversalResponseData
  nouns: ImportNouns
  onDismiss: () => void
}) {
  const { headline, retained, notReverted } = reversalOutcome(result, nouns)

  return (
    <div
      role="status"
      className="mt-4 space-y-2 rounded-control border border-border bg-subtle p-3 text-sm"
    >
      <div className="flex items-start gap-3">
        <p className="text-content">{headline}</p>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="ml-auto shrink-0"
          onClick={onDismiss}
        >
          Dismiss
        </Button>
      </div>

      {notReverted && <p className="text-warning-text">{notReverted}</p>}

      {retained && (
        <>
          <p className="text-content-muted">{retained}</p>
          <ul className="space-y-1">
            {result.retained.map((row) => (
              <li key={row.naturalKey} className="text-content-muted">
                <span className="font-medium text-content">{row.naturalKey}</span> — {row.reason}
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  )
}

/** The refusal, with the remedy that matches its `type` rather than a blanket retry. */
function ReversalRefusalNotice({ error }: { error: unknown }) {
  const { message, remedy } = reversalRefusal(error)
  const next =
    remedy === 'wait'
      ? 'Wait for it to finish, then refresh this list.'
      : remedy === 'refresh'
        ? 'Refresh this list to see its current state.'
        : remedy === 'contact'
          ? 'Nothing has been deleted.'
          : 'Try again in a moment.'

  return (
    <p
      role="alert"
      className="mt-4 flex items-start gap-2 rounded-control border border-danger bg-surface p-3 text-sm text-danger-text"
    >
      <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
      <span>
        {message} {next}
      </span>
    </p>
  )
}
