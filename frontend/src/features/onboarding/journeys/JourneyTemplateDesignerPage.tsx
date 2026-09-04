import * as React from 'react'
import { useNavigate, useParams } from 'react-router-dom'

import { ApiError } from '@/api/http'
import type { ObJourneyTemplateDetail } from '@/api/generated/model/obJourneyTemplateDetail'
import type { ObJourneyTemplateStep } from '@/api/generated/model/obJourneyTemplateStep'

import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { toast } from '@/components/ui/use-toast'

import {
  useAddJourneyTemplateStep,
  useAddJourneyTemplateStepDoc,
  useAddJourneyTemplateStepItem,
  useBeginJourneyTemplateRevision,
  useJourneyTemplate,
  usePublishJourneyTemplate,
  useRemoveJourneyTemplateStep,
  useRemoveJourneyTemplateStepDoc,
  useRemoveJourneyTemplateStepItem,
  useReorderJourneyTemplateSteps,
} from './journeyTemplateQueries'

/**
 * C-102 · OB-07's journey template designer.
 *
 * <p>Blueprint §4A/§5.5-5.8: the Module Service catalogue an OB Admin builds
 * once per product and every client's journey is instantiated from —
 * services in order, each with a Task List and a required-document
 * checklist, some running in parallel.
 *
 * <h2>The one rule that shapes everything on this page</h2>
 *
 * <p>{@code ObJourneyTemplateService}'s own header: <em>"an admin edit never
 * mutates an in-flight journey."</em> A template version is editable only
 * while {@code publishedAt == null} — a draft. The moment it publishes it is
 * frozen for the rest of its life, active or retired, and the only way
 * forward is {@code beginRevision}, which clones it into a new draft one
 * version higher. So every write control on this page — Add step, Remove,
 * the item/doc checklists, the reorder — is conditioned on
 * {@code editable}, not on {@code isActive}: a retired version is exactly as
 * frozen as the currently active one.
 *
 * <h2>Most writes go immediately; only the order is staged</h2>
 *
 * <p>The same call {@code WorkflowDesignerPage} (B-043) makes for the
 * identical reason: add/remove a step, add/remove an item or doc, publish,
 * begin revision — each is one route with no rollback between it and the
 * next, so holding them behind one Save would be a batch of independent
 * writes with nothing to undo them together. Reordering is different:
 * {@code PUT .../steps/order} replaces the whole set under one
 * {@code If-Match}, so it is staged locally and sent once, on confirm.
 *
 * <h2>Not a canvas</h2>
 *
 * <p>This is a linear step list, not a graph — keyboard-operable Move up /
 * Move down buttons are the whole reorder affordance, on
 * {@code WorkflowDesignerPage}'s own "keyboard parity, not a keyboard
 * fallback" philosophy. Existing step *fields* are read-only: the backend
 * exposes add and remove on a step, never an edit, so there is nothing here
 * for a field-level edit to write to.
 *
 * <h2>What is deliberately not enforced here</h2>
 *
 * <p>{@code dependsOnStepId} naming an *earlier* step in the template is
 * C-119's rule, not this screen's — the contract says so explicitly, and
 * inventing that validation client-side would make this page's opinion
 * disagree with the server's the day C-119 actually lands with a different
 * one.
 */
export function JourneyTemplateDesignerPage() {
  const params = useParams()
  const templateId = Number(params.templateId)

  const query = useJourneyTemplate(Number.isFinite(templateId) ? templateId : null)

  if (!Number.isFinite(templateId)) {
    return (
      <EmptyState
        title="No such journey template"
        description="Pick one from the onboarding product catalogue."
      />
    )
  }

  if (query.isPending) {
    return <Skeleton className="h-screen w-full" />
  }
  if (query.isError || !query.data) {
    return (
      <EmptyState
        title="Could not load this journey template"
        description="Reload the page to try again."
      />
    )
  }

  return (
    <Designer
      key={templateId}
      templateId={templateId}
      detail={query.data.detail}
      etag={query.data.etag}
    />
  )
}

