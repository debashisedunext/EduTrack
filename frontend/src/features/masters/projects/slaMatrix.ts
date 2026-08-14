import type { Level } from '@/api/generated/model/level'
import type { SlaPolicyCell } from '@/api/generated/model/slaPolicyCell'
import type { SlaPolicyWrite } from '@/api/generated/model/slaPolicyWrite'
import type { SlaSource } from '@/api/generated/model/slaSource'

/**
 * B-018 · the SLA tab's decisions, with no React in them.
 *
 * The screen is a grid of forty-four editable cells behind one wholesale `PUT`,
 * and everything that can go wrong is arithmetic and bookkeeping: which cells
 * are overrides, what a cleared cell means, what gets sent. That is all here so
 * it can be tested without rendering anything, the same split
 * `projectTeam.ts` uses.
 */

/** A cell's identity — `taskTypeId` alone is a row of four. */
export const cellKey = (taskTypeId: number, level: Level) => `${taskTypeId}/${level}`

/**
 * What the user has typed, before it is worth sending.
 *
 * Strings, not numbers, because an in-progress `"1."` is a state the input is
 * legitimately in and `Number("1.")` is `1` — parsing on every keystroke would
 * fight the user's cursor. They are parsed once, on save.
 *
 * `null` for `escalateToL1` / `escalateToL2` never occurs; they are checkboxes
 * and always have a value. They are on the draft rather than read from the cell
 * so that toggling one on an inherited cell is what *creates* the override,
 * which is what an administrator ticking a box on that row means.
 */
export interface CellDraft {
  responseHrs: string
  resolutionHrs: string
  escalateToL1: boolean
  escalateToL2: boolean
}

/** How a cell got its figures, in words an administrator can act on. */
const SOURCE_LABELS: Record<SlaSource, string> = {
  PROJECT_TASK_TYPE: 'Set here',
  PROJECT_LEVEL: 'Project default',
  ORG_DEFAULT: 'Organisation default',
  PRIORITY_DEFAULT: 'Priority master',
  TASK_TYPE_DEFAULT: 'Task type master',
  NONE: 'Not set',
}

export const sourceLabel = (source: SlaSource) => SOURCE_LABELS[source]

/**
 * Only an override is chipped, and the rest are plain text.
 *
 * If every source were a coloured chip, the one distinction the screen exists
 * to make — this cell is ours, that one is inherited — would be four colours
 * competing rather than one signal. `NONE` is the exception because it is not
 * an inheritance at all: nothing in the product has a figure, so any ticket in
 * that cell gets no planned close date and drops out of the breach sweep. That
 * is a warning, not a provenance note.
 */
export function sourceVariant(source: SlaSource): 'info' | 'warning' | 'neutral' {
  if (source === 'PROJECT_TASK_TYPE') return 'info'
  if (source === 'NONE') return 'warning'
  return 'neutral'
}

/** The grid, grouped into the sections the screen renders — one per task type. */
export interface TaskTypeGroup {
  taskTypeId: number
  taskTypeName: string
  cells: SlaPolicyCell[]
}

/**
 * Group by task type, preserving the server's order in both directions.
 *
 * The server orders by the masters' own `seq`, which is the order an
 * administrator configured them in. Re-sorting here by name or id would discard
 * that for an ordering that means nothing to anybody.
 */
export function groupByTaskType(cells: SlaPolicyCell[]): TaskTypeGroup[] {
  const groups: TaskTypeGroup[] = []
  const byId = new Map<number, TaskTypeGroup>()

  for (const cell of cells) {
    let group = byId.get(cell.taskTypeId)
    if (!group) {
      group = { taskTypeId: cell.taskTypeId, taskTypeName: cell.taskTypeName, cells: [] }
      byId.set(cell.taskTypeId, group)
      groups.push(group)
    }
    group.cells.push(cell)
  }
  return groups
}

/** The number an input should start at. Empty for "nothing to show". */
const asInput = (value: number | null | undefined) => (value == null ? '' : String(value))

/**
 * The draft a cell starts in.
 *
 * **Inherited figures are shown, not blanked.** An empty row for a cell that
 * resolves to sixteen hours would be a screen telling an administrator nothing
 * is configured when a ticket raised there is measured against a real target —
 * which is the mistake that makes somebody "configure" the whole grid and
 * silently detach the project from every default it was following.
 */
export function draftFor(cell: SlaPolicyCell): CellDraft {
  return {
    responseHrs: asInput(cell.responseHrs),
    resolutionHrs: asInput(cell.resolutionHrs),
    escalateToL1: cell.escalateToL1 ?? true,
    escalateToL2: cell.escalateToL2 ?? false,
  }
}

export const draftsFor = (cells: SlaPolicyCell[]): Record<string, CellDraft> =>
  Object.fromEntries(cells.map((c) => [cellKey(c.taskTypeId, c.level), draftFor(c)]))

/**
 * Whether the user has moved a cell away from what the server sent.
 *
 * Compared as *parsed numbers* on the hours, so `"8"` and `"8.0"` are not a
 * change — otherwise a cell an administrator clicked into and out of would be
 * saved as a fresh override, which for an inherited cell means silently
 * detaching it from its default.
 */
export function isDirty(cell: SlaPolicyCell, draft: CellDraft): boolean {
  const original = draftFor(cell)
  return (
    !sameNumber(original.responseHrs, draft.responseHrs)
    || !sameNumber(original.resolutionHrs, draft.resolutionHrs)
    || original.escalateToL1 !== draft.escalateToL1
    || original.escalateToL2 !== draft.escalateToL2
  )
}

