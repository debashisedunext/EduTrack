import * as React from 'react'

import { ApiError } from '@/api/http'
import type { TemplateMapping } from '@/api/generated/model/templateMapping'
import type { WorkflowTemplate } from '@/api/generated/model/workflowTemplate'

import { RibbonStrip } from '@/components/ribbon/RibbonStrip'
import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Input } from '@/components/ui/input'
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

import { useProjects } from '../projects/projectQueries'
import { useStages, useWorkflowTemplates } from '../stages/stageQueries'
import { useTaskTypes } from '../taskTypes/taskTypeQueries'
import { buildPreviewRibbon, previewChain } from './previewRibbon'
import {
  EMPTY_TEMPLATE_FORM,
  describePair,
  describeRung,
  draftsToRequest,
  duplicatePairKeys,
  formToCreate,
  mappingsChanged,
  mappingsToDrafts,
  newMappingDraft,
  templateFormErrors,
  type MappingDraft,
  type TemplateFormState,
} from './templateForm'
import {
  useCreateTemplate,
  useDeleteTemplate,
  useReplaceTemplateMappings,
  useTemplate,
  useTemplateMappings,
  useTemplateResolution,
  useUpdateTemplate,
} from './templateQueries'

/**
 * S-13 tab 3 — workflow templates. B-041.
 *
 * §7.4 asks for three things and this screen is those three things, in the order
 * an Admin does them: **named templates**, **built by picking stages**, then
 * **mapped to project × task type**, with **a live ribbon preview rendering as
 * the Admin edits**.
 *
 * ## "Built by picking stages" is a copy, not a picker
 *
 * The blueprint's phrase implies a catalogue of stage definitions to choose
 * from. There is no such table — B-040 recorded this at length —
 * `workflow_stages.template_id` is `NOT NULL`, so `DEV` on Standard Dev Flow and
 * `DEV` on Support Fast-Track are two rows sharing a code. What "picking" means
 * here is therefore **cloning an existing ribbon and editing it on tab 2**,
 * which is also the operation A-005's own header asks for by name: a template is
 * versioned by copy, never edited in place.
 *
 * The alternative — a second stage editor on this tab — would be a second copy
 * of the `canReturnTo` direction rule, the code-uniqueness rule and the `seq`
 * spacing, all of which tab 2 already holds.
 *
 * ## The preview reuses B-050's ribbon rather than drawing its own
 *
 * `buildPreviewRibbon` turns the stage list into the `Ribbon` shape
 * `RibbonStrip` already draws. A second renderer would drift from §4A.3, and the
 * first time it did the preview would stop being a preview — it would be showing
 * the Admin something other than what a ticket will show. See `previewRibbon.ts`
 * for what a template does not have and why those fields are absent rather than
 * zero.
 *
 * ## The resolution checker is the point of the mapping panel
 *
 * A list of rules does not answer the question an Admin actually has, which is
 * *"where does a Production Bug on the CRM project end up?"* — because the
 * answer may come from a rule on **another template**, or from no rule at all.
 * So the panel carries a pair picker that asks the server, and reports which
 * rung of §4A.9's ladder answered. A pair silently falling through to the
 * default is the one failure mode the configuration has no other way to surface.
 */
export function TemplatesTab() {
  const templates = useWorkflowTemplates()
  const [selectedId, setSelectedId] = React.useState<number | null>(null)
  const [creating, setCreating] = React.useState(false)

  const selected = selectedId ?? templates.data?.[0]?.id ?? null

  if (templates.isPending) {
    return <Skeleton className="h-64 w-full" />
  }
  if (templates.isError) {
    return (
      <EmptyState
        title="Could not load workflow templates"
        description="Reload the page to try again."
      />
    )
  }

  const rows = templates.data ?? []

  return (
    <section
      id="panel-templates"
      role="tabpanel"
      aria-labelledby="tab-templates"
      className="space-y-6 py-4"
    >
      <header className="flex items-start justify-between gap-4">
        <div>
          <h2 className="text-h3 text-content">Workflow templates</h2>
          <p className="max-w-2xl text-body-sm text-content-muted">
            A template is a ribbon. Map it to the projects and task types that should
            follow it — anything unmapped follows the default.
          </p>
        </div>
        <Button onClick={() => setCreating(true)}>New template</Button>
      </header>

      <TemplateList
        templates={rows}
        selectedId={selected}
        onSelect={setSelectedId}
      />

      {selected != null && <TemplateDetail key={selected} templateId={selected} />}

      {creating && (
        <CreateTemplateDialog
          templates={rows}
          onClose={() => setCreating(false)}
          onCreated={(id) => {
            setSelectedId(id)
            setCreating(false)
          }}
        />
      )}
    </section>
  )
}

