import type { Cycle } from '@/api/generated/model/cycle'
import type { RoleCode } from '@/api/generated/model/roleCode'
import type { Ticket } from '@/api/generated/model/ticket'

/**
 * C-020 · the two §4B.1 rules the summary panel needs as pure functions, plus
 * the one date arithmetic the preview cannot get right by itself.
 *
 * ## These decide what is *shown*, never what is *allowed*
 *
 * The same division `commentPermissions.ts` sets out. `PriorityChangeService`
 * refuses a caller without the capability with 403 and an assigned ticket with
 * no reason with 400, and a DOM manipulated past anything here still gets that
 * refusal. What these do is stop the product offering a control that will be
 * refused — a missing affordance is a nuisance, a phantom one is a lie.
 */

/**
 * §4B.1: "Admin, PM, Support Desk. Developer/QA/Deployment can only *request* a
 * change, which raises a notification to the PM."
 *
 * **False while `useGetMe()` is still resolving**, which is deliberate and is
 * C-017's lesson written down: an affordance that appears a beat after the panel
 * has already been read is worse than one that appears a beat late, because the
 * row it sits in shifts under the cursor. The Level row renders as a plain chip
 * until the viewer is known, which is exactly what it renders as for the three
 * roles that may not change it — so nothing moves either way.
 *
 * ⚠ The *request* path those three roles are offered instead does not exist yet.
 * It is a notification to the PM, which is Stream D's, and C-020 deliberately
 * does not draw a button for it: a "Request a change" control that silently did
 * nothing would be worse than the honest absence. Raised rather than faked.
 */
export function canChangeLevel(role: RoleCode | undefined): boolean {
  return role === 'ADMIN' || role === 'PM' || role === 'SUPPORT'
}

/**
 * §4B.1: "Mandatory free-text reason when the ticket is already assigned.
 * Optional at creation."
 *
 * Read from the ticket rather than from a flag on the wire, because it is
 * exactly the same fact the server checks — `tickets.assigned_to IS NOT NULL` —
 * and a derived boolean would be one more thing that can disagree with it.
 * `LevelReasonRequiredException` is the server's copy of this line.
 */
export function isLevelReasonRequired(ticket: Pick<Ticket, 'assignee'>): boolean {
  return ticket.assignee?.id != null
}

/**
 * When this cycle's SLA clock started — what the preview must measure from.
 *
 * **Not now**, and this is the single thing about C-020 that is easiest to get
 * wrong quietly. `usePlannedCloseDate` defaults `from` to now, which is right on
 * the create form because a ticket being raised starts its clock now. An
 * existing ticket's clock started when it was reported, so a preview measured
 * from now would show a comfortable date for a ticket that the server is about
 * to mark breached — the dialog and the row it writes would disagree by however
 * long the ticket has been open, and only the dialog would be visible.
 *
 * `PriorityChangeService.slaClockStart` is the server's copy of this function,
 * down to the fallback: the current cycle's start date, then the ticket's
 * creation. After a reopen the two differ and the cycle's is right — cycle 2's
 * SLA has never had anything to do with when the ticket was first raised.
 *
 * @returns an ISO instant, or `undefined` when neither is known — in which case
 *   the preview falls back to now, which is wrong by less than any guess
 */
export function slaClockStart(
  ticket: Pick<Ticket, 'createdAt' | 'cycleNo'>,
  cycles: Cycle[] | undefined,
): string | undefined {
  const currentCycleNo = ticket.cycleNo ?? 1
  const current = cycles?.find((c) => (c.cycleNo ?? 1) === currentCycleNo)
  return current?.startedAt ?? ticket.createdAt ?? undefined
}
