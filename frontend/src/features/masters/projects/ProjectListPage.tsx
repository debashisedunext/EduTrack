import * as React from 'react'
import { Link } from 'react-router-dom'

import type { Project } from '@/api/generated/model/project'
import type { ProjectStatus } from '@/api/generated/model/projectStatus'

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

import { useProjects } from './projectQueries'

/**
 * S-10 Project Master — the list. B-016.
 *
 * The create/edit form is `ProjectFormPage`; this is the grid that gets you
 * there.
 *
 * **The status filter has four positions, not three.** "All" includes closed
 * projects, because retiring one is not deleting it and an admin looking for a
 * project they closed last quarter should find it here. The default is "All"
 * for the same reason the resource grid's is: a master screen whose purpose
 * includes reactivating things must not hide the things to reactivate.
 */
// Mutable, not `readonly`: `FilterDropdown` takes `options: T[]` and a readonly
// array is not assignable to it. Widening the component's prop is a change to
// Stream C's `components/ui/`, which is not this task's to make.
const STATUS_OPTIONS: { value: ProjectStatus; label: string }[] = [
  { value: 'ACTIVE', label: 'Active' },
  { value: 'ON_HOLD', label: 'On hold' },
  { value: 'CLOSED', label: 'Closed' },
]

export function ProjectListPage() {
  const [status, setStatus] = React.useState<ProjectStatus | null>(null)
  const [search, setSearch] = React.useState('')

  const { data, isPending, isError } = useProjects({
    limit: 200,
    ...(status == null ? {} : { status }),
  })

  const projects = React.useMemo(() => {
    const rows = data?.data ?? []
    const text = search.trim().toLowerCase()
    if (!text) return rows
    // Filtered here as well as on the server: the server owns the query the
    // grid pages over, and this keeps typing responsive without a request per
    // keystroke. The two use the same predicate — code or name, substring,
    // case-insensitive.
    return rows.filter(
      (p) => p.name.toLowerCase().includes(text) || p.projectCode.toLowerCase().includes(text),
    )
  }, [data, search])

  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-6 p-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-content">Projects</h1>
          <p className="mt-1 max-w-2xl text-sm text-content-muted">
            Every project, and the code that prefixes its ticket IDs. A project that has
            issued a ticket ID keeps its code for good — closing it is how a project is
            retired, and its tickets stay readable either way.
          </p>
        </div>
        <Button asChild>
          <Link to="/masters/projects/new">New project</Link>
        </Button>
      </header>

      <div className="flex flex-wrap items-center gap-3">
        <Input
          value={search}
          placeholder="Search code or name…"
          aria-label="Search projects"
          className="max-w-xs"
          onChange={(e) => setSearch(e.target.value)}
        />
        <FilterDropdown
          label="Status"
          options={STATUS_OPTIONS}
          value={STATUS_OPTIONS.find((s) => s.value === status) ?? null}
          onChange={(s) => setStatus(s?.value ?? null)}
          getKey={(s) => s.value}
          getLabel={(s) => s.label}
          searchable={false}
        />
      </div>

      {isPending ? (
        <Skeleton className="h-64 w-full" />
      ) : isError ? (
        <p className="text-sm text-danger-text">Projects could not be loaded.</p>
      ) : projects.length === 0 ? (
        <EmptyState
          title="No projects match"
          description={
            search || status != null
              ? 'Clear the search or the status filter to see the rest.'
              : 'Create the first project to start raising tickets against it.'
          }
        />
      ) : (
        <TableContainer>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead scope="col">Project</TableHead>
                <TableHead scope="col">Code</TableHead>
                <TableHead scope="col">Client</TableHead>
                <TableHead scope="col">Project manager</TableHead>
                <TableHead scope="col">Dates</TableHead>
                <TableHead scope="col">Status</TableHead>
                <TableHead scope="col">
                  <span className="sr-only">Actions</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {projects.map((project) => (
                <ProjectRow key={project.id} project={project} />
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      {/*
        The server pages by a keyset cursor and this grid asks for 200 in one go
        without following `meta.nextCursor`. That is a real cap, and it is said
        out loud rather than left to look like the whole list: a silent
        truncation reads as "these are all the projects" when it is not. An
        organisation with more than 200 projects needs the cursor wired up here
        — the API side of it is already built.
      */}
      {data?.meta?.hasMore ? (
        <p className="text-sm text-content-muted">
          Showing the first 200 projects. Narrow the search or the status filter to see the rest.
        </p>
      ) : null}
    </div>
  )
}

const STATUS_CHIP: Record<ProjectStatus, { variant: 'success' | 'warning' | 'neutral'; label: string }> = {
  ACTIVE: { variant: 'success', label: 'Active' },
  ON_HOLD: { variant: 'warning', label: 'On hold' },
  CLOSED: { variant: 'neutral', label: 'Closed' },
}

function ProjectRow({ project }: { project: Project }) {
  const chip = STATUS_CHIP[project.status ?? 'ACTIVE'] ?? STATUS_CHIP.ACTIVE

  return (
    <TableRow>
      <TableCell>
        <span className="flex items-center gap-2">
          {/*
            The colour tag is decoration, not information — the status chip and
            the name already carry everything. aria-hidden rather than a label,
            so a screen reader is not read a hex value.
          */}
          <span
            aria-hidden
            className="h-2.5 w-2.5 shrink-0 rounded-full"
            style={{ backgroundColor: project.colourTag ?? 'transparent' }}
          />
          <Link
            to={`/masters/projects/${project.id}/edit`}
            className="font-medium text-primary hover:underline"
          >
            {project.name}
          </Link>
        </span>
      </TableCell>
      <TableCell>
        <code className="text-xs text-content-muted">{project.projectCode}</code>
      </TableCell>
      <TableCell className="text-sm text-content-muted">{project.clientName ?? '—'}</TableCell>
      <TableCell className="text-sm">{project.projectManager?.displayName ?? '—'}</TableCell>
      <TableCell className="text-sm text-content-muted">
        {project.startDate ?? '—'} → {project.endDate ?? '—'}
      </TableCell>
      <TableCell>
        <Chip variant={chip.variant}>{chip.label}</Chip>
      </TableCell>
      <TableCell className="text-right">
        <Button asChild variant="ghost" size="sm">
          <Link to={`/masters/projects/${project.id}/edit`}>Edit</Link>
        </Button>
      </TableCell>
    </TableRow>
  )
}
