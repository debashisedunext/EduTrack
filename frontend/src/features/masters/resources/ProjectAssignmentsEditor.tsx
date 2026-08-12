import * as React from 'react'
import { Plus, X } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { SearchableDropdown } from '@/components/ui/searchable-dropdown'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import type { Project } from '@/api/generated/model/project'
import type { ProjectRoleCode } from '@/api/generated/model/projectRoleCode'

import { PROJECT_ROLES } from './resourceForm'

/**
 * B-011 · S-08's Projects section — "multi-select project assignment with
 * per-project role".
 *
 * <h2>Why a row per project rather than a multi-select</h2>
 *
 * A plain multi-select would satisfy "multi-select project assignment" and drop
 * the second half of the sentence on the floor. The per-project role is the
 * point: `project_members.role_in_project` exists so a Developer globally can
 * be mapped as QA on one project, and a control that cannot express that would
 * make the column permanently unreachable from the only screen that writes it.
 *
 * <h2>Role is optional, and blank means something</h2>
 *
 * "Same as their global role" is the common case and the column's `NULL`. A
 * required dropdown would force every membership to restate the person's role,
 * and the first time somebody's global role changed, every restatement would be
 * a stale override nobody knew was there.
 */
export interface ProjectAssignmentRow {
  projectId: number
  roleInProject: ProjectRoleCode | ''
}

export interface ProjectAssignmentsEditorProps {
  value: readonly ProjectAssignmentRow[]
  onChange: (rows: ProjectAssignmentRow[]) => void
  projects: readonly Project[]
  disabled?: boolean
}

const ROLE_LABELS: Record<ProjectRoleCode, string> = {
  PM: 'Project Manager',
  DEVELOPER: 'Developer',
  SUPPORT: 'Support',
  QA: 'QA',
  DEPLOYMENT: 'Deployment',
  VIEWER: 'Viewer',
}

export function ProjectAssignmentsEditor({
  value,
  onChange,
  projects,
  disabled,
}: ProjectAssignmentsEditorProps) {
  const assigned = new Set(value.map((row) => row.projectId))
  // A project already on the list is not offerable again: two rows for one
  // project is a request the server de-duplicates anyway, and letting the form
  // express it means the user's second choice silently wins.
  const available = React.useMemo(
    () => projects.filter((project) => !assigned.has(project.id)),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [projects, value],
  )

  const [picking, setPicking] = React.useState(false)

  function addProject(project: Project) {
    onChange([...value, { projectId: project.id, roleInProject: '' }])
    setPicking(false)
  }

  function setRole(projectId: number, role: ProjectRoleCode | '') {
    onChange(value.map((row) => (row.projectId === projectId ? { ...row, roleInProject: role } : row)))
  }

  function remove(projectId: number) {
    onChange(value.filter((row) => row.projectId !== projectId))
  }

  function nameOf(projectId: number) {
    const project = projects.find((p) => p.id === projectId)
    return project ? `${project.projectCode} — ${project.name}` : `Project ${projectId}`
  }

  return (
    <div className="flex flex-col gap-3">
      {value.length === 0 ? (
        <p className="text-caption text-content-muted">
          Not assigned to any project. Tickets can still be raised for them, but they will not appear in a
          project team.
        </p>
      ) : (
        <ul className="flex flex-col gap-2">
          {value.map((row) => (
            <li
              key={row.projectId}
              className="flex flex-wrap items-center gap-3 rounded-control border border-border bg-subtle px-3 py-2"
            >
              <span className="min-w-0 flex-1 truncate text-sm text-content">{nameOf(row.projectId)}</span>

              <Select
                value={row.roleInProject === '' ? 'INHERIT' : row.roleInProject}
                disabled={disabled}
                onValueChange={(next) =>
                  setRole(row.projectId, next === 'INHERIT' ? '' : (next as ProjectRoleCode))
                }
              >
                <SelectTrigger
                  className="w-48"
                  aria-label={`Role on ${nameOf(row.projectId)}`}
                >
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="INHERIT">Same as global role</SelectItem>
                  {PROJECT_ROLES.map((role) => (
                    <SelectItem key={role} value={role}>
                      {ROLE_LABELS[role]}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>

              <button
                type="button"
                disabled={disabled}
                onClick={() => remove(row.projectId)}
                aria-label={`Remove from ${nameOf(row.projectId)}`}
                className="rounded-control p-1.5 text-content-muted hover:bg-surface hover:text-content focus-visible:outline-none focus-visible:ring-2"
              >
                <X className="h-4 w-4" />
              </button>
            </li>
          ))}
        </ul>
      )}

      {picking ? (
        <SearchableDropdown
          options={[...available]}
          value={null}
          onChange={addProject}
          getKey={(p) => String(p.id)}
          getLabel={(p) => `${p.projectCode} — ${p.name}`}
          getSearchable={(p) => [p.projectCode, p.name]}
          placeholder="Search projects…"
          emptyText={available.length === 0 ? 'Already on every project' : 'No results'}
          disabled={disabled}
          aria-labelledby="projects-label"
        />
      ) : (
        <div>
          <Button
            type="button"
            variant="secondary"
            size="sm"
            disabled={disabled || available.length === 0}
            onClick={() => setPicking(true)}
          >
            <Plus className="h-4 w-4" />
            Add project
          </Button>
        </div>
      )}
    </div>
  )
}