function Designer({
  templateId,
  detail,
  etag,
}: {
  templateId: number
  detail: ObJourneyTemplateDetail
  etag: string | null
}) {
  const navigate = useNavigate()
  const beginRevision = useBeginJourneyTemplateRevision()
  const publish = usePublishJourneyTemplate()
  const removeStep = useRemoveJourneyTemplateStep()
  const reorder = useReorderJourneyTemplateSteps()

  const [ordered, setOrdered] = React.useState<ObJourneyTemplateStep[] | null>(null)
  const [announcement, setAnnouncement] = React.useState('')
  const [addingStep, setAddingStep] = React.useState(false)

  // Dropped, not merged, whenever the server's own steps change — the reason
  // `WorkflowDesignerPage` gives: a merge would have to guess whether a row
  // that moved underneath is this drag arriving back or somebody else's edit.
  React.useEffect(() => {
    setOrdered(null)
  }, [detail.steps])

  const editable = detail.publishedAt == null
  const state = editable ? 'Draft' : detail.isActive ? 'Active' : 'Retired'
  const steps = ordered ?? detail.steps
  const dirty = editable && orderChanged(steps, detail.steps)

  const move = (from: number, to: number) => {
    const next = moveItem(steps, from, to)
    if (next === steps) return
    setOrdered(next)
    setAnnouncement(`${steps[from].name} moved to position ${to + 1} of ${next.length}.`)
  }

  const saveOrder = async () => {
    try {
      await reorder.mutateAsync({ templateId, stepIds: steps.map((s) => s.id), etag })
      setOrdered(null)
      toast({ title: 'Step order saved' })
    } catch (error) {
      toast({
        title: 'That order was refused',
        description: problemDetail(error),
        variant: 'danger',
      })
    }
  }

  const doBeginRevision = async () => {
    try {
      const draft = await beginRevision.mutateAsync({ templateId })
      toast({ title: `Revision v${draft.version} created` })
      navigate(`/onboarding/journey-templates/${draft.id}`)
    } catch (error) {
      toast({
        title: 'Could not begin a revision',
        description: problemDetail(error),
        variant: 'danger',
      })
    }
  }

  const doPublish = async () => {
    try {
      await publish.mutateAsync({ templateId })
      toast({ title: `${detail.name} published` })
    } catch (error) {
      toast({ title: 'Could not publish', description: problemDetail(error), variant: 'danger' })
    }
  }

  const doRemoveStep = async (step: ObJourneyTemplateStep) => {
    try {
      await removeStep.mutateAsync({ templateId, stepId: step.id })
      toast({ title: `${step.name} removed` })
    } catch (error) {
      const dependents = dependentStepIds(error)
      if (dependents.length > 0) {
        const names = dependents.map(
          (id) => detail.steps.find((s) => s.id === id)?.name ?? `step #${id}`,
        )
        toast({
          title: `${step.name} still has dependents`,
          description: `Re-point ${names.join(', ')} to something else first.`,
          variant: 'danger',
        })
      } else {
        toast({
          title: 'Could not remove that step',
          description: problemDetail(error),
          variant: 'danger',
        })
      }
    }
  }

  return (
    <div className="flex flex-col gap-6 p-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-h2 text-content">{detail.name}</h1>
          <p className="text-body-sm text-content-muted">Version {detail.version}</p>
          <div className="mt-2 flex flex-wrap gap-2">
            <Chip>{state}</Chip>
          </div>
        </div>

        <div className="flex flex-wrap gap-2">
          {/* Shown only when this version is the product's currently active one — the
              401/409 the route documents on any other version is not a case a screen
              offering the button on a retired or draft row should ever produce. */}
          {detail.isActive && (
            <Button
              type="button"
              variant="secondary"
              disabled={beginRevision.isPending}
              onClick={doBeginRevision}
            >
              Begin revision
            </Button>
          )}
          {editable && (
            <Button
              type="button"
              disabled={publish.isPending || steps.length === 0}
              title={steps.length === 0 ? 'Add at least one step before publishing' : undefined}
              onClick={doPublish}
            >
              Publish
            </Button>
          )}
        </div>
      </header>

      <section
        aria-labelledby="designer-steps-heading"
        className="flex flex-col gap-4 rounded-card border border-line p-4"
      >
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 id="designer-steps-heading" className="text-h4 text-content">
            Steps ({steps.length})
          </h2>
          {editable && !addingStep && (
            <Button type="button" size="sm" onClick={() => setAddingStep(true)}>
              Add step
            </Button>
          )}
        </div>

        <p role="status" aria-live="polite" className="sr-only">
          {announcement}
        </p>

        {steps.length === 0 ? (
          <EmptyState
            title="No steps yet"
            description="Add the first service this journey walks a client through."
          />
        ) : (
          <ol className="flex flex-col gap-3">
            {steps.map((step, index) => (
              <StepRow
                key={step.id}
                templateId={templateId}
                step={step}
                index={index}
                total={steps.length}
                editable={editable}
                allSteps={detail.steps}
                onMove={move}
                onRemove={() => doRemoveStep(step)}
              />
            ))}
          </ol>
        )}

        {dirty && (
          <div className="flex flex-wrap items-center gap-2 rounded-control border border-line bg-surface-muted p-3">
            <p className="text-body-sm text-content-muted">
              The order above is not saved yet.
            </p>
            <div className="ml-auto flex gap-2">
              <Button type="button" size="sm" disabled={reorder.isPending} onClick={saveOrder}>
                Save order
              </Button>
              <Button type="button" size="sm" variant="ghost" onClick={() => setOrdered(null)}>
                Discard
              </Button>
            </div>
          </div>
        )}

        {addingStep && editable && (
          <AddStepForm
            templateId={templateId}
            steps={detail.steps}
            onClose={() => setAddingStep(false)}
          />
        )}
      </section>

      <ParallelGroupsPanel groups={detail.parallelGroups} steps={detail.steps} />
    </div>
  )
}

