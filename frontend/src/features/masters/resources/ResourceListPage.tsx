import * as React from 'react'
import { keepPreviousData } from '@tanstack/react-query'
import { ChevronLeft, ChevronRight, Download, Plus, RotateCcw, Search, Upload } from 'lucide-react'
import { Link, useLocation, useNavigate } from 'react-router-dom'

import { useListUsers, useSetUserStatus, useSetUserStatusBulk } from '@/api/generated/users/users'
import { useListProjects } from '@/api/generated/projects/projects'
import type { RoleCode } from '@/api/generated/model/roleCode'
import type { BulkUserStatusResponseData } from '@/api/generated/model/bulkUserStatusResponseData'
import { ApiError, BASE } from '@/api/http'

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
import {
  BulkStatusBar,
  BulkStatusResultDialog,
  DeactivationConfirmDialog,
  type DeactivationCandidate,
} from './BulkStatusBar'
import { resumeDeactivationTarget, withoutResumeMarker } from './reassignHandoff'
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
 * S-07 Resource Master — List (B-010), with B-011's entry points into the form
 * and B-014's deactivation flow.
 *
 * "New resource" and the per-row Edit link both go to `ResourceFormPage`.
 *
 * <h2>Deactivation is a three-legged flow, and this screen owns two of them</h2>
 *
 * 1. **Refuse, and say what would fix it.** A selection containing somebody with
 *    open tickets stops in `DeactivationConfirmDialog` before the request is
 *    made, because the grid already knows the counts.
 * 2. **Hand off.** Each blocked person links into S-24 with themselves
 *    preselected and a `returnTo` pointing back here — Stream C's C-063 builds
 *    that screen; `reassignHandoff.ts` is the contract between us.
 * 3. **Resume.** Arriving with `?deactivate=<id>` finishes the interrupted job
 *    through `PATCH /users/{userId}/status`. Without this leg the admin comes
 *    back to a grid where the person is still active and nothing explains why,
 *    and the flow the blueprint calls *forced* quietly ends one step short.
 */
