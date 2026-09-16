# CI Fix Guide — turning `develop` green again

**Prepared:** 2026-09-15 (revised after full re-verification) · **Companion to:** [`CI-FAILURE-REPORT.md`](CI-FAILURE-REPORT.md) (the diagnosis) · **This file:** the remediation, end to end.

Read the report first for the evidence. This guide answers one question: *what exactly do we change, in which order, and how do we prove each change before it reaches `develop`?* Every diff was written against `develop` @ `52a9e0ec` by reading the source; line numbers are as of that commit.

---

## 0. The one thing to understand first

**You cannot fix a failed CI run.** A run is an immutable record of what one commit did on one day. The 578 historical failures and the 180-run red streak on `develop` stay in the Actions tab forever — correctly.

"Fixing all the failed CI" means exactly one thing: **make `develop` HEAD pass once.** After that, every push is verified against a green baseline again, and the nightly run (`cron: '17 2 * * *'`, 02:17 UTC — the one producing a fresh red run every morning) goes green by itself the next day. Re-running an old run on the same commit reproduces the same failure. Don't.

**And understand that the failures are layered.** Each CI job stops at its first error. Today's visible errors are the *top* of a pile that has been growing since 20 Aug. Fixing the visible layer reveals the next one — this guide lists every layer we could uncover locally, but budget for one more round after the first push.

---

## 1. Anatomy of the pipeline

`.github/workflows/ci.yml` — eight jobs. Run #1127 on `52a9e0ec`:

| Job | Result | Died at | Passed before dying | Behind it (known) |
|---|---|---|---|---|
| What changed | ✅ | | | |
| Migration guard | ✅ | | | |
| Backend — build & test | ❌ | `mvn -B verify` — **javac** | MySQL/Redis containers, JDK 25 | `SeedManifestTest` (domain) → then `api` tests (§8) |
| Frontend — lint, test & build | ❌ | `npm run test -- --run` | **`npm run lint` passed** | `npm run build` never ran |
| OpenAPI contract | ❌ | Enforce the API conventions | **Redocly validation passed** | regenerate client → staleness → type-check never ran |
| Security — dependency & image scan | ❌ | Build the jar (same javac) | JDK, Node | Trivy (unknown) → **npm audit (will fail)** |
| Security — dependency review | ⏭️ | pull_request-only | | |
| Package — single deployable jar | ⏭️ | push-only, builds from source | | jar smoke test never ran since #450 |

---

## 2. Prerequisites and ground rules

### Local toolchain

```powershell
# JDK 25 is at C:\Program Files\Java\jdk-25.0.4 but NOT on PATH (java -version says 1.8).
# Set JAVA_HOME per command, and run Maven from a terminal — the IDE's Java builder
# wipes domain/target/classes mid-build and swallows Maven's output.
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4'
cd backend;  .\mvnw -v                      # must print Java 25
cd frontend; node -v                        # Node 22, what CI uses
python -c "import yaml"                     # pyyaml, for contracts/check-conventions.py
```

**Windows gotcha (real):** `contracts/check-conventions.py` prints `✗` and crashes on a cp1252 console. Run it as `$env:PYTHONIOENCODING='utf-8'; python contracts/check-conventions.py`. CI (Linux) never sees this.

**Docker Desktop is not running** on this machine, so `mvn verify` (Testcontainers ITs) cannot run locally. Unit tests via `test` can. Leave the ITs to CI.

### Ownership

| Fix | Files | Owner | Introduced by |
|---|---|---|---|
| §3 backend compile | `backend/api/src/test/…/onboarding/**` | **Stream B** | #450 |
| §4 seed manifest | `backend/domain/…/db/migration/SEED-MANIFEST.md` | **Stream A** (manifest owner; A‑058 precedent) — or each stream for its own row | #443 (C), #445 (A), #450 (B) |
| §5 Sidebar test | `frontend/src/app/Sidebar.test.tsx` | **Stream B** | #450 |
| §6 portal mocks | `frontend/src/mocks/handlers/**` | **Stream D** (D‑004) | #438 (Stream C) |
| §7 contract | `contracts/openapi.yaml`, `contracts/check-conventions.py` | **Stream A** (`listObJourneyTemplates`), **Stream B** (project 403s), **Stream D** (the checker file) | #442, #450 |
| §9 npm audit | `frontend/package.json`, `package-lock.json`, `orval.config.ts` | **Stream A** (owns the gate, A‑074) with frontend sign-off | drift |

Per `CLAUDE.md`, touching another stream's path needs that owner's sign-off — say so in the PR.

