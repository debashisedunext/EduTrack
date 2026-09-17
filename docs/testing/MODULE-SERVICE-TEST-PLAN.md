# Module Service — manual test plan (TAT, dependencies, parallel work)

**Scope.** Everything a Module Service *is*: its catalogue row, its designer,
the TAT figure it prints, the cross-service dependency it declares, and what
both of those do to a real client's journeys once a project is boarded.

**Where behaviour is decided.** `docs/Onboarding-Module-Plan.md` — §5 item 5
(service-level dependency), §5 item 6 (step activation and parallel steps),
OB-07 (the designer and the catalogue), §5.10 (journey totals). Where this
document and the plan disagree, the plan wins and the disagreement is a bug
report, not a correction to make here.

---

## 0. Read this first — the three TATs are three different numbers

Most of the confusion in this area comes from "TAT" naming three things. They
are computed by three different pieces of code and **they are not meant to be
equal**.

| # | Which TAT | Question it answers | Computed by | Where you see it |
|---|---|---|---|---|
| 1 | **Task TAT** | How many working days this one task takes | `tat_days`, entered by the admin | Designer task card, `ObTaskDialog` |
| 2 | **Service TAT** | How long this Module Service takes end to end | [`JourneyTatCalculator.criticalPathDays`](../../backend/api/src/main/java/com/edunext/edutrack/api/feature/onboarding/journeys/JourneyTatCalculator.java) — **critical path over `depends_on_step_id`**, *not* Σ | Catalogue TAT column, designer header, [`journeyTemplateTat.ts`](../../frontend/src/features/onboarding/journeys/journeyTemplateTat.ts) |
| 3 | **Project TAT** | How long this whole project takes | [`ObProjectTatPath.longestPath`](../../backend/api/src/main/java/com/edunext/edutrack/api/feature/onboarding/projects/ObProjectTatPath.java) — **longest chain over `ob_journey_template_dependencies`**, each service weighted by its own tasks | Project detail header "Total TAT" |

**The one rule behind all three: a dependency adds, a parallel branch does
not.** Two tasks of 1 and 2 days that wait for nothing both start on day 1, so
the service is 2 days, not 3. Chain them and it is 3.

**Two levels of dependency, and they are unrelated mechanisms:**

- **Task → task**, inside one service: `depends_on_step_id`, at most one
  predecessor, must be an earlier task in the same template. Drives task
  activation at runtime (`activateEligibleSteps`).
- **Service → service**, across the catalogue: `ob_journey_template_dependencies`,
  **a set** (since `V20260911_1100`), cross-product allowed, cycle-free.
  Drives the journey-level hold (`ob_journeys.held_by_journey_id`).

Keep them apart while testing. A bug in one looks nothing like a bug in the
other.

---

## 1. Preconditions — get the environment honest before test 1

Every one of these has cost someone an hour on this project. Do them in order.

### 1.1 Three processes, not two

The worker is the one that gets forgotten. The API writes no dashboard summary
table; without the worker, every counter reads "No summary has been computed
yet" and gets reported as a broken screen.

```powershell
$sp = "C:\Users\Admin\AppData\Local\Temp\edutrack-logs"
New-Item -ItemType Directory -Force $sp | Out-Null

# 1. API
Start-Process -FilePath "C:\Users\Admin\EduTrack\backend\mvnw.cmd" -ArgumentList "-pl","api","spring-boot:run","-Dspring-boot.run.profiles=local","-DskipFrontend" -WorkingDirectory "C:\Users\Admin\EduTrack\backend" -RedirectStandardOutput "$sp\api.log" -RedirectStandardError "$sp\api.err" -WindowStyle Hidden

# 2. Worker — nothing fills the summary tables without it
$env:OB_STATS_REFRESH_INTERVAL = "PT30S"
Start-Process -FilePath "C:\Users\Admin\EduTrack\backend\mvnw.cmd" -ArgumentList "-pl","worker","spring-boot:run","-Dspring-boot.run.profiles=local" -WorkingDirectory "C:\Users\Admin\EduTrack\backend" -RedirectStandardOutput "$sp\worker.log" -RedirectStandardError "$sp\worker.err" -WindowStyle Hidden

# 3. Vite — MOCKS OFF, set in the parent session so it is inherited
$env:VITE_USE_MOCKS = "false"
Start-Process -FilePath "cmd.exe" -ArgumentList "/c","npm run dev" -WorkingDirectory "C:\Users\Admin\EduTrack\frontend" -RedirectStandardOutput "$sp\web.log" -RedirectStandardError "$sp\web.err" -WindowStyle Hidden
```

