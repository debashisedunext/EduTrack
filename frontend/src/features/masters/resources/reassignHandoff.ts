/**
 * B-014 · the one place that knows how the Resource Master hands off to the
 * S-24 bulk reassignment wizard, and how it gets back.
 *
 * <h2>Why this file exists at all</h2>
 *
 * Deactivating somebody who holds open tickets is refused in three places —
 * `POST /users/bulk-status`, `PATCH /users/{userId}`, and B-014's
 * `PATCH /users/{userId}/status` — and until now every one of those refusals was
 * a dead end. The blueprint's word for what should happen instead is *forces*:
 * "deactivating a resource with open tickets **forces** a bulk reassignment
 * wizard" (§S-07). A refusal that names a problem and offers no way through is
 * not a forcing function, it is an obstacle, and the reliable response to an
 * obstacle is to stop trying — which leaves the organisation with people who
 * have left and accounts that are still active.
 *
 * So the master screen must send the admin somewhere, and get them back. The
 * somewhere belongs to Stream C.
 *
 * <h2>The handoff contract with Stream C (C-063 / S-24)</h2>
 *
 * This module is Stream B's half, written against a screen that does not exist
 * yet. Two query parameters, both optional from the wizard's point of view:
 *
 * - **`fromUserId`** — preselects step one, "pick source resource". The admin
 *   has already told us who they are trying to deactivate; asking again is how a
 *   handoff becomes a fresh task.
 * - **`returnTo`** — an app-relative path to send the admin back to when the
 *   wizard finishes or is cancelled. Ours carries `?deactivate=<id>`, which is
 *   what makes the return leg resume the interrupted deactivation instead of
 *   dropping the admin on a grid where the person is still active and nothing
 *   explains why.
 *
 * **A wizard that ignores both still works** — it just starts empty and returns
 * nowhere, which is the behaviour C gets for free before wiring anything up.
 * That is deliberate: a handoff whose source screen breaks when the destination
 * ships a stage late is worse than one that degrades.
 *
 * **`returnTo` is validated on the way back in, not trusted.** It is a URL
 * parameter, which means it is user input; a path read out of the query string
 * and handed to `navigate()` is an open-redirect the day somebody puts an
 * absolute URL in it. {@link isSafeReturnPath} is the whole defence and it is
 * deliberately strict.
 *
 * <h2>What is not here</h2>
 *
 * The API path. `POST /tickets/bulk-reassign` is what the wizard calls, and the
 * server already names it in `reassignUrl` on both refusals. A browser cannot
 * navigate to it, so it is not the thing the screen needs — the two are
 * different facts about the same flow and collapsing them would mean either the
 * API emitting UI routes or the UI POSTing from a link.
 */

/** Stream C's route for S-24. Registered as a placeholder until C-063 lands. */
export const REASSIGN_WIZARD_ROUTE = '/tickets/bulk-reassign'

/** The Resource Master, which is both the origin and the destination. */
export const RESOURCE_LIST_ROUTE = '/masters/resources'

/**
 * The query parameter that turns a return trip into a resumed deactivation.
 *
 * Named for the intent rather than the mechanism — `?deactivate=42` says what
 * the screen should try, not that a wizard sent us. The grid resumes it whether
 * the admin arrived from the wizard, a bookmark, or a link somebody pasted into
 * chat; all three are the same request.
 */
export const RESUME_DEACTIVATION_PARAM = 'deactivate'

export interface ReassignHandoff {
  /** Whose tickets are being moved — the resource that could not be deactivated. */
  fromUserId: number
  /**
   * Preserved so the admin lands back on the page they left, filters and all.
   * Defaults to the bare list.
   */
  returnSearch?: string
}

/**
 * Where the wizard should send the admin when it is done.
 *
 * Exported because the return leg is testable on its own, and because a reader
 * looking at {@link RESUME_DEACTIVATION_PARAM} should be able to find the one
 * place it is written.
 */
export function resumeDeactivationHref(fromUserId: number, returnSearch = ''): string {
  const params = new URLSearchParams(returnSearch)
  params.set(RESUME_DEACTIVATION_PARAM, String(fromUserId))
  return `${RESOURCE_LIST_ROUTE}?${params.toString()}`
}

/** The link into the wizard, with the source resource and the way home. */
export function reassignWizardHref({ fromUserId, returnSearch = '' }: ReassignHandoff): string {
  const params = new URLSearchParams({
    fromUserId: String(fromUserId),
    returnTo: resumeDeactivationHref(fromUserId, returnSearch),
  })
  return `${REASSIGN_WIZARD_ROUTE}?${params.toString()}`
}

/**
 * Reads `?deactivate=` off the current query string.
 *
 * Returns null for anything that is not a positive integer rather than passing
 * a `NaN` to the mutation, which would reach the server as
 * `PATCH /users/NaN/status` and come back as a 400 the admin cannot act on.
 */
export function resumeDeactivationTarget(search: string): number | null {
  const raw = new URLSearchParams(search).get(RESUME_DEACTIVATION_PARAM)
  if (raw == null || !/^\d+$/.test(raw)) return null

  const userId = Number(raw)
  return Number.isSafeInteger(userId) && userId > 0 ? userId : null
}

/** The same query string with the resume marker taken out, for `replace`. */
export function withoutResumeMarker(search: string): string {
  const params = new URLSearchParams(search)
  params.delete(RESUME_DEACTIVATION_PARAM)
  const rest = params.toString()
  return rest ? `?${rest}` : ''
}

/**
 * Whether a `returnTo` handed to us by another screen is safe to navigate to.
 *
 * Not currently called from this side — the Resource Master is the screen that
 * *sends* `returnTo`, so it never consumes one. It lives here because the two
 * halves of one contract belong in one file, and because the wizard is going to
 * need exactly this check on the day it honours the parameter. Stream C is
 * welcome to import it or copy it; what should not happen is that nobody writes
 * it, since `navigate(searchParams.get('returnTo'))` looks completely reasonable
 * and is an open redirect.
 *
 * Rejects anything that is not a single-slash-rooted app path: absolute URLs,
 * scheme-relative `//evil.example`, and backslash variants that some browsers
 * normalise to a scheme-relative URL.
 */
export function isSafeReturnPath(path: string | null | undefined): path is string {
  if (!path) return false
  if (!path.startsWith('/')) return false
  return !path.startsWith('//') && !path.startsWith('/\\')
}
