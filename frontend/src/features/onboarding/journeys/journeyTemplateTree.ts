import type { ObJourneyTemplateStep } from '@/api/generated/model/obJourneyTemplateStep'

/**
 * The designer's step table reads as a **tree**, and this is what shapes it:
 * `dependsOnStepId` nests a step under the one it waits for, and the day range
 * beside it falls straight out of that nesting.
 *
 * <h2>Why a tree rather than the flat list the column used to describe</h2>
 *
 * <p>The dependency was a cell — `↳ 3. Cleansing & field mapping` — which says
 * what one step waits for but never what the journey *shape* is. Reading a
 * chain of five meant holding five row numbers in your head and walking them
 * backwards. Nested, the chain is the indentation: a root runs from day one, a
 * child starts when its parent ends, and a second root beside the first is
 * visibly parallel rather than parallel-if-you-check-the-column.
 *
 * <h2>Day numbers are relative, and they are working days</h2>
 *
 * <p>Day 1 is the day the journey starts, not a date — a template has no
 * client and therefore no calendar. `tatDays` is working days (the v1.2 unit
 * change), so the range is working days too, and turning it into real dates is
 * the client page's job, through the working calendar. Nothing here touches
 * weekends or holidays, and nothing here should: that arithmetic has exactly
 * one home.
 *
 * <h2>What the numbers assume</h2>
 *
 * <p>A step begins the day after the step it depends on ends. That is the
 * earliest it *could* begin, which is the only thing a template can claim —
 * an instantiated journey slips against it the moment a step runs late. So
 * the schedule is the plan, not a forecast, and two steps under one parent
 * both start on the same day because both genuinely could.
 *
 * <p>`dependsOnStepId` names **one** predecessor, so the shape really is a
 * tree and not a general DAG. If that ever becomes a set, `startDay` is the
 * line to change: it would take the max over predecessors' `endDay` instead
 * of the one parent's.
 */

/** The little a step needs to be placed in the tree — the rest is the caller's. */
type Placeable = Pick<ObJourneyTemplateStep, 'id' | 'tatDays' | 'dependsOnStepId'>

export interface StepNode<S> {
  step: S
  /** 1-based, assigned depth-first — what the row's badge shows. */
  number: number
  /** 0 for a step that runs from journey start. */
  depth: number
  /** First working day, 1-based and relative to journey start. */
  startDay: number
  /** Last working day, inclusive. */
  endDay: number
  children: StepNode<S>[]
}

export interface StepTree<S> {
  /** Steps with no predecessor — each starts on day 1, in parallel. */
  roots: StepNode<S>[]
  /** Depth-first, siblings in list order. The order the table draws rows in. */
  flat: StepNode<S>[]
  /** The last day any step ends on — 0 for a template with no steps. */
  spanDays: number
}

export function buildStepTree<S extends Placeable>(steps: readonly S[]): StepTree<S> {
  const present = new Set(steps.map((s) => s.id))
  const childrenOf = new Map<number, S[]>()
  const rootSteps: S[] = []

  for (const step of steps) {
    const parentId = step.dependsOnStepId ?? null
    /*
      A `dependsOnStepId` naming something not in this list is a root here
      rather than a dropped row. The database constrains the column to a step
      in the same template, so in practice this is the staged-reorder view
      mid-edit — but a step vanishing from the screen is the one outcome that
      must not be possible, whatever the data does.
    */
    if (parentId == null || !present.has(parentId)) {
      rootSteps.push(step)
      continue
    }
    const siblings = childrenOf.get(parentId)
    if (siblings) siblings.push(step)
    else childrenOf.set(parentId, [step])
  }

  const placed = new Set<number>()
  const flat: StepNode<S>[] = []
  let spanDays = 0
  let counter = 0

  const place = (step: S, depth: number, startDay: number): StepNode<S> => {
    placed.add(step.id)
    counter += 1
    // The contract floors `tatDays` at 1; the clamp is so a bad payload
    // produces a short row rather than a range that runs backwards.
    const endDay = startDay + Math.max(1, step.tatDays) - 1
    if (endDay > spanDays) spanDays = endDay

    const node: StepNode<S> = { step, number: counter, depth, startDay, endDay, children: [] }
    flat.push(node)

    for (const child of childrenOf.get(step.id) ?? []) {
      // A cycle would otherwise recurse forever. `parallelGroups` means the
      // backend rejects one, so this guard should never fire — which is
      // exactly why it is cheap to keep.
      if (placed.has(child.id)) continue
      node.children.push(place(child, depth + 1, endDay + 1))
    }
    return node
  }

  const roots = rootSteps.map((step) => place(step, 0, 1))

  /*
    Anything a cycle kept out of the walk, appended as a root. It draws in the
    wrong place, which is honest — the alternative is a step an admin can see
    in the database and not on the screen that edits it.
  */
  for (const step of steps) {
    if (!placed.has(step.id)) roots.push(place(step, 0, 1))
  }

  return { roots, flat, spanDays }
}

/** `Day 1–2`, or `Day 7` when a step is a single day. */
export function scheduleLabel(node: Pick<StepNode<unknown>, 'startDay' | 'endDay'>): string {
  return node.startDay === node.endDay
    ? `Day ${node.startDay}`
    : `Day ${node.startDay}\u2013${node.endDay}`
}

/**
 * The step's slice of the template's whole span, as percentages for the little
 * bar beside the label. `spanDays` of 0 is the empty template — the caller
 * draws no bar at all, but a division by zero here would reach it as `NaN%`.
 */
export function scheduleBar(
  node: Pick<StepNode<unknown>, 'startDay' | 'endDay'>,
  spanDays: number,
): { left: string; width: string } {
  if (spanDays <= 0) return { left: '0%', width: '0%' }
  const days = node.endDay - node.startDay + 1
  return {
    left: `${((node.startDay - 1) / spanDays) * 100}%`,
    width: `${(days / spanDays) * 100}%`,
  }
}
