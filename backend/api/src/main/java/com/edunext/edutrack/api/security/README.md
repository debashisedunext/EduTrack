# api/security

**Owner: Stream A · Shivendra**

Filter chain, ScopeResolver, permission model. Every ticket query is scoped here — never per-controller. Out-of-scope IDs return 404, not 403.

`jwt/` holds the access-token signing key and encoder bean (A-022). Minting a token for a given identity lives with the login it belongs to, in `feature/auth/`.
