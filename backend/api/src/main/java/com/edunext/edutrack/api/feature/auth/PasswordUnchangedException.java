package com.edunext.edutrack.api.feature.auth;

/**
 * A-026 · the new password is the current one.
 *
 * <p><b>Without this, the forced change is not a change.</b> A user handed a
 * temporary password and told to replace it can otherwise submit the same string
 * back: {@code must_change_password} flips to 0, the admin-generated password
 * that was emailed in plain text stays live, and the account reads as remediated
 * in every report. That is worse than not having the flag, because it manufactures
 * evidence that the credential was rotated.
 *
 * <p>It is a 400, not a 401 — the caller authenticated fine and knew their own
 * password; the <i>request</i> is the thing that does not make sense. A distinct
 * {@code type} rather than a field error, so S-03 can put the message under the
 * new-password field without parsing prose.
 *
 * <p>Not to be confused with A-028's no-reuse-of-last-3 rule, which needs a
 * password history table that does not exist yet. This is the degenerate case of
 * that rule — reuse of the <i>zeroth</i> previous password — and is the one part
 * of it checkable today, because the current hash is right there on the row.
 */
class PasswordUnchangedException extends RuntimeException {

    PasswordUnchangedException() {
        super("New password is the current password", null, false, false);
    }
}
