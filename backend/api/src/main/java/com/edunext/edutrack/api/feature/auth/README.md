# feature/auth

**Owner: Stream A · Shivendra**

Login, JWT, refresh rotation, password reset, 2FA. Screens S-01…S-04.

## The two tokens

| | Access token (A-022) | Refresh token (A-023) |
|---|---|---|
| Form | Signed JWT, self-describing | Opaque — 256 random bits, means nothing on its own |
| Life | 15 minutes | 7 days |
| Carried in | Response body → memory | `HttpOnly` cookie, never the body |
| Stored server-side | Nothing | `SHA-256(value)` → `StoredRefreshToken` in Redis |
| Revocable | No — valid until it expires | Yes, instantly |

The asymmetry is the point. A JWT cannot be withdrawn, which is fine for
fifteen minutes and unacceptable for seven days; an opaque token is only as
valid as the Redis entry behind it, which is what lets A-024 revoke a family
and A-025 log someone out for real.

The refresh token's raw value exists in exactly two places — the `Set-Cookie`
header and the browser's cookie jar. It is never in a response body, never in
a log, and never in Redis. `Digests` explains why the stored form is SHA-256
and not Argon2id.

## Landed, and what is still open

`AuthenticationService` verifies credentials (A-020) and counts failures
(A-021). `AccessTokenIssuer` mints the JWT (A-022). `RefreshTokenIssuer` mints
the refresh token, stamps a family and a device fingerprint on it, and returns
the cookie (A-023). `RefreshRotationService` rotates it with family revocation
on reuse (A-024). `LogoutService` ends one session — refresh token deleted,
access `jti` blacklisted (A-025). `PasswordChangeService` is `PATCH
/me/password` — verify, reject a same-password resubmission, write the hash
and clear `must_change_password` in one statement, revoke the token that did
it (A-026).

Two routes now authenticate their own caller — through the shared
`AccessTokenVerifier` — because A-032's filter chain does not exist yet:
`LogoutService` and `PasswordChangeService`. When that chain lands it replaces
both callers at once.

**Two hooks are built and not yet read**, same shape, same reason: the only
place either can be enforced is a filter chain that does not exist.
`StoredRefreshToken#matchesDevice` is A-024's device fingerprint, recorded on
every refresh token since A-023. `PasswordChangeGate` is A-026's decision —
`AccessTokenIssuer#MUST_CHANGE_PASSWORD_CLAIM` is stamped into the token
already, but nothing consults the gate on a route that is not `/me/password`,
`/auth/logout` or `/auth/refresh`. A-032 wires both.

Not started: A-027 (forgot/reset password), A-028 (composition policy and
no-reuse-of-last-3 — needs a `password_history` table), A-029 (TOTP).
