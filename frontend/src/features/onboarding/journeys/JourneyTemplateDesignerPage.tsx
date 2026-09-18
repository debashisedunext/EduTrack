import * as React from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'

import { ApiError } from '@/api/http'
import type { ObJourneyTemplateDetail } from '@/api/generated/model/obJourneyTemplateDetail'
import type { ObJourneyTemplateStage } from '@/api/generated/model/obJourneyTemplateStage'
import type { ObJourneyTemplateStep } from '@/api/generated/model/obJourneyTemplateStep'
import type { UserRef } from '@/api/generated/model'
import { useListObProducts } from '@/api/generated/onboarding-masters/onboarding-masters'
import { useListObJourneyTemplates } from '@/api/generated/onboarding-journeys/onboarding-journeys'
import { useListUsers } from '@/api/generated/users/users'

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
  SlideOver,
  SlideOverBody,
  SlideOverContent,
  SlideOverFooter,
  SlideOverHeader,
  SlideOverTitle,
} from '@/components/ui/slide-over'
import { toast } from '@/components/ui/use-toast'

import {
  useAddJourneyTemplateTask,
  useAddJourneyTemplateStepDoc,
  useAddJourneyTemplateStepItem,
  useBeginJourneyTemplateRevision,
  useJourneyTemplate,
  usePublishJourneyTemplate,
  useRemoveJourneyTemplateStep,
  useRemoveJourneyTemplateStepDoc,
  useRemoveJourneyTemplateStepItem,
  useReorderJourneyTemplateTasks,
  useUpdateJourneyTemplateStep,
} from './journeyTemplateQueries'
import { formatTemplateTotalTatDays, templateTotalTatDays } from './journeyTemplateTat'
import { buildStepTree, scheduleLabel, type StepNode } from './journeyTemplateTree'
import {
  EXPANDED,
  TREE_LEVELS,
  buildTaskFilter,
  countsLabel,
  levelOf,
  presetFor,
  stageKey,
  stageSpan,
  taskKey,
  type TaskFilter,
  type TreeLevel,
  type TreeViewState,
} from './journeyTemplateTreeView'
import { ModuleServiceAdmin } from './ModuleServiceAdmin'
import { TaskImportDialog } from './TaskImportDialog'

