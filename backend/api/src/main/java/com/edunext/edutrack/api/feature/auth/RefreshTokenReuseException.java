package com.edunext.edutrack.api.feature.auth;

/**
 * A-024 · a refresh token was presented after it had already been rotated away.
 * Blueprint §10.1: "re-use of a consumed refresh token ⇒ token theft ⇒ revoke
 * the whole family and force re-login."
 *
 * <p>By the time this is thrown the family has already been revoked. The
 * revocation is not the handler's job and must not become it — a refusal that
 * forgets to revoke is a refusal that leaves the attacker's own token working.
 *
 * <p><b>Why this one is distinguishable from
 * {@link InvalidRefreshTokenException}, when no other auth failure is.</b> Every
 * other generic refusal in this package exists to deny an outsider information
 * they do not already have. This path is reachable only by presenting a token
 * this server actually issued and actually consumed, so whoever reaches it knows
 * the token was real; the response tells them nothing new. What it does buy is
 * on the other side: the legitimate user is about to be signed out of a session
 * they did nothing to end, and "your session was used from somewhere else" is
 * how they find out they were compromised. A generic "session expired" hides a
 * security event from the only person who can act on it.
 *
 * <p>A stack trace <i>is</i> captured here, unlike the generic refusal. This is
 * a security event rather than routine control flow, it should be rare, and when
 * it fires the question is always "which call path detected it".
 */
class RefreshTokenReuseException extends RuntimeException {

    RefreshTokenReuseException() {
        super("Refresh token reuse detected; the token family has been revoked");
    }
}
