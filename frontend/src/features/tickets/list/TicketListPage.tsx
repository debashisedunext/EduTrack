import * as React from 'react'
import { Link } from 'react-router-dom'
import { keepPreviousData } from '@tanstack/react-query'
import { ChevronLeft, ChevronRight, Plus, RotateCcw, Search } from 'lucide-react'

import { useListTickets } from '@/api/generated/tickets/tickets'
import { useListProjects } from '@/api/generated/projects/projects'
import { useListClients } from '@/api/generated/clients/clients'
import { useListUsers } from '@/api/generated/users/users'
import {
  useListModules,
  useListTaskTypes,
  useListPriorities,
  useListWorkflowTemplates,
} from '@/api/generated/masters/masters'
import { useGetMe } from '@/api/generated/auth/auth'
import type { Level } from '@/api/generated/model/level'
import type { StatusCode } from '@/api/generated/model/statusCode'
import type { BulkResultResponseData } from '@/api/generated/model/bulkResultResponseData'
import { ApiError, newIdempotencyKey } from '@/api/http'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { EmptyState } from '@/components/ui/empty-state'
import { FilterDropdown } from '@/components/ui/filter-dropdown'
import {
  TableContainer,
  Table,
  TableHeader,
  TableBody,
  TableRow,
  TableHead,
  TableCell,
} from '@/components/ui/table'
import { cn } from '@/lib/utils'

import { DateRangeFilter } from './DateRangeFilter'
import { ColumnChooserMenu } from './ColumnChooserMenu'
import { DensityToggle } from './DensityToggle'
import { SavedViewsMenu } from './SavedViewsMenu'
import { useTicketListFilters, type TicketListFilters } from './useTicketListFilters'
import { useListPreferences } from './useListPreferences'
import { COLUMNS, STATUS_LABEL, rowCueClassName, type ColumnRenderContext } from './columns'
import {
  TicketBulkActionBar,
  BulkReassignDialog,
  BulkLevelDialog,
  BulkCloseDialog,
  BulkResultDialog,
  type BulkAction,
} from './bulk/TicketBulkActionBar'
import {
  alreadyClosedCount,
  canBulkAct,
  closableIds,
  selectionAfter,
} from './bulk/bulkActions'
import { useBulkChangeLevel, useBulkClose, useBulkReassign } from './bulk/useBulkTicketActions'

const PAGE_SIZE = 25
const SEARCH_DEBOUNCE_MS = 300

const LEVEL_LABEL: Record<Level, string> = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High', CRITICAL: 'Critical' }

const STATUS_OPTIONS = (Object.keys(STATUS_LABEL) as StatusCode[]).map((value) => ({
  value,
  label: STATUS_LABEL[value],
}))

const DENSITY_ROW_CLASS = { comfortable: 'py-3', compact: 'py-1.5' } as const

/**
 * S-17 Ticket List (All Tickets) — C-014, saved views C-015, bulk select C-017.
 *
 * The compact ribbon column the wireframe draws is deliberately not here: see
 * the folder README.
 */
