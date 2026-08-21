import type { Stage } from '@/api/generated/model/stage'
import type { WorkflowStage } from '@/api/generated/model/workflowStage'
import type { WorkflowTemplate } from '@/api/generated/model/workflowTemplate'

/**
 * B-043 · S-30's canvas, as data.
 *
 * <p>Blueprint §7.4 S-30: <em>"the visual builder inside S-13: drag stages onto a
 * canvas, set owner role and SLA per stage, draw the allowed return paths,
 * preview the rendered ribbon, then map it to project × task type."</em>
 *
 * <h2>Everything here is arithmetic, and that is the point</h2>
 *
 * A canvas is the kind of screen where the interesting failures hide inside
 * pointer handlers — an arc drawn the wrong way, a node dropped one index off,
 * two arrows rendered on top of each other so a loop-back looks like it does not
 * exist. None of those can be asserted by rendering a `<div>` and none of them
 * are about React.
 *
 * So the geometry, the palette and the refusals live here as pure functions over
 * plain arrays, `canvasModel.test.ts` drives them directly, and
 * `WorkflowDesignerPage` is left holding gestures and requests.
 *
 * <h2>What is deliberately not here</h2>
 *
 * The rules B-040 already wrote. {@link moveStage}, {@link forwardReturnPaths},
 * {@link orderChanged}, {@link returnTargetOptions}, {@link stageFormErrors} and
 * both mappers are imported from `stages/stageForm.ts` unchanged — a canvas that
 * re-derived the backward-target rule would be a second copy of the one rule on
 * this screen whose failure is silent, since a forward "return" is refused by the
 * server with a 409 and looks, on the canvas, exactly like an arrow.
 */

/**
 * One entry in the palette an Admin drags from.
 *
 * `stageId` is absent because a palette entry is **not a row**. There is no stage
 * catalogue table — B-040 recorded this at length and B-041's README repeats it —
 * `workflow_stages.template_id` is `NOT NULL`, so `DEV` on Standard Dev Flow and
 * `DEV` on Support Fast-Track are two independent rows sharing a code.
 */
export interface PaletteEntry {
  stageCode: string
  displayName: string
  ownerRole?: string
  /** Which templates already use this code, for the palette's subtitle. */
  usedBy: string[]
}

/**
 * The palette: every stage code the organisation already uses, minus the ones
 * already on this canvas.
 *
 * <h2>"Drag stages onto a canvas" needs something to drag, and there is no
 * catalogue</h2>
 *
 * The blueprint's phrase implies a library of stage definitions. B-041 resolved
 * the same phrase on tab 3 by reading "picking" as **cloning a whole template**,
 * which is right for standing up a new flow and useless for adding one stage to
 * an existing one — the operation this screen exists for.
 *
 * What does exist is a **vocabulary**. `WorkflowTemplate.stages` is the inline
 * `WorkflowStage[]` B-040 fought to keep on the list response, and it carries
 * exactly code, name, sequence and owner role across every template. The union of
 * it is the set of stage names this organisation has agreed on, which is the
 * closest thing to a catalogue the schema contains and is a genuinely useful
 * thing to drag: a fourth template's QA stage should be called `QA` and owned by
 * the QA role, because the other three call it that.
 *
 * Dropping one **creates a new row on this template**, pre-filled. It does not
 * link to the source, because there is nothing to link to — which is why the
 * entry carries `usedBy` and the palette says so on the card. An Admin who thinks
 * they are reusing a shared definition would be wrong, and the screen should not
 * let them think it.
 *
 * Codes already on this canvas are excluded: `uq_workflow_stages_code` is per
 * template, so dropping a second `DEV` is a 409 the palette can see coming.
 */
