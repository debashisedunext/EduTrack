import type { RoleCode } from '@/api/generated/model/roleCode'

/**
 * B-065 · which affordance the week grid draws, as a pure function over the
 * viewer's role — `commentPermissions.ts`'s own precedent.
 *
 * ## This decides what is *shown*, never what is *allowed*
 *
 * The real rule is server-side and narrower than a role: `TimesheetApprovalService`
 * refuses with 404 unless the caller is an Admin or this resource's own
 * *direct* reporting manager, which this page has no data to check without a
 * second round trip the grid does not otherwise need. So this only draws the
 * half of the rule that never depends on a row — a Support agent or a
 * delivery role can never approve ANY week, which is exactly what
 * `hasAnyRole('ADMIN','PM')` refuses first on the server, before a row is
 * looked up at all. A PM who is not this particular resource's manager still
 * sees the button and still gets refused on submit, on `HandoffDialog`'s own
 * precedent for a control that can be shown and still declined.
 */
export function canApproveTimesheet(viewer: { role?: RoleCode }): boolean {
  return viewer.role === 'ADMIN' || viewer.role === 'PM'
}