export function TicketListPage() {
  const { filters, setFilter, applyFilters, resetFilters, activeCount } = useTicketListFilters()
  const { density, setDensity, visibleColumns, toggleColumn } = useListPreferences()
  const { data: meData } = useGetMe()
  const myUserId = meData?.data.id ?? null
  const showBulk = canBulkAct(meData?.data.role)

  // Cursor pagination has no page numbers to jump to — CONVENTIONS.md is
  // explicit that offset paging over a table under write traffic skips and
  // repeats rows. The stack is what makes "Previous" possible without one.
  const [cursorStack, setCursorStack] = React.useState<string[]>([])
  const cursor = cursorStack[cursorStack.length - 1]

  const filterSignature = JSON.stringify(filters)
  const lastFilterSignature = React.useRef(filterSignature)
  React.useEffect(() => {
    if (lastFilterSignature.current !== filterSignature) {
      lastFilterSignature.current = filterSignature
      setCursorStack([])
    }
  }, [filterSignature])

  function updateFilter<K extends keyof typeof filters>(key: K, value: (typeof filters)[K]) {
    setCursorStack([])
    setFilter(key, value)
  }

  // A saved view replaces the whole filter row in one URL update, same
  // cursor-reset rule as every other filter-changing action on this screen.
  function applySavedView(recipe: Partial<TicketListFilters>) {
    setCursorStack([])
    applyFilters(recipe)
  }

  /**
   * Two keys, one URL update — and it has to be, or one of them is lost.
   *
   * `updateFilter` is safe for the single-key filters that make up the rest of
   * this row, but the date range sets `dueFrom` and `dueTo` together. Calling
   * it twice meant the second write read the URL as it was *before* the first,
   * and overwrote it: picking a From and a To saved only the To, and the From
   * silently vanished from the chip. `useTicketListFilters` documents exactly
   * this on `applyFilters` — "`setSearchParams` updaters don't compose across
   * separate calls in the same tick" — which is why that function exists.
   *
   * `replace: false` merges rather than clearing the row. The default replaces
   * everything, which is right for a saved view and would be wrong here: it
   * would drop the project and level somebody had already chosen the moment
   * they touched the dates.
   */
  function updateDateRange(range: { from: string | null; to: string | null }) {
    setCursorStack([])
    applyFilters({ dueFrom: range.from, dueTo: range.to }, { replace: false })
  }

  // ── search box — debounced so every keystroke does not refetch ───────────
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

  // Progressively narrowed the same way the create form does — a project
  // filter is exactly the signal that says which clients and members matter.
  //
  // **B-029 · `isActive` is deliberately not sent here, and removing it was a
  // defect fix.** Blueprint line 523: deactivating a client blocks new tickets
  // but "never hides the historical tickets". This dropdown filters a list of
  // tickets that already exist — so sending `isActive: true` meant a client's
  // name vanished from it the moment they were deactivated, and forty
  // historical tickets became unreachable through the one control that reaches
  // them. Nothing failed loudly; the filter just quietly stopped offering
  // somebody.
  //
  // The create form is the opposite case and still sends it: that list is a
  // *picker* for something new, which is exactly what deactivation blocks. Same
  // parameter, opposite answers, and the question that separates them is
  // "historical or new" — see `features/clients/ticketEligibility.ts`. Identical
  // in shape to B-027's `includeInactive` split, which this same page's detail
  // view got wrong in the same direction.
  //
  // Stream B's edit in Stream C's file, one argument wide — the precedent
  // B-027 and B-028 both set. **Flagged for Stream C.**
  const { data: clientsData } = useListClients({
    projectId: filters.projectId ?? undefined,
    limit: 200,
  })
  const clients = React.useMemo(() => clientsData?.data ?? [], [clientsData])

  const { data: membersData } = useListUsers({
    projectId: filters.projectId ?? undefined,
    isActive: true,
    limit: 200,
  })
  const members = React.useMemo(() => membersData?.data ?? [], [membersData])

  const { data: taskTypesData } = useListTaskTypes()
  const taskTypes = React.useMemo(
    () => (taskTypesData?.data ?? []).filter((t) => t.isActive !== false),
    [taskTypesData],
  )
  const taskTypeNames = React.useMemo(
    () => new Map(taskTypes.filter((t) => t.id != null).map((t) => [t.id as number, t.name ?? `#${t.id}`])),
    [taskTypes],
  )
  /*
    C-070 · §7.5's module master. **Unfiltered — retired rows included, in both
    the filter and the column.** The create form filters them out because
    nothing should be raised against a retired module; a *list* is the opposite
    case. "Every open Transport ticket" is a fair question about a module being
    wound down, and it is the one question you cannot ask if the filter hides it
    — while the column has to render the name whatever the master now says about
    the row. Same reasoning D-060 gives for the endpoint returning them at all.
  */
  const { data: modulesData } = useListModules()
  const modules = React.useMemo(() => modulesData?.data ?? [], [modulesData])
  const moduleNames = React.useMemo(() => new Map(modules.map((m) => [m.id, m.name])), [modules])

  const renderContext: ColumnRenderContext = { taskTypeNames, moduleNames }

  const { data: prioritiesData } = useListPriorities()
  const levelOptions = React.useMemo(
    () =>
      (prioritiesData?.data ?? [])
        .map((p) => p.level)
        .filter((l): l is Level => l != null)
        .map((value) => ({ value, label: LEVEL_LABEL[value] })),
    [prioritiesData],
  )

  // Stages come from the workflow templates master, not a dedicated list
  // endpoint — deduped across every template an unfiltered call returns and
  // sorted by sequence. Good enough for *filtering* the grid, where picking a
  // stage a ticket happens not to reach just returns zero rows. Resolving
  // each row's own template for a per-ticket ribbon column is a different,
  // heavier problem — see the README for why that stays out of this task.
  const { data: workflowTemplatesData } = useListWorkflowTemplates()
  const stageOptions = React.useMemo(() => {
    const byCode = new Map<string, { value: string; label: string; sequence: number }>()
    for (const template of workflowTemplatesData?.data ?? []) {
      for (const stage of template.stages ?? []) {
        if (stage.stageCode === 'CLOSED' || stage.isDeprecated) continue
        if (!stage.stageCode || byCode.has(stage.stageCode)) continue
        byCode.set(stage.stageCode, {
          value: stage.stageCode,
          label: stage.displayName ?? stage.stageCode,
          sequence: stage.sequence ?? 0,
        })
      }
    }
    return [...byCode.values()].sort((a, b) => a.sequence - b.sequence)
  }, [workflowTemplatesData])

  // ── the list itself ────────────────────────────────────────────────────────
  const { data, isPending, isError, error, refetch, isFetching } = useListTickets(
    {
      cursor,
      limit: PAGE_SIZE,
      q: filters.q || undefined,
      projectId: filters.projectId ?? undefined,
      clientId: filters.clientId ?? undefined,
      taskTypeId: filters.taskTypeId ?? undefined,
      moduleId: filters.moduleId ?? undefined,
      level: filters.level ?? undefined,
      status: filters.status ?? undefined,
      stage: filters.stage ?? undefined,
      assigneeId: filters.assigneeId ?? undefined,
      dueFrom: filters.dueFrom ?? undefined,
      dueTo: filters.dueTo ?? undefined,
      isDelayed: filters.isDelayed ?? undefined,
      reopenedOnly: filters.reopenedOnly ?? undefined,
      unassigned: filters.unassigned ?? undefined,
      excludeClosed: filters.excludeClosed ?? undefined,
      closedFrom: filters.closedFrom ?? undefined,
      closedTo: filters.closedTo ?? undefined,
      // A-060 · the window every dashboard deep-link names. Read from the URL
      // like the rest, but with no control in the filter row: nothing on this
      // screen sets it, and arriving with it already applied is the whole
      // point of a drill-down.
      reportedFrom: filters.reportedFrom ?? undefined,
      reportedTo: filters.reportedTo ?? undefined,
    },
    { query: { placeholderData: keepPreviousData } },
  )

  const tickets = data?.data ?? []
  const meta = data?.meta
  const orderedVisibleColumns = COLUMNS.filter((c) => c.alwaysVisible || visibleColumns.includes(c.key))
  const rowPadding = DENSITY_ROW_CLASS[density]

  const pageStart = tickets.length === 0 ? 0 : cursorStack.length * PAGE_SIZE + 1
  const pageEnd = pageStart === 0 ? 0 : pageStart + tickets.length - 1

  // ── C-017 · bulk selection ────────────────────────────────────────────────
  //
  // Keyed by `ticketId`, the string the API and every route already use, so a
  // selection is the same thing the request body carries with no mapping step
  // in between.
  const [selected, setSelected] = React.useState<ReadonlySet<string>>(new Set())
  const [openAction, setOpenAction] = React.useState<BulkAction | null>(null)
  const [result, setResult] = React.useState<{ data: BulkResultResponseData; verb: string } | null>(
    null,
  )
  const [actionError, setActionError] = React.useState<string | null>(null)

  const reassign = useBulkReassign()
  const changeLevel = useBulkChangeLevel()
  const close = useBulkClose()
  const isActing = reassign.isPending || changeLevel.isPending || close.isPending

  const pageTicketIds = tickets.map((t) => t.ticketId)
  const allOnPageSelected =
    pageTicketIds.length > 0 && pageTicketIds.every((id) => selected.has(id))
  const someOnPageSelected = pageTicketIds.some((id) => selected.has(id))
  const closable = closableIds(selected, tickets)
  const closedInSelection = alreadyClosedCount(selected, tickets)

  // The selection column is drawn only for the roles that can act, so it must
  // be counted in `colSpan` the same way — an empty-state row that spans one
  // column too few leaves a stray cell at the end of the table for a PM and
  // not for a Developer.
  const gridColumnCount = orderedVisibleColumns.length + (showBulk ? 1 : 0)

  function toggleRow(ticketId: string) {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(ticketId)) next.delete(ticketId)
      else next.add(ticketId)
      return next
    })
  }

  function togglePage() {
    setSelected((prev) => {
      const next = new Set(prev)
      // The header box is "this page", not "everything" — selecting it adds to
      // a selection that survives paging rather than replacing it. Same rule as
      // S-07's grid, and the reason `closableIds` treats ids it cannot see as
      // closable rather than dropping them.
      if (allOnPageSelected) pageTicketIds.forEach((id) => next.delete(id))
      else pageTicketIds.forEach((id) => next.add(id))
      return next
    })
  }

  /**
   * The one place a bulk result is applied, whichever action produced it.
   *
   * Succeeded ids leave the selection; refused ids stay ticked, because the
   * user has something left to do about those and clearing them would hide the
   * failure the moment the dialog closed.
   */
  function applyResult(data: BulkResultResponseData, verb: string) {
    setSelected((prev) => selectionAfter(prev, data))
    setOpenAction(null)
    setActionError(null)
    setResult({ data, verb })
  }

  function reportFailure(err: unknown) {
    setActionError(
      err instanceof ApiError
        ? err.problem.detail ?? err.problem.title ?? 'The request was refused.'
        : err instanceof Error
          ? err.message
          : 'Something went wrong. Try again.',
    )
  }

  function closeDialog() {
    setOpenAction(null)
    setActionError(null)
  }

  return (
    <div className="flex h-full flex-col gap-4 p-6">
      {/* ── header ─────────────────────────────────────────────────────── */}
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-h1 text-content">Tickets</h1>
        <Button asChild size="sm">
          <Link to="/tickets/new">
            <Plus className="h-4 w-4" />
            New ticket
          </Link>
        </Button>

        <div className="relative ml-2 min-w-[16rem] flex-1 max-w-sm">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-content-muted" />
          <Input
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') updateFilter('q', searchInput)
            }}
            placeholder="Search title, description or ID…"
            aria-label="Search tickets"
            className="pl-9"
          />
        </div>

        <SavedViewsMenu filters={filters} onApply={applySavedView} myUserId={myUserId} />

        <div className="ml-auto flex items-center gap-2">
          <DensityToggle density={density} onChange={setDensity} />
          <ColumnChooserMenu visibleColumns={visibleColumns} onToggle={toggleColumn} />
        </div>
      </div>

      {/* ── filters ────────────────────────────────────────────────────── */}
      <div className="flex flex-wrap items-center gap-2">
        <FilterDropdown
          label="Project"
          options={projects}
          value={projects.find((p) => p.id === filters.projectId) ?? null}
          onChange={(p) => updateFilter('projectId', p?.id ?? null)}
          getKey={(p) => String(p.id)}
          getLabel={(p) => `${p.projectCode} — ${p.name}`}
          getSearchable={(p) => [p.projectCode, p.name]}
        />
        <FilterDropdown
          label="Client"
          options={clients}
          value={clients.find((c) => c.id === filters.clientId) ?? null}
          onChange={(c) => updateFilter('clientId', c?.id ?? null)}
          getKey={(c) => String(c.id)}
          getLabel={(c) => c.name ?? c.clientCode ?? `#${c.id}`}
          getSearchable={(c) => [c.clientCode ?? '', c.name ?? '']}
        />
        <FilterDropdown
          label="Type"
          options={taskTypes}
          value={taskTypes.find((t) => t.id === filters.taskTypeId) ?? null}
          onChange={(t) => updateFilter('taskTypeId', t?.id ?? null)}
          getKey={(t) => String(t.id)}
          getLabel={(t) => t.name ?? `#${t.id}`}
        />
        <FilterDropdown
          label="Module"
          options={modules}
          value={modules.find((m) => m.id === filters.moduleId) ?? null}
          onChange={(m) => updateFilter('moduleId', m?.id ?? null)}
          getKey={(m) => String(m.id)}
          getLabel={(m) => (m.isActive === false ? `${m.name} (retired)` : m.name)}
        />
        <FilterDropdown
          label="Level"
          options={levelOptions}
          value={levelOptions.find((l) => l.value === filters.level) ?? null}
          onChange={(l) => updateFilter('level', l?.value ?? null)}
          getKey={(l) => l.value}
          getLabel={(l) => l.label}
          searchable={false}
        />
        <FilterDropdown
          label="Stage"
          options={stageOptions}
          value={stageOptions.find((s) => s.value === filters.stage) ?? null}
          onChange={(s) => updateFilter('stage', s?.value ?? null)}
          getKey={(s) => s.value}
          getLabel={(s) => s.label}
          searchable={false}
        />
        <FilterDropdown
          label="Status"
          options={STATUS_OPTIONS}
          value={STATUS_OPTIONS.find((s) => s.value === filters.status) ?? null}
          onChange={(s) => updateFilter('status', s?.value ?? null)}
          getKey={(s) => s.value}
          getLabel={(s) => s.label}
          searchable={false}
        />
        <FilterDropdown
          label="Assignee"
          options={members}
          value={members.find((u) => u.id === filters.assigneeId) ?? null}
          onChange={(u) => updateFilter('assigneeId', u?.id ?? null)}
          getKey={(u) => String(u.id)}
          getLabel={(u) => u.displayName}
          getSearchable={(u) => [u.email ?? '']}
        />
        <DateRangeFilter
          label="Dates"
          value={{ from: filters.dueFrom, to: filters.dueTo }}
          onChange={updateDateRange}
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

      {/* ── C-017 · bulk actions, PM and Admin only ────────────────────── */}
      {showBulk && (
        <TicketBulkActionBar
          selectedCount={selected.size}
          closedInSelection={closedInSelection}
          isPending={isActing}
          onOpen={(action) => {
            setActionError(null)
            setOpenAction(action)
          }}
          onClear={() => setSelected(new Set())}
        />
      )}

      {/* ── grid ───────────────────────────────────────────────────────── */}
      <TableContainer className="max-h-[calc(100vh-15rem)] flex-1">
        <Table>
          <TableHeader>
            <TableRow>
              {showBulk && (
                <TableHead className="w-10">
                  <input
                    type="checkbox"
                    aria-label="Select all tickets on this page"
                    checked={allOnPageSelected}
                    ref={(node) => {
                      // Mixed selection is indeterminate, not unchecked: an
                      // unchecked box beside three ticked rows says the wrong
                      // thing about what clicking it will do.
                      if (node) node.indeterminate = someOnPageSelected && !allOnPageSelected
                    }}
                    onChange={togglePage}
                    disabled={pageTicketIds.length === 0}
                    className="h-4 w-4 cursor-pointer accent-primary"
                  />
                </TableHead>
              )}
              {orderedVisibleColumns.map((column) => (
                <TableHead
                  key={column.key}
                  className={cn(column.align === 'right' && 'text-right', column.widthClassName)}
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
                  {Array.from({ length: gridColumnCount }, (_, c) => (
                    <TableCell key={c} className={rowPadding}>
                      <Skeleton className="h-4 w-full max-w-[10rem]" />
                    </TableCell>
                  ))}
                </TableRow>
              ))
            ) : isError ? (
              <TableRow>
                <TableCell colSpan={gridColumnCount} className="p-0">
                  <EmptyState
                    title="Could not load tickets"
                    description={error instanceof Error ? error.message : 'Something went wrong. Try again.'}
                    action={
                      <Button size="sm" onClick={() => refetch()}>
                        Retry
                      </Button>
                    }
                  />
                </TableCell>
              </TableRow>
            ) : tickets.length === 0 ? (
              <TableRow>
                <TableCell colSpan={gridColumnCount} className="p-0">
                  <EmptyState
                    title="No tickets match these filters"
                    description={
                      activeCount > 0 || filters.q
                        ? 'Try widening the date range or clearing a filter.'
                        : 'Tickets you can see will show up here.'
                    }
                    action={
                      activeCount > 0 ? (
                        <Button variant="secondary" size="sm" onClick={resetFilters}>
                          Reset filters
                        </Button>
                      ) : (
                        <Button asChild size="sm">
                          <Link to="/tickets/new">New ticket</Link>
                        </Button>
                      )
                    }
                  />
                </TableCell>
              </TableRow>
            ) : (
              tickets.map((ticket) => (
                <TableRow
                  key={ticket.ticketId}
                  aria-busy={isFetching || undefined}
                  className={rowCueClassName(ticket)}
                >
                  {showBulk && (
                    <TableCell className={rowPadding}>
                      <input
                        type="checkbox"
                        // The ID, not the title: two tickets can share a title
                        // and the accessible name has to identify the row.
                        aria-label={`Select ${ticket.ticketId}`}
                        checked={selected.has(ticket.ticketId)}
                        onChange={() => toggleRow(ticket.ticketId)}
                        className="h-4 w-4 cursor-pointer accent-primary"
                      />
                    </TableCell>
                  )}
                  {orderedVisibleColumns.map((column) => (
                    <TableCell
                      key={column.key}
                      className={cn(rowPadding, column.align === 'right' && 'text-right', column.widthClassName)}
                    >
                      {column.render(ticket, renderContext)}
                    </TableCell>
                  ))}
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </TableContainer>

      {/* ── pagination ─────────────────────────────────────────────────── */}
      <div className="flex items-center justify-between text-caption text-content-muted">
        <p role="status">
          {tickets.length === 0
            ? '0 tickets'
            : `Rows ${pageStart}–${pageEnd}${meta?.totalCount != null ? ` of ${meta.totalCount}` : ''}`}
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

      {/* ── C-017 · the three dialogs and the refusal list ─────────────── */}
      {openAction === 'reassign' && (
        <BulkReassignDialog
          selectedCount={selected.size}
          members={members}
          isPending={reassign.isPending}
          error={actionError}
          onCancel={closeDialog}
          onConfirm={({ toUserId, reason }) => {
            reassign.mutate(
              {
                data: { ticketIds: [...selected], toUserId, reason },
                idempotencyKey: newIdempotencyKey(),
              },
              {
                onSuccess: (response) => applyResult(response.data, 'reassigned'),
                onError: reportFailure,
              },
            )
          }}
        />
      )}

      {openAction === 'level' && (
        <BulkLevelDialog
          selectedCount={selected.size}
          levels={levelOptions.map((l) => l.value)}
          isPending={changeLevel.isPending}
          error={actionError}
          onCancel={closeDialog}
          onConfirm={({ level, reason }) => {
            changeLevel.mutate(
              {
                data: { ticketIds: [...selected], level, reason },
                idempotencyKey: newIdempotencyKey(),
              },
              {
                onSuccess: (response) => applyResult(response.data, 'updated'),
                onError: reportFailure,
              },
            )
          }}
        />
      )}

      {openAction === 'close' && (
        <BulkCloseDialog
          closableCount={closable.length}
          alreadyClosed={closedInSelection}
          isPending={close.isPending}
          error={actionError}
          onCancel={closeDialog}
          onConfirm={({ resolutionSummary, rootCauseCategory }) => {
            close.mutate(
              {
                // `closable`, not the whole selection: the rows already closed
                // were named in the dialog and are deliberately not sent.
                data: { ticketIds: closable, resolutionSummary, rootCauseCategory },
                idempotencyKey: newIdempotencyKey(),
              },
              {
                onSuccess: (response) => applyResult(response.data, 'closed'),
                onError: reportFailure,
              },
            )
          }}
        />
      )}

      <BulkResultDialog
        result={result?.data ?? null}
        verb={result?.verb ?? ''}
        onClose={() => setResult(null)}
      />
    </div>
  )
}
