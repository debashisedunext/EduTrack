package com.edunext.edutrack.api.feature.auth;

/**
 * A-022 · a minted access token and the lifetime it was minted with.
 *
 * @param value            the signed JWT, compact-serialised.
 * @param expiresInSeconds 900, per §10.1 — carried alongside the token rather
 *                         than recomputed from it, so nothing has to parse the
 *                         JWT it just issued to answer "how long is this good
 *                         for".
 */
record AccessToken(String value, int expiresInSeconds) {
}
