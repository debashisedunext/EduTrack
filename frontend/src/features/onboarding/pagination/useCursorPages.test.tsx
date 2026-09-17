import type { ReactNode } from 'react'
import { act, renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { useCursorPages } from './useCursorPages'

/**
 * The stack behind Previous, and the reset that stops the grid lying.
 *
 * The second of those is the one worth a test: resuming a cursor under a
 * changed filter does not fail, it returns rows from an arbitrary position in a
 * result set the cursor was never ordered against. Nothing errors and the grid
 * looks like it answered.
 */
function renderPages(initialKey = 'status=RUNNING') {
  /** Every cursor any render pass saw — the discarded ones included. */
  const seen: Array<string | undefined> = []

  const view = renderHook(
    ({ key }: { key: string }) => {
      const pages = useCursorPages(key)
      seen.push(pages.cursor)
      return pages
    },
    {
      initialProps: { key: initialKey },
      wrapper: ({ children }: { children: ReactNode }) => <>{children}</>,
    },
  )

  return { ...view, seen }
}

describe('useCursorPages', () => {
  it('starts on the first page, with no cursor and nowhere back to', () => {
    const { result } = renderPages()

    expect(result.current.cursor).toBeUndefined()
    expect(result.current.pageIndex).toBe(0)
    expect(result.current.canGoBack).toBe(false)
  })

  it('steps forward onto the cursor the server named', () => {
    const { result } = renderPages()

    act(() => result.current.next('cursor-10'))

    expect(result.current.cursor).toBe('cursor-10')
    expect(result.current.pageIndex).toBe(1)
    expect(result.current.canGoBack).toBe(true)
  })

  /**
   * `meta.nextCursor` is null on the last page. Pushing that would send a
   * request with no cursor — the first page — while the stack claimed to have
   * moved on, so Previous would then land on the page just shown.
   */
  it('ignores a null next cursor rather than paging back to the top', () => {
    const { result } = renderPages()

    act(() => result.current.next(null))
    act(() => result.current.next(undefined))

    expect(result.current.pageIndex).toBe(0)
    expect(result.current.canGoBack).toBe(false)
  })

  it('pops back through the pages it came by', () => {
    const { result } = renderPages()

    act(() => result.current.next('cursor-10'))
    act(() => result.current.next('cursor-20'))
    expect(result.current.pageIndex).toBe(2)

    act(() => result.current.previous())

    expect(result.current.cursor).toBe('cursor-10')
    expect(result.current.pageIndex).toBe(1)

    act(() => result.current.previous())

    expect(result.current.cursor).toBeUndefined()
    expect(result.current.canGoBack).toBe(false)
  })

  it('returns to the first page when the filters change', () => {
    const { result, rerender } = renderPages('status=RUNNING')

    act(() => result.current.next('cursor-10'))
    act(() => result.current.next('cursor-20'))

    rerender({ key: 'status=COMPLETED' })

    expect(result.current.cursor).toBeUndefined()
    expect(result.current.pageIndex).toBe(0)
    expect(result.current.canGoBack).toBe(false)
  })

  /**
   * The reset has to happen *during* the render that first sees the new key.
   * An effect would let one pass — and so one fetch — go out carrying the old
   * cursor under the new filter, which is the request this hook exists to
   * prevent. So the assertion is on every pass, not just on the settled state.
   */
  it('never renders the old cursor under the new filter', () => {
    const { result, rerender, seen } = renderPages('status=RUNNING')

    act(() => result.current.next('cursor-10'))
    seen.length = 0

    rerender({ key: 'status=COMPLETED' })

    expect(seen.length).toBeGreaterThan(0)
    expect(seen).toEqual(seen.map(() => undefined))
  })

  it('keeps the page when the key is unchanged', () => {
    const { result, rerender } = renderPages('status=RUNNING')

    act(() => result.current.next('cursor-10'))
    rerender({ key: 'status=RUNNING' })

    expect(result.current.cursor).toBe('cursor-10')
  })
})
