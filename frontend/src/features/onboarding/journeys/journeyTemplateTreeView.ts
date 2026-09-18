import type { ObJourneyTemplateStep } from '@/api/generated/model/obJourneyTemplateStep'

/**
 * The designer's tree **view state** — what is open, how deep the tree is
 * showing, and what a filter has narrowed it to. `journeyTemplateTree.ts`
 * builds the shape; this decides how much of that shape is on screen.
 *
 * <h2>Five levels, four tables, and one of them counted twice</h2>
 *
 * <p>The screen reads Module Service → Stage → <b>Step</b> → <b>Task</b> →
 * Checklist. Only four of those are tables: a Step and a Task are the same row
 * of {@code ob_journey_template_steps}, and which one it is falls out of
 * {@code dependsOnStepId}:
 *
 * <ul>
 *   <li><b>Step</b> — {@code dependsOnStepId == null}. Runs in parallel from
 *       day 1. Not "the first task": that is the misreading the contract
 *       itself warns about.</li>
 *   <li><b>Task</b> — held by another row, and starts the day that row ends.
 *       Nests to any depth, because a dependency chain does.</li>
 * </ul>
 *
 * <p>That is why {@link stepAndTaskCounts} keys off the dependency and never
 * off tree depth. A task whose predecessor lives in <em>another stage</em> is
 * drawn at its own stage's left margin — {@code buildStepTree} treats an
 * out-of-list parent as a root — and it is still a Task, because something is
 * still holding it. Counting depth would call it a Step and say the journey
 * starts in two places at once.
 *
 * <h2>Why the levels are a preset and not a filter</h2>
 *
 * <p>"Show to Task" could have been a cap applied over the collapsed set —
 * rows below the cap hidden, their stored expansion untouched. That produces
 * a row whose chevron says expanded while its children are nowhere on the
 * screen, and no honest value for {@code aria-expanded}. So the control
 * <b>rewrites</b> the state instead, exactly like Expand all and Collapse
 * all, and {@link levelOf} reads the level back off the state rather than
 * storing it. Toggle one row afterwards and no segment is highlighted, which
 * is the truth: the tree is no longer at any one level.
 */

/**
 * How deep the tree is opened to. The order is the segmented control's.
 *
 * <p><b>There is no `step` level, and the omission is deliberate.</b> It
 * opened stages and their day-1 tasks while leaving every dependent task
 * closed — a genuine view, and one whose name collided with the Step badge on
 * the cards themselves. Two controls a row apart, one of them a filter and one
 * of them a label, both saying "Step" about different things: the strip is
 * Stage / Task / Checklist now, which is three nouns for three levels of
 * nesting and nothing else.
 *
 * <p>The `subtask` *value* survives the same way, one level down — see its
 * entry below.
 */
export type TreeLevel = 'stage' | 'task' | 'subtask'

export const TREE_LEVELS: readonly { value: TreeLevel; label: string; hint: string }[] = [
  { value: 'stage', label: 'Step', hint: 'Steps only' },
  { value: 'task', label: 'Task', hint: 'Every task, at every depth' },
  /*
    The value stays `subtask` — it is the stored view state and the key half
    of a preset — while the label is what the screen has always meant by it:
    the task's Checklist. "Sub-task" named the level after a row called Task,
    which is the vocabulary that made five levels read as an indeterminate
    number of them.
  */
  { value: 'subtask', label: 'Checklist', hint: 'Everything, each task’s checklist included' },
]

/**
 * One collapsed set covers both stages and tasks, so the two id spaces are
 * prefixed rather than merged — stage 201 and task 201 are different rows.
 */
export function stageKey(stageId: number): string {
  return `stage:${stageId}`
}

export function taskKey(taskId: number): string {
  return `task:${taskId}`
}

export interface TreeViewState {
  /** Row keys the reader has closed. Collapsed rather than expanded is stored,
   *  so a task added tomorrow arrives open. */
  collapsed: ReadonlySet<string>
  /** Task lists and required documents. A level of its own because a task's
   *  children are both nested tasks *and* its checklist — one chevron cannot
   *  reveal the first without the second. */
  showSubtasks: boolean
}

export const EXPANDED: TreeViewState = { collapsed: new Set(), showSubtasks: true }

/** Every row key in the template, for the presets to close. */
export interface TreeKeys {
  stageIds: readonly number[]
  taskIds: readonly number[]
}

/**
 * The state "show the tree down to this level" means.
 *
 * <p>Each level closes every row below it, tasks included. That is what lets
 * a task carry a disclosure control of its own at <em>every</em> level rather
 * than only at Checklist: the control reads the task's own collapsed state,
 * so "Task" means every task drawn and every checklist shut, and opening one
 * of them is a click on that task rather than a jump to another level.
 */
export function presetFor(level: TreeLevel, keys: TreeKeys): TreeViewState {
  const tasks = keys.taskIds.map(taskKey)
  switch (level) {
    case 'stage':
      return { collapsed: new Set([...keys.stageIds.map(stageKey), ...tasks]), showSubtasks: false }
    case 'task':
      return { collapsed: new Set(tasks), showSubtasks: false }
    case 'subtask':
      return EXPANDED
  }
}

/**
 * The level the state is currently at, or `null` when it is at none of them —
 * which is what any manual toggle produces, and what leaves every segment
 * unhighlighted rather than one of them lying.
 */
