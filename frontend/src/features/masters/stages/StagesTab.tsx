import * as React from 'react'

import { ApiError } from '@/api/http'
import type { Stage } from '@/api/generated/model/stage'

import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
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
import { toast } from '@/components/ui/use-toast'

import { useRoles } from '../roles/roleQueries'
import {
  EMPTY_STAGE_FORM,
  forwardReturnPaths,
  formToCreate,
  formToPatch,
  moveStage,
  orderChanged,
  retireBlockers,
  returnTargetOptions,
  stageFormErrors,
  stageToForm,
  type StageFormState,
} from './stageForm'
import {
  useCreateStage,
  useDeleteStage,
  useReorderStages,
  useSetStageDeprecation,
  useStage,
  useStages,
  useUpdateStage,
  useWorkflowTemplates,
} from './stageQueries'

/**
 * S-13 tab 2 — the ribbon's stages. B-040.
 *
 * ## A template selector, which §7.4 does not describe
 *
 * The blueprint reads as though tab 2 edits one flat list of stages and tab 3
 * assembles templates from them. `workflow_stages.template_id` is `NOT NULL`
 * behind a cascading foreign key, so there is no stage outside a template and no
 * catalogue table for one to live in — `DEV` on Standard Dev Flow and `DEV` on
 * Support Fast-Track are two independent rows. CLAUDE.md settles which side wins:
 * PLAN.md is the authority on implementation, and A-005 is what it produced.
 *
 * ## Drag, and a keyboard path that is not a lesser one
 *
 * WCAG AA is not optional here, and "drag to reorder" is the control that most
 * often ships without it. Every row carries Move up / Move down buttons that do
 * exactly what the drag does, the list announces each move through an
 * `aria-live` region, and the pointer path is plain HTML5 drag events — no new
 * dependency for a list of eight.
 *
 * ## Retired, not removed — B-042
 *
 * §7.4: *"Stages used by live tickets can only be deprecated, never deleted —
 * otherwise historical ribbons would break."* So the row action is **Deprecate**,
 * and Delete appears only on a stage the server has said is deletable — nothing
 * has ever entered it, nothing stands in it, nothing live returns to it, and it
 * is not the template's last live stage.
 *
 * The two are deliberately not one control with a confirmation. They are
 * different operations with different consequences, and a screen that offered
 * "Remove" and then explained which of the two had happened would be describing
 * the rule after acting on it rather than before.
 *
 * A deprecated row stays in the list, in its place. It has to: it holds a `seq`
 * the reorder sends back, and it is what every ribbon that has been through it
 * still renders. Hiding it would make the ribbon an Admin edits disagree with the
 * ribbon a ticket shows.
 *
 * ## The order is staged, then saved
 *
 * Dragging does not write. The reorder is a whole-set `PUT` with an `If-Match`,
 * so a per-drag save would fire eight requests to move one row four places and
 * each would move the tag under the next. The list holds the proposed order and
 * one Save commits it — which is also what makes the forward-return-path warning
 * possible before anything is written.
 */
export function StagesTab() {
  const templates = useWorkflowTemplates()
  const [templateId, setTemplateId] = React.useState<number | null>(null)

  const selected = templateId ?? templates.data?.[0]?.id ?? null
  const template = templates.data?.find((t) => t.id === selected) ?? null

  return (
    <section
      id="panel-stages"
      role="tabpanel"
      aria-labelledby="tab-stages"
      className="flex flex-col gap-6 py-6"
    >
      <div className="flex flex-wrap items-end justify-between gap-4">
        <label className="flex flex-col gap-1 text-sm">
          <span className="font-medium text-content">Template</span>
          <select
            className="min-w-64 rounded-md border border-line bg-surface px-3 py-2 text-sm"
            value={selected ?? ''}
            aria-label="Workflow template"
            onChange={(e) => setTemplateId(Number(e.target.value))}
          >
            {(templates.data ?? []).map((t) => (
              <option key={t.id} value={t.id}>
                {t.name}
                {t.isDefault ? ' — default' : ''}
                {t.isActive ? '' : ' (inactive)'}
              </option>
            ))}
          </select>
        </label>
        {template?.description && (
          <p className="max-w-xl text-sm text-content-muted">{template.description}</p>
        )}
      </div>

      {templates.isPending && <Skeleton className="h-64 w-full" />}
      {selected != null && <StageList templateId={selected} />}
    </section>
  )
}

