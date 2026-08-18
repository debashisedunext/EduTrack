import { useState } from 'react'
import { CheckCircle2, CircleSlash, Copy, Loader2, RefreshCw, ShieldCheck } from 'lucide-react'

import { useDescribeImportSchema } from '@/api/generated/imports/imports'
import type { ImportPreviewResponseData, ImportRowVerdict } from '@/api/generated/model'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

import { type ImportSchemaKey } from './importQueries'
import {
  PREVIEW_PAGE_SIZE,
  VERDICTS,
  VERDICT_PRESENTATION,
  countFor,
  filterRows,
  hasWritableRows,
  naturalKeyValue,
  writableCount,
  type PreviewFilter,
  type Verdict,
} from './validationPreview'

/**
 * S-34 step 4 — the dry run. B-034, blueprint §4B.3.
 *
 * ## The screen the whole wizard exists for
 *
 * §4B.3 opens with the reason: "a silent bulk import that half-succeeds is worse
 * than no import at all". Steps 1 to 3 are preparation; this is where the user is
 * shown what four hundred rows are about to do and asked to agree. Everything
 * here is arranged around making that answerable rather than merely visible —
 * the counts before the table, the problems reachable in one click, and the
 * fields an update would change named rather than left to trust.
 *
 * ## "Nothing has been written" is stated, not implied
 *
 * Twice, and deliberately. It is the promise the step is built on and the user
 * has no way to verify it; the previous steps have made it too, so dropping it at
 * the point it matters most would read as it having quietly stopped being true.
 *
 * ## Rows are paged, never capped
 *
 * The response carries every row — see `ImportDtos.Preview` for why a server-side
 * cap would defeat the step — and this renders fifty at a time. Filtering to
 * Rejected is the real workflow: the six bad rows out of four hundred are what
 * the user came for, and the tabs are what get them there without scrolling.
 */
