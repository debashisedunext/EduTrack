import * as React from 'react'
import { keepPreviousData } from '@tanstack/react-query'
import { ChevronDown, ChevronLeft, ChevronRight, Plus, RotateCcw, Search, Upload } from 'lucide-react'
import { Link } from 'react-router-dom'

import {
  useListClientContacts,
  useListClients,
  useSetClientStatusBulk,
} from '@/api/generated/clients/clients'
import { useListProjects } from '@/api/generated/projects/projects'
import { useListUsers } from '@/api/generated/users/users'
import type { Client } from '@/api/generated/model/client'

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
import { toast } from '@/components/ui/use-toast'
import { cn } from '@/lib/utils'

import { SUPPORT_PLANS } from './clientForm'
import { CLIENT_COLUMNS } from './columns'
import {
  ClientBulkStatusBar,
  DeactivationWarningDialog,
  type DeactivationCandidate,
} from './ClientBulkStatusBar'
import { toQueryParams, useClientFilters } from './useClientFilters'

const PAGE_SIZE = 25
const SEARCH_DEBOUNCE_MS = 300

const STATUS_OPTIONS = [
  { value: 'true', label: 'Active' },
  { value: 'false', label: 'Inactive' },
]

/**
 * The plans blueprint §4B.2 names in the Commercial group.
 *
 * A fixed list rather than a lookup, because there is no support-plan master —
 * §4B.2 states the values and B-031's import template puts a data-validation
 * dropdown on the same column. If a master ever arrives this becomes a query,
 * and the filter is the only thing that changes.
 *
 * **B-026 · the values are the stored codes now, not title-case labels.** The
 * filter worked either way — the server matches case-insensitively, agreeing
 * with `utf8mb4_0900_ai_ci` — but a filter whose value is not the stored value
 * is a coincidence rather than a design, and S-33's `<Select>` binds to the same
 * vocabulary. One list, shared with the form.
 */
const SUPPORT_PLAN_OPTIONS = SUPPORT_PLANS.map((plan) => ({
  value: plan.value,
  label: plan.label,
}))

/**
 * S-32 Client Master — List. B-025.
 *
 * Blueprint line 946's nine columns, its four filters, its row-expand and its
 * bulk activate/deactivate. The create/edit form is B-026, the contact child
 * grid is B-027 and the Excel import wizard is B-030…B-038 — this screen links
 * to the last of those and owns none of them.
 *
 * <h2>`openTicketCount` is on every row, not only in the confirmation</h2>
 *
 * Deactivating is the consequential act here and its consequence is invisible
 * from the row: new tickets against the client stop being possible. An admin
 * should see the size of that before clicking rather than in a dialog after —
 * the call B-015 made with `userCount`, B-014 with its own `openTicketCount`
 * and B-020 with `ticketCount`.
 *
 * <h2>Deactivating warns; it does not refuse</h2>
 *
 * S-07's grid blocks a deactivation that would orphan tickets. This one does
 * not, and the difference is blueprint §4B.2's sentence rather than an
 * inconsistency: a client's open tickets stay open, stay assigned and stay
 * visible. Nothing is orphaned, so there is nothing to refuse until it is
 * fixed. What stops afterwards is *new* tickets, and enforcing that is B-029's
 * on the create path.
 */
