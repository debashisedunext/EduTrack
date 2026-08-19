import { isSafeReturnPath } from '@/features/masters/resources/reassignHandoff'

/**
 * C-063 · the pure half of S-24 — reading the handoff B-014 sends, and
 * deciding where the wizard goes when it is done.
 *
 * Split out from the page for the reason `reassignHandoff.ts` itself gives:
 * the round trip is the behaviour, and it is testable without a router or a
 * server.
 */

/** Falls back to the tickets list — this wizard's own stream — when nothing safer is known. */
export const DEFAULT_DESTINATION = '/tickets'

export interface WizardHandoff {
  /** Preselects step 1. Null when the wizard was opened without one — it still works, just empty. */
  fromUserId: number | null
  /** Where "Done" and "Cancel" go. Null when the caller sent nothing, or sent something unsafe. */
  returnTo: string | null
}

/**
 * Reads `?fromUserId=&returnTo=` off the current query string.
 *
 * `fromUserId` follows {@link resumeDeactivationTarget}'s rule in
 * `reassignHandoff.ts`: anything that is not a positive integer is read as
 * "no preselection" rather than passed through as `NaN`, which would reach
 * `GET /tickets?assigneeId=NaN` and come back a 400 the user cannot act on.
 *
 * `returnTo` is validated here, not trusted — see {@link isSafeReturnPath}'s
 * own javadoc. An unsafe value is treated exactly like an absent one: the
 * wizard still works, it just has nowhere of the caller's choosing to send
 * the user back to.
 */
export function parseWizardHandoff(search: string): WizardHandoff {
  const params = new URLSearchParams(search)

  const rawUserId = params.get('fromUserId')
  const parsedUserId = rawUserId != null && /^\d+$/.test(rawUserId) ? Number(rawUserId) : NaN
  const fromUserId = Number.isSafeInteger(parsedUserId) && parsedUserId > 0 ? parsedUserId : null

  const rawReturnTo = params.get('returnTo')
  const returnTo = isSafeReturnPath(rawReturnTo) ? rawReturnTo : null

  return { fromUserId, returnTo: returnTo ?? null }
}

/** Where "Done" (and "Cancel", before anything has been sent) navigates to. */
export function wizardDestination(returnTo: string | null): string {
  return returnTo ?? DEFAULT_DESTINATION
}
