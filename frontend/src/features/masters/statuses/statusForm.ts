import type { Status } from '@/api/generated/model/status'
import type { StatusCategory } from '@/api/generated/model/statusCategory'
import type { StatusCode } from '@/api/generated/model/statusCode'
import type { StatusPatchRequest } from '@/api/generated/model/statusPatchRequest'
import type { StatusTransition } from '@/api/generated/model/statusTransition'
import type { RoleCode } from '@/api/generated/model/roleCode'
import type { StatusTransitionWrite } from '@/api/generated/model/statusTransitionWrite'
import type { StatusWriteRequest } from '@/api/generated/model/statusWriteRequest'

/**
 * S-13 tab 1 — form state, validation, the matrix's cell model, and the mapping
 * onto the wire. B-039.
 *
 * Kept apart from the page for the reason `projectForm.ts`, `taskTypeForm.ts`
 * and `priorityForm.ts` give: the rules are worth testing without rendering
 * anything, and the mappers are where a quiet mistake shows up as a save that
 * silently did nothing.
 */

/**
 * Blueprint §12.1's tokens, in the order B-003 assigns them to the eight
 * statuses, then the rest of the chart palette.
 *
 * CLAUDE.md: "Design tokens come from blueprint §12.1. Never introduce a colour
 * that isn't a token." §12.1 names two of these literally — `--success` for
 * "Closed, on-time" and `--info` for "In progress" — and the rest are chosen
 * from the same palette for distinctness across eight chips, every one of which
 * also carries a text label, per §12.1's rule that colour is never the only
 * signal.
 *
 * The server only checks the *shape* (`#RRGGBB`), because it has no palette; the
 * palette lives here, where the choice is made.
 */
export const STATUS_PALETTE: readonly string[] = [
  '#4F46E5', // New — --primary
  '#3B82F6', // In Progress — --info
  '#F59E0B', // On Hold — --warning
  '#6B7280', // Awaiting Info — --text-secondary
  '#8B5CF6', // Rework
  '#14B8A6', // Resolved
  '#10B981', // Closed — --success
  '#EF4444', // Reopened — --danger
]

/**
 * The eight the contract's `StatusCode` enum can carry — see `StatusService`.
 *
 * S-13 does not promise an Admin can add a ninth (unlike S-12, which does), so
 * this list is a constraint rather than an unkept promise. It still shapes the
 * create dialog the same way: the codes not already taken, which in a seeded
 * system is none — so the create button exists for the case where one has been
 * retired and somebody wants a different code, and the empty state says so
 * rather than presenting a free-text box that always fails.
 */
export const CONTRACT_STATUS_CODES: readonly StatusCode[] = [
  'NEW', 'IN_PROGRESS', 'ON_HOLD', 'AWAITING_INFO',
  'REWORK', 'RESOLVED', 'CLOSED', 'REOPENED',
]

export const STATUS_CATEGORIES: readonly StatusCategory[] = ['TODO', 'IN_PROGRESS', 'DONE']

/** §7.4's own words, so the screen's labels are not a translation of the enum. */
export const CATEGORY_LABELS: Record<StatusCategory, string> = {
  TODO: 'To-do',
  IN_PROGRESS: 'In progress',
  DONE: 'Done',
}

/**
 * What the create and edit dialogs hold.
 *
 * `seq` is a string because that is what an `<input>` gives back, and an empty
 * numeric input yielding `NaN` is the classic way a required-field error turns
 * into "expected number" — `priorityForm.ts` and `ticketForm.ts` make the same
 * call. Empty means "add at the end".
 */
export interface StatusFormValues {
  code: StatusCode
  name: string
  category: StatusCategory
  colour: string
  seq: string
  isOpen: boolean
  isTerminal: boolean
  isActive: boolean
}

export const emptyStatusForm: StatusFormValues = {
  code: 'NEW',
  name: '',
  category: 'TODO',
  colour: STATUS_PALETTE[0],
  seq: '',
  isOpen: true,
  isTerminal: false,
  isActive: true,
}

/** The stored row as the edit dialog first renders it. */
export function toFormValues(status: Status): StatusFormValues {
  return {
    code: status.code ?? 'NEW',
    name: status.name ?? '',
    category: status.category ?? 'TODO',
    colour: status.colour ?? STATUS_PALETTE[0],
    seq: status.seq == null ? '' : String(status.seq),
    isOpen: status.isOpen ?? true,
    isTerminal: status.isTerminal ?? false,
    isActive: status.isActive ?? true,
  }
}

