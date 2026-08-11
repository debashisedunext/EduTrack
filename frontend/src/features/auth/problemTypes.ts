/**
 * The `type` URIs the auth endpoints emit, as constants.
 *
 * `ApiError.is()` matches on the suffix of `problem.type`, which is the stable
 * half of an RFC 9457 document. `title` and `detail` are written for humans and
 * may be reworded without notice, so a screen that branches on them breaks on a
 * copy edit — see `api/http.ts`.
 *
 * Every value here is grepped straight out of the backend. Adding a case to a
 * screen means adding it here first, so the set of outcomes a user can hit is
 * readable in one file rather than scattered across five `catch` blocks.
 */

/** Wrong username, wrong password, unknown user, deactivated account — one problem for all four. */
export const INVALID_CREDENTIALS = 'errors/invalid-credentials';

/** Five failures inside the window. Reported only *after* correct credentials (A-021). */
export const ACCOUNT_LOCKED = 'errors/account-locked';

/** The password was right and this account has a second factor. Not a failure — a next step. */
export const TWO_FACTOR_REQUIRED = 'errors/two-factor-required';

/** The six digits (or the recovery code) did not verify. */
export const INVALID_TOTP_CODE = 'errors/invalid-totp-code';

/** The session is valid but every route except the change-password screen is closed (A-026). */
export const PASSWORD_CHANGE_REQUIRED = 'errors/password-change-required';

/** Reset link expired, already used, or never existed. All three, deliberately (A-027). */
export const INVALID_RESET_TOKEN = 'errors/invalid-reset-token';

/** Matched one of the last three passwords (A-028). Only the server can know this. */
export const PASSWORD_REUSED = 'errors/password-reused';

/** The "new" password is the current one. */
export const PASSWORD_UNCHANGED = 'errors/password-unchanged';

/** Bean Validation rejected the body — read `ApiError.fieldErrors` for the per-field messages. */
export const VALIDATION = 'errors/validation';

/** Enrolment already completed; `setup` refuses rather than silently reissuing (A-029). */
export const TWO_FACTOR_ALREADY_ENABLED = 'errors/two-factor-already-enabled';

/** Confirm or disable was called on an account that never enrolled. */
export const TWO_FACTOR_NOT_ENROLLED = 'errors/two-factor-not-enrolled';
