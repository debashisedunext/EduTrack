import * as React from 'react'
import { Link } from 'react-router-dom'
import { keepPreviousData } from '@tanstack/react-query'
import { ChevronLeft, ChevronRight, Plus, RotateCcw, Search } from 'lucide-react'

import { useListTickets } from '@/api/generated/tickets/tickets'
import { useListProjects } from '@/api/generated/projects/projects'
import { useListClients } from '@/api/generated/clients/clients'
import { useListUsers } from '@/api/generated/users/users'
import { useListTaskTypes, useListPriorities, useListWorkflowTemplates } from '@/api/generated/masters/masters'
import { useGetMe } from '@/api/generated/auth/auth'
import type { Level } from '@/api/generated/model/level'
import type { StatusCode } from '@/api/generated/model/statusCode'

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

const PAGE_SIZE = 25
const SEARCH_DEBOUNCE_MS = 300

const LEVEL_LABEL: Record<Level, string> = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High', CRITICAL: 'Critical' }

const STATUS_OPTIONS = (Object.keys(STATUS_LABEL) as StatusCode[]).map((value) => ({
  value,
  label: STATUS_LABEL[value],
}))

const DENSITY_ROW_CLASS = { comfortable: 'py-3', compact: 'py-1.5' } as const

/**
 * S-17 Ticket List (All Tickets) — C-014, saved views C-015.
 *
 * The compact ribbon column the wireframe draws is deliberately not here: see
 * the folder README. Bulk select (C-017) is a separate backlog item and
 * stays out of this screen too.
 */
export function TicketListPage() {
  const { filters, setFilter, applyFilters, resetFilters, activeCount } = useTicketListFilters()
  const { density, setDensity, visibleColumns, toggleColumn } = useListPreferences()
  const { data: meData } = useGetMe()
  const myUserId = meData?.data.id ?? null

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
  const { data: clientsData } = useListClients({
    projectId: filters.projectId ?? undefined,
    isActive: true,
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
  const renderContext: ColumnRenderContext = { taskTypeNames }

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
    },
    { query: { placeholderData: keepPreviousData } },
  )

  const tickets = data?.data ?? []
  const meta = data?.meta
  const orderedVisibleColumns = COLUMNS.filter((c) => c.alwaysVisible || visibleColumns.includes(c.key))
  const rowPadding = DENSITY_ROW_CLASS[density]

  const pageStart = tickets.length === 0 ? 0 : cursorStack.length * PAGE_SIZE + 1
  const pageEnd = pageStart === 0 ? 0 : pageStart + tickets.length - 1

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
          onChange={(range) => {
            setCursorStack([])
            setFilter('dueFrom', range.from)
            setFilter('dueTo', range.to)
          }}
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
                  {orderedVisibleColumns.map((column) => (
                    <TableCell key={column.key} className={rowPadding}>
                      <Skeleton className="h-4 w-full max-w-[10rem]" />
                    </TableCell>
                  ))}
                </TableRow>
              ))
            ) : isError ? (
              <TableRow>
                <TableCell colSpan={orderedVisibleColumns.length} className="p-0">
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
                <TableCell colSpan={orderedVisibleColumns.length} className="p-0">
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
    </div>
  )
}
