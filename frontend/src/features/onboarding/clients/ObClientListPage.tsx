import * as React from 'react'
import { keepPreviousData } from '@tanstack/react-query'
import { ChevronLeft, ChevronRight, RotateCcw, Search } from 'lucide-react'
import { Link } from 'react-router-dom'

import { useListObClients } from '@/api/generated/onboarding/onboarding'
import { useListObProducts } from '@/api/generated/onboarding-masters/onboarding-masters'
import { useListUsers } from '@/api/generated/users/users'
import type { ObClient } from '@/api/generated/model/obClient'

import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { FilterDropdown } from '@/components/ui/filter-dropdown'
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

import { healthChip, journeyProgress, productSummary, statusChip } from './obClientRow'
import { toQueryParams, useObClientFilters } from './useObClientFilters'

const PAGE_SIZE = 25
const SEARCH_DEBOUNCE_MS = 300
const COLUMN_COUNT = 8

const STATUS_OPTIONS = [
  { value: 'ONBOARDING', label: 'Onboarding' },
  { value: 'LIVE', label: 'Live' },
  { value: 'ON_HOLD', label: 'On hold' },
  { value: 'DROPPED', label: 'Dropped' },
] as const

/**
 * The three colours, under the names the rest of the module already uses.
 *
 * `journeyStrip.ragLabel` is the source — "On track", "At risk", "Breached" —
 * rather than GREEN/AMBER/RED, because a filter whose vocabulary differs from
 * the chip it filters on reads as two different fields.
 */
const RAG_OPTIONS = [
  { value: 'GREEN', label: 'On track' },
  { value: 'AMBER', label: 'At risk' },
  { value: 'RED', label: 'Breached' },
] as const

const GATE_OPTIONS = [
  { value: 'LOCKED', label: 'Prerequisites pending' },
  { value: 'OPEN', label: 'Gate open' },
] as const

/**
 * B-108 · OB-03, the onboarding client list — `/onboarding/clients`.
 *
 * Plan §9's line for this screen is one sentence — "shows journey count + worst
 * RAG; 'Prerequisites pending' state" — and the whole design is in why those
 * three things are three columns rather than one chip. See `obClientRow.ts`.
 *
 * ## This is not S-32
 *
 * `features/clients/ClientListPage` is the *ticketing* client master. The two
 * tables are disjoint with no foreign key between them (plan §1.2), a company
 * present in both is linked by an explicit audited admin action through
 * `client_accounts`, and this grid's vocabulary — journeys, gates, RAG, sales
 * person — has no counterpart over there. The shape of the two screens is
 * deliberately similar because they are both keyset grids with a filter row
 * and because a reader who knows one should not have to learn the other; the
 * data is not shared and neither is the code.
 *
 * ## Six filters, and the two the backlog does not name
 *
 * Status, RAG, owner and sales person are the four B-108 asks for. `productId`
 * and `gateStatus` come from the contract, and `gateStatus` in particular is
 * not optional: it is the only way to ask for §9's "Prerequisites pending"
 * clients, because they have no colour and are returned by none of the three
 * RAG values. `useObClientFilters` carries that argument in full.
 *
 * ## The row is a link, and the whole row
 *
 * OB-05 is what somebody wants from every one of these rows, so the client name
 * is an anchor rather than an `onClick` on the `<tr>`: middle-click, Ctrl-click
 * and "copy link" all work, and the row stays one tab stop rather than nine.
 * `/onboarding/clients/{id}` is C-110's route and the path every onboarding
 * mail already points at (`ObMailLinks`).
 *
 * ## What is deliberately not here
 *
 * **No create button.** OB-04 is B-109's four-step wizard and does not exist;
 * a "New client" button that 404s is worse than an absent one, and the wizard
 * lands on the route this page will link to.
 *
 * **No bulk actions and no row selection.** S-32 has both because activating
 * and deactivating clients in bulk is a real administrative act there. Nothing
 * on an onboarding client is safely settable in bulk: `LIVE` is earned rather
 * than set, and `ON_HOLD` and `DROPPED` each require a reason that is
 * per-client by nature.
 *
 * **No sort controls.** The contract orders by `onboardingDate` descending and
 * offers no alternative — the keyset cursor is built on exactly that ordering,
 * so a sort control would be a second contract and a second cursor. Newest
 * first is also the right default for a list whose top is where work arrives.
 */
