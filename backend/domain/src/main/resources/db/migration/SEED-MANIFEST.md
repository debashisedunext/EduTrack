# Seed manifest — the fixed load order

**B-008.** Authored by Stream B; **owned by Stream A** alongside the rest of this
directory (TEAM-PLAN.md §6). Every stream adds a row to it.

TEAM-PLAN.md §7.3 sets the rule this file implements:

> **Rule: one seed file per stream** (`seed_masters.sql`, `seed_tickets.sql`, …),
> never one shared file. Loaded in a fixed order by a manifest A owns.

Flyway already *executes* in a fixed order — ascending version, which for us is
the `V<YYYYMMDD>_<HHMM>` timestamp. What it does not do is make that order
**reviewable**. Ordering defects do not live inside any one file; they live
between two files, and nobody reads the whole directory end to end to find them.
This is the one page where the whole queue is visible at once.

That is not hypothetical. `V20260808_1400` exists because laying this register
out revealed that a correction migration was silently undone by the seed file
queued immediately behind it — see [Correction history](#correction-history).

---

## 1. The load order

Ascending by version — top to bottom is the order Flyway runs them, on every
environment, always.

<!-- load-order:begin -->

| # | File | Stream | Task | Kind | Loads |
|---|---|---|---|---|---|
| 1 | `V20260805_1024__baseline_identity.sql` | A | A-003 | schema | `users`, `roles`, `permissions`, `role_permissions`, `user_roles`, `projects`, `project_members` |
| 2 | `V20260805_1041__baseline_tickets.sql` | A | A-004 | schema | `tickets`, `ticket_cycles`, `ticket_history`, `ticket_effort_logs`, `ticket_watchers`, `ticket_links` |
| 3 | `V20260805_1042__baseline_workflow.sql` | A | A-005 | schema | `workflow_templates`, `workflow_stages`, `ticket_stage_transitions` |
| 4 | `V20260805_1106__baseline_masters_ops.sql` | A | A-007 | schema | `task_types`, `priorities`, `statuses`, `workflow_transitions`, `sla_policies`, `holidays`, `resource_leaves`, `notification_templates`, `notifications`, `chat_threads`, `chat_participants`, `chat_messages`, `audit_logs` |
| 5 | `V20260805_1107__immutability_triggers.sql` | A | A-008 | guard | The append-only triggers: no-UPDATE / no-DELETE on history and effort logs, seal-only on stage transitions |
| 6 | `V20260805_1108__generated_columns_and_indexes.sql` | A | A-009 | schema | `pcd_open`, `current_ticket_id`, the `FULLTEXT` index |
| 7 | `V20260805_1530__baseline_clients_content.sql` | A | A-006 | schema | `clients`, `client_contacts`, `client_projects`, `ticket_comments`, `ticket_attachments`, `email_log`, `import_batches` |
| 8 | `V20260806_0900__seed_roles_permissions.sql` | B | B-001 | **seed** | 6 roles · 18 permissions · the §2 grant matrix |
| 9 | `V20260807_1000__seed_task_types_priorities.sql` | B | B-002 | **seed** | 11 task types · 4 priorities |
| 10 | `V20260807_1030__fix_support_role_code.sql` | B | B-001 | correction | `roles`: `SUPPORT_DESK` → `SUPPORT` |
| 11 | `V20260807_1100__seed_statuses_transitions.sql` | B | B-003 | **seed** | 8 statuses · 72 transition rows |
| 12 | `V20260807_1500__email_suppressions.sql` | D | D-034 | schema | `email_suppressions` |
| 13 | `V20260807_1700__seed_workflow_templates_stages.sql` | B | B-004 | **seed** | 3 templates · 18 stages |
| 14 | `V20260808_1000__chat_project_channels_and_message_lifecycle.sql` | D | D-050 | schema | Project channels; the message edit window and tombstones |
| 15 | `V20260808_1200__chat_message_search.sql` | D | D-053 | schema | `FULLTEXT` on `chat_messages.body` |
| 16 | `V20260808_1400__fix_workflow_transitions_support_role_code.sql` | B | B-003 | correction | `workflow_transitions`: 13 rows, `SUPPORT_DESK` → `SUPPORT` |
| 17 | `V20260808_1630__working_calendar.sql` | B | B-023 | schema + **seed** | `working_calendar` — the weekly-off pattern and working-day bounds, plus the one row it is constrained to hold |
| 18 | `V20260810_0930__holidays_unique_org_wide.sql` | B | B-023 | correction | `holidays`: `uq_holidays` rebuilt over a NULL-free `project_scope`, so two org-wide holidays cannot share a date |
| 19 | `V20260810_1015__notification_delivery_log.sql` | D | D-046 | schema | `notifications.delivered_at` + `ix_notifications_undelivered` — what was shown, as distinct from what was read |
| 20 | `V20260810_1420__notification_preferences.sql` | D | D-042 | schema | `notification_preferences` — sparse overrides only; the default lives in code |
| 21 | `V20260810_1620__stage_sla_alerts.sql` | D | D-023 | schema | `stage_sla_alerts` — the once-per-segment claim, kept outside the append-only chain |
| 22 | `V20260810_1810__sla_prebreach_alerts.sql` | D | D-021 | schema | `sla_prebreach_alerts` — once per ticket **per cycle**, so a reopen re-arms the warning |
| 23 | `V20260810_1930__stale_ticket_nudges.sql` | D | D-022 | schema | `stale_ticket_nudges` — one row per ticket, updated in place; a nudge repeats where a warning does not |
| 24 | `V20260810_2040__l2_escalations.sql` | D | D-024 | schema | `l2_escalations` — the second-level claim; L1 needs none, `tickets.is_delayed` already records it |

<!-- load-order:end -->

`SeedManifestTest` parses the block between those two markers and fails the
build if it does not match this directory exactly — every file listed, nothing
listed that does not exist, and in ascending version order. **A new migration
without a row here is a build failure, not a review comment.**

---

## 2. Why the order is what it is

Flyway will happily run a seed file before the data it depends on exists. These
are the edges that hold today — break one and the failure is a silently empty
table, not an error.

- **Schema before seed.** Rows 1–7 create every table rows 8–17 write into,
  except row 17, which creates and seeds its own in one file — a singleton
  config row is the table's initial state, not a separate load step.
  Row 7 carries a later timestamp than rows 5–6 on purpose: `clients` and
  `email_log` have FKs into `sla_policies` and `notification_templates`, both
  created by row 4.
- **Row 8 before rows 9, 11, 13.** Roles come first because the three later
  seeds all name role codes. Rows 11 and 13 store them as *plain strings* with
  no FK, so the dependency is real but unenforced by the database.
- **Row 9 before row 13.** Templates map to task types.
- **Row 11 before row 13.** Stages and statuses are different axes (§3 — a
  ticket can be `IN_PROGRESS` while sitting in the QA stage), but the ribbon's
  stage seed reads the status vocabulary, so statuses land first.
- **Corrections run after what they correct**, which is what the timestamp
  gives for free — and is also the trap in §3.

Seeds are **idempotent by position, not by statement**. They are plain
`INSERT`s, not upserts; Flyway runs each exactly once and that is the only
thing stopping a duplicate. Never re-run one by hand against a database that
already has it.

---

## 3. Correction history

A correction is a **new** migration. Applied files are checksummed — editing
one breaks every other developer's database, and CI's migration-guard job
rejects it.

The trap this register exists to expose:

> A correction fixes a value. A seed file queued **behind** it writes the old
> value straight back. Both files are individually correct. Only the order is
> wrong, and only a list like the one above shows it.

| Correction | Undone by | Repaired by |
|---|---|---|
| Row 10 — `roles.code` → `SUPPORT` | Row 11, which hardcoded `'SUPPORT_DESK'` into 13 `workflow_transitions` rows | Row 16 |

Row 18 is a different kind of correction — not a value written back by a later
file, but a constraint that never held what it appeared to. `uq_holidays
(holiday_date, project_id)` reads as "one holiday per date per scope", and for
project-scoped rows it is. For **org-wide** rows, where `project_id IS NULL`,
MySQL compares NULLs as distinct and the index permits unlimited duplicates.
Nothing failed; the second insert simply succeeded. Found by calling the
endpoint against a real database rather than by reading the DDL, which is the
only way this class of defect surfaces.

Row 13, written later and aware of the rename, seeded `owner_role` correctly —
which is why the defect sat in exactly one table.

Neither column has an FK to `roles.code`, so neither would have failed loudly.
`SeedDataIT` now asserts referential integrity across those unenforced string
references; that is the standing guard, not this paragraph.

---

## 4. Adding a migration

1. Name it `V<YYYYMMDD>_<HHMM>__<snake_case>.sql`. Timestamps make collisions
   impossible; sequential numbering does not.
2. **Add a row to the table in §1.** Append it in version order.
3. If it seeds data, keep it to *your* stream's tables. One seed file per
   stream — never a shared one, or four developers conflict on every merge.
4. If a correction: state in the file header what it corrects and why the
   original is not being edited, then add a row to §3.
5. Migrations touching `tickets`, `ticket_history`, `ticket_effort_logs` or
   `ticket_stage_transitions` need **Stream A's review** — the append-only
   guarantees live there.

Before adding a seed that references another stream's data, read §2. If your
reference is a plain string rather than an FK, the database will not catch you
getting it wrong — add an assertion to `SeedDataIT` instead.

---

## 5. Seed data is not fixture data

Everything in §1 is **seed data**: reference rows the product cannot boot
without — roles, statuses, task types, workflow templates. It belongs in every
environment, production included.

**B-007's 200-ticket fixture corpus is not that.** It is dev and test data, and
it must never reach production.

`spring.flyway.locations` is a single `classpath:db/migration` in both `api` and
`worker`, so **nothing currently separates the two** — a fixture migration
dropped into this directory would ship. B-007 needs its own location
(`db/fixtures`) enabled per profile rather than another row above.

Flagged here rather than solved here: B-007 is the task that has to choose, and
this is the file where the choice becomes visible.

---

## 6. Related

- [`README.md`](README.md) — directory rules (Stream A)
- `docs/TEAM-PLAN.md` §7.1, §7.3 — migration and seed conflict rules
- `docs/streams/STREAM-B-MASTERS.md` — B-001 … B-008
- `SeedManifestTest` / `SeedDataIT` — `backend/domain/src/test/java/…/seed/`
