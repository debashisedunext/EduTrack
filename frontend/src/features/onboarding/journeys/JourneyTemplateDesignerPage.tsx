import * as React from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'

import { ApiError } from '@/api/http'
import type { ObJourneyTemplateDetail } from '@/api/generated/model/obJourneyTemplateDetail'
import type { ObJourneyTemplateStep } from '@/api/generated/model/obJourneyTemplateStep'
import type { Role } from '@/api/generated/model/role'
import type { UserRef } from '@/api/generated/model'
import { useListRoles } from '@/api/generated/masters/masters'
import { useListObProducts } from '@/api/generated/onboarding-masters/onboarding-masters'
import { useListObJourneyTemplates } from '@/api/generated/onboarding-journeys/onboarding-journeys'
import { useListUsers } from '@/api/generated/users/users'

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
import { formatTemplateTotalTatDays, templateTotalTatDays } from './journeyTemplateTat'
import { ModuleServiceAdmin } from './ModuleServiceAdmin'

/**
 * C-102 · OB-07's journey template designer, laid out to
 * `docs/prototype/onboarding.html`'s `vTplEdit()`: back link, name + product
 * chip header with the versioning caption, publish button, then the step
 * table — #, Service name, TAT (days), Default responsible, Service depends
 * on, Sign-off, Order — with the Task List chip editor under each row and
 * "+ Add step" at the bottom.
 *
 * <h2>The one rule that shapes everything on this page</h2>
 *
 * <p>{@code ObJourneyTemplateService}'s own header: <em>"an admin edit never
 * mutates an in-flight journey."</em> A template version is editable only
 * while {@code publishedAt == null} — a draft. The moment it publishes it is
 * frozen for the rest of its life, active or retired, and the only way
 * forward is {@code beginRevision}, which clones it into a new draft one
 * version higher. So every write control on this page — Add step, ✕, the
 * chip editors, the reorder — is conditioned on {@code editable}, not on
 * {@code isActive}: a retired version is exactly as frozen as the currently
 * active one.
 *
 * <h2>Where the table diverges from the mockup, and why</h2>
 *
 * <p>The mockup draws name, TAT, responsible, depends-on and sign-off as
 * editable fields on every row. The backend exposes <b>add and remove on a
 * step, never an edit</b> — so those cells render the values as text and the
 * sign-off as a checkbox that cannot be changed, rather than as controls
 * whose input would be silently dropped. Correcting a step is remove + add,
 * which the Order column and "+ Add step" cover. The mockup's editable
 * template-name input is out for the same reason: there is no rename route.
 *
 * <h2>A step's owner is picked from the Role Master, not typed</h2>
 *
 * <p>{@code ob_journey_template_steps.owner_role} is a plain {@code VARCHAR}
 * with no foreign key to {@code roles.code} — the same "a typo would not fail
 * loudly" note {@code workflow_stages.owner_role} carries. So the form offers a
 * closed list of the active roles an admin defined in S-09 and stores the
 * <em>code</em> of the row chosen, rather than accepting free text nothing
 * checks. The picker's value is the role's <b>id</b> and the code is read off
 * the selection: the id is what is chosen, the role follows from it, and the
 * two cannot disagree.
 *
 * <p>The Default responsible column resolves a stored code back to the
 * master's name, falling back to the code itself — a step written against a
 * role that has since been deleted survives the delete, exactly because there
 * is no foreign key.
 *
 * <h2>Most writes go immediately; only the order is staged</h2>
 *
 * <p>Add/remove a step, add/remove an item or doc, publish, begin revision —
 * each is one route with no rollback between it and the next. Reordering is
 * different: {@code PUT .../steps/order} replaces the whole set under one
 * {@code If-Match}, so it is staged locally and sent once, on confirm.
 *
 * <h2>This page is the whole service, not only its steps</h2>
 *
 * <p>The OB-07 catalogue card is now a summary tile — a step count, not a step
 * list — and clicking it lands here. So {@code ModuleServiceAdmin}'s rename /
 * move / delete came with it and sit below the table: they act on the whole
 * version chain rather than on this version, and are still refused outright
 * while a client is boarded on any version of it. The one control that stayed
 * on the card is "Service depends on", which is a comparison between services
 * and belongs where the other services are.
 *
 * <p>{@code serviceJourneyCount} is what that refusal reads, and it is on the
 * catalogue <em>summary</em> row rather than on this detail payload — hence
 * the {@code listObJourneyTemplates} read here.
 */