export function ClientListPage() {
  const { filters, setFilter, resetFilters, activeCount } = useClientFilters()

  // Cursor pagination has no page numbers to jump to. The stack is what makes
  // "Previous" possible without an offset — CONVENTIONS.md §6.
  const [cursorStack, setCursorStack] = React.useState<string[]>([])
  const cursor = cursorStack[cursorStack.length - 1]

  const [selected, setSelected] = React.useState<ReadonlySet<number>>(new Set())
  const [expanded, setExpanded] = React.useState<number | null>(null)
  const [pendingDeactivation, setPendingDeactivation] =
    React.useState<readonly DeactivationCandidate[] | null>(null)

  const filterSignature = JSON.stringify(filters)
  const lastFilterSignature = React.useRef(filterSignature)
  React.useEffect(() => {
    if (lastFilterSignature.current !== filterSignature) {
      lastFilterSignature.current = filterSignature
      setCursorStack([])
      // A selection made under one filter must not survive into another. The
      // rows are no longer on screen, and acting on clients you can no longer
      // see is how a bulk deactivation surprises somebody.
      setSelected(new Set())
      setExpanded(null)
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
  const { data: projectsData } = useListProjects({ isActive: true, limit: 200 })
  const projects = React.useMemo(() => projectsData?.data ?? [], [projectsData])

  // Every active resource is a candidate account manager, so this reads the
  // directory unfiltered — filtering it by the grid's own filters would hide
  // the manager you are trying to filter by.
  const { data: managersData } = useListUsers({ isActive: true, limit: 200 })
  const managers = React.useMemo(() => managersData?.data ?? [], [managersData])

  // ── the list itself ───────────────────────────────────────────────────────
  const queryParams = toQueryParams(filters)
  const { data, isPending, isError, error, refetch, isFetching } = useListClients(
    { cursor, limit: PAGE_SIZE, ...queryParams },
    { query: { placeholderData: keepPreviousData } },
  )

  const clients = React.useMemo(() => data?.data ?? [], [data])
  const meta = data?.meta

  // ── bulk status ───────────────────────────────────────────────────────────
  const bulkStatus = useSetClientStatusBulk({
    mutation: {
      onSuccess: (response) => {
        const changed = response.data?.length ?? 0
        setSelected(new Set())
        setPendingDeactivation(null)
        void refetch()
        toast({ variant: 'success', title: `${changed} updated` })
      },
      onError: (mutationError) => {
        // The server refuses the whole batch if any id is unknown, so nothing
        // was written and the selection is worth keeping — the admin fixes the
        // stale row rather than rebuilding a fifty-row selection.
        setPendingDeactivation(null)
        toast({
          variant: 'danger',
          title: 'Could not update those clients',
          description:
            mutationError instanceof Error
              ? mutationError.message
              : 'Nothing was changed. Reload and try again.',
        })
      },
    },
  })

  function runBulkStatus(clientIds: number[], isActive: boolean) {
    bulkStatus.mutate({ data: { clientIds, isActive } })
  }

  /**
   * Warn before deactivating anything that still has open tickets.
   *
   * Rows outside the current page are in `selected` but not in `clients`, so
   * their counts are unknown here and they go through unwarned. Fetching them
   * to complete the picture would be a round trip to improve a warning that
   * blocks nothing.
   */
  function applyBulkStatus(isActive: boolean) {
    const ids = [...selected]
    if (isActive) {
      runBulkStatus(ids, true)
      return
    }

    const affected: DeactivationCandidate[] = clients
      .filter((c) => c.id != null && selected.has(c.id) && (c.openTicketCount ?? 0) > 0)
      .map((c) => ({
        id: c.id!,
        name: c.name ?? '',
        clientCode: c.clientCode ?? '',
        openTicketCount: c.openTicketCount ?? 0,
      }))

    if (affected.length === 0) {
      runBulkStatus(ids, false)
      return
    }
    setPendingDeactivation(affected)
  }

  // ── selection ─────────────────────────────────────────────────────────────
  const pageIds = React.useMemo(
    () => clients.map((c) => c.id).filter((id): id is number => id != null),
    [clients],
  )
  const allOnPageSelected = pageIds.length > 0 && pageIds.every((id) => selected.has(id))
  const someOnPageSelected = pageIds.some((id) => selected.has(id))

  function toggleRow(clientId: number) {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(clientId)) next.delete(clientId)
      else next.add(clientId)
      return next
    })
  }

  function togglePage() {
    setSelected((prev) => {
      const next = new Set(prev)
      // Selecting the page adds to a selection built across pages rather than
      // replacing it — the header box is "this page", not "everything".
      if (allOnPageSelected) pageIds.forEach((id) => next.delete(id))
      else pageIds.forEach((id) => next.add(id))
      return next
    })
  }

  // Two leading control columns — selection and the expand toggle — plus the
  // data columns. Wrong here and every empty state spans the wrong width.
  const columnCount = CLIENT_COLUMNS.length + 2
  const pageStart = clients.length === 0 ? 0 : cursorStack.length * PAGE_SIZE + 1
  const pageEnd = pageStart === 0 ? 0 : pageStart + clients.length - 1

  return (
    <div className="flex h-full flex-col gap-4 p-6">
      {/* ── header ─────────────────────────────────────────────────────── */}
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-h1 text-content">Clients</h1>

        <div className="relative ml-2 min-w-[16rem] max-w-sm flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-content-muted" />
          <Input
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') updateFilter('q', searchInput)
            }}
            placeholder="Search name, code or domain…"
            aria-label="Search clients"
            className="pl-9"
          />
        </div>

        <div className="ml-auto flex items-center gap-2">
          {/*
            S-34, the five-step import wizard — B-030 built the engine and
            B-031…B-038 build the steps. The route is not registered yet, so
            this is deliberately not a Link: a button that navigates nowhere is
            worse than one that says what is coming. **Replace with a Link the
            day the wizard route lands.**
          */}
          <Button variant="secondary" size="sm" disabled title="Excel import — S-34, coming with B-031">
            <Upload className="h-4 w-4" />
            Import from Excel
          </Button>
          <Button asChild variant="secondary" size="sm">
            <Link to="/masters">Back to masters</Link>
          </Button>
          {/* B-026 · S-33. The primary action on a master grid is adding a row,
              so it takes the primary variant and "Back to masters" gives it up. */}
          <Button asChild size="sm">
            <Link to="/masters/clients/new">
              <Plus className="h-4 w-4" />
              New client
            </Link>
          </Button>
        </div>
      </div>

      {/* ── filters ────────────────────────────────────────────────────── */}
      <div className="flex flex-wrap items-center gap-2">
        <FilterDropdown
          label="Status"
          options={STATUS_OPTIONS}
          value={
            filters.isActive == null
              ? null
              : (STATUS_OPTIONS.find((s) => s.value === String(filters.isActive)) ?? null)
          }
          onChange={(s) => updateFilter('isActive', s == null ? null : s.value === 'true')}
          getKey={(s) => s.value}
          getLabel={(s) => s.label}
          searchable={false}
        />
        <FilterDropdown
          label="Support plan"
          options={SUPPORT_PLAN_OPTIONS}
          value={SUPPORT_PLAN_OPTIONS.find((p) => p.value === filters.supportPlan) ?? null}
          onChange={(p) => updateFilter('supportPlan', p?.value ?? null)}
          getKey={(p) => p.value}
          getLabel={(p) => p.label}
          searchable={false}
        />
        <FilterDropdown
          label="Account manager"
          options={managers}
          value={managers.find((u) => u.id === filters.accountManagerId) ?? null}
          onChange={(u) => updateFilter('accountManagerId', u?.id ?? null)}
          getKey={(u) => String(u.id)}
          getLabel={(u) => u.displayName}
          getSearchable={(u) => [u.email ?? '']}
        />
        <FilterDropdown
          label="Project"
          options={projects}
          value={projects.find((p) => p.id === filters.projectId) ?? null}
          onChange={(p) => updateFilter('projectId', p?.id ?? null)}
          getKey={(p) => String(p.id)}
          getLabel={(p) => `${p.projectCode} — ${p.name}`}
          getSearchable={(p) => [p.projectCode, p.name]}
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

      <ClientBulkStatusBar
        selectedCount={selected.size}
        isPending={bulkStatus.isPending}
        onApply={applyBulkStatus}
        onClear={() => setSelected(new Set())}
      />

      {/* ── grid ───────────────────────────────────────────────────────── */}
      <TableContainer className="max-h-[calc(100vh-17rem)] flex-1">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="w-10">
                <input
                  type="checkbox"
                  aria-label="Select all clients on this page"
                  checked={allOnPageSelected}
                  ref={(node) => {
                    // Mixed selection is indeterminate, not unchecked: an
                    // unchecked box next to three ticked rows says the wrong
                    // thing about what clicking it will do.
                    if (node) node.indeterminate = someOnPageSelected && !allOnPageSelected
                  }}
                  onChange={togglePage}
                  disabled={pageIds.length === 0}
                  className="h-4 w-4 cursor-pointer accent-primary"
                />
              </TableHead>
              <TableHead className="w-10">
                <span className="sr-only">Expand contacts</span>
              </TableHead>
              {CLIENT_COLUMNS.map((column) => (
                <TableHead
                  key={column.key}
                  className={cn(column.widthClassName, column.align === 'right' && 'text-right')}
                >
                  {column.header}
                </TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {isPending ? (
              Array.from({ length: 8 }, (_, i) => (
                <TableRow key={i}>
                  {Array.from({ length: columnCount }, (_, c) => (
                    <TableCell key={c}>
                      <Skeleton className="h-4 w-full max-w-[10rem]" />
                    </TableCell>
                  ))}
                </TableRow>
              ))
            ) : isError ? (
              <TableRow>
                <TableCell colSpan={columnCount} className="p-0">
                  <EmptyState
                    title="Could not load clients"
                    description={
                      error instanceof Error ? error.message : 'Something went wrong. Try again.'
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
                <TableCell colSpan={columnCount} className="p-0">
                  <EmptyState
                    title="No clients match these filters"
                    description={
                      activeCount > 0 || filters.q
                        ? 'Try clearing a filter or widening the search.'
                        : 'Clients will show up here once they are added or imported.'
                    }
                    action={
                      activeCount > 0 ? (
                        <Button variant="secondary" size="sm" onClick={resetFilters}>
                          Reset filters
                        </Button>
                      ) : undefined
                    }
                  />
                </TableCell>
              </TableRow>
            ) : (
              clients.map((client) => (
                <ClientRow
                  key={client.id}
                  client={client}
                  columnCount={columnCount}
                  isSelected={client.id != null && selected.has(client.id)}
                  isExpanded={expanded === client.id}
                  isFetching={isFetching}
                  onToggleSelect={() => client.id != null && toggleRow(client.id)}
                  onToggleExpand={() =>
                    setExpanded((prev) => (prev === client.id ? null : (client.id ?? null)))
                  }
                />
              ))
            )}
          </TableBody>
        </Table>
      </TableContainer>

      {/* ── pagination ─────────────────────────────────────────────────── */}
      <div className="flex items-center justify-between text-caption text-content-muted">
        <p role="status">
          {clients.length === 0 ? '0 clients' : `Rows ${pageStart}–${pageEnd}`}
        </p>
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

      <DeactivationWarningDialog
        affected={pendingDeactivation}
        totalSelected={selected.size}
        isPending={bulkStatus.isPending}
        onConfirm={() => runBulkStatus([...selected], false)}
        onCancel={() => setPendingDeactivation(null)}
      />
    </div>
  )
}

function ClientRow({
  client,
  columnCount,
  isSelected,
  isExpanded,
  isFetching,
  onToggleSelect,
  onToggleExpand,
}: {
  client: Client
  columnCount: number
  isSelected: boolean
  isExpanded: boolean
  isFetching: boolean
  onToggleSelect: () => void
  onToggleExpand: () => void
}) {
  return (
    <>
      <TableRow aria-busy={isFetching || undefined} className={cn(!client.isActive && 'opacity-60')}>
        <TableCell>
          <input
            type="checkbox"
            aria-label={`Select ${client.name}`}
            checked={isSelected}
            onChange={onToggleSelect}
            className="h-4 w-4 cursor-pointer accent-primary"
          />
        </TableCell>
        <TableCell>
          <button
            type="button"
            onClick={onToggleExpand}
            aria-expanded={isExpanded}
            aria-label={`${isExpanded ? 'Hide' : 'Show'} contacts for ${client.name}`}
            className="flex h-6 w-6 items-center justify-center rounded-control text-content-muted hover:bg-subtle hover:text-content"
          >
            <ChevronDown
              className={cn('h-4 w-4 transition-transform', isExpanded && 'rotate-180')}
            />
          </button>
        </TableCell>
        {CLIENT_COLUMNS.map((column) => (
          <TableCell
            key={column.key}
            className={cn(column.widthClassName, column.align === 'right' && 'text-right')}
          >
            {column.render(client)}
          </TableCell>
        ))}
      </TableRow>

      {isExpanded && client.id != null ? (
        <TableRow>
          <TableCell colSpan={columnCount} className="bg-subtle/40 p-0">
            <ContactsPanel clientId={client.id} />
          </TableCell>
        </TableRow>
      ) : null}
    </>
  )
}

/**
 * The inline contacts S-32 asks each row to expand into.
 *
 * Fetched when the row opens rather than with the grid: the page reads 25
 * clients and almost nobody expands more than one, so eagerly loading every
 * client's contacts would be 25 requests to render nothing. The query is keyed
 * by client so a second open is a cache hit.
 *
 * Read-only — add, edit, remove and the primary flag are B-027's child grid.
 */
function ContactsPanel({ clientId }: { clientId: number }) {
  const { data, isPending, isError } = useListClientContacts(clientId)
  const contacts = data?.data ?? []

  if (isPending) {
    return (
      <div className="flex flex-col gap-2 px-6 py-3">
        <Skeleton className="h-4 w-64" />
        <Skeleton className="h-4 w-48" />
      </div>
    )
  }
  if (isError) {
    return <p className="px-6 py-3 text-sm text-danger-text">Contacts could not be loaded.</p>
  }
  if (contacts.length === 0) {
    return (
      <p className="px-6 py-3 text-sm text-content-muted">
        No contacts yet. A client needs a primary contact before it can be selected on a ticket.
      </p>
    )
  }

  return (
    <div className="px-6 py-3">
      <table className="w-full text-sm">
        <caption className="sr-only">Contacts</caption>
        <thead>
          <tr className="text-left text-caption text-content-muted">
            <th scope="col" className="py-1 font-normal">
              Name
            </th>
            <th scope="col" className="py-1 font-normal">
              Email
            </th>
            <th scope="col" className="py-1 font-normal">
              Phone
            </th>
            <th scope="col" className="py-1 font-normal">
              Flags
            </th>
          </tr>
        </thead>
        <tbody>
          {contacts.map((contact) => (
            <tr key={contact.id} className="border-t border-subtle">
              <td className="py-1.5 text-content">{contact.name}</td>
              <td className="py-1.5 text-content-muted">{contact.email}</td>
              <td className="py-1.5 text-content-muted">{contact.phone || '—'}</td>
              <td className="py-1.5">
                <span className="flex flex-wrap gap-1">
                  {contact.isPrimary ? <Chip variant="info">Primary</Chip> : null}
                  {contact.notificationOptIn ? <Chip variant="neutral">Notified</Chip> : null}
                  {contact.portalAccess ? <Chip variant="neutral">Portal</Chip> : null}
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
