import type { ProjectMember } from '@/api/generated/model/projectMember'
import type { ProjectMemberPatch } from '@/api/generated/model/projectMemberPatch'
import type { ProjectRoleCode } from '@/api/generated/model/projectRoleCode'

/**
 * B-017 · the Team tab's decisions, out of the component.
 *
 * Kept here for the reason `projectForm.ts` gives: the mapping between what
 * somebody did on screen and what goes on the wire is the part worth testing on
 * its own, and it is unreachable behind a rendered page. The two decisions that
 * matter — what "clear this field" means, and when an allocation is a problem —
 * are both here.
 */

/** The six of blueprint §7.4 S-10. `ProjectRoleCode`'s whole set, in tab order. */
export const PROJECT_ROLE_OPTIONS: readonly { value: ProjectRoleCode; label: string }[] = [
  { value: 'PM', label: 'Project Manager' },
  { value: 'DEVELOPER', label: 'Developer' },
  { value: 'QA', label: 'QA' },
  { value: 'DEPLOYMENT', label: 'Deployment' },
  { value: 'SUPPORT', label: 'Support' },
  { value: 'VIEWER', label: 'Viewer' },
] as const

/**
 * The sentinel the role `Select` uses for "no project role".
 *
 * A radix `SelectItem` cannot have `value=""` — it throws, because the empty
 * string is how it represents "nothing selected". So the absence needs a name,
 * and it must not be a `ProjectRoleCode`: `INHERIT` is deliberately not in the
 * enum, so a bug that leaked it onto the wire fails the server's `@Pattern`
 * loudly instead of storing a seventh role nobody defined.
 *
 * Same sentinel and same reason as `ProjectAssignmentsEditor` (B-011), which is
 * this control's mirror image on the resource form.
 */
export const NO_PROJECT_ROLE = 'INHERIT'

export const ROLE_LABELS: Record<ProjectRoleCode, string> = Object.fromEntries(
  PROJECT_ROLE_OPTIONS.map((o) => [o.value, o.label]),
) as Record<ProjectRoleCode, string>

/**
 * What a role change means on the wire.
 *
 * **Returns `{ projectRole: null }`, never `{}`, when the role is cleared.** The
 * operation reads an omitted key as "leave it alone" and an explicit null as
 * "clear it" — so a patch that dropped the key would make going back to "same as
 * their global role" impossible, and the UI would show a change that silently
 * did not happen.
 */
export function roleChangePatch(next: string): ProjectMemberPatch {
  return { projectRole: next === NO_PROJECT_ROLE ? null : (next as ProjectRoleCode) }
}

/**
 * What an allocation change means on the wire.
 *
 * An empty input is "not stated", which is a real value and not a missing one —
 * so it clears rather than omits, exactly as the role does. Anything that is not
 * a number in 0–100 returns `null` for "do not send this", which the caller
 * treats as "the field is mid-edit", not as a clear.
 */
export function allocationChangePatch(raw: string): ProjectMemberPatch | null {
  const trimmed = raw.trim()
  if (trimmed === '') return { allocationPct: null }

  const value = Number(trimmed)
  if (!Number.isInteger(value) || value < 0 || value > 100) return null
  return { allocationPct: value }
}

/**
 * Total stated allocation across a team, and whether it is worth saying.
 *
 * **Members with no stated allocation contribute nothing and are counted
 * separately.** Treating them as 100 would make almost every real project read
 * as wildly over-committed on the day this screen shipped — every membership
 * B-011 and B-007 wrote has no allocation, because no screen had an input for
 * one. Treating them as 0 would be just as wrong in the other direction, which
 * is why the count is surfaced rather than folded in.
 *
 * This is a **project** total, and it is not a rule. A project's team summing to
 * 340% is normal — six people at varying commitments — so this figure is
 * informational. The number that would be a warning is a *resource's* total
 * across their projects, which this screen cannot see: it has one project's
 * rows. That belongs to B-061's capacity report, and is flagged rather than
 * approximated from here.
 */
export function summariseAllocation(members: readonly ProjectMember[]) {
  const stated = members.filter((m) => m.allocationPct != null)
  return {
    totalPct: stated.reduce((sum, m) => sum + (m.allocationPct ?? 0), 0),
    statedCount: stated.length,
    unstatedCount: members.length - stated.length,
  }
}

/**
 * Members who cannot be removed right now, and why.
 *
 * The roster already carries `openTicketCount`, so the tab can say so before the
 * click rather than spending a round trip to learn what was on screen the whole
 * time — B-014's lesson from the resource grid, where a refusal arriving after
 * the action made it read as a failure of the click rather than a fact about the
 * organisation.
 */
export function isRemovable(member: ProjectMember): boolean {
  return (member.openTicketCount ?? 0) === 0
}

/**
 * Whether the two roles differ, which is the thing this tab exists to show.
 *
 * A membership with no project role is **not** a difference — it means "same as
 * their global role", which is the common case and would drown the signal if it
 * were highlighted.
 */
export function overridesGlobalRole(member: ProjectMember): boolean {
  return member.projectRole != null && member.projectRole !== member.role
}
