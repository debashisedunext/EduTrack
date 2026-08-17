import type { ImportPreviewResponseData, ImportRowVerdict } from '@/api/generated/model'

/**
 * B-034 · S-34 step 4's rules, as pure functions.
 *
 * Same reasoning as `columnMapping.ts` one step over: these are decisions the
 * screen makes on every render and every filter click, and the interesting cases
 * are about counting and grouping rather than about pixels. Asserting "the
 * Rejected tab shows two rows" through a rendered table needs a click and a query
 * to say what one function call says.
 *
 * ## The counts come from the server, the filtering is ours
 *
 * `ImportPreview` derives its four counts from the rows rather than accumulating
 * them alongside, so a summary that disagrees with the table below it is not
 * expressible. Nothing here recomputes them — that would reintroduce exactly the
 * disagreement the server took care to make impossible.
 */

/**
 * The four outcomes, in the order blueprint §4B.3's own summary line reads them
 * — "412 create · 38 update · 6 rejected · 2 duplicates".
 *
 * One order, used by the tiles, the filter tabs and `summaryLine`. Two would be
 * defensible (the table's example rows happen to run duplicate-then-rejected)
 * and would mean the same four numbers appearing in two sequences on one screen.
 */
export const VERDICTS = ['WILL_CREATE', 'WILL_UPDATE', 'REJECTED', 'DUPLICATE_IN_FILE'] as const

export type Verdict = (typeof VERDICTS)[number]

/** `all` is the default view — the blueprint's table shows every row, in file order. */
export type PreviewFilter = 'all' | Verdict

/**
 * How each verdict is spoken about on screen.
 *
 * Held as data rather than as a `switch` in JSX because three places need it —
 * the filter tabs, the row badge and the screen-reader text — and three
 * independent switches over four cases is how "Duplicate" ends up called
 * something different in the tab from what it is called in the table.
 *
 * `tone` is a token name and not a colour. Blueprint §12.1's rule is that no
 * colour enters this codebase that is not a token, and going through a named
 * tone keeps the mapping in one place if the palette moves.
 */
export interface VerdictPresentation {
  /** The badge, and the tab. Sentence case, matching the blueprint's own labels. */
  label: string
  /** What the tab says when it is a count of rows: "6 rejected". */
  countLabel: string
  tone: 'success' | 'info' | 'warning' | 'danger'
  /**
   * Read out after the label, because the badge alone is a word without a
   * consequence. "Will create — this client does not exist yet" is the sentence
   * the user would otherwise have to infer from a green tick.
   */
  meaning: string
}

export const VERDICT_PRESENTATION: Record<Verdict, VerdictPresentation> = {
  WILL_CREATE: {
    label: 'Will create',
    countLabel: 'create',
    tone: 'success',
    meaning: 'no client has this code yet, so a new one is added',
  },
  WILL_UPDATE: {
    label: 'Will update',
    countLabel: 'update',
    tone: 'info',
    meaning: 'a client already has this code and is updated, never duplicated',
  },
  DUPLICATE_IN_FILE: {
    label: 'Duplicate in file',
    countLabel: 'duplicates',
    tone: 'warning',
    // Deliberately not "rejected". Nothing is wrong with the row's content, and
    // telling somebody their perfectly valid row was rejected sends them looking
    // for a fault that is not there — `ImportVerdict` makes the same point.
    meaning: 'an earlier row in this file already claimed this code, so this one is skipped',
  },
  REJECTED: {
    label: 'Rejected',
    countLabel: 'rejected',
    tone: 'danger',
    meaning: 'the row broke a validation rule and is not imported',
  },
}

/** The count for one verdict, read off the summary the server derived. */
export function countFor(preview: ImportPreviewResponseData, verdict: Verdict): number {
  switch (verdict) {
    case 'WILL_CREATE':
      return preview.willCreate
    case 'WILL_UPDATE':
      return preview.willUpdate
    case 'DUPLICATE_IN_FILE':
      return preview.duplicates
    case 'REJECTED':
      return preview.rejected
  }
}

/**
 * How many rows a commit would actually write.
 *
 * Step 5 offers "import valid rows only", and this is the number that offer is
 * about. Not `rows.length` — duplicates and rejections are skipped — and not
 * `willCreate` alone, which is the number people reach for and which silently
 * omits every update.
 */
export function writableCount(preview: ImportPreviewResponseData): number {
  return preview.willCreate + preview.willUpdate
}

/** Whether anything at all would be written. Nothing writable makes step 5 pointless. */
export function hasWritableRows(preview: ImportPreviewResponseData): boolean {
  return writableCount(preview) > 0
}

export function filterRows(
  rows: ImportRowVerdict[],
  filter: PreviewFilter,
): ImportRowVerdict[] {
  return filter === 'all' ? rows : rows.filter((row) => row.verdict === filter)
}

/**
 * The value of the column rows are matched on — the client code.
 *
 * Blueprint §4B.3's table has it as its own column, next to the row number, and
 * it is the one field that has to be there: it is what the user searches their
 * spreadsheet for, and for a duplicate or an update it is the whole explanation.
 *
 * Read by field name off the schema rather than hardcoded, so B-038's resource
 * import shows an employee code in the same column with no change here. Absent —
 * which happens on a row rejected for a blank code, the blueprint's own row 5 —
 * renders as the em dash its mock-up uses rather than as an empty cell that
 * looks like a rendering fault.
 */
export function naturalKeyValue(row: ImportRowVerdict, naturalKeyField: string): string | null {
  const value = row.values?.[naturalKeyField]
  return typeof value === 'string' && value.length > 0 ? value : null
}

/**
 * How many rows are rendered before the user asks for more.
 *
 * The file may hold 5,000 and the DOM should not. Deliberately a *page* rather
 * than a cap: every row is in the response and reachable, because a preview that
 * quietly describes part of what the commit will write is the one thing this step
 * exists to prevent. Filtering to Rejected and paging through is also the actual
 * workflow — the six bad rows are what the user came for.
 */
export const PREVIEW_PAGE_SIZE = 50

/**
 * The summary line, as blueprint §4B.3 writes it:
 * "412 create · 38 update · 6 rejected · 2 duplicates".
 *
 * Every verdict is present even at zero, which reads oddly for a clean file and
 * is right anyway: "0 rejected" is information, and a line whose entries appear
 * and disappear makes two runs of the same import hard to compare at a glance.
 */
export function summaryLine(preview: ImportPreviewResponseData): string {
  return VERDICTS.map(
    (verdict) => `${countFor(preview, verdict)} ${VERDICT_PRESENTATION[verdict].countLabel}`,
  ).join(' · ')
}
