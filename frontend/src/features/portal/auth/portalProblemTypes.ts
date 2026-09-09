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

/** Unrelated to auth — `PortalOnboardingExceptionHandler`'s, kept as-is. */
export const PORTAL_NO_PRIMARY_CONTACT = 'errors/portal-no-primary-contact'