Install `common,domain` first if you have pulled since the last run, or `-pl api`
compiles against a stale jar:

```
./mvnw -pl common,domain install -DskipTests -DskipFrontend=true
```

### 1.2 Prove the mocks are off — this is not optional

MSW answers every API call in the browser by default and its errors look almost
real. `curl` does **not** trigger it, so a curl smoke test proves nothing about
what the browser sees.

```bash
curl -s http://localhost:5173/src/main.tsx | grep -o '"VITE_USE_MOCKS":[^,}]*'
```

Must print `"VITE_USE_MOCKS": "false"` — **no trailing space**. No match at all
means the variable is unset and mocks are ON. Also confirm the browser console
does *not* say `EduTrack mock API active`, and that port 5174 is not a second,
still-mocked Vite.

### 1.3 Log in as someone who can actually write

Every Module Service write is `ADMIN_ONLY` in
[`ObModuleRoleRules`](../../backend/api/src/main/java/com/edunext/edutrack/api/security/module/ObModuleRoleRules.java) —
`POST`/`PATCH`/`DELETE /journey-templates`, `PUT /order`, `PUT /depends-on`.

Use **`priya.nair` / `Launcher#2026Ob`** (dual-module, OB_ADMIN, no forced
password change). The other OB fixture users are `Fixture#B101-2026` but most
land on `/change-password` first.

A failed login that does **not** increment `users.failed_attempts` never reached
Spring — it was the mock or the wrong port:

```bash
docker exec edutrack-mysql mysql -uedutrack_app -pedutrack -D edutrack -N -B \
  -e "SELECT username, failed_attempts, locked_until FROM users WHERE username='priya.nair';"
```

### 1.4 Have a corpus

If the onboarding tables are empty, reboot the 
API with `local,fixtures`. Do not
hand-seed — products created by hand break `OnboardingFixture`'s idempotence
probe.

Boot with `local,fixtures` anyway if any test needs a sign-off cleared:
`DevSignoffSimulationController` only exists under `dev-noauth` or `fixtures`,
and without it a task whose `requires_signoff` is set can never be completed by
any route.

### 1.5 After any new migration, re-run grants

`make grants`, or the API boots with a misleading *"missing table"*. MySQL hides
tables the app user has no privilege on, so Hibernate honestly reports missing.

### 1.6 The screens under test

- Catalogue — `http://localhost:5173/onboarding/journey-templates`
- Designer — `http://localhost:5173/onboarding/journey-templates/{id}` (click a row)
- Project detail — `http://localhost:5173/onboarding/projects/{id}`

---

## 2. What is already covered by automated tests — do not re-test by hand

The pure arithmetic is well covered. Spend your manual time elsewhere.

| Already proven | By |
|---|---|
| Critical path: parallel doesn't add, chains do, longest chain wins, empty = 0, cycle terminates, TAT floored at 1 | `JourneyTatCalculatorTest` (10 cases), `journeyTemplateTat.test.ts` (7) |
| Project longest path: independent services, chains, two dependencies, dependency outside the project, cycle, self-dependency | `ObProjectTatPathTest` (12 cases) |
| Picker cycle exclusion including transitive and second-branch-of-a-fork | `moduleServiceCatalogue.test.ts` |
| Tree nesting, day ranges, schedule bars | `journeyTemplateTree.test.ts` |
| Hold at instantiation, first-of-two does not release, completion releases and activates | `ObJourneyDependencyReleaseIT` |

Run them once before you start, so a manual failure is not chasing a known-red
suite:

