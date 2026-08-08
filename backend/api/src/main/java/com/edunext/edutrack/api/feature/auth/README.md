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
the cookie (A-023).

Nothing yet **reads** either token: `POST /auth/refresh` with rotation and
family revocation is A-024, logout and the access-token blacklist are A-025,
and the filter chain that rejects a missing or expired access token is A-032.
`StoredRefreshToken#matchesDevice` is the hook A-024 needs for the device
binding — the fingerprint is recorded today but not enforced, because the only
place it can be checked is the endpoint that does not exist yet.
