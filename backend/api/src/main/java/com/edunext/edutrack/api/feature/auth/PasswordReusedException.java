package com.edunext.edutrack.api.feature.auth;

/**
 * A-028 · the new password is one of the last few this account already used.
 * Blueprint §10.3, "no reuse of last 3".
 *
 * <p><b>Distinct from {@link PasswordUnchangedException}, and the two are not
 * redundant.</b> That one is the degenerate case — the replacement is the
 * password being replaced right now — and it is checkable without touching the
 * database, so A-026 could enforce it before this table existed. This one is
 * "you used that three changes ago", which needs history. Keeping them separate
 * lets S-03 say which happened; collapsing them would tell a user cycling back
 * to an old favourite that their new password is the same as their current one,
 * which is simply untrue and unactionable.
 *
 * <p>400 rather than 401 or 403, like every other policy refusal: the caller
 * authenticated correctly and the request is well-formed — it is the
 * <i>content</i> the policy will not accept.
 *
 * <p><b>The message never says which previous password matched, or when.</b>
 * "You used this in March" narrows an attacker's search materially if the
 * caller is not the account's owner — and on the reset path they need not be
 * authenticated as anyone at all.
 */
class PasswordReusedException extends RuntimeException {

    PasswordReusedException() {
        super("New password matches a recently used one", null, false, false);
    }
}