```bash
cd frontend && npm run test -- --run src/features/onboarding/journeys src/features/onboarding/projects
cd backend  && ./mvnw -pl api -Dtest=JourneyTatCalculatorTest,ObProjectTatPathTest,ObJourneyTemplateServiceTest test
```

**What automation does not cover, and what this plan is therefore for:** the
wiring. Whether the number the calculator produces is the number the screen
prints. Whether the picker's exclusions match the server's refusals against a
real database. Whether a held journey actually looks held to the person who owns
its first task. And whether the same service reads the same on four different
screens.

---

## 3. The test points

Each has an ID. Track pass/fail against these.

### Group A — Service TAT (the catalogue and designer figure)

| ID | What to prove | Expected |
|---|---|---|
| **A-01** | A service with no tasks | TAT reads `0 working days`, no schedule bars, no crash |
| **A-02** | One task, 3 days | `3 working days`; card shows `Day 1–3` |
| **A-03** | Two tasks, 1d and 2d, **neither depends on anything** | **2** working days, not 3. Cards read `Day 1` and `Day 1–2` |
| **A-04** | Same two, second now depends on first | **3** working days. Second card reads `Day 2–3` |
| **A-05** | Three chains of different lengths in one service | TAT = the longest chain only. Shortening a *shorter* chain changes nothing |
| **A-06** | Change a task's TAT from 2 to 5 | Header total updates without a page reload |
| **A-07** | Delete a task that others depend on | Refused — `StepHasDependentsException`. Re-point the dependents first |
| **A-08** | Delete a leaf task | Allowed; the total drops by its contribution to the critical path, which may be **0** if it sat on a short parallel branch |
| **A-09** | A dependency that crosses implementation stages | Counted exactly like any other; drawn as a caption rather than an indent |
| **A-10** | Singular/plural | `1 working day`, `2 working days` |
| **A-11** | Catalogue TAT column vs designer header for the same service | **Identical.** Two arithmetics disagreeing is what the shared walk exists to prevent |
| **A-12** | A draft revision with different TATs from its active version | The catalogue shows the *head* — active where one exists, newest otherwise — one row per `(product, name)`, not two |

### Group B — Task-to-task dependency (inside one service)

| ID | What to prove | Expected |
|---|---|---|
| **B-01** | The dependency picker offers only **earlier** tasks in the same template | Later tasks, and tasks from other services, are not offered |
| **B-02** | Set a dependency, reload | Persisted; the designer draws the child indented under its parent |
| **B-03** | Clear a dependency back to "none — runs parallel" | The task becomes a root, starts day 1, and the TAT may drop |
| **B-04** | Reorder tasks so a dependency would point forwards | Re-validated — either refused, or the dependency is cleared, never left pointing forwards silently |
| **B-05** | Publish a service, then try to edit a task's TAT or dependency | Refused — `TemplateNotEditableException`. Published journey content is frozen |
| **B-06** | On that same published service, change **name / product / position / depends-on** | **Allowed.** These are catalogue metadata, not journey content. This asymmetry is deliberate — verify both halves |

### Group C — Service-to-service dependency (the catalogue picker)