/**
 * The same rules the server enforces, so the form refuses before the round trip
 * rather than after it.
 *
 * The server stays the authority — these are duplicated deliberately and
 * narrowly, and the ones that are *not* here are the ones a browser cannot know:
 * uniqueness of `code` and of `name`, and whether live tickets block a retire.
 * All three come back as a field-keyed 409 and land on the input.
 *
 * **The rule that is deliberately absent is "DONE implies not open".** It reads
 * like an obvious fourth check and it is wrong: `RESOLVED` is DONE work on a
 * ticket that stays open until sign-off, and it is one of the eight seeded rows.
 * A form that enforced it would refuse the row the blueprint asks for.
 */
export function statusFormErrors(
  values: StatusFormValues,
): Partial<Record<keyof StatusFormValues, string>> {
  const errors: Partial<Record<keyof StatusFormValues, string>> = {}

  if (!values.name.trim()) {
    errors.name = 'A name is required.'
  } else if (values.name.trim().length > 40) {
    errors.name = 'At most 40 characters.'
  }

  if (!/^#[0-9A-Fa-f]{6}$/.test(values.colour)) {
    errors.colour = 'Pick a colour from the palette.'
  }

  if (values.seq.trim()) {
    const seq = Number(values.seq)
    if (!Number.isInteger(seq) || seq < 0 || seq > 32767) {
      errors.seq = 'A whole number between 0 and 32767, or blank to add at the end.'
    }
  }

  // Terminal means only a reopen moves a ticket on; open means the dashboard
  // counts it as outstanding. Together they would put every ticket that reached
  // this status into an open count nobody can drive to zero.
  if (values.isTerminal && values.isOpen) {
    errors.isTerminal =
      'A terminal status cannot also be open — tickets here would be counted as '
      + 'outstanding forever.'
  }

  return errors
}

export function toWriteRequest(values: StatusFormValues): StatusWriteRequest {
  return {
    code: values.code,
    name: values.name.trim(),
    category: values.category,
    colour: values.colour,
    seq: values.seq.trim() ? Number(values.seq) : null,
    isOpen: values.isOpen,
    isTerminal: values.isTerminal,
    isActive: values.isActive,
  }
}

/**
 * The whole form on every save, `code` included.
 *
 * Sending the stored code is a deliberate no-op on the server — S-13 is a
 * full-form submit, and any other reading would make every edit a 409. Sending
 * it is what makes a *changed* one refusable, which is the point:
 * `tickets.status` stores this string with no foreign key behind it, so a rename
 * would not cascade, it would orphan.
 */
export function toPatchRequest(values: StatusFormValues): StatusPatchRequest {
  return {
    code: values.code,
    name: values.name.trim(),
    category: values.category,
    colour: values.colour,
    seq: values.seq.trim() ? Number(values.seq) : null,
    isOpen: values.isOpen,
    isTerminal: values.isTerminal,
    isActive: values.isActive,
  }
}

// ---------------------------------------------------------------------------
// The matrix
// ---------------------------------------------------------------------------

/**
 * One row of the matrix grid: a move, with a cell per role.
 *
 * The grid is moves down the side and roles across the top, rather than statuses
 * on both axes with a role selector. Both were drawn; this one wins because the
 * question an Admin arrives with is "who may close a ticket?", which is one row
 * read across — and because the from×to grid is 64 cells of which 14 are ever
 * populated, so it renders mostly emptiness and hides the answer in it.
 */
export interface MatrixRow {
  fromStatus: StatusCode | null
  toStatus: StatusCode
  /**
   * Keyed by role code, and **not** typed `Record<RoleCode, …>`.
   *
   * The columns come from whatever roles the matrix actually contains, because
   * S-09 lets an Admin add a seventh — and a `Record<RoleCode, …>` would type
   * the grid to the six the contract enum happens to name today. The narrowing
   * back to `RoleCode` happens once, on the way onto the wire, where the
   * contract is the authority.
   */
  cells: Record<string, MatrixCell>
}

export interface MatrixCell {
  allowed: boolean
  requiresReason: boolean
  requiresEffort: boolean
  /** True when a row exists but is inactive — cleared, as against never configured. */
  wasCleared: boolean
}

export const EMPTY_CELL: MatrixCell = {
  allowed: false,
  requiresReason: false,
  requiresEffort: false,
  wasCleared: false,
}

/** Null is a real key here: it is the on-create row. */
export const moveKey = (from: StatusCode | null, to: StatusCode) => `${from ?? ''}>${to}`

