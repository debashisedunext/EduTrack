import * as React from 'react'

import type { ObProjectModuleService } from '@/api/generated/model/obProjectModuleService'
import type { UserRef } from '@/api/generated/model/userRef'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'

import { isSettled } from './moduleStripStats'
import { ObModuleStrip } from './ObModuleStrip'
import { ObStepTimeline } from './ObStepTimeline'
import { ObTaskDialog } from './ObTaskDialog'
import { buildProjectTree, findTaskPath, taskAnchorId, type TreeService } from './projectTree'
import { firstServiceWithWork, type ObTaskFilter } from './taskFilter'
import { isMine, type ProjectTask } from './useProjectTasks'
import type { ObViewerScope } from './viewerScope'

/**
 * The project's work — Module Service → Step → Task → Check list — as an
 * accordion of modules, each opening onto a timeline.
 *
 * <h2>Step is the product's word; the API still says stage</h2>
 *
 * <p>`ObProjectStage`, `stageKey`, `ob_journey_template_stages` — all of them
 * name the level this calls a **Step**. The rename stopped at the labels
 * deliberately: `ob_journey_steps` is the *task* table, so the API's "step" is
 * this screen's Task, and swapping the model's vocabulary would mean a
 * migration across the append-only history to buy one word.
 *
 * <p>So: <b>screen Step = API stage</b>, and <b>screen Task = API step</b>.
 * Read that twice before touching either.
 *
 * <h2>One module open onto everything in it</h2>
 *
 * <p>Each service is a card whose header row carries its figures and a dot per
 * Step; open it and its Steps run down the page as a timeline of accordions,
 * the first of them open. There is no ribbon to pick a Step from and no side
 * rail to pick a service from — both were a click between the reader and the
 * work, and a project boarded through two services is two cards, not a
 * navigator. Modules open and close independently.
 *
 * <p>The task is a <b>popup</b> because acting on it — answering the check
 * list, moving it, logging a call — is a piece of work with a beginning and an
 * end, and the timeline it came from is what the reader wants back when it is
 * done. The dialog stays open across those actions.
 *
 * <h2>The page opens onto one task, not onto forty</h2>
 *
 * <p>A project boarded through three services opens as three rows, one of them
 * open: the <b>first module holding outstanding work</b>, with the first
 * outstanding Step inside it open and its tasks listed. Every other row stays
 * closed, carrying its own figures and a dot per Step, so "where is everyone"
 * is answered without opening anything.
 *
 * <p>It used to open with everything closed, on the argument that a list of
 * services is the cheapest thing to scan. It is also the one thing nobody
 * arrives wanting: a reader opening their own project came to see what is on
 * them, and made the same two clicks — the module, then the Step — every
 * single visit to get there.
 *
 * <p>The seeding happens once, and only once the tasks have arrived: which
 * module has work is not knowable while the journey reads are in flight, and
 * re-seeding on a later refetch would re-open a module the reader had closed.
 * A link naming a task — `?task=` — takes precedence over it entirely, because
 * that reader came for that row.
 *
 * <h2>Pending work, or all of it</h2>
 *
 * <p>The <b>Pending</b> / <b>Show all</b> switch above an open module's Steps
 * is one choice for the whole page, held here — see {@link ObTaskFilter}.
 * Asking a reader the same question again on each module they open would be
 * three answers to one question, and the module they opened second would
 * disagree with the one they opened first.
 *
 * <p>It moves what is <em>drawn</em> and never what is counted: the header's
 * completion, the Module strips and every Step's own fraction are folded from
 * the unfiltered tree.
 *
 * <h2>Who is reading decides what is counted</h2>
 *
 * <p>{@link ObViewerScope} arrives as a prop rather than being derived here:
 * the page header reads the same fold for its own tallies, and two components
 * deciding independently who the reader is would be two answers the first time
 * one of them changed. <b>It is presentation, never permission</b> — see
 * `viewerScope.ts`.
 *
 * <h2>Which modules are open is not in the URL</h2>
 *
 * <p>That is a scroll position rather than a link. A <em>task</em> is a link,
 * which is what `revealTaskId` is for: a mailed `?task=` names one task, and
 * this opens its module, scrolls to its row and opens it.
 */
export interface ObProjectWorkspaceProps {
  services: readonly ObProjectModuleService[]
  tasks: readonly ProjectTask[]
  scope: ObViewerScope
  users: readonly UserRef[]
  isPending: boolean
  escalations?: ReadonlyMap<number, { id: number; raisedBy: string; raisedAt: string; note: string }>
  /**
   * One task to open — `?task=`, from a link that named it.
   *
   * <p>There was a `revealNonce` beside this, bumped so that pressing the same
   * control twice revealed the same task twice. Its one caller was the
   * your-work strip's **Go**, which has been removed from the project page, and
   * an arriving link cannot ask twice — the id alone is enough for it.
   */
  revealTaskId: number | null
  /**
   * Whether this reader reviews *this* project's submitted tasks — its own
   * implementor manager, or an OB Admin.
   *
   * <p>A prop rather than a reading off {@link ObViewerScope}, because it is
   * not a fact about the reader alone: the same person reviews one project and
   * not the next. The page above owns the project record that answers it.
   */
  canReview?: boolean
  /** Drawn under the modules — the escalations and go-live sign-off panels. */
  below?: React.ReactNode
}

