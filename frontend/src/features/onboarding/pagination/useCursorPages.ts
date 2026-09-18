import * as React from 'react'

/**
 * Ten rows a page, for the onboarding grids.
 *
 * <p>The server's own default is fifty (`PageLimit.DEFAULT`) and its ceiling is
 * two hundred; this is the page size these two screens ask for, not a new
 * server rule. It is one constant rather than a literal per screen so the
 * Projects grid and the Clients master cannot drift apart — a `limit` on the
 * request and a page size in the row-range caption that disagree would print
 * "Rows 1–10" under twenty-five rows.
 */
export const OB_PAGE_SIZE = 10

/** A stable empty page stack, so the reset render does not churn identities. */
const NO_PAGES: readonly string[] = []

export interface CursorPages {
  /** The cursor for the page on screen — absent on the first page. */
  cursor: string | undefined
  /** Zero-based: 0 is the first page, and how far in the row range starts. */
  pageIndex: number
  /** Whether there is a page to go back to. */
  canGoBack: boolean
  /** Step forward onto the page `meta.nextCursor` names. A null cursor is ignored. */
  next: (nextCursor: string | null | undefined) => void
  /** Step back onto the page before this one. */
  previous: () => void
}

/**
 * Forward-cursor paging with a working **Previous**.
 *
 * <h2>Why a stack, and not a page number</h2>
 *
 * The server pages by keyset cursor — `PageMeta` carries `nextCursor` and
 * `hasMore` and deliberately carries no `totalCount`, because a total over a
 * cursor-paged predicate costs a second `COUNT(*)` and is stale the moment it
 * is computed. So there is no "page 4 of 12" to jump to, and no cursor for the
 * page *before* this one: the only way back is to remember where you came from.
 * Keeping every cursor visited makes Previous a pop, which is what
 * `ClientListPage` and `TicketListPage` already do on the ticketing side.
 *
 * <p>That pattern is reimplemented here rather than imported: the onboarding
 * module imports nothing from `features/tickets`, and `moduleSeparation.test.ts`
 * enforces it.
 *
 * <h2>The stack lives in component state, while the filters live in the URL</h2>
 *
 * A filtered grid is a link and a paged one is not — a cursor is an opaque
 * position in one ordered result set, so a pasted `?cursor=` means nothing to
 * the person who receives it and cannot be stepped back from. Filters therefore
 * stay in the query string, and the position within them stays here.
 *
 * <h2>`resetKey` is what stops the silent bug</h2>
 *
 * Resuming a cursor under a different filter does not return an empty page — it
 * returns rows from an arbitrary position in a result set the cursor was never
 * ordered against, which looks like data. Hence every filter change must return
 * to page one, and hence the reset is driven by a key derived from the filters
 * rather than by remembering to call a reset in each of five change handlers.
 * Miss one of those and the grid lies rather than errors.
 *
 * @param resetKey any string that changes when the filters do — the filter
 *                 query string is the natural one
 */
export function useCursorPages(resetKey: string): CursorPages {
  const [stack, setStack] = React.useState<readonly string[]>(NO_PAGES)
  const [key, setKey] = React.useState(resetKey)

  /*
    React's documented "adjust state while rendering" pattern rather than an
    effect: an effect would let one render — and therefore one fetch — go out
    with the old cursor under the new filter before the reset landed, which is
    the exact request this hook exists to prevent. The local `pages` is what
    keeps even the discarded first pass correct.
  */
  const stale = key !== resetKey
  if (stale) {
    setKey(resetKey)
    setStack(NO_PAGES)
  }
  const pages = stale ? NO_PAGES : stack

  return {
    cursor: pages[pages.length - 1],
    pageIndex: pages.length,
    canGoBack: pages.length > 0,
    next: React.useCallback((nextCursor: string | null | undefined) => {
      // Guarded rather than trusted: `nextCursor` is null on the last page, and
      // pushing that would page to the top of the list while claiming to move on.
      if (nextCursor) setStack((current) => [...current, nextCursor])
    }, []),
    previous: React.useCallback(() => {
      setStack((current) => current.slice(0, -1))
    }, []),
  }
}
