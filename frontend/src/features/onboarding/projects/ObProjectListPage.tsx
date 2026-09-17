import * as React from 'react'
import { Link, useSearchParams } from 'react-router-dom'

import { useListObProducts } from '@/api/generated/onboarding-masters/onboarding-masters'
import { useListUsers } from '@/api/generated/users/users'
import type { ObProject } from '@/api/generated/model/obProject'

import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Input } from '@/components/ui/input'
import { FilterDropdown } from '@/components/ui/filter-dropdown'
import {
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
} from '@/components/ui/modal'
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

import { ObCursorPager } from '@/features/onboarding/pagination/ObCursorPager'
import { OB_PAGE_SIZE, useCursorPages } from '@/features/onboarding/pagination/useCursorPages'

import {
  projectBlockersFrom,
  projectQueryString,
  useDeleteObProject,
  useObProjects,
} from './projectQueries'
import { currentStageLabel, delayCell, formatDate, stageProgress } from './projectRow'

const STATUSES = ['RUNNING', 'COMPLETED', 'ON_HOLD', 'DROPPED'] as const

/**
 * The Projects grid — `/onboarding/projects`. Every running engagement, with
 * the figures somebody opens this screen to read.
 *
 * <h2>Ten columns, and each is a question a manager asks in a stand-up</h2>
 *
 * Project, client, product, started, sales, implementor, current step, steps
 * complete of total, delayed by, tentative completion. Nothing derived on this
 * side except formatting: the two calendar figures are the server's, because
 * every duration in the system routes through the working calendar and a second
 * opinion computed here would disagree with the dashboard by a weekend.
 *
 * <h2>Filter state lives in the URL</h2>
 *
 * A filtered grid is a link. "Every delayed project Ravi is implementing" is
 * what one manager sends another, and it cannot be if the state is private to a
 * component — the same argument `useObClientFilters` makes for OB-03, applied to
 * the screen that replaced it. The Clients master links here with `?clientId=`
 * for exactly this reason.
 *
 * <h2>Ten rows a page, and the position is not in the URL</h2>
 *
 * The filters are a link; the page within them is not. A cursor is an opaque
 * keyset position in one ordered result set, so a pasted `?cursor=` means
 * nothing to whoever receives it — and resuming one under a different filter
 * returns rows from an arbitrary position rather than an error. So the cursor
 * stack lives in `useCursorPages`, keyed on the filter query string, which
 * returns to page one whenever any filter moves without each of the five change
 * handlers having to remember to.
 *
 * <h2>Why there is no delay filter</h2>
 *
 * `delayedByDays` is computed per row after the page is fetched, so filtering on
 * it server-side would mean paging over a value the keyset cursor is not
 * ordered by — the defect `ObDelayedProjectsService` avoids by sorting its whole
 * candidate set in memory, which it can afford because that set is already
 * narrowed to late journeys. This grid is not. The dashboard's Delayed Projects
 * card is the screen for that question and is already built.
 */