export function ObProjectWorkspace({
  services,
  tasks,
  scope,
  users,
  isPending,
  escalations,
  revealTaskId,
  canReview = false,
  below,
}: ObProjectWorkspaceProps) {
  const tree = React.useMemo(() => buildProjectTree(services, tasks, scope), [services, tasks, scope])

  const nameOf = React.useCallback(
    (userId: number) => users.find((u) => u.id === userId)?.displayName ?? null,
    [users],
  )

  /**
   * Which modules are open. Empty until the seeding effect below picks the
   * first one with outstanding work — see the class note.
   */
  const [openSet, setOpened] = React.useState<ReadonlySet<number>>(() => new Set())
  const [chosenTask, setChosenTask] = React.useState<number | null>(null)
  /** Outstanding work, or all of it. One answer for the whole page. */
  const [filter, setFilter] = React.useState<ObTaskFilter>('PENDING')

  const toggle = (journeyId: number) =>
    setOpened((current) => {
      const next = new Set(current)
      if (next.has(journeyId)) next.delete(journeyId)
      else next.add(journeyId)
      return next
    })

  /*
    The tree as the reveal effect sees it. A ref rather than a dependency: the
    effect reacts to an intent — a task was named — and depending on the tree as
    well would re-run it on every refetch, re-opening a module the reader had
    since closed.
  */
  const treeRef = React.useRef(tree)
  treeRef.current = tree

  /*
    Which module the page opens onto, decided once the tasks are in.

    Guarded by a ref rather than by a dependency list: the tree is a fresh
    object on every refetch, and an effect that re-ran on it would re-open a
    module the reader had since closed. `null` means no module has work the
    current filter would draw — a finished project, where nothing opens and
    **Show all** has it all back — so the seed is not spent and the switch can
    still open something.
  */
  const seeded = React.useRef(false)
  React.useEffect(() => {
    if (seeded.current) return
    // A link naming a task outranks it, and reveals below.
    if (revealTaskId != null) {
      seeded.current = true
      return
    }
    const first = firstServiceWithWork(tree, filter)
    if (first == null) return
    seeded.current = true
    setOpened((current) => (current.size > 0 ? current : new Set([first])))
  }, [tree, filter, revealTaskId])

  React.useEffect(() => {
    if (revealTaskId == null) return
    const path = findTaskPath(treeRef.current, revealTaskId)
    if (!path) return
    /*
      A mailed link can name a task that is already finished, and **Pending**
      would draw neither its Step nor its row — the reader would land on a
      project page with nothing revealed and no way to tell why. The link wins.
    */
    const found = findTask(treeRef.current, revealTaskId)
    if (found && isSettled(found.task)) setFilter('ALL')
    setOpened((current) => new Set(current).add(path.journeyId))
    setChosenTask(revealTaskId)
    /*
      After the row has rendered. It does not exist at the moment the state is
      set, so a scroll here would find nothing — one frame is enough and is
      cheaper than an observer for a one-shot reveal.
    */
    const timer = window.setTimeout(() => {
      const row = document.getElementById(taskAnchorId(revealTaskId))
      /*
        Optional on the method as well as on the element: jsdom implements no
        `scrollIntoView` at all, so a bare call throws inside a timer nothing is
        awaiting — an unhandled rejection that fails the run without failing a
        test, which is the worst shape a test failure comes in.
      */
      row?.scrollIntoView?.({ block: 'center', behavior: 'smooth' })
    }, 60)
    return () => window.clearTimeout(timer)
  }, [revealTaskId])

  if (isPending && tasks.length === 0) {
    return <Skeleton className="h-48 w-full" />
  }

  if (tree.length === 0) {
    return (
      <p className="rounded-card border border-border bg-surface px-4 py-6 text-center text-sm text-content-muted">
        This project was boarded through no module service, so there is nothing to run. Add one on
        the client&rsquo;s product record.
      </p>
    )
  }

  /* The open task, wherever it sits — the dialog is one, over the whole page. */
  const found = findTask(tree, chosenTask)

  return (
    <div className="flex flex-col gap-3">
      <ul className="m-0 flex list-none flex-col gap-3 p-0" aria-label="Module services">
        {tree.map((node) => {
          const id = node.service.journeyId
          const isOpen = openSet.has(id)
          const panelId = `ob-service-${id}`
          return (
            <li
              key={id}
              className={cn(
                'overflow-hidden rounded-card border bg-surface shadow-rest',
                isOpen ? 'border-primary' : 'border-border',
              )}
            >
              <ObModuleStrip
                node={node}
                scope={scope}
                nameOf={nameOf}
                isOpen={isOpen}
                onToggle={() => toggle(id)}
                panelId={panelId}
              />
              {isOpen && (
                <div id={panelId} className="border-t border-border px-5 pb-5 pt-2">
                  <ObStepTimeline
                    node={node}
                    scope={scope}
                    nameOf={nameOf}
                    users={users}
                    escalations={escalations}
                    selectedTaskId={found?.task.id ?? null}
                    onSelectTask={setChosenTask}
                    filter={filter}
                    onFilterChange={setFilter}
                  />
                </div>
              )}
            </li>
          )
        })}
      </ul>

      {below}

      <ObTaskDialog
        task={found?.task ?? null}
        crumb={found ? `${found.service.service.serviceName} · ${found.stage.stage.name} · Task ${found.index + 1} of ${found.stage.tasks.length}` : ''}
        users={users}
        yours={found ? isMine(found.task, scope.meId) : false}
        canReview={canReview}
        escalation={found ? (escalations?.get(found.task.id) ?? null) : null}
        onClose={() => setChosenTask(null)}
      />
    </div>
  )
}

/** A task by id, with the Step and service it sits in — for the dialog's crumb. */
function findTask(tree: readonly TreeService[], taskId: number | null) {
  if (taskId == null) return null
  for (const service of tree) {
    for (const stage of service.stages) {
      const index = stage.tasks.findIndex((t) => t.id === taskId)
      if (index >= 0) return { service, stage, task: stage.tasks[index], index }
    }
  }
  return null
}
