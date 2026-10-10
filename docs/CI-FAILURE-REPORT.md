# CI Failure Report — `develop`

**Prepared:** 2026-09-15 (revised the same day after a full re-verification — see §7) · **Branch:** `develop` @ `52a9e0ec` (HEAD) · **Workflow:** `.github/workflows/ci.yml` (latest run #1127) · **Remediation:** [`CI-FIX-GUIDE.md`](CI-FIX-GUIDE.md)

This is a full accounting of what is red on `develop`, since when, why, and who introduced each cause. Every number comes from the GitHub Actions API for `debashisedunext/EduTrack`; every root cause was confirmed either by a compiler/test annotation naming file and line, or by reproducing it locally against the exact commit.

---

## 1. Executive summary

| Question | Answer |
|---|---|
| Is `develop` green? | **No.** Backend, Frontend, OpenAPI contract and Security scan all fail; Package is skipped. |
| Last green run on `develop` | **#658**, 2026‑08‑20 10:11 UTC |
| Runs on `develop` since then | **180 — every one failed.** (Across all branches in the same window: 469 runs, of which 137 PR-branch runs passed.) |
| All‑time | 578 of 1,127 workflow runs failed. |
| How many distinct causes today | Layered: 2 compile errors → (behind them) 4 unregistered migrations → (behind them) **9 failing `api` guard tests** that have not run in CI since 4 Sep; 2 frontend test failures; 4 contract violations; a dependency audit failing on 3 advisories; Trivy and the ITs unmeasured. |
| Where they came from | **Four PRs merged with their own CI already red** — #438 (Stream C, 9 Sep), #442 (Stream A, 9 Sep), #446 (Stream A, 10 Sep), #450 (Stream B, 14 Sep) — plus the Security scan job, which has **never passed on `develop`** since the PR that created it (#258, 20 Aug). |
| Open PRs right now | 0 — nothing is queued; everything broken is on `develop` itself. |

The short version: the streak began on 20 Aug with a bug in the brand-new security job, not with product code. Product code then broke on top of it in four separate merges between 9 and 14 Sep, each one pressed through while its checks were red. Nobody has gone back, so every push and every 02:17 UTC nightly run since has failed on the accumulated pile.

---

## 2. How this was measured

- **Run data:** `GET /repos/debashisedunext/EduTrack/actions/runs` (all pages), per-run `/jobs`, and per-job `/check-runs/{id}/annotations`, all anonymous — the repository is public. Raw step logs are **not** readable without a token (`403`), so where annotations were only "exit code 1" the failure was reproduced locally instead.
- **Reproduced locally against the exact commits:**
  - `contracts/check-conventions.py` run against every `develop` merge that touched `contracts/` since 20 Aug (46 commits) — pinpoints the commit that first broke the contract job.
  - `mvnw -pl api -am test` in a scratch worktree at `a2636cce` (the merge before #450, when the code still compiled) — reveals what the backend job fails on *underneath* today's compile error.
  - `npm audit` in `frontend/` — what the security job will hit once the jar builds again.
- **Git archaeology:** `git log -S` on each broken symbol/label to find the commit that introduced it, then `/commits/{sha}/pulls` to map it to a PR, then `/commits/{head}/check-runs` to see whether that PR's own CI was green when it was merged.

---

## 3. Current failures on `develop` HEAD (run #1127, `52a9e0ec`)

```
✅ What changed
✅ Migration guard
❌ Backend — build & test                 died at: Build and test (mvn -B verify)   → javac errors
❌ Security — dependency & image scan     died at: Build the jar                    → same javac errors
❌ Frontend — lint, test & build          died at: npm run test  (lint passed)      → 2 test files
❌ OpenAPI contract                       died at: Enforce the API conventions (Redocly passed) → 4 violations
⏭️ Package — single deployable jar        skipped
⏭️ Security — dependency review           skipped (pull_request-only job)
```

Each job stops at its first failure, so every one of them is hiding at least one more. The layers, in the order CI will reveal them:

### 3.1 Backend — build & test

**Layer 1 — does not compile.** Two service signatures changed in PR #450 (`f5e6e7af`) without their tests:

- `ObJourneyStepLifecycleService.answerItem(long, long, boolean)` → `(long, long, Boolean answer, String remark)` — [service:909](../backend/api/src/main/java/com/edunext/edutrack/api/feature/onboarding/instances/ObJourneyStepLifecycleService.java#L909); six 3‑arg calls remain in [ObJourneyStepLifecycleServiceTest.java](../backend/api/src/test/java/com/edunext/edutrack/api/feature/onboarding/instances/ObJourneyStepLifecycleServiceTest.java) (lines 1263, 1283, 1301, 1312, 1326, 1334).
- `ObJourneyTemplateService.updateStep(…)` grew to 9 parameters, the last two primitive `boolean` — [service:493](../backend/api/src/main/java/com/edunext/edutrack/api/feature/onboarding/journeys/ObJourneyTemplateService.java#L493); [ObJourneyTemplateServiceTest.java:1061](../backend/api/src/test/java/com/edunext/edutrack/api/feature/onboarding/journeys/ObJourneyTemplateServiceTest.java#L1061) passes 8 arguments with `null` in a `boolean` slot.

**Layer 2 — `domain` module test failure, hidden since 10 Sep.** Reproduced at `a2636cce` (the commit before #450, which compiled): `SeedManifestTest.everyMigrationIsRegistered` and `theRegisterIsInTheOrderFlywayWillRunThem` fail. Re-checked at HEAD by parsing the register the same way the test does: **four migrations exist on disk and are not in the load-order table** of `backend/domain/src/main/resources/db/migration/SEED-MANIFEST.md`:

| Migration | Added by | PR / stream |
|---|---|---|
| `V20260910_0030__ob_many_active_services_per_product.sql` | `ea72b057` | #443, Stream C |
| `V20260910_0930__ob_journeys_pinned_sequence.sql` | `9f081f29` | #445, Stream A |
| `V20260910_1045__ob_scope_dashboard_summary.sql` | `9f081f29` | #445, Stream A |
| `V20260911_1800__ob_projects.sql` | `f5e6e7af` | #450, Stream B |

Because `domain` fails, Maven skips `api` — **the `api` module's ~700 unit tests and every integration test have not executed in CI since the backend job first went red** (intermittently from 21 Aug; continuously since 4 Sep).

**Layer 3 — `api` unit tests: 9 of 5,815 fail** (run locally at `a2636cce` with `-Dmaven.test.failure.ignore=true`). All nine are guard tests whose registers were not updated: `LayeringRulesTest` (33 controllers reaching repositories directly), `ObModuleSeparationTest` (7 onboarding/portal → ticketing dependencies), `PaginationRulesTest` (a second `nextCursor` record), `ContractConformanceTest` (3 GET responses drift from their schema), `ObWireConformanceTest` (an unrecorded known gap), `OnboardingFixtureScheduleTest.oneActiveVersionPerProduct` (obsolete since #442), `ClientAccountAdminServiceTest.placeholderPasswordIsHashed`, and both permission-matrix tests (routes with no entry for the six roles). Details and owners: `CI-FIX-GUIDE.md` §8. Integration tests (`*IT`) remain unmeasured — they need the CI containers.

### 3.2 Security — dependency & image scan

**Layer 1** — the "Build the jar" step hits the same javac errors as 3.1.

**Layer 2 — `npm audit --audit-level=high` will fail** (it is the step that has been failing on every `develop` run from 25 Aug to 9 Sep, before the compile error started masking it). Reproduced locally today — 8 advisories, three of which are at or above `high`:

| Package | Severity | Path | Fix |
|---|---|---|---|
| `fast-uri` | high | prod, transitive | `npm audit fix` (non-breaking) |
| `js-yaml` 4.0.0–4.3.1 | high | via `orval` ≤ 8.30 (dev) | needs `orval@8.33.0` — **semver-major** |
| `orval` ≤ 8.30 | critical | dev | same upgrade |

(`react-router` 6.x has two *moderate* advisories; those do not trip `--audit-level=high` but the fix is also a major bump.)

**Layer 3 — Trivy (jar + lockfile, HIGH/CRITICAL, `--ignore-unfixed`).** Status unknown: it passed from 25 Aug to 9 Sep and has been masked since 10 Sep. Not reproducible locally (Docker Desktop is not running on this machine).

### 3.3 Frontend — lint, test & build

Lint passes. Two test files fail; `npm run build` has not run on this code.

- **`src/mocks/coverage.test.ts:89`** — 15 `portal*` operations in `contracts/openapi.yaml` have no MSW handler. Introduced by **PR #438** (`20638876`, Stream C, C‑121 client portal, merged 9 Sep 08:15). The test's own message assigns the mocks to Stream D (task D‑004) — or Stream C builds against a 501.
- **`src/app/Sidebar.test.tsx:120,182`** — expects a "New client" link. **PR #450** removed that nav row ([Sidebar.tsx:148-152](../frontend/src/app/Sidebar.tsx#L148-L152)) and the `/onboarding/clients/new` route ([App.tsx:729](../frontend/src/App.tsx#L729)) without touching the test. *(An earlier version of this report blamed PR #447 for this; `git log -S"label: 'New client'"` shows #447 never touched the label — see §7.)*

### 3.4 OpenAPI contract — validity, conventions, staleness

Redocly validation passes; our own convention checker reports **4 violations**. Bisected locally across every `develop` commit touching `contracts/` since 20 Aug:

| Commit / PR | Violations | What |
|---|---|---|
| everything up to `8cb6a355` (PR #441, 9 Sep) | 0 | green |
| **`4a8275f2` — PR #442** (Stream A, "A product sells several named Module Services", 9 Sep 18:30) | **2** | `listObJourneyTemplates` (new in `d9b075e0`): collection without cursor pagination / without `meta` |
| … #443, #445, #447, #449 | 2 | unchanged |
| **`52a9e0ec` — PR #450** (Stream B, 14 Sep) | **4** | + `updateObProject` and `deleteObProject` declare `403` without a `ROWLESS_403` entry |

The three later steps of this job — regenerate client, staleness check, type-check — have not run since 9 Sep.

### 3.5 Package / Security — dependency review

Skipped, not failed. Package (the only job that proves the jar starts and serves the UI) last succeeded on `a2636cce` (11 Sep); it has been failing or skipped since #450.

---

## 4. Timeline of the red streak

| Date (UTC) | Run | Event | Jobs red on `develop` afterwards |
|---|---|---|---|
| 20 Aug 10:11 | #658 | last green run | — |
| 20 Aug 10:11 | #659 | **PR #258** (A‑074 security hardening) merges and *creates* the Security scan job. Its Trivy step fails on its first run — `ci.yml` now records why: the step passed two targets to a command that takes one, so *"every run of it was red for a reason that was never a vulnerability"*. | Security |
| 21 Aug | #690 | Backend "Build and test" fails for the first time (cause not recoverable without logs; the backend then flaps — green 25 Aug and 1 Sep, red 4 Sep onward) | Security, Backend |
| 25 Aug → 9 Sep | | Trivy fixed; the Security job now fails one step later, at `npm audit --audit-level=high`. The job has **never** been green on `develop`. | Security (+ Backend intermittently) |
| 4 Sep → | #896 | Backend red continuously from here | Security, Backend |
| 9 Sep 08:15 | | **PR #438** (Stream C) adds 15 portal operations, no mocks → `coverage.test.ts` red | + Frontend |
| 9 Sep 18:30 | #1101 | **PR #442** (Stream A) adds `listObJourneyTemplates` unpaginated → contract red | + Contract |
| 10 Sep | #1113 | **PR #446** (Stream A) breaks compilation → Package red, Security dies at jar build; #445 and #443 leave migrations unregistered | all five |
| 11 Sep | #1117–1120 | #447/#448/#449 restore compilation (Package green again on `a2636cce`); manifest, coverage, contract, npm audit still red | Backend, Frontend, Contract, Security |
| 14 Sep 20:07 | #1126 | **PR #450** (Stream B) merges: compile broken again (2 signatures), "New client" row removed under a live test, 2 more contract violations, a 4th unregistered migration | all five |
| 15 Sep 07:41 | #1127 | nightly run on the same HEAD — identical result | all five |

---

## 5. How this got onto `develop`

Each of the four code-breaking PRs was merged **while its own head commit showed the failure it introduced**, straight into `develop`, by the same account, without an integration branch:

| PR | Stream | Merged | Red on its own head commit before merge |
|---|---|---|---|
| #438 | C | 9 Sep 08:15 | Backend, Frontend |
| #442 | A | 9 Sep 18:30 | Backend, Frontend, OpenAPI contract |
| #446 | A | 10 Sep 17:46 | Frontend |
| #450 | B | 14 Sep 20:07 | Backend, Frontend, OpenAPI contract |

`CLAUDE.md` names this exact failure mode and its cure: branch protection with a required aggregate check (`all-checks-passed`, Stream A, issue #3). Until that lands there is nothing on the platform stopping a red PR from being merged — and, as of 20 Aug, `develop` itself was already red from the security job, which makes "is `develop` red?" a useless signal for anyone deciding whether their PR is safe. **A branch that is always red teaches everybody to ignore red.** That is the real cost of the 26 days, more than any single bug.

---

## 6. Ownership of the fixes

| Cause | Fix owner | Introduced by |
|---|---|---|
| 2 compile errors | Stream B | #450 |
| 4 unregistered migrations | C (1), A (2), B (1) — or Stream A as manifest owner in one PR | #443, #445, #450 |
| `Sidebar.test.tsx` | Stream B | #450 |
| 15 portal mocks | Stream D (D‑004) | #438 (Stream C) |
| `listObJourneyTemplates` pagination | Stream A (endpoint) + Stream D (allowlist file) | #442 |
| `updateObProject`/`deleteObProject` 403 | Stream B (endpoint) + Stream D (allowlist file) | #450 |
| npm audit (fast-uri, js-yaml/orval) | Stream A (owns the gate, A‑074) with frontend owner sign-off | dependency drift |
| Trivy | unknown until unmasked | — |

Step-by-step remediation for each: [`CI-FIX-GUIDE.md`](CI-FIX-GUIDE.md).

---

## 7. Corrections to the first version of this report

The first draft (earlier on 15 Sep) was checked line by line against the API and the source before this revision. What changed, so nobody acts on the old numbers:

| First draft said | Verified | Why it was wrong |
|---|---|---|
| "~469 consecutive runs, zero successes" | **180 `develop` runs**, all failed; 469 is the global run count across *all* branches, 137 of which (PR branches) passed | counted run numbers, not `develop` runs |
| "PR #447 removed the New client row" | **PR #450** did; #447 is not an offender | `git log -S` on the label |
| "Sidebar test unfixed across ~100+ runs" | **12 runs** total since 11 Sep (9 on `develop`) | run numbers are global |
| "Almost entirely one PR (#450)" | Four PRs plus a CI-job bug that started the streak; #450 is the *latest* and largest layer, not the first | only the top failure of each job was visible |
| Backend failure = compile error | Compile error is layer 1; **`SeedManifestTest`** is behind it; the `api` tests behind that | reproduced at `a2636cce` |
| Security scan = compile error | Compile error masks an **npm audit** failure that predates it by three weeks | annotations on earlier runs |
| "All four contract violations from #450" | Two are from **#442 (Stream A)** | local bisect of `check-conventions.py` |

---

## Appendix — raw references

- Latest run: [#1127](https://github.com/debashisedunext/EduTrack/actions/runs/34942995995) on `52a9e0ec`; first red run #659 on `73a73566` (PR #258); last green #658
- Offending PRs: [#438](https://github.com/debashisedunext/EduTrack/pull/438) · [#442](https://github.com/debashisedunext/EduTrack/pull/442) · [#446](https://github.com/debashisedunext/EduTrack/pull/446) · [#450](https://github.com/debashisedunext/EduTrack/pull/450)
- Root-cause commits: `f5e6e7af` (signatures, Sidebar, 403s, `ob_projects` migration) · `20638876` (portal ops) · `d9b075e0` in `4a8275f2` (`listObJourneyTemplates`) · `ea72b057`, `9f081f29` (unregistered migrations)
- Counts: 1,127 runs all-time, 578 `failure`; 180/180 `develop` runs failed since #658; 12 runs since 11 Sep
