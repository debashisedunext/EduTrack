# api/security

**Owner: Stream A · Shivendra**

Filter chain, permission model, ScopeResolver. Every ticket query is scoped here — never per-controller. Out-of-scope IDs return 404, not 403.

`jwt/` holds the access-token signing key, the encoder (A-022), the decoder and its validators (A-025/A-032), and the authorities converter (A-033). Minting a token for a given identity lives with the login it belongs to, in `feature/auth/`.

## The three questions, and where each is answered

| Question | Where | Task |
|---|---|---|
| Is the caller who they say they are, with a live token? | `SecurityConfig` + `JwtDecoderConfig` | A-032 ✅ |
| May this role do this thing at all? | `@PreAuthorize` on the handler, `permission/` | A-033 ✅ |
| Which rows may they see? | `scope/ScopeResolver` | A-034 ✅ |
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

## scope/

Row scope. `ScopeResolver` turns the caller into a `Specification<Ticket>`; `ScopedTickets` is the only place it gets composed in, and every ticket read in the application goes through that bean rather than through `TicketRepository`.

| Role | Sees |
|---|---|
| ADMIN | every ticket |
| PM, SUPPORT | `project_id IN (their projects)` |
| DEVELOPER, QA, DEPLOYMENT | `assigned_to = them` |
| anything else | nothing |

**Everything not in the first three rows is deny-all** — a PM in no projects, a role code the §2 matrix does not contain, an unidentifiable caller. The `IN ()` case is the one that matters: dropping an empty-list predicate is the usual defence and it promotes that PM to Admin with no error and no log line. `TicketScopeIT` pins it.

Admin is an always-true predicate, not a `null` specification, so "unrestricted" and "the guard was never consulted" cannot be the same value.

**Adding a ticket query:** call `ScopedTickets`, and pass your own filter as the `criteria` argument — it is `AND`-ed onto the scope and cannot replace it. **Do not autowire `TicketRepository`** — since A-037 that is a build failure, not a convention.

If your class genuinely has no caller to scope by, annotate it `@UnscopedAccess("why")` and say so in the reason. Two classes do today, both because they answer something other than a user: `InboundReplyService` (a mail server) and `SingleTicketFixture` (seed data, `fixtures` profile). The reason is mandatory and a blank one fails the build — an exemption you cannot justify in a sentence is one to reconsider rather than to write. The `worker` SLA scanners are outside the rule rather than exempt from it: they are a different module, with no request and no caller at all.

### 404, never 403 (A-035)

Two different questions produce two different codes, and getting them the wrong way round is the leak:

| Refusal | Code | Because |
|---|---|---|
| the role lacks the capability | **403** | it is a fact about the caller, not about any ticket |
| the ticket is not in the caller's scope | **404** | a 403 confirms the ticket exists |
| the ticket id was never issued | **404** | genuinely absent |

The last two are not "both mapped to 404" — they are the *same* `TicketNotFoundException`, thrown from the same line in `ScopedTickets.require`, so the responses cannot drift apart. It carries no reason code: a value that was never recorded cannot leak into a log, a `detail` string or a debug header.

**In a detail handler, call `require`, not `byId`:**

```java
Ticket t = tickets.require(caller, id);   // no Optional, no status code, nothing to get wrong
```

`byId` still exists for callers that genuinely want the `Optional`. `require` is for anything that answers an HTTP request — the status code is already decided, so it cannot be decided wrongly.

**Two things that put the 403 back.** Neither exists today; both are one careless line away.
- Throwing `AccessDeniedException` anywhere in a scoped path — `ProblemErrorResponses` turns it into a 403.
- `@PostAuthorize("returnObject.assignedTo == …")` — reads the row, then answers **403**. Never use it on a ticket route; `require` is the replacement.

`CallerIdentity` (in this package, not `scope/`) is the one reading of "who is calling", resolved from either principal — `JwtAuthenticationToken` on the real chain, `DevPrincipal` under `dev-noauth`. It is the shared home the two copied `CurrentUser` classes in `feature/chat` and `feature/notifications` were waiting for.

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
| `CallerIdentityTest` | Both principal shapes read as one caller; every unreadable shape reads as none. No Docker. |
| `TicketScopeIT` | A-034 — which rows each role actually gets back, against real MySQL. |
| `ScopedNotFoundIT` | A-035 — out-of-scope and never-existed are byte-identical, from a real login. |
| `arch/ScopeGuardRulesTest` | A-037 — the guard cannot be walked around: no `TicketRepository` outside `scope/`, no `@PostAuthorize` anywhere. No Docker. |

The rest of the suite is the allow-path net: everything runs as an authenticated principal and would start answering 403 if an annotation over-restricted. Keeping it green is part of the check, not incidental to it.