export function ObClientListPage() {
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

  // ── filter option sources ─────────────────────────────────────────────────
  //
  // The whole catalogue, not `isActive: true`. A retired product's clients are
  // still onboarding through it — `ObApplicationService` makes that point about
  // renewals — so filtering the picker to bookable products would hide exactly
  // the clients somebody is looking for. OB-04's picker is the one that wants
  // active only, and it is a different screen.
  const { data: productsData } = useListObProducts(undefined)
  const products = React.useMemo(() => productsData?.data ?? [], [productsData])

  // Read unfiltered, on `ClientListPage`'s reasoning: narrowing the directory
  // by the grid's own filters would hide the person you are filtering by.
  const { data: usersData } = useListUsers({ isActive: true, limit: 200 })
  const users = React.useMemo(() => usersData?.data ?? [], [usersData])

  // ── the list itself ───────────────────────────────────────────────────────
  const { data, isPending, isError, error, refetch, isFetching } = useListObClients(
    { cursor, limit: PAGE_SIZE, ...toQueryParams(filters) },
    { query: { placeholderData: keepPreviousData } },
  )

  const clients = React.useMemo(() => data?.data ?? [], [data])
  const meta = data?.meta

  const pageStart = clients.length === 0 ? 0 : cursorStack.length * PAGE_SIZE + 1
  const pageEnd = pageStart === 0 ? 0 : pageStart + clients.length - 1

  /**
   * The one filter combination that can never match anything, named rather
   * than left to look like a bug.
   *
   * A locked gate means no clock is running, so the client has no colour at
   * all. Asking for `LOCKED` and a colour together asks for a client that is
   * both started and not started, and the server correctly answers with
   * nothing — which is indistinguishable from "no such clients" unless the
   * screen says otherwise.
   */
  const contradictoryFilters = filters.gateStatus === 'LOCKED' && filters.rag != null

  return (
    <div className="flex h-full flex-col gap-4 p-6">
      {/* ── header ─────────────────────────────────────────────────────── */}
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-h1 text-content">Onboarding clients</h1>

        <div className="relative ml-2 min-w-[16rem] max-w-sm flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-content-muted" />
          <Input
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') updateFilter('q', searchInput)
            }}
            /* Name only. The contract is explicit that this is never a PAN
               search — the PAN is masked on the way out, so matching on it
               would make the search an oracle for a value no read returns. */
            placeholder="Search client name…"
            aria-label="Search onboarding clients by name"
            className="pl-9"
          />
        </div>

        <div className="ml-auto flex items-center gap-2">
          <Button asChild variant="secondary" size="sm">
            <Link to="/onboarding/dashboard">Dashboard</Link>
          </Button>
        </div>
      </div>

      {/* ── filters ────────────────────────────────────────────────────── */}
      <div className="flex flex-wrap items-center gap-2">
        <FilterDropdown
          label="Status"
          options={[...STATUS_OPTIONS]}
          value={STATUS_OPTIONS.find((s) => s.value === filters.status) ?? null}
          onChange={(s) => updateFilter('status', s?.value ?? null)}
          getKey={(s) => s.value}
          getLabel={(s) => s.label}
          searchable={false}
        />
        <FilterDropdown
          label="Health"
          options={[...RAG_OPTIONS]}
          value={RAG_OPTIONS.find((r) => r.value === filters.rag) ?? null}
          onChange={(r) => updateFilter('rag', r?.value ?? null)}
          getKey={(r) => r.value}
          getLabel={(r) => r.label}
          searchable={false}
        />
        <FilterDropdown
          label="Gate"
          options={[...GATE_OPTIONS]}
          value={GATE_OPTIONS.find((g) => g.value === filters.gateStatus) ?? null}
          onChange={(g) => updateFilter('gateStatus', g?.value ?? null)}
          getKey={(g) => g.value}
          getLabel={(g) => g.label}
          searchable={false}
        />
        <FilterDropdown
          label="Implementor"
          options={users}
          value={users.find((u) => u.id === filters.ownerId) ?? null}
          onChange={(u) => updateFilter('ownerId', u?.id ?? null)}
          getKey={(u) => String(u.id)}
          getLabel={(u) => u.displayName}
          getSearchable={(u) => [u.email ?? '']}
        />
        <FilterDropdown
          label="Sales person"
          options={users}
          value={users.find((u) => u.id === filters.salesPersonId) ?? null}
          onChange={(u) => updateFilter('salesPersonId', u?.id ?? null)}
          getKey={(u) => String(u.id)}
          getLabel={(u) => u.displayName}
          getSearchable={(u) => [u.email ?? '']}
        />
        <FilterDropdown
          label="Product"
          options={products}
          value={products.find((p) => p.id === filters.productId) ?? null}
          onChange={(p) => updateFilter('productId', p?.id ?? null)}
          getKey={(p) => String(p.id)}
          getLabel={(p) => p.name}
          getSearchable={(p) => [p.code]}
        />
        {activeCount > 0 && (
          <button
            type="button"
            onClick={() => {
              setCursorStack([])
              resetFilters()
            }}
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
              <TableHead>Status</TableHead>
              <TableHead>Health</TableHead>
              <TableHead className="text-right">Journeys</TableHead>
              <TableHead>Products</TableHead>
              <TableHead>Primary SPOC</TableHead>
              <TableHead>Sales</TableHead>
              <TableHead>Onboarded</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isPending ? (
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
                      contradictoryFilters
                        ? 'A client behind its prerequisite gate has no health colour yet — nothing is running to colour. Clear one of the two filters.'
                        : activeCount > 0 || filters.q
                          ? 'Try clearing a filter or widening the search.'
                          : 'Clients appear here once they are boarded.'
                    }
                    action={
                      activeCount > 0 ? (
                        <Button
                          variant="secondary"
                          size="sm"
                          onClick={() => {
                            setCursorStack([])
                            resetFilters()
                          }}
                        >
                          Reset filters
                        </Button>
                      ) : undefined
                    }
                  />
                </TableCell>
              </TableRow>
            ) : (
              clients.map((client) => (
                <ObClientRow key={client.id} client={client} isFetching={isFetching} />
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

function ObClientRow({ client, isFetching }: { client: ObClient; isFetching: boolean }) {
  const health = healthChip(client)
  const status = statusChip(client.status)
  const journeys = journeyProgress(client)
  const products = productSummary(client)

  return (
    <TableRow aria-busy={isFetching || undefined}>
      <TableCell>
        <Link
          to={`/onboarding/clients/${client.id}`}
          className="font-medium text-content hover:text-primary hover:underline"
        >
          {client.name}
        </Link>
        {client.hasPortalLogin ? (
          <span className="ml-2 align-middle">
            {/* A fact about the client, not an action. B-126's OB-05 panel is
                where a login is created, reset or disabled. */}
            <Chip variant="neutral">Portal</Chip>
          </span>
        ) : null}
      </TableCell>
      <TableCell>
        <Chip variant={status.variant}>{status.label}</Chip>
      </TableCell>
      <TableCell>
        <Chip variant={health.variant} title={health.hint}>
          {health.label}
        </Chip>
      </TableCell>
      <TableCell className="text-right tabular-nums" title={journeys.hint}>
        {journeys.label}
      </TableCell>
      <TableCell title={products.title}>
        {products.shown.length === 0 ? (
          <span className="text-content-muted">—</span>
        ) : (
          <span className="flex flex-wrap items-center gap-1">
            {products.shown.map((name) => (
              <Chip key={name} variant="neutral">
                {name}
              </Chip>
            ))}
            {products.overflow > 0 ? (
              <span className="text-caption text-content-muted">+{products.overflow}</span>
            ) : null}
          </span>
        )}
      </TableCell>
      <TableCell>
        {client.primaryContact ? (
          <span className="flex flex-col">
            <span className="text-content">{client.primaryContact.name}</span>
            <span className="text-caption text-content-muted">{client.primaryContact.email}</span>
          </span>
        ) : (
          // Not merely missing data: a client with no primary SPOC has nobody
          // for the module's mail to reach, and OB-05's panel is where it is
          // fixed. Said plainly rather than left as an em dash.
          <span className="text-warning-text">No primary SPOC</span>
        )}
      </TableCell>
      <TableCell className="text-content-muted">{client.salesPerson?.displayName ?? '—'}</TableCell>
      <TableCell className="text-content-muted tabular-nums">{client.onboardingDate}</TableCell>
    </TableRow>
  )
}
