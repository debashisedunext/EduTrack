# Authentication — Stream A · A-030 / A-031

S-01 Login, S-02 Forgot & Reset, S-03 Change Password, and the S-04 two-factor
challenge. The backend for all of it landed in A-020…A-029; this is the screens,
the session's client-side lifecycle, and the route guard.

| File | What it is |
|---|---|
| `authStore.ts` | Who is signed in. The access token is **not** in here — see below. |
| `AuthProvider.tsx` | Startup restore, token renewal, A-025's idle and absolute timeouts. |
| `useSignOut.ts` | Ends both halves of a session — local state *and* the server's cookie. |
| `RequireAuth.tsx` | Route guard. Convenience, not security. |
| `AuthCard.tsx` | S-01's centred card on the indigo gradient, shared by all four screens. |
| `AuthField.tsx` | Labelled field, alert and notice. |
| `LoginPage.tsx` | S-01, plus the S-04 challenge step. |
| `ForgotPasswordPage.tsx` / `ResetPasswordPage.tsx` | S-02, both halves. |
| `ChangePasswordPage.tsx` | S-03, forced and voluntary. |
| `PasswordStrengthMeter.tsx` / `passwordPolicy.ts` | §10.3's rules and the meter S-02 asks for. |
| `problemTypes.ts` | The `type` URIs the auth endpoints emit. |

## Four decisions worth knowing before editing anything here

**The access token lives in `api/http.ts`'s closure, not in the store.** Nothing
renders it, so nothing should be able to subscribe to it — and a token in store
state is one `persist` middleware away from sitting in `localStorage`. A reload
is handled instead by trading the HttpOnly refresh cookie for a new token, which
is why `status` starts at `'unknown'` rather than `'anonymous'`.

**The login failure message never names a field.** A-020 makes unknown user,
wrong password and deactivated account byte-identical on the wire, and
`AuthLoginIT` asserts that byte for byte. One screen rendering three different
sentences undoes all of it. `LoginPage.test.tsx` pins the exact string.
`account-locked` is the deliberate exception: A-021 reports it only *after*
correct credentials, so it can only be seen by someone who already knows the
password.

**Recovery codes are sent as `recoveryCode`, never `totpCode`.** `totpCode`
carries `^\d{6}$` in the contract, so a recovery code posted in it is refused by
Bean Validation before the service is reached. That was one of the four bugs
A-029's tests caught; the same mistake is possible from this end.

**A renewal is not activity.** A background refresh every ~14 minutes would
silently defeat A-025's 30-minute idle timeout, so `AuthProvider` checks for real
interaction before renewing and otherwise lets the token lapse.

**Logout is issued before the local state is cleared, and the order matters.**
`POST /auth/logout` is authenticated — `signOut()` clears the token out of
`api/http.ts`, so calling it first makes the request anonymous, the server
answers `401 invalid-access-token`, and the refresh family survives the logout.
The mock accepts an unauthenticated logout, so no test caught this; the running
backend did. `useSignOut.test.tsx` now asserts on the `Authorization` header.

## Verified against the real backend

Not only mocks: MySQL and Redis via `docker-compose`, the packaged jar on
`local,fixtures`, and the frontend through the Vite proxy with
`VITE_USE_MOCKS=false`. Login, refresh, logout-then-refresh, the five-attempt
lockout, forgot-password's unconditional 202 and an invalid reset token all match
what these screens assume, and the `HttpOnly; Path=/api/v1/auth` cookie survives
the proxy. Two things the mocks had wrong, now corrected here:
`invalid-reset-token` returns **410**, not 400, and the real login response
carries **no `landingRoute`** — see A-031.

### Signing in locally

Use B-007's fixture users. `ReferenceDataFixture` seeds 18 of them across all six
roles sharing one password, and that is the documented way in — no hand-made user
needed:

```
username: priya.nair (or any of the 18)   password: Fixture#B007-2026
```

They are created with `must_change_password = 1`, so the first screen after
signing in is S-03's forced change — which is a convenient end-to-end check that
the gate and the redirect both work.

Getting there from nothing, in order — the middle step is the one that is easy to
miss, because it fails with `Access denied for user 'edutrack_app'` rather than
anything mentioning grants:

```bash
docker compose up -d mysql redis
cd backend && ./mvnw -pl api spring-boot:run -Dspring-boot.run.profiles=local   # applies migrations, then fails
make grants                                                                     # A-010, table-level, after the schema exists
cd backend && ./mvnw -pl api spring-boot:run -Dspring-boot.run.profiles=local,fixtures
```

`docker/mysql-init/01-users.sql` explains why `edutrack_app` starts with no
privileges at all: MySQL privileges are cumulative, so the only way to hold the
append-only tables to `INSERT, SELECT` is to grant nothing at database level and
grant table by table once the tables exist. It fails closed on purpose.

## Two things recorded rather than done quietly

**"Remember my username", not "Remember me".** S-01 lists a *Remember me* field.
A-025 fixes the session at 30 minutes idle and 12 hours absolute server-side, and
A-024 refuses to slide the refresh window, so the frontend cannot keep anyone
signed in longer no matter what the box says. A checkbox promising that would be
read as broken when the user is signed out next morning. What it can honestly do
is save the typing, so the label says exactly that. This is a reading of S-01's
field, not a dropped requirement — raise it if the product wants the other thing,
which would be a change to A-023's cookie lifetime, not to this screen.

**`AuthField` duplicates `features/tickets/create/FormField`.** Same ARIA
contract, ~40 lines, and importing Divyansh's would compile this feature against
a file in Stream C's directory that he cannot know I depend on. The right fix is
to promote one into `components/ui`, which is his to own — a request to make with
two real callers behind it, not something to do unilaterally in his path.

## What is not built yet, and why

**Two-factor enrolment** — `/me/2fa/setup`, `confirm`, `disable`. The endpoints
and mocks exist (A-029) and the challenge half of S-04 ships here, but enrolment
belongs on the profile screen, which does not exist yet. The avatar menu's
Profile item is still inert. Tracked as the remainder of S-04.

**The guard cannot be proven end to end until A-032.** There is no Spring
Security filter chain yet — the app still runs `ScaffoldSecurityConfig`'s
permit-all. Login, refresh, reset, change-password and the 2FA challenge all
exercise real endpoints; "a protected route rejects a revoked token" has nothing
to reject it yet. That assertion belongs with A-032 rather than being faked
against a mock here.
