import type { Me } from '@/api/generated/model/me'
import { isObImplementor } from '@/features/onboarding/mytasks/myTasks'

/**
 * Who is reading the project page, and therefore what it counts.
 *
 * <h2>One question, asked once</h2>
 *
 * <p>Three things on this page change with the reader — which tasks the tree
 * carries, which Steps the timeline draws, and whose work the Module strip counts
 * — and all three are the same question. Asked three times it would be answered
 * three ways the first time somebody edits one of them, so it is asked here and
 * passed down as a value.
 *
 * <h2>This decides what is easy to read, never what is permitted</h2>
 *
 * <p>CLAUDE.md's row-scoping rule is categorical: scoping is applied
 * server-side, never by a frontend filter. Nothing here stands in for that. The
 * project read is already scoped to what the caller may see, and a write by
 * somebody who is not the task's owner is refused with
 * `NotStepOwnerException` whatever this returns. What this does is choose
 * between two readings of data the caller already holds.
 *
 * <p>So the direction of a wrong answer matters, and only one direction is
 * acceptable: hiding somebody's colleagues' work from them is a nuisance,
 * showing one person's work to somebody outside the scope would be a leak — and
 * the second is not reachable from here, because the filter only ever
 * <em>removes</em> rows from a response the server already decided to send.
 *
 * <h2>Absent is a real session, and gets the unfiltered view</h2>
 *
 * <p>`Me.moduleRoles` is advisory and up to fifteen minutes stale by the access
 * token's own bargain, and a platform `ADMIN` who holds no onboarding module
 * role is an ordinary session rather than a fault. Falling back to `ALL` means a
 * claim that has not caught up never hides a reader's own project from them.
 */

/** Module roles that read the whole module rather than their own queue. */
export const OB_ADMIN = 'OB_ADMIN'
export const OB_MANAGER = 'OB_MANAGER'
export const OB_SALES = 'OB_SALES'

export type ObViewerKind = 'IMPLEMENTOR' | 'ALL'

export interface ObViewerScope {
  /**
   * `IMPLEMENTOR` — only tasks this person owns or backs up, and only the Steps
   * holding them. `ALL` — the project as it stands, every Step included.
   */
  kind: ObViewerKind
  /** The signed-in user, or null while `/me` is still in flight. */
  meId: number | null
  /**
   * Draw the per-implementor accordion under the Module strip.
   *
   * <p>Admin and Manager only. A salesperson chasing a project needs every
   * Step; the workload split is a management reading and already has a screen
   * of its own in the dashboard's implementor workload grid.
   */
  showsBreakdown: boolean
}

/**
 * The reader's scope.
 *
 * <p>There is no longer a control over it. An implementor's **Show all Steps**
 * used to swap this between `IMPLEMENTOR` and `ALL`, and the project page now
 * offers a different two-position switch in the same corner — outstanding
 * tasks against all of the reader's tasks, `taskFilter.ts`. Two controls both
 * reading "show all" and meaning different things is one too many, and the one
 * that survived is the one people press. Who the rows belong to is settled
 * here; which of them are drawn is settled there.
 *
 * @param me `/me`, or undefined while it is loading.
 */
export function obViewerScope(me: Me | undefined | null): ObViewerScope {
  const meId = me?.id ?? null
  const implementor = isObImplementor(me)

  /*
    An implementor whose id has not arrived yet cannot be filtered *to*, and
    filtering to nobody would empty the page while `/me` is in flight. The
    unfiltered view is the honest intermediate state, and it corrects itself on
    the next render rather than flashing an empty ribbon.
  */
  const kind: ObViewerKind = implementor && meId != null ? 'IMPLEMENTOR' : 'ALL'

  return {
    kind,
    meId,
    showsBreakdown: isObModerator(me),
  }
}

/**
 * `OB_ADMIN` or `OB_MANAGER` — the module's moderators.
 *
 * <p>Two things read this: the Module strip's per-implementor popover, and
 * Reassign on the task action bar. Both are the same question — "does this
 * person act on other people's work?" — and asking it twice is how two parts of
 * one screen end up disagreeing about who somebody is.
 *
 * <p>Advisory, like everything off `moduleRoles`. `ObJourneyStepLifecycleService`
 * gates the reassign itself with `requireModerator`, so a stale claim here can
 * only offer a button the server then refuses — never perform one.
 */
/**
 * `OB_ADMIN` or `OB_SALES` — who may edit a project's own record.
 *
 * <p>Mirrors `ObModuleRoleRules`' `ADMIN_AND_SALES` on
 * `PATCH /onboarding/projects/{id}`, and not a narrower "admin only": a project
 * is what Sales sells, and correcting one is the natural continuation of
 * boarding the company. Drawing the button for a role the server refuses would
 * be a control that lies; hiding it from a role the server accepts would be a
 * screen that lies the other way. The server's rule is the one that holds.
 *
 * <p>Advisory, like everything off `moduleRoles`: `ObModuleRoleFilter` decides
 * per request, so the worst a stale answer here does is show an Edit button
 * that answers 403 — never save an edit the caller may not make.
 */
export function isObProjectEditor(me: Me | undefined | null): boolean {
  const role = me?.moduleRoles?.ONBOARDING
  return role === OB_ADMIN || role === OB_SALES
}

/**
 * `OB_ADMIN` alone — the one role that reviews a project it is not named on.
 *
 * <p>Distinct from {@link isObModerator}, and the distinction is the whole
 * point of the review gate's authorisation: who reviews a task is the
 * project's own `implementorManagerUserId`, compared against the reader's id.
 * Holding `OB_MANAGER` says you manage something, not that you manage *this*.
 *
 * <p>This is the escape hatch — a project whose named manager has left, or was
 * never set, would otherwise be a review nobody on earth could close. The
 * server draws the same line in `requireReviewer`.
 */
export function isObAdmin(me: Me | undefined | null): boolean {
  return me?.moduleRoles?.ONBOARDING === OB_ADMIN
}

export function isObModerator(me: Me | undefined | null): boolean {
  const role = me?.moduleRoles?.ONBOARDING
  return role === OB_ADMIN || role === OB_MANAGER
}

/** Reads better at the call sites than comparing the string. */
export function isMineOnly(scope: ObViewerScope): boolean {
  return scope.kind === 'IMPLEMENTOR'
}
