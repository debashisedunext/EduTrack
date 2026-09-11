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
import { buildStepTree, scheduleBar, scheduleLabel, type StepNode } from './journeyTemplateTree'
import { ModuleServiceAdmin } from './ModuleServiceAdmin'

/**
 * C-102 · OB-07's journey template designer: back link, name + product chip
 * header with the versioning caption, publish button, then the step tree —
 * Service, Schedule, TAT (days), Default responsible, Sign-off (and Order
 * while the version is editable) — with each step's task list and required
 * documents drawn as its own child rows, and "+ Add step" at the bottom.
 *
 * <h2>The table is a tree, and the dependency is the shape of it</h2>
 *
 * <p>It was a flat list with a "Service depends on" cell reading
 * {@code ↳ 3. Cleansing & field mapping}. That says what one step waits for
 * and never what the journey *is*: reading a chain of five meant holding five
 * row numbers in your head and walking them backwards. Now a step is nested
 * under the step it waits for, and the column is gone because the indentation
 * has replaced it. {@code journeyTemplateTree.ts} builds the tree and, from
 * the same walk, the **Schedule** column's day ranges — the earliest a step
 * could begin if nothing slips, in working days relative to journey start,
 * never dates, because a template has no client and therefore no calendar.
 *
 * <p>The one thing indentation cannot say is "waits for nothing", so the root
 * rows say it in words. That is not decoration: {@code dependsOnStepId} being
 * null means the step runs in **parallel** from journey start, not that it is
 * first, and a step at the left margin is otherwise indistinguishable from the
 * head of a chain.
 *
 * <p>Reordering still moves a step in the **flat sequence**, which is what
 * {@code PUT .../steps/order} replaces — so ↑/↓ visibly reorders siblings and
 * does nothing visible to a parent and its child, which the tree draws in
 * dependency order regardless.
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
 * <p>The tick boxes on the task rows are the same kind of statement. This
 * screen defines the list; the client's own journey page is where a task is
 * ticked off. They are drawn because the shape is what an admin is authoring,
 * and they are inert and {@code aria-hidden} because the control they look
 * like does not exist here.
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

  /*
    C-124 · ModuleServiceAdmin's Position and "Service depends on" fields need
    the same two shapes the OB-07 catalogue page itself builds from this
    exact list: every active template's id in sequence order (the reorder
    route's own shape), and the active rows as cycle-free candidates. Built
    here rather than threading the catalogue page's own values in, since this
    page reads the list independently for `serviceRow` already.
  */
  const activeRows = (catalogue.data?.data ?? [])
    .filter((row) => row.isActive)
    .sort((a, b) => a.sequence - b.sequence)
  const activeOrder = activeRows.map((row) => row.id)
  const catalogueEntries = activeRows.map((row) => ({
    activeTemplateId: row.id,
    dependsOnTemplateIds: row.dependsOnTemplateIds ?? [],
    name: row.name,
  }))

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

  /*
    The table is a tree of `dependsOnStepId`, and the Schedule column falls out
    of the same walk — see `journeyTemplateTree.ts` for why the dependency
    stopped being a column and became the shape of the rows.
  */
  const tree = React.useMemo(() => buildStepTree(steps), [steps])
  /*
    Collapsed rather than expanded is the stored set, so a step added or
    revealed by a reorder arrives open — the default is "show me everything",
    and only a deliberate collapse is remembered. Keyed by step id, so it
    survives a reorder; a removed step leaves a stale id behind, which costs
    nothing and cannot resurrect as anything but that same step.
  */
  const [collapsed, setCollapsed] = React.useState<ReadonlySet<number>>(() => new Set())
  const toggle = (node: StepNode<ObJourneyTemplateStep>) => {
    setCollapsed((held) => {
      const next = new Set(held)
      if (next.has(node.step.id)) next.delete(node.step.id)
      else next.add(node.step.id)
      return next
    })
    setAnnouncement(
      `${node.step.name} ${collapsed.has(node.step.id) ? 'expanded' : 'collapsed'}.`,
    )
  }
  // A collapsed step takes its whole subtree with it, not only its task list.
  const visibleNodes = flattenVisible(tree.roots, collapsed)

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
            {/* Σ TAT is the work; the span is the elapsed plan, and with the
                tree they are different numbers the moment anything runs in
                parallel. Both, rather than one standing in for the other. */}
            {tree.spanDays > 0 && (
              <> · <b title="Earliest finish if every step starts the day after the one it waits for">
                Plan runs to day {tree.spanDays}
              </b></>
            )}
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
        <div className="flex flex-col gap-2">
          {/*
            Said once, above the table, rather than under every step. With the
            tasks now drawn inline as the step's own children there is one
            pattern on the screen to explain, not one per row.
          */}
          <p className="m-0 text-caption text-content-muted">
            Services nest under the one they wait for. A task list is shown when its service is
            clicked on the client page, and a step can't complete until its mandatory tasks are
            ticked; required documents (📎) gate it the same way.
          </p>
          <div className="overflow-x-auto rounded-card border border-border bg-surface shadow-rest">
            <table className="w-full border-collapse text-sm" aria-label="Services">
              <thead>
                <tr className="border-b border-border text-left">
                  <th scope="col" className="px-3 py-2 text-caption font-semibold text-content-muted">Service</th>
                  <th scope="col" className="w-48 px-3 py-2 text-caption font-semibold text-content-muted">
                    Schedule
                  </th>
                  <th scope="col" className="w-20 px-3 py-2 text-right text-caption font-semibold text-content-muted">
                    TAT
                    <span className="block font-normal">(days)</span>
                  </th>
                  <th scope="col" className="w-48 px-3 py-2 text-caption font-semibold text-content-muted">Default responsible</th>
                  <th scope="col" className="w-20 px-3 py-2 text-center text-caption font-semibold text-content-muted">Sign-off</th>
                  {editable && (
                    <th scope="col" className="w-32 px-3 py-2 text-caption font-semibold text-content-muted">Order</th>
                  )}
                </tr>
              </thead>
              {visibleNodes.map((node) => (
                <StepTreeRows
                  key={node.step.id}
                  templateId={templateId}
                  node={node}
                  spanDays={tree.spanDays}
                  /* The reorder route replaces the whole flat sequence, so the
                     ↑/↓ pair moves a step in `steps` — not in the tree. */
                  index={steps.indexOf(node.step)}
                  total={steps.length}
                  /* Indentation is what says "waits for that one" to the eye,
                     and says nothing at all to a screen reader — so the name
                     the removed column used to print goes back in, visually
                     hidden, for the readers the tree shape does not reach. */
                  parentName={
                    node.depth > 0
                      ? steps.find((s) => s.id === node.step.dependsOnStepId)?.name ?? null
                      : null
                  }
                  editable={editable}
                  collapsed={collapsed.has(node.step.id)}
                  users={userList}
                  roles={roleList}
                  onToggle={() => toggle(node)}
                  onMove={move}
                  onRemove={() => doRemoveStep(node.step)}
                />
              ))}
            </table>
          </div>
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
          isActive={detail.isActive}
          dependsOnTemplateIds={detail.dependsOnTemplateIds ?? []}
          activeOrder={activeOrder}
          catalogueEntries={catalogueEntries}
        />
      )}
    </div>
  )
}