/**
 * C-102 · OB-07's journey template designer: back link, a two-line head, then
 * the board — one container per implementation stage, holding a card per task
 * with Schedule, TAT (days), Implementor, Sign-off and, on a draft, Order;
 * each card holding its **Checklist**, and each strip and row ending with the
 * button that writes into it.
 *
 * <h2>Two grounds, and the dependency is a fact rather than a shape</h2>
 *
 * <p>It was a flat list with a "Service depends on" cell reading
 * {@code ↳ 3. Cleansing & field mapping}, then one table with indentation,
 * then cards nested inside the card they waited for — each of them trying to
 * draw the dependency as the shape of the screen.
 *
 * <p>Nesting read well for a chain of two and badly for everything else. A
 * task three deep sat in a box in a box in a box, its own checklist further
 * in again, and collapsing the head of a chain took work nobody was looking
 * at off the screen with it. So the tree is two grounds now: a stage is a
 * bordered container with a header and a body, and every task in it is a card
 * in that body — held or not, all at one level, in dependency-walk order so a
 * task still reads directly under the one it waits for. The checklist is the
 * innermost box, and the only thing a card ever contains.
 *
 * <p>The dependency has not gone anywhere; it is simply written down. A card
 * that waits on another says {@code Dependency: ‹name›}, and one waiting on a
 * task in a different stage names that stage too. {@code
 * journeyTemplateTree.ts} still walks the chain and still produces the
 * **Schedule** column's day ranges from that walk: the earliest a step could
 * begin if nothing slips, in working days relative to journey start, never
 * dates, because a template has no client and therefore no calendar.
 *
 * <p>What a {@code <table>} gave for free and this pays for is in
 * {@link MetaCells}: the right-hand columns hold their line because their
 * widths are fixed. And a value's tie to its column heading, which each value
 * now carries as its own {@code sr-only} label.
 *
 * <p>Nothing in the layout can say "waits for nothing" now that nothing is
 * enclosed, so a card says it in words and in its marker — a filled disc and
 * the **Parallel** chip against a hollow ring. That is not decoration: {@code
 * dependsOnStepId} being null means the step runs in parallel from journey
 * start, not that it is first.
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
 * <h2>A step is an implementation stage, and the six arrive on their own</h2>
 *
 * <p>A step used to be a name an admin typed. It is now a row of the OB-15
 * Implementation Stage master, and a Module Service is <b>created already
 * holding one step per active stage</b> — Configuration, Data Migration,
 * Reports, Training, Communication, Third Party Integration — seeded by the
 * server inside the same transaction as the create. So opening a new service
 * shows six steps nobody added, and the work on this screen is configuring
 * them rather than inventing them.
 *
 * <p><b>There is no "add a step" control, and that is the requirement rather
 * than an omission.</b> The stages come from one place — the Implementation
 * Stage master in the sidebar — and a service holds the set that existed when
 * it was created. A picker on this screen would be a second way to decide
 * which stages a service has, and two ways to decide one thing is how two
 * services end up described in different vocabularies. Configure the stages
 * here; change which stages exist on OB-15.
 *
 * <p>The consequence worth knowing: a stage removed from a draft with ✕
 * cannot be re-added from this screen. {@code POST
 * /onboarding/journey-templates/{templateId}/steps} still exists and still
 * takes a stage id — it is what the seeding itself is built on, and deleting a
 * route to hide a button would be removing a capability rather than a
 * control.
 *
 * <h2>Where the table diverges from the mockup, and why</h2>
 *
 * <p>The mockup draws name, TAT, responsible, depends-on and sign-off as
 * editable fields on every row. They are editable here now, through the row's
 * own <b>Edit</b> form rather than as inline cells — {@code PATCH
 * /onboarding/journey-template-steps/{stepId}} is the route the seeded stages
 * made necessary, because six steps arriving with a one-day TAT and no owner
 * cannot be corrected by the remove-and-re-add that used to be the only
 * option, which takes the task list with it. <b>The name and the stage are
 * still not editable</b>, and that is the rule rather than a gap: a step is
 * its stage. The mockup's editable template-name input is out for its own
 * reason — there is no rename route on a version.
 *
 * <p>The mockup's inert tick boxes on each list item went the same way, and
 * for a related reason: this screen defines the checklist, the client's own
 * journey page is where it is answered, and a row of unclickable checkboxes
 * spent the reader's attention on a control that does not exist here. What an
 * admin is authoring is what the {@link ChecklistPanel} grid now shows —
 * label, type, and whether it gates the task.
 *
 * <h2>Five levels, and the screen says which one each row is</h2>
 *
 * <p>Stage, Step and Task each carry their level as a badge beside the name,
 * and the fifth is captioned <b>Checklist</b> rather than "sub-task" — the
 * word that named the level directly under a row called Task, and the reason
 * this screen read as an indeterminate number of nested things. The
 * indentation is one 24px rail per ancestor with an elbow into the row, so
 * depth is counted rather than inferred, and each stage carries its group down
 * the left edge of everything inside it.
 *
 * <h2>A task names one person, and usually names nobody</h2>
 *
 * <p>The Implementor column is {@code owner_user_id} and is the whole of
 * "who does this". The owning role and the backup owner that used to sit
 * beside it are gone ({@code V20260914_1830}): the role was a fallback
 * {@code ObJourneyInstantiationService} never consulted, because no per-client
 * role→user resolver exists, so a task carrying only a role instantiated onto
 * nobody; and leave coverage is a fact about a live journey rather than about
 * a plan, so it stayed on {@code ob_journey_steps} where it can be set per
 * client.
 *
 * <p><b>Leaving the Implementor unset is the ordinary answer, not a gap.</b> A
 * Module Service is authored once and boarded for every client that buys the
 * product, so naming a person here says "this one person does it for everyone,
 * for ever" — true of almost no task. A task with nobody pinned goes to the
 * <em>project's</em> implementor when a journey is created, which is where
 * "who, for this client" is actually known. The cell therefore reads "The
 * project's implementor" rather than a dash: a dash read as a half-configured
 * row and sent people hunting for a field to fill in.
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
  const reorder = useReorderJourneyTemplateTasks()
  const products = useListObProducts()
  const users = useListUsers({ isActive: true, limit: 200 })
  const userList = users.data?.data ?? []
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

  // Dropped, not merged, whenever the server's own steps change — the reason
  // `WorkflowDesignerPage` gives: a merge would have to guess whether a row
  // that moved underneath is this drag arriving back or somebody else's edit.
  React.useEffect(() => {
    setOrdered(null)
  }, [detail.steps])

  const editable = detail.publishedAt == null
  /*
    B-131 · adding a checklist entry is allowed one state further than
    everything else on this page.

    `editable` is the strict rule and stays strict: a published version's
    tasks, TATs, implementors, dependencies and order are frozen, because
    changing any of them changes what a client already onboarding is looking
    at. Adding a tick to a task takes nothing away and contradicts no answer
    already given, so the ACTIVE version accepts one too — and the server
    back-fills it onto the journeys already running from that version, which
    is the whole reason an admin wants it.

    A retired version is excluded on both counts. It is what its clients are
    still running and it is never offered again.
  */
  const checklistEditable = editable || detail.isActive
  const state = editable ? 'Draft' : detail.isActive ? 'Active' : 'Retired'
  const steps = ordered ?? detail.steps
  const dirty = editable && orderChanged(steps, detail.steps)
  /*
    The critical path, not Σ tatDays. Tasks that wait for nothing run
    alongside each other, so adding their TATs answered "how much work is in
    this service" under a heading that says how long it takes — see
    `journeyTemplateTat.ts`. It is the same number `tree.spanDays` carries,
    which is why the header no longer prints both.
  */
  const totalTatDays = templateTotalTatDays(steps)
  const product = products.data?.data.find((p) => p.id === detail.productId)

  /*
    Two trees, and the split is what makes four levels work.

    The **template-wide** tree is where the Schedule column comes from: Day 1
    is the day the journey starts, so a task's day range has to be computed
    across every task the template holds, not restarted inside each stage.

    The **per-stage** trees are what the rows are drawn from. A stage is a
    group, so its tasks nest under one another by `dependsOnStepId` within it;
    a task whose dependency lives in another stage becomes a root of its own
    stage's tree — `buildStepTree` already treats an out-of-list parent that
    way — and gets a caption instead of an indent, because a row cannot be
    indented under a parent in another group.
  */
  const tree = React.useMemo(() => buildStepTree(steps), [steps])
  const scheduleOf = React.useMemo(() => {
    const byId = new Map<number, StepNode<ObJourneyTemplateStep>>()
    for (const node of tree.flat) byId.set(node.step.id, node)
    return byId
  }, [tree])

  /*
    Every stage the template holds, each with its own tasks — **including the
    stages holding none**, which is the usual state of a service somebody has
    just created and where "+ Add a task" has to live.
  */
  const stageGroups = React.useMemo(
    () =>
      [...(detail.stages ?? [])]
        .sort((a, b) => a.sequence - b.sequence || a.id - b.id)
        .map((stage) => {
          const tasks = steps.filter((step) => step.templateStageId === stage.id)
          return { stage, tasks, tree: buildStepTree(tasks) }
        }),
    [detail.stages, steps],
  )
  /*
    Collapsed rather than expanded is the stored set, so a step added or
    revealed by a reorder arrives open — the default is "show me everything",
    and only a deliberate collapse is remembered. Keyed by step id, so it
    survives a reorder; a removed step leaves a stale id behind, which costs
    nothing and cannot resurrect as anything but that same step.
  */
  const [view, setView] = React.useState<TreeViewState>(EXPANDED)
  const [query, setQuery] = React.useState('')
  /* Closed by default. The paragraph it holds explains the vocabulary of the
     screen, which is worth a click on a first visit and worth none of the page
     on every visit after it. */
  const [showHow, setShowHow] = React.useState(false)

  /** OB-07 · the "Import tasks" dialog — download, upload, preview, confirm. */
  const [importing, setImporting] = React.useState(false)

  /*
    Stages and tasks share the one set, prefixed rather than merged, because
    both are now disclosure rows: `stage:201` and `task:201` are different
    things. `showSubtasks` is the fifth level and needs a flag of its own — a
    task's children are both nested tasks *and* its checklist, and one chevron
    cannot reveal the first without the second.
  */
  const toggleRow = (key: string, name: string) => {
    const wasCollapsed = view.collapsed.has(key)
    setView((held) => {
      const next = new Set(held.collapsed)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return { ...held, collapsed: next }
    })
    setAnnouncement(`${name} ${wasCollapsed ? 'expanded' : 'collapsed'}.`)
  }

  /*
    Every row key the presets close. `taskIds` is read off the staged order
    rather than the server's, so "Collapse all" during an unsaved reorder
    closes what is actually on the screen.
  */
  const treeKeys = React.useMemo(
    () => ({
      stageIds: (detail.stages ?? []).map((stage) => stage.id),
      taskIds: steps.map((step) => step.id),
    }),
    [detail.stages, steps],
  )
  const level = levelOf(view, treeKeys)
  const applyLevel = (next: TreeLevel) => {
    setView(presetFor(next, treeKeys))
    setAnnouncement(
      `Showing every ${TREE_LEVELS.find((l) => l.value === next)?.label.toLowerCase()} level.`,
    )
  }

  /*
    A filter narrows the tree; it never rewrites what is collapsed. So a
    search, then a clear, puts the reader back exactly where they were rather
    than in a tree somebody else opened for them.
  */
  const filter = React.useMemo(() => buildTaskFilter(steps, query), [steps, query])

  /*
    Arrow keys across the tree, driven off the DOM rather than off a parallel
    model of it.

    <p>Every disclosure control carries `data-tree-toggle` and its depth, so
    "the next row" is the next such button in document order — which is the
    order the reader sees, collapse and filter already applied, without this
    handler having to re-derive either. ↑↓ walk, ← closes or steps out to the
    parent, → opens or steps in, Home/End jump the ends.

    <p>It fires only when the key came from a toggle, so typing in the filter
    box or in the "Add a task…" composer is untouched — those are inputs, not
    tree rows, and an arrow key inside them means what it always means.

    <p>Deliberately not `role="treegrid"`: that pattern puts focus on the row
    and reaches the row's own buttons through an interaction mode, and this
    table's rows are full of them — edit, remove, reorder, two composers. A
    table that is still a table, with the toggles wired together, keeps every
    one of those a normal tab stop.
  */
  const treeKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    const from = (event.target as HTMLElement).closest<HTMLElement>('[data-tree-toggle]')
    if (!from) return
    const toggles = Array.from(
      event.currentTarget.querySelectorAll<HTMLElement>('[data-tree-toggle]'),
    )
    const at = toggles.indexOf(from)
    if (at < 0) return

    const depthOf = (el: HTMLElement) => Number(el.dataset.treeDepth ?? '0')
    const focusAt = (index: number) => {
      const next = toggles[index]
      if (!next) return
      next.focus()
      event.preventDefault()
    }

    switch (event.key) {
      case 'ArrowDown':
        focusAt(at + 1)
        break
      case 'ArrowUp':
        focusAt(at - 1)
        break
      case 'Home':
        focusAt(0)
        break
      case 'End':
        focusAt(toggles.length - 1)
        break
      case 'ArrowRight':
        if (from.getAttribute('aria-expanded') === 'false') {
          from.click()
          event.preventDefault()
        } else {
          focusAt(at + 1)
        }
        break
      case 'ArrowLeft':
        if (from.getAttribute('aria-expanded') === 'true') {
          from.click()
          event.preventDefault()
        } else {
          // Out to the parent: the nearest toggle above this one that sits a
          // level shallower. A root row has none, and nothing moves.
          for (let i = at - 1; i >= 0; i -= 1) {
            if (depthOf(toggles[i]) < depthOf(from)) {
              focusAt(i)
              return
            }
          }
        }
        break
      default:
        break
    }
  }
  /*
    ↑/↓ moves a task within its own stage, never across one. The staged list
    is still the whole template's, because that is what `steps` is and what
    the tree is rebuilt from — but the two positions swapped are always both
    inside the stage, so no task ever changes which group it belongs to by
    being nudged off the end of it.
  */
  const move = (stageId: number, from: number, to: number) => {
    const inStage = steps.filter((step) => step.templateStageId === stageId)
    if (to < 0 || to >= inStage.length) return
    const globalFrom = steps.indexOf(inStage[from])
    const globalTo = steps.indexOf(inStage[to])
    const next = moveItem(steps, globalFrom, globalTo)
    if (next === steps) return
    setOrdered(next)
    setAnnouncement(
      `${inStage[from].name} moved to position ${to + 1} of ${inStage.length} in its stage.`,
    )
  }

  /*
    One request per stage whose order actually changed. The route takes one
    stage's task set, so a save that touched two stages is two calls — done in
    series rather than in parallel because each one invalidates the detail
    query the next one's `If-Match` is read from.
  */
  const saveOrder = async () => {
    try {
      const changed = stageGroups.filter(({ stage, tasks }) => {
        const before = detail.steps.filter((step) => step.templateStageId === stage.id)
        return orderChanged(tasks, before)
      })
      for (const { stage, tasks } of changed) {
        await reorder.mutateAsync({
          templateId,
          stageId: stage.id,
          taskIds: tasks.map((t) => t.id),
          etag,
        })
      }
      setOrdered(null)
      toast({ title: 'Task order saved' })
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
      Full-bleed, not centred in a `max-w-4xl` column. The board is the widest
      thing in the module — a name column plus five meta columns, and a
      checklist grid inside every card — and the measure that suits a page of
      prose left it scrolling sideways with empty gutters either side of it.
    */
    <div className="flex w-full flex-col gap-4 p-6">
      <Button asChild variant="ghost" size="sm" className="self-start">
        <Link to="/onboarding/journey-templates">← Module Service</Link>
      </Button>

      {/*
        Two lines, and they used to be five.

        <p>Name, product, version and state on one; the versioning sentence and
        the totals condensed onto a second. What left is the paragraph
        explaining what a step, a task and a checklist are — needed on a first
        visit and noise on a fiftieth, so it is behind "How this reads" now
        rather than above the board every time.
      */}
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="m-0 text-h2 text-content">{detail.name}</h1>
            {product && <Chip variant="neutral">{product.name}</Chip>}
            <Chip variant="neutral">v{detail.version}</Chip>
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
              <>Active <b>v{detail.version}</b> — Begin revision to edit; it publishes as <b>v{detail.version + 1}</b>. Clients already in flight keep v{detail.version}. <b>Checklist entries are the exception</b> — add one here and it reaches those clients now.</>
            ) : (
              <>Retired <b>v{detail.version}</b> — read-only for good.</>
            )}
            {' · '}
            {/* One figure, where there used to be two. "Total TAT" was Σ of the
                task TATs and "Plan runs to day N" was the critical path, and
                the pair invited the reader to work out which one meant how
                long the service takes. It is the second, so that is what Total
                TAT now is, and the other line is gone rather than restated. */}
            <b title={`${formatTemplateTotalTatDays(totalTatDays)} — the longest chain of dependencies, with everything else running alongside it`}>
              Total TAT: {totalTatDays}d
            </b>
            {' '}across {steps.length} task{steps.length === 1 ? '' : 's'} in{' '}
            {stageGroups.length} step{stageGroups.length === 1 ? '' : 's'}
            {' '}
            <button
              type="button"
              aria-expanded={showHow}
              aria-controls="ob-how-this-reads"
              onClick={() => setShowHow((open) => !open)}
              className="rounded-control text-caption text-primary underline underline-offset-2 hover:text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
            >
              {showHow ? 'Hide' : 'How this reads'}
            </button>
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
            <Button type="button" variant="secondary" onClick={() => setImporting(true)}>
              Import tasks
            </Button>
          )}
          {editable && (
            <Button
              type="button"
              disabled={publish.isPending || steps.length === 0}
              title={steps.length === 0 ? 'Add at least one task before publishing' : undefined}
              onClick={doPublish}
            >
              Publish v{detail.version}
            </Button>
          )}
        </div>
      </header>

      {editable && (
        <TaskImportDialog templateId={templateId} open={importing} onOpenChange={setImporting} />
      )}

      <p role="status" aria-live="polite" className="sr-only">
        {announcement}
      </p>

      {/* The vocabulary of the screen, on request. It sat above the board on
          every visit; it is read once and then in the way. */}
      {showHow && (
        <p
          id="ob-how-this-reads"
          className="m-0 rounded-card border border-dashed border-border px-3 py-2 text-caption text-content-muted"
        >
          Each <b>step</b> groups the tasks that belong to it. A <b>task</b> with no dependency runs in
          parallel from day 1; a task that declares one starts the day that one
          ends. A task carries the TAT, the implementor and its <b>checklist</b> — the ticks and
          documents that have to be answered before it can complete on a client&rsquo;s journey.
          Required items gate the task; optional ones do not.
          {' '}
          <b>Total TAT is the longest chain of dependencies</b>, not the sum of every task: two
          tasks of 1 and 2 days that depend on nothing both start on day 1, so they cost 2 days
          between them. Chain the second behind the first and it is 3.
          {' '}
          A task with no implementor is not unassigned — it goes to whoever is running the
          project it is boarded for.
        </p>
      )}

      {stageGroups.length === 0 ? (
        <EmptyState
          title="No implementation steps on this Module Service"
          description="A service picks up the active steps when it is created. This one has none, which means the master was empty at the time."
        />
      ) : (
        <div className="flex flex-col gap-2">
          <TreeToolbar
            level={level}
            onLevel={applyLevel}
            query={query}
            onQuery={setQuery}
            matchCount={filter == null ? null : filter.visibleTaskIds.size}
          />

          {/*
            The board, and no longer a table.

            <p>A stage is a container with a body, a task is a box inside that
            body, and a checklist is a box inside the task — three grounds,
            each a step lighter than the one it sits in, so depth is read off
            enclosure and fill rather than counted in pixels of indent.

            <p>Two things a `<table>` gave for free are paid for here. The
            column alignment: `MetaCells`' fixed widths, plus every card body
            padding on the left only, so a nested card ends on its parent's
            right edge. And the association between a value and its column
            heading, which each value now carries as its own `sr-only` label —
            the strip below is `aria-hidden`, because it lines the columns up
            for the eye and would otherwise be read out before every row.
          */}
          {/* One card holding the whole rail, rather than a card per stage:
              the line has to run between the steps, and it cannot cross a
              border and a gap to do it. No `min-width` and no sideways
              scroll either — nothing is in fixed columns any more, so the
              tree reflows instead of forcing the page wide. */}
          <div
            role="group"
            aria-label="Steps and tasks"
            onKeyDown={treeKeyDown}
            className="rounded-card border border-border bg-surface px-5 py-1.5 shadow-rest"
          >
            {stageGroups.map(({ stage, tasks, tree: stageTree }, index) => (
                <StageBlock
                  key={stage.id}
                  stage={stage}
                  index={index}
                  total={stageGroups.length}
                  /* `flat`, not `roots`: every task in the stage is drawn at
                     one level, so this is the whole list in dependency-walk
                     order rather than the heads of its chains. */
                  nodes={stageTree.flat}
                  /* A filter opens what it has to in order to show a hit, and
                     leaves the stored collapse alone — clearing the box puts
                     the reader back where they were. */
                  open={filter != null || !view.collapsed.has(stageKey(stage.id))}
                  ctx={{
                    templateId,
                    stageId: stage.id,
                    tasks,
                    allSteps: steps,
                    allStages: detail.stages ?? [],
                    scheduleOf,
                    spanDays: tree.spanDays,
                    editable,
                    checklistEditable,
                    view,
                    filter,
                    users: userList,
                    etag,
                    onToggle: toggleRow,
                    onMove: move,
                    onRemove: doRemoveStep,
                  }}
                />
            ))}
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

      {/*
        Where "+ Add step" used to be. A service's stages are decided in one
        place — the master — and this sentence is what stops the absence
        reading as a screen missing its add button.
      */}
      {editable && (
        <p className="m-0 text-caption text-content-muted">
          These steps come from the{' '}
          <Link to="/onboarding/implementation-stages" className="text-primary hover:underline">
            Implementation Stage master
          </Link>
          , picked up when this Module Service was created. Edit a step&rsquo;s TAT, owner and
          checklist here; add or retire steps themselves on that page.
        </p>
      )}

      {/* Drawn only when the service has stages to list — an empty state here
          would repeat the one the board is already showing. */}
      {stageGroups.length > 0 && (
        <ClientSignoffPanel
          templateId={templateId}
          groups={stageGroups}
          scheduleOf={scheduleOf}
          users={userList}
          editable={editable}
          etag={etag}
        />
      )}

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
 * The one control that makes a deep tree readable: how far down it is open,
 * and a box to narrow it.
 *
 * <h2>"Show to" is four presets, not a filter</h2>
 *
 * <p>Each segment rewrites the collapsed set outright — see
 * {@code journeyTemplateTreeView.ts}'s header for why a cap over it would
 * leave rows whose {@code aria-expanded} could not be answered honestly. The
 * highlight is therefore <em>derived</em> from the state rather than stored:
 * toggle one row by hand and no segment is lit, because the tree is genuinely
 * no longer at any one level.
 *
 * <p>Expand all and Collapse all are the same two presets the ends of the
 * control already are. They are here anyway, because "Show to · Stage" does
 * not read as "collapse everything" to somebody meeting this screen for the
 * first time, and the words people look for should be on the screen.
 */
function TreeToolbar({
  level,
  onLevel,
  query,
  onQuery,
  matchCount,
}: {
  /** The preset the tree currently matches, or null once a row is toggled by hand. */
  level: TreeLevel | null
  onLevel: (level: TreeLevel) => void
  query: string
  onQuery: (query: string) => void
  /** Tasks the filter is showing, or null when the box is empty. */
  matchCount: number | null
}) {
  return (
    <div className="flex flex-wrap items-center gap-2 rounded-card border border-border bg-surface px-3 py-2">
      <span id="tree-level-label" className="text-caption font-semibold text-content-muted">
        Show to
      </span>
      {/*
        `aria-pressed` rather than a radiogroup: none of the four is pressed
        once the reader has opened a row by hand, and a radiogroup with no
        checked radio is a shape the pattern does not have.
      */}
      <div
        role="group"
        aria-labelledby="tree-level-label"
        className="flex overflow-hidden rounded-control border border-border"
      >
        {TREE_LEVELS.map(({ value, label, hint }) => (
          <button
            key={value}
            type="button"
            aria-pressed={level === value}
            title={hint}
            onClick={() => onLevel(value)}
            className={`border-r border-border px-3 py-1 text-caption last:border-r-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary ${
              level === value
                ? 'bg-primary font-semibold text-white'
                : 'bg-surface text-content-muted hover:bg-subtle hover:text-content'
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      <Button type="button" size="sm" variant="secondary" onClick={() => onLevel('subtask')}>
        Expand all
      </Button>
      <Button type="button" size="sm" variant="secondary" onClick={() => onLevel('stage')}>
        Collapse all
      </Button>

      <div className="ml-auto flex items-center gap-2">
        {matchCount != null && (
          <span className="text-caption text-content-muted">
            {matchCount === 0
              ? 'Nothing matches'
              : `${matchCount} task${matchCount === 1 ? '' : 's'} shown`}
          </span>
        )}
        <Input
          value={query}
          onChange={(event) => onQuery(event.target.value)}
          placeholder="Filter tasks and checklist items…"
          aria-label="Filter tasks and checklist items"
          className="h-8 w-56 text-caption"
        />
        {query !== '' && (
          <Button type="button" size="sm" variant="ghost" onClick={() => onQuery('')}>
            Clear
          </Button>
        )}
      </div>
    </div>
  )
}

/**
 * Everything a task card needs that is the same for every card in a stage.
 *
 * <p>A bundle rather than eighteen props threaded through two levels of
 * recursion: a task draws its own children, so every prop a card takes is a
 * prop it also has to pass on, and the ones that never vary inside a stage are
 * exactly the ones worth passing once.
 */
interface TaskContext {
  templateId: number
  /** The stage these cards live in — what a cross-stage dependency is measured against. */
  stageId: number
  /** This stage's tasks in order — what ↑/↓ permutes, and what index/total read. */
  tasks: readonly ObJourneyTemplateStep[]
  /** Every task on the template — dependencies cross stages. */
  allSteps: readonly ObJourneyTemplateStep[]
  /** Every stage — for naming the stage a cross-stage dependency lives in. */
  allStages: readonly ObJourneyTemplateStage[]
  /** Task id → its node in the template-wide tree, for the Schedule column. */
  scheduleOf: ReadonlyMap<number, StepNode<ObJourneyTemplateStep>>
  spanDays: number
  editable: boolean
  /**
   * B-131 · `editable`, or the active version. Gates the checklist composer
   * alone — every other editor on a card reads `editable`.
   */
  checklistEditable: boolean
  view: TreeViewState
  filter: TaskFilter | null
  users: readonly UserRef[]
  etag: string | null
  onToggle: (key: string, name: string) => void
  onMove: (stageId: number, from: number, to: number) => void
  onRemove: (step: ObJourneyTemplateStep) => void
}

/**
 * The nodes to draw at one level of the tree: all of them, or the ones a
 * filter is showing.
 *
 * <p>Replaces the flatten-the-whole-tree walk the table needed. Cards nest as
 * DOM children now, so each level filters its own siblings and recurses —
 * there is no flat list of rows to build, and depth is the shape of the markup
 * rather than a number carried on a row.
 */
function visibleNodes(
  nodes: readonly StepNode<ObJourneyTemplateStep>[],
  filter: TaskFilter | null,
): readonly StepNode<ObJourneyTemplateStep>[] {
  if (!filter) return nodes
  return nodes.filter((node) => filter.visibleTaskIds.has(node.step.id))
}

/**
 * Step or Task — the level in a word, beside the name that has it. Every card is a
 * Task; a parallel one is told apart by its filled disc and the Parallel chip.
 *
 * <p>The enclosure and the tone say the same thing to the eye; this says it
 * outright, and is why the {@code sr-only} "Step. " it replaced is gone. One
 * announcement, not two.
 */
function LevelBadge({ children }: { children: React.ReactNode }) {
  return (
    <span className="shrink-0 rounded-control border border-border bg-surface px-1 text-caption font-semibold uppercase tracking-wide text-content-muted">
      {children}
    </span>
  )
}

/**
 * One implementation stage and everything inside it — the second of OB-07's
 * five levels, and the only one an admin cannot create or delete here.
 *
 * <h2>A container, not a heading row</h2>
 *
 * <p>This was a tinted row in a flat table, which says where a group *starts*
 * and never where it ends: a task three stages down the page was indented
 * under a heading it had to be scrolled back to. It is now a bordered section
 * with a header strip and a body, and its tasks are boxes inside that body.
 * There is no reading of the screen on which a task belongs to the stage above
 * it rather than the one around it.
 *
 * <h2>Three grounds, darkest outside</h2>
 *
 * <p>The stage sits on {@code bg-subtle}, a task on {@code bg-app}, a
 * checklist on {@code bg-surface} — each a step lighter than the thing it is
 * inside. Depth is carried by fill rather than by counting indents, which is
 * what survives a glance and a narrow screen. The one place the ramp runs out
 * is the dark theme, which has three usable grounds and wants four; a token
 * request rather than a colour invented here.
 *
 * <h2>The header carries no work</h2>
 *
 * <p>No owner, no sign-off, no ✕. A stage is a container: asking who owns
 * "Configuration" has no answer, because the people are on the tasks. The TAT
 * it shows is its tasks' critical path — derived on every render, never
 * stored, and critical-pathed rather than summed because that is the reading
 * `templateTotalTatDays` gives one level up. A stage that totalled on a
 * different rule from its parent would be a bug report waiting to happen.
 *
 * <h2>An empty stage still draws</h2>
 *
 * <p>Which is the usual state of a service somebody has just created: six
 * stages, no tasks. Hiding the empty ones would leave "+ Add a task" nowhere
 * to live and the six stages invisible until the first one was filled in.
 */
function StageBlock({
  ctx,
  stage,
  nodes,
  open,
  index,
  total,
}: {
  ctx: TaskContext
  stage: ObJourneyTemplateStage
  /**
   * Every task in this stage, unfiltered and at one level — the filter is
   * applied here. Depth-first order, so a task still reads directly under
   * the one it waits for; it is simply no longer drawn inside it.
   */
  nodes: readonly StepNode<ObJourneyTemplateStep>[]
  /** Whether this stage's tasks are drawn at all. */
  open: boolean
  /** Its place on the rail — the number in the node, and where the line stops. */
  index: number
  total: number
}) {
  const { tasks, filter, editable, scheduleOf } = ctx
  /*
    The stage's own critical path, on the same rule as the template's: two
    tasks in Configuration that wait for nothing take the longer of the two,
    not their sum. Computed over this stage's tasks alone, so a task held by
    something in another stage is a root here — which is right for "how long
    does this stage take", and is why this number can be smaller than the
    stage's Schedule span, which includes the wait.
  */
  const stageTat = templateTotalTatDays(tasks)
  const span = stageSpan(tasks, scheduleOf)
  const shown = visibleNodes(nodes, filter)
  /*
    A filter that matched nothing in this stage still draws the stage. A
    silently missing group reads as "this service has two stages", which is a
    worse lie than an empty one.
  */
  const filteredOut = filter != null && shown.length === 0 && tasks.length > 0
  const headingId = `ob-stage-${stage.id}`

  /*
    The rail. One line down the board with a node per step on it, so the six
    or seven steps of a service read as a sequence rather than as six boxes
    that happen to be stacked. It stops at the last node instead of running
    past the end — a line continuing into white space says there is more.

    NODE_CENTRE is the node's own middle: `py-[18px]` plus half of `h-8`.
  */
  const NODE_CENTRE = 34
  const railStyle =
    total <= 1
      ? { display: 'none' }
      : index === 0
        ? { top: NODE_CENTRE, bottom: 0 }
        : index === total - 1
          ? { top: 0, height: NODE_CENTRE }
          : { top: 0, bottom: 0 }

  return (
    <section aria-labelledby={headingId} className="relative py-[18px] pl-[52px]">
      <span aria-hidden className="absolute left-[15px] w-0.5 bg-border" style={railStyle} />
      {/*
        Filled when the step holds work, an outline when it does not. That is
        the difference a reader is scanning a new service for — six stages
        arrive empty and the ones that have been filled in are the answer.
      */}
      <span
        aria-hidden
        className={`absolute left-0 top-[18px] flex h-8 w-8 items-center justify-center rounded-full border-2 text-caption font-semibold tabular-nums ${
          tasks.length > 0
            ? 'border-primary bg-primary text-white'
            : 'border-border bg-surface text-content-muted'
        }`}
      >
        {index + 1}
      </span>

      <header className="flex min-h-8 flex-wrap items-center gap-x-3 gap-y-2">
        {/* A stage with nothing in it has nothing to disclose, and gets a
            spacer instead so the six stages of a new service still line up. */}
        {tasks.length > 0 ? (
          <button
            type="button"
            data-tree-toggle
            data-tree-depth={0}
            aria-expanded={open}
            aria-label={`${open ? 'Collapse' : 'Expand'} ${stage.name}`}
            onClick={() => ctx.onToggle(stageKey(stage.id), stage.name)}
            className="inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-control text-content-muted hover:bg-surface hover:text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          >
            <span aria-hidden>{open ? '▾' : '▸'}</span>
          </button>
        ) : (
          <span aria-hidden className="h-5 w-5 shrink-0" />
        )}
        <LevelBadge>Step</LevelBadge>
        <h2 id={headingId} className="m-0 text-h3 text-content">
          {stage.name}
        </h2>
        <span className="rounded-chip bg-subtle px-2.5 py-0.5 text-caption text-content-muted">
          {filteredOut ? 'No matches' : countsLabel(tasks)}
        </span>
        {/* The rolled-up span and the stage's own critical path, as one line
            of text. They were two fixed columns; nothing on this board lines
            up in columns any more, so they read where they sit. */}
        {span && (
          <span className="text-caption tabular-nums text-content-muted">
            <span className="sr-only">Schedule: </span>
            {scheduleLabel(span)}
            {stageTat > 0 && (
              <>
                {' · '}
                <span className="sr-only">Total TAT: </span>
                {stageTat}d
              </>
            )}
          </span>
        )}
        <span className="flex-1" />
        {/* The only way work is written into a Module Service, and it sits at
            the end of the row of the stage it writes into. A stage closed on
            an empty group used to hide it entirely. */}
        {editable && (
          <AddTaskSlideOver
            templateId={ctx.templateId}
            stage={stage}
            siblings={ctx.allSteps}
            users={ctx.users}
          />
        )}
      </header>

      {open && (
        <>
          {shown.length > 0 && (
            /* Level two, on a rail of its own one step in from the board's.
               `pl-[26px]` is what the cards' elbows reach back across. */
            <ul className="m-0 ml-2 mt-3.5 flex list-none flex-col gap-2 p-0 pl-[26px]">
              {shown.map((node, i) => (
                <TaskCard
                  key={node.step.id}
                  ctx={ctx}
                  node={node}
                  last={i === shown.length - 1}
                />
              ))}
            </ul>
          )}

          {tasks.length === 0 && filter == null && (
            <p className="m-0 mt-3 text-caption text-content-muted">
              This step came from the Implementation Stage master with no tasks in it.
            </p>
          )}
        </>
      )}
    </section>
  )
}

/**
 * "+ Add task" — the only way work is written into a Module Service, and the
 * one form on this page that asks for a name.
 *
 * <p>The name field is back after a spell without one, and the difference is
 * the level it sits at: a <em>stage</em> is an OB-15 value and takes its name
 * from the master, while a <em>task</em> is what somebody decides has to
 * happen inside that stage. There is still no way to create a stage here.
 *
 * <p>TAT defaults to one working day — the same default the server applies,
 * restated so the field is never empty and the form can be submitted the
 * moment a name is typed.
 *
 * <h2>A panel from the right, not a form in the middle of the board</h2>
 *
 * <p>It expanded in place at the foot of its stage, which pushed every stage
 * below it down the page and moved the row the reader was looking at. The
 * fields are unchanged and so is what they write; only where they are asked
 * for is. The trigger names no stage because it sits on that stage's strip —
 * the panel's own title is what says which one it will write into, and it is
 * the thing on screen while the form is being filled in.
 */
function AddTaskSlideOver({
  templateId,
  stage,
  siblings,
  users,
}: {
  templateId: number
  stage: ObJourneyTemplateStage
  siblings: readonly ObJourneyTemplateStep[]
  /** Active users — who the new task can be pinned to, when one has to be. */
  users: readonly UserRef[]
}) {
  const addTask = useAddJourneyTemplateTask()
  const [open, setOpen] = React.useState(false)
  const [name, setName] = React.useState('')
  const [tatDays, setTatDays] = React.useState('1')
  const [ownerUserId, setOwnerUserId] = React.useState('')
  const [requiresSignoff, setRequiresSignoff] = React.useState(false)
  const [dependsOnStepId, setDependsOnStepId] = React.useState('')
  const [submitted, setSubmitted] = React.useState(false)
  const [serverErrors, setServerErrors] = React.useState<Record<string, string>>({})

  const tatValue = Number(tatDays)
  const nameError = name.trim() ? null : 'A task needs a name'
  const tatError =
    !tatDays.trim() || !Number.isFinite(tatValue) || tatValue < 1
      ? 'TAT must be at least 1 working day'
      : null

  const reset = () => {
    setName('')
    setTatDays('1')
    setOwnerUserId('')
    setRequiresSignoff(false)
    setDependsOnStepId('')
    setSubmitted(false)
    setServerErrors({})
  }

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    setSubmitted(true)
    if (nameError || tatError) return
    setServerErrors({})
    try {
      await addTask.mutateAsync({
        templateId,
        stageId: stage.id,
        data: {
          name: name.trim(),
          tatDays: tatValue,
          // A create takes nulls at face value — there is no existing value
          // for an omitted field to say nothing about, so no clear flags here.
          // Null is also the *usual* answer: the project's own implementor
          // takes the task at instantiation.
          ownerUserId: ownerUserId ? Number(ownerUserId) : null,
          requiresSignoff,
          dependsOnStepId: dependsOnStepId ? Number(dependsOnStepId) : null,
        },
      })
      toast({ title: `${name.trim()} added to ${stage.name}` })
      reset()
      setOpen(false)
    } catch (error) {
      setServerErrors(fieldErrors(error))
      toast({
        title: 'Could not add that task',
        description: problemDetail(error),
        variant: 'danger',
      })
    }
  }

  return (
    <SlideOver
      open={open}
      onOpenChange={(next) => {
        /* Closing by any route — Escape, the ✕, the overlay, Cancel — throws
           the half-typed task away rather than leaving it to reappear the
           next time the panel is opened against a different stage. */
        if (!next) reset()
        setOpen(next)
      }}
    >
      {/* The visible label is the same two words on every strip, because the
          strip it sits on is what says which stage it writes into. A screen
          reader has no strip, so the accessible name carries the stage —
          otherwise this page offers seven buttons called "+ Add task". */}
      <Button
        type="button"
        variant="secondary"
        size="sm"
        aria-label={`Add a task to ${stage.name}`}
        onClick={() => setOpen(true)}
      >
        + Add task
      </Button>

      {/* Named by its own title — Radix wires `aria-labelledby` to it, so an
          `aria-label` here would be dead weight the title overrides. */}
      <SlideOverContent>
        <SlideOverHeader>
          {/* Titled with the stage it will write into: the panel covers the
              right of the board, so the strip it was opened from is no longer
              necessarily the thing under the reader's eye. */}
          <SlideOverTitle>New task in {stage.name}</SlideOverTitle>
        </SlideOverHeader>

        <SlideOverBody>
          <form id={`add-task-${stage.id}`} onSubmit={submit} className="flex flex-col gap-3">
        <label className="flex min-w-0 flex-col gap-1 text-caption text-content-muted sm:col-span-2">
          Task name
          <Input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="What has to happen in this step?"
            aria-label={`Name of the new task in ${stage.name}`}
            className="h-8 text-caption"
          />
        </label>
        <label className="flex min-w-0 flex-col gap-1 text-caption text-content-muted">
          TAT (days)
          <Input
            type="number"
            min={1}
            value={tatDays}
            onChange={(e) => setTatDays(e.target.value)}
            aria-label={`TAT in working days for the new task in ${stage.name}`}
            className="h-8 text-caption"
          />
        </label>
        {/* One owner field, and leaving it alone is the ordinary answer: a
            service is authored once and boarded for many projects, so the
            person is usually decided on the project rather than here. */}
        <label className="flex min-w-0 flex-col gap-1 text-caption text-content-muted">
          Implementor
          <select
            value={ownerUserId}
            onChange={(e) => setOwnerUserId(e.target.value)}
            aria-label={`Implementor for the new task in ${stage.name}`}
            className="h-8 rounded-control border border-border bg-surface px-2 text-caption text-content"
          >
            <option value="">The project&rsquo;s implementor</option>
            {users.map((user) => (
              <option key={user.id} value={String(user.id)}>{user.displayName}</option>
            ))}
          </select>
        </label>
        <label className="flex min-w-0 flex-col gap-1 text-caption text-content-muted sm:col-span-2">
          Dependency
          <select
            value={dependsOnStepId}
            onChange={(e) => setDependsOnStepId(e.target.value)}
            aria-label={`The task the new task in ${stage.name} depends on`}
            className="h-8 rounded-control border border-border bg-surface px-2 text-caption text-content"
          >
            {/* Any task on the template, not only this stage's: Data Migration
                genuinely waits on Configuration, and a picker that refused to
                say so would push people into inventing filler tasks. */}
            <option value="">Nothing — runs in parallel</option>
            {siblings.map((task) => (
              <option key={task.id} value={String(task.id)}>{task.name}</option>
            ))}
          </select>
        </label>
        <label className="flex items-center gap-2 pb-2 text-caption text-content">
          <input
            type="checkbox"
            checked={requiresSignoff}
            onChange={(e) => setRequiresSignoff(e.target.checked)}
            aria-label={`Client sign-off required for the new task in ${stage.name}`}
            className="h-4 w-4 rounded border-border"
          />
          Sign-off
        </label>

            <div className="flex flex-col gap-1">
              {submitted && nameError && <span className="text-xs text-danger">{nameError}</span>}
              {submitted && tatError && <span className="text-xs text-danger">{tatError}</span>}
              {serverErrors.name && <span className="text-xs text-danger">{serverErrors.name}</span>}
              {serverErrors.tatDays && (
                <span className="text-xs text-danger">{serverErrors.tatDays}</span>
              )}
            </div>
          </form>
        </SlideOverBody>

        {/* Outside the scrolling body, so Add task is reachable without
            scrolling to the bottom of the fields. `form` is what still ties
            the submit button to the form it sits outside of. */}
        <SlideOverFooter>
          <Button
            type="button"
            size="sm"
            variant="ghost"
            onClick={() => { reset(); setOpen(false) }}
          >
            Cancel
          </Button>
          <Button
            type="submit"
            form={`add-task-${stage.id}`}
            size="sm"
            disabled={addTask.isPending}
          >
            Add task
          </Button>
        </SlideOverFooter>
      </SlideOverContent>
    </SlideOver>
  )
}

/**
 * One task, as a box inside its stage — and, when something waits on it, a box
 * holding boxes.
 *
 * <h2>The dependency is written down, not drawn</h2>
 *
 * <p>A dependent task was a card inside its predecessor's card. It is now a
 * card beside it — every task in a stage at one level, in the order the
 * dependency walk produces, so a held task still reads directly under what
 * holds it and says {@code Dependency: ‹name›} rather than sitting in it. A
 * task held by something in <em>another</em> stage names that stage too,
 * which is the caption this card has always carried; it is simply the only
 * form now rather than the exception.
 *
 * <h2>Step or Task is the dependency, never the position</h2>
 *
 * <p>With nothing enclosed, the marker is what separates the two: a filled
 * disc and the <b>Parallel</b> chip for a task that waits for nothing, a
 * hollow ring for one that is held. Reading position instead would call every
 * card a Step and claim the journey starts in eleven places at once, which is
 * precisely the misreading the contract warns about.
 */
function TaskCard({
  ctx,
  node,
  last,
}: {
  ctx: TaskContext
  node: StepNode<ObJourneyTemplateStep>
  /** The last card on this stage's rail — where the line stops. */
  last: boolean
}) {
  const [editing, setEditing] = React.useState(false)
  const [confirmingRemove, setConfirmingRemove] = React.useState(false)
  const { step, depth } = node
  const {
    editable,
    checklistEditable,
    filter,
    view,
    users,
    scheduleOf,
    spanDays,
    allSteps,
    allStages,
    tasks,
  } = ctx

  /*
    Null is the ordinary case, and it is not "unassigned": the task goes to the
    project's own implementor when a journey is created from this service. So
    the cell says whose it will be rather than drawing a dash, which read as a
    half-configured row and sent people looking for a field to fill in.

    Falling through to `user #id` covers an owner whose user row the active
    list does not offer — deactivated since the task was written, or simply
    not loaded yet.
  */
  const implementor =
    step.ownerUserId == null
      ? null
      : users.find((u) => u.id === step.ownerUserId)?.displayName ?? `user #${step.ownerUserId}`

  /* Falls back to the stage node only if the template-wide tree has somehow
     not placed this task — it always does; the fallback is so a missing entry
     draws a wrong day rather than crashing. */
  const schedule = scheduleOf.get(step.id) ?? node
  const isStep = step.dependsOnStepId == null
  const parent =
    step.dependsOnStepId == null
      ? null
      : allSteps.find((candidate) => candidate.id === step.dependsOnStepId) ?? null
  const crossStageParent =
    depth === 0 && parent && parent.templateStageId !== ctx.stageId
      ? {
          name: parent.name,
          stageName:
            allStages.find((g) => g.id === parent.templateStageId)?.name ?? 'another step',
        }
      : null

  /* ↑/↓ moves a task within the flat stage sequence, which is what
     `PUT .../steps/order` replaces — so it visibly reorders siblings and does
     nothing visible to a parent and its child, which the tree draws in
     dependency order regardless. */
  const index = tasks.indexOf(step)
  const total = tasks.length

  /* A filter opens the path down to a hit without disturbing what the reader
     had collapsed — clear the box and the tree is as they left it. */
  const collapsed = filter == null && view.collapsed.has(taskKey(step.id))
  /** The step this task sits in, for the confirmation to name. */
  const ownStageName = allStages.find((g) => g.id === ctx.stageId)?.name ?? 'this step'

  /*
    Which checklist items this task shows. With no filter, all of them. With
    one, all of them if the task's own name matched — the reader asked for this
    task — and otherwise only the items and documents that matched, so a hit is
    not buried in nine rows that did not.
  */
  const visibleItems =
    filter == null || filter.matchedTaskIds.has(step.id)
      ? step.items
      : step.items.filter((item) => filter.matchedItemIds.has(item.id))
  const visibleDocs =
    filter == null || filter.matchedTaskIds.has(step.id)
      ? step.docs
      : step.docs.filter((doc) => filter.matchedDocIds.has(doc.id))
  /*
    B-131 · the checklist can be written one version-state further than
    everything else: a published ACTIVE version takes a new entry, and the
    server back-fills it onto the journeys already running. `composerIsLive`
    is that case, said in the panel before the admin types rather than by the
    toast afterwards.
  */
  const composerIsLive = checklistEditable && !editable

  /*
    Every task carries its own control, at every level. It was offered only
    while the Checklist level was showing, which meant the one way to see a
    single task's checklist was to open every checklist on the board and then
    close the others — and a task drawn with no control at all reads as a
    task that has nothing under it.

    <p>So the control is unconditional and the body follows the task's own
    collapsed state. An empty checklist still opens, onto the line that asks
    what has to be true before the task can complete: that is the prompt to
    write one, and hiding it hides the prompt.
  */
  const collapsible = true
  const hasBody = !collapsed
  const headingId = `ob-task-${step.id}`

  /*
    This card's own piece of the stage's rail: a vertical run joined to the
    card by an elbow. The run carries `+0.5rem` so it bridges the `gap-2`
    between cards and reads as one line rather than a dashed one; on the last
    card it stops at the elbow, the same way the board's rail stops at its
    last node. ELBOW is the row's middle — `py-3` plus half a 22px row.
  */
  const ELBOW = 23

  return (
    <li className="list-none">
      <article
        aria-labelledby={headingId}
        className="relative rounded-[10px] border border-border bg-app"
      >
        <span
          aria-hidden
          className="absolute -left-[26px] w-0.5 bg-border"
          style={{ top: 0, height: last ? ELBOW : 'calc(100% + 0.5rem)' }}
        />
        <span aria-hidden className="absolute -left-[26px] h-0.5 w-[26px] bg-border" style={{ top: ELBOW }} />

      <header className="flex flex-wrap items-center gap-x-3 gap-y-2 px-3.5 py-3">
        {collapsible ? (
          <button
            type="button"
            data-tree-toggle
            data-tree-depth={depth + 1}
            onClick={() => ctx.onToggle(taskKey(step.id), step.name)}
            aria-expanded={!collapsed}
            aria-label={`${collapsed ? 'Expand' : 'Collapse'} ${step.name}`}
            className="mt-px inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-control text-content-muted hover:bg-surface hover:text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          >
            <span aria-hidden>{collapsed ? '▸' : '▾'}</span>
          </button>
        ) : (
          <span aria-hidden className="h-5 w-5 shrink-0" />
        )}

        <span className="flex min-w-0 flex-1 flex-wrap items-center gap-x-2.5 gap-y-1">
          {/* Depth-first, so the badge counts down the tree rather than along
              the flat sequence — the order a reader's eye takes the chain in. */}
          <span
            aria-hidden
            className="inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-primary-soft text-caption font-semibold tabular-nums text-primary"
          >
            {node.number}
          </span>
          <LevelBadge>Task</LevelBadge>
          {/*
            Held or parallel, as the first thing on the row. Nothing about the
            layout can say it now that no card contains another, so the chip
            carries it: a filled dot for a task that waits for nothing, a ring
            and the name of what holds it for one that does.
          */}
          {isStep ? (
            <Chip variant="neutral">
              <span aria-hidden className="h-1.5 w-1.5 rounded-full bg-primary" />
              Parallel
            </Chip>
          ) : (
            parent && (
              <Chip variant="info">
                <span aria-hidden className="h-1.5 w-1.5 rounded-full border border-current" />
                After {parent.name}
              </Chip>
            )
          )}
          <span id={headingId} className="min-w-0 break-words font-semibold text-content">
            {step.name}
          </span>
          {/* The card the plan's last day falls on. Ties are real — two chains
              can finish together — so every task that ends on it is marked
              rather than one picked arbitrarily. */}
          {spanDays > 0 && schedule.endDay === spanDays && (
            <Chip
              variant="warning"
              title="The plan's last day falls on this task — shortening anything else will not finish the journey sooner"
            >
              Plan ends here
            </Chip>
          )}
          {step.description && (
            <span className="basis-full text-caption text-content-muted">{step.description}</span>
          )}
          {crossStageParent ? (
            /*
              The chip says what holds this task; this says where that lives,
              and only when it is somewhere else. A task waiting on one in
              another step is the case a reader is most likely to misread —
              the name alone looks like a sibling — so the step is named.
            */
            <span className="basis-full text-caption text-content-muted">
              Dependency: {crossStageParent.name} · {crossStageParent.stageName}
            </span>
          ) : (
            isStep && (
              /* The Parallel chip above is this fact for the eye; this is the
                 same fact spelled out for a screen reader, which has no chip. */
              <span className="sr-only">No dependency, runs in parallel</span>
            )
          )}
        </span>

        <span className="flex shrink-0 flex-wrap items-center gap-x-4 gap-y-1 text-caption tabular-nums text-content-muted">
          <span className="whitespace-nowrap">
            <span className="sr-only">Schedule: </span>
            {scheduleLabel(schedule)}
            {" / "}
            <span className="sr-only">TAT: </span>
            {step.tatDays}d
            <span className="sr-only"> working days</span>
          </span>

          {/* Nobody pinned is the usual state and is spelled out rather than
              drawn as a dash: the task goes to whoever is running the project
              it is boarded for. A dash read as missing configuration and sent
              people hunting for a field. */}
          <span className="flex min-w-0 items-center gap-2">
            {implementor != null && (
              <span
                aria-hidden
                className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary-soft text-[10px] font-semibold text-primary"
              >
                {initials(implementor)}
              </span>
            )}
            <span className={implementor == null ? "text-content-muted" : "text-content"}>
              <span className="sr-only">Implementor: </span>
              {implementor ?? (
                <span title="Nobody is pinned here, so this task goes to whoever is running the project — set on the project, not on the service">
                  The project’s implementor
                </span>
              )}
            </span>
          </span>

          {/* Read-only here and editable in the card’s own Edit form, rather
              than a live control: sign-off is saved together with the TAT and
              the owner under one precondition, and a cell that wrote on each
              tick would make that form’s Cancel a lie about one of its
              fields. */}
          {step.requiresSignoff ? (
            <Chip variant="success" title="Change it in this task's Edit form">
              <span className="sr-only">Client sign-off: </span>Yes
            </Chip>
          ) : (
            <span className="sr-only">Client sign-off: no</span>
          )}
        </span>

        {editable && (
          <span className="inline-flex shrink-0 gap-0.5">
            <Button
              type="button"
              variant="ghost"
              size="sm"
              className="h-7 w-7 px-0"
              disabled={index <= 0}
              aria-label={`Move ${step.name} up`}
              onClick={() => ctx.onMove(ctx.stageId, index, index - 1)}
            >
              ↑
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              className="h-7 w-7 px-0"
              disabled={index < 0 || index === total - 1}
              aria-label={`Move ${step.name} down`}
              onClick={() => ctx.onMove(ctx.stageId, index, index + 1)}
            >
              ↓
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              className="h-7 w-7 px-0"
              aria-label={`Edit ${step.name}`}
              onClick={() => setEditing((open) => !open)}
            >
              ✎
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              className="h-7 w-7 px-0"
              aria-label={`Remove ${step.name}`}
              onClick={() => setConfirmingRemove(true)}
            >
              ✕
            </Button>
          </span>
        )}

        {/* The end of the task, mirroring "+ Add task" at the end of its
            step's row. `checklistEditable` rather than `editable`: B-131
            opened adding a checklist entry on an ACTIVE version. */}
        {checklistEditable && (
          <AddChecklistSlideOver
            templateId={ctx.templateId}
            step={step}
            composerIsLive={composerIsLive}
          />
        )}
      </header>

      {/*
        Editing opens on the right, like adding does. It expanded in place
        under the row, which pushed every task below it down the board and
        moved the row being edited out from under the cursor — and it put a
        five-field form inside a card whose own body is the checklist, so a
        task being edited and a task showing its checklist looked alike.
      */}
      {editable && (
        <SlideOver
          open={editing}
          onOpenChange={setEditing}
        >
          <SlideOverContent>
            <SlideOverHeader>
              <SlideOverTitle>Edit {step.name}</SlideOverTitle>
            </SlideOverHeader>
            <EditStepForm
              templateId={ctx.templateId}
              step={step}
              siblings={allSteps}
              users={users}
              etag={ctx.etag}
              onClose={() => setEditing(false)}
            />
          </SlideOverContent>
        </SlideOver>
      )}

      {/*
        Removing a task is a write nothing on this page undoes, so it asks
        first. The dependents check still happens on the server — this is the
        "did you mean to" in front of it, not a replacement for it.
      */}
      <Modal open={confirmingRemove} onOpenChange={setConfirmingRemove}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>Remove {step.name}?</ModalTitle>
            <ModalDescription>
              This takes the task out of {ownStageName} along with its checklist
              {step.items.length + step.docs.length > 0
                ? ` — ${step.items.length + step.docs.length} item${
                    step.items.length + step.docs.length === 1 ? '' : 's'
                  }`
                : ''}
              . Anything that waits on it has to be re-pointed first. It cannot be undone.
            </ModalDescription>
          </ModalHeader>
          <ModalFooter>
            <Button variant="secondary" size="sm" onClick={() => setConfirmingRemove(false)}>
              Cancel
            </Button>
            <Button
              variant="danger"
              size="sm"
              onClick={() => {
                setConfirmingRemove(false)
                ctx.onRemove(step)
              }}
            >
              Remove task
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {hasBody && (
        <ChecklistPanel
          templateId={ctx.templateId}
          step={step}
          items={visibleItems}
          docs={visibleDocs}
          editable={editable}
        />
      )}
      </article>
    </li>
  )
}

/**
 * The **Checklist** — everything that has to be answered before a task can
 * complete, drawn as one grid in the innermost box on the screen.
 *
 * <h2>One list, because there was only ever one question</h2>
 *
 * <p>This was two lists with two composers: a Task List ({@code items}) and a
 * required-document list ({@code docs}), stacked, each with its own full-width
 * input. They answer the same question — what gates this task — and the first
 * composer's placeholder read <em>"Add a task…"</em> one indent under a row
 * called a Task, where it added something that was not one. **The word "task"
 * now names exactly one level of this screen**, and the thing underneath it is
 * a checklist item.
 *
 * <p>So the two collections are drawn as one grid with a <b>Type</b> column,
 * and the composer writes to whichever route the type names. The contract is
 * untouched: {@code items} and {@code docs} are still two arrays and four
 * routes, which is why a row carries its kind rather than a merged id — row #4
 * may be item 4 or doc 2, and the ✕ has to know which.
 *
 * <h2>Items first, then documents</h2>
 *
 * <p>The two carry separate {@code sequence} runs, so there is no single order
 * to interleave them into. Grouping by kind at least puts the ticks together
 * and the files together; one ordering across both needs one sequence across
 * both, which is a contract change rather than a screen's decision.
 *
 * <h2>The panel reports; it no longer writes</h2>
 *
 * <p>The composer was the last row of this grid. It is now
 * {@link AddChecklistSlideOver}, opened from "+ Add checklist" at the end of
 * the task's own row — the same place "+ Add task" sits at the end of a
 * stage's strip, one level up. The fields it asks for are the ones that were
 * in the row, and what they write is unchanged.
 *
 * <p>The tick boxes the mockup drew against each item are gone with the rest
 * of the decorative controls. This screen defines the checklist; the client's
 * journey page is where it is answered, and a column of unclickable checkboxes
 * spent a reader's attention on a control that does not exist here.
 */
function ChecklistPanel({
  templateId,
  step,
  items,
  docs,
  editable,
}: {
  templateId: number
  step: ObJourneyTemplateStep
  /** The items to draw — every one, or the ones a filter matched. */
  items: readonly ObJourneyTemplateStep['items'][number][]
  /** The documents to draw — every one, or the ones a filter matched. */
  docs: readonly ObJourneyTemplateStep['docs'][number][]
  /**
   * The strict rule — a draft. Gates removing a row and nothing else here.
   * Deliberately narrower than the condition on adding: B-131 opened
   * *adding* on a published version, not removing. A row a client has
   * already answered cannot be taken back out from under them.
   */
  editable: boolean
}) {
  const removeItem = useRemoveJourneyTemplateStepItem()
  const removeDoc = useRemoveJourneyTemplateStepDoc()

  const rows = [
    ...items.map((item) => ({
      key: `item:${item.id}`,
      id: item.id,
      kind: 'check' as const,
      label: item.label,
      gating: item.mandatory,
    })),
    ...docs.map((doc) => ({
      key: `doc:${doc.id}`,
      id: doc.id,
      kind: 'document' as const,
      label: doc.label,
      gating: doc.required,
    })),
  ]
  const gatingCount = rows.filter((row) => row.gating).length

  /* The row the ✕ was pressed on, held while the confirmation is up. */
  const [confirming, setConfirming] = React.useState<(typeof rows)[number] | null>(null)

  const remove = async (row: (typeof rows)[number]) => {
    setConfirming(null)
    try {
      if (row.kind === 'document') {
        await removeDoc.mutateAsync({ templateId, docId: row.id })
      } else {
        await removeItem.mutateAsync({ templateId, itemId: row.id })
      }
      toast({ title: `${row.label} removed` })
    } catch (error) {
      toast({
        title: 'Could not remove that checklist item',
        description: problemDetail(error),
        variant: 'danger',
      })
    }
  }

  return (
    /* The third level, and the innermost. It was a four-column grid; nothing
       on this board is in columns now, so it is a list on a rail of its own —
       one step in from the task's, the way the task's is one step in from the
       board's. */
    /* The panel carries the name, not the list inside it: a task with an
       empty checklist has no list to hang it on, and it still has to be
       findable — that is the state most of a new service is in. */
    <div role="group" aria-label={`Checklist for ${step.name}`} className="pb-3 pl-[30px] pr-3.5">
      <div className="flex flex-wrap items-center gap-2 py-2">
        <span className="text-caption font-semibold uppercase tracking-wide text-content-muted">
          Checklist
        </span>
        {/* What actually gates the task, said in the caption — so a reader
            knows before opening anything whether this list is six suggestions
            or six blockers. */}
        <span className="rounded-chip bg-subtle px-2.5 py-0.5 text-caption text-content-muted">
          {rows.length === 0
            ? 'Nothing yet'
            : `${rows.length} item${rows.length === 1 ? '' : 's'} · ${gatingCount} required`}
        </span>
        <span className="ml-auto text-caption text-content-muted">
          Defined here, answered on the client&rsquo;s journey
        </span>
      </div>

      {rows.length === 0 ? (
        <p className="m-0 text-caption text-content-muted">
          Nothing on this checklist yet. What has to be true before {step.name} can complete?
        </p>
      ) : (
        <ul className="m-0 flex list-none flex-col p-0 pl-6">
          {rows.map((row, index) => (
            <li key={row.key} className="relative flex items-center gap-3 py-1.5 text-caption">
              <span
                aria-hidden
                className="absolute -left-6 w-0.5 bg-border"
                style={{ top: 0, height: index === rows.length - 1 ? 17 : '100%' }}
              />
              <span
                aria-hidden
                className="absolute -left-6 h-0.5 w-[18px] bg-border"
                style={{ top: 17 }}
              />
              {/* The order the client's journey page will ask for them in. */}
              <span className="w-4 shrink-0 text-right tabular-nums text-content-muted">
                {index + 1}
              </span>
              {/* A tick box for something somebody confirms, a dashed one for
                  a file that has to arrive — the kind, before the words. */}
              <span
                aria-hidden
                className={`inline-flex h-4 w-4 shrink-0 items-center justify-center rounded border-[1.5px] border-border text-[9px] leading-none text-content-muted ${
                  row.kind === 'document' ? 'border-dashed' : ''
                }`}
              >
                {row.kind === 'document' ? '↓' : '✓'}
              </span>
              <span className="min-w-0 flex-1 break-words text-content">{row.label}</span>
              <Chip variant="neutral">{row.kind === 'document' ? 'Document' : 'Check'}</Chip>
              {row.gating ? (
                <Chip variant="info">Required</Chip>
              ) : (
                <span className="text-content-muted">Optional</span>
              )}
              {editable && (
                <button
                  type="button"
                  aria-label={`Remove ${row.label}`}
                  onClick={() => setConfirming(row)}
                  className="shrink-0 rounded-chip leading-none text-content-muted hover:text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                >
                  ✕
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      {/*
        A checklist entry is removed for good and, on a live service, out
        from under clients who may already have answered it — so the ✕ asks
        before it writes, and says which of the two it is about to take.
      */}
      <Modal open={confirming != null} onOpenChange={(next) => !next && setConfirming(null)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>
              Remove this {confirming?.kind === 'document' ? 'document' : 'check'}?
            </ModalTitle>
            <ModalDescription>
              “{confirming?.label}” comes off {step.name}&rsquo;s checklist
              {confirming?.gating
                ? ', and the task stops being gated by it'
                : ''}
              . It cannot be undone.
            </ModalDescription>
          </ModalHeader>
          <ModalFooter>
            <Button variant="secondary" size="sm" onClick={() => setConfirming(null)}>
              Cancel
            </Button>
            <Button
              variant="danger"
              size="sm"
              onClick={() => confirming && void remove(confirming)}
            >
              Remove
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  )
}

/**
 * "+ Add checklist" — what the last row of the checklist grid used to be,
 * asked for in a panel from the right instead.
 *
 * <p>The three controls are the composer's three, in its order: the label,
 * the <b>Type</b> that decides which of the two routes it writes to, and
 * <b>Required</b>, which decides whether the entry gates its task. Nothing
 * was added and nothing dropped — {@code items} and {@code docs} are still
 * two arrays and four routes, and the type is still what picks between them.
 *
 * <p>Filing an item leaves the panel open with the type and the tick
 * <em>unchanged</em> and the cursor back in an empty label, because a
 * checklist is written eight items at a time and re-picking "Check ·
 * Required" eight times is what the old pair of composers charged for.
 * Enter files it too, which is the second way in the composer row had.
 */
function AddChecklistSlideOver({
  templateId,
  step,
  composerIsLive,
}: {
  templateId: number
  step: ObJourneyTemplateStep
  /**
   * B-131 · this version is published and active, so a new entry reaches the
   * clients already onboarding. Said before the admin types rather than by
   * the toast afterwards — the panel looks identical on a draft and on a live
   * service, and the difference is who sees the result.
   */
  composerIsLive: boolean
}) {
  const addItem = useAddJourneyTemplateStepItem()
  const addDoc = useAddJourneyTemplateStepDoc()

  const [open, setOpen] = React.useState(false)
  const [label, setLabel] = React.useState('')
  const [kind, setKind] = React.useState<'check' | 'document'>('check')
  const [gating, setGating] = React.useState(true)
  const labelRef = React.useRef<HTMLInputElement>(null)

  const pending = addItem.isPending || addDoc.isPending

  const submit = async () => {
    const text = label.trim()
    if (!text) return
    try {
      if (kind === 'document') {
        await addDoc.mutateAsync({
          templateId,
          stepId: step.id,
          data: { label: text, required: gating },
        })
        toast({ title: `${text} added to the checklist` })
      } else {
        const { backfilledJourneyCount } = await addItem.mutateAsync({
          templateId,
          stepId: step.id,
          data: { label: text, mandatory: gating },
        })
        /*
          B-131 · the count is said out loud rather than assumed. An admin
          adding an item to a live service is doing it *for* the clients on
          it, and "added to the checklist" alone leaves them unable to tell
          whether it got there — the catalogue would look identical either
          way. Zero is worth saying too: on an active service it means
          nobody is currently onboarding, which is a different thing from
          the edit having failed.
        */
        toast({
          title: `${text} added to the checklist`,
          description: composerIsLive
            ? backfilledJourneyCount === 0
              ? 'No client is currently onboarding with this service, so only clients boarded from now on will see it.'
              : `Added to ${backfilledJourneyCount} client journey${
                  backfilledJourneyCount === 1 ? '' : 's'
                } already in flight.`
            : undefined,
        })
      }
      setLabel('')
      /* The type and the gate are deliberately left as they were — see the
         header. Only the label clears, and the cursor comes back to it. */
      labelRef.current?.focus()
    } catch (error) {
      toast({
        title: 'Could not add that checklist item',
        description: problemDetail(error),
        variant: 'danger',
      })
    }
  }

  return (
    <SlideOver
      open={open}
      onOpenChange={(next) => {
        /* Only the label is thrown away on close. The type and the tick are
           the reader's working setting, exactly as they are between two
           items filed without closing. */
        if (!next) setLabel('')
        setOpen(next)
      }}
    >
      {/* Named for the eye by its position on the task's row, and for a
          screen reader by the task itself — see the same note on the stage
          strip's button. */}
      <Button
        type="button"
        variant="secondary"
        size="sm"
        aria-label={`Add a checklist item to ${step.name}`}
        onClick={() => setOpen(true)}
      >
        + Add checklist
      </Button>

      {/* Named by its title, exactly as the add-task panel is. */}
      <SlideOverContent>
        <SlideOverHeader>
          <SlideOverTitle>New checklist item for {step.name}</SlideOverTitle>
        </SlideOverHeader>

        <SlideOverBody>
          <div className="flex flex-col gap-3">
            <label className="flex min-w-0 flex-col gap-1 text-caption text-content-muted">
              Item
              <Input
                ref={labelRef}
                value={label}
                onChange={(event) => setLabel(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') {
                    event.preventDefault()
                    void submit()
                  }
                }}
                placeholder="Add a checklist item…"
                aria-label={`New checklist item for ${step.name}`}
                className="h-8 text-caption"
              />
            </label>

            <label className="flex min-w-0 flex-col gap-1 text-caption text-content-muted">
              Type
              <select
                value={kind}
                onChange={(event) => setKind(event.target.value as 'check' | 'document')}
                aria-label={`Type of the new checklist item for ${step.name}`}
                title={
                  composerIsLive
                    ? 'Only a check can be added to a published service. A required document is a new obligation on a client who may already have finished this task, so it stays a revision.'
                    : undefined
                }
                className="h-8 rounded-control border border-border bg-surface px-2 text-caption text-content"
              >
                <option value="check">Check</option>
                {/*
                  B-131 · disabled on a published version, because the server
                  refuses it there: `addStepDoc` is still guarded by
                  `requireEditable`, and only `addStepItem` was opened up.
                  Offering it would be a 409 the admin could do nothing
                  about. Disabled rather than removed so the option's absence
                  does not read as this screen having forgotten documents
                  exist — and the select's `title` says why.
                */}
                <option value="document" disabled={composerIsLive}>
                  Document
                </option>
              </select>
            </label>

            <label className="flex items-center gap-2 text-caption text-content">
              <input
                type="checkbox"
                checked={gating}
                onChange={(event) => setGating(event.target.checked)}
                className="h-4 w-4 rounded border-border"
              />
              Required
            </label>

            {composerIsLive && (
              <p className="m-0 text-caption text-content-muted">
                This service is live. A check added here appears immediately on the clients already
                onboarding with this version, and gates their task if it is Required. Clients
                boarded on an earlier version keep the checklist they started on.
              </p>
            )}
          </div>
        </SlideOverBody>

        <SlideOverFooter>
          <Button type="button" size="sm" variant="ghost" onClick={() => setOpen(false)}>
            Cancel
          </Button>
          <Button
            type="button"
            size="sm"
            disabled={pending || !label.trim()}
            onClick={() => void submit()}
          >
            Add
          </Button>
        </SlideOverFooter>
      </SlideOverContent>
    </SlideOver>
  )
}

/**
 * Edit one step of a draft — the form the seeded stages made necessary.
 *
 * <p>A Module Service arrives holding six steps nobody typed, each with a
 * one-day TAT and no owner, so configuring a service <em>is</em> editing
 * steps. Before {@code PATCH /onboarding/journey-template-steps/{stepId}}
 * existed the only correction available was remove-and-re-add, which takes
 * the step's task list and required documents with it — survivable when an
 * admin had typed the step a moment ago, not survivable on a step that
 * arrived with the service.
 *
 * <h2>Neither the name nor the stage is on the form</h2>
 *
 * <p>Not an omission. A step <em>is</em> its implementation stage, and
 * changing which one it is means removing it and adding the other — which is
 * also what makes it obvious that the task list underneath goes too, where a
 * silent re-point would not. The stage's name is shown as the form's own
 * label so the admin knows which row they are in.
 *
 * <h2>Sent as a whole, under one precondition</h2>
 *
 * <p>Every field saves together on Save rather than on each keystroke, so
 * Cancel means something. The {@code If-Match} is the <em>template's</em> tag
 * — a step has no read of its own — which is the tag that catches the edit
 * worth catching: somebody else re-pointing a dependency while this form was
 * open.
 *
 * <p>"Depends on" excludes this step itself and every step that already waits
 * on it, so the picker cannot offer a chain that waits on itself. The server
 * refuses one anyway; the picker not offering it is what stops an admin
 * discovering the rule through an error.
 */
function EditStepForm({
  templateId,
  step,
  siblings,
  users,
  etag,
  onClose,
}: {
  templateId: number
  step: ObJourneyTemplateStep
  /** Every step on the template — the dependency picker's raw candidates. */
  siblings: readonly ObJourneyTemplateStep[]
  /** Active users — who the task can be pinned to, when one has to be. */
  users: readonly UserRef[]
  /** The template's ETag, required as `If-Match`. */
  etag: string | null
  onClose: () => void
}) {
  const update = useUpdateJourneyTemplateStep()
  const [description, setDescription] = React.useState(step.description ?? '')
  const [tatDays, setTatDays] = React.useState(String(step.tatDays))
  /*
    Seeded from the step itself, so the form is correct the moment it opens.
    The role picker that used to sit beside this had to be seeded from a list
    arriving on its own request, and a form opened before that list landed
    initialised to '' and never caught up — so Save silently cleared an owner
    nobody had touched. There is nothing to wait for here.
  */
  const [ownerUserId, setOwnerUserId] = React.useState(
    step.ownerUserId == null ? '' : String(step.ownerUserId),
  )
  const [requiresSignoff, setRequiresSignoff] = React.useState(step.requiresSignoff)
  const [dependsOnStepId, setDependsOnStepId] = React.useState(
    step.dependsOnStepId == null ? '' : String(step.dependsOnStepId),
  )
  const [submitted, setSubmitted] = React.useState(false)
  const [serverErrors, setServerErrors] = React.useState<Record<string, string>>({})

  const tatValue = Number(tatDays)
  const tatError =
    !tatDays.trim() || !Number.isFinite(tatValue) || tatValue < 1
      ? 'TAT must be at least 1 working day'
      : null

  /*
    Everything except this step and its own dependents. Walking *down* rather
    than up: a step that already waits on this one, directly or through a
    chain, would close a loop if this one were pointed back at it.
  */
  const dependents = new Set<number>([step.id])
  for (let pass = 0; pass < siblings.length; pass += 1) {
    for (const candidate of siblings) {
      if (candidate.dependsOnStepId != null && dependents.has(candidate.dependsOnStepId)) {
        dependents.add(candidate.id)
      }
    }
  }
  const dependencyOptions = siblings.filter((s) => !dependents.has(s.id))

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    setSubmitted(true)
    if (tatError) return

    try {
      await update.mutateAsync({
        templateId,
        stepId: step.id,
        etag,
        data: {
          // Sent as an empty string rather than omitted, so clearing a
          // description is something this form can actually do — the server
          // reads blank as null.
          description: description.trim(),
          tatDays: tatValue,
          requiresSignoff,
          // The two halves of "depends on nothing": an explicit clear, because
          // over JSON a null is indistinguishable from a field nobody sent.
          dependsOnStepId: dependsOnStepId ? Number(dependsOnStepId) : undefined,
          clearDependsOn: dependsOnStepId === '',
          // The same two halves on the implementor, for the same reason: a
          // number has no blank value to clear it with.
          ownerUserId: ownerUserId ? Number(ownerUserId) : undefined,
          clearOwnerUserId: ownerUserId === '',
        },
      })
      toast({ title: `${step.name} saved` })
      onClose()
    } catch (error) {
      if (error instanceof ApiError && error.status === 412) {
        toast({
          title: 'Someone else changed this Module Service',
          description: 'Reload the page to see the current steps, then reapply your edit.',
          variant: 'danger',
        })
        return
      }
      const fields = fieldErrors(error)
      if (Object.keys(fields).length > 0) {
        setServerErrors(fields)
        return
      }
      toast({
        title: `Could not save ${step.name}`,
        description: problemDetail(error),
        variant: 'danger',
      })
    }
  }

  return (
    /* No card of its own any more: the panel it opens in is the container,
       and a bordered box inside a bordered panel is one border too many. */
    <form
      onSubmit={submit}
      aria-label={`Edit ${step.name}`}
      className="flex flex-1 flex-col gap-3 overflow-y-auto"
    >
      <p className="m-0 text-caption text-content-muted">
        Editing <b className="text-content">{step.name}</b>. Its name comes from the
        Implementation Stage master and cannot be changed here — remove the step and add another
        step instead.
      </p>

      <div className="grid gap-3">
        <div className="flex min-w-0 flex-col gap-1 text-sm">
          <label htmlFor={`edit-step-tat-${step.id}`} className="font-medium text-content">
            TAT (working days)
          </label>
          <Input
            id={`edit-step-tat-${step.id}`}
            value={tatDays}
            inputMode="numeric"
            aria-describedby={submitted && tatError ? `edit-step-tat-error-${step.id}` : undefined}
            onChange={(e) => setTatDays(e.target.value)}
          />
          {submitted && tatError && (
            <span id={`edit-step-tat-error-${step.id}`} className="text-xs text-danger">
              {tatError}
            </span>
          )}
        </div>

        {/*
          One owner field, and leaving it on the default is the ordinary
          answer rather than an omission — which is why the empty option says
          what happens instead of saying "none". The option carries that on
          its own: the paragraph that used to sit under the select repeated it
          at four times the height, and was the one thing keeping these three
          fields off a single row.
        */}
        <div className="flex min-w-0 flex-col gap-1 text-sm">
          <label htmlFor={`edit-step-person-${step.id}`} className="font-medium text-content">
            Implementor
          </label>
          <select
            id={`edit-step-person-${step.id}`}
            className="h-9 min-w-0 truncate rounded-control border border-border bg-surface px-2 text-sm text-content"
            value={ownerUserId}
            onChange={(e) => setOwnerUserId(e.target.value)}
          >
            <option value="">The project&rsquo;s implementor</option>
            {users.map((user) => (
              <option key={user.id} value={user.id}>
                {user.displayName}
              </option>
            ))}
          </select>
        </div>

        <div className="flex min-w-0 flex-col gap-1 text-sm">
          <label htmlFor={`edit-step-depends-on-${step.id}`} className="font-medium text-content">
            Dependency
          </label>
          <select
            id={`edit-step-depends-on-${step.id}`}
            className="h-9 min-w-0 truncate rounded-control border border-border bg-surface px-2 text-sm text-content"
            value={dependsOnStepId}
            onChange={(e) => setDependsOnStepId(e.target.value)}
          >
            <option value="">∥ none — runs parallel</option>
            {dependencyOptions.map((s) => (
              <option key={s.id} value={s.id}>
                ↳ {s.name}
              </option>
            ))}
          </select>
        </div>
      </div>

      {/* The whole second row. A description is a sentence about the step, and
          a third of a row is not a width anybody writes one in. */}
      <div className="flex flex-col gap-1 text-sm">
        <label htmlFor={`edit-step-description-${step.id}`} className="font-medium text-content">
          Description
        </label>
        <Input
          id={`edit-step-description-${step.id}`}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
        />
      </div>

      <label className="flex items-center gap-2 text-sm text-content">
        <input
          type="checkbox"
          checked={requiresSignoff}
          onChange={(e) => setRequiresSignoff(e.target.checked)}
        />
        Client sign-off required before this step may complete
      </label>

      {serverErrors.tatDays && <span className="text-xs text-danger">{serverErrors.tatDays}</span>}

      <div className="flex gap-2">
        <Button type="submit" size="sm" disabled={update.isPending}>
          Save step
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onClose}>
          Cancel
        </Button>
      </div>
    </form>
  )
}

/**
 * **Client sign-off** — every task in this service the client will be asked to
 * sign off, and the one place all of them can be set at once.
 *
 * <h2>Why a panel when the board already has the column</h2>
 *
 * <p>Sign-off is the field somebody configures in a pass of its own: not
 * "while I am editing this task" but "which of these seven does the client
 * have to approve". Answering that from the board means opening seven Edit
 * forms and reading a column that is two boxes wide, in a service whose tasks
 * are nested three deep across four stages. Here it is one list, in stage
 * order, with the count in the heading.
 *
 * <h2>It writes, and it is the second place that does</h2>
 *
 * <p>Deliberately, and with one rule kept: the board's own Sign-off column
 * stays read-only. The objection to a live tick in a table cell was that the
 * task's Edit form saves TAT, owner and sign-off together under one
 * precondition, so a cell writing on each tick would make that form's Cancel a
 * lie about one of its fields. A panel outside the form is not inside that
 * bargain — each tick here is its own {@code PATCH} carrying nothing but
 * {@code requiresSignoff}.
 *
 * <p>The two surfaces cannot silently disagree, because both send the
 * template's {@code ETag} as {@code If-Match}: a tick here moves the tag, and
 * an Edit form that was open when it happened is refused rather than allowed
 * to write yesterday's value back over it.
 *
 * <h2>Every task, not only the ticked ones</h2>
 *
 * <p>A list of what already needs sign-off cannot be used to ask for sign-off
 * on anything else, which is most of what somebody opens this for. So every
 * task is listed and the ticked ones are checked — the count in the heading is
 * what says how many of them gate.
 */
function ClientSignoffPanel({
  templateId,
  groups,
  scheduleOf,
  users,
  editable,
  etag,
}: {
  templateId: number
  /** Stage and its tasks, in the order the board draws them. */
  groups: readonly {
    stage: ObJourneyTemplateStage
    tasks: readonly ObJourneyTemplateStep[]
  }[]
  /** Task id → its node in the template-wide tree, for the day range. */
  scheduleOf: ReadonlyMap<number, StepNode<ObJourneyTemplateStep>>
  users: readonly UserRef[]
  /** A published version is frozen here exactly as it is everywhere else. */
  editable: boolean
  etag: string | null
}) {
  const update = useUpdateJourneyTemplateStep()
  /* The one row being written, so a slow response disables its own box and
     not the other six — this panel exists to tick several in a row. */
  const [pendingId, setPendingId] = React.useState<number | null>(null)

  const filled = groups.filter((group) => group.tasks.length > 0)
  const all = filled.flatMap((group) => group.tasks)
  const gated = all.filter((task) => task.requiresSignoff).length

  const toggle = async (step: ObJourneyTemplateStep, next: boolean) => {
    setPendingId(step.id)
    try {
      await update.mutateAsync({
        templateId,
        stepId: step.id,
        data: { requiresSignoff: next },
        etag,
      })
      toast({
        title: next
          ? `${step.name} now needs client sign-off`
          : `${step.name} no longer needs client sign-off`,
      })
    } catch (error) {
      toast({
        title: 'Could not change that sign-off',
        description: problemDetail(error),
        variant: 'danger',
      })
    } finally {
      setPendingId(null)
    }
  }

  return (
    <section
      aria-labelledby="ob-signoff-heading"
      className="flex flex-col gap-3 rounded-card border border-border bg-surface p-4 shadow-rest"
    >
      <div className="flex flex-wrap items-center gap-2">
        <h2 id="ob-signoff-heading" className="m-0 text-h3 text-content">
          Client sign-off
        </h2>
        <Chip variant={gated > 0 ? 'info' : 'neutral'}>
          {gated} of {all.length} task{all.length === 1 ? '' : 's'}
        </Chip>
      </div>
      <p className="m-0 text-caption text-content-muted">
        The client is asked to sign off each ticked task on their journey, and the task cannot
        complete until they do.{' '}
        {editable
          ? 'Tick a task to ask for it; each tick saves on its own.'
          : 'Read-only — this version is published.'}
      </p>

      {all.length === 0 ? (
        <p className="m-0 text-caption text-content-muted">
          No tasks on this service yet. Add one to a step above.
        </p>
      ) : (
        <div className="flex flex-col gap-3">
          {filled.map(({ stage, tasks }) => (
            <div key={stage.id} className="flex flex-col gap-0.5">
              <h3 className="m-0 text-caption font-semibold uppercase tracking-wide text-content-muted">
                {stage.name}
              </h3>
              <ul className="m-0 flex list-none flex-col p-0">
                {tasks.map((task) => {
                  const node = scheduleOf.get(task.id)
                  const who = implementorName(task, users)
                  return (
                    <li
                      key={task.id}
                      className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-control px-1 py-1 hover:bg-subtle"
                    >
                      <label className="flex min-w-0 flex-1 items-center gap-2">
                        <input
                          type="checkbox"
                          checked={task.requiresSignoff}
                          disabled={!editable || pendingId === task.id}
                          aria-label={`Client sign-off for ${task.name}`}
                          onChange={(event) => void toggle(task, event.target.checked)}
                          className="h-4 w-4 shrink-0 rounded border-border"
                        />
                        <span
                          className={`min-w-0 break-words text-sm ${
                            task.requiresSignoff ? 'text-content' : 'text-content-muted'
                          }`}
                        >
                          {task.name}
                        </span>
                      </label>
                      <span className="w-24 shrink-0 text-right text-caption tabular-nums text-content-muted">
                        {node ? scheduleLabel(node) : ''}
                      </span>
                      {/* Same wording as the board's own column: nobody pinned
                          means the project's implementor picks it up, which is
                          the ordinary case and not a gap to fill in. */}
                      <span className="w-40 shrink-0 truncate text-caption text-content-muted">
                        {who ?? 'The project’s implementor'}
                      </span>
                    </li>
                  )
                })}
              </ul>
            </div>
          ))}
        </div>
      )}
    </section>
  )
}


// ── pure helpers ─────────────────────────────────────────────────────────────


/**
 * The person pinned to a task, or `null` for the ordinary case: nobody, which
 * means the task goes to whoever is running the project it is boarded for.
 *
 * <p>Falling through to `user #id` covers an owner whose user row the active
 * list does not offer — deactivated since the task was written, or simply not
 * loaded yet. One function because two surfaces read it, and two copies of the
 * fallback is two chances to name a deactivated user differently.
 */
function implementorName(
  step: ObJourneyTemplateStep,
  users: readonly UserRef[],
): string | null {
  if (step.ownerUserId == null) return null
  return users.find((u) => u.id === step.ownerUserId)?.displayName ?? `user #${step.ownerUserId}`
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
