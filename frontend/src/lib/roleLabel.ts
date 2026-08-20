/**
 * The six system roles, in the words a person reads.
 *
 * Written for the S-07 resource grid (B-010) and moved here by B-050 because
 * `components/ribbon/` needs it too: a pending ribbon segment has no owner
 * yet — nobody has held that stage — and §4A.1 gives every stage an owning
 * *role*, so "Developer · unassigned" is the honest thing to show where the
 * avatar and name would otherwise be blank on five segments out of eight.
 *
 * In `lib/` rather than in either consumer because one of the two is a shared
 * component. `features/masters/resources/columns.tsx` re-exports it, so no
 * resource-screen call site changed.
 *
 * `Record<RoleCode, string>` and not a partial: adding a seventh role to the
 * contract must fail the build here rather than render an empty cell.
 */
import type { RoleCode } from '@/api/generated/model/roleCode'

export const ROLE_LABEL: Record<RoleCode, string> = {
  ADMIN: 'Admin',
  PM: 'PM',
  DEVELOPER: 'Developer',
  QA: 'QA',
  DEPLOYMENT: 'Deployment',
  SUPPORT: 'Support',
}
