/**
 * A-130 · the `type` URIs `/portal/auth/**` emits, as constants —
 * `problemTypes.ts`'s shape, for the portal's own surface.
 *
 * Mirrors `PortalAuthExceptionHandler` on develop@55803bb7 exactly. No
 * too-many-attempts URI: A-130 ships no rate limiter on this surface yet
 * (named there as a deliberately deferred follow-up, not a gap to fill here)
 * — callers still check `error.status === 429` as a generic fallback in case
 * something upstream (a gateway, a future limiter) ever throttles the route.
 */

export const PORTAL_INVALID_CREDENTIALS = 'errors/invalid-credentials'
export const PORTAL_ACCOUNT_LOCKED = 'errors/account-locked'
export const PORTAL_INVALID_CREDENTIAL_LINK = 'errors/invalid-credential-link'
export const PORTAL_WEAK_PASSWORD = 'errors/weak-password'

/**
 * The replacement password is the one being replaced.
 *
 * Its own type rather than `weak-password`, because the form has to say
 * something different: the password may be perfectly strong and is still
 * refused. A forced change that accepted the temporary password back would
 * clear the flag while leaving the staff-readable credential in place.
 */
export const PORTAL_PASSWORD_UNCHANGED = 'errors/password-unchanged'

/**
 * The caller is a client in good standing who has not yet replaced the
 * temporary password they were issued, and asked for something other than
 * doing so. `PortalPasswordChangeFilter` answers it on every portal route but
 * `PATCH /portal/me/password`.
 *
 * **403, not 401**, and the portal's interceptor must not treat it as a
 * session failure: the credentials are perfectly good, and signing in again
 * mints another token with the same claim, so a redirect to the login card
 * would loop. The correct answer is `/portal/change-password`.
 *
 * Distinct from staff's `password-change-required` on purpose — a shared URI
 * would send a client to `/change-password`, a staff route the portal does
 * not have.
 */
export const PORTAL_PASSWORD_CHANGE_REQUIRED = 'errors/portal-password-change-required'

/** Unrelated to auth — `PortalOnboardingExceptionHandler`'s, kept as-is. */
export const PORTAL_NO_PRIMARY_CONTACT = 'errors/portal-no-primary-contact'
