import * as React from 'react'
import * as DialogPrimitive from '@radix-ui/react-dialog'
import { useNavigate } from 'react-router-dom'
import { Search, Ticket as TicketIcon, User as UserIcon, CornerDownLeft } from 'lucide-react'
import { useGlobalSearch } from '@/api/generated/search/search'
import type { GlobalSearchPersonHit, GlobalSearchTicketHit } from '@/api/generated/model'
import { Chip, type ChipProps } from '@/components/ui/chip'
import { cn } from '@/lib/utils'
import { resourcePath, ticketPath } from '@/features/tickets/detail/entityLinks'
import { useCommandPaletteStore } from './commandPaletteStore'

const LEVEL_VARIANT: Record<string, ChipProps['variant']> = {
  LOW: 'low', MEDIUM: 'medium', HIGH: 'high', CRITICAL: 'critical',
}

/**
 * Jump-to-ticket, Ctrl+K — blueprint §12.3, and A-072's global search.
 *
 * <h2>What A-072 changed</h2>
 *
 * C-006 built this against `GET /tickets?q=`, which searches tickets and only
 * tickets, with `LIKE '%term%'` over title, description and code. Two things
 * were wrong with that and both are visible to a user:
 *
 * - **The top bar promised "person" and no search returned one.** §7.2 words
 *   the box as "ticket ID / keyword / person"; two of the three worked.
 * - **A pasted ticket code was a substring match, not a lookup.** PLAN.md §3.8
 *   calls the code "the dominant search" and requires it to be exact and
 *   instant — the unique index, not a table scan behind a leading wildcard.
 *
 * `GET /search` answers all three, and the code branch comes back as its own
 * field so Enter can go straight there.
 *
 * <h2>Enter means the exact ticket when there is one</h2>
 *
 * Somebody who pasted a code wants that ticket, not the first row of a ranked
 * list that happens to be selected. So an exact hit is always the first item
 * and always what an untouched Enter opens; arrow keys move off it normally.
 * The alternative — treating it as an ordinary result — makes the common case
 * (paste, Enter) depend on nothing else having scored higher.
 */