export function ValidationStep({
  schema,
  preview,
  fileName,
  onBack,
  onCommit,
  committing = false,
}: {
  schema: ImportSchemaKey
  preview: ImportPreviewResponseData
  /** Named on screen so the user knows which file this preview is of. */
  fileName: string
  onBack: () => void
  /**
   * Step 5 — B-035. Still optional, and still omitted rather than passed as a
   * no-op where it does not exist: a caller that has no commit gets a disabled
   * button that says why instead of one that looks broken.
   */
  onCommit?: () => void
  /**
   * The commit request is in flight.
   *
   * Held here rather than inferred, and it disables the button rather than only
   * changing its label: this is the one irreversible action in the wizard, and a
   * second press before the first response lands is a second batch against the
   * same file. The server refuses the duplicate — it consumes its staging entry
   * — but the user would see a refusal for something they were right to expect
   * to work.
   */
  committing?: boolean
}) {
  const [filter, setFilter] = useState<PreviewFilter>('all')
  const [shown, setShown] = useState(PREVIEW_PAGE_SIZE)

  // Already fetched and cached by step 3, so this is not a second round trip.
  // What it is for here is the natural-key column: which field the rows are
  // matched on, and what the user's own template calls it.
  const described = useDescribeImportSchema(schema)
  const naturalKeyField = described.data?.data.naturalKey ?? ''
  const naturalKeyHeader =
    described.data?.data.fields.find((field) => field.name === naturalKeyField)?.header ?? 'Code'

  const rows = filterRows(preview.rows, filter)
  const visible = rows.slice(0, shown)
  const writable = writableCount(preview)

  function choose(next: PreviewFilter) {
    setFilter(next)
    // Back to the first page. Keeping the offset across a filter change means
    // clicking Rejected on a 400-row file and landing on an empty table.
    setShown(PREVIEW_PAGE_SIZE)
  }

  return (
    <div className="space-y-5">
      <p className="max-w-2xl text-sm text-content-muted">
        Every row of <span className="font-medium text-content">{fileName}</span> has been
        checked against the client master. <strong className="font-medium text-content">
          Nothing has been written
        </strong>{' '}
        — no client has been created or changed, and none will be until you commit at
        step 5.
      </p>

      {/* ── the summary · §4B.3's "412 create · 38 update · …" ───────────── */}
      <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4" aria-label="Preview summary">
        {VERDICTS.map((verdict) => (
          <li
            key={verdict}
            className="rounded-card border border-border bg-surface p-3"
          >
            <p className={cn('text-h2 tabular-nums', toneText(VERDICT_PRESENTATION[verdict].tone))}>
              {countFor(preview, verdict).toLocaleString()}
            </p>
            <p className="text-sm font-medium text-content">
              {VERDICT_PRESENTATION[verdict].label}
            </p>
            <p className="mt-0.5 text-xs text-content-muted">
              {VERDICT_PRESENTATION[verdict].meaning}
            </p>
          </li>
        ))}
      </ul>

      {/*
        The number step 5's "import valid rows only" is actually about. Said
        plainly because it is not any one of the four tiles: reaching for
        `willCreate` is the natural mistake and it silently omits every update.
      */}
      <p
        className="flex items-start gap-2 rounded-control border border-border bg-subtle p-3 text-sm text-content"
        role="status"
      >
        <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0 text-content-muted" aria-hidden="true" />
        <span>
          {hasWritableRows(preview) ? (
            <>
              Committing would write{' '}
              <strong className="font-medium">{writable.toLocaleString()}</strong>{' '}
              {writable === 1 ? 'row' : 'rows'} — {preview.willCreate.toLocaleString()} new
              and {preview.willUpdate.toLocaleString()} updated. The other{' '}
              {(preview.rejected + preview.duplicates).toLocaleString()} are skipped and
              can be downloaded as an error report at step 5.
            </>
          ) : (
            <>
              No row in this file can be imported. Fix the problems below, or go back and
              choose a different file — nothing here has changed any client.
            </>
          )}
        </span>
      </p>

      {/* ── filter tabs ──────────────────────────────────────────────────── */}
      <div className="flex flex-wrap gap-2" role="group" aria-label="Filter rows by outcome">
        <Button
          type="button"
          size="sm"
          variant={filter === 'all' ? 'primary' : 'secondary'}
          aria-pressed={filter === 'all'}
          onClick={() => choose('all')}
        >
          All {preview.rows.length.toLocaleString()}
        </Button>
        {VERDICTS.map((verdict) => (
          <Button
            key={verdict}
            type="button"
            size="sm"
            variant={filter === verdict ? 'primary' : 'secondary'}
            aria-pressed={filter === verdict}
            // A tab that would show nothing is disabled rather than hidden: a
            // clean file should still say "0 rejected" out loud, and controls
            // that come and go make two runs hard to compare.
            disabled={countFor(preview, verdict) === 0}
            onClick={() => choose(verdict)}
          >
            {VERDICT_PRESENTATION[verdict].label} {countFor(preview, verdict).toLocaleString()}
          </Button>
        ))}
      </div>

      {/* ── the table · §4B.3's Row / Client code / Status / Message ─────── */}
      <div className="overflow-x-auto">
        <table className="w-full min-w-[40rem] text-sm">
          <caption className="sr-only">
            Every row of your file and what importing it would do. Nothing has been
            written.
          </caption>
          <thead>
            <tr className="border-b border-border text-left text-xs uppercase tracking-wide text-content-muted">
              <th scope="col" className="py-2 pr-4 font-medium">
                Row
              </th>
              <th scope="col" className="py-2 pr-4 font-medium">
                {naturalKeyHeader}
              </th>
              <th scope="col" className="py-2 pr-4 font-medium">
                Status
              </th>
              <th scope="col" className="py-2 font-medium">
                Message
              </th>
            </tr>
          </thead>
          <tbody>
            {visible.map((row) => (
              <PreviewRow key={row.rowNumber} row={row} naturalKeyField={naturalKeyField} />
            ))}
          </tbody>
        </table>
      </div>

      {rows.length === 0 && (
        <p className="text-sm text-content-muted">No rows with that outcome.</p>
      )}

      {rows.length > visible.length && (
        <div className="flex flex-wrap items-center gap-3">
          <Button
            type="button"
            variant="secondary"
            size="sm"
            onClick={() => setShown((current) => current + PREVIEW_PAGE_SIZE)}
          >
            Show {Math.min(PREVIEW_PAGE_SIZE, rows.length - visible.length)} more
          </Button>
          <p className="text-sm text-content-muted" role="status">
            Showing {visible.length.toLocaleString()} of {rows.length.toLocaleString()} rows.
          </p>
        </div>
      )}

      <div className="flex flex-wrap items-center gap-3 border-t border-border pt-4">
        <Button type="button" variant="secondary" onClick={onBack} disabled={committing}>
          Back to mapping
        </Button>
        <Button
          type="button"
          disabled={!onCommit || !hasWritableRows(preview) || committing}
          onClick={onCommit}
          title={
            !hasWritableRows(preview)
              ? 'There is nothing to import — every row is rejected or a duplicate'
              : onCommit
                ? undefined
                : 'Committing arrives with the next step of this screen'
          }
        >
          {committing && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
          {committing
            ? 'Starting…'
            : `Import ${writable.toLocaleString()} ${writable === 1 ? 'row' : 'rows'}`}
        </Button>
        <p className="text-sm text-content-muted">
          {onCommit
            ? 'Nothing has been written yet. Importing runs in the background, so you can leave this page — and the rows it skips can be downloaded as an error report.'
            : 'Nothing has been written — no client has changed. Committing arrives with the next step.'}
        </p>
      </div>
    </div>
  )
}

/**
 * One row of the blueprint's table.
 *
 * The message is the interesting cell and it says a different kind of thing per
 * verdict — the rule a rejection broke, which earlier row a duplicate lost to,
 * and for an update **the fields it would change**. That last one is what makes
 * a screen full of "will update" reviewable rather than alarming, and it is why
 * an absent reason on an update renders as a stated unknown rather than as an em
 * dash: `null` there means the server could not say, which is not the same as
 * "nothing changes".
 */
function PreviewRow({
  row,
  naturalKeyField,
}: {
  row: ImportRowVerdict
  naturalKeyField: string
}) {
  const verdict = row.verdict as Verdict
  const presentation = VERDICT_PRESENTATION[verdict]
  const key = naturalKeyValue(row, naturalKeyField)

  return (
    <tr className="border-b border-border align-top">
      <th scope="row" className="py-2 pr-4 text-left font-normal tabular-nums text-content-muted">
        {row.rowNumber}
      </th>
      <td className="py-2 pr-4 font-medium text-content">
        {/*
          The blueprint's own "(blank)" for a row rejected because the code is
          missing — its row 5. An empty cell would read as a rendering fault at
          exactly the moment the user is looking for a reason.
        */}
        {key ?? <span className="font-normal text-content-muted">(blank)</span>}
      </td>
      <td className="py-2 pr-4">
        <span
          className={cn(
            'inline-flex items-center gap-1 rounded-chip border px-2 py-1 text-xs',
            toneChip(presentation.tone),
          )}
        >
          <VerdictIcon verdict={verdict} />
          {presentation.label}
        </span>
        <span className="sr-only"> — {presentation.meaning}</span>
      </td>
      <td className="py-2 text-content-muted">
        {row.reason ?? (
          <span aria-label="No message">
            {verdict === 'WILL_UPDATE' ? 'Changed fields not available' : '—'}
          </span>
        )}
      </td>
    </tr>
  )
}

function VerdictIcon({ verdict }: { verdict: Verdict }) {
  const className = 'h-3 w-3 shrink-0'
  switch (verdict) {
    case 'WILL_CREATE':
      return <CheckCircle2 className={className} aria-hidden="true" />
    case 'WILL_UPDATE':
      // The blueprint's ♻. A spinner would have read as "in progress", which is
      // the one thing this screen must not suggest.
      return <RefreshCw className={className} aria-hidden="true" />
    case 'DUPLICATE_IN_FILE':
      return <Copy className={className} aria-hidden="true" />
    case 'REJECTED':
      return <CircleSlash className={className} aria-hidden="true" />
  }
}

/**
 * Tone → token classes, in one place.
 *
 * Blueprint §12.1: no colour that is not a token. Going through a named tone
 * rather than writing the class at each of the three call sites is what keeps
 * that checkable, and what makes a palette change one edit.
 */
function toneText(tone: VerdictTone): string {
  return {
    success: 'text-success-text',
    info: 'text-info-text',
    warning: 'text-warning-text',
    danger: 'text-danger-text',
  }[tone]
}

function toneChip(tone: VerdictTone): string {
  return {
    success: 'border-success bg-surface text-success-text',
    info: 'border-info bg-surface text-info-text',
    warning: 'border-warning bg-surface text-warning-text',
    danger: 'border-danger bg-surface text-danger-text',
  }[tone]
}

type VerdictTone = (typeof VERDICT_PRESENTATION)[Verdict]['tone']
