import { ApiError } from '@/api/http'
import type { ImportBatch, ImportReversalResponseData } from '@/api/generated/model'

/**
 * B-037 · the import history panel's logic, with no JSX in it.
 *
 * Blueprint §4B.3's closing validation rule: *"every import writes an
 * `import_batch` row so a bad import can be identified and reversed as a set."*
 * The panel is the identification, and the Reverse button is the set.
 *
 * Everything here is a pure function over a batch, for the reason
 * `validationPreview.ts` and `commitProgress.ts` are: the sentences this screen
 * puts in front of somebody about to delete four hundred clients are the part
 * worth testing exhaustively, and a component test that has to render a table to
 * assert on a string tests the table instead.
 */

/** The problem `type` URIs the reversal branches on — three, because the remedies differ. */
export const REVERSAL_PROBLEM = {
  /** Still running. Clears itself; the screen should wait. */
  notFinished: 'import-batch-not-finished',
  /** Done once already. Never clears; the screen should stop offering the button. */
  alreadyReversed: 'import-batch-already-reversed',
  /** The importer that wrote this run is not in this build. Needs an operator. */
  schemaUnavailable: 'import-schema-unavailable',
} as const

export interface ReversalRefusal {
  message: string
  /**
   * What the screen should do next.
   *
   * `wait` and `refresh` are deliberately different: one is "this will be
   * reversible in a moment" and the other is "your list is stale". Collapsing
   * them into "try again" would offer a retry on a batch that will refuse
   * forever — the whole reason the server splits the two types.
   */
  remedy: 'wait' | 'refresh' | 'contact' | 'retry'
}

/**
 * The server's refusal, in words the user can act on.
 *
 * Branches on `problem.type`, never on `title` or `detail` — CONVENTIONS.md §3
 * makes the type stable and the prose changeable. `detail` is still what is
 * shown where the server wrote something specific, because it names the actual
 * status or timestamp.
 */
export function reversalRefusal(error: unknown): ReversalRefusal {
  if (!(error instanceof ApiError)) {
    return {
      message: 'The import could not be reversed. Check your connection and try again.',
      remedy: 'retry',
    }
  }

  const detail = error.problem.detail
  if (error.is(REVERSAL_PROBLEM.notFinished)) {
    return { message: detail ?? error.problem.title, remedy: 'wait' }
  }
  if (error.is(REVERSAL_PROBLEM.alreadyReversed)) {
    return { message: detail ?? error.problem.title, remedy: 'refresh' }
  }
  if (error.is(REVERSAL_PROBLEM.schemaUnavailable)) {
    return { message: detail ?? error.problem.title, remedy: 'contact' }
  }
  if (error.status === 403) {
    return {
      message:
        'You do not have permission to reverse an import. Reversing is an administrator action.',
      remedy: 'contact',
    }
  }
  if (error.status === 404) {
    return {
      message: 'That import is no longer there. Refresh the list.',
      remedy: 'refresh',
    }
  }
  return {
    message: `The import could not be reversed (${error.status}${detail ? ` — ${detail}` : ''}).`,
    remedy: 'retry',
  }
}

/**
 * What the confirmation dialog promises, before anything is deleted.
 *
 * **The second sentence is the one that matters and the one a dialog like this
 * usually leaves out.** A reversal deletes what the run *created* and cannot
 * restore what it *updated* — there is no before image anywhere. Somebody who
 * imported 412 rows and is about to press a button labelled "Reverse" will
 * reasonably expect all 412 to go back to how they were, and that is not what
 * happens. It is said here, in advance, rather than explained afterwards by a
 * result screen.
 */
export function reversalWarning(batch: ImportBatch): { deletes: string; keeps: string | null } {
  const created = batch.created
  const updated = batch.updated

  const deletes =
    created === 1
      ? 'This deletes the 1 client this import created.'
      : `This deletes the ${created.toLocaleString()} clients this import created.`

  if (updated === 0) {
    return { deletes, keeps: null }
  }

  return {
    deletes,
    keeps:
      updated === 1
        ? 'The 1 client it updated is not restored — its earlier values were overwritten and are not kept anywhere.'
        : `The ${updated.toLocaleString()} clients it updated are not restored — their earlier values were overwritten and are not kept anywhere.`,
  }
}

/**
 * What actually happened, once it has.
 *
 * Split from {@link reversalWarning} rather than reusing it with a past tense,
 * because the two describe different numbers: the warning is over what the batch
 * *created*, and this is over what the reversal *managed*. On a batch where a
 * client has since been ticketed those two differ, and that difference is the
 * only thing on the screen worth reading.
 */
export function reversalOutcome(result: ImportReversalResponseData): {
  headline: string
  retained: string | null
  notReverted: string | null
} {
  const deleted = result.batch.reversedRows
  const kept = result.retained.length

  const headline =
    deleted === 0
      ? 'Nothing was deleted — this import had not created any clients.'
      : deleted === 1
        ? 'Deleted the 1 client this import created.'
        : `Deleted the ${deleted.toLocaleString()} clients this import created.`

  const retained =
    kept === 0
      ? null
      : kept === 1
        ? '1 client was kept because work has been raised against it since the import. It is listed below.'
        : `${kept.toLocaleString()} clients were kept because work has been raised against them since the import. They are listed below.`

  const notReverted =
    result.updatedRowsNotReverted === 0
      ? null
      : result.updatedRowsNotReverted === 1
        ? '1 client that this import updated was left as it is. Its earlier values are not kept anywhere, so they cannot be put back.'
        : `${result.updatedRowsNotReverted.toLocaleString()} clients that this import updated were left as they are. Their earlier values are not kept anywhere, so they cannot be put back.`

  return { headline, retained, notReverted }
}

/**
 * Why the Reverse button is disabled, or `null` when it is not.
 *
 * **Reads `batch.reversible`, never re-derives it.** The server decides, for the
 * reason its own contract gives: `reversible` is the two refusals in
 * `ImportReversalService`, and a copy of those rules in TypeScript agrees on the
 * day it is written and then drifts — leaving a button that offers an operation
 * the server refuses, on a screen whose job is deleting rows.
 *
 * What is derived here is only the *sentence*, which the server does not send
 * and should not: a tooltip is a property of this screen.
 */
export function reverseDisabledReason(batch: ImportBatch): string | null {
  if (batch.reversible) {
    return null
  }
  if (batch.reversedAt) {
    return 'This import has already been reversed.'
  }
  if (batch.status === 'QUEUED' || batch.status === 'RUNNING') {
    return 'This import has not finished yet.'
  }
  // Not reachable from the two rules above, and deliberately not an exhaustive
  // switch: `reversible` is the server's answer and this only explains it, so a
  // rule added there should leave the button correctly disabled with a vaguer
  // tooltip rather than incorrectly enabled with a confident one.
  return 'This import cannot be reversed.'
}

/**
 * `2026-08-18T09:28:47Z` → `18 Aug 2026, 09:28`.
 *
 * The panel's first column, and half of what "a bad import can be identified"
 * means in practice: an Admin knows roughly when they uploaded the wrong file
 * long before they know its batch id.
 *
 * Rendered in the browser's timezone, per CLAUDE.md — storage is UTC everywhere
 * and the user's zone is applied in the presentation layer only.
 */
export function formatRunTime(iso: string | undefined): string {
  if (!iso) return '—'
  const at = new Date(iso)
  if (Number.isNaN(at.getTime())) return '—'
  return at.toLocaleString(undefined, {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}