function StageList({ templateId }: { templateId: number }) {
  const { data, isPending } = useStages(templateId)
  const reorder = useReorderStages()
  const deprecation = useSetStageDeprecation()

  const [ordered, setOrdered] = React.useState<Stage[] | null>(null)
  const [announcement, setAnnouncement] = React.useState('')
  const [dialog, setDialog] = React.useState<{ stageId?: number } | null>(null)
  const [dragFrom, setDragFrom] = React.useState<number | null>(null)
  const [retiring, setRetiring] = React.useState<Stage | null>(null)
  const [removing, setRemoving] = React.useState<Stage | null>(null)

  // The proposed order is dropped whenever the server's changes, rather than
  // merged. A merge would have to guess whether a row that moved underneath was
  // the same drag arriving back or somebody else's edit, and guessing wrong
  // silently reinstates an order nobody chose.
  React.useEffect(() => {
    setOrdered(null)
  }, [data?.stages])

  const stages = ordered ?? data?.stages ?? []
  const dirty = data ? orderChanged(stages, data.stages) : false
  const broken = dirty ? forwardReturnPaths(stages) : []

  const move = (from: number, to: number) => {
    const next = moveStage(stages, from, to)
    if (next === stages) return
    setOrdered(next)
    setAnnouncement(`${stages[from].displayName} moved to position ${to + 1} of ${next.length}.`)
  }

  const restore = async (stage: Stage) => {
    try {
      await deprecation.mutateAsync({ templateId, stageId: stage.id, isDeprecated: false })
      setAnnouncement(`${stage.displayName} restored.`)
      toast({ title: `${stage.displayName} is live again` })
    } catch (error) {
      toast({
        title: 'That could not be restored',
        description: problemDetail(error) ?? 'Reload and try again.',
        variant: 'danger',
      })
    }
  }

  const save = async () => {
    try {
      await reorder.mutateAsync({
        templateId,
        stageIds: stages.map((s) => s.id),
        etag: data?.etag ?? null,
      })
      setOrdered(null)
      toast({ title: 'Ribbon reordered' })
    } catch (error) {
      toast({
        title: 'That order was refused',
        description: problemDetail(error) ?? 'Reload and try again.',
        variant: 'danger',
      })
    }
  }

  if (isPending) return <Skeleton className="h-96 w-full" />

  const liveTickets = stages.reduce((sum, s) => sum + s.openTicketCount, 0)

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h3 className="text-base font-semibold text-content">
          Stages <span className="font-normal text-content-muted">({stages.length})</span>
        </h3>
        <Button size="sm" onClick={() => setDialog({})}>
          Add stage
        </Button>
      </div>

      {/* Every move is announced, so the keyboard path is not a silent one. */}
      <p aria-live="polite" className="sr-only">
        {announcement}
      </p>

      <ol className="flex flex-col gap-2">
        {stages.map((stage, index) => (
          <li
            key={stage.id}
            draggable
            onDragStart={() => setDragFrom(index)}
            onDragOver={(e) => e.preventDefault()}
            onDrop={() => {
              if (dragFrom != null) move(dragFrom, index)
              setDragFrom(null)
            }}
            className={`flex flex-wrap items-center gap-3 rounded-md border border-line px-3 py-2 ${
              // Retired rows stay in place and stay legible. Dimming the whole
              // row would take the contrast below AA on text an Admin still has
              // to read to decide whether to restore it, so the surface changes
              // and the type does not.
              stage.isDeprecated ? 'bg-surface-muted' : 'bg-surface'
            }`}
          >
            <span
              aria-hidden="true"
              className="cursor-grab select-none text-content-muted"
              title="Drag to reorder"
            >
              ⠿
            </span>
            <span className="w-6 text-sm tabular-nums text-content-muted">{index + 1}</span>

            <span className="font-mono text-sm text-content">{stage.stageCode}</span>
            <span className="text-sm text-content">{stage.displayName}</span>
            <Chip>{stage.ownerRole}</Chip>
            {stage.isDeprecated && <Chip variant="warning">Deprecated</Chip>}

            <span className="text-sm text-content-muted">
              {stage.slaHours == null ? 'No stage SLA' : `${stage.slaHours} working h`}
            </span>
            {stage.isOptional && <Chip>Optional</Chip>}
            {stage.canReturnTo.length > 0 && (
              <span className="text-xs text-content-muted">
                returns to {stage.canReturnTo.join(', ')}
              </span>
            )}
            {stage.openTicketCount > 0 && (
              <span className="text-xs text-content-muted tabular-nums">
                {stage.openTicketCount} here now
              </span>
            )}

            <span className="ml-auto flex items-center gap-1">
              <Button
                variant="ghost"
                size="sm"
                disabled={index === 0}
                aria-label={`Move ${stage.displayName} up`}
                onClick={() => move(index, index - 1)}
              >
                ↑
              </Button>
              <Button
                variant="ghost"
                size="sm"
                disabled={index === stages.length - 1}
                aria-label={`Move ${stage.displayName} down`}
                onClick={() => move(index, index + 1)}
              >
                ↓
              </Button>
              <Button
                variant="ghost"
                size="sm"
                aria-label={`Edit ${stage.displayName}`}
                onClick={() => setDialog({ stageId: stage.id })}
              >
                Edit
              </Button>
              {stage.isDeprecated ? (
                // Restoring is unconditional on the server, so there is nothing
                // to confirm and no consequence to state before the click.
                <Button
                  variant="ghost"
                  size="sm"
                  aria-label={`Restore ${stage.displayName}`}
                  onClick={() => restore(stage)}
                >
                  Restore
                </Button>
              ) : (
                <Button
                  variant="ghost"
                  size="sm"
                  aria-label={`Deprecate ${stage.displayName}`}
                  onClick={() => setRetiring(stage)}
                >
                  Deprecate
                </Button>
              )}
              {/* Only where the server has already said it would allow it — the
                  flag is computed from three facts about other rows, so a screen
                  deriving it from the array it holds would offer a button that
                  409s. */}
              {stage.isDeletable && (
                <Button
                  variant="ghost"
                  size="sm"
                  aria-label={`Delete ${stage.displayName}`}
                  onClick={() => setRemoving(stage)}
                >
                  Delete
                </Button>
              )}
            </span>
          </li>
        ))}
      </ol>

      {dirty && (
        <div className="flex flex-col gap-2 rounded-md border border-line bg-surface-muted p-3">
          {broken.length > 0 ? (
            // The server refuses this too. Saying so here means the Admin reads
            // which two stages are the problem instead of a 409 naming a rule.
            <p role="alert" className="text-sm text-danger">
              That order would leave {broken.join(', ')} pointing forwards. A return target
              is a backward target — clear it on the stage first, or move the other row.
            </p>
          ) : (
            liveTickets > 0 && (
              // Stated before the click, because it is the consequence nobody
              // asks about: a template is edited in place until B-043's designer
              // can clone it, so this reorder redraws a ribbon that is in flight.
              <p className="text-sm text-content-muted">
                {liveTickets} ticket{liveTickets === 1 ? '' : 's'} on this template are in a
                stage right now. Saving changes the ribbon they render.
              </p>
            )
          )}
          <div className="flex gap-2">
            <Button size="sm" onClick={save} disabled={broken.length > 0 || reorder.isPending}>
              Save order
            </Button>
            <Button variant="ghost" size="sm" onClick={() => setOrdered(null)}>
              Discard
            </Button>
          </div>
        </div>
      )}

      {dialog && (
        <StageDialog
          templateId={templateId}
          stageId={dialog.stageId}
          stages={data?.stages ?? []}
          onClose={() => setDialog(null)}
        />
      )}

      {retiring && (
        <DeprecateDialog
          templateId={templateId}
          stage={retiring}
          stages={data?.stages ?? []}
          onDone={(message) => setAnnouncement(message)}
          onClose={() => setRetiring(null)}
        />
      )}

      {removing && (
        <DeleteDialog
          templateId={templateId}
          stage={removing}
          onDone={(message) => setAnnouncement(message)}
          onClose={() => setRemoving(null)}
        />
      )}
    </div>
  )
}