/**
 * The templates, and which one is being edited.
 *
 * Inactive templates are listed rather than filtered out, on B-040's reasoning:
 * a template is what every historical ticket points at, not a value offered in a
 * picker, and this is the screen on which an inactive one is switched back on. A
 * list that hid them would be a list from which reactivation is impossible.
 */
function TemplateList({
  templates,
  selectedId,
  onSelect,
}: {
  templates: WorkflowTemplate[]
  selectedId: number | null
  onSelect: (id: number) => void
}) {
  if (templates.length === 0) {
    return (
      <EmptyState
        title="No workflow templates"
        description="Create one to define the stages a ticket travels through."
      />
    )
  }

  return (
    <TableContainer>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Template</TableHead>
            <TableHead>Stages</TableHead>
            <TableHead>Status</TableHead>
            <TableHead className="sr-only">Select</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {templates.map((t) => (
            <TableRow key={t.id} data-selected={t.id === selectedId ? 'true' : undefined}>
              <TableCell>
                <div className="font-medium text-content">{t.name}</div>
                {t.description && (
                  <div className="text-caption text-content-muted">{t.description}</div>
                )}
              </TableCell>
              <TableCell>{t.stageCount ?? 0}</TableCell>
              <TableCell>
                <div className="flex flex-wrap gap-1">
                  {t.isDefault && <Chip variant="info">Default</Chip>}
                  {!t.isActive && <Chip variant="neutral">Inactive</Chip>}
                </div>
              </TableCell>
              <TableCell>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  aria-pressed={t.id === selectedId}
                  onClick={() => onSelect(t.id as number)}
                >
                  {t.id === selectedId ? 'Editing' : 'Edit'}
                </Button>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  )
}

/** The selected template: its settings, its live ribbon preview, and its rules. */
function TemplateDetail({ templateId }: { templateId: number }) {
  const detail = useTemplate(templateId)
  const stages = useStages(templateId)

  if (detail.isPending || stages.isPending) {
    return <Skeleton className="h-96 w-full" />
  }
  if (detail.isError || !detail.data) {
    return <EmptyState title="Could not load this template" description="Reload to try again." />
  }

  const template = detail.data.template
  const stageRows = stages.data?.stages ?? []

  return (
    <div className="space-y-6">
      <TemplateSettings
        templateId={templateId}
        template={template}
        etag={detail.data.etag}
      />

      <section aria-labelledby="preview-heading" className="space-y-2 rounded-card border border-line p-4">
        <div className="flex items-baseline justify-between gap-4">
          <h3 id="preview-heading" className="text-h4 text-content">
            Ribbon preview
          </h3>
          <p className="text-caption text-content-muted">
            {stageRows.length} stage{stageRows.length === 1 ? '' : 's'} — edit them on the
            Stages tab
          </p>
        </div>

        {stageRows.length === 0 ? (
          <EmptyState
            title="No stages yet"
            description="This template routes no ticket until it has at least one stage. Add them on the Stages tab."
          />
        ) : (
          <>
            {/* B-050's ribbon, unmodified. See previewRibbon.ts. */}
            <RibbonStrip ribbon={buildPreviewRibbon(stageRows)} />
            <p className="text-caption text-content-muted">{previewChain(stageRows)}</p>
          </>
        )}
      </section>

      <MappingPanel templateId={templateId} templateName={template.name ?? ''} />
    </div>
  )
}

/** Name, description, and the two flags with consequences. */
function TemplateSettings({
  templateId,
  template,
  etag,
}: {
  templateId: number
  template: { name?: string; description?: string | null; isDefault?: boolean
    isActive?: boolean; ticketCount?: number; mappingCount?: number
    isDeletable?: boolean; isDeactivatable?: boolean }
  etag: string | null
}) {
  const update = useUpdateTemplate()
  const remove = useDeleteTemplate()
  const [name, setName] = React.useState(template.name ?? '')
  const [description, setDescription] = React.useState(template.description ?? '')
  const [confirmingDelete, setConfirmingDelete] = React.useState(false)

  const dirty = name !== (template.name ?? '') || description !== (template.description ?? '')

  const save = (patch: Record<string, unknown>) => {
    update.mutate(
      { templateId, data: patch, etag },
      {
        onSuccess: () => toast({ title: 'Template saved' }),
        onError: (error: ApiError) =>
          toast({ title: 'Could not save', description: problemDetail(error), variant: 'danger' }),
      },
    )
  }

  return (
    <section aria-labelledby="settings-heading" className="space-y-4 rounded-card border border-line p-4">
      <h3 id="settings-heading" className="text-h4 text-content">
        Settings
      </h3>

      <div className="grid gap-4 sm:grid-cols-2">
        <div className="flex flex-col gap-1 text-sm">
          <label htmlFor="template-settings-name" className="font-medium text-content">
            Template name
          </label>
          <Input
            id="template-settings-name"
            value={name}
            maxLength={80}
            onChange={(e) => setName(e.target.value)}
          />
        </div>
        <div className="flex flex-col gap-1 text-sm">
          <label htmlFor="template-settings-description" className="font-medium text-content">
            Template description
          </label>
          <Input
            id="template-settings-description"
            value={description}
            maxLength={255}
            onChange={(e) => setDescription(e.target.value)}
          />
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <Button
          type="button"
          disabled={!dirty || update.isPending}
          onClick={() => save({ name: name.trim(), description: description.trim() || null })}
        >
          Save
        </Button>

        {/*
          Both flags are offered as buttons rather than switches, because both
          are refusable and a switch that springs back is worse than a button
          that reports why. The server owns the rules; these only ask.
        */}
        {!template.isDefault && (
          <Button type="button" variant="secondary" onClick={() => save({ isDefault: true })}>
            Make default
          </Button>
        )}

        {template.isActive ? (
          <Button
            type="button"
            variant="secondary"
            disabled={!template.isDeactivatable}
            title={
              template.isDeactivatable
                ? undefined
                : template.isDefault
                  ? 'The default template cannot be deactivated. Make another template the default first.'
                  : `${template.mappingCount ?? 0} routing rule(s) point at this template. Re-point them first.`
            }
            onClick={() => save({ isActive: false })}
          >
            Deactivate
          </Button>
        ) : (
          <Button type="button" variant="secondary" onClick={() => save({ isActive: true })}>
            Reactivate
          </Button>
        )}

        <Button
          type="button"
          variant="danger"
          disabled={!template.isDeletable}
          title={
            template.isDeletable
              ? undefined
              : `${template.ticketCount ?? 0} ticket(s) started on this template and `
                + `${template.mappingCount ?? 0} rule(s) point at it. Deactivate it instead — `
                + 'deleting it would break every historical ribbon.'
          }
          onClick={() => setConfirmingDelete(true)}
        >
          Delete
        </Button>
      </div>

      {confirmingDelete && (
        <Modal open onOpenChange={() => setConfirmingDelete(false)}>
          <ModalContent>
            <ModalHeader>
              <ModalTitle>Delete “{template.name}”?</ModalTitle>
              <ModalDescription>
                Its stages go with it. Nothing has ever run on this template, so no ribbon
                loses its meaning — that is the only case in which this is offered.
              </ModalDescription>
            </ModalHeader>
            <ModalFooter>
              <Button variant="secondary" onClick={() => setConfirmingDelete(false)}>
                Cancel
              </Button>
              <Button
                variant="danger"
                disabled={remove.isPending}
                onClick={() =>
                  remove.mutate(
                    { templateId, etag },
                    {
                      onSuccess: () => {
                        setConfirmingDelete(false)
                        toast({ title: 'Template deleted' })
                      },
                      onError: (error: ApiError) =>
                        toast({
                          title: 'Could not delete',
                          description: problemDetail(error),
                          variant: 'danger',
                        }),
                    },
                  )
                }
              >
                Delete
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>
      )}
    </section>
  )
}

/** §7.4's "mapped to project × task type", plus the checker that makes it legible. */
function MappingPanel({ templateId, templateName }: { templateId: number; templateName: string }) {
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

/** Create, optionally as a copy of an existing ribbon. */
function CreateTemplateDialog({
  templates,
  onClose,
  onCreated,
}: {
  templates: WorkflowTemplate[]
  onClose: () => void
  onCreated: (id: number) => void
}) {
  const create = useCreateTemplate()
  const [form, setForm] = React.useState<TemplateFormState>(EMPTY_TEMPLATE_FORM)
  const errors = templateFormErrors(form)

  return (
    <Modal open onOpenChange={onClose}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>New workflow template</ModalTitle>
          <ModalDescription>
            Start from an existing ribbon and edit it on the Stages tab, or start empty and
            build one there.
          </ModalDescription>
        </ModalHeader>

        <div className="space-y-4">
          <div className="flex flex-col gap-1 text-sm">
            <label htmlFor="template-name" className="font-medium text-content">
              Name
            </label>
            <Input
              id="template-name"
              value={form.name}
              maxLength={80}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              aria-invalid={errors.name ? true : undefined}
            />
            {errors.name && <span className="text-caption text-danger">{errors.name}</span>}
          </div>

          <div className="flex flex-col gap-1 text-sm">
            <label htmlFor="template-description" className="font-medium text-content">
              Description
            </label>
            <Input
              id="template-description"
              value={form.description}
              maxLength={255}
              onChange={(e) => setForm({ ...form, description: e.target.value })}
            />
          </div>

          <div className="flex flex-col gap-1 text-sm">
            <label htmlFor="template-copy-from" className="font-medium text-content">
              Copy stages from
            </label>
            <select
              id="template-copy-from"
              className="h-10 w-full rounded-control border border-border bg-surface px-3 text-sm"
              value={form.copyStagesFromTemplateId ?? ''}
              onChange={(e) =>
                setForm({
                  ...form,
                  copyStagesFromTemplateId: e.target.value ? Number(e.target.value) : null,
                })
              }
            >
              <option value="">Start empty</option>
              {templates.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name} ({t.stageCount ?? 0} stages)
                </option>
              ))}
            </select>
            <span className="text-caption text-content-muted">
              §7.4&rsquo;s &ldquo;built by picking stages&rdquo;. There is no stage catalogue
              to pick from &mdash; a template is versioned by copy, then edited on the Stages
              tab.
            </span>
          </div>
        </div>

        <ModalFooter>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button
            disabled={Object.keys(errors).length > 0 || create.isPending}
            onClick={() =>
              create.mutate(formToCreate(form), {
                onSuccess: (t) => {
                  toast({ title: `${t.name} created` })
                  onCreated(t.id as number)
                },
                onError: (error: ApiError) =>
                  toast({
                    title: 'Could not create the template',
                    description: problemDetail(error),
                    variant: 'danger',
                  }),
              })
            }
          >
            Create
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}

/**
 * The server's sentence, not ours.
 *
 * Every refusal on this screen is about rows the client cannot see — a pair
 * claimed by another template, a count that is not zero — so the problem
 * document's `detail` is the only text that can say what actually happened. A
 * generic "could not save" would drop the one piece of information the Admin
 * needs to act.
 */
function problemDetail(error: ApiError): string {
  return error.problem?.detail ?? error.problem?.title ?? 'Something went wrong.'
}
