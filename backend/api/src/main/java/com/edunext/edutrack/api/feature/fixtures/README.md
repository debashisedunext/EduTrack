# feature/fixtures

**Owner: Stream B · Ayush**

B-007 — the 200-ticket fixture corpus. Unblocks Debashis's SLA scanner (D-020…D-023)
and Divyansh's ribbon work before either feature exists against real tickets.

## What is here

| Class | Job |
|---|---|
| `FixtureLoader` | `ApplicationRunner`, `@Profile("fixtures")`. Orchestrates the other two; refuses to start unless `local` is also active. |
| `ReferenceDataFixture` | 3 projects (`CRM`/`PAY`/`WEB`, same codes the frontend mock uses), 18 resources across the 6 roles, 8 clients with contacts, 4 org-wide `sla_policies` rows. None of this exists anywhere else — no B-00x migration seeds demo users, projects or clients. |
| `TicketFixtureGenerator` | The loop — picks a project/task type/breach/rework flag per ticket index and calls `SingleTicketFixture` 200 times. |
| `SingleTicketFixture` | Builds one ticket's entire journey (cycles, stage transitions, history, effort logs) via `WorkingHoursService`, inside its own `@Transactional` boundary. A separate bean from the generator on purpose — see its javadoc: a self-invoked `@Transactional` method silently runs with no transaction at all. |
| `TicketCodeAllocator` | Issues real `CRM-26-00347`-shaped codes. See its javadoc for why this duplicates rather than calls `api.feature.tickets.TicketCodeGenerator`. |

## Running it

```
./mvnw -pl api spring-boot:run -Dspring-boot.run.profiles=local,fixtures
```

Both profiles, always — `FixtureLoader`'s constructor throws otherwise, the same
guarantee A-012's `dev-noauth` gives its fake principal. Safe to re-run: it
checks for the `CRM` project first and skips if the corpus is already loaded.

## Three decisions worth reading before touching this package

**Never a Flyway migration.** `SEED-MANIFEST.md` §5 flags this directly — the
corpus is dev/test data, not the reference data every environment boots with,
and `spring.flyway.locations` is one shared classpath location between `api`
and `worker` with nothing separating a fixture migration from a real one. This
package is the isolation instead: a profile-gated `ApplicationRunner` that
never runs unless `fixtures` is explicitly requested.

**Hash-chain columns are NULL, on purpose, for now.** `ticket_history`,
`ticket_effort_logs` and `ticket_stage_transitions` are hash-chained, but the
algorithm does not exist anywhere yet — `AppendOnlyImpl.insert` is bare
`persist()`, and the real chaining is Stream A's A-040/A-044, due week 8-9.
Inventing a hashing scheme here, weeks ahead of that work, risks writing
something Stream A then has to reconcile with or migrate away from.
`prevHash`/`rowHash` are left NULL on every row this package writes; nothing
reads or verifies them today. **Once A-040/A-044 land, this corpus needs a
backfill (or a re-run of this loader against a fresh database) to bring those
columns in line.**

**`TicketCodeAllocator` duplicates C-011, deliberately.** `TicketCodeGenerator`
and `TicketCode` in `api.feature.tickets` are package-private — Stream C's
directory — and CLAUDE.md requires that stream's sign-off before this package
reaches into it. Rather than widen their visibility unasked, this package
re-implements the same `LAST_INSERT_ID(ticket_seq + 1)` idiom and the same
`"%s-%02d-%05d"` rendering. See `TicketCodeAllocator`'s javadoc for the two
ways to resolve the duplication later.

## Every duration is real working time

Stage entry/exit instants come from `WorkingHoursService.addWorkingHours`
(B-024) — never from adding wall-clock hours. CLAUDE.md: "never write your own
date maths." A fixture that faked durations would teach the SLA scanner and
the dashboard to expect numbers the real system never produces.

## What "varied" means for 200 tickets

- Every stage of all 3 templates (Standard Dev Flow, Support Fast-Track, Infra
  Flow) is visited — INTAKE-only tickets sit next to fully `CLOSED` ones.
- ~14% get exactly one rework loop (iteration 2) at whichever reworkable stage
  their walk reaches first — `QA→DEV`, `DEPLOY→DEV`, `VERIFY→DEV`,
  `SIGNOFF→DEV`, per the B-004 seed's own loop-back table.
- ~15% of `CLOSED` tickets get reopened into a second cycle, modelled as an
  `OVERRIDE` hop from `CLOSED` (not a `can_return_to` loop-back — closed
  tickets have none).
- ~20% are deliberately breached — reported 45–65 days back so their
  `sla_policies` resolution target is unambiguously in the past — so the SLA
  scanner has real cases to find without D-020 existing yet.
- ~40% carry client attribution (`client_id`/`client_contact_id`/
  `is_client_raised`), drawn from the clients mapped to that ticket's project.
