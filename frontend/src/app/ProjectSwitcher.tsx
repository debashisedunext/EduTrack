import { useEffect, useMemo } from 'react'
import { useListProjects } from '@/api/generated/projects/projects'
import { SearchableDropdown } from '@/components/ui/searchable-dropdown'
import { useCurrentProjectStore } from './currentProjectStore'

/**
 * Project switcher — blueprint §7.2. Defaults to the first active project.
 *
 * **`isActive` is passed, and the comment above is why.** This called
 * `useListProjects()` unfiltered while claiming to offer active projects, and
 * nothing contradicted it because until B-016 no project was ever anything but
 * active — `isActive` was a stored boolean that every seeded row set to true.
 * B-016 gives projects the three states S-10 asks for, and the first `CLOSED`
 * one went straight to the top of this list and became the default selection:
 * every user landing on a retired project, with no way to tell that was not
 * intended.
 *
 * `isActive: true` means `status <> 'CLOSED'`, so an **On Hold** project stays
 * offered here — it is paused, not retired, and its tickets are still worked.
 * The other five callers of this hook already send exactly this.
 *
 * **Stream A owns `app/`** — flagged rather than done quietly. One line, and the
 * alternative was shipping a switcher whose own comment was false.
 */
export function ProjectSwitcher() {
  const { data } = useListProjects({ isActive: true, limit: 200 })
  const projects = useMemo(() => data?.data ?? [], [data])
  const { project, setProject } = useCurrentProjectStore()

  useEffect(() => {
    if (!project && projects.length > 0) setProject(projects[0])
  }, [projects, project, setProject])

  return (
    <SearchableDropdown
      options={projects}
      value={project}
      onChange={setProject}
      getKey={(p) => String(p.id)}
      getLabel={(p) => p.name}
      getSearchable={(p) => [p.projectCode]}
      placeholder="Select project…"
      className="w-56"
    />
  )
}
