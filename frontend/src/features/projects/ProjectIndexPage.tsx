import { Link } from 'react-router-dom'
import { ArrowRight, Settings2 } from 'lucide-react'

import type { Project } from '@/api/generated/model'
import { useListProjects } from '@/api/generated/projects/projects'
import { EmptyState } from '@/components/ui/empty-state'
import { projectPath } from '@/features/tickets/detail/entityLinks'

/**
 * A-077 · the index the sidebar's "Projects" leads to, and the way into the
 * project dashboard that is not a typed URL.
 *
 * <h2>Why this exists, and what it deliberately is not</h2>
 *
 * A-077 replaced the `PROJECT_ROUTE` placeholder, so `/projects/:projectId`
 * became a real screen — but only reachable by clicking a project name on a
 * ticket or in a report. The sidebar's own "Projects" entry pointed at
 * `/projects`, a *different* placeholder, so the nav led to "This screen is
 * built in a later task" while the screen it was named for existed one segment
 * further down the path.
 *
 * <p><b>This is not a second project master.</b> Stream B built that, and it is
 * at `/masters/projects` — list, create, edit, team, SLA matrix, settings. This
 * screen creates nothing, edits nothing and configures nothing; it is a list of
 * doors into {@code ProjectDashboardPage}. Rebuilding B's grid here would put
 * two project lists in the product with different columns and different
 * behaviour, and the first person to change one would not know about the other.
 * The link to the master is right there in the header instead.
 *
 * <h2>Every project is listed, including ones whose figures you cannot see</h2>
 *
 * `GET /projects` is deliberately not row-scoped — `ProjectController` explains
 * that a PM who could not see a project's name could not read the tickets they
 * do own, and every project is already visible in four pickers. So this lists
 * all of them, and a project the caller is not a member of opens to a dashboard
 * that says so in words rather than drawing zeroes.
 *
 * <p><b>Membership is not marked here, and that is deliberate.</b> `GET /me`
 * carries `projectIds`, so a "yours" badge would be one line — and it would be
 * the SPA re-deriving a scope rule that CLAUDE.md places server-side. A-056 and
 * A-077 both put that answer on the server for the same reason: a rule stated
 * twice drifts, and this one would drift silently. The dashboard already answers
 * the question authoritatively, one click away.
 */
export function ProjectIndexPage() {
  // `isActive: true` is every project except CLOSED — the generated model's own
  // wording. Closed projects still have dashboards worth reading, so they are
  // not excluded; the status is shown instead and the reader decides.
  const projects = useListProjects({ limit: 200 })

  const rows = projects.data?.data ?? []

  return (
    <div className="flex flex-col gap-6 p-6">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-content">Projects</h1>
          <p className="mt-1 text-sm text-content-muted">
            Open a project to see its dashboard — tickets, SLA, ageing and team load.
          </p>
        </div>

        {/*
         * The master is Stream B's and is where a project is created or edited.
         * Linked rather than duplicated, so there is one place that owns the
         * writes and this screen stays a reader.
         */}
        <Link
          to="/masters/projects"
          className="inline-flex items-center gap-1.5 rounded-md border border-border px-3 py-1.5 text-sm text-content-muted hover:text-content"
        >
          <Settings2 className="size-4" aria-hidden="true" />
          Manage projects
        </Link>
      </header>

      {projects.isPending ? (
        <ul className="grid gap-3 md:grid-cols-2 xl:grid-cols-3" aria-busy="true">
          {Array.from({ length: 6 }, (_, i) => (
            <li key={i} className="h-28 animate-pulse rounded-card bg-subtle" />
          ))}
        </ul>
      ) : projects.isError ? (
        <EmptyState
          title="Could not load projects"
          description="The project list could not be fetched. Try again in a moment."
        />
      ) : rows.length === 0 ? (
        <EmptyState
          title="No projects yet"
          description="Once a project is created it will appear here, with its dashboard one click away."
        />
      ) : (
        <ul className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {rows.map((project) => (
            <ProjectCard key={project.id} project={project} />
          ))}
        </ul>
      )}
    </div>
  )
}

function ProjectCard({ project }: { project: Project }) {
  return (
    <li>
      {/*
       * The whole card is the link, not a div with an onClick — A-055's rule for
       * the KPI cards, and the reason is the same: an anchor keeps keyboard
       * reach, the announced role and open-in-new-tab, all of which a clickable
       * div silently removes.
       */}
      <Link
        to={projectPath(project.id)}
        className="group flex h-full flex-col rounded-card border border-border bg-surface p-4 transition-colors hover:border-primary"
      >
        <div className="flex items-start justify-between gap-2">
          <span className="font-medium text-content">{project.name}</span>
          <ArrowRight
            className="size-4 shrink-0 text-content-muted transition-transform group-hover:translate-x-0.5"
            aria-hidden="true"
          />
        </div>

        <div className="mt-2 flex flex-wrap items-center gap-2 text-sm">
          {/*
           * The code, because it is the ticket-ID prefix. Somebody looking for
           * the project that CRM-26-13013 belongs to is scanning for "CRM", not
           * for a name they may not know.
           */}
          <span className="rounded bg-subtle px-1.5 py-0.5 font-mono text-xs text-content-muted">
            {project.projectCode}
          </span>
          {project.status && <span className="text-content-muted">{project.status}</span>}
        </div>

        {project.clientName && (
          <p className="mt-2 truncate text-sm text-content-muted">{project.clientName}</p>
        )}
      </Link>
    </li>
  )
}