### Branches

```
git checkout develop && git pull
git checkout -b fix/masters/ci-onboarding-tests        # B  — §3, §5, §7 (project 403 side)
git checkout -b fix/platform/seed-manifest-and-catalogue # A  — §4, §7 (catalogue side), §9
git checkout -b fix/engines/portal-onboarding-mocks    # D  — §6, §7 (allowlist entries)
```

Branch from `develop`, never `main`. Conventional commits. **No task ID in the subject** unless that commit finishes the task.

---

## 3. Fix 1 — Backend won't compile (Stream B)

**Cause:** PR #450 changed two service signatures and left the tests on the old ones.

### 3.1 `ObJourneyStepLifecycleService.answerItem` — 6 call sites

Old: `answerItem(long itemId, long callerId, boolean isDone)`
New ([service:909](../backend/api/src/main/java/com/edunext/edutrack/api/feature/onboarding/instances/ObJourneyStepLifecycleService.java#L909)): `answerItem(long itemId, long callerId, Boolean answer, String remark)`

**The semantics changed, not just the arity.** `answer` is three-valued:

| `answer` | Meaning | `remark` |
|---|---|---|
| `TRUE` | answered Yes | optional |
| `FALSE` | answered **No** | **required** — else `StepItemRemarkRequiredException` |
| `null` | **untick → unanswered**; clears remark, answeredBy, answeredAt | ignored |

The old `false` ("untick") is now `null`. A mechanical "append `, null`" compiles and then **fails the untick test at runtime**.

Edit [`ObJourneyStepLifecycleServiceTest.java`](../backend/api/src/test/java/com/edunext/edutrack/api/feature/onboarding/instances/ObJourneyStepLifecycleServiceTest.java):

| Line | Test | Change to | Why |
|---|---|---|---|
| 1263 | `answeringAnItemRecordsWhoAnsweredItAndWhen` | `service.answerItem(1L, OWNER, true, null)` | Yes needs no remark |
| 1283 | `untickingAnItemReturnsItToUnansweredAndClearsTheRemark` | `service.answerItem(1L, OWNER, null, null)` | **untick is `null`**; `false` would throw remark-required |
| 1301 | `aStrangerCannotAnswerAnItem` | `service.answerItem(1L, STRANGER, true, null)` | ownership is checked first |
| 1312 | `theBackupOwnerCanAnswerAnItem` | `service.answerItem(1L, BACKUP_OWNER, true, null)` | |
| 1326 | `aClosedStepsChecklistCannotBeEdited` | `service.answerItem(1L, OWNER, false, "Not received after all")` | a remark, so the test proves the *terminal* rule rather than the order of two checks |
| 1334 | `answeringAnItemThatDoesNotExistIsNotFound` | `service.answerItem(404L, OWNER, true, null)` | |

Recommended while there (PR #450 shipped the new rule with zero coverage):

```java
/** Answering No without saying why is refused before the DB check constraint can turn it into a 500. */
@Test
void answeringNoWithoutARemarkIsRefused() {
    stepRows.get(STEP).setStatus(ObJourneyStepStatus.IN_PROGRESS);
    when(stepItems.findById(1L)).thenReturn(Optional.of(
            stepItem(1L, 100L, null, "Signed agreement received")));

    assertThatThrownBy(() -> service.answerItem(1L, OWNER, false, "  "))
            .isInstanceOf(StepItemRemarkRequiredException.class);
}
```

### 3.2 `ObJourneyTemplateService.updateStep` — 1 call site

Nine parameters now, the last two primitive `boolean` ([service:493](../backend/api/src/main/java/com/edunext/edutrack/api/feature/onboarding/journeys/ObJourneyTemplateService.java#L493)). Every other call in the test already uses the 9-arg form (lines 696, 711, 728, 763); [line 1061](../backend/api/src/test/java/com/edunext/edutrack/api/feature/onboarding/journeys/ObJourneyTemplateServiceTest.java#L1061) is the straggler:

```java
// before
service.updateStep(kickoff.getId(), "Renamed", null, 3, null, null, null, null))
// after
service.updateStep(kickoff.getId(), "Renamed", null, 3, null, null, null, false, false))
```

### 3.3 Verify

```powershell
cd backend
.\mvnw -pl api -Dtest=ObJourneyStepLifecycleServiceTest,ObJourneyTemplateServiceTest test
.\mvnw -pl api test-compile        # compile errors are file-wide; prove the whole module compiles
```

**Turns green:** the *first layer* of Backend and Security scan. §4 is next.

---

## 4. Fix 2 — Four migrations missing from `SEED-MANIFEST.md` (Stream A, or one row per stream)

**Symptom:** hidden behind §3. Reproduced at `a2636cce` and re-checked at HEAD: `domain`'s `SeedManifestTest.everyMigrationIsRegistered` and `theRegisterIsInTheOrderFlywayWillRunThem` fail, Maven skips `api`, and the backend job is red before a single `api` test runs.

**Cause:** four migrations were added without a row in the load-order table (between `<!-- load-order:begin -->` and `<!-- load-order:end -->`, from line 27 of [`SEED-MANIFEST.md`](../backend/domain/src/main/resources/db/migration/SEED-MANIFEST.md)). The register has 106 rows; disk has 110 files. No phantoms, no duplicates, order is otherwise correct.

**Fix:** insert four rows in version order (the test matches on filename; the `#` column is for humans — renumber the rows below or leave a gap, either passes). Fill the *Loads* column from each file's header comment:

```markdown
| 100 | `V20260910_0030__ob_many_active_services_per_product.sql` | C | — | schema | `ob_journey_templates` — several services of one product may be active at once (drops the one-active-per-product uniqueness that `V20260909_1900` left in place) |
| 101 | `V20260910_0930__ob_journeys_pinned_sequence.sql` | A | — | schema | `ob_journeys.sequence` — a journey's position is pinned at instantiation rather than read live from the catalogue |
| 102 | `V20260910_1045__ob_scope_dashboard_summary.sql` | A | B-121 follow-up | schema | `ob_scope_dashboard_summary` — the per-scope card board, so the two roles the unscoped summary could not answer are read from a table, not `COUNT(*)` |
```
…after existing row 99 (`V20260909_1900`), and

```markdown
| 107 | `V20260911_1800__ob_projects.sql` | B | — | schema | `ob_projects` — the engagement becomes a row: name, start date, sales person, implementor, status, promoted from the (client, product) pair |
```
…after existing `V20260911_1630__ob_template_stage_groups.sql` and before `V20260914_1830`.

**Verify:**

```powershell
cd backend
.\mvnw -pl domain -Dtest=SeedManifestTest test          # 5 tests, 0 failures
```

**Turns green:** the *second layer* of Backend. What the `api` module does after that is §8.

---

## 5. Fix 3 — Stale `Sidebar.test.tsx` (Stream B)

**Cause:** PR #450 removed the "New client" nav row ([Sidebar.tsx:148-152](../frontend/src/app/Sidebar.tsx#L148-L152) explains why: adding a client is a dialog on the list now) and the `/onboarding/clients/new` route ([App.tsx:729](../frontend/src/App.tsx#L729)). The test still expects both.

Onboarding nav today, in order: `Dashboard · Projects · Clients · Reports` then Administration `Module Service · Products · Prerequisites master · Implementation Stage · Roles & module access · TAT & escalation · Notification templates`.

### 5.1 Line 119

```ts
for (const label of ['Dashboard', 'Projects', 'Clients', 'Reports', 'TAT & escalation']) {
```

### 5.2 Lines ~171–186 — the "wizard under the list" test

The property — *a nested route lights exactly one row* — is still worth holding; the pair that exists now is `/onboarding/projects` + `/onboarding/projects/new` ([App.tsx:722-723](../frontend/src/App.tsx#L722-L723)), with `isActive: (p) => p.startsWith('/onboarding/projects')` ([Sidebar.tsx:146](../frontend/src/app/Sidebar.tsx#L146)). Replace the test, docblock included:

```tsx
/**
 * `NavLink` matches on prefix, so a nested route under a list lights the list
 * row and nothing else. The pair used to be `/onboarding/clients` +
 * `/onboarding/clients/new`; that wizard is gone (adding a client is a dialog
 * on the list now), so the New project form is the nested route the rail has
 * to get right.
 */
it('marks exactly one row current when the New project form sits under the list', () => {
  renderSidebarAs(OB_ADMIN, '/onboarding/projects/new')

  expect(within(obNav()).getByRole('link', { name: 'Projects' })).toHaveAttribute('aria-current', 'page')
  expect(within(obNav()).getByRole('link', { name: 'Clients' })).not.toHaveAttribute('aria-current')
})
```

`keeps Clients current on a client detail page` (`/onboarding/clients/42`) is unaffected.

**Verify:** `cd frontend; npm run test -- --run src/app/Sidebar.test.tsx`

---

## 6. Fix 4 — 15 portal endpoints with no MSW handler (Stream D, D‑004)

**Cause:** PR #438 (Stream C, C‑121) added the client-portal onboarding surface to the contract; nothing mocks it. `coverage.test.ts` diffs the spec against MSW's live registry — the guard that exists so a new endpoint can't ship unmocked. It is doing its job.

### 6.1 What is missing

| Method | Path (under `/api/v1`) | operationId | 2xx → schema | Request body |
|---|---|---|---|---|
| POST | `/portal/auth/login` | `portalLogin` | 200 `PortalLoginResponse` | `PortalLoginRequest` |
| GET | `/portal/auth/credential/{token}` | `describePortalCredentialLink` | 200 `PortalCredentialLinkResponse` | — |
| POST | `/portal/auth/credential/{token}` | `redeemPortalCredentialLink` | **204** | `PortalCredentialRedeemRequest` |
| GET | `/portal/onboarding/home` | `getPortalOnboardingHome` | 200 `PortalOnboardingHomeResponse` | — |
| POST | `/portal/onboarding/steps/{stepId}/escalate` | `raisePortalEscalation` | 200 / **201** `PortalClientEscalationResponse` | `PortalEscalationRaiseRequest` |
| GET | `/portal/onboarding/signoffs` | `listPortalSignoffs` | 200 `PortalSignoffListResponse` | — |
| GET | `/portal/onboarding/signoffs/{signoffId}` | `getPortalSignoff` | 200 `PortalSignoffReviewResponse` | — |
| POST | `/portal/onboarding/signoffs/{signoffId}/accept` | `acceptPortalSignoff` | 200 `PortalSignoffDecisionResponse` | `PortalSignoffAcceptRequest` |
| POST | `/portal/onboarding/signoffs/{signoffId}/object` | `objectPortalSignoff` | 200 `PortalSignoffDecisionResponse` | `PortalSignoffObjectRequest` |
| POST | `/portal/onboarding/signoffs/{signoffId}/csat` | `submitPortalCsat` | **204** | `PortalCsatRequest` |
| GET | `/portal/onboarding/prereq-tasks/{prereqTaskId}` | `getPortalPrereqTask` | 200 `ObClientPrereqTaskDetailResponse` | — |
| POST | `/portal/onboarding/prereq-tasks/{prereqTaskId}/submit` | `submitPortalPrereqTask` | 200 `ObClientPrereqTaskResponse` | `PortalPrereqSubmitRequest` |
| GET | `/portal/onboarding/prereq-tasks/{prereqTaskId}/comments` | `listPortalPrereqComments` | 200 `PortalPrereqCommentListResponse` | — |
| POST | `/portal/onboarding/prereq-tasks/{prereqTaskId}/comments` | `addPortalPrereqComment` | **201** `PortalPrereqCommentResponse` | `ObPrereqCommentCreateRequest` |
| POST | `/portal/onboarding/prereq-tasks/{prereqTaskId}/attachments` | `uploadPortalPrereqAttachment` | **201** `PortalAttachmentResponse` | `multipart/form-data` |

### 6.2 Where

New `frontend/src/mocks/handlers/portalOnboarding.ts` exporting `portalOnboardingHandlers`, spread into [`handlers/index.ts`](../frontend/src/mocks/handlers/index.ts) **right after `...portalHandlers`**, before `...fileHandlers` and the `501` catch-all (which must stay last). Keep it out of `portal.ts`, whose docblock is explicit that it is "four reads and nothing else".

### 6.3 Reuse — don't build a second fixture set

- **Principal:** `portalClientId()` from `portal.ts` (the client whose active contact has `portalAccess`). Everything served must be that client's; another client's `stepId`/`signoffId`/`prereqTaskId` → `notFound()`, never 403.
- **Sign-offs:** `db.obSignoffs`, `db.obSignoffSessions` (`token`, `used`), and `acceptSignoffRow()` / `signoffDto()` / `signoffDetailDto()` in `onboardingAdmin.ts` already implement the public accept path — **export and call them**, one implementation of the transition.
- **Prereq tasks:** `onboardingPrereqs.ts` handles the staff side of the same rows (`/onboarding/prereq-tasks/:id`, `/submit`, `/comments`); reuse its finders and serialisers.
- **Helpers** (`util.ts`): `url`, `ok(data, meta?, init?)`, `noContent()`, `notFound()`, `problem(status, type, title, extra?)`, `validationFailed()`, `paginate(items, requestUrl)` → `{ page, meta }`.

### 6.4 Skeleton

```ts
import { http } from 'msw';
import { getDb } from '../db';
import { noContent, notFound, ok, paginate, problem, url } from './util';
import { portalClientId } from './portal';

export const portalOnboardingHandlers = [
  http.post(url('/portal/auth/login'), async ({ request }) => {
    const body = (await request.json()) as { username: string; password: string };
    return ok({ /* PortalLoginResponse */ });
  }),
  http.get(url('/portal/auth/credential/:token'), ({ params }) => {
    const link = getDb().obSignoffSessions.find((s) => s.token === params.token && !s.used);
    return link ? ok({ /* PortalCredentialLinkResponse */ }) : notFound('Credential link');
  }),
  http.post(url('/portal/auth/credential/:token'), async () => noContent()),            // 204
  http.get(url('/portal/onboarding/home'), () => {
    const clientId = portalClientId();
    if (clientId == null) return problem(401, 'unauthenticated', 'No portal session');
    return ok({ prerequisites: /* ObClientPrereqs */ null, journeys: [] /* PortalJourneyStrip[] */ });
  }),
  http.get(url('/portal/onboarding/signoffs'), ({ request }) => {
    const mine = getDb().obSignoffs.filter((s) => s.obClientId === portalClientId());
    const { page, meta } = paginate(mine, new URL(request.url));
    return ok(page, meta);
  }),
  http.post(url('/portal/onboarding/signoffs/:signoffId/csat'), async () => noContent()),  // 204
  http.post(url('/portal/onboarding/prereq-tasks/:prereqTaskId/comments'), async ({ params, request }) => {
    // push a row, then 201:
    return ok({ /* PortalPrereqCommentResponse */ }, undefined, { status: 201 });
  }),
  http.post(url('/portal/onboarding/prereq-tasks/:prereqTaskId/attachments'), async ({ request }) => {
    const form = await request.formData();      // multipart — not request.json()
    // store like fileHandlers does, then 201
  }),
  // … remaining routes from the table
];
```

- The coverage test normalises path params (`{token}` ≡ `:token`); literal segments and **arity** must match.
- A handler returning `problem(501, …)` counts as covered and defeats the point — implement against fixtures.
- Add `portalOnboarding.test.ts` beside it, in the style of `portal.test.ts`.

**Verify:** `npm run test -- --run src/mocks` → coverage lists `[]` missing.

---

## 7. Fix 5 — Four OpenAPI convention violations (Stream A + B, in Stream D's file)

Reproduced on this tree (`python contracts/check-conventions.py`):

```
  ✗ listObJourneyTemplates: collection without cursor pagination       ← PR #442, Stream A
  ✗ listObJourneyTemplates: paginated collection without meta          ← PR #442, Stream A
  ✗ updateObProject: declares 403 — … add it to ROWLESS_403 with a reason   ← PR #450, Stream B
  ✗ deleteObProject: declares 403 — …                                       ← PR #450, Stream B
```

### 7.1 `listObJourneyTemplates` — exempt it, with a reason (recommended)

The rule ([`check-conventions.py:298-304`](../contracts/check-conventions.py#L298-L304)): a `GET` whose `200` body has `data: array` must take `Cursor` and return `meta`, unless the path is in `NO_PAGINATION` with a reason.

[`ObJourneyTemplateController.list`](../backend/api/src/main/java/com/edunext/edutrack/api/feature/onboarding/journeys/ObJourneyTemplateController.java#L93) returns *every version of every service* deliberately and computes chain-wide journey counts, dependency sets and stage groups over the whole result; the OB‑07 catalogue and the New Project service picker draw the entire set. It is bounded master data like `/masters/workflow-templates`, which is already exempt. `CONVENTIONS.md` itself warns that a `Cursor` in the spec the server ignores is a "spec-only patch". So add to `NO_PAGINATION`:

```python
"/onboarding/journey-templates": "OB-07 — the Module Service catalogue: every version of every service for one product, or all products. Bounded the way /masters/workflow-templates is (a handful of services per product, and a product's chain history is what the catalogue exists to show). The controller computes chain-wide journey counts, dependency sets and stage groups over the full result, and both the OB-07 cards and the New Project service picker draw the entire set on one read — a page could not carry the chain it is grouping. Not enforced server-side; if retired versions ever pile up, the fix is a real keyset cursor plus a 'current versions only' default, not a wider exemption",
```

(Real pagination — spec + keyset paging in the service + regenerated client + two frontend consumers — is a feature, not a CI fix. File it if the list is expected to grow beyond a screen.)

### 7.2 `updateObProject` / `deleteObProject` — `/onboarding/projects/{obProjectId}`

The spec's `403` reads *"An OB Viewer or Step Owner on a project they can read"* — word for word the argument already accepted for `/onboarding/clients/{obClientId}` (`ROWLESS_403`, entry B‑103): the caller already rendered the row via the scoped read, so a 404 would deny something on their own screen; what is refused is a capability decided from the module role before the row is consulted.

**First confirm the backend agrees** — find the exception the project write service throws for a Viewer/Step Owner on PATCH/DELETE. If it maps to 403 (as the client service does), add the allowlist entry below. If it 404s, delete the `'403':` block from both operations instead — the spec must describe what runs.

```python
"/onboarding/projects/{obProjectId}": "PR #450 — the project header inherits the client's entry (B-103) one level down. The PATCH names its writers and the DELETE is Manager/Admin only; the two roles excluded, OB Viewer and OB Step Owner, can *reach* the route because listObProjects and getObProject already showed them the row, so a 404 here would deny a project on their own grid. The capability is decided from the module role before anything about this project is read; out-of-scope projects are still 404 from the scoped read that runs first. The DELETE's other refusal is NOT here: whether anything points at the project (nine FK tables, two hash-chained) is 409, because that depends on the rows and not on the caller",
```

Adjust the role list to what the service actually enforces — the reason string is documentation and must be true.

### 7.3 Verify — including the three steps CI has not run since 9 Sep

```powershell
$env:PYTHONIOENCODING='utf-8'; python contracts/check-conventions.py   # 0 violations
cd frontend
npm run api:generate                                                   # CI's "Regenerate client"
git status --porcelain src/api/generated                               # MUST be empty — else commit the regenerated files
npx tsc --noEmit -p tsconfig.app.json                                  # "Type-check the regenerated client"
```

Never hand-edit `frontend/src/api/generated/`.

---

## 8. Layer 3 of the backend — the `api` module's own tests

With §3 and §4 done, Maven reaches `api` for the first time since 4 Sep. To know what it will find *before* pushing, the `api` unit tests were run locally at `a2636cce` (the last compiling merge before #450) with `-Dmaven.test.failure.ignore=true` so every failure is listed rather than the first.

**Result: `api` — 5,815 unit tests, 9 failures, 0 errors** (~10 min on this machine). None is a logic bug; every one is a *register* the code outgrew — the same shape as the seed manifest, one layer down:

| Test | What it guards | Failure (at `a2636cce`) | Owner |
|---|---|---|---|
| `arch.LayeringRulesTest.noControllerReachesARepositoryDirectly` | controllers go through a service, never a repository | **33 violations** | whoever wrote each controller — onboarding-heavy, so B/C/A |
| `arch.ObModuleSeparationTest.onboardingDoesNotDependOnTicketing` | `feature.onboarding..`/`feature.portal..` must not import `feature.tickets..`/`feature.transitions..` | **7 violations** | C (portal) / B |
| `arch.PaginationRulesTest.paginationMetaIsDeclaredOnlyOnce` | one `PageMeta`, not a fifth `nextCursor` record | 1 violation | A |
| `contract.ContractConformanceTest.responseBodiesCarryTheDeclaredPropertyNames` | live GET responses carry the property names the spec declares | **3 GET responses** drift from their schema | endpoint owners |
| `contract.ObWireConformanceTest.theKnownGapListIsARatchet` | a DTO omitting a contract field must be on the recorded known-gap list | 1 new unrecorded gap | B/C |
| `fixtures.onboarding.OnboardingFixtureScheduleTest.oneActiveVersionPerProduct` | fixture assumption: one active template per product | **obsolete since #442** made several active services per product legal — the *test* needs updating, not the fixture | A |
| `feature.portal.ClientAccountAdminServiceTest.placeholderPasswordIsHashed` | placeholder passwords are stored hashed | fails | C (C‑121) |
| `security.permission.ObPermissionMatrixTest.everyRouteIsCovered` | every onboarding route has an `ObPermissionMatrix` entry (or is in `NOT_YET_ENFORCED`) | routes missing | route owners |
| `security.permission.PermissionMatrixTest.everyRoutedEndpointHasAMatrixEntry` | every routed endpoint has a permission-matrix entry for all six roles | routes missing | route owners — this is the `CLAUDE.md` Definition-of-Done item "permission-matrix entries for all six roles" |

Each failure message names the offending classes/routes; the full lists are in `backend/api/target/surefire-reports/*.txt` after running `.\mvnw -DskipFrontend -pl api -am test -Dmaven.test.failure.ignore=true`. This is the state at `a2636cce`; **#450 may have added to it** (it added routes and DTOs) — re-run at HEAD once §3 compiles.

These nine are the real second round, and they are the most valuable failures in this document: the architecture tests, the wire-conformance ratchet and the permission matrix are exactly the guarantees `CLAUDE.md` says erode first and are hardest to restore. They have been silently off since 4 Sep.

The integration tests (`*IT`, Testcontainers) still only run in CI — a third round is possible.

---

## 9. Fix 6 — `npm audit --audit-level=high` in the Security job (Stream A + frontend)

**Symptom:** masked today by the compile error. It was the failing step on every `develop` run from 25 Aug to 9 Sep and nothing has changed underneath.

Reproduced locally: 8 advisories, three at `high` or above:

| Package | Sev. | How it gets in | Fix | Breaking? |
|---|---|---|---|---|
| `fast-uri` | high | prod, transitive | `npm audit fix` | no |
| `js-yaml` 4.0.0–4.3.1 | high | `orval` ≤ 8.30 (devDependency `^7.21.0`) | `orval@8.33.0` | **yes — major** |
| `orval` ≤ 8.30 | critical | devDependency | same | **yes** |

Everything else is *moderate* (`react-router` 6.x, `vitest`) and does not trip `--audit-level=high`.

**Steps:**

1. `npm audit fix` (no `--force`) — clears `fast-uri` and the vitest/mocker moderates. Commit `package-lock.json`.
2. Upgrade orval: `npm i -D orval@8.33.0`, read orval's 8.x migration notes against `orval.config.ts`, run `npm run api:generate`, review the diff in `src/api/generated/` (a major codegen bump can change generated signatures), `npx tsc --noEmit -p tsconfig.app.json`, then commit lockfile + config + regenerated client together.
3. **Decision for Stream A** (gate owner): if the orval bump is judged too risky for this batch, the alternative is to scope the gate — `npm audit --audit-level=high --omit=dev` — which is a change to `ci.yml` and to what the check promises. Say which in the PR; don't do it silently.

**Verify:** `cd frontend; npm audit --audit-level=high` → exit 0.

**Trivy (jar + lockfile, HIGH/CRITICAL, unfixed ignored):** cannot run here without Docker; it passed 25 Aug–9 Sep and has been masked since. If it fails on the first green-jar run, the step's `::group::` output names the CVE and the artifact.

---

## 10. Landing it — the only path into `develop`

Three PRs, one batch. They are independent of each other; `develop` only goes green when **all** have landed, which is exactly what a batch is for.

| PR | Branch | Contains | Stream |
|---|---|---|---|
| A | `fix/masters/ci-onboarding-tests` | §3 · §5 · §7.2 (and its `ROWLESS_403` row, with D's sign-off) | B |
| B | `fix/platform/seed-manifest-and-catalogue` | §4 (all four rows, with C/B sign-off on theirs) · §7.1 (with D's sign-off) · §9 | A |
| C | `fix/engines/portal-onboarding-mocks` | §6 | D |

Commit subjects (no task IDs — none of these *finish* a task):

```
fix(masters): update onboarding tests to answerItem's tri-state answer and updateStep's clear flags
fix(masters): drop the New client nav assertions removed with the wizard
fix(platform): register the four September onboarding migrations in SEED-MANIFEST.md
fix(platform): exempt the Module Service catalogue from paging and clear npm audit
fix(engines): mock the fifteen portal onboarding operations D-004 flagged
```

For each PR: push → **draft** → local checks green (§3.3, §4, §5, §6, §7.3, §9) → **Ready for review**. Do not press Merge. Ask for an integration batch (`/integrate`): all three into one integration branch, CI runs **once** on the combined result, green ones merge together, `develop` moves once. If one PR breaks the batch it is dropped and the others still land — `develop` then stays red until it returns, which is fine and visible.

Given that CI has not run the `api` tests, `npm run build`, the client-staleness check or the jar smoke test on this code, **plan on a second batch** for whatever the first green-through-layer-1 run uncovers.

---

## 11. "But there are 578 failed builds"

Nothing to do about them. How to read the number:

| Bucket | Runs | What they are |
|---|---|---|
| `develop`, since the last green (#659 → #1127) | **180** | The same broken HEAD re-measured on every push and every 02:17 nightly. One fix clears the *cause*; the runs stay red because they were. |
| Other branches in the same window | 289 | 137 green, 110 red, the rest skipped/cancelled — PR branches doing what PR branches do. |
| `develop`, cancelled, before 20 Aug | ~61 | The old `cancel-in-progress` setting killing each merge's run with the next (documented and fixed in the `ci.yml` header, 20 Aug). Not code. |
| Everything older | the rest | Normal red PRs that were fixed and merged. CI working. |

The metric is **"is HEAD green?"**, not "how many red runs exist". After the batch: one green push run, one green nightly, streak over.

---

## 12. Keeping it green

1. **Branch protection with the `all-checks-passed` aggregate — Stream A, issue #3.** All four offending PRs showed their failure on their own head commit before merge. A required check greys out the button; nobody has to remember anything.
2. Until then: **a red check on your own PR is a hard stop**, and so is merging onto a red `develop`. And note the trap this month proved: once `develop` is always red, "my PR is red too" stops meaning anything. Fix `develop` first, keep it green, and red becomes a signal again.
3. **Changed a signature? Grep the call sites** (`grep -rn "\.answerItem(" backend/`) before opening the PR. Both backend breaks were mechanical.
4. **Added a migration? Add its manifest row in the same commit.** `SeedManifestTest` runs in the `domain` module, before anything else — a missing row fails the whole backend job and hides every `api` test behind it. Three streams missed this in five days.
5. **Added an operation to `openapi.yaml`? Same PR: MSW handler, `check-conventions.py`, `api:generate`, commit the client.** The contract job checks all four in that order.
6. **Renamed or removed UI copy? Run that component's tests** — `Sidebar.test.tsx` was one `npm run test -- --run src/app` away from being caught.
7. `npm audit` drifts on its own. Run `npm audit --audit-level=high` when touching `package.json`, and treat a red Security job on a *push* as owned by Stream A, not by whoever pushed.

---

## Appendix A — command sheet

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4'; $env:PYTHONIOENCODING = 'utf-8'

# backend
cd backend
.\mvnw -pl api -Dtest=ObJourneyStepLifecycleServiceTest,ObJourneyTemplateServiceTest test
.\mvnw -pl domain -Dtest=SeedManifestTest test
.\mvnw -pl api test-compile
.\mvnw -DskipFrontend -pl api -am test          # every unit test, ~15 min; ITs need Docker

# frontend
cd frontend
npm run test -- --run src/app/Sidebar.test.tsx
npm run test -- --run src/mocks
npm run lint; npx tsc --noEmit -p tsconfig.app.json; npm run build
npx vitest --run --maxWorkers=2                  # whole suite on this 4-core machine
npm audit --audit-level=high

# contract
python contracts/check-conventions.py
cd frontend; npm run api:generate; git status --porcelain src/api/generated
```

## Appendix B — fix → proof → job matrix

| Fix | Local proof | Expected | CI job / layer it clears |
|---|---|---|---|
| §3 | targeted `-Dtest=…` + `test-compile` | BUILD SUCCESS | Backend L1, Security L1, unskips Package |
| §4 | `-pl domain -Dtest=SeedManifestTest` | 5/5 pass | Backend L2 |
| §5 | `--run src/app/Sidebar.test.tsx` | pass | Frontend (½) |
| §6 | `--run src/mocks` | `[]` missing | Frontend (½) |
| §7 | `check-conventions.py` + `api:generate` + `git status` | 0 violations, clean tree | OpenAPI contract |
| §9 | `npm audit --audit-level=high` | exit 0 | Security L2 |
| §8 / ITs / `npm run build` / Trivy / jar smoke | — | — | second batch |

## Appendix C — files this guide touches

- `backend/api/src/test/java/com/edunext/edutrack/api/feature/onboarding/instances/ObJourneyStepLifecycleServiceTest.java` — 1263, 1283, 1301, 1312, 1326, 1334 (+ optional new test)
- `backend/api/src/test/java/com/edunext/edutrack/api/feature/onboarding/journeys/ObJourneyTemplateServiceTest.java` — 1061
- `backend/domain/src/main/resources/db/migration/SEED-MANIFEST.md` — four rows in the load-order table
- `frontend/src/app/Sidebar.test.tsx` — 119; the test at ~171–186
- `frontend/src/mocks/handlers/portalOnboarding.ts` (new), `portalOnboarding.test.ts` (new), `handlers/index.ts` (one spread)
- `contracts/check-conventions.py` — `NO_PAGINATION`, `ROWLESS_403`
- `frontend/package.json`, `package-lock.json`, `orval.config.ts`, `src/api/generated/**` (regenerated) — §9
- `contracts/openapi.yaml` — only if the backend does not actually emit the project `403`
