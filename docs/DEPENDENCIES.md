# EduTrack — Task Ownership & Dependency Map

**Who does what · what blocks what · what can run at the same time.**

The single reference for sequencing. Task detail lives in `streams/STREAM-*.md`; this document is only about **order and ownership**.

> Slide version for walkthroughs and standups: [`decks/EduTrack-Dependencies.pptx`](decks/EduTrack-Dependencies.pptx)

| | |
|---|---|
| 🔴 | **Cross-stream blocker** — another developer is waiting. Slipping it stalls somebody else |
| 🟡 | Within-stream dependency — only affects its own owner |
| 🟢 | Independent — can start any time, blocks nobody |

---

## 1. Ownership index

| Stream | Owner | GitHub | Task IDs | Branch prefix |
|---|---|---|---|---|
| **A** Platform & Security | Shivendra | `@shivendraedunext-18` | `A-001` … `A-075` | `feat/platform/…` |
| **B** Masters & Clients | Ayush | `@Ayushedunext` | `B-001` … `B-064` | `feat/masters/…` |
| **C** Tickets & Ribbon | Divyansh | `@Divyanshedunext` | `C-001` … `C-070` | `feat/tickets/…` |
| **D** Engines & Realtime | Debashis | `@debashisedunext` | `D-001` … `D-060` | `feat/engines/…` |

---

## 2. Cross-stream dependency register

Every edge where one developer waits on another. **These are the only dependencies that can stall the team** — everything else is internal to a stream.

| # | Blocker | Owner | → Blocks | Waiting on it | Needed by |
|---|---|---|---|---|---|
| 1 | **A-002** docker-compose | Shivendra | Every local environment | **All three** | Day 1 ✅ *verified* |
| 2 | **A-003…A-009** baseline schema | Shivendra | B-001…B-005 seeds & entities | Ayush | Day 3 |
| 3 | **A-012** `dev-noauth` profile | Shivendra | Any authenticated endpoint | **All three** | **Day 10** |
| 4 | **A-034** `ScopeResolver` | Shivendra | D-013 socket authorisation mirrors it | Debashis | Week 7 |
| 5 | **A-040…A-042** append-only + hash chain | Shivendra | C-042 transition service writes to those tables | Divyansh | Week 9 |
| 6 | **B-001…B-004** seed data | Ayush | Realistic local data for everyone | All three | Day 7 |
| 7 | **B-007** fixture corpus | Ayush | C ribbon testing · D SLA testing | Divyansh, Debashis | **Day 10** |
| 8 | **B-021** priority master | Ayush | C-020 priority dropdown | Divyansh | Week 5 |
| 9 | **B-022** notification templates | Ayush | D-029 mail engine templates | Debashis | Week 8 |
| 10 | **B-024** working-hours service | Ayush | D-020…D-027 **every SLA calculation** · C stage durations | Debashis, Divyansh | **Week 3** |
| 11 | **B-039…B-043** workflow templates | Ayush | C-042 transition service needs stage definitions | Divyansh | Week 9 |
| 12 | **B-025…B-029** client master | Ayush | C-021 client dropdown · A-059 client widget | Divyansh, Shivendra | Week 7 |
| 13 | **C-042…C-049** transitions | Divyansh | A-058 ribbon widgets · D-058 live ribbon push | Shivendra, Debashis | Week 11 |
| 14 | **C-003** component library | Divyansh | Every screen in every stream | All three | Day 7 |
| 15 | **D-001** OpenAPI contract | Debashis | All API work in all streams | **All three** | Week 1 |
| 16 | **D-004** MSW mock server | Debashis | C's entire frontend before APIs exist | Divyansh | **Day 5** |
| 17 | **D-012…D-014** STOMP infrastructure | Debashis | C-045 live ribbon advance | Divyansh | Week 10 |
| 18 | **D-060** triage fields in the contract & mocks | Debashis | C-065…C-070 all build against it | Divyansh | **Before C-065** |
| 19 | **B-064** module master endpoint | Ayush | C-068's dropdown **against the real backend** — the screen itself builds on D-060's mock (§6) | Divyansh | Before M4 demo |
| 20 | **C-065** `tickets` ALTER for the triage fields | Divyansh | Needs **A's review**, not A's work (TEAM-PLAN §7.1) | Shivendra reviews | Week 3 |

### The four that decide whether this works

Everything else can slip a few days. These cannot — they are what makes four people work in parallel rather than in a queue.

| Due | Task | Owner | Consequence if late |
|---|---|---|---|
| **Day 5** | D-004 mock server | Debashis | Divyansh's entire frontend stalls |
| **Day 10** | A-012 `dev-noauth` | Shivendra | Ayush, Divyansh and Debashis all stop |
| **Day 10** | B-007 fixture corpus | Ayush | No realistic data to test ribbon or SLA against |
| **Week 3** | B-024 working-hours | Ayush | Debashis cannot start the SLA engine at all |

---

## 3. The critical path

The longest chain of dependent work. Delay anywhere on it delays go-live one-for-one; delay off it is absorbed.