export function stageVocabulary(
  templates: WorkflowTemplate[],
  onCanvas: Stage[],
): PaletteEntry[] {
  const taken = new Set(onCanvas.map((s) => s.stageCode))
  const byCode = new Map<string, PaletteEntry>()

  templates.forEach((template) => {
    ;(template.stages ?? []).forEach((stage: WorkflowStage) => {
      const code = stage.stageCode
      if (!code || taken.has(code)) return

      const existing = byCode.get(code)
      if (existing) {
        if (!existing.usedBy.includes(template.name ?? '')) {
          existing.usedBy.push(template.name ?? '')
        }
        return
      }
      byCode.set(code, {
        stageCode: code,
        displayName: stage.displayName ?? code,
        ownerRole: stage.ownerRole,
        usedBy: [template.name ?? ''],
      })
    })
  })

  // Sorted by how widely used the code is, then alphabetically. A code six
  // templates agree on is the one most likely to be wanted, and an alphabetical
  // list would bury it under a one-off somebody added last month.
  return [...byCode.values()].sort(
    (a, b) => b.usedBy.length - a.usedBy.length || a.stageCode.localeCompare(b.stageCode),
  )
}

/**
 * Where a drop lands, given which node it was dropped on and which side.
 *
 * Separated from the pointer handler because off-by-one is the entire failure
 * mode of a drop target and it is invisible in a screenshot: dropping on the
 * right half of node 2 must yield index 3, and dropping on the right half of the
 * **last** node must yield the length rather than the last index.
 */
export function dropIndex(overIndex: number, side: 'before' | 'after'): number {
  return side === 'before' ? overIndex : overIndex + 1
}

/**
 * Insert into a list at an index, clamped.
 *
 * Clamped rather than throwing for {@link moveStage}'s reason: the ends of the
 * canvas are the ordinary case, not an error, and a drop past the last node is
 * what appending looks like to a pointer.
 */
export function insertAt<T>(items: T[], item: T, index: number): T[] {
  const at = Math.max(0, Math.min(index, items.length))
  const next = [...items]
  next.splice(at, 0, item)
  return next
}

/**
 * Why this return path cannot be drawn, or null if it can.
 *
 * <h2>Drawn arrows need the refusal at the pointer, not at the server</h2>
 *
 * §4A.1's loop-back table is entirely backward moves: a return target is a stage
 * the transition service will let a ticket go *back* to, and a forward "return"
 * is an ordinary handoff with a reason attached. `returnTargetOptions` enforces
 * that on tab 2 by only offering the stages above the one being edited — a
 * checkbox list can express the rule by omission.
 *
 * A canvas cannot. The gesture is "drag from this node to that one", every node
 * is on screen, and an arrow the Admin has already drawn cannot be un-drawn by
 * not offering it. So the rule has to arrive as a sentence at the moment of the
 * drop, naming the pair, which is also the shape B-040 chose for
 * `forwardReturnPaths` and B-042 for `retireBlockers`: the browser explains
 * before the request and the server refuses again after it.
 *
 * <p>Three refusals, and the third is the one worth having:
 *
 * <ul>
 *   <li><b>forward</b> — the target is at or below the source. The rule itself.
 *   <li><b>self</b> — a stage returning to itself. Not a loop-back; a stage a
 *       ticket can never leave.
 *   <li><b>deprecated</b> — B-042. A return target is a move the transition
 *       service honours, and an arrow into a retired stage is an entry into a
 *       stage nothing may enter. The server refuses it and the canvas would
 *       otherwise draw it first.
 * </ul>
 */
export function returnPathRefusal(
  ordered: Stage[],
  fromCode: string,
  toCode: string,
): { reason: 'self' | 'forward' | 'deprecated' | 'exists'; message: string } | null {
  if (fromCode === toCode) {
    return {
      reason: 'self',
      message: `${fromCode} cannot return to itself — that is a stage a ticket never leaves.`,
    }
  }

  const fromIndex = ordered.findIndex((s) => s.stageCode === fromCode)
  const toIndex = ordered.findIndex((s) => s.stageCode === toCode)
  if (fromIndex < 0 || toIndex < 0) return null

  if (toIndex > fromIndex) {
    return {
      reason: 'forward',
      message:
        `${fromCode} → ${toCode} points forwards. A return path is a backward move; `
        + 'sending a ticket onward is an ordinary handoff.',
    }
  }

  const target = ordered[toIndex]
  if (target.isDeprecated) {
    return {
      reason: 'deprecated',
      message:
        `${toCode} is deprecated, so nothing new may enter it. `
        + 'Restore it first, or pick another target.',
    }
  }

  if (ordered[fromIndex].canReturnTo.includes(toCode)) {
    return { reason: 'exists', message: `${fromCode} → ${toCode} is already drawn.` }
  }

  return null
}