/**
 * The retire confirmation — §7.4's "deprecated, never deleted", stated before it
 * happens rather than reported after.
 *
 * The blockers are named **before** the request, the way the reorder's forward
 * return paths are, because "that stage is still a return target" is only useful
 * with the stage's name in it. The server checks both again; this exists so an
 * Admin can act on the answer rather than read a 409.
 */
function DeprecateDialog({
  templateId,
  stage,
  stages,
  onDone,
  onClose,
}: {
  templateId: number
  stage: Stage
  stages: Stage[]
  onDone: (message: string) => void
  onClose: () => void
}) {
  const deprecation = useSetStageDeprecation()
  const [serverError, setServerError] = React.useState<string | null>(null)
  const blocked = retireBlockers(stage, stages)

  const confirm = async () => {
    try {
      await deprecation.mutateAsync({ templateId, stageId: stage.id, isDeprecated: true })
      onDone(`${stage.displayName} deprecated.`)
      toast({
        title: `${stage.displayName} deprecated`,
        description: 'It stays on every ribbon it is already on and accepts nothing new.',
      })
      onClose()
    } catch (error) {
      setServerError(problemDetail(error) ?? 'Reload and try again.')
    }
  }

  return (
    <Modal open onOpenChange={(next) => { if (!next) onClose() }}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>Deprecate {stage.displayName}?</ModalTitle>
          <ModalDescription>
            A deprecated stage keeps rendering on every ribbon it is already on and accepts
            no new tickets. It is never deleted, because its code is written into every
            journey that has been through it.
          </ModalDescription>
        </ModalHeader>

        <div className="flex flex-col gap-3 py-4 text-sm">
          {blocked?.reason === 'last-live' && (
            <p role="alert" className="text-danger">
              {stage.stageCode} is the last stage on this template that is still live.
              Retiring it would leave a workflow that can route no ticket at all. Add or
              restore another stage first.
            </p>
          )}
          {blocked?.reason === 'return-target' && (
            <p role="alert" className="text-danger">
              {blocked.arrows.join(', ')} would point at a retired stage. A return target is
              a move the workflow will honour, so clear it on that stage first.
            </p>
          )}
          {!blocked && stage.openTicketCount > 0 && (
            // Stated rather than refused. §7.4's clause is about stages used by
            // live tickets, so this is the case the rule exists for — those
            // tickets keep their segment and keep their way out of it.
            <p className="text-content-muted">
              {stage.openTicketCount} ticket{stage.openTicketCount === 1 ? ' is' : 's are'} standing
              in this stage right now. They keep this segment and can still move on; what stops
              is anything new entering it.
            </p>
          )}
          {!blocked && stage.transitionCount > 0 && (
            <p className="text-content-muted">
              {stage.transitionCount} ribbon segment
              {stage.transitionCount === 1 ? '' : 's'} across this template&rsquo;s history name
              this stage. They go on rendering exactly as they do now.
            </p>
          )}
          {serverError && (
            <p role="alert" className="text-danger">
              {serverError}
            </p>
          )}
        </div>

        <ModalFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button
            type="button"
            onClick={confirm}
            disabled={blocked != null || deprecation.isPending}
          >
            Deprecate
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}

/**
 * The delete confirmation, offered only where `isDeletable` is true.
 *
 * It reads the stage's own tag rather than the list's, because that is what the
 * `DELETE` preconditions on — and the precondition is doing real work here: the
 * server's whole guard is that both usage counts are zero, and both are inside
 * that tag. A ticket entering the stage while this dialog is open has to lose.
 */
function DeleteDialog({
  templateId,
  stage,
  onDone,
  onClose,
}: {
  templateId: number
  stage: Stage
  onDone: (message: string) => void
  onClose: () => void
}) {
  const { data: loaded } = useStage(templateId, stage.id)
  const remove = useDeleteStage()
  const [serverError, setServerError] = React.useState<string | null>(null)

  const confirm = async () => {
    try {
      await remove.mutateAsync({
        templateId,
        stageId: stage.id,
        etag: loaded?.etag ?? null,
      })
      onDone(`${stage.displayName} deleted.`)
      toast({ title: `${stage.displayName} deleted` })
      onClose()
    } catch (error) {
      setServerError(problemDetail(error) ?? 'Reload and try again.')
    }
  }

  return (
    <Modal open onOpenChange={(next) => { if (!next) onClose() }}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>Delete {stage.displayName}?</ModalTitle>
          <ModalDescription>
            Nothing has ever entered this stage, so there is no journey to break. Once a
            ticket passes through it this stops being possible — a used stage is deprecated
            instead, and never removed.
          </ModalDescription>
        </ModalHeader>

        {serverError && (
          <p role="alert" className="py-4 text-sm text-danger">
            {serverError}
          </p>
        )}

        <ModalFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="button" variant="danger" onClick={confirm} disabled={remove.isPending}>
            Delete
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}

/**
 * The create and edit dialog.
 *
 * One component for both, as S-12's and S-13 tab 1's are: the fields are
 * identical and the only differences are where the initial values come from,
 * which mutation runs, and whether the code may still be typed.
 */
function StageDialog({
  templateId,
  stageId,
  stages,
  onClose,
}: {
  templateId: number
  stageId?: number
  stages: Stage[]
  onClose: () => void
}) {
  const editing = stageId != null
  const { data: loaded, isPending } = useStage(templateId, stageId ?? null)
  const roles = useRoles({ isActive: true })
  const create = useCreateStage()
  const update = useUpdateStage()

  const [values, setValues] = React.useState<StageFormState>(EMPTY_STAGE_FORM)
  const [serverErrors, setServerErrors] = React.useState<Record<string, string>>({})

  React.useEffect(() => {
    if (loaded) setValues(stageToForm(loaded.stage))
  }, [loaded])

  // `isCodeEditable` is the server's answer, not a rule restated here. A second
  // copy would be a second thing to keep true, and the failure mode is a form
  // that greys out a field the server would have accepted.
  const codeEditable = loaded?.stage.isCodeEditable ?? true
  const errors = { ...stageFormErrors(values, { codeEditable }), ...serverErrors }

  // `values.canReturnTo` rather than the loaded row's, so a target the Admin has
  // just unticked disappears from the list only on the next open — unticking it
  // and having the row vanish mid-edit is how a checkbox becomes unclearable.
  const targets = returnTargetOptions(
    stages, loaded?.stage.position ?? null, values.canReturnTo,
  )

  const set = <K extends keyof StageFormState>(key: K, value: StageFormState[K]) => {
    setValues((v) => ({ ...v, [key]: value }))
    setServerErrors((e) =>
      Object.fromEntries(Object.entries(e).filter(([field]) => field !== key)),
    )
  }

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (Object.keys(stageFormErrors(values, { codeEditable })).length > 0) return

    try {
      if (editing && loaded) {
        await update.mutateAsync({
          templateId,
          stageId: stageId!,
          data: formToPatch(values, loaded.stage),
          etag: loaded.etag,
        })
        toast({ title: 'Stage saved' })
      } else {
        await create.mutateAsync({ templateId, data: formToCreate(values) })
        toast({
          title: 'Stage added',
          description: 'It is at the end of the ribbon. Drag it where it belongs.',
        })
      }
      onClose()
    } catch (error) {
      setServerErrors(fieldErrors(error))
    }
  }

  return (
    <Modal open onOpenChange={(next) => { if (!next) onClose() }}>
      <ModalContent>
        <form onSubmit={submit}>
          <ModalHeader>
            <ModalTitle>{editing ? 'Edit stage' : 'Add stage'}</ModalTitle>
            <ModalDescription>
              The code is stored as plain text on every ribbon segment a ticket has ever
              passed through, so it stops being editable the moment one exists. The display
              name can always be changed.
            </ModalDescription>
          </ModalHeader>

          {isPending && editing ? (
            <Skeleton className="h-80 w-full" />
          ) : (
            <div className="flex flex-col gap-4 py-4">
              <div className="flex flex-col gap-1 text-sm">
                <label htmlFor="stage-code" className="font-medium text-content">
                  Code
                </label>
                <Input
                  id="stage-code"
                  value={values.stageCode}
                  maxLength={20}
                  disabled={!codeEditable}
                  aria-invalid={errors.stageCode ? true : undefined}
                  aria-describedby="stage-code-help"
                  onChange={(e) => set('stageCode', e.target.value.toUpperCase())}
                />
                <span id="stage-code-help" className="flex flex-col gap-1">
                  {!codeEditable && loaded && (
                    <span className="text-xs text-content-muted">
                      Used by {loaded.stage.transitionCount} ribbon segment
                      {loaded.stage.transitionCount === 1 ? '' : 's'} and{' '}
                      {loaded.stage.openTicketCount} open ticket
                      {loaded.stage.openTicketCount === 1 ? '' : 's'}. Renaming it would leave
                      their journeys unresolvable and stop the stage-SLA scan matching them.
                    </span>
                  )}
                  {errors.stageCode && (
                    <span className="text-xs text-danger">{errors.stageCode}</span>
                  )}
                </span>
              </div>

              <div className="flex flex-col gap-1 text-sm">
                <label htmlFor="stage-display-name" className="font-medium text-content">
                  Display name
                </label>
                <Input
                  id="stage-display-name"
                  value={values.displayName}
                  maxLength={50}
                  aria-invalid={errors.displayName ? true : undefined}
                  aria-describedby="stage-display-name-help"
                  onChange={(e) => set('displayName', e.target.value)}
                />
                <span id="stage-display-name-help">
                  {errors.displayName && (
                    <span className="text-xs text-danger">{errors.displayName}</span>
                  )}
                </span>
              </div>

              <div className="flex flex-col gap-1 text-sm">
                <label htmlFor="stage-owner-role" className="font-medium text-content">
                  Owner role
                </label>
                <select
                  id="stage-owner-role"
                  className="rounded-md border border-line bg-surface px-3 py-2 text-sm"
                  value={values.ownerRole}
                  aria-describedby="stage-owner-role-help"
                  onChange={(e) => set('ownerRole', e.target.value)}
                >
                  <option value="">Pick a role…</option>
                  {(roles.data ?? []).map((role) => (
                    <option key={role.id} value={role.code}>
                      {role.name}
                    </option>
                  ))}
                </select>
                <span id="stage-owner-role-help" className="flex flex-col gap-1">
                  <span className="text-xs text-content-muted">
                    Only this role, plus PM and Admin, may move a ticket on from this stage.
                  </span>
                  {errors.ownerRole && (
                    <span className="text-xs text-danger">{errors.ownerRole}</span>
                  )}
                </span>
              </div>

              <div className="flex flex-col gap-1 text-sm">
                <label htmlFor="stage-sla-hours" className="font-medium text-content">
                  Stage SLA (working hours)
                </label>
                <Input
                  id="stage-sla-hours"
                  value={values.slaHours}
                  inputMode="decimal"
                  aria-invalid={errors.slaHours ? true : undefined}
                  aria-describedby="stage-sla-hours-help"
                  onChange={(e) => set('slaHours', e.target.value)}
                />
                <span id="stage-sla-hours-help" className="flex flex-col gap-1">
                  <span className="text-xs text-content-muted">
                    Working hours — weekends, holidays and leave are already excluded. Leave
                    it empty where the figure comes from the project’s SLA matrix instead, as
                    Development’s does.
                  </span>
                  {errors.slaHours && (
                    <span className="text-xs text-danger">{errors.slaHours}</span>
                  )}
                </span>
              </div>

              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={values.isOptional}
                  onChange={(e) => set('isOptional', e.target.checked)}
                />
                <span className="text-content">Optional — may be skipped with a reason</span>
              </label>

              <fieldset className="flex flex-col gap-1 text-sm">
                <legend className="font-medium text-content">Can return to</legend>
                <span className="text-xs text-content-muted">
                  Backward targets only — the stages before this one. Moving forward is an
                  ordinary handoff, not a return.
                </span>
                {targets.length === 0 ? (
                  <span className="text-xs text-content-muted">
                    Nothing precedes this stage yet.
                  </span>
                ) : (
                  targets.map((target) => (
                    <label key={target.id} className="flex items-center gap-2">
                      <input
                        type="checkbox"
                        checked={values.canReturnTo.includes(target.stageCode)}
                        onChange={(e) =>
                          set(
                            'canReturnTo',
                            e.target.checked
                              ? [...values.canReturnTo, target.stageCode]
                              : values.canReturnTo.filter((c) => c !== target.stageCode),
                          )
                        }
                      />
                      <span className="text-content">
                        {target.displayName}{' '}
                        <span className="font-mono text-xs text-content-muted">
                          {target.stageCode}
                        </span>
                        {target.isDeprecated && (
                          // Only ever reachable on an arrow authored before the
                          // target was retired. Labelled rather than hidden, so
                          // clearing it is a decision instead of a side effect.
                          <span className="ml-1 text-xs text-content-muted">
                            — deprecated, clear this
                          </span>
                        )}
                      </span>
                    </label>
                  ))
                )}
                {errors.canReturnTo && (
                  <span className="text-xs text-danger">{errors.canReturnTo}</span>
                )}
              </fieldset>

              <div className="flex flex-col gap-1 text-sm">
                <label htmlFor="stage-icon" className="font-medium text-content">
                  Icon
                </label>
                <Input
                  id="stage-icon"
                  value={values.icon}
                  maxLength={30}
                  placeholder="code-2"
                  aria-describedby="stage-icon-help"
                  onChange={(e) => set('icon', e.target.value)}
                />
                <span id="stage-icon-help">
                  {errors.icon && <span className="text-xs text-danger">{errors.icon}</span>}
                </span>
              </div>
            </div>
          )}

          <ModalFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={create.isPending || update.isPending}>
              Save
            </Button>
          </ModalFooter>
        </form>
      </ModalContent>
    </Modal>
  )
}

function fieldErrors(error: unknown): Record<string, string> {
  if (!(error instanceof ApiError)) return {}
  const errors = (error.problem as { errors?: Record<string, string[]> }).errors
  if (!errors) return {}
  return Object.fromEntries(
    Object.entries(errors).map(([field, messages]) => [field, messages[0] ?? '']),
  )
}

function problemDetail(error: unknown): string | null {
  if (!(error instanceof ApiError)) return null
  return (error.problem as { detail?: string }).detail ?? null
}