export function JourneyTemplateDesignerPage() {
  const params = useParams()
  const templateId = Number(params.templateId)

  const query = useJourneyTemplate(Number.isFinite(templateId) ? templateId : null)

  if (!Number.isFinite(templateId)) {
    return (
      <EmptyState
        title="No such journey template"
        description="Pick one from the Module Service catalogue."
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
  const products = useListObProducts()
  const users = useListUsers({ isActive: true, limit: 200 })
  const userList = users.data?.data ?? []
  /*
    The step owner is a role from the S-09 Role Master, so the picker offers
    exactly what an admin has defined there and nothing else — `ownerRole`
    carries no foreign key, which is why a free-text field let a typo through
    silently. Active only in the picker; the full list is still what resolves
    a code to a name on an existing step, since a step written before a role
    was retired must still render it.
  */
  const activeRoles = useListRoles({ isActive: true })
  const allRoles = useListRoles()
  const roleOptions = activeRoles.data?.data ?? []
  const roleList = allRoles.data?.data ?? []
  /*
    This service's own catalogue row, for `serviceJourneyCount` — chain-wide,
    and the one fact the rename and the delete are gated on. It is on the
    summary row rather than on this detail payload, so the list read is how
    the page gets at it.
  */
  const catalogue = useListObJourneyTemplates()
  const serviceRow = catalogue.data?.data.find((row) => row.id === templateId)

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
  // C-120 · Σ tatDays, not netted for parallel groups — the work the
  // template carries, read the same way `totalTatDays` reads a journey.
  const totalTatDays = templateTotalTatDays(steps)
  const product = products.data?.data.find((p) => p.id === detail.productId)

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
    /*
      Full-bleed, not centred in a `max-w-4xl` column. The step table is the
      widest thing in the module — seven columns plus a task list under every
      row — and the measure that suits a page of prose left it scrolling
      sideways inside a card with empty gutters either side of it.
    */
    <div className="flex w-full flex-col gap-4 p-6">
      <Button asChild variant="ghost" size="sm" className="self-start">
        <Link to="/onboarding/journey-templates">← Module Service</Link>
      </Button>

      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="m-0 text-h2 text-content">{detail.name}</h1>
            {product && <Chip variant="neutral">{product.name}</Chip>}
            <Chip variant={editable ? 'info' : detail.isActive ? 'success' : 'neutral'}>{state}</Chip>
          </div>
          <p className="mt-1.5 text-caption text-content-muted">
            {editable ? (
              detail.version === 1 ? (
                <>New template — publishing activates <b>v1</b>{product && <> for {product.name}</>}.</>
              ) : (
                <>Draft of <b>v{detail.version}</b> — publishing replaces v{detail.version - 1}. Clients already in flight keep v{detail.version - 1}.</>
              )
            ) : detail.isActive ? (
              <>Active <b>v{detail.version}</b> — Begin revision to edit; it publishes as <b>v{detail.version + 1}</b>. Clients already in flight keep v{detail.version}.</>
            ) : (
              <>Retired <b>v{detail.version}</b> — read-only for good.</>
            )}
            {' · '}
            <b title={formatTemplateTotalTatDays(totalTatDays)}>Total TAT: {totalTatDays}d</b>
            {' '}across {steps.length} service{steps.length === 1 ? '' : 's'}
          </p>
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
              Publish v{detail.version}
            </Button>
          )}
        </div>
      </header>

      <p role="status" aria-live="polite" className="sr-only">
        {announcement}
      </p>

      {steps.length === 0 ? (
        <EmptyState
          title="No steps yet"
          description="Add the first service this journey walks a client through."
        />
      ) : (
        <div className="overflow-x-auto rounded-card border border-border bg-surface shadow-rest">
          <table className="w-full border-collapse text-sm" aria-label="Services">
            <thead>
              <tr className="border-b border-border text-left">
                <th scope="col" className="w-9 px-3 py-2 text-caption font-semibold text-content-muted">#</th>
                <th scope="col" className="px-3 py-2 text-caption font-semibold text-content-muted">Service name</th>
                <th scope="col" className="w-24 px-3 py-2 text-caption font-semibold text-content-muted">TAT (days)</th>
                <th scope="col" className="w-44 px-3 py-2 text-caption font-semibold text-content-muted">Default responsible</th>
                <th scope="col" className="w-48 px-3 py-2 text-caption font-semibold text-content-muted">Service depends on</th>
                <th scope="col" className="w-20 px-3 py-2 text-center text-caption font-semibold text-content-muted">Sign-off</th>
                {editable && (
                  <th scope="col" className="w-32 px-3 py-2 text-caption font-semibold text-content-muted">Order</th>
                )}
              </tr>
            </thead>
            {steps.map((step, index) => (
              <StepRows
                key={step.id}
                templateId={templateId}
                step={step}
                index={index}
                total={steps.length}
                editable={editable}
                allSteps={steps}
                users={userList}
                roles={roleList}
                onMove={move}
                onRemove={() => doRemoveStep(step)}
              />
            ))}
          </table>
        </div>
      )}

      {dirty && (
        <div className="flex flex-wrap items-center gap-2 rounded-control border border-border bg-subtle p-3">
          <p className="m-0 text-sm text-content-muted">The order above is not saved yet.</p>
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

      {editable && !addingStep && (
        <Button type="button" variant="secondary" className="self-start" onClick={() => setAddingStep(true)}>
          + Add step
        </Button>
      )}

      {addingStep && editable && (
        <AddStepForm
          templateId={templateId}
          steps={detail.steps}
          roles={roleOptions}
          onClose={() => setAddingStep(false)}
        />
      )}

      <ParallelGroupsPanel groups={detail.parallelGroups} steps={detail.steps} />

      {/*
        Held back until the catalogue row is in hand. `serviceJourneyCount` is
        what disables the rename and the delete, so drawing the section before
        it lands would offer both controls enabled for a moment on a service
        that is locked — and a click inside that moment answers 409.
      */}
      {serviceRow && (
        <ModuleServiceAdmin
          templateId={templateId}
          serviceName={detail.name}
          productId={detail.productId}
          productName={product?.name ?? `Product ${detail.productId}`}
          version={detail.version}
          serviceJourneyCount={serviceRow.serviceJourneyCount}
          products={products.data?.data ?? []}
        />
      )}
    </div>
  )
}

/**
 * One service — two `<tr>`s inside their own `<tbody>`: the field row, then
 * the mockup's full-width Task List chip-editor row under it.
 */
function StepRows({
  templateId,
  step,
  index,
  total,
  editable,
  allSteps,
  users,
  roles,
  onMove,
  onRemove,
}: {
  templateId: number
  step: ObJourneyTemplateStep
  index: number
  total: number
  editable: boolean
  allSteps: ObJourneyTemplateStep[]
  users: readonly UserRef[]
  /** Every role, active or not — a retired one still has to render its name. */
  roles: readonly Role[]
  onMove: (from: number, to: number) => void
  onRemove: () => void
}) {
  const depIndex = step.dependsOnStepId != null
    ? allSteps.findIndex((s) => s.id === step.dependsOnStepId)
    : -1
  const dependsOn = depIndex >= 0
    ? `↳ ${depIndex + 1}. ${allSteps[depIndex].name}`
    : '∥ none — runs parallel'
  /*
    A role code is what the column stores; a role *name* is what an admin
    typed into the master and expects to read back. Falling through to the
    code covers a step written against a role that has since been deleted —
    `owner_role` carries no foreign key, so that row survives the delete.
  */
  const responsible = step.ownerUserId != null
    ? users.find((u) => u.id === step.ownerUserId)?.displayName ?? `user #${step.ownerUserId}`
    : step.ownerRole
      ? roles.find((r) => r.code === step.ownerRole)?.name ?? step.ownerRole
      : '—'
  const colSpan = editable ? 6 : 5

  return (
    <tbody className="border-b border-border last:border-b-0">
      <tr>
        <td className="px-3 pb-1 pt-2.5 align-top text-caption tabular-nums text-content-muted">{index + 1}</td>
        <td className="px-3 pb-1 pt-2 align-top">
          <span className="font-medium text-content">{step.name}</span>
          {step.description && (
            <span className="block text-caption text-content-muted">{step.description}</span>
          )}
        </td>
        <td className="px-3 pb-1 pt-2 align-top tabular-nums text-content">{step.tatDays}</td>
        <td className="px-3 pb-1 pt-2 align-top text-content">{responsible}</td>
        <td className="px-3 pb-1 pt-2 align-top text-content-muted">{dependsOn}</td>
        <td className="px-3 pb-1 pt-2 text-center align-top">
          {/* Set when the step is added — the backend has no step-edit route,
              so the box states the fact rather than offering a dead control. */}
          <input
            type="checkbox"
            checked={step.requiresSignoff}
            disabled
            aria-label={`Step ${index + 1} requires client sign-off`}
            title="Set when the step is added — remove and re-add the step to change it"
            className="h-4 w-4 rounded border-border"
          />
        </td>
        {editable && (
          <td className="px-3 pb-1 pt-1.5 align-top">
            <span className="inline-flex gap-1">
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
                ✕
              </Button>
            </span>
          </td>
        )}
      </tr>
      <tr>
        <td className="pb-3" />
        <td colSpan={colSpan} className="px-3 pb-3">
          <StepItemChips templateId={templateId} step={step} editable={editable} />
          <StepDocChips templateId={templateId} step={step} editable={editable} />
        </td>
      </tr>
    </tbody>
  )
}

/**
 * The Task List editor — one task per line with a ✕, an "Add a task…" input
 * (Enter submits) and a "+ Add" button. Items are always added mandatory,
 * matching the mockup, which has no optional flag on a task.
 *
 * <p>A vertical list rather than a wrapping chip row. Tasks are sentences
 * ("Cut-over window agreed with the client"), not tags, so a row of them wraps
 * at arbitrary points and two tasks read as one; stacked, each is a line the
 * eye can count, and the input beneath spans the cell rather than sitting in
 * whatever gap the last chip left.
 */
function StepItemChips({
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

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!label.trim()) return
    try {
      await addItem.mutateAsync({
        templateId,
        stepId: step.id,
        data: { label: label.trim(), mandatory: true },
      })
      toast({ title: 'Task list item added' })
      setLabel('')
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

  if (!editable && step.items.length === 0) return null

  return (
    <div>
      <p className="m-0 mb-1.5 text-caption text-content-muted">
        Task list — shown when this service is clicked on the client page; each task is ticked off
        there, and the step cannot complete until the mandatory ones are ticked.
      </p>
      {step.items.length > 0 && (
        // `items-start` is what keeps each row the width of its own task
        // rather than the width of the cell: a column flex container stretches
        // its children by default, and a one-line task in a full-bleed band
        // reads as an empty field waiting to be filled.
        <ul className="m-0 flex list-none flex-col items-start gap-1 p-0">
          {step.items.map((item) => (
            <li
              key={item.id}
              className="flex max-w-full items-start gap-2 rounded-control border border-border bg-subtle px-2.5 py-1.5 text-sm text-content"
            >
              <span className="min-w-0 flex-1 break-words">
                {item.label}
                {!item.mandatory && <span className="text-content-muted"> (optional)</span>}
              </span>
              {editable && (
                <button
                  type="button"
                  aria-label={`Remove ${item.label}`}
                  className="rounded-chip leading-none text-content-muted hover:text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                  onClick={() => doRemove(item.id, item.label)}
                >
                  ✕
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {editable && (
        // `w-full` on the form and `flex-1 min-w-0` on the input: the cell is
        // as wide as the table, and a fixed `w-56` box in it was the one thing
        // on the row that did not use the width it was given.
        <form onSubmit={submit} className="mt-1.5 flex w-full items-center gap-2">
          <Input
            value={label}
            onChange={(e) => setLabel(e.target.value)}
            placeholder="Add a task…"
            aria-label={`New task list item for ${step.name}`}
            className="h-8 min-w-0 flex-1 text-caption"
          />
          <Button
            type="submit"
            size="sm"
            variant="secondary"
            className="shrink-0"
            disabled={addItem.isPending || !label.trim()}
          >
            + Add
          </Button>
        </form>
      )}
    </div>
  )
}

/**
 * The required-document checklist, kept from the backend's own contract even
 * though the mockup's template editor omits it — the client page's document
 * gate has to be authored somewhere, and this is its only write surface.
 * Same chip-editor shape as the Task List so the two read as one pattern.
 */
function StepDocChips({
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

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!label.trim()) return
    try {
      await addDoc.mutateAsync({
        templateId,
        stepId: step.id,
        data: { label: label.trim(), required: true },
      })
      toast({ title: 'Required document added' })
      setLabel('')
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

  if (!editable && step.docs.length === 0) return null

  return (
    <div className="mt-2">
      <p className="m-0 mb-1.5 text-caption text-content-muted">
        Required documents — the step's document gate on the client page
      </p>
      {step.docs.length > 0 && (
        <ul className="m-0 flex list-none flex-col items-start gap-1 p-0">
          {step.docs.map((doc) => (
            <li
              key={doc.id}
              className="flex max-w-full items-start gap-2 rounded-control border border-border bg-subtle px-2.5 py-1.5 text-sm text-content"
            >
              <span className="min-w-0 flex-1 break-words">
                📎 {doc.label}
                {!doc.required && <span className="text-content-muted"> (optional)</span>}
              </span>
              {editable && (
                <button
                  type="button"
                  aria-label={`Remove ${doc.label}`}
                  className="rounded-chip leading-none text-content-muted hover:text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                  onClick={() => doRemove(doc.id, doc.label)}
                >
                  ✕
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {editable && (
        <form onSubmit={submit} className="mt-1.5 flex w-full items-center gap-2">
          <Input
            value={label}
            onChange={(e) => setLabel(e.target.value)}
            placeholder="Add a document…"
            aria-label={`New required document for ${step.name}`}
            className="h-8 min-w-0 flex-1 text-caption"
          />
          <Button
            type="submit"
            size="sm"
            variant="secondary"
            className="shrink-0"
            disabled={addDoc.isPending || !label.trim()}
          >
            + Add
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
  roles,
  onClose,
}: {
  templateId: number
  steps: ObJourneyTemplateStep[]
  /** Active roles from the S-09 master — the owner picker's only candidates. */
  roles: readonly Role[]
  onClose: () => void
}) {
  const addStep = useAddJourneyTemplateStep()
  const [name, setName] = React.useState('')
  const [description, setDescription] = React.useState('')
  const [tatDays, setTatDays] = React.useState('')
  const [ownerRoleId, setOwnerRoleId] = React.useState('')
  const [requiresSignoff, setRequiresSignoff] = React.useState(false)
  const [dependsOnStepId, setDependsOnStepId] = React.useState('')
  const [submitted, setSubmitted] = React.useState(false)
  const [serverErrors, setServerErrors] = React.useState<Record<string, string>>({})

  const tatValue = Number(tatDays)
  /*
    The picker's value is the role's **id**, and the code that `ownerRole`
    actually stores is read off the selected row rather than typed. That is
    the whole point of the change: the id is what is chosen, the role follows
    from it, and the two cannot disagree.
  */
  const ownerRoleObj = roles.find((r) => String(r.id) === ownerRoleId)
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
          ownerRole: ownerRoleObj?.code,
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
      className="flex flex-col gap-3 rounded-control border border-border bg-surface p-3 shadow-rest"
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
        <div className="flex min-w-0 flex-col gap-1 text-sm">
          <label htmlFor="add-step-owner-role-id" className="font-medium text-content">
            Owner
          </label>
          <select
            id="add-step-owner-role-id"
            /*
              `min-w-0` for the reason the catalogue's depends-on picker gives:
              a <select> in a grid cell sizes to its widest <option> unless it
              is allowed to shrink, and role names are arbitrary length.
            */
            className="h-9 min-w-0 truncate rounded-control border border-border bg-surface px-2 text-sm text-content"
            value={ownerRoleId}
            aria-describedby="add-step-owner-role-code"
            onChange={(e) => setOwnerRoleId(e.target.value)}
          >
            <option value="">Unassigned — no owning role</option>
            {roles.map((role) => (
              <option key={role.id} value={role.id}>
                {role.id} · {role.name}
              </option>
            ))}
          </select>
          {/*
            The picker is a closed list, so the answer to "the role I want is
            not here" has to be on the form rather than left to be guessed —
            S-09 is in the ticketing module and nothing in the Onboarding nav
            leads to it. Opens in a new tab: this form holds unsaved state and
            navigating away in place would discard a half-written step.
          */}
          <a
            href="/masters/roles"
            target="_blank"
            rel="noopener noreferrer"
            className="self-start text-xs text-primary hover:underline"
          >
            Role not listed? Add or edit roles ↗
          </a>
        </div>
        <div className="flex min-w-0 flex-col gap-1 text-sm">
          <label htmlFor="add-step-owner-role" className="font-medium text-content">
            Owner role
          </label>
          {/*
            Read-only, and shown rather than hidden: `ownerRole` is what the
            step actually stores, so the form says which code the chosen id
            resolves to instead of leaving the admin to trust that it did.
          */}
          <Input
            id="add-step-owner-role"
            value={ownerRoleObj?.code ?? ''}
            readOnly
            placeholder="Follows the owner above"
            className="bg-subtle text-content-muted"
          />
          <span id="add-step-owner-role-code" className="text-xs text-content-muted">
            Set automatically from the owner id — roles come from the Role master.
          </span>
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
          <option value="">∥ none — runs parallel</option>
          {steps.map((s, i) => (
            <option key={s.id} value={s.id}>
              ↳ {i + 1}. {s.name}
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
 * the same journey at once (plan §5.6). Not in the mockup's `vTplEdit()`,
 * kept deliberately: it is the readable form of the same dependency column
 * the table shows one row at a time.
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
      className="flex flex-col gap-2 rounded-card border border-border bg-surface p-4 shadow-rest"
    >
      <h2 id="parallel-groups-heading" className="m-0 text-h3 text-content">
        Parallel groups
      </h2>
      <p className="m-0 text-sm text-content-muted">
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
            <li key={layer} className="text-sm text-content">
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
