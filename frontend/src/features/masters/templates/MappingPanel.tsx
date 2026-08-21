import * as React from 'react'

import { ApiError } from '@/api/http'
import type { TemplateMapping } from '@/api/generated/model/templateMapping'

import { Button } from '@/components/ui/button'
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

import { useProjects } from '../projects/projectQueries'
import { useTaskTypes } from '../taskTypes/taskTypeQueries'
import {
  describePair,
  describeRung,
  draftsToRequest,
  duplicatePairKeys,
  mappingsChanged,
  mappingsToDrafts,
  newMappingDraft,
  type MappingDraft,
} from './templateForm'
import {
  useReplaceTemplateMappings,
  useTemplateMappings,
  useTemplateResolution,
} from './templateQueries'

/**
 * §7.4's *"mapped to project × task type"*, extracted from `TemplatesTab` by
 * B-043 so that S-30's designer can end where §7.4 says it ends.
 *
 * <h2>Moved rather than reimplemented</h2>
 *
 * S-30's sentence finishes *"…then map it to project × task type"*, so the
 * designer needs this panel. The alternative — a second mapping editor on the
 * canvas — would be a second copy of {@link duplicatePairKeys}, of the
 * order-insensitive dirty check, and of the resolution checker's reading of
 * §4A.9's ladder. B-041's own README argues that case one level down about the
 * stage editor; the same argument applies here and reaches the same answer.
 *
 * So this file is a **pure move**. Nothing about the behaviour changed, and both
 * `TemplatesTab` (tab 3) and `WorkflowDesignerPage` (S-30) render the same
 * component against the same two `ETag` scopes.
 */