function sameNumber(a: string, b: string): boolean {
  const left = a.trim()
  const right = b.trim()
  if (left === '' || right === '') return left === right
  return Number(left) === Number(right)
}

/**
 * Which cells the `PUT` should carry.
 *
 * **This is the function the whole feature turns on.** The body is this
 * project's *overrides*, never the resolved grid: a cell sent as an override
 * becomes a `sla_policies` row for this project, and a cell left out goes back
 * to inheriting. Sending the grid back — the obvious implementation, since the
 * grid is what the screen holds — would materialise every inherited figure as
 * a project row, and the project would silently stop following the org-wide
 * default it was displayed as following. Nothing would look wrong until
 * somebody changed that default six months later and this project did not move.
 *
 * So a cell is sent when it is **already an override** or when the user has
 * **changed it**, and never merely because it has a figure in it.
 *
 * A cell whose resolution box has been emptied is dropped, which is how an
 * override is removed: there is no separate delete, because "no target" and
 * "inherit" are the same request as far as the row is concerned, and offering
 * two controls for one outcome is how they end up disagreeing.
 */
export function buildOverrides(
  cells: SlaPolicyCell[],
  drafts: Record<string, CellDraft>,
): SlaPolicyWrite[] {
  const overrides: SlaPolicyWrite[] = []

  for (const cell of cells) {
    const draft = drafts[cellKey(cell.taskTypeId, cell.level)]
    if (!draft) continue
    if (!cell.isOverride && !isDirty(cell, draft)) continue

    const resolutionHrs = parseHours(draft.resolutionHrs)
    if (resolutionHrs == null) continue

    overrides.push({
      taskTypeId: cell.taskTypeId,
      level: cell.level,
      responseHrs: parseHours(draft.responseHrs),
      resolutionHrs,
      escalateToL1: draft.escalateToL1,
      escalateToL2: draft.escalateToL2,
    })
  }
  return overrides
}

/**
 * A figure the server will accept, or null.
 *
 * Null covers both "the box is empty" and "the box holds something that is not
 * a usable number", and the caller treats them the same for `responseHrs` —
 * where null is a legitimate value — while {@link validate} is what stops the
 * second case reaching the wire on `resolutionHrs`.
 */
export function parseHours(raw: string): number | null {
  const trimmed = raw.trim()
  if (trimmed === '') return null

  const value = Number(trimmed)
  if (!Number.isFinite(value) || value <= 0 || value > 9999.99) return null
  return value
}

/** One thing wrong with one cell, in the words the row will show. */
export interface CellProblem {
  key: string
  message: string
}

/**
 * The client-side half of the server's rules.
 *
 * Deliberately the same four checks the service makes, and deliberately not
 * trusted instead of it: this exists so a typo is answered on the row that
 * caused it rather than as a banner naming one cell out of forty-four after a
 * round trip. The server stays the authority — a `400` still lands in the
 * banner.
 *
 * The duplicate-cell rule the service has is absent here and cannot fire: the
 * grid is keyed by `taskTypeId/level`, so the screen has no way to produce two
 * drafts for one cell.
 */
export function validate(
  cells: SlaPolicyCell[],
  drafts: Record<string, CellDraft>,
): CellProblem[] {
  const problems: CellProblem[] = []

  for (const cell of cells) {
    const key = cellKey(cell.taskTypeId, cell.level)
    const draft = drafts[key]
    if (!draft) continue
    if (!cell.isOverride && !isDirty(cell, draft)) continue

    const resolution = draft.resolutionHrs.trim()
    const response = draft.responseHrs.trim()

    // An emptied resolution box is how an override is removed, so it is not a
    // problem — it is the request to stop overriding this cell.
    if (resolution !== '' && parseHours(resolution) == null) {
      problems.push({ key, message: 'Resolution hours must be a number between 0 and 9999.99.' })
      continue
    }
    if (response !== '' && parseHours(response) == null) {
      problems.push({ key, message: 'Response hours must be a number between 0 and 9999.99.' })
      continue
    }

    const resolutionHrs = parseHours(resolution)
    const responseHrs = parseHours(response)

    // A response target later than the resolution target is not a policy, it is
    // a transposition — and nothing downstream would reject it.
    if (resolutionHrs != null && responseHrs != null && responseHrs > resolutionHrs) {
      problems.push({ key, message: 'The response target cannot be longer than the resolution target.' })
    }
  }
  return problems
}

/**
 * How many cells this project has its own row for, after the pending edits.
 *
 * Shown because "3 of 44 overridden" is the one sentence that tells an
 * administrator whether this project is mostly following the defaults or has
 * quietly drifted onto its own.
 */
export function summarise(cells: SlaPolicyCell[], drafts: Record<string, CellDraft>) {
  const overrides = buildOverrides(cells, drafts)
  return {
    total: cells.length,
    overrideCount: overrides.length,
    dirtyCount: cells.filter((c) => {
      const draft = drafts[cellKey(c.taskTypeId, c.level)]
      return draft ? isDirty(c, draft) : false
    }).length,
    /** Cells nothing in the product has a figure for — a ticket here gets no planned close date. */
    unsetCount: cells.filter((c) => c.source === 'NONE').length,
  }
}
