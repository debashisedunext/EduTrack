import { useEffect, useMemo } from 'react'
import { useListProjects } from '@/api/generated/projects/projects'
import { SearchableDropdown } from '@/components/ui/searchable-dropdown'
import { useCurrentProjectStore } from './currentProjectStore'

// Project switcher — blueprint §7.2. Defaults to the first active project.
export function ProjectSwitcher() {
  const { data } = useListProjects()
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