/**
 * Folds the flat transition list into the grid the screen renders.
 *
 * **Every move that exists in *any* state gets a row**, active or cleared. A
 * cleared cell and a never-configured cell look identical once rendered, and an
 * Admin restoring the first is doing something different from authoring the
 * second — dropping the inactive rows here would erase that distinction before
 * the screen ever saw it.
 *
 * Rows are ordered with the on-create moves first and the rest by the statuses'
 * own `seq`, so the grid reads down the lifecycle rather than by insertion id.
 */
export function toMatrixRows(
  transitions: StatusTransition[],
  statuses: Status[],
): MatrixRow[] {
  const order = new Map<string, number>()
  statuses.forEach((s) => { if (s.code) order.set(s.code, s.seq ?? 0) })

  const rows = new Map<string, MatrixRow>()
  for (const t of transitions) {
    if (!t.toStatus || !t.roleCode) continue
    const from = (t.fromStatus ?? null) as StatusCode | null
    const key = moveKey(from, t.toStatus)
    let row = rows.get(key)
    if (!row) {
      row = { fromStatus: from, toStatus: t.toStatus, cells: {} }
      rows.set(key, row)
    }
    row.cells[t.roleCode] = {
      allowed: t.isActive ?? false,
      requiresReason: t.requiresReason ?? false,
      requiresEffort: t.requiresEffort ?? false,
      wasCleared: !(t.isActive ?? false),
    }
  }

  return [...rows.values()].sort((a, b) => {
    if ((a.fromStatus === null) !== (b.fromStatus === null)) {
      return a.fromStatus === null ? -1 : 1
    }
    const fromDelta =
      (order.get(a.fromStatus ?? '') ?? 0) - (order.get(b.fromStatus ?? '') ?? 0)
    if (fromDelta !== 0) return fromDelta
    return (order.get(a.toStatus) ?? 0) - (order.get(b.toStatus) ?? 0)
  })
}

/**
 * Flattens the grid back into the `PUT` body.
 *
 * **Only allowed cells are sent.** The route's contract is that anything not
 * listed is deactivated, so a cell an Admin unticked is expressed by its
 * absence — sending it with `allowed: false` would be a second way to say the
 * same thing, and the server has no field to read it from.
 */
export function toMatrixWriteRequest(rows: MatrixRow[]): StatusTransitionWrite[] {
  return rows.flatMap((row) =>
    Object.entries(row.cells)
      .filter(([, cell]) => cell.allowed)
      .map(([roleCode, cell]) => ({
        fromStatus: row.fromStatus,
        toStatus: row.toStatus,
        // The one narrowing, and it is here rather than on `MatrixRow` on
        // purpose — see that type's `cells`. A code the contract's enum does not
        // name reaches the server, which refuses it with a 409 naming the role;
        // the alternative is the grid silently dropping a column an Admin can
        // see, which is worse than a refusal they can read.
        roleCode: roleCode as RoleCode,
        requiresReason: cell.requiresReason,
        requiresEffort: cell.requiresEffort,
      })),
  )
}

/**
 * The one thing the browser can check before the round trip, and the only edit
 * on this screen that can lock the product out of itself.
 *
 * With no on-create row, no role can raise a ticket on any screen. Checked here
 * so the Save button can explain itself rather than the server refusing after
 * the click; the server refuses too, because a browser is not a guarantee.
 */
export function matrixHasOnCreateMove(rows: MatrixRow[]): boolean {
  return rows.some(
    (row) => row.fromStatus === null && Object.values(row.cells).some((c) => c.allowed),
  )
}

/**
 * Moves PLAN.md §5 settled as governance decisions, flagged in the grid.
 *
 * **Advice, not a lock.** `workflow_transitions` is a whitelist precisely so
 * these are data rather than code — B-003's seed header says changing the policy
 * should be "a seed edit, not a deploy", and S-13 makes it a screen edit. An
 * organisation whose sign-off process differs from ours has to be able to say
 * so. What the screen owes them is that they know which cells carry a decision
 * somebody already made, and why.
 */
export const GOVERNANCE_NOTES: Record<string, string> = {
  'RESOLVED>CLOSED':
    'G-3: closure belongs to the sign-off stage owner. Developer, QA and Deployment '
    + 'are deliberately excluded.',
  'CLOSED>REOPENED':
    'Blueprint §2 grants "Reopen ticket" to Admin, PM and Support Desk only.',
  'IN_PROGRESS>RESOLVED':
    'G-1: effort logging is blocking. Every move that claims work complete asks for '
    + 'effort first.',
  'REWORK>RESOLVED':
    'G-1: effort logging is blocking, the same as the first resolve.',
}
