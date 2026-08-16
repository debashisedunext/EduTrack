import { Chip } from '@/components/ui/chip'
import type { Project } from '@/api/generated/model/project'

import type { FieldAria } from '../masters/resources/FormField'

/**
 * B-026 · S-33's Projects & SLA tab — which projects this client is reachable
 * from, and which one the ticket form defaults to.
 *
 * <h2>Every project, not only the active ones</h2>
 *
 * B-016 gave projects three states, and a client's association with one that has
 * been put on hold or closed is a **fact about the relationship**, not a mistake
 * to tidy. Filtering the retired ones out of this list would delete them from the
 * mapping on the next save — the deletion-by-omission B-018 guards against for
 * project-level SLA defaults and B-019 for retired task types, arriving here for
 * a third time. They render with their status beside them instead, so the reason
 * a project reads oddly is on screen rather than inferred.
 *
 * <h2>The default is a radio inside the checkbox list, not a second dropdown</h2>
 *
 * A separate picker would let an admin choose a default the client is not mapped
 * to — a row §4B.2's ticket form can never offer, so configuration that silently
 * does nothing. Both the schema and the server refuse that combination; putting
 * the control inside the list makes it unrepresentable, which is better than
 * refusing it well.
 */
export interface ClientProjectsPickerProps {
  projects: readonly Project[]
  selected: readonly number[]
  defaultProjectId: number
  onChange: (projectIds: number[], defaultProjectId: number) => void
  aria: FieldAria
}

export function ClientProjectsPicker({
  projects,
  selected,
  defaultProjectId,
  onChange,
  aria,
}: ClientProjectsPickerProps) {
  function toggle(projectId: number, checked: boolean) {
    const next = checked
      ? [...selected, projectId]
      : selected.filter((id) => id !== projectId)

    // Unmapping the default clears it rather than leaving it pointing at a
    // project the client is no longer on. The alternative is a save that fails
    // validation for a reason the admin cannot see, because the control that
    // would have shown it has just been unchecked.
    const nextDefault = next.includes(defaultProjectId) ? defaultProjectId : 0
    onChange(next, nextDefault)
  }

  if (projects.length === 0) {
    return (
      <p className="text-sm text-content-muted">
        No projects exist yet. Create one first — a client with no project mapping cannot be
        chosen on a ticket.
      </p>
    )
  }

  return (
    <div {...aria} role="group" className="flex flex-col gap-1">
      <ul className="flex flex-col divide-y divide-border rounded-card border border-border">
        {projects.map((project) => {
          const projectId = project.id!
          const isSelected = selected.includes(projectId)
          return (
            <li key={projectId} className="flex flex-wrap items-center gap-3 px-3 py-2.5">
              <label className="flex min-w-0 flex-1 items-center gap-2.5 text-sm text-content">
                <input
                  type="checkbox"
                  checked={isSelected}
                  onChange={(e) => toggle(projectId, e.target.checked)}
                  className="h-4 w-4 rounded border-border text-primary focus:ring-2 focus:ring-primary focus:ring-offset-1"
                />
                <span className="truncate">
                  <span className="font-mono text-caption text-content-muted">
                    {project.projectCode}
                  </span>{' '}
                  {project.name}
                </span>
              </label>

              {/* The project's own state, so a closed one reads as closed rather
                  than as a row somebody forgot to remove. Never colour alone. */}
              {project.status && project.status !== 'ACTIVE' ? (
                <Chip variant="neutral">
                  {project.status === 'ON_HOLD' ? 'On hold' : 'Closed'}
                </Chip>
              ) : null}

              <label
                className={
                  'flex items-center gap-1.5 text-caption ' +
                  (isSelected ? 'text-content-muted' : 'text-content-muted opacity-40')
                }
              >
                <input
                  type="radio"
                  name="defaultProject"
                  value={projectId}
                  checked={defaultProjectId === projectId}
                  // Disabled rather than hidden on an unmapped project: an
                  // absent control reads as a rendering fault, and this one has
                  // an obvious precondition worth showing.
                  disabled={!isSelected}
                  onChange={() => onChange([...selected], projectId)}
                  className="h-3.5 w-3.5 border-border text-primary focus:ring-2 focus:ring-primary focus:ring-offset-1"
                />
                Default
              </label>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