export function CommandPalette() {
  const open = useCommandPaletteStore((s) => s.open)
  const setOpen = useCommandPaletteStore((s) => s.setOpen)
  const navigate = useNavigate()

  const [query, setQuery] = React.useState('')
  const [debounced, setDebounced] = React.useState('')
  const [activeIndex, setActiveIndex] = React.useState(0)

  React.useEffect(() => {
    const id = setTimeout(() => setDebounced(query.trim()), 200)
    return () => clearTimeout(id)
  }, [query])

  React.useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setOpen(!useCommandPaletteStore.getState().open)
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [setOpen])

  React.useEffect(() => {
    if (!open) { setQuery(''); setDebounced(''); setActiveIndex(0) }
  }, [open])

  const { data, isFetching } = useGlobalSearch(
    { q: debounced },
    { query: { enabled: debounced.length > 0 } },
  )

  const results = data?.data

  /*
    One flat list behind the sections, because the keyboard moves through
    everything in one sequence and a per-section index would have to know how
    many sections are non-empty to know where the next arrow lands. The
    rendering below reads back out of this list, so what is drawn and what
    Enter opens cannot disagree — which they did in the first version, where
    the sections were mapped independently and a hidden empty group put the
    highlight one row off.
  */
  type Row =
    | { kind: 'ticket'; ticket: GlobalSearchTicketHit; exact: boolean }
    | { kind: 'person'; person: GlobalSearchPersonHit }

  // The `?? []` defaults live inside the memo rather than above it: a fresh
  // array literal on every render is a new dependency every render, which makes
  // the memo do nothing and warns.
  const rows: Row[] = React.useMemo(() => [
    ...(results?.exactTicket ? [{ kind: 'ticket' as const, ticket: results.exactTicket, exact: true }] : []),
    ...(results?.tickets ?? []).map((t) => ({ kind: 'ticket' as const, ticket: t, exact: false })),
    ...(results?.people ?? []).map((p) => ({ kind: 'person' as const, person: p })),
  ], [results])

  React.useEffect(() => setActiveIndex(0), [debounced])

  function go(row: Row | undefined) {
    if (!row) return
    setOpen(false)
    if (row.kind === 'ticket') {
      if (row.ticket.ticketId) navigate(ticketPath(row.ticket.ticketId))
    } else {
      // A-069's resource profile — the one screen a person's name should open.
      navigate(resourcePath(row.person.id))
    }
  }

  function onKeyDown(e: React.KeyboardEvent) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setActiveIndex((i) => Math.min(i + 1, rows.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActiveIndex((i) => Math.max(i - 1, 0))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      go(rows[activeIndex])
    }
  }

  const nothingFound = debounced.length > 0 && !isFetching && rows.length === 0

  return (
    <DialogPrimitive.Root open={open} onOpenChange={setOpen}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />
        <DialogPrimitive.Content
          onOpenAutoFocus={(e) => e.preventDefault()}
          className={cn(
            'fixed left-1/2 top-24 z-50 w-full max-w-xl -translate-x-1/2 overflow-hidden rounded-card border border-border bg-surface shadow-modal',
            'data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0',
            'data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95',
          )}
        >
          <DialogPrimitive.Title className="sr-only">Search</DialogPrimitive.Title>
          <DialogPrimitive.Description className="sr-only">
            Search by ticket ID, keyword or person
          </DialogPrimitive.Description>
          <div className="flex items-center gap-2 border-b border-border px-4">
            <Search className="h-4 w-4 shrink-0 text-content-muted" />
            <input
              autoFocus
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={onKeyDown}
              placeholder="Paste a ticket ID, or search by keyword or person…"
              className="h-12 w-full bg-transparent text-sm text-content outline-none placeholder:text-content-muted"
            />
            <kbd className="rounded border border-border px-1.5 py-0.5 text-[10px] font-medium text-content-muted">
              Esc
            </kbd>
          </div>

          <ul role="listbox" aria-label="Search results" className="max-h-96 overflow-y-auto p-1">
            {debounced.length === 0 && (
              <li className="px-4 py-8 text-center text-sm text-content-muted">
                Paste a ticket ID to jump straight to it, or search by keyword or name.
              </li>
            )}

            {nothingFound && (
              <li className="px-4 py-8 text-center text-sm text-content-muted">
                Nothing matches “{debounced}”.
              </li>
            )}

            {rows.map((row, index) => {
              const active = index === activeIndex
              const key = row.kind === 'ticket'
                ? `t-${row.exact ? 'exact-' : ''}${row.ticket.ticketId}`
                : `p-${row.person.id}`

              return (
                <li
                  key={key}
                  role="option"
                  aria-selected={active}
                  onMouseEnter={() => setActiveIndex(index)}
                  onClick={() => go(row)}
                  className={cn(
                    'flex cursor-pointer items-center gap-3 rounded-control px-3 py-2.5 text-sm',
                    active && 'bg-primary-soft',
                  )}
                >
                  {row.kind === 'ticket' ? (
                    <>
                      <TicketIcon className="h-4 w-4 shrink-0 text-content-muted" aria-hidden />
                      <span className="shrink-0 font-mono text-xs text-content-muted">
                        {row.ticket.ticketId}
                      </span>
                      <span className="flex-1 truncate text-content">{row.ticket.title}</span>
                      {/*
                        Said in words, not only by position. An exact hit sitting
                        first looks like a ranking accident, and the whole point
                        of the deep link is that it is not one.
                      */}
                      {row.exact && (
                        <span className="flex shrink-0 items-center gap-1 text-[10px] font-medium text-content-muted">
                          <CornerDownLeft className="h-3 w-3" aria-hidden />
                          exact match
                        </span>
                      )}
                      {row.ticket.level && (
                        <Chip variant={LEVEL_VARIANT[row.ticket.level]}>{row.ticket.level}</Chip>
                      )}
                    </>
                  ) : (
                    <>
                      <UserIcon className="h-4 w-4 shrink-0 text-content-muted" aria-hidden />
                      <span className="flex-1 truncate text-content">{row.person.displayName}</span>
                      {row.person.role && (
                        <span className="shrink-0 text-xs text-content-muted">{row.person.role}</span>
                      )}
                    </>
                  )}
                </li>
              )
            })}
          </ul>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
