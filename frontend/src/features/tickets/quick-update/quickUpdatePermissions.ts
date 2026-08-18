import type { RoleCode } from '@/api/generated/model/roleCode'

/**
 * C-037 · S-21's own exception to "level is not exposed here" — narrower than
 * the detail page's `canChangeLevel` (Admin/PM/Support, per §4B.1's inline
 * chip). The blueprint's field list for this panel names exactly one role:
 * "level (unless PM)". Admin and Support still have the detail page's own
 * chip for this; the fast panel draws its own, tighter line.
 *
 * Same division `commentPermissions.ts` and `levelChange.ts` both draw: this
 * decides what is *shown*, never what is *allowed*. `TicketLevelControl`
 * fires the same `PATCH .../priority` this reuses, and `PriorityChangeService`
 * enforces the real rule — Admin/PM/Support at the route (§4B.1's
 * `ticket.assign` borrow) — so a non-PM who somehow reached the control would
 * still be refused server-side. This only stops the panel offering an
 * affordance three of those roles never see and one of the three the route
 * *would* allow (Support) is deliberately not offered here either.
 */
export function canChangeLevelHere(role: RoleCode | undefined): boolean {
  return role === 'PM'
}
