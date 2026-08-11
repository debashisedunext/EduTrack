import * as React from 'react'
import { keepPreviousData } from '@tanstack/react-query'
import { ChevronLeft, ChevronRight, Download, RotateCcw, Search } from 'lucide-react'

import { useListUsers, useSetUserStatusBulk } from '@/api/generated/users/users'
import { useListProjects } from '@/api/generated/projects/projects'
import type { RoleCode } from '@/api/generated/model/roleCode'
import type { BulkUserStatusResponseData } from '@/api/generated/model/bulkUserStatusResponseData'
import { BASE } from '@/api/http'

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
import { toast } from '@/components/ui/use-toast'
import { cn } from '@/lib/utils'

import { RESOURCE_COLUMNS, ROLE_LABEL } from './columns'
import { BulkStatusBar, BulkStatusResultDialog } from './BulkStatusBar'
import { useResourceFilters, toQueryParams } from './useResourceFilters'

const PAGE_SIZE = 25
const SEARCH_DEBOUNCE_MS = 300

const ROLE_OPTIONS = (Object.keys(ROLE_LABEL) as RoleCode[]).map((value) => ({
  value,
  label: ROLE_LABEL[value],
}))

const STATUS_OPTIONS = [
  { value: 'true', label: 'Active' },
  { value: 'false', label: 'Inactive' },
]

/**
 * S-07 Resource Master — List (B-010).
 *
 * The read half of the Resource Master. Create and edit are B-011,
 * reporting-manager cycle detection B-012, and the bulk reassignment wizard
 * B-014 — which is why deactivating somebody who holds open tickets stops here
 * with a named list rather than offering to fix it.
 */