export function levelOf(state: TreeViewState, keys: TreeKeys): TreeLevel | null {
  const tasks = keys.taskIds.map(taskKey)
  if (state.showSubtasks) return state.collapsed.size === 0 ? 'subtask' : null
  if (sameKeys(state.collapsed, [...keys.stageIds.map(stageKey), ...tasks])) return 'stage'
  /*
    Every task closed and nothing else. It is a preset again — the level that
    draws each task and none of their checklists — because a task's own
    control is what opens one from here, rather than the strip.
  */
  if (sameKeys(state.collapsed, tasks)) return 'task'
  return null
}

function sameKeys(held: ReadonlySet<string>, expected: readonly string[]): boolean {
  if (expected.length === 0) return false
  if (held.size !== expected.length) return false
  return expected.every((key) => held.has(key))
}

/**
 * Steps and Tasks inside one stage, counted separately — what the stage row's
 * chip says. Keyed off the dependency, never off depth: see the header.
 */
export function stepAndTaskCounts(tasks: readonly ObJourneyTemplateStep[]): {
  steps: number
  tasks: number
} {
  let steps = 0
  for (const task of tasks) if (task.dependsOnStepId == null) steps += 1
  return { steps, tasks: tasks.length - steps }
}

/**
 * `3 tasks`, or the empty-step wording when there is nothing in it.
 *
 * <p>One count, not two. The container is captioned **Step** on the screen,
 * so the chip can no longer say "1 step · 1 task" about the cards inside it
 * without using the word for two different things. The parallel-vs-held
 * split {@link stepAndTaskCounts} still makes is drawn on the cards
 * themselves — filled disc and the Parallel chip — rather than counted here.
 */
export function countsLabel(tasks: readonly ObJourneyTemplateStep[]): string {
  if (tasks.length === 0) return 'No tasks yet'
  return `${tasks.length} task${tasks.length === 1 ? '' : 's'}`
}

/**
 * What a search box narrows the tree to.
 *
 * <p>Matches run against task names and descriptions and against checklist
 * labels, and then every **ancestor** of a hit is pulled in with it — a task
 * three deep is meaningless shown on its own, and its stage would otherwise
 * read as empty. Ancestors are carried for context only: their own checklists
 * stay closed unless they matched too.
 */
export interface TaskFilter {
  query: string
  /** Every task to draw — hits and the ancestors that place them. */
  visibleTaskIds: ReadonlySet<number>
  /** Hits on the task's own name or description: all of its checklist shows. */
  matchedTaskIds: ReadonlySet<number>
  /** Task list items that matched, when their task did not. */
  matchedItemIds: ReadonlySet<number>
  /** Required documents that matched, when their task did not. */
  matchedDocIds: ReadonlySet<number>
}

/** `null` for a blank box — the whole tree, not a filter matching everything. */
export function buildTaskFilter(
  steps: readonly ObJourneyTemplateStep[],
  query: string,
): TaskFilter | null {
  const needle = query.trim().toLowerCase()
  if (!needle) return null

  const matchedTaskIds = new Set<number>()
  const matchedItemIds = new Set<number>()
  const matchedDocIds = new Set<number>()
  const hits = new Set<number>()

  for (const step of steps) {
    if (contains(step.name, needle) || contains(step.description, needle)) {
      matchedTaskIds.add(step.id)
      hits.add(step.id)
    }
    for (const item of step.items) {
      if (contains(item.label, needle)) {
        matchedItemIds.add(item.id)
        hits.add(step.id)
      }
    }
    for (const doc of step.docs) {
      if (contains(doc.label, needle)) {
        matchedDocIds.add(doc.id)
        hits.add(step.id)
      }
    }
  }

  const byId = new Map(steps.map((step) => [step.id, step]))
  const visibleTaskIds = new Set<number>(hits)
  for (const id of hits) {
    /*
      Up the dependency chain. `seen` is the cycle guard the tree builder
      keeps for the same reason: the backend rejects a loop, so this should
      never fire, which is exactly why it is cheap to keep.
    */
    const seen = new Set<number>([id])
    let parentId = byId.get(id)?.dependsOnStepId ?? null
    while (parentId != null && !seen.has(parentId)) {
      seen.add(parentId)
      visibleTaskIds.add(parentId)
      parentId = byId.get(parentId)?.dependsOnStepId ?? null
    }
  }

  return { query: needle, visibleTaskIds, matchedTaskIds, matchedItemIds, matchedDocIds }
}

function contains(haystack: string | null | undefined, needle: string): boolean {
  return haystack != null && haystack.toLowerCase().includes(needle)
}

/**
 * The day range a stage covers — the earliest start and the latest end of
 * anything inside it, read off the **template-wide** tree, because Day 1 is
 * the day the journey starts and a range computed inside one stage would
 * restart at 1 in every group.
 *
 * <p>`null` for a stage holding nothing, which is the usual state of a
 * service somebody has just created.
 */
export function stageSpan(
  tasks: readonly ObJourneyTemplateStep[],
  scheduleOf: ReadonlyMap<number, { startDay: number; endDay: number }>,
): { startDay: number; endDay: number } | null {
  let startDay = Number.POSITIVE_INFINITY
  let endDay = 0
  for (const task of tasks) {
    const node = scheduleOf.get(task.id)
    if (!node) continue
    if (node.startDay < startDay) startDay = node.startDay
    if (node.endDay > endDay) endDay = node.endDay
  }
  if (endDay === 0) return null
  return { startDay, endDay }
}
