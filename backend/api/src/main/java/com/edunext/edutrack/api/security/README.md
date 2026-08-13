# api/security

**Owner: Stream A · Shivendra**

Filter chain, permission model, ScopeResolver. Every ticket query is scoped here — never per-controller. Out-of-scope IDs return 404, not 403.

`jwt/` holds the access-token signing key, the encoder (A-022), the decoder and its validators (A-025/A-032), and the authorities converter (A-033). Minting a token for a given identity lives with the login it belongs to, in `feature/auth/`.

## The three questions, and where each is answered

| Question | Where | Task |
|---|---|---|
| Is the caller who they say they are, with a live token? | `SecurityConfig` + `JwtDecoderConfig` | A-032 ✅ |
| May this role do this thing at all? | `@PreAuthorize` on the handler, `permission/` | A-033 ✅ |
| Which rows may they see? | `ScopeResolver` | A-034 |
| Does an out-of-scope id answer 404? | detail routes | A-035 |

Keeping them separate is deliberate. Row scope in an annotation would be an authorisation rule expressed per controller, which is the thing CLAUDE.md forbids and blueprint §17 names as the top risk.

## Adding a route

**Every request-mapped handler must carry `@PreAuthorize`**, on the method or on its controller. `RouteAuthorizationTest` fails the build otherwise — there is no default and no exemption list.

Pick one of three:

```java
@PreAuthorize("hasAuthority('master.write')")   // a §2 capability. Prefer this.
@PreAuthorize("isAuthenticated()")              // any signed-in caller, said out loud
@PreAuthorize("permitAll()")                    // public — also add it to
                                                // EXPECTED_PUBLIC_ROUTES and
                                                // SecurityConfig.PUBLIC_API_PATHS
```

The capability comes from the blueprint §2 matrix, seeded into `permissions` by `V20260806_0900`. The eighteen codes are listed in `permission/Permissions.java`.

**Prefer `hasAuthority` over `hasRole`.** A role check hard-codes today's matrix into Java and stops matching the `roles` table the moment S-09 grants that capability to a seventh role. A permission check follows the grant. Role checks are for the few §2 rules genuinely expressed as a role rather than a capability.

The literals are validated: a `hasAuthority('typo')` that names no seeded code fails the build, as does a `hasRole('SUPPORT_DESK')` naming the pre-correction role code.

### Two things that surprise people

**`@PreAuthorize` runs *after* request-body validation.** `@Valid @RequestBody` is resolved during handler-argument resolution, before the method is invoked; the annotation is method-invocation advice. So an unauthorised caller sending an invalid body gets 400, not 403. It is not a bypass — the handler body never runs — but a permission test that asserts 403 will pass or fail depending on whether its fixture body happens to be valid. Assert deny on a route with no validated body, or send a valid one. `RouteAuthorizationTest.bodyValidationRunsBeforeThePermissionCheck` pins the behaviour.

**Only proxied calls are advised.** A `private` or `final` handler, or one invoked from inside the same bean, is not intercepted and its annotation silently does nothing. Handlers here are package-private instance methods called through the CGLIB proxy, which is the advised path.

## permission/

`Permissions` — the eighteen codes as constants. `RolePermissions` — the §2 matrix as a static map.

The **database is the authority**. A real login reads `role_permissions` and A-022 puts the result in the token's `permissions` claim, so S-09 can change a role's grants without a redeploy. The static map exists for the two callers that have no row to read: `DevNoAuthFilter`, whose principal is a property rather than a user, and A-036's matrix. `PermissionCatalogTest` parses every migration and fails the build if either mirror drifts.

Permissions are trusted from the token rather than re-read per request, so a revoked grant survives until the access token expires — at most 15 minutes. Blacklist the `jti` the way logout does if an immediate revoke is ever needed.

## Tests

| | What it holds |
|---|---|
| `PermissionCatalogTest` | Java catalogue ≡ the migrations. Text-parsed, no Docker. |
| `RouteAuthorizationTest` | Every route declares a decision; every literal is real; method security is actually enforced. |
| `JwtAuthoritiesConverterTest` | Claims → authorities, including absent and malformed ones. |
| `PermissionModelIT` | The whole chain against real MySQL and Redis, from a real login. |
| `SecurityChainIT` | A-032 — authentication, revocation, refusal shape. |

The rest of the suite is the allow-path net: everything runs as an authenticated principal and would start answering 403 if an annotation over-restricted. Keeping it green is part of the check, not incidental to it.