| ID | What to prove | Expected |
|---|---|---|
| **C-01** | Pick one other service as a dependency, save, reload | Persisted; the "Depends on" cell names it |
| **C-02** | Pick **three** services at once | All three persist. This is a **set** since `V20260911_1100` — if only one survives, the multi-dependency migration is not live on this database |
| **C-03** | Save again with the set unchanged | Succeeds. (The write is delete-then-insert and needs a flush between; without it this is a duplicate-key 500. Worth one deliberate click) |
| **C-04** | Clear the picker to empty and save | Every dependency removed; the service runs unheld |
| **C-05** | The service itself is never offered in its own picker | Absent |
| **C-06** | A service that **directly** depends on this one is not offered | Absent |
| **C-07** | A service that depends on it **transitively** (A→B→C, editing C) is not offered | Absent. This is the case a chain-walk misses and then reports as safe |
| **C-08** | Cross-product dependency (an ERP service ← a Biometric service) | Allowed |
| **C-09** | Force a cycle past the UI with a direct API call | `409`, naming the offending template — not a 500, not a raw constraint violation |
| **C-10** | Name a non-existent template id via the API | `404` |
| **C-11** | Call `PUT /depends-on` with **no** `If-Match` | `428`. With a stale one → `412` |
| **C-12** | Delete a service others depend on | Refused — `ModuleServiceHasDependentsException`, naming the dependents |
| **C-13** | Delete a service clients are already boarded on | Refused — `ModuleServiceInUseException`, naming the journeys |
| **C-14** | Reorder the catalogue (↑/↓ in the service's own admin form) | `sequence` persists as 0..N-1; a partial list is `400` |

### Group D — Parallel work at runtime (a real client's journey)

This is where the feature earns its keep, and where nothing is covered
end-to-end today.

| ID | What to prove | Expected |
|---|---|---|
| **D-01** | Board a project through a service whose first two tasks have no dependency | **Both** go `IN_PROGRESS` at gate-open. A journey holding several in-progress tasks at once is correct, not a bug |
| **D-02** | A task whose predecessor is not done | Stays `PENDING`. A manual start is **refused, naming the blocking task** |
| **D-03** | Complete the predecessor | Every newly-eligible task activates immediately — not just the next one by sequence |
| **D-04** | **Skip** the predecessor instead of completing it | The same activation sweep runs. A skip is a completion for dependency purposes |
| **D-05** | A branch point: one task that two others depend on | Completing it activates **both** |
| **D-06** | A merge point: when the journey completes | Only when **all** tasks are DONE/SKIPPED — parallel branches must all land, not just the longest chain |
| **D-07** | Task badges on the ribbon/board | `↳ N` for a dependent, `∥` for a parallel task |

### Group E — The service-level hold (`held_by_journey_id`)

| ID | What to prove | Expected |
|---|---|---|
| **E-01** | Client buys product A and product B, where B's service depends on A's | B's journey is **created** but held: no task activates, no clock runs |
| **E-02** | Inspect the row | `held_by_journey_id` = A's journey id; `released_at` NULL |
| **E-03** | B's service has **three** dependencies; complete the first | **Still held.** `held_by_journey_id` re-points at the next outstanding one and `released_at` stays NULL. This is the trap the naive one-liner falls into — it would start the journey behind the *first* dependency to finish |
| **E-04** | Complete the last outstanding dependency | `held_by_journey_id` → NULL, `released_at` stamped, first wave of tasks activates |
| **E-05** | A `JOURNEY_UNBLOCKED` notification | Enqueued to every owner whose task just activated, on EMAIL and IN_APP. Check `ob_notifications` / Mailpit |
| **E-06** | The client **never bought** the dependency's product | Vacuous — the journey starts unheld. This is what lets a partially-purchased client board at all |
| **E-07** | The client already **finished** the dependency's journey | Also vacuous; starts unheld |
| **E-08** | The client buys a product **after** the gate has opened | Instantiates directly `OPEN` and the first wave activates immediately |
| **E-09** | Publish a **new version** of the dependency service, then complete that client's journey | Still releases. The edge resolves by `(product, service name)`, not by template row id — by id, every dependent journey would stay permanently held |
| **E-10** | **Rename** a service clients are on | Allowed; every journey's denormalised `service_name` is re-stamped in the same transaction, and the hold still resolves |
| **E-11** | **Move** a service to another product once a client is on it | `409` — a journey's `productId` is the client's purchase and cannot follow |

Useful while running group E:

```bash
docker exec edutrack-mysql mysql -uedutrack_app -pedutrack -D edutrack -N -B -e "
  SELECT j.id, j.service_name, j.gate_status, j.held_by_journey_id, j.released_at, j.completed_at
    FROM ob_journeys j WHERE j.ob_client_id = <CLIENT_ID> ORDER BY j.sequence, j.id;"

docker exec edutrack-mysql mysql -uedutrack_app -pedutrack -D edutrack -N -B -e "
  SELECT template_id, depends_on_template_id FROM ob_journey_template_dependencies ORDER BY 1,2;"
```

### Group F — Project TAT (longest path across services)

| ID | What to prove | Expected |
|---|---|---|
| **F-01** | A project with **one** service | Project TAT = that service's figure |
| **F-02** | A project with **two independent** services, 4d and 6d | **6**, not 10. A sum here puts a tentative completion date a week late on every parallel project — that is a promise to a client, not a rounding |
| **F-03** | The same two, now B depends on A | **10** |
| **F-04** | A chain of three | Sums along the chain |
| **F-05** | A service depending on a service **this project was not boarded through** | The edge contributes nothing — there is no journey to wait for |
| **F-06** | A project with no journeys yet | `0`, not an error |
| **F-07** | Republish a Module Service with different TATs while a project runs | The running project's figure **does not move** — `ob_journey_steps.tat_days` is pinned at instantiation |

### Group G — Cross-screen consistency

Same service, same client, four screens. Any disagreement is a finding.

| ID | What to prove | Expected |
|---|---|---|
| **G-01** | Catalogue TAT column = designer header | Equal |
| **G-02** | Designer day ranges = the tree's span = the header total | Equal |
| **G-03** | Project detail "Total TAT" vs the module strip rows | **See the known suspect below — investigate before filing** |
| **G-04** | Journey roll-up on the client page vs the project header | A consistent reading of the same pinned figures |

> ### ⚠ Known suspect — check G-03 deliberately
>
> [`projectTree.ts`](../../frontend/src/features/onboarding/projects/projectTree.ts)
> computes each module-strip row's `totalTatDays` as a plain
> `own.reduce((sum, t) => sum + t.tatDays, 0)`, and its comment still asserts
> that this is *"the only reading under which the strips add up to the header's
> Total TAT"*.
>
> That was true when the header was `SUM(tat_days)`. It is no longer: the header
> is now `ObProjectTatPath.longestPath` folded over `JourneyTatCalculator`. So on
> a project with **two parallel services**, or with **any parallel tasks inside
> one service**, the strips will not add up to the header — by design at the
> project level, and arguably by oversight at the service level, where the strip
> sums what the catalogue critical-paths.
>
> **Test it explicitly (F-02 gives you the fixture) and record what you see.**
> Do not "fix" it mid-test run: whether the strip should print the critical path
> or a labelled "total effort" is a plan question, and the file belongs to
> another stream. File it with the numbers.

### Group H — Permissions and negative paths

| ID | What to prove | Expected |
|---|---|---|
| **H-01** | Any Module Service write as a non-`OB_ADMIN` onboarding user | Refused |
| **H-02** | Any of these routes as a user with **no ONBOARDING module grant** | **404, not 403** — no existence leak. `ModuleAccessFilter` answers before the handler runs |
| **H-03** | Duplicate service name within one product | `409` |
| **H-04** | The same name under a **different** product | Allowed |
| **H-05** | A `tatDays` of 0 or negative | Refused by the CHECK constraint; the UI should not offer it |
| **H-06** | Concurrent edit: open the designer in two tabs, save in both | The second gets `412` on `If-Match`, not a silent lost update |

---

## 4. The plan — five sittings, in this order

The order matters: each sitting builds the fixtures the next one needs.

### Sitting 1 — Environment and baseline (30 min)

1. Bring up all three processes (§1.1).
2. Prove the mocks are off (§1.2). **Do not skip this — it invalidates everything after it.**
3. Log in as `priya.nair` and confirm you land in the onboarding module.
4. Run the automated suites in §2 and note anything already red.
5. Open the catalogue. Confirm one row per `(product, service)`.

**Exit criterion:** you can create a Module Service and see it appear. If not,
stop and fix the environment — nothing below is meaningful until you can.

### Sitting 2 — Service TAT in isolation (60–90 min) → Groups A and B

Build one throwaway service, `TAT Lab`, under any product, and drive it through
Group A by editing it. Suggested shape:

```
Task 1  "Kickoff"     2d   depends on: none
Task 2  "Survey"      1d   depends on: none      <- A-03: total must read 2, not 3
Task 3  "Install"     3d   depends on: Task 1    <- A-04: total becomes 5
Task 4  "Handover"    1d   depends on: Task 3    <- A-05: total becomes 6
Task 5  "Poster"      1d   depends on: Task 2    <- total stays 6, the short branch is free
```

Work A-01 through A-12, then B-01 through B-04. Finish by **publishing** the
service and running B-05 and B-06 — the frozen/not-frozen asymmetry is the most
misunderstood rule on this screen, and it is worth seeing both answers back to
back.

**Record:** the header total after each edit. A total that only updates on
reload is a finding (A-06).

### Sitting 3 — The dependency graph, catalogue level (60 min) → Group C

You need **four** services to test transitive exclusion honestly. Create them
under two different products:

```
Product P1:  S-Core        no dependencies
Product P1:  S-Config      depends on -> S-Core
Product P2:  S-Biometric   depends on -> S-Config     <- C-07: editing S-Core must NOT offer S-Biometric
Product P2:  S-Standalone  no dependencies            <- C-08 cross-product target
```

Work C-01 through C-08 in the UI, then C-09 through C-11 with curl — the UI will
not let you form a cycle, and the point is that the server refuses it too:

```bash
# Read the tag
curl -s -D- -o /dev/null http://localhost:5173/api/v1/onboarding/journey-templates/<ID> \
  -H "Authorization: Bearer $TOKEN" | grep -i etag

# Attempt the cycle — expect 409
curl -s -X PUT http://localhost:5173/api/v1/onboarding/journey-templates/<S_CORE_ID>/depends-on \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H 'If-Match: "<tag>"' \
  -d '{"dependsOnTemplateIds":[<S_BIOMETRIC_ID>]}'

# The same call with no If-Match — expect 428
```

Leave C-12 and C-13 (the two delete refusals) until **after** sitting 4 — C-13
cannot be tested until a client is boarded.

### Sitting 4 — Board a client and watch it run (90 min) → Groups D and E

This is the real test. Everything before it is preparation.

1. Create an OB client and a project, purchasing **both** P1 and P2, so that
   `S-Biometric` (which waits on `S-Config`) and `S-Standalone` (which waits on
   nothing) are both boarded.
2. Open the gate.
3. **Before touching anything**, run the two SQL queries in Group E. Write down
   which journeys are held and by what. That snapshot is your baseline.
4. Work D-01 through D-07 on `S-Core`: the parallel first wave, the refused
   manual start, the activation sweep on completion, the skip path, the branch
   and the merge.
5. Work E-01 through E-08 by completing journeys in order, re-running the query
   after each completion. **E-03 is the one to be careful with** — give
   `S-Biometric` a second dependency first, so you can prove that completing one
   of two does not release it.
6. E-09 through E-11: publish a new version of `S-Config`, rename it, and try to
   move it to another product. Three different answers, all of them correct.
7. Go back and finish **C-12 and C-13** now that a client is on a service.

**Record:** for every completion, the before/after of `held_by_journey_id` and
`released_at`. That pair is the whole feature.

### Sitting 5 — Numbers across screens (45 min) → Groups F, G and H

1. F-01 through F-07 on the project from sitting 4, plus a second, deliberately
   parallel project for F-02 (two independent services with clearly different
   TATs — 4d and 6d).
2. G-01 through G-04. Open the catalogue, the designer, the project detail and
   the client journey page side by side for the *same* service, and write the
   four numbers down.
3. **G-03 against the F-02 project** — this is where the `projectTree` suspect
   will show, if it shows. Record both numbers and how they differ.
4. H-01 through H-06. For H-02, log in as a user with no ONBOARDING grant and
   confirm **404** on `/api/v1/onboarding/journey-templates`. A 403 here is a
   security finding, not a cosmetic one.

---

## 5. Recording results

For each ID record **Pass / Fail / Blocked**, the numbers you actually saw, and
for a failure the exact request and response. A finding without the numbers is a
conversation; a finding with them is a fix.

Anything in Group E or H-02 that fails is worth stopping for — a hold that does
not hold, and a 403 that should be a 404, are both correctness rather than
polish.