export function MappingPanel({ templateId, templateName }: { templateId: number; templateName: string }) {
  const mappings = useTemplateMappings(templateId)
  const projects = useProjects()
  const taskTypes = useTaskTypes()
  const replace = useReplaceTemplateMappings()

  const [drafts, setDrafts] = React.useState<MappingDraft[] | null>(null)

  const loaded = mappings.data?.mappings ?? []
  const rows = drafts ?? mappingsToDrafts(loaded)
  const clashing = duplicatePairKeys(rows)
  const dirty = mappingsChanged(rows, loaded)

  const projectLabel = (id: number | null) =>
    id == null ? null : projects.data?.data.find((p) => p.id === id)?.projectCode ?? `#${id}`
  const taskTypeLabel = (id: number | null) =>
    id == null ? null : taskTypes.data?.find((t) => t.id === id)?.name ?? `#${id}`

  if (mappings.isPending) {
    return <Skeleton className="h-48 w-full" />
  }

  const update = (key: string, patch: Partial<MappingDraft>) =>
    setDrafts(rows.map((d) => (d.key === key ? { ...d, ...patch } : d)))

  return (
    <section aria-labelledby="mappings-heading" className="space-y-4 rounded-card border border-line p-4">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h3 id="mappings-heading" className="text-h4 text-content">
            Routing rules
          </h3>
          <p className="max-w-2xl text-body-sm text-content-muted">
            Which tickets follow {templateName}. Leave a column blank for <em>any</em> — the
            most specific rule wins, and a project rule beats a task-type rule.
          </p>
        </div>
        <Button type="button" variant="secondary" onClick={() => setDrafts([...rows, newMappingDraft()])}>
          Add rule
        </Button>
      </div>

      {rows.length === 0 ? (
        <p className="text-body-sm text-content-muted">
          Nothing routes to this template. Tickets reach it only if it is the default.
        </p>
      ) : (
        <TableContainer>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Project</TableHead>
                <TableHead>Task type</TableHead>
                <TableHead className="sr-only">Remove</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((d) => (
                <TableRow key={d.key} aria-invalid={clashing.has(d.key) || undefined}>
                  <TableCell>
                    <select
                      aria-label="Project"
                      className="h-9 w-full rounded-control border border-border bg-surface px-2 text-sm"
                      value={d.projectId ?? ''}
                      onChange={(e) =>
                        update(d.key, { projectId: e.target.value ? Number(e.target.value) : null })
                      }
                    >
                      <option value="">Any project</option>
                      {projects.data?.data.map((p) => (
                        <option key={p.id} value={p.id}>
                          {p.projectCode} — {p.name}
                        </option>
                      ))}
                    </select>
                  </TableCell>
                  <TableCell>
                    <select
                      aria-label="Task type"
                      className="h-9 w-full rounded-control border border-border bg-surface px-2 text-sm"
                      value={d.taskTypeId ?? ''}
                      onChange={(e) =>
                        update(d.key, { taskTypeId: e.target.value ? Number(e.target.value) : null })
                      }
                    >
                      <option value="">Any task type</option>
                      {taskTypes.data?.map((t) => (
                        <option key={t.id} value={t.id}>
                          {t.name}
                        </option>
                      ))}
                    </select>
                  </TableCell>
                  <TableCell>
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      aria-label={`Remove rule: ${describePair(projectLabel(d.projectId), taskTypeLabel(d.taskTypeId))}`}
                      onClick={() => setDrafts(rows.filter((r) => r.key !== d.key))}
                    >
                      Remove
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      {clashing.size > 0 && (
        <p role="alert" className="text-body-sm text-danger">
          Two rules name the same pair. A project and task type can route to one template only.
        </p>
      )}

      <div className="flex gap-2">
        <Button
          type="button"
          disabled={!dirty || clashing.size > 0 || replace.isPending}
          onClick={() =>
            replace.mutate(
              {
                templateId,
                mappings: draftsToRequest(rows),
                etag: mappings.data?.etag ?? null,
              },
              {
                onSuccess: () => {
                  setDrafts(null)
                  toast({ title: 'Routing rules saved' })
                },
                onError: (error: ApiError) =>
                  toast({
                    title: 'Could not save the rules',
                    description: problemDetail(error),
                    variant: 'danger',
                  }),
              },
            )
          }
        >
          Save rules
        </Button>
        {dirty && (
          <Button type="button" variant="ghost" onClick={() => setDrafts(null)}>
            Discard
          </Button>
        )}
      </div>

      <ResolutionChecker
        projects={projects.data?.data ?? []}
        taskTypes={taskTypes.data ?? []}
        mappings={loaded}
      />
    </section>
  )
}

/**
 * "Where would a ticket on this pair actually go?"
 *
 * The list above cannot answer it. A pair may be claimed by a rule on **another**
 * template, or by no rule at all — and in the second case the ticket still goes
 * somewhere, which is exactly the case an Admin needs to see rather than assume.
 * So this asks the server and reports the rung.
 */
function ResolutionChecker({
  projects,
  taskTypes,
  mappings,
}: {
  projects: { id?: number; projectCode?: string; name?: string }[]
  taskTypes: { id?: number; name?: string }[]
  mappings: TemplateMapping[]
}) {
  const [projectId, setProjectId] = React.useState<number | null>(null)
  const [taskTypeId, setTaskTypeId] = React.useState<number | null>(null)
  const resolution = useTemplateResolution(projectId, taskTypeId)

  const answeredHere = resolution.data?.mappingId != null
    && mappings.some((m) => m.id === resolution.data?.mappingId)

  return (
    <div className="space-y-2 rounded-control bg-surface-muted p-3">
      <h4 className="text-body-sm font-medium text-content">Where does a ticket go?</h4>
      <div className="flex flex-wrap gap-2">
        <select
          aria-label="Check project"
          className="h-9 rounded-control border border-border bg-surface px-2 text-sm"
          value={projectId ?? ''}
          onChange={(e) => setProjectId(e.target.value ? Number(e.target.value) : null)}
        >
          <option value="">Any project</option>
          {projects.map((p) => (
            <option key={p.id} value={p.id}>
              {p.projectCode}
            </option>
          ))}
        </select>
        <select
          aria-label="Check task type"
          className="h-9 rounded-control border border-border bg-surface px-2 text-sm"
          value={taskTypeId ?? ''}
          onChange={(e) => setTaskTypeId(e.target.value ? Number(e.target.value) : null)}
        >
          <option value="">Any task type</option>
          {taskTypes.map((t) => (
            <option key={t.id} value={t.id}>
              {t.name}
            </option>
          ))}
        </select>
      </div>

      {resolution.isPending ? (
        <Skeleton className="h-5 w-64" />
      ) : resolution.data?.templateId == null ? (
        <p role="status" className="text-body-sm text-danger">
          Nothing routes this pair — no rule matches and no template is the default.
        </p>
      ) : (
        <p role="status" className="text-body-sm text-content">
          <strong>{resolution.data.templateName}</strong>
          {' — '}
          {describeRung(resolution.data.rung)}
          {!answeredHere && resolution.data.mappingId != null && (
            <span className="text-content-muted"> (a rule on another template)</span>
          )}
        </p>
      )}
    </div>
  )
}

function problemDetail(error: ApiError): string {
  return error.problem?.detail ?? error.problem?.title ?? 'Something went wrong.'
}
