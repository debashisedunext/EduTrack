import type { ObMyTask } from '@/api/generated/model/obMyTask'

/**
 * The review highlight a My Tasks row can wear — its three tones, the classes
 * each one paints, and the words that go with them.
 *
 * <h2>Why this is a module and not three ternaries in the page</h2>
 *
 * <p>The tones are drawn in four places now: the row's tint and left edge, the
 * note under the task name, the banner over the queue, and the legend that
 * explains all three. Four copies of `tone === 'back' ? … : …` is four chances
 * for the legend to teach a colour the rows do not use — which is the one
 * failure a legend cannot survive, because a reader has no way to tell that the
 * explanation is the half that is wrong.
 */

/**
 * What happened to this row's check list, in the order somebody should act on
 * it.
 *
 * <p><b>back</b> is work and is the only one of the three that is;
 * <b>good</b> is news the person wanted; <b>review</b> is a wait.
 */
export type FlagTone = 'back' | 'good' | 'review'

/** The row's own tint and the coloured edge that carries it down the page. */
export const FLAG_ROW_CLASS: Record<FlagTone, string> = {
  back: 'bg-danger-soft shadow-[inset_3px_0_0_var(--danger)]',
  good: 'bg-level-low-soft shadow-[inset_3px_0_0_var(--success)]',
  review: 'bg-level-medium-soft shadow-[inset_3px_0_0_var(--level-medium)]',
}

/** The note under the task name — the words the colour is not allowed to carry alone. */
export const FLAG_TEXT_CLASS: Record<FlagTone, string> = {
  back: 'text-danger-text',
  good: 'text-success-text',
  review: 'text-level-medium-text',
}

/** The banner over the queue, and the legend's swatch for the same tone. */
export const FLAG_BANNER_CLASS: Record<FlagTone, string> = {
  back: 'border-danger bg-danger-soft text-danger-text',
  good: 'border-success bg-level-low-soft text-success-text',
  review: 'border-level-medium bg-level-medium-soft text-level-medium-text',
}

/** The mark that stands in for the tone where there is no room for a sentence. */
export const FLAG_MARK: Record<FlagTone, string> = {
  back: '↻',
  good: '✓',
  review: '⌛',
}

/** `1 row` / `3 rows` — said the same way on the row, the banner and the legend. */
export function rows(n: number): string {
  return `${n} row${n === 1 ? '' : 's'}`
}

/**
 * What this row's review state is worth saying, if anything.
 *
 * <p>Came back first: it is the only one of the three that is work. Approved
 * second: news rather than a task, but news the person wanted. Out last, and it
 * is the one that reads two ways — to a manager `rowsOut` is a queue, to the
 * implementor it is a wait. The server sends one number because it is one fact;
 * which sentence it earns depends on who is looking.
 *
 * <p>The unseen counts fade on their own — opening the task stamps the rows —
 * so the highlight is a signal rather than a permanent label. `rowsOut` does not
 * fade, because it is a fact about where the work is rather than about what
 * anybody has read.
 */
export function reviewFlag(task: ObMyTask): { tone: FlagTone; note: string } | null {
  if (task.rowsReturned > 0) {
    return { tone: 'back', note: `${rows(task.rowsReturned)} came back — open to see why` }
  }
  if (task.rowsApproved > 0) {
    return { tone: 'good', note: `${rows(task.rowsApproved)} approved` }
  }
  if (task.rowsOut > 0) {
    return { tone: 'review', note: `${rows(task.rowsOut)} out for verification` }
  }
  return null
}
