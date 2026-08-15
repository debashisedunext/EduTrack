import { Link, useParams } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'

import { cn } from '@/lib/utils'

import { useProject } from './projectQueries'

/**
 * B-017 · the header and tab strip a project's screens share.
 *
 * <h2>Four tabs, and S-10 is complete</h2>
 *
 * S-10 describes four — General, Team, SLA, Settings — and each arrived as an
 * entry in {@link TABS} on the day the screen behind it existed: Team with
 * B-017, SLA with B-018, Settings with B-019. **None was ever rendered as a
 * disabled stub**, which was B-016's call and held for three tasks: a greyed-out
 * tab and a broken one look identical to a user, and unbuilt screens rendered as
 * tabs make a feature look finished.
 *
 * <h2>Why the tab strip is not a router `Outlet`</h2>
 *
 * The tabs are separate routes and each owns its own data. Nesting them under a
 * layout route would put this component in the tree above them, which is right,
 * and would also make the project read a shared parent fetch — which is wrong:
 * the General tab reads the project *with its `ETag`* because its `PATCH`
 * requires one, and a shared read handed to a tab that never writes would be
 * caching a precondition nobody uses. **The SLA tab makes that sharper rather
 * than weaker**: it also needs an `ETag`, and a different one, over a different
 * resource. One parent fetch could not have served both. Separate routes, one
 * header, one cheap query each, and react-query dedupes the overlap.
 */

const TABS = [
  { to: (id: number) => `/masters/projects/${id}/edit`, label: 'General' },
  { to: (id: number) => `/masters/projects/${id}/team`, label: 'Team' },
  { to: (id: number) => `/masters/projects/${id}/sla`, label: 'SLA' },
  { to: (id: number) => `/masters/projects/${id}/settings`, label: 'Settings' },
] as const

export function ProjectTabs({ active }: { active: 'General' | 'Team' | 'SLA' | 'Settings' }) {
  const params = useParams<{ projectId?: string }>()
  const projectId = params.projectId ? Number(params.projectId) : null

  const { data } = useProject(projectId)

  return (
    <header className="flex flex-col gap-3">
      <Link
        to="/masters/projects"
        className="inline-flex w-fit items-center gap-1 text-sm text-content-muted hover:text-content"
      >
        <ArrowLeft className="h-4 w-4" aria-hidden />
        Projects
      </Link>

      <div className="flex flex-wrap items-baseline gap-3">
        <h1 className="text-2xl font-semibold text-content">{data?.project.name ?? 'Project'}</h1>
        {data?.project.projectCode ? (
          <code className="text-sm text-content-muted">{data.project.projectCode}</code>
        ) : null}
      </div>

      {projectId != null ? (
        // A real tab list: roving focus and arrow keys come free from the
        // links, and `aria-current` is what tells a screen reader which one it
        // is on. WCAG AA is not optional (CLAUDE.md).
        <nav aria-label="Project sections" className="border-b border-border">
          <ul className="flex gap-1">
            {TABS.map((tab) => {
              const isActive = tab.label === active
              return (
                <li key={tab.label}>
                  <Link
                    to={tab.to(projectId)}
                    aria-current={isActive ? 'page' : undefined}
                    className={cn(
                      'inline-block border-b-2 px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
                      isActive
                        ? 'border-primary font-medium text-content'
                        : 'border-transparent text-content-muted hover:text-content',
                    )}
                  >
                    {tab.label}
                  </Link>
                </li>
              )
            })}
          </ul>
        </nav>
      ) : null}
    </header>
  )
}
