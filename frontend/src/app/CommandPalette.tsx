import * as React from 'react'
import * as DialogPrimitive from '@radix-ui/react-dialog'
import { useNavigate } from 'react-router-dom'
import { Search, Ticket as TicketIcon } from 'lucide-react'
import { useListTickets } from '@/api/generated/tickets/tickets'
import { Chip, type ChipProps } from '@/components/ui/chip'
import { cn } from '@/lib/utils'
import { useCommandPaletteStore } from './commandPaletteStore'

const LEVEL_VARIANT: Record<string, ChipProps['variant']> = {
  LOW: 'low', MEDIUM: 'medium', HIGH: 'high', CRITICAL: 'critical',
}

/**
 * Jump-to-ticket, Ctrl+K — blueprint §12.3. Global: mounted once in AppShell,
 * opens from anywhere via the keyboard or the top bar's hint (TopBar.tsx).
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

  const { data, isFetching } = useListTickets(
    { q: debounced, limit: 8 },
    { query: { enabled: debounced.length > 0 } },
  )
  const results = data?.data ?? []

  React.useEffect(() => setActiveIndex(0), [debounced])

  function select(ticketId?: string) {
    if (!ticketId) return
    setOpen(false)
    navigate(`/tickets/${ticketId}`)
  }

  function onKeyDown(e: React.KeyboardEvent) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setActiveIndex((i) => Math.min(i + 1, results.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActiveIndex((i) => Math.max(i - 1, 0))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      select(results[activeIndex]?.ticketCode)
    }
  }

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
          <DialogPrimitive.Title className="sr-only">Jump to ticket</DialogPrimitive.Title>
          <DialogPrimitive.Description className="sr-only">
            Search tickets by ID, title or description
          </DialogPrimitive.Description>
          <div className="flex items-center gap-2 border-b border-border px-4">
            <Search className="h-4 w-4 shrink-0 text-content-muted" />
            <input
              autoFocus
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={onKeyDown}
              placeholder="Jump to a ticket by ID, title or keyword…"
              className="h-12 w-full bg-transparent text-sm text-content outline-none placeholder:text-content-muted"
            />
            <kbd className="rounded border border-border px-1.5 py-0.5 text-[10px] font-medium text-content-muted">
              Esc
            </kbd>
          </div>

          <ul role="listbox" className="max-h-96 overflow-y-auto p-1">
            {debounced.length === 0 && (
              <li className="px-4 py-8 text-center text-sm text-content-muted">
                Start typing to search every ticket you have access to.
              </li>
            )}
            {debounced.length > 0 && !isFetching && results.length === 0 && (
              <li className="px-4 py-8 text-center text-sm text-content-muted">
                No tickets match “{debounced}”.
              </li>
            )}
            {results.map((t, index) => (
              <li
                key={t.ticketCode}
                role="option"
                aria-selected={index === activeIndex}
                onMouseEnter={() => setActiveIndex(index)}
                onClick={() => select(t.ticketCode)}
                className={cn(
                  'flex cursor-pointer items-center gap-3 rounded-control px-3 py-2.5 text-sm',
                  index === activeIndex && 'bg-primary-soft',
                )}
              >
                <TicketIcon className="h-4 w-4 shrink-0 text-content-muted" />
                <span className="shrink-0 font-mono text-xs text-content-muted">{t.ticketCode}</span>
                <span className="flex-1 truncate text-content">{t.title}</span>
                {t.level && <Chip variant={LEVEL_VARIANT[t.level]}>{t.level}</Chip>}
              </li>
            ))}
          </ul>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
