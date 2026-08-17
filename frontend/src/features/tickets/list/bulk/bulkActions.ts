import type { RoleCode } from '@/api/generated/model/roleCode'
import type { Ticket } from '@/api/generated/model/ticket'
import type { BulkResultResponseData } from '@/api/generated/model/bulkResultResponseData'

/**
 * C-017 · who may bulk-act on the S-17 grid, and which of a selection each
 * action can actually reach — as pure functions, so the page can be tested
 * without a server and the rules can be tested without a page.
 *
 * ## These decide what is *shown*, never what is *allowed*
 *
 * Same division `commentPermissions.ts` draws, for the same reason. The three
 * bulk endpoints refuse a caller who is not a PM or an Admin with `403`, and a
 * request built by hand past a hidden toolbar still gets that refusal. What
 * this file does is stop the grid offering a control that will be refused —
 * a missing affordance is a nuisance, a phantom one is a lie.
 *
 * **Row scoping is not modelled here at all.** The grid only ever holds rows
 * the server already scoped, so a selection cannot contain an out-of-scope id
 * in the first place; and if one somehow arrives — a stale tab, a hand-edited
 * URL — the server reports it per ticket as `Not found or out of scope`, which
 * is deliberately indistinguishable from a ticket that never existed. Trying to
 * pre-empt that client-side would be exactly the frontend filtering CLAUDE.md
 * forbids.
 */

/** The two roles blueprint §7.5 puts bulk actions behind. */
const BULK_ROLES: readonly RoleCode[] = ['ADMIN', 'PM']

/**
 * Whether the selection column and the action bar are drawn at all.
 *
 * **Returns false while `useGetMe()` is still resolving**, which is the safe
 * direction: a column that appears a beat after the grid shifts every other
 * column sideways under the cursor, and a Developer who briefly sees a Close
 * button has been told something untrue about their own permissions.
 */
export function canBulkAct(role: RoleCode | undefined): boolean {
  return role != null && BULK_ROLES.includes(role)
}

/**
 * The subset of a selection a bulk **close** can act on.
 *
 * A closed ticket is excluded rather than sent and refused. The server refuses
 * it either way — re-stamping `actualCloseDate` moves a date reports depend on,
 * and re-sealing a sealed stage transition is the mutation the append-only rule
 * exists to forbid — but a selection that spans a page boundary is easy to make
 * accidentally, and "38 closed, 2 refused" is a worse answer than closing the
 * 38 the user meant and saying so up front.
 *
 * Rows are matched by id against what is on screen. **Ids the grid cannot see
 * are treated as closable**, not dropped: selection survives paging, so a
 * ticket ticked two pages back has no row here to read a status from, and
 * silently excluding it would quietly shrink a batch the user assembled
 * deliberately. The server is the authority on those.
 */
export function closableIds(
  selected: ReadonlySet<string>,
  visible: readonly Ticket[],
): string[] {
  const closedOnScreen = new Set(
    visible.filter((t) => t.status === 'CLOSED').map((t) => t.ticketId),
  )
  return [...selected].filter((id) => !closedOnScreen.has(id))
}

/** How many of the selection a close would skip — what the dialog warns about. */
export function alreadyClosedCount(
  selected: ReadonlySet<string>,
  visible: readonly Ticket[],
): number {
  return selected.size - closableIds(selected, visible).length
}

/**
 * One line summarising a `BulkResultResponse`, for the toast or the dialog head.
 *
 * Singular and plural are spelled out rather than suffixed with `(s)`: this
 * string is read aloud by a screen reader on a `role="status"` region, and
 * "1 ticket(s) updated" is what it sounds like.
 */
export function summariseBulkResult(result: BulkResultResponseData, verb: string): string {
  const succeeded = result.succeeded ?? 0
  const failed = result.failed ?? 0
  const noun = succeeded === 1 ? 'ticket' : 'tickets'
  if (failed === 0) return `${succeeded} ${noun} ${verb}`
  return `${succeeded} ${noun} ${verb} · ${failed} refused`
}

/** The refused rows, which are the only ones worth listing back to the user. */
export function failedResults(result: BulkResultResponseData) {
  return (result.results ?? []).filter((r) => r.ok === false)
}

/**
 * Selection minus the rows that are no longer reachable.
 *
 * Called after every bulk action so the bar does not keep claiming ownership of
 * tickets the server just refused or the filters just excluded. Ids that
 * succeeded are dropped; ids that were refused stay ticked, because the user
 * has something left to do about those and clearing them would hide the
 * failure the moment the dialog closed.
 */
export function selectionAfter(
  selected: ReadonlySet<string>,
  result: BulkResultResponseData,
): Set<string> {
  const succeeded = new Set(
    (result.results ?? [])
      .filter((r) => r.ok && r.ticketId != null)
      .map((r) => r.ticketId as string),
  )
  return new Set([...selected].filter((id) => !succeeded.has(id)))
}
