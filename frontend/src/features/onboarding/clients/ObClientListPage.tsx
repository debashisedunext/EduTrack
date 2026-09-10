import * as React from 'react'
import { keepPreviousData } from '@tanstack/react-query'
import { format, parseISO } from 'date-fns'
import { ChevronLeft, ChevronRight, RotateCcw, Search } from 'lucide-react'
import { Link, useNavigate } from 'react-router-dom'

import { useListObClients } from '@/api/generated/onboarding/onboarding'
import type { ObClient } from '@/api/generated/model/obClient'

import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'

import { healthChip, journeyProgress } from './obClientRow'
import { isContradictory, toQueryParams, useObClientFilters } from './useObClientFilters'

const PAGE_SIZE = 25
const SEARCH_DEBOUNCE_MS = 300
const COLUMN_COUNT = 8

/**
 * The mockup's two selects, verbatim (`vClients()` in
 * `docs/prototype/onboarding.html`): Status offers All/Onboarding/Live, and
 * Health offers the three colours plus Live. `ON_HOLD` and `DROPPED` are real
 * `ObClientStatus` values and still parse from the URL — they are simply not
 * offered here, because the mockup's select does not offer them.
 */
const STATUS_OPTIONS = [
  { value: '', label: 'All' },
  { value: 'ONBOARDING', label: 'Onboarding' },
  { value: 'LIVE', label: 'Live' },
] as const

const HEALTH_OPTIONS = [
  { value: '', label: 'All' },
  { value: 'GREEN', label: 'On track' },
  { value: 'AMBER', label: 'At risk' },
  { value: 'RED', label: 'Breached / blocked' },
  { value: 'LIVE', label: 'Live' },
] as const

/**
 * B-108 · OB-03, the onboarding client list — `/onboarding/clients`, aligned
 * to the authoritative mockup (`docs/prototype/onboarding.html`, `vClients()`).
 *
 * ## What the mockup shows that the list read cannot
 *
 * The mockup's table carries four things `ObClient` deliberately does not:
 * the "city · PAN masked" caption under the name (identity data is the detail
 * read's, where the masking rule and its audit apply), the current *step*
 * ("3/8 · Data migration"), the step's owner, and the license type. Those
 * columns render what the row does return — the journey roll-up in Current
 * step — and the rest is a contract gap named in the B-130 report rather than
 * a second endpoint invented here.
 *
 * ## The "+ Board a new client" button is not role-gated client-side
 *
 * The mockup gates it on OB_ADMIN/MANAGER/SALES. This codebase's position
 * (RequireAuth.tsx, `ObModuleRoleRules`) is that role checks live server-side
 * only — a frontend gate is authorisation theatre and the top risk in
 * blueprint §17. The button shows; a caller with no standing is refused by
 * the create endpoint.
 *
 * ## Rows are links *and* clickable
 *
 * The client name stays an anchor — middle-click, Ctrl-click and "copy link"
 * work — and the whole row also navigates on click or Enter with
 * `tabindex="0"`, which is the mockup's interaction.
 *
 * ## URL-only filters
 *
 * `gateStatus`, `ownerId`, `salesPersonId` and `productId` remain in
 * `useObClientFilters` and keep working from the URL (OB-02's cards deep-link
 * into them), but the filter card shows the mockup's three controls only.
 *
 * ## Products Bought, Start Date and Expected Completion are not in the mockup
 *
 * Added on top of `vClients()` rather than aligned to it: `products` was
 * already on `ObClient`, and `startedAt`/`currentStep.dueAt` were added to
 * the contract for this. Both dates are null under the same "nothing is
 * running to name" cases `currentStep` already documents, except that
 * `startedAt` stays populated once a finished primary journey has no current
 * step left — a client that is done still started on a real day.
 */