export function ResourceListPage() {
  const { filters, setFilter, resetFilters, activeCount } = useResourceFilters()

  // Cursor pagination has no page numbers to jump to. The stack is what makes
  // "Previous" possible without an offset — CONVENTIONS.md §6.
  const [cursorStack, setCursorStack] = React.useState<string[]>([])
  const cursor = cursorStack[cursorStack.length - 1]

  const [selected, setSelected] = React.useState<ReadonlySet<number>>(new Set())
  const [bulkResult, setBulkResult] = React.useState<BulkUserStatusResponseData | null>(null)
  const [lastBulkWasActivate, setLastBulkWasActivate] = React.useState(false)

  const filterSignature = JSON.stringify(filters)
  const lastFilterSignature = React.useRef(filterSignature)
  React.useEffect(() => {
    if (lastFilterSignature.current !== filterSignature) {
      lastFilterSignature.current = filterSignature
      setCursorStack([])
      // A selection made under one filter must not survive into another. The
      // rows are no longer on screen, and acting on people you can no longer
      // see is how a bulk deactivation surprises somebody.
      setSelected(new Set())
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

  // Every active resource is a candidate manager, so the manager filter reads
  // the same endpoint the grid does — unfiltered, because filtering managers by
  // the grid's own filters would hide the manager you are trying to filter by.
  const { data: managersData } = useListUsers({ isActive: true, limit: 200 })
  const managers = React.useMemo(() => managersData?.data ?? [], [managersData])

  // ── the list itself ───────────────────────────────────────────────────────
  const queryParams = toQueryParams(filters)
  const { data, isPending, isError, error, refetch, isFetching } = useListUsers(
    { cursor, limit: PAGE_SIZE, ...queryParams },
    { query: { placeholderData: keepPreviousData } },
  )

  const resources = data?.data ?? []
  const meta = data?.meta

  // ── bulk status ───────────────────────────────────────────────────────────
  const bulkStatus = useSetUserStatusBulk({
    mutation: {
      onSuccess: (response) => {
        const result = response.data
        setSelected(new Set())
        void refetch()

        // A clean run is a toast; anything needing attention is a dialog with
        // the names in it. A list somebody has to act on must not vanish after
        // five seconds.
        if (result.blocked === 0 && result.notFound === 0) {
          toast({
            variant: 'success',
            title: `${result.changed} updated`,
            description:
              result.unchanged > 0 ? `${result.unchanged} were already in that state.` : undefined,
          })
          return
        }
        setBulkResult(result)
      },
      onError: (mutationError) => {
        toast({
          variant: 'danger',
          title: 'Could not update those resources',
          description: mutationError instanceof Error ? mutationError.message : 'Try again.',
        })
      },
    },
  })

  function applyBulkStatus(isActive: boolean) {
    setLastBulkWasActivate(isActive)
    bulkStatus.mutate({ data: { userIds: [...selected], isActive } })
  }

  // ── selection ─────────────────────────────────────────────────────────────
  const pageIds = resources.map((r) => r.id)
  const allOnPageSelected = pageIds.length > 0 && pageIds.every((id) => selected.has(id))
  const someOnPageSelected = pageIds.some((id) => selected.has(id))

  function toggleRow(id: number) {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
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

  // ── export ────────────────────────────────────────────────────────────────
  // A plain link, not a fetch: the browser's own download handling deals with
  // Content-Disposition, progress and the file dialog, and a blob round trip
  // would hold the whole export in memory to reproduce that badly.
  const exportHref = React.useMemo(() => {
    const params = new URLSearchParams({ format: 'xlsx' })
    for (const [key, value] of Object.entries(queryParams)) {
      if (value != null && value !== '') params.set(key, String(value))
    }
    return `${BASE}/users/export?${params.toString()}`
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterSignature])

  const columnCount = RESOURCE_COLUMNS.length + 1
  const pageStart = resources.length === 0 ? 0 : cursorStack.length * PAGE_SIZE + 1
  const pageEnd = pageStart === 0 ? 0 : pageStart + resources.length - 1

  return (
    <div className="flex h-full flex-col gap-4 p-6">
      {/* ── header ─────────────────────────────────────────────────────── */}
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-h1 text-content">Resources</h1>

        <div className="relative ml-2 min-w-[16rem] flex-1 max-w-sm">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-content-muted" />
          <Input
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') updateFilter('q', searchInput)
            }}
            placeholder="Search name, username, email or emp code…"
            aria-label="Search resources"
            className="pl-9"
          />
        </div>

        <div className="ml-auto flex items-center gap-2">
          <Button asChild variant="secondary" size="sm">
            {/* `download` and a real href, so the filters in the URL are exactly
                the ones the file was built from. */}
            <a href={exportHref} download>
              <Download className="h-4 w-4" />
              Export
            </a>
          </Button>
        </div>
      </div>

      {/* ── filters ────────────────────────────────────────────────────── */}
      <div className="flex flex-wrap items-center gap-2">
        <FilterDropdown
          label="Role"
          options={ROLE_OPTIONS}
          value={ROLE_OPTIONS.find((r) => r.value === filters.role) ?? null}
          onChange={(r) => updateFilter('role', r?.value ?? null)}
          getKey={(r) => r.value}
          getLabel={(r) => r.label}
          searchable={false}
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
        <FilterDropdown
          label="Manager"
          options={managers}
          value={managers.find((u) => u.id === filters.managerId) ?? null}
          onChange={(u) => updateFilter('managerId', u?.id ?? null)}
          getKey={(u) => String(u.id)}
          getLabel={(u) => u.displayName}
          getSearchable={(u) => [u.email ?? '']}
        />
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

      <BulkStatusBar
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
                  aria-label="Select all resources on this page"
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
              {RESOURCE_COLUMNS.map((column) => (
                <TableHead key={column.key} className={column.widthClassName}>
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
                    title="Could not load resources"
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
            ) : resources.length === 0 ? (
              <TableRow>
                <TableCell colSpan={columnCount} className="p-0">
                  <EmptyState
                    title="No resources match these filters"
                    description={
                      activeCount > 0 || filters.q
                        ? 'Try clearing a filter or widening the search.'
                        : 'Resources will show up here once they are added.'
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
              resources.map((resource) => (
                <TableRow
                  key={resource.id}
                  aria-busy={isFetching || undefined}
                  className={cn(!resource.isActive && 'opacity-60')}
                >
                  <TableCell>
                    <input
                      type="checkbox"
                      aria-label={`Select ${resource.displayName}`}
                      checked={selected.has(resource.id)}
                      onChange={() => toggleRow(resource.id)}
                      className="h-4 w-4 cursor-pointer accent-primary"
                    />
                  </TableCell>
                  {RESOURCE_COLUMNS.map((column) => (
                    <TableCell key={column.key} className={column.widthClassName}>
                      {column.render(resource)}
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
          {resources.length === 0
            ? '0 resources'
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

      <BulkStatusResultDialog
        result={bulkResult}
        isActivating={lastBulkWasActivate}
        onClose={() => setBulkResult(null)}
      />
    </div>
  )
}