/** One drawn return path, positioned for rendering. */
export interface ReturnArc {
  fromCode: string
  toCode: string
  /** Index of the source node in the current order. */
  fromIndex: number
  /** Index of the target node — always lower than `fromIndex`. */
  toIndex: number
  /**
   * Which row beneath the nodes this arc is drawn on. Zero is nearest the nodes.
   */
  lane: number
  /** True when the order has been dragged such that this arc now points forward. */
  isBroken: boolean
}

/**
 * Every return path on the canvas, assigned to a lane so that none is hidden
 * under another.
 *
 * <h2>Why lanes, rather than drawing every arc at the same depth</h2>
 *
 * `DEV → TRIAGE` and `QA → TRIAGE` share an endpoint, and `SIGNOFF → DEV` spans
 * both. Drawn at one depth they overlap into a single smear, and the screen whose
 * entire justification is *"see the flow"* would be hiding two of the three
 * things §4A.1 says about this template.
 *
 * The assignment is the interval-graph greedy, and it runs **narrowest first**.
 * Each arc takes the lowest lane no arc it overlaps already occupies, so a short
 * hop settles nearest the nodes and anything containing it is pushed outward.
 *
 * That order is the whole of the correctness. Widest-first is the natural way to
 * write it and it draws `SIGNOFF → DEV` on the inner lane with `QA → DEV` dipping
 * *underneath* it — a nested loop-back crossing its own container twice, which
 * reads as two arrows tangled rather than one inside the other. Nesting on the
 * page has to match nesting in the flow.
 *
 * It is also deterministic, which matters more than optimal: an arc that changed
 * depth when an unrelated one was added would make the canvas appear to move on
 * its own.
 *
 * <h2>Broken arcs are laid out, not filtered</h2>
 *
 * A staged reorder can leave an existing arc pointing forwards, which is exactly
 * what `forwardReturnPaths` refuses to save. Dropping it from the layout would
 * make the offending arrow **vanish at the moment it became a problem**, leaving
 * an Admin reading a Save button that names a pair they can no longer see. So it
 * is returned with `isBroken` and the canvas draws it in the danger token.
 */
export function returnArcs(ordered: Stage[]): ReturnArc[] {
  const indexOf = new Map(ordered.map((stage, index) => [stage.stageCode, index]))

  const arcs: Omit<ReturnArc, 'lane'>[] = []
  ordered.forEach((stage, fromIndex) => {
    stage.canReturnTo.forEach((toCode) => {
      const toIndex = indexOf.get(toCode)
      if (toIndex == null) return
      arcs.push({
        fromCode: stage.stageCode,
        toCode,
        fromIndex,
        toIndex,
        isBroken: toIndex >= fromIndex,
      })
    })
  })

  const span = (arc: Omit<ReturnArc, 'lane'>) => Math.abs(arc.fromIndex - arc.toIndex)
  const ordering = [...arcs].sort(
    (a, b) => span(a) - span(b) || a.fromIndex - b.fromIndex || a.toCode.localeCompare(b.toCode),
  )

  const lanes: { lo: number; hi: number }[][] = []
  const assigned = new Map<Omit<ReturnArc, 'lane'>, number>()

  ordering.forEach((arc) => {
    const lo = Math.min(arc.fromIndex, arc.toIndex)
    const hi = Math.max(arc.fromIndex, arc.toIndex)

    let lane = 0
    for (;; lane += 1) {
      const occupants = lanes[lane]
      if (!occupants) {
        lanes[lane] = []
        break
      }
      // Touching at a single endpoint is not an overlap — two arcs meeting at
      // TRIAGE share that node and diverge, which reads correctly on one lane.
      if (!occupants.some((o) => lo < o.hi && o.lo < hi)) break
    }

    lanes[lane].push({ lo, hi })
    assigned.set(arc, lane)
  })

  // Returned in canvas order rather than lane order: the arcs are keyed by
  // endpoint in the DOM, and a list that reshuffled on every edit would remount
  // every path element and lose any CSS transition on it.
  return arcs.map((arc) => ({ ...arc, lane: assigned.get(arc) ?? 0 }))
}

