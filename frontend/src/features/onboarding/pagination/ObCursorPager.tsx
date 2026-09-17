import { ChevronLeft, ChevronRight } from 'lucide-react'

import { Button } from '@/components/ui/button'

import { OB_PAGE_SIZE } from './useCursorPages'

export interface ObCursorPagerProps {
  /** Zero-based page number, from `useCursorPages`. */
  pageIndex: number
  /** How many rows this page actually rendered. */
  rowsOnPage: number
  /** `meta.hasMore` — whether the server has a row beyond this page. */
  hasMore: boolean
  /** True while a page is in flight; both controls are held until it lands. */
  isFetching: boolean
  canGoBack: boolean
  onPrevious: () => void
  onNext: () => void
  /** Plural noun for the empty caption — "projects", "clients". */
  noun: string
  pageSize?: number
}

/**
 * Previous / Next, and which rows are on screen.
 *
 * <h2>A row range rather than "page 3 of 12"</h2>
 *
 * There is no twelve to print. `PageMeta` carries no `totalCount` by design, so
 * the last page is only knowable once you are on it — `hasMore` is false. The
 * range is therefore computed from the page size and how far into the stack we
 * are, which is honest about everything it claims and claims nothing about the
 * end of the list.
 *
 * <h2>Both buttons are disabled while a page is in flight</h2>
 *
 * Not for tidiness: the query keeps the previous page on screen while the next
 * one loads, so `meta` still describes the page you are leaving. A second click
 * would push that same `nextCursor` twice and skip a page while the stack
 * claimed otherwise.
 *
 * <p>The range is announced with `role="status"` because for a keyboard or
 * screen-reader user, Next is a button whose only visible effect is elsewhere
 * on the page; without the live region nothing says the grid moved.
 */
export function ObCursorPager({
  pageIndex,
  rowsOnPage,
  hasMore,
  isFetching,
  canGoBack,
  onPrevious,
  onNext,
  noun,
  pageSize = OB_PAGE_SIZE,
}: ObCursorPagerProps) {
  const firstRow = rowsOnPage === 0 ? 0 : pageIndex * pageSize + 1
  const lastRow = firstRow === 0 ? 0 : firstRow + rowsOnPage - 1

  return (
    <nav
      aria-label={`${noun} pagination`}
      className="flex items-center justify-between text-caption text-content-muted"
    >
      <p role="status">
        {rowsOnPage === 0 ? `0 ${noun}` : `Rows ${firstRow}–${lastRow}`}
      </p>
      <div className="flex items-center gap-2">
        <Button
          variant="secondary"
          size="sm"
          disabled={!canGoBack || isFetching}
          onClick={onPrevious}
        >
          <ChevronLeft className="h-4 w-4" />
          Previous
        </Button>
        <Button
          variant="secondary"
          size="sm"
          disabled={!hasMore || isFetching}
          onClick={onNext}
        >
          Next
          <ChevronRight className="h-4 w-4" />
        </Button>
      </div>
    </nav>
  )
}