export function ResourceListPage() {
  const { filters, setFilter, resetFilters, activeCount } = useResourceFilters()
  const location = useLocation()
  const navigate = useNavigate()

  // Cursor pagination has no page numbers to jump to. The stack is what makes
  // "Previous" possible without an offset — CONVENTIONS.md §6.
  const [cursorStack, setCursorStack] = React.useState<string[]>([])
  const cursor = cursorStack[cursorStack.length - 1]

  const [selected, setSelected] = React.useState<ReadonlySet<number>>(new Set())
  const [bulkResult, setBulkResult] = React.useState<BulkUserStatusResponseData | null>(null)
  const [lastBulkWasActivate, setLastBulkWasActivate] = React.useState(false)
  const [pendingDeactivation, setPendingDeactivation] =
    React.useState<readonly DeactivationCandidate[] | null>(null)

  // Every link into the S-24 wizard carries this home, so the return trip lands
  // on the filtered view the admin was working in rather than a reset grid. The
  // resume marker is stripped: it describes the trip we are on, not the next one.
  const returnSearch = withoutResumeMarker(location.search)

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

  function runBulkStatus(userIds: number[], isActive: boolean) {
    setLastBulkWasActivate(isActive)
    setPendingDeactivation(null)
    bulkStatus.mutate({ data: { userIds, isActive } })
  }

  /**
   * B-014 · stop before the request when the grid already knows it will be
   * refused.
   *
   * Only for deactivation, and only when somebody in the selection holds open
   * tickets. Activation cannot orphan anything, and a clean deactivation must
   * not grow a confirmation step it does not need — a dialog on every bulk
   * action is a dialog nobody reads.
   *
   * Rows outside the current page are in `selected` but not in `resources`, so
   * their counts are unknown here. Those go to the server unchecked and come
   * back through `BulkStatusResultDialog`, which is the same path a stale count
   * takes. Fetching them to complete the picture would be a round trip to
   * pre-empt a refusal the server makes anyway.
   */
  function applyBulkStatus(isActive: boolean) {
    const ids = [...selected]
    if (isActive) {
      runBulkStatus(ids, true)
      return
    }

    const blocked: DeactivationCandidate[] = resources
      .filter((r) => selected.has(r.id) && r.isActive && (r.openTicketCount ?? 0) > 0)
      .map((r) => ({
        id: r.id,
        displayName: r.displayName,
        openTicketCount: r.openTicketCount ?? 1,
      }))

    if (blocked.length > 0) {
      setPendingDeactivation(blocked)
      return
    }
    runBulkStatus(ids, false)
  }

  /**
   * "Deactivate the other N" — the whole selection minus the named blockers.
   *
   * <b>Subtracted from `selected`, not rebuilt from the visible rows.</b> A
   * selection survives paging, so somebody ticked two pages back is in
   * `selected` and not in `resources`; deriving "the rest" from what is on
   * screen would drop them silently, and a bulk action that quietly does less
   * than the count on the button is worse than one that refuses.
   */
  function confirmDeactivation() {
    const blockedIds = new Set(pendingDeactivation?.map((c) => c.id))
    runBulkStatus(
      [...selected].filter((id) => !blockedIds.has(id)),
      false,
    )
  }

  // ── B-014 · the return leg from the reassignment wizard ───────────────────
  const resumeStatus = useSetUserStatus({
    mutation: {
      onSuccess: () => {
        void refetch()
        toast({ variant: 'success', title: 'Deactivated', description: 'Their tickets now have a new owner.' })
      },
      onError: (mutationError) => {
        // The most likely failure by far, and the one worth a specific
        // sentence: the wizard moved some of the tickets and not all of them.
        // "Something went wrong" would send the admin looking for a bug in a
        // flow that is working exactly as designed.
        const remaining =
          mutationError instanceof ApiError && mutationError.is('open-tickets')
            ? Number((mutationError.problem as { openTicketCount?: number }).openTicketCount ?? 0)
            : null

        toast({
          variant: 'danger',
          title: remaining != null ? 'Still holding open tickets' : 'Could not deactivate',
          description:
            remaining != null
              ? `${remaining} ${remaining === 1 ? 'ticket is' : 'tickets are'} still assigned to them. Reassign the rest, then deactivate again.`
              : 'Try again from the list.',
        })
      },
    },
  })

  /**
   * Finishes the job the wizard interrupted.
   *
   * The marker is cleared from the URL in the same tick the mutation is fired,
   * for two reasons: a refresh must not re-run the write, and the URL should
   * describe where the admin is rather than what happened on the way. `replace`
   * rather than `push`, so Back goes to the wizard's referrer and not to a URL
   * that would immediately try again.
   *
   * The ref guards against a second run when React re-invokes the effect (Strict
   * Mode does this deliberately in development) before the navigation has
   * settled. Deactivation is idempotent so a double fire is harmless at the
   * server, but it would raise two toasts saying the same thing.
   */
  const resumeTarget = resumeDeactivationTarget(location.search)
  const resumed = React.useRef<number | null>(null)

  React.useEffect(() => {
    if (resumeTarget == null || resumed.current === resumeTarget) return
    resumed.current = resumeTarget

    resumeStatus.mutate({
      userId: resumeTarget,
      data: { isActive: false, reason: 'Tickets reassigned via the S-24 wizard' },
    })
    navigate({ pathname: location.pathname, search: withoutResumeMarker(location.search) }, { replace: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resumeTarget])

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
          {/*
            B-038 · §7.4's S-07 bulk action "bulk import via CSV", which is the
            five-step wizard of §4B.3 registered a second time — not a second
            screen. Secondary, beside Export, because the two are a pair: the
            file that comes out of one is the file that goes into the other, and
            the round trip is how an organisation edits four hundred people.
          */}
          <Button asChild variant="secondary" size="sm">
            <Link to="/masters/resources/import">
              <Upload className="h-4 w-4" />
              Import from Excel
            </Link>
          </Button>
          <Button asChild size="sm">
            <Link to="/masters/resources/new">
              <Plus className="h-4 w-4" />
              New resource
            </Link>
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
                      ) : (
                        // An empty directory with no filters on is a fresh
                        // tenant, and the only useful next action is to add
                        // somebody — not to clear filters that are not set.
                        <Button asChild size="sm">
                          <Link to="/masters/resources/new">Add the first resource</Link>
                        </Button>
                      )
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

      <DeactivationConfirmDialog
        blocked={pendingDeactivation}
        proceedCount={selected.size - (pendingDeactivation?.length ?? 0)}
        returnSearch={returnSearch}
        isPending={bulkStatus.isPending}
        onConfirm={confirmDeactivation}
        onCancel={() => setPendingDeactivation(null)}
      />

      <BulkStatusResultDialog
        result={bulkResult}
        isActivating={lastBulkWasActivate}
        returnSearch={returnSearch}
        onClose={() => setBulkResult(null)}
      />
    </div>
  )
}