/**
 * The SVG path for one arc, in the node-grid coordinates the canvas lays out.
 *
 * A cubic with both control points pushed straight down, so the curve leaves and
 * enters its nodes vertically and the arrowhead reads as pointing *back into* the
 * stage rather than glancing off it.
 */
export function arcPath(
  arc: ReturnArc,
  geometry: { nodeWidth: number; gap: number; baseline: number; laneHeight: number },
): string {
  const centre = (index: number) =>
    index * (geometry.nodeWidth + geometry.gap) + geometry.nodeWidth / 2

  const x1 = centre(arc.fromIndex)
  const x2 = centre(arc.toIndex)
  const depth = geometry.baseline + (arc.lane + 1) * geometry.laneHeight

  return `M ${x1} ${geometry.baseline} C ${x1} ${depth}, ${x2} ${depth}, ${x2} ${geometry.baseline}`
}

/**
 * The width the canvas needs to draw `count` nodes, so the scroll container sizes
 * itself rather than clipping the last one.
 *
 * §17's readability risk is about eight stages on a laptop — the same one B-053
 * carries for the ticket page's ribbon. Here the answer is an honest horizontal
 * scroll over a correctly sized surface, because a designer that shrank its nodes
 * to fit would make the SLA and owner role on each one unreadable at exactly the
 * size where an Admin most needs to check them.
 */
export function canvasWidth(
  count: number,
  geometry: { nodeWidth: number; gap: number },
): number {
  if (count === 0) return 0
  return count * geometry.nodeWidth + (count - 1) * geometry.gap
}

/**
 * How deep the arc area is, so the nodes and the arcs do not overlap.
 *
 * Zero lanes means zero extra height — a template with no loop-backs should not
 * reserve a band of empty canvas below it and look like it is missing something.
 */
export function arcAreaHeight(
  arcs: ReturnArc[],
  geometry: { laneHeight: number },
): number {
  if (arcs.length === 0) return 0
  const deepest = Math.max(...arcs.map((a) => a.lane))
  return (deepest + 1) * geometry.laneHeight
}

/**
 * The staged order, renumbered, so the preview can be built from it.
 *
 * <h2>Why the preview needs this and the canvas does not</h2>
 *
 * `buildPreviewRibbon` and `previewChain` both **sort by `seq`** — correctly, for
 * B-041's caller, which hands them exactly what the server returned. A staged
 * reorder is an array in a new order whose rows still carry the *old* `seq`, so
 * handing that straight to either one sorts the drag back out again and the
 * preview renders the saved flow while the canvas shows the dragged one.
 *
 * That failure is silent and it lands on the one requirement §7.4 words as a
 * requirement: *"a live ribbon preview renders as the Admin edits"*. It would
 * have looked right on every screenshot taken before the first drag.
 *
 * So the preview is fed a renumbered copy. B-004's 10, 20, 30 … spacing is kept
 * rather than using the bare index, because that is what the reorder route will
 * actually write and a preview should not be built on a numbering the server
 * would not produce.
 *
 * <p>Nothing is mutated: these rows are the query cache's.
 */
export function resequence(stages: Stage[]): Stage[] {
  return stages.map((stage, index) => ({ ...stage, seq: (index + 1) * 10, position: index + 1 }))
}

/**
 * What the canvas announces after a node moves, for the `aria-live` region.
 *
 * Split out because it is the assertion the keyboard tests make, and burying it
 * in JSX would mean testing the accessible path through a string literal in a
 * component — which is how that path drifts.
 */
export function moveAnnouncement(stage: Stage, to: number, total: number): string {
  return `${stage.displayName} moved to position ${to + 1} of ${total}.`
}
