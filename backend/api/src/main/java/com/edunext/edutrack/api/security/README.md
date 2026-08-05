# api/security

**Owner: Stream A · Shivendra**

Filter chain, ScopeResolver, permission model. Every ticket query is scoped here — never per-controller. Out-of-scope IDs return 404, not 403.