export function ObProjectListPage() {
  const [params, setParams] = useSearchParams()
  const [deleting, setDeleting] = React.useState<ObProject | null>(null)

  const q = params.get('q') ?? ''
  const clientId = numberParam(params.get('clientId'))
  const productId = numberParam(params.get('productId'))
  const implementorId = numberParam(params.get('implementorId'))
  const salesPersonId = numberParam(params.get('salesPersonId'))
  const status = params.get('status')

  const setParam = (key: string, value: string | null) => {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value)
    else next.delete(key)
    setParams(next, { replace: true })
  }

  /*
    Every filter, and nothing else. `projectQueryString` is reused rather than a
    hand-rolled join so a filter added to the request cannot be forgotten here —
    which would leave the grid on page four of a result set the new filter never
    ordered, showing rows that look like an answer.
  */
  const filters = { q, clientId, productId, implementorId, salesPersonId, status }
  const pages = useCursorPages(projectQueryString(filters))

  const { data, isPending, isError, isFetching } = useObProjects({
    ...filters,
    cursor: pages.cursor,
    limit: OB_PAGE_SIZE,
  })
  const { data: products } = useListObProducts({ isActive: true })
  const { data: users } = useListUsers()

  const projects = data?.data ?? []
  const productList = products?.data ?? []
  const people = users?.data ?? []

  return (
    <div className="mx-auto flex max-w-[88rem] flex-col gap-6 p-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-content">Projects</h1>
          <p className="mt-1 max-w-3xl text-sm text-content-muted">
            One row per engagement: a client, a product, and the module services they were
            boarded through. Delay and the tentative completion date are working-calendar
            figures — weekends, org holidays and resource leave are already excluded.
          </p>
        </div>
        <Button asChild>
          <Link to="/onboarding/projects/new">New project</Link>
        </Button>
      </header>

      <div className="flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-1">
          <label htmlFor="project-search" className="text-xs font-medium text-content-muted">
            Search
          </label>
          <Input
            id="project-search"
            className="w-56"
            placeholder="Project or client name…"
            value={q}
            onChange={(e) => setParam('q', e.target.value)}
          />
        </div>

        <FilterDropdown
          label="Product"
          options={productList}
          value={productList.find((p) => p.id === productId) ?? null}
          onChange={(p) => setParam('productId', p ? String(p.id) : null)}
          getKey={(p) => String(p.id)}
          getLabel={(p) => p.name}
        />

        <FilterDropdown
          label="Implementor"
          options={people}
          value={people.find((u) => u.id === implementorId) ?? null}
          onChange={(u) => setParam('implementorId', u ? String(u.id) : null)}
          getKey={(u) => String(u.id)}
          getLabel={(u) => u.displayName}
          getSearchable={(u) => [u.email ?? '']}
        />

        {/*
          The route has always accepted `salesPersonId` and `projectQueryString`
          has always sent it; only this page had no control for it, so a link
          carrying the parameter — OB-02's salesperson donut is the first —
          landed on an unfiltered grid that looked like an answer. A filter the
          request honours and the URL does not is worse than no filter.
        */}
        <FilterDropdown
          label="Salesperson"
          options={people}
          value={people.find((u) => u.id === salesPersonId) ?? null}
          onChange={(u) => setParam('salesPersonId', u ? String(u.id) : null)}
          getKey={(u) => String(u.id)}
          getLabel={(u) => u.displayName}
          getSearchable={(u) => [u.email ?? '']}
        />

        <FilterDropdown
          label="Status"
          options={[...STATUSES]}
          value={status && STATUSES.includes(status as (typeof STATUSES)[number]) ? status : null}
          onChange={(s) => setParam('status', s)}
          getKey={(s) => s}
          getLabel={statusLabel}
          // Four options — nothing to type-ahead over.
          searchable={false}
        />

        {clientId != null ? (
          /*
            Shown as a removable chip rather than a fourth dropdown: this filter
            arrives from a link on the Clients master, and somebody who followed
            one needs to see that the grid is narrowed and get out of it.
          */
          <Button variant="secondary" onClick={() => setParam('clientId', null)}>
            Showing one client · clear
          </Button>
        ) : null}
      </div>

      {isPending ? (
        <Skeleton className="h-72 w-full" />
      ) : isError ? (
        <p className="text-sm text-danger-text">Projects could not be loaded.</p>
      ) : projects.length === 0 ? (
        <EmptyState
          title="No projects match"
          description="Clear a filter, or create the first project for a client."
        />
      ) : (
        <TableContainer>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead scope="col">Project</TableHead>
                <TableHead scope="col">Client</TableHead>
                <TableHead scope="col">Product</TableHead>
                <TableHead scope="col" className="w-28">
                  Started
                </TableHead>
                <TableHead scope="col" className="w-32">
                  Sales
                </TableHead>
                <TableHead scope="col" className="w-32">
                  Implementor
                </TableHead>
                <TableHead scope="col" className="w-44">
                  Current step
                </TableHead>
                <TableHead scope="col" className="w-32">
                  Steps
                </TableHead>
                <TableHead scope="col" className="w-28">
                  Delayed
                </TableHead>
                <TableHead scope="col" className="w-32">
                  Tentative finish
                </TableHead>
                <TableHead scope="col" className="w-24">
                  <span className="sr-only">Actions</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {projects.map((project) => (
                <ProjectRow
                  key={project.id}
                  project={project}
                  onDelete={() => setDeleting(project)}
                />
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      {/*
        Rendered outside the loading branch above, and kept on screen on an
        empty page as long as there is a page behind it — a filter that empties
        page three has to leave a way back to page two.
      */}
      {!isError && (projects.length > 0 || pages.canGoBack) ? (
        <ObCursorPager
          noun="projects"
          pageIndex={pages.pageIndex}
          rowsOnPage={projects.length}
          hasMore={data?.meta?.hasMore ?? false}
          isFetching={isFetching}
          canGoBack={pages.canGoBack}
          onPrevious={pages.previous}
          onNext={() => pages.next(data?.meta?.nextCursor)}
        />
      ) : null}

      <DeleteProjectDialog project={deleting} onClose={() => setDeleting(null)} />
    </div>
  )
}

function ProjectRow({ project, onDelete }: { project: ObProject; onDelete: () => void }) {
  const delay = delayCell(project)
  const stages = stageProgress(project)

  return (
    <TableRow>
      <TableCell className="font-medium">
        <Link to={`/onboarding/projects/${project.id}`} className="text-primary hover:underline">
          {project.name}
        </Link>
        {project.status !== 'RUNNING' ? (
          <span className="ml-2">
            <Chip variant={project.status === 'COMPLETED' ? 'success' : 'neutral'}>
              {statusLabel(project.status)}
            </Chip>
          </span>
        ) : null}
      </TableCell>
      <TableCell className="text-content-muted">{project.client.name}</TableCell>
      <TableCell className="text-content-muted">{project.product.name}</TableCell>
      <TableCell className="tabular-nums text-content-muted">
        {formatDate(project.startDate)}
      </TableCell>
      <TableCell className="text-content-muted">{project.salesPerson?.displayName ?? '—'}</TableCell>
      <TableCell className="text-content-muted">{project.implementor?.displayName ?? '—'}</TableCell>
      <TableCell>
        <Chip variant={project.currentStage ? 'info' : 'neutral'}>
          {currentStageLabel(project)}
        </Chip>
      </TableCell>
      <TableCell>
        <div className="flex items-center gap-2">
          {/*
            A meter rather than a progress bar: this is a measurement within a
            known range, not the progress of an operation the page started.
          */}
          <div
            className="h-1.5 w-12 shrink-0 overflow-hidden rounded-full bg-subtle"
            role="meter"
            aria-valuemin={0}
            aria-valuemax={project.stagesTotal}
            aria-valuenow={project.stagesComplete}
            aria-label={`${stages.label} steps complete`}
          >
            <span
              className="block h-full bg-primary"
              style={{ width: `${Math.round(stages.fraction * 100)}%` }}
            />
          </div>
          <span className="tabular-nums text-content-muted">{stages.label}</span>
        </div>
      </TableCell>
      <TableCell>
        <span title={delay.hint}>
          <Chip variant={delay.tone === 'late' ? 'danger' : delay.tone === 'ok' ? 'success' : 'neutral'}>
            {delay.label}
          </Chip>
        </span>
      </TableCell>
      <TableCell className="tabular-nums text-content-muted">
        {formatDate(project.tentativeCompletion)}
      </TableCell>
      <TableCell>
        <Button
          variant="secondary"
          onClick={onDelete}
          // Named for a screen reader, because "Delete" down a column says
          // nothing about which project it deletes.
          aria-label={`Delete ${project.name}`}
        >
          Delete
        </Button>
      </TableCell>
    </TableRow>
  )
}

/**
 * Confirm, then delete — or explain why not.
 *
 * <h2>The button is offered on every row, including the ones it will refuse</h2>
 *
 * Whether a project is deletable depends on nine tables this page cannot see:
 * step history, logged time, sign-offs, communications, escalations, documents
 * and sent notifications. Only the server knows, so this does not try to
 * predict the refusal — it asks, and surfaces the `409` with the blockers the
 * server named plus the way forward.
 *
 * Hiding the button on rows that might refuse would hide it on the one row
 * somebody mis-created, since a brand-new project looks like any other. A
 * refused delete that names what is in the way is more useful than a missing
 * control.
 */
function DeleteProjectDialog({
  project,
  onClose,
}: {
  project: ObProject | null
  onClose: () => void
}) {
  const remove = useDeleteObProject()
  const [refusal, setRefusal] = React.useState<{ message: string; blockers: string[] } | null>(null)

  React.useEffect(() => {
    if (project) setRefusal(null)
  }, [project])

  const onConfirm = () => {
    if (!project) return
    remove.mutate(project.id, {
      onSuccess: () => {
        toast({ title: `${project.name} deleted` })
        onClose()
      },
      onError: (error) => {
        const blockers = projectBlockersFrom(error)
        if (blockers.length > 0) {
          setRefusal({
            message: error.problem.detail ?? 'This project cannot be deleted.',
            blockers,
          })
          return
        }
        toast({ title: 'The project was not deleted', description: error.problem.detail })
      },
    })
  }

  return (
    <Modal open={project != null} onOpenChange={(open) => (open ? undefined : onClose())}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>Delete {project?.name}?</ModalTitle>
          <ModalDescription>
            {refusal ? 'Nothing was deleted.' : 'This cannot be undone.'}
          </ModalDescription>
        </ModalHeader>

        <div className="px-6 py-4 text-sm">
          {refusal ? (
            <div className="rounded-card border border-danger bg-danger-soft p-3">
              <p className="font-medium text-danger-text">
                This project has {refusal.blockers.join(', ')}.
              </p>
              <p className="mt-1 text-content-muted">{refusal.message}</p>
            </div>
          ) : (
            <p className="text-content-muted">
              This removes the project and the journeys created for it. A project that has
              actually run — anything with recorded history, logged time, sign-offs, documents or
              sent notifications — cannot be deleted; if that is this one, you will be told and
              nothing will be removed.
            </p>
          )}
        </div>

        <ModalFooter>
          <Button type="button" variant="secondary" onClick={onClose}>
            {refusal ? 'Close' : 'Cancel'}
          </Button>
          {refusal ? null : (
            <Button type="button" onClick={onConfirm} disabled={remove.isPending}>
              {remove.isPending ? 'Deleting…' : 'Delete project'}
            </Button>
          )}
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}

function statusLabel(status: string): string {
  switch (status) {
    case 'RUNNING':
      return 'Running'
    case 'COMPLETED':
      return 'Completed'
    case 'ON_HOLD':
      return 'On hold'
    case 'DROPPED':
      return 'Dropped'
    default:
      return status
  }
}

/** A hand-edited `?clientId=abc` reads as no filter rather than reaching the server. */
function numberParam(value: string | null): number | null {
  if (!value) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}