```
A-003…A-009  schema                 wk 1     Shivendra
      ↓
A-008        immutability triggers  wk 2     Shivendra
      ↓
A-040…A-042  append-only + chain    wk 8-9   Shivendra
      ↓
C-042…C-049  transition service     wk 10-11 Divyansh
      ↓
C-055…C-058  Journey roll-up        wk 11    Divyansh
      ↓
A-058        ribbon dashboard       wk 12-14 Shivendra
      ↓
E2E walkthrough A                   wk 15-16 Divyansh
      ↓
hardening → go-live                 wk 17-18 all
```

**Off the critical path** — these have slack and can absorb delay: client master, Excel import, reports, chat, notification centre, browser push.

**The most fragile handoff** is A-042 → C-042 (week 9). Divyansh's transition service writes to the append-only tables, so the hash chain must be finished and proven first. If A-042 slips, C-042 slips, and everything downstream moves with it. Watch that one.

---

## 4. What can run in parallel

### Weeks 1–2 · Sprint 0 — full parallelism

All four independent except one short edge.

```
Shivendra  A-001 … A-013   schema, triggers, CI, dev-noauth     ── independent
Ayush      B-001 … B-008   seeds, entities, fixtures            ── waits on A's schema, day 3
Divyansh   C-001 … C-006   design system, Storybook, shell      ── independent
Debashis   D-001 … D-005   contract, codegen, MSW mocks         ── independent
```

### Weeks 3–7 — full parallelism

Nobody blocks anybody, provided the day-10 gates landed.

```
Shivendra  A-020 … A-037   auth, JWT, scope guard, permission matrix
Ayush      B-010 … B-029   masters, calendar, client master, Excel import
Divyansh   C-010 … C-037   create, list, detail, attachments, comments, quick update
Debashis   D-010 … D-023   outbox, STOMP, SLA + stage-SLA scanners
```

⚠️ Debashis's D-020…D-023 need **B-024** (week 3). If it is late, he works D-010…D-015 infrastructure instead and the SLA engine shifts right.

### Weeks 8–11 — first real convergence

```
Shivendra  A-040 … A-042   immutability core        ──┐
Ayush      B-039 … B-043   workflow templates       ──┼──▶ both feed C-042
Divyansh   C-038 … C-041   cycles, reopen             │
           C-042 … C-054   ribbon + handoff         ◀──┘  needs A-042 AND B-043
Debashis   D-024 … D-039   escalation, mail engine
```

**This is the week to watch.** Divyansh cannot start the transition service until Shivendra's hash chain and Ayush's stage definitions both exist. If either is late, give Divyansh C-059…C-064 (tabs, stage queue, bulk reassign) — they're independent.

### Weeks 12–16 — divergent again

```
Shivendra  A-054 … A-070   dashboard widgets, reports    (A-058 needs C's transitions)
Ayush      B-050 … B-063   ribbon UI with Divyansh, then reports
Divyansh   C-055 … C-064   Journey grid, stage queue, bulk reassign
Debashis   D-040 … D-059   notification centre, chat, live ribbon push
```

---

## 5. If you are blocked, do this

Nobody should ever idle. Each stream has independent work to fall back on.

| Owner | Blocked on | Pick up instead |
|---|---|---|
| **Shivendra** | Nothing — he is upstream of everyone | — |
| **Ayush** | A's schema (days 1–2) | Design the seed *content* — permission matrix, 11 task types, 4 priorities, 3 workflow templates. It comes from the blueprint, not the schema |
| **Ayush** | A's schema (later) | B-030…B-038 Excel import engine — pure POI work, touches no EduTrack table |
| **Divyansh** | D's mocks (days 1–4) | C-002…C-004 tokens, component library, Storybook — no data needed |
| **Divyansh** | A-042 or B-043 (week 9) | C-059…C-064 tabs, stage queue, bulk reassign, ticket links |
| **Debashis** | B-024 working-hours (week 3) | D-010…D-015 outbox, ShedLock, STOMP, channel interceptor |
| **Debashis** | C's transitions (week 11) | D-040…D-046 notification centre, preference matrix, offline queue |

---

## 6. Rules that prevent false dependencies

Three habits stop imaginary blockers appearing. Each has bitten real teams on this exact shape of project.

**Never wait for an API — use the mocks.** `D-004` exists so Divyansh can build the full ticket detail page, ribbon included, before a single ticket endpoint is written. When the real endpoint lands the only change is a flag.

**Never wait for auth — use `dev-noauth`.** And never write your own filtering as a workaround. That is how a temporary shortcut becomes a permanent security hole, and it is the top risk in blueprint §17.

**Never write your own date maths.** Every SLA, duration, breach and utilisation figure routes through `B-024`. Four private implementations of "working hours" produce four different answers to the same question, and the disagreement surfaces in a client dispute.

---

## 7. Keeping this current

This map is derived from `streams/STREAM-*.md`. When a task is added, split, or reassigned across streams, update §2 in the same pull request. A dependency register that has drifted is worse than none — people trust it and are wrong.

Review it at each milestone boundary, when `develop` is promoted to `main`.