/**
 * One service, as a branch of the tree: its own `<tbody>` holding the field
 * row, then a row per task and per required document, then — while the step
 * is editable — the two add forms. Its children are separate `<tbody>`s that
 * follow, drawn by the same component one depth deeper.
 *
 * <p>The rowgroup is what keeps a step's controls scoped to that step even
 * though the tree is flattened into one table: "remove this task" is always
 * inside the same group as the service it belongs to, never a sibling's.
 */
function StepTreeRows({
  templateId,
  node,
  spanDays,
  index,
  total,
  parentName,
  editable,
  collapsed,
  users,
  roles,
  onToggle,
  onMove,
  onRemove,
}: {
  templateId: number
  node: StepNode<ObJourneyTemplateStep>
  /** The template's last day — what the schedule bar is drawn against. */
  spanDays: number
  /** Position in the flat sequence, which is what the reorder route replaces. */
  index: number
  total: number
  /** The step this one waits for, for readers the indentation does not reach. */
  parentName: string | null
  editable: boolean
  collapsed: boolean
  users: readonly UserRef[]
  /** Every role, active or not — a retired one still has to render its name. */
  roles: readonly Role[]
  onToggle: () => void
  onMove: (from: number, to: number) => void
  onRemove: () => void
}) {
  const { step, depth } = node
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
      : null
  const columns = editable ? 6 : 5
  const bar = scheduleBar(node, spanDays)
  /*
    An editable step always has something to hide — the two add forms — so the
    chevron is offered whether or not it has children yet. A published one
    with nothing under it has nothing to toggle, and gets a spacer so its name
    still lines up with its siblings'.
  */
  const collapsible =
    editable || node.children.length > 0 || step.items.length > 0 || step.docs.length > 0

  return (
    <tbody className="border-b border-border last:border-b-0">
      <tr>
        <td className="px-3 py-2 align-top">
          <div className="flex items-start">
            <TreeGuides depth={depth} />
            {collapsible ? (
              <button
                type="button"
                onClick={onToggle}
                aria-expanded={!collapsed}
                aria-label={`${collapsed ? 'Expand' : 'Collapse'} ${step.name}`}
                className="mr-1 mt-px inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-control text-content-muted hover:bg-subtle hover:text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
              >
                <span aria-hidden>{collapsed ? '▸' : '▾'}</span>
              </button>
            ) : (
              <span aria-hidden className="mr-1 h-5 w-5 shrink-0" />
            )}
            {/* Depth-first, so the badge counts down the tree rather than
                along the flat sequence — which is the order a reader's eye
                takes the chain in. */}
            <span
              aria-hidden
              className="mr-2 mt-px inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-primary-soft text-caption font-semibold tabular-nums text-primary"
            >
              {node.number}
            </span>
            <span className="min-w-0">
              <span className="font-medium text-content">{step.name}</span>
              {step.description && (
                <span className="block text-caption text-content-muted">{step.description}</span>
              )}
              {/*
                Said on the root rows only. Indentation already says "waits for
                the row above"; nothing says "waits for nothing", and a step at
                the left margin is otherwise indistinguishable from the first
                step of a chain — which is precisely the misreading the
                contract warns about: null means parallel, not first.
              */}
              {depth === 0 ? (
                <span className="block text-caption text-content-muted">
                  No dependency, runs in parallel
                </span>
              ) : (
                parentName && <span className="sr-only">Waits for {parentName}</span>
              )}
            </span>
          </div>
        </td>
        <td className="px-3 py-2 align-top">
          <span className="block whitespace-nowrap text-caption text-content-muted">
            {scheduleLabel(node)}
          </span>
          {/* Decorative — the range above it is the accessible form of the
              same fact, so a screen reader hears it once. */}
          <span
            aria-hidden
            className="mt-1 block h-1.5 w-full overflow-hidden rounded-full bg-subtle"
          >
            <span
              className="block h-full rounded-full bg-primary"
              style={{ marginLeft: bar.left, width: bar.width }}
            />
          </span>
        </td>
        <td className="px-3 py-2 text-right align-top tabular-nums text-content">{step.tatDays}</td>
        <td className="px-3 py-2 align-top">
          {responsible == null ? (
            <span className="text-content-muted">—</span>
          ) : (
            <span className="flex items-start gap-2">
              <span
                aria-hidden
                className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary-soft text-[10px] font-semibold text-primary"
              >
                {initials(responsible)}
              </span>
              <span className="min-w-0 break-words text-content">{responsible}</span>
            </span>
          )}
        </td>
        <td className="px-3 py-2 text-center align-top">
          {/* Set when the step is added — the backend has no step-edit route,
              so the box states the fact rather than offering a dead control. */}
          <input
            type="checkbox"
            checked={step.requiresSignoff}
            disabled
            aria-label={`${step.name} requires client sign-off`}
            title="Set when the step is added — remove and re-add the step to change it"
            className="mt-1 h-4 w-4 rounded border-border"
          />
        </td>
        {editable && (
          <td className="px-3 py-1.5 align-top">
            <span className="inline-flex gap-1">
              <Button
                type="button"
                variant="ghost"
                size="sm"
                disabled={index <= 0}
                aria-label={`Move ${step.name} up`}
                onClick={() => onMove(index, index - 1)}
              >
                ↑
              </Button>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                disabled={index < 0 || index === total - 1}
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
      {!collapsed && (
        <>
          <StepItemRows
            templateId={templateId}
            step={step}
            depth={depth}
            editable={editable}
            restSpan={columns - 1}
          />
          <StepDocRows
            templateId={templateId}
            step={step}
            depth={depth}
            editable={editable}
            restSpan={columns - 1}
          />
        </>
      )}
    </tbody>
  )
}

/**
 * The rails that make the indentation read as a tree rather than as padding.
 * One per ancestor level, so a task three deep sits behind three of them and
 * the eye can follow any of them back up to the service it belongs to.
 */
function TreeGuides({ depth }: { depth: number }) {
  if (depth <= 0) return null
  return (
    <span aria-hidden className="flex shrink-0 self-stretch">
      {Array.from({ length: depth }, (_, level) => (
        <span key={level} className="w-5 border-l border-border" />
      ))}
    </span>
  )
}

/**
 * A task or a document, as a child row of its service. One `<td>` under the
 * Service column and one empty cell spanning the rest — the schedule, TAT,
 * owner and sign-off are the *step's* facts, and repeating them against every
 * task would claim a task has a TAT of its own.
 */
function LeafRow({
  depth,
  restSpan,
  children,
}: {
  /** The owning step's depth; the row draws itself one level in from it. */
  depth: number
  restSpan: number
  children: React.ReactNode
}) {
  return (
    <tr>
      <td className="px-3 py-1 align-top">
        <div className="flex items-start">
          <TreeGuides depth={depth + 1} />
          {children}
        </div>
      </td>
      <td colSpan={restSpan} />
    </tr>
  )
}

/**
 * The Task List — one row per task under its service, each with the tick box
 * the client page will offer, plus an "Add a task…" row while the step is
 * editable. Items are always added mandatory, matching the mockup, which has
 * no optional flag on a task.
 *
 * <p>The boxes are drawn but inert, and deliberately so: this screen defines
 * the list, the client's own journey is where it is ticked off. They are
 * `aria-hidden` for the same reason — the task's text is the row's meaning,
 * and a screen reader announcing "checkbox, not checked" on a template would
 * be describing a control that does not exist.
 */
function StepItemRows({
  templateId,
  step,
  depth,
  editable,
  restSpan,
}: {
  templateId: number
  step: ObJourneyTemplateStep
  depth: number
  editable: boolean
  restSpan: number
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

  return (
    <>
      {step.items.map((item) => (
        <LeafRow key={item.id} depth={depth} restSpan={restSpan}>
          <span className="flex min-w-0 flex-1 items-start gap-2">
            <input
              type="checkbox"
              checked={false}
              readOnly
              aria-hidden
              tabIndex={-1}
              title="Ticked on the client page, not here"
              className="mt-0.5 h-4 w-4 shrink-0 rounded border-border"
            />
            <span className="min-w-0 break-words text-content">
              {item.label}
              {!item.mandatory && <span className="text-content-muted"> (optional)</span>}
            </span>
            {editable && (
              <button
                type="button"
                aria-label={`Remove ${item.label}`}
                className="ml-auto shrink-0 rounded-chip leading-none text-content-muted hover:text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                onClick={() => doRemove(item.id, item.label)}
              >
                ✕
              </button>
            )}
          </span>
        </LeafRow>
      ))}
      {editable && (
        <LeafRow depth={depth} restSpan={restSpan}>
          <form onSubmit={submit} className="flex min-w-0 flex-1 items-center gap-2">
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
        </LeafRow>
      )}
    </>
  )
}

/**
 * The required-document checklist, kept from the backend's own contract even
 * though the mockup's template editor omits it — the client page's document
 * gate has to be authored somewhere, and this is its only write surface. Same
 * child-row shape as the Task List, marked 📎 so the two are distinguishable
 * at a glance without a heading between them.
 */
function StepDocRows({
  templateId,
  step,
  depth,
  editable,
  restSpan,
}: {
  templateId: number
  step: ObJourneyTemplateStep
  depth: number
  editable: boolean
  restSpan: number
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

  return (
    <>
      {step.docs.map((doc) => (
        <LeafRow key={doc.id} depth={depth} restSpan={restSpan}>
          <span className="flex min-w-0 flex-1 items-start gap-2">
            <span aria-hidden className="mt-0.5 w-4 shrink-0 text-center leading-none">
              📎
            </span>
            <span className="min-w-0 break-words text-content">
              {doc.label}
              {!doc.required && <span className="text-content-muted"> (optional)</span>}
            </span>
            {editable && (
              <button
                type="button"
                aria-label={`Remove ${doc.label}`}
                className="ml-auto shrink-0 rounded-chip leading-none text-content-muted hover:text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                onClick={() => doRemove(doc.id, doc.label)}
              >
                ✕
              </button>
            )}
          </span>
        </LeafRow>
      ))}
      {editable && (
        <LeafRow depth={depth} restSpan={restSpan}>
          <form onSubmit={submit} className="flex min-w-0 flex-1 items-center gap-2">
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
        </LeafRow>
      )}
    </>
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

/**
 * Depth-first, skipping anything a collapsed ancestor hides. The tree is a
 * tree; the table is a list of rows — this is the only place the two meet.
 */
function flattenVisible(
  roots: readonly StepNode<ObJourneyTemplateStep>[],
  collapsed: ReadonlySet<number>,
): StepNode<ObJourneyTemplateStep>[] {
  const rows: StepNode<ObJourneyTemplateStep>[] = []
  const walk = (nodes: readonly StepNode<ObJourneyTemplateStep>[]) => {
    for (const node of nodes) {
      rows.push(node)
      if (!collapsed.has(node.step.id)) walk(node.children)
    }
  }
  walk(roots)
  return rows
}

/** Up to two initials for the responsible avatar — `avatar-stack`'s own rule. */
function initials(name: string): string {
  const parts = name.split(' ').filter(Boolean).slice(0, 2)
  const letters = parts.map((part) => part[0]?.toUpperCase() ?? '').join('')
  return letters || '?'
}

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