function StepRow({
  templateId,
  step,
  index,
  total,
  editable,
  allSteps,
  onMove,
  onRemove,
}: {
  templateId: number
  step: ObJourneyTemplateStep
  index: number
  total: number
  editable: boolean
  allSteps: ObJourneyTemplateStep[]
  onMove: (from: number, to: number) => void
  onRemove: () => void
}) {
  const dependsOn = step.dependsOnStepId == null
    ? 'Parallel from journey start'
    : `Runs after: ${allSteps.find((s) => s.id === step.dependsOnStepId)?.name ?? `step #${step.dependsOnStepId}`}`

  return (
    <li className="flex flex-col gap-3 rounded-control border border-line p-3">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <p className="text-body font-medium text-content">
            {index + 1}. {step.name}
          </p>
          {step.description && (
            <p className="text-body-sm text-content-muted">{step.description}</p>
          )}
          <div className="mt-1 flex flex-wrap gap-1">
            <Chip>{step.tatDays} working day{step.tatDays === 1 ? '' : 's'}</Chip>
            {step.ownerUserId != null && <Chip>Owner: user #{step.ownerUserId}</Chip>}
            {step.ownerRole && <Chip>Owner role: {step.ownerRole}</Chip>}
            {step.ownerUserId == null && !step.ownerRole && <Chip>No owner set</Chip>}
            {step.requiresSignoff && <Chip>Requires sign-off</Chip>}
          </div>
          <p className="mt-1 text-caption text-content-muted">{dependsOn}</p>
        </div>

        {editable && (
          <div className="flex flex-wrap gap-1">
            <Button
              type="button"
              variant="ghost"
              size="sm"
              disabled={index === 0}
              aria-label={`Move ${step.name} up`}
              onClick={() => onMove(index, index - 1)}
            >
              ↑
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              disabled={index === total - 1}
              aria-label={`Move ${step.name} down`}
              onClick={() => onMove(index, index + 1)}
            >
              ↓
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              aria-label={`Remove ${step.name}`}
              onClick={onRemove}
            >
              Remove
            </Button>
          </div>
        )}
      </div>

      <div className="grid gap-3 sm:grid-cols-2">
        <StepItemsList templateId={templateId} step={step} editable={editable} />
        <StepDocsList templateId={templateId} step={step} editable={editable} />
      </div>
    </li>
  )
}

function StepItemsList({
  templateId,
  step,
  editable,
}: {
  templateId: number
  step: ObJourneyTemplateStep
  editable: boolean
}) {
  const addItem = useAddJourneyTemplateStepItem()
  const removeItem = useRemoveJourneyTemplateStepItem()
  const [label, setLabel] = React.useState('')
  const [mandatory, setMandatory] = React.useState(true)

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!label.trim()) return
    try {
      await addItem.mutateAsync({
        templateId,
        stepId: step.id,
        data: { label: label.trim(), mandatory },
      })
      toast({ title: 'Task list item added' })
      setLabel('')
      setMandatory(true)
    } catch (error) {
      toast({
        title: 'Could not add that item',
        description: problemDetail(error),
        variant: 'danger',
      })
    }
  }

  const doRemove = async (itemId: number, itemLabel: string) => {
    try {
      await removeItem.mutateAsync({ templateId, itemId })
      toast({ title: `${itemLabel} removed` })
    } catch (error) {
      toast({
        title: 'Could not remove that item',
        description: problemDetail(error),
        variant: 'danger',
      })
    }
  }

  return (
    <div className="flex flex-col gap-1">
      <h3 className="text-caption font-medium text-content-muted">Task list — {step.name}</h3>
      {step.items.length === 0 ? (
        <p className="text-caption text-content-muted">No Task List items.</p>
      ) : (
        <ul className="flex flex-col gap-1">
          {step.items.map((item) => (
            <li key={item.id} className="flex items-center justify-between gap-2 text-body-sm">
              <span>
                {item.label}
                {!item.mandatory && <span className="text-content-muted"> (optional)</span>}
              </span>
              {editable && (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  aria-label={`Remove ${item.label}`}
                  onClick={() => doRemove(item.id, item.label)}
                >
                  Remove
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
      {editable && (
        <form onSubmit={submit} className="flex flex-wrap items-center gap-2">
          <Input
            value={label}
            onChange={(e) => setLabel(e.target.value)}
            placeholder="New task list item"
            aria-label={`New task list item for ${step.name}`}
            className="h-8 flex-1"
          />
          <label className="flex items-center gap-1 text-caption text-content-muted">
            <input
              type="checkbox"
              checked={mandatory}
              onChange={(e) => setMandatory(e.target.checked)}
            />
            Mandatory
          </label>
          <Button
            type="submit"
            size="sm"
            variant="secondary"
            disabled={addItem.isPending || !label.trim()}
          >
            Add
          </Button>
        </form>
      )}
    </div>
  )
}

function StepDocsList({
  templateId,
  step,
  editable,
}: {
  templateId: number
  step: ObJourneyTemplateStep
  editable: boolean
}) {
  const addDoc = useAddJourneyTemplateStepDoc()
  const removeDoc = useRemoveJourneyTemplateStepDoc()
  const [label, setLabel] = React.useState('')
  const [required, setRequired] = React.useState(true)

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!label.trim()) return
    try {
      await addDoc.mutateAsync({
        templateId,
        stepId: step.id,
        data: { label: label.trim(), required },
      })
      toast({ title: 'Required document added' })
      setLabel('')
      setRequired(true)
    } catch (error) {
      toast({
        title: 'Could not add that document',
        description: problemDetail(error),
        variant: 'danger',
      })
    }
  }

  const doRemove = async (docId: number, docLabel: string) => {
    try {
      await removeDoc.mutateAsync({ templateId, docId })
      toast({ title: `${docLabel} removed` })
    } catch (error) {
      toast({
        title: 'Could not remove that document',
        description: problemDetail(error),
        variant: 'danger',
      })
    }
  }

  return (
    <div className="flex flex-col gap-1">
      <h3 className="text-caption font-medium text-content-muted">Required documents — {step.name}</h3>
      {step.docs.length === 0 ? (
        <p className="text-caption text-content-muted">No required documents.</p>
      ) : (
        <ul className="flex flex-col gap-1">
          {step.docs.map((doc) => (
            <li key={doc.id} className="flex items-center justify-between gap-2 text-body-sm">
              <span>
                {doc.label}
                {!doc.required && <span className="text-content-muted"> (optional)</span>}
              </span>
              {editable && (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  aria-label={`Remove ${doc.label}`}
                  onClick={() => doRemove(doc.id, doc.label)}
                >
                  Remove
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
      {editable && (
        <form onSubmit={submit} className="flex flex-wrap items-center gap-2">
          <Input
            value={label}
            onChange={(e) => setLabel(e.target.value)}
            placeholder="New required document"
            aria-label={`New required document for ${step.name}`}
            className="h-8 flex-1"
          />
          <label className="flex items-center gap-1 text-caption text-content-muted">
            <input
              type="checkbox"
              checked={required}
              onChange={(e) => setRequired(e.target.checked)}
            />
            Required
          </label>
          <Button
            type="submit"
            size="sm"
            variant="secondary"
            disabled={addDoc.isPending || !label.trim()}
          >
            Add
          </Button>
        </form>
      )}
    </div>
  )
}

/**
 * The step catalogue's own add form. Inline rather than a modal — S-30's
 * dialog pattern buys nothing here since there is nothing to switch between
 * create and edit, the backend never exposing an edit route on a step.
 */
function AddStepForm({
  templateId,
  steps,
  onClose,
}: {
  templateId: number
  steps: ObJourneyTemplateStep[]
  onClose: () => void
}) {
  const addStep = useAddJourneyTemplateStep()
  const [name, setName] = React.useState('')
  const [description, setDescription] = React.useState('')
  const [tatDays, setTatDays] = React.useState('')
  const [ownerUserId, setOwnerUserId] = React.useState('')
  const [ownerRole, setOwnerRole] = React.useState('')
  const [requiresSignoff, setRequiresSignoff] = React.useState(false)
  const [dependsOnStepId, setDependsOnStepId] = React.useState('')
  const [submitted, setSubmitted] = React.useState(false)
  const [serverErrors, setServerErrors] = React.useState<Record<string, string>>({})

  const tatValue = Number(tatDays)
  const errors: Record<string, string> = {}
  if (!name.trim()) errors.name = 'Name is required'
  if (!tatDays.trim() || !Number.isFinite(tatValue) || tatValue < 1) {
    errors.tatDays = 'TAT must be at least 1 working day'
  }

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    setSubmitted(true)
    if (Object.keys(errors).length > 0) return

    try {
      await addStep.mutateAsync({
        templateId,
        data: {
          name: name.trim(),
          description: description.trim() || undefined,
          tatDays: tatValue,
          ownerUserId: ownerUserId.trim() ? Number(ownerUserId) : undefined,
          ownerRole: ownerRole.trim() || undefined,
          requiresSignoff,
          dependsOnStepId: dependsOnStepId ? Number(dependsOnStepId) : undefined,
        },
      })
      toast({ title: `${name.trim()} added` })
      onClose()
    } catch (error) {
      setServerErrors(fieldErrors(error))
    }
  }

  return (
    <form
      onSubmit={submit}
      aria-label="Add step"
      className="flex flex-col gap-3 rounded-control border border-line p-3"
    >
      <div className="flex flex-col gap-1 text-sm">
        <label htmlFor="add-step-name" className="font-medium text-content">
          Name
        </label>
        <Input
          id="add-step-name"
          value={name}
          maxLength={200}
          aria-describedby={submitted && errors.name ? 'add-step-name-error' : undefined}
          onChange={(e) => setName(e.target.value)}
        />
        {submitted && errors.name && (
          <span id="add-step-name-error" className="text-xs text-danger">
            {errors.name}
          </span>
        )}
        {serverErrors.name && <span className="text-xs text-danger">{serverErrors.name}</span>}
      </div>

      <div className="flex flex-col gap-1 text-sm">
        <label htmlFor="add-step-description" className="font-medium text-content">
          Description
        </label>
        <Input
          id="add-step-description"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
        />
      </div>

      <div className="flex flex-col gap-1 text-sm">
        <label htmlFor="add-step-tat" className="font-medium text-content">
          TAT (working days)
        </label>
        <Input
          id="add-step-tat"
          value={tatDays}
          inputMode="numeric"
          aria-describedby={submitted && errors.tatDays ? 'add-step-tat-error' : undefined}
          onChange={(e) => setTatDays(e.target.value)}
        />
        {submitted && errors.tatDays && (
          <span id="add-step-tat-error" className="text-xs text-danger">
            {errors.tatDays}
          </span>
        )}
      </div>

      <div className="grid gap-3 sm:grid-cols-2">
        <div className="flex flex-col gap-1 text-sm">
          <label htmlFor="add-step-owner-user" className="font-medium text-content">
            Owner user id
          </label>
          <Input
            id="add-step-owner-user"
            value={ownerUserId}
            inputMode="numeric"
            onChange={(e) => setOwnerUserId(e.target.value)}
          />
        </div>
        <div className="flex flex-col gap-1 text-sm">
          <label htmlFor="add-step-owner-role" className="font-medium text-content">
            Owner role
          </label>
          <Input
            id="add-step-owner-role"
            value={ownerRole}
            maxLength={40}
            onChange={(e) => setOwnerRole(e.target.value)}
          />
        </div>
      </div>

      <label className="flex items-center gap-2 text-sm text-content">
        <input
          type="checkbox"
          checked={requiresSignoff}
          onChange={(e) => setRequiresSignoff(e.target.checked)}
        />
        Client sign-off required before this step may complete
      </label>

      <div className="flex flex-col gap-1 text-sm">
        <label htmlFor="add-step-depends-on" className="font-medium text-content">
          Depends on
        </label>
        <select
          id="add-step-depends-on"
          className="h-9 rounded-control border border-border bg-surface px-2 text-sm"
          value={dependsOnStepId}
          onChange={(e) => setDependsOnStepId(e.target.value)}
        >
          <option value="">Parallel from journey start</option>
          {steps.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </select>
      </div>

      <div className="flex gap-2">
        <Button type="submit" size="sm" disabled={addStep.isPending}>
          Add step
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onClose}>
          Cancel
        </Button>
      </div>
    </form>
  )
}

/**
 * `parallelGroups`, rendered — the payoff of the whole computed field. Layer
 * 0 first, each group a list of step names that could all be in progress on
 * the same journey at once (plan §5.6).
 */
function ParallelGroupsPanel({
  groups,
  steps,
}: {
  groups: number[][]
  steps: ObJourneyTemplateStep[]
}) {
  const nameOf = (id: number) => steps.find((s) => s.id === id)?.name ?? `step #${id}`

  return (
    <section
      aria-labelledby="parallel-groups-heading"
      className="flex flex-col gap-2 rounded-card border border-line p-4"
    >
      <h2 id="parallel-groups-heading" className="text-h4 text-content">
        Parallel groups
      </h2>
      <p className="text-body-sm text-content-muted">
        Everything inside one group could be in progress on the same journey at once.
      </p>
      {groups.length === 0 ? (
        <EmptyState
          title="Nothing to show yet"
          description="Add a step to see how the journey's services line up."
        />
      ) : (
        <ol className="flex flex-col gap-1">
          {groups.map((group, layer) => (
            <li key={layer} className="text-body-sm text-content">
              <span className="font-medium">
                Group {layer + 1} (layer {layer})
              </span>{' '}
              — could all run at once: {group.map(nameOf).join(', ')}
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}

// ── pure helpers ─────────────────────────────────────────────────────────────

function moveItem<T>(items: T[], from: number, to: number): T[] {
  if (from === to || from < 0 || to < 0 || from >= items.length || to >= items.length) {
    return items
  }
  const next = [...items]
  const [moved] = next.splice(from, 1)
  next.splice(to, 0, moved)
  return next
}

function orderChanged(ordered: ObJourneyTemplateStep[], original: ObJourneyTemplateStep[]): boolean {
  if (ordered.length !== original.length) return true
  return ordered.some((step, index) => step.id !== original[index].id)
}

function dependentStepIds(error: unknown): number[] {
  if (!(error instanceof ApiError)) return []
  const problem = error.problem as { dependentStepIds?: number[] }
  return problem.dependentStepIds ?? []
}

function fieldErrors(error: unknown): Record<string, string> {
  if (!(error instanceof ApiError)) return {}
  const errors = error.fieldErrors
  return Object.fromEntries(Object.entries(errors).map(([field, messages]) => [field, messages[0] ?? '']))
}

function problemDetail(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Reload the page and try again.'
  return error.problem.detail ?? error.problem.title ?? 'Reload the page and try again.'
}