export function ObClientListPage() {
  const navigate = useNavigate()
  const { filters, setFilter, resetFilters, activeCount } = useObClientFilters()

  // Cursor pagination has no page numbers to jump to. The stack is what makes
  // "Previous" possible without an offset — CONVENTIONS.md §6.
  const [cursorStack, setCursorStack] = React.useState<string[]>([])
  const cursor = cursorStack[cursorStack.length - 1]

  const filterSignature = JSON.stringify(filters)
  const lastFilterSignature = React.useRef(filterSignature)
  React.useEffect(() => {
    if (lastFilterSignature.current !== filterSignature) {
      lastFilterSignature.current = filterSignature
      // A cursor is a position in one ordered result set. Carrying it into a
      // different filter asks the server to resume a page of rows that are no
      // longer the rows being listed, and the answer is arbitrary rather than
      // empty — which is worse, because it looks like data.
      setCursorStack([])
    }
  }, [filterSignature])

  function updateFilter<K extends keyof typeof filters>(key: K, value: (typeof filters)[K]) {
    setCursorStack([])
    setFilter(key, value)
  }

  // ── search box — debounced so every keystroke does not refetch ────────────
  const [searchInput, setSearchInput] = React.useState(filters.q)
  React.useEffect(() => setSearchInput(filters.q), [filters.q])
  React.useEffect(() => {
    if (searchInput === filters.q) return
    const timer = setTimeout(() => updateFilter('q', searchInput), SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchInput])

  /**
   * The mockup's caption: "N boarded · M live". Two limit-1 reads whose
   * `meta.totalCount` is the figure — the contract marks `totalCount`
   * "present only where a count is cheap", and this list is one of those
   * places. Unfiltered on purpose: the caption describes the book of
   * clients, not the current filter.
   */
  const boardedCount = useListObClients({ limit: 1 })
  const liveCount = useListObClients({ status: 'LIVE', limit: 1 })
  const boarded = boardedCount.data?.meta?.totalCount
  const live = liveCount.data?.meta?.totalCount

  /**
   * The filter combinations that can never match anything, named rather than
   * left to look like a bug: a colour on a gate-locked client (nothing is
   * running to colour), or Health=Live under a status that is not LIVE. The
   * request is skipped — `toQueryParams`' Live translation must not override
   * the status the user chose.
   */
  const contradictoryFilters = isContradictory(filters)

  // ── the list itself ───────────────────────────────────────────────────────
  const { data, isPending, isError, error, refetch, isFetching } = useListObClients(
    { cursor, limit: PAGE_SIZE, ...toQueryParams(filters) },
    { query: { placeholderData: keepPreviousData, enabled: !contradictoryFilters } },
  )

  const clients = React.useMemo(() => data?.data ?? [], [data])
  const meta = data?.meta

  const pageStart = clients.length === 0 ? 0 : cursorStack.length * PAGE_SIZE + 1
  const pageEnd = pageStart === 0 ? 0 : pageStart + clients.length - 1

  const resetAll = () => {
    setCursorStack([])
    resetFilters()
  }

  const selectClassName =
    'h-9 rounded-control border border-border bg-surface px-3 text-sm text-content ' +
    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary'

  return (
    <div className="flex h-full flex-col gap-4 p-6">
      {/* ── page head — h1, "N boarded · M live", the boarding button ──── */}
      <div className="flex flex-wrap items-start gap-3">
        <div>
          <h1 className="text-h1 text-content">Projects</h1>
          {boarded != null && live != null && (
            <p className="mt-0.5 text-caption text-content-muted">
              {boarded} boarded · {live} live
            </p>
          )}
        </div>
        <div className="ml-auto">
          <Button asChild size="sm">
            <Link to="/onboarding/clients/new">+ Board a new client</Link>
          </Button>
        </div>
      </div>

      {/* ── filter card — Search · Status · Health, per the mockup ─────── */}
      <div className="flex flex-wrap items-end gap-3 rounded-card border border-border bg-surface px-5 py-3.5">
        <label className="flex min-w-[14rem] flex-1 flex-col gap-1">
          <span className="text-caption font-medium text-content-muted">Search</span>
          <span className="relative">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-content-muted" />
            <Input
              value={searchInput}
              onChange={(e) => setSearchInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') updateFilter('q', searchInput)
              }}
              /* Name only — narrower than the mockup's "Client, city or sales
                 person…". The contract is explicit that `q` matches the client
                 name and never the PAN: the PAN is masked on the way out, so
                 matching on it would make the search an oracle for a value no
                 read returns. City is not on the row at all. */
              placeholder="Search client name…"
              aria-label="Search onboarding clients by name"
              className="pl-9"
            />
          </span>
        </label>

        <label className="flex flex-col gap-1">
          <span className="text-caption font-medium text-content-muted">Status</span>
          <select
            aria-label="Status"
            className={selectClassName}
            value={filters.status ?? ''}
            onChange={(e) =>
              updateFilter('status', (e.target.value || null) as typeof filters.status)
            }
          >
            {STATUS_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1">
          <span className="text-caption font-medium text-content-muted">Health</span>
          <select
            aria-label="Health"
            className={selectClassName}
            value={filters.rag ?? ''}
            onChange={(e) => updateFilter('rag', (e.target.value || null) as typeof filters.rag)}
          >
            {HEALTH_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </label>

        {activeCount > 0 && (
          <button
            type="button"
            onClick={resetAll}
            className="flex h-9 items-center gap-1.5 rounded-control px-2.5 text-sm text-content-muted hover:bg-subtle hover:text-content"
          >
            <RotateCcw className="h-3.5 w-3.5" />
            Reset ({activeCount})
          </button>
        )}
      </div>

      {/* ── grid ───────────────────────────────────────────────────────── */}
      <TableContainer className="max-h-[calc(100vh-15rem)] flex-1">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Client</TableHead>
              <TableHead>Health</TableHead>
              <TableHead>Current step</TableHead>
              <TableHead>Products Bought</TableHead>
              <TableHead>Start Date</TableHead>
              <TableHead>Expected Completion</TableHead>
              <TableHead>Sales</TableHead>
              <TableHead>Boarded</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {contradictoryFilters ? (
              <TableRow>
                <TableCell colSpan={COLUMN_COUNT} className="p-0">
                  <EmptyState
                    title="No clients match these filters"
                    description={
                      filters.rag === 'LIVE'
                        ? 'Health "Live" and a non-Live status cannot both be true of one client. Clear one of the two filters.'
                        : 'A client behind its prerequisite gate has no health colour yet — nothing is running to colour. Clear one of the two filters.'
                    }
                    action={
                      <Button variant="secondary" size="sm" onClick={resetAll}>
                        Reset filters
                      </Button>
                    }
                  />
                </TableCell>
              </TableRow>
            ) : isPending ? (
              Array.from({ length: 8 }, (_, i) => (
                <TableRow key={i}>
                  {Array.from({ length: COLUMN_COUNT }, (_, c) => (
                    <TableCell key={c}>
                      <Skeleton className="h-4 w-full max-w-[10rem]" />
                    </TableCell>
                  ))}
                </TableRow>
              ))
            ) : isError ? (
              <TableRow>
                <TableCell colSpan={COLUMN_COUNT} className="p-0">
                  <EmptyState
                    title="Could not load clients"
                    description={
                      error instanceof Error
                        ? error.message
                        : 'Something went wrong. If it keeps failing, the onboarding module may not be enabled for your account.'
                    }
                    action={
                      <Button size="sm" onClick={() => refetch()}>
                        Retry
                      </Button>
                    }
                  />
                </TableCell>
              </TableRow>
            ) : clients.length === 0 ? (
              <TableRow>
                <TableCell colSpan={COLUMN_COUNT} className="p-0">
                  <EmptyState
                    title="No clients match these filters"
                    description={
                      activeCount > 0 || filters.q
                        ? 'Try clearing a filter or widening the search.'
                        : 'Clients appear here once they are boarded.'
                    }
                    action={
                      activeCount > 0 ? (
                        <Button variant="secondary" size="sm" onClick={resetAll}>
                          Reset filters
                        </Button>
                      ) : undefined
                    }
                  />
                </TableCell>
              </TableRow>
            ) : (
              clients.map((client) => (
                <ObClientRow
                  key={client.id}
                  client={client}
                  isFetching={isFetching}
                  onOpen={() => navigate(`/onboarding/clients/${client.id}`)}
                />
              ))
            )}
          </TableBody>
        </Table>
      </TableContainer>

      {/* ── pagination ─────────────────────────────────────────────────── */}
      <div className="flex items-center justify-between text-caption text-content-muted">
        <p role="status">{clients.length === 0 ? '0 clients' : `Rows ${pageStart}–${pageEnd}`}</p>
        <div className="flex items-center gap-2">
          <Button
            variant="secondary"
            size="sm"
            disabled={cursorStack.length === 0 || isFetching}
            onClick={() => setCursorStack((stack) => stack.slice(0, -1))}
          >
            <ChevronLeft className="h-4 w-4" />
            Previous
          </Button>
          <Button
            variant="secondary"
            size="sm"
            disabled={!meta?.hasMore || isFetching}
            onClick={() => {
              if (meta?.nextCursor) setCursorStack((stack) => [...stack, meta.nextCursor!])
            }}
          >
            Next
            <ChevronRight className="h-4 w-4" />
          </Button>
        </div>
      </div>
    </div>
  )
}

function ObClientRow({
  client,
  isFetching,
  onOpen,
}: {
  client: ObClient
  isFetching: boolean
  onOpen: () => void
}) {
  const health = healthChip(client)
  const journeys = journeyProgress(client)
  const journeyCount = client.journeyCount ?? 0

  return (
    <TableRow
      aria-busy={isFetching || undefined}
      // The mockup's rowlink: the whole row opens OB-05, from pointer or
      // keyboard. The name below stays a real anchor so browser affordances
      // (middle-click, copy link) keep working.
      tabIndex={0}
      onClick={onOpen}
      onKeyDown={(e) => {
        if (e.key === 'Enter' && e.target === e.currentTarget) onOpen()
      }}
      className="cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary"
    >
      <TableCell>
        <Link
          to={`/onboarding/clients/${client.id}`}
          onClick={(e) => e.stopPropagation()}
          className="font-medium text-content hover:text-primary hover:underline"
        >
          {client.name}
        </Link>
        {/* Mockup caption is "city · PAN masked" — neither is on the list row
            (identity data belongs to the detail read). Contract gap, reported. */}
      </TableCell>
      <TableCell>
        <Chip variant={health.variant} title={health.hint}>
          <span aria-hidden>{health.glyph}</span>
          <span>{health.label}</span>
        </Chip>
      </TableCell>
      <TableCell title={journeys.hint}>
        {journeys.complete ? (
          <span className="text-caption text-content-muted">{journeys.label}</span>
        ) : (
          <span className="flex items-center gap-1.5">
            {/* The mockup's small "N journeys" chip, shown only when >1. The
                step position and name ("3/8 · Data migration") are not on the
                list read — reported as a gap, not faked. */}
            {journeyCount > 1 && (
              <Chip variant="neutral" className="text-[10px]">
                {journeyCount} journeys
              </Chip>
            )}
            <span className="tabular-nums text-content">{journeys.label}</span>
          </span>
        )}
      </TableCell>
      <TableCell className="text-content-muted">
        {client.products && client.products.length > 0 ? (
          <span className="flex flex-wrap items-center gap-x-1">
            {client.products.map((p, i) => (
              <React.Fragment key={p.id}>
                {i > 0 && <span aria-hidden>,</span>}
                {/* Straight to this product's own ribbon — OB-05's card chooser
                    is a detour when the reader already knows which product
                    they want. stopPropagation so this wins over the row's own
                    click, which would otherwise still open the client page. */}
                <Link
                  to={`/onboarding/clients/${client.id}/products/${p.id}`}
                  onClick={(e) => e.stopPropagation()}
                  className="hover:text-primary hover:underline"
                >
                  {p.code ?? p.name}
                </Link>
              </React.Fragment>
            ))}
          </span>
        ) : (
          '—'
        )}
      </TableCell>
      <TableCell className="text-content-muted tabular-nums">
        {client.startedAt ? (
          <time dateTime={client.startedAt}>{format(parseISO(client.startedAt), 'd MMM yyyy')}</time>
        ) : (
          '—'
        )}
      </TableCell>
      <TableCell className="text-content-muted tabular-nums">
        {client.currentStep?.dueAt ? (
          <time dateTime={client.currentStep.dueAt}>
            {format(parseISO(client.currentStep.dueAt), 'd MMM yyyy')}
          </time>
        ) : (
          '—'
        )}
      </TableCell>
      <TableCell className="text-content-muted">{client.salesPerson?.displayName ?? '—'}</TableCell>
      <TableCell className="text-content-muted tabular-nums">{client.onboardingDate}</TableCell>
    </TableRow>
  )
}
