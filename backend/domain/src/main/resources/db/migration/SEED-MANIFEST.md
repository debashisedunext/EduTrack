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
| 20 | `V20260810_1120__password_reset_tokens.sql` | A | A-027 | schema | `password_reset_tokens` — hashed at rest, 30-minute TTL, single use via `used_at` |
| 21 | `V20260810_1420__notification_preferences.sql` | D | D-042 | schema | `notification_preferences` — sparse overrides only; the default lives in code |
| 22 | `V20260810_1620__stage_sla_alerts.sql` | D | D-023 | schema | `stage_sla_alerts` — the once-per-segment claim, kept outside the append-only chain |
| 23 | `V20260810_1810__sla_prebreach_alerts.sql` | D | D-021 | schema | `sla_prebreach_alerts` — once per ticket **per cycle**, so a reopen re-arms the warning |
| 24 | `V20260810_1930__stale_ticket_nudges.sql` | D | D-022 | schema | `stale_ticket_nudges` — one row per ticket, updated in place; a nudge repeats where a warning does not |
| 25 | `V20260810_2010__import_batch_status_vocabulary.sql` | B | B-030 | correction | `import_batches.status`: the column's private vocabulary replaced by the contract's `QUEUED\|RUNNING\|COMPLETED\|FAILED`, with a `CHECK` so they cannot diverge again |
| 26 | `V20260810_2040__l2_escalations.sql` | D | D-024 | schema | `l2_escalations` — the second-level claim; L1 needs none, `tickets.is_delayed` already records it |
| 27 | `V20260811_1030__password_policy.sql` | A | A-028 | schema | `password_history` + `users.password_changed_at` — the no-reuse rule and the optional expiry clock |
| 28 | `V20260811_1100__ping_pong_flags.sql` | D | D-025 | schema | `ping_pong_flags` — one row **per cycle**, holding the highest iteration already announced, so a further bounce is new information and a reopen re-arms |
| 29 | `V20260811_1400__unassigned_ticket_alerts.sql` | D | D-026 | schema | `unassigned_ticket_alerts` — one row per ticket, cleared the moment somebody picks it up, so a later reassignment away alerts immediately |
| 30 | `V20260811_1420__two_factor_totp.sql` | A | A-029 | schema | `users.totp_secret`/`totp_enabled`/`totp_confirmed_at` + `totp_recovery_codes` — the enrol-then-confirm states and the way back in |
| 31 | `V20260811_1520__resource_profile_fields.sql` | B | B-011 | schema | `users`: `date_of_joining`, `avatar_url`, `location`, `weekly_off`, `skills` — the five S-08 fields A-003 had no reader for. Plus `ck_project_members_role`, which fixes the vocabulary of `role_in_project` at its first writer |
| 32 | `V20260811_1600__push_subscriptions.sql` | D | D-045 | schema | `push_subscriptions` — one row per browser that granted permission; unique on the **endpoint alone**, so a second user on a shared machine takes the subscription over rather than inheriting somebody else's alerts |
| 33 | `V20260813_1030__ticket_status_requests.sql` | D | D-055 | schema | `ticket_status_requests` — one open ask per manager per ticket (a generated column plus a unique key, A-009's `pcd_open` idiom), and the answered row carries the wait in **working** minutes, stamped once so a holiday added later cannot restate it |
| 34 | `V20260813_1420__project_master_fields.sql` | B | B-016 | schema | `projects`: `description` and `auto_assign_rule`, the two S-10 fields with no column; `ck_projects_status` fixes the vocabulary at `ACTIVE\|ON_HOLD\|CLOSED` at its first writer, before the javadoc's fourth and fifth values could be written by anything |
| 35 | `V20260813_1810__project_member_allocation.sql` | B | B-017 | schema | `project_members.allocation_pct` — the S-10 Team tab field the contract has promised since D-001 with no column behind it. **Nullable, and the contract's `default: 100` dropped**: a backfill would read 300% for every fixture resource on three projects, and B-061 could not tell that from a stated figure |
| 36 | `V20260814_1120__project_settings.sql` | B | B-019 | schema | `project_task_types` + `projects.mandatory_fields` — the two S-10 Settings-tab fields B-016 deliberately left out. **No rows in the join table means every active task type is allowed, not none**: every project predates the table, so the other reading would have stopped ticket creation everywhere the migration ran, and a backfill would have frozen each project's list at the eleven types that existed today. `ck_projects_mandatory_fields` constrains **shape and not vocabulary**, unlike `ck_projects_status`, because the codes track Stream C's create form |
| 37 | `V20260814_1530__one_correction_per_entry.sql` | A | A-043 | schema | `UNIQUE` on `corrects_entry_id` for `ticket_history` and `ticket_effort_logs`, replacing the plain index. MySQL allows any number of NULLs in a unique index, so this constrains exactly the correction rows: a second reversal of one entry collides, while every ordinary append is untouched. Two reversals of a `+8` net to `-8` and the §4A.4 grid reports negative hours for a stage somebody worked |
| 38 | `V20260814_1900__chain_anchors.sql` | A | A-044 | schema | `chain_anchors` — row count and head hash per ticket per chain, so tail truncation becomes a comparison. Append-only makes the count monotonic; `trg_chain_anchor_monotonic` refuses one that decreases and `trg_chain_anchor_no_delete` refuses removal, so laundering a deletion means defeating a second trigger on a second table. Named in `apply-app-grants.sql` to withhold `DELETE`, which the default branch would grant |
| 39 | `V20260814_2130__summary_tables.sql` | A | A-050 | schema | `daily_ticket_stats` and `resource_daily_stats` — the pre-aggregated rows the dashboard reads instead of counting, keyed `(date, project)` and `(date, user)`. Each row carries both **flow** (what happened that day) and **stock** (what was true at end of day), because stock does not aggregate over days and widgets 2, 5, 12 and 16 would otherwise need the live `COUNT(*)` CLAUDE.md forbids. `wip_by_stage` is declared NULL now rather than added with A-058: a point-in-time column cannot be backfilled |
| 40 | `V20260815_1100__notification_template_recipients.sql` | B | B-022 | schema + seed | `notification_templates.recipients`, and the first rows the table has ever held — one per (event, channel) pair blueprint §11 ticks, for every event `NotificationEvent` declares. `recipients` is a `NotificationRecipient` vocabulary rather than a join onto `roles`, because eight of §11's ten "To" values are positions relative to a ticket. **The channel vocabulary is `IN_APP\|EMAIL\|PUSH` and supersedes A-007's `POPUP\|BELL\|EMAIL` column comment** — everything that runs keys on `NotificationChannel`, and the bell is not a channel. `MAIL_DELIVERY_FAILED` has no email row on purpose |
| 41 | `V20260815_1140__attachment_settings.sql` | C | C-027 | schema + **seed** | `attachment_settings` — §4B.4's three attachment caps, administrator-editable at runtime instead of only at deploy. Exactly one row, `id = 1`, enforced by a `CHECK`; seeded with the blueprint's own 10 MB / 50 MB / 20 so the enforced values are unchanged the moment it runs. Creates and seeds in one file for row 17's reason — a singleton config row is the table's initial state, not a separate load step |
| 42 | `V20260816_1030__client_master_s33_fields.sql` | B | B-026 | schema | `clients`: `logo_url`, `billing_reference`, `billing_email`, `tags` — the four blueprint §4B.2 fields S-33's form needs and the table had no column for. Plus `ck_clients_status`, which lands at the column's **first writer of the third value**: §4B.2's Identity group names Active / Inactive / **Prospect** and the column has carried two of them unconstrained. `tags` is JSON constrained for **shape and not vocabulary**, `users.skills` and `projects.mandatory_fields` one table over. **`clients` is A-006's table — flagged for Stream A** |
| 43 | `V20260816_1230__type_counts.sql` | A | A-056 | schema | `daily_ticket_stats.type_counts` — the task-type breakdown §S-05's donut needs and A-050 did not build. **JSON rather than a column per type**, for exactly the argument A-050 made for `wip_by_stage`: `task_types` is a master an Admin extends (B-020), and a column set would need a migration every time somebody adds one. Shaped `{"3": 41}` — task_type_id to **open** count, matching `open_total` and the level columns beside it rather than counting creations, and keyed by id so renaming a type does not rewrite history. Existing rows stay NULL rather than backfilling to `{}`: an empty object would claim no tickets of any type were open that day, which is false for every historical row |
| 44 | `V20260816_1615__resource_in_progress.sql` | A | A-056 | schema | `resource_daily_stats.assigned_in_progress` — the middle segment of §S-05's widget 10, which A-050 left without a column. **Disjoint from `assigned_delayed`, which `assigned_critical` is not**: critical is a lens over the open set and the overlap is the point, but widget 10 *stacks* its three segments, so they must partition `assigned_open` or the bar's length double-counts every ticket that is both delayed and being worked. In progress is `status IN ('IN_PROGRESS','REWORK')` — not `ON_HOLD` or `AWAITING_INFO`, which are open and stopped. Backfills to 0 rather than NULL because this table is deleted and rewritten per day, so no row outlives its default |
| 45 | `V20260816_2020__sla_compliance.sql` | A | A-057 | schema | `daily_ticket_stats.sla_closed` / `sla_met` — §S-05 widget 14's denominator and numerator, neither of which A-050 built. **Flow, not stock, and deliberately not derived from `open_delayed`**: that counts what is late *now*, so a gauge fed from it would improve every time somebody closed an overdue ticket — late delivery registering as compliance rising. Two columns because a ticket with no `planned_close_date` has made no commitment and can neither meet nor breach one, so it is excluded from both halves rather than inflating the denominator. NULL until A-051 recomputes, like `type_counts` and unlike `assigned_in_progress`: this table is upserted, so `0` on a historical row would claim nothing with an SLA closed that day |
| 46 | `V20260817_1130__resource_variant_columns.sql` | A | A-062 | schema | `resource_daily_stats`: the four `assigned_aging_*` buckets and `assigned_due_today` / `assigned_due_next_7` — §S-05's developer dashboard is "widgets 1–6, 9, 12 scoped to `assignee = me`, plus My due today / this week", and A-056 refused widget 12 for a delivery role because this table had no aging columns, naming this task as the one that owed them. **The bucket edges are A-050's 0–2 / 3–7 / 8–30 / 31+, deliberately repeated rather than corrected towards §S-05's 0–2 / 3–5 / 6–10 / >10**: a Developer and their PM read two charts with the same four labels and the same drill-down links, and edges differing by a day between them produce figures that never reconcile with no way to see why. **Due is bounded below by the start of the day, so it cannot overlap `assigned_delayed`** — overdue work appearing under "due today" would overstate the one tile somebody plans their day around; `due_next_7` contains `due_today` because nesting is what the two labels claim. Seven rolling days rather than the calendar week, which would make the tile mean six days on a Monday and one on a Friday. Backfills to 0 rather than NULL for row 44's reason — this table is deleted and rewritten per day, so no row outlives its default |
| 47 | `V20260817_1615__import_mapping_presets.sql` | B | B-033 | schema | `import_mapping_presets` — §4B.3 step 3's "presets can be saved and reused for the next import". A new table, touching nothing. **Org-wide rather than per user, and a table rather than `localStorage`**, because "the *next* import" is next month, quite possibly from a reimaged laptop and by a different Admin: a preset records how another system's export is shaped, which is organisation knowledge and not a display preference. `mapping` is JSON constrained for **shape and not vocabulary** — `clients.tags` (row 42) and `users.skills`' argument, and which target fields are legal is a property of a Java class the API refuses against with a 422, not something a `CHECK` should hold a second copy of. `schema_key` is **not** a foreign key because there is no table of import schemas to point at: a schema is a Spring `@Component`, which is B-030's whole design and what makes B-038 one file. `UNIQUE (schema_key, name)` is what makes Save an upsert rather than an accumulator, and `created_by` is `ON DELETE SET NULL` — an Admin leaving takes their attribution, not the mapping the team still imports with. **Renamed from `V20260817_1130` on the rebase**: A-062's row 46 above took that exact version while this branch was open, and Flyway refuses two migrations at one version — the timestamp scheme makes a same-minute collision unlikely rather than impossible, and this is the first time it has happened |
| 48 | `V20260818_1030__ticket_pct_complete.sql` | C | C-036 | schema | `tickets.pct_complete` — S-21 Quick Update's progress slider, declared on the contract's `Ticket`, `QuickUpdateRequest` and `TicketPatchRequest` schemas since D-001 with no column behind any of them. `SMALLINT NOT NULL DEFAULT 0`, `CHECK (pct_complete BETWEEN 0 AND 100)` — zero backfills honestly, since nothing already in the table has been reported done through this field. **Touches `tickets` — flagged for Stream A**, row 42's precedent |
| 49 | `V20260818_1210__import_batch_reversal.sql` | B | B-037 | schema | `import_batches`: `reversed_at`, `reversed_by`, `reversed_rows`, `retained_rows` — blueprint §4B.3's closing validation rule and §17's mitigation for "Client Excel import silently corrupts the master": *every import writes an `import_batch` row so a bad import can be identified and reversed as a set*. B-035 wrote the row and stamped `clients.import_batch_id`; nothing had ever read either. **Four columns rather than a fifth `status` value**, which was the obvious shape and is wrong: `status` records how the *run* ended — `ImportBatchStatus` is explicit that COMPLETED is not a synonym for no rejections — and a reversal is a later fact about a run that already ended. Overwriting it collapses "completed with 6 rejections, later reversed" and "failed at row 314, later reversed" into one word, and leaves B-036's `error_report_key` belonging to a run whose status no longer explains why it has one. It also avoids dropping and recreating `ck_import_batches_status`, which B-030 added (row 25) precisely so the column's vocabulary and the contract's could not drift a second time — a constraint that must be rebuilt every time the lifecycle grows is a constraint on its way to being deleted. **`reversed_at` NULL is the whole state machine**: a boolean beside a timestamp would be two columns that can disagree, and "has this already been reversed?" is answered by the presence of the fact rather than by a flag about it. **`retained_rows` is stored rather than derived** because neither counter survives the operation it describes: `tickets.client_id` is RESTRICT, so a client the import created and which has since been named on a ticket is kept rather than destroyed — and once the others are deleted the `import_batch_id` pointing at this run goes with them, so an unreversed batch and a fully reversed one both `COUNT` zero. **No before-image table**, deliberately: an undo of the undo is a bigger promise than the blueprint makes and one nothing here could keep, since the delete cascades to `client_projects` and takes `client_contacts` with it. Restoring deleted master data is a backup's job. **`import_batches` is A-006's table — flagged for Stream A**, like V20260810_2010 (row 25) before it, though CLAUDE.md asks for no review since it is none of the three append-only tables |
| 50 | `V20260818_1500__audit_log_immutability.sql` | A | A-071 | schema | `trg_audit_no_update` / `trg_audit_no_delete` on `audit_logs`, plus `entity_ref` and `ix_audit_logs_recent`. **The decision V20260805_1106 deferred by name** — it created the table with a note saying immutability triggers were "a separate decision and a separate migration", and S-16's "export only, never editable" is what settles it. `audit_logs` now has four of the five layers `ticket_history` has and pointedly not the fifth: no hash chain, so tampering that first defeats the triggers is undetectable and a truncated tail has no `chain_anchors` to contradict it — A-075's external anchoring, not this. **The DELETE trigger means the table only ever grows**, and that is accepted rather than overlooked: there is no retention policy in the blueprint, and acquiring one by leaving DELETE available is the wrong order, so pruning becomes a DBA operation. `entity_ref VARCHAR(40)` because half the subjects in this product are ticket *codes* (`CRM-26-00347`) and `entity_id BIGINT` has nowhere to put one — the contract already types `AuditLogEntry.entityId` as a string. Not one of the four tables in CLAUDE.md's review rule; `audit_logs` is Stream A's own |
| 51 | `V20260818_1530__ticket_cycles_close_fields.sql` | C | C-040 | schema | `ticket_cycles`: `root_cause_category`, `client_verification_requested` — S-23's close dialog fields with nowhere to land. `resolution_summary` already existed (row 2); these two are the same per-cycle shape. Both nullable/defaulted and additive — no existing row changes. **Touches a ticket-lifecycle table — flagged for Stream A**, row 42/48's precedent |
| 52 | `V20260818_1720__status_categories.sql` | B | B-039 | schema | `statuses.category` — blueprint §7.4's S-13 tab 1 asks for "status list, **categories (To-do / In progress / Done)**, allowed-transition matrix per role", and the column did not exist. **It cannot be derived from `is_open` and `is_terminal`, which is the reason it is a column at all**: NEW and REOPENED are To-do while ON_HOLD, AWAITING_INFO and REWORK are In progress, and all five carry `is_open = 1, is_terminal = 0` — identical on both columns, three categories apart. Deriving it would have to hard-code the eight seeded codes in Java, putting the master's own vocabulary back into the application that S-13 exists to take it out of. `VARCHAR(20)` + `CHECK` rather than `ENUM`, PLAN.md §3.1's standing substitution — a MySQL `ENUM` compares by ordinal and renumbers its set on every `ALTER`, so a fourth category added later would silently renumber the three already stored. **NOT NULL with no `DEFAULT`**, added nullable → backfilled → constrained in one file, because a `DEFAULT` would outlive this migration and let a ninth status be inserted carrying a category nobody chose. The eight backfills are one statement each rather than a `CASE`, so each judgement is reviewable on its own line; the mapping is reasoned rather than cited, since §3.2's lifecycle diagram names no categories. **RESOLVED is DONE while `is_open` stays 1** — the category is about the work, not about the ticket record, which is the distinction that stops this column being `is_open` renamed. `statuses` is A-007's table and none of the four protected ones, so no Stream A review is required |
| 53 | `V20260818_1745__resource_import_batch.sql` | B | B-038 | schema | `users.import_batch_id` — the second registration's half of §4B.3's closing rule, *every import writes an `import_batch` row so a bad import can be identified and reversed as a set*. `clients` has carried this column since A-006 laid it down in row 7, whose own comment says `import_batches` is created first "because `clients.import_batch_id` points at it"; `users` never did, because until B-038 nothing imported them. **Without it B-038 is not a registration.** B-037 put `reverse` on the SPI precisely so this task would get reversal without writing any of it, and what only a registration can supply is *which rows this run created* — a question whose only durable answer is a stamp on the row. A Reverse button offered on a resource import that could delete nothing is worse than no button. **Stamped on INSERT only**, held by `ResourceImportSchema.upsert` and its tests rather than by DDL: nothing here can express "insert only", and a trigger refusing an UPDATE of one column on the identity table is an immutability guard Stream A should own if it is ever wanted. The rule is B-035's and B-037 depends on it — a run that merely corrected a department must not re-attribute that person to itself, and with no before image an update is not a thing a reversal could undo anyway. **RESTRICT rather than ON DELETE SET NULL**, matching `fk_clients_import_batch`: the batch row is the audit trail and is never deleted, not even by the reversal that fills in four of its columns, so a rule for that event would be a rule for something that does not happen. **Touches `users` — flagged for Stream A on B-011's precedent (row 31)**, though CLAUDE.md asks for no review since it is neither `tickets` nor one of the three append-only tables |
| 54 | `V20260818_2140__workflow_stage_deprecation.sql` | B | B-042 | schema | `workflow_stages`: `is_deprecated`, `deprecated_at` — blueprint §7.4's *"Stages used by live tickets can only be deprecated, never deleted, otherwise historical ribbons would break"*, which §17's risk table states a second time as a mitigation. B-040 built the whole of S-13 tab 2 with **no removal at all** rather than ship a delete this file would have had to take away; this is the column that lets the tab retire a stage. **A DELETE here breaks nothing and is still destructive, which is the reason the rule is a column rather than a constraint**: A-005 made `ticket_stage_transitions.to_stage` and `tickets.current_stage` plain `VARCHAR` holding the code with no foreign key onto this table, so removing a row cascades nothing, fails nowhere, and leaves every historical ribbon segment pointing at a definition that is gone while Stream D's §4A.7 stuck-in-stage scan quietly stops matching them — the same silent pair B-040 froze `stage_code` against, one verb further along. **Two columns, not one**: "when did we stop using this?" is not derivable, because the last hop into a stage records when it was last *used* rather than when it was *retired*, and those differ by exactly the interval that makes the question worth asking. `ck_workflow_stages_deprecation` keeps the flag and the timestamp from disagreeing, so no reader has to defend against a row no code path can produce. **NOT NULL with a `DEFAULT`, which is the opposite of row 51's call and deliberately so**: a category is a judgement with no correct initial value, while this is a state whose initial value is correct for every row — all 18 of B-004's stages are live, and so is every stage created after this file. `workflow_stages` is A-005's table and none of the four protected ones, so no Stream A review is required |
| 55 | `V20260819_0443__client_daily_stats.sql` | A | A-059 | schema | `client_daily_stats` — §S-05's widget 20 is "client-wise volume" and nothing summarised could answer it: A-050's two tables are keyed by project and by person, and a client is neither. **Keyed by `(stat_date, project_id, client_id)`, and the project column is the one it would be wrong without** — §2's row rule scopes every read by project, so a table keyed by client alone can express no scope filter at all and every PM would be served organisation-wide figures for work they cannot open a single ticket from. With both, a client's bar is the sum over the projects that caller can see, and two callers with different scopes get different bars for one client — each the honest answer to "this client's volume, within the work you can open". **Summing stock across projects is sound; across days it is not** — A-050's header states the second half, and the first is worth stating beside it, because a ticket belongs to exactly one project but exists on many days. `created` is what widget 20 reads; `closed` is a free extra SUM over rows the recompute already visits; **`open_total` is here now because it is stock and stock cannot be backfilled** — the wip_by_stage argument, and A-068's client report is a trend report, which is precisely the thing a hole ruins. Deliberately absent: SLA and resolution-time columns that report will also want, both flow, both derivable from immutable close timestamps whenever it lands. Tickets with `client_id IS NULL` are summarised nowhere — §4B.7 allows an internally-raised ticket, and there is no client whose volume it is — so this is a breakdown of client-attributed work rather than of all work, which is what "client-wise" asks for. **Rows are earned, so the refresh clears the day and rewrites it**, `resource_daily_stats`' argument reaching a mutable column instead of a missing history table: `tickets.client_id` is editable, and an upsert cannot retract the row it wrote for the old client, so one re-attributed ticket would be drawn twice under two names. None of the four protected tables is touched |
| 56 | `V20260819_0555__report_schedules.sql` | A | A-065 | schema + seed | `report_schedules`, `report_schedule_runs`, and the `SCHEDULED_REPORT` email template — §7.8's "All reports schedulable by email (daily/weekly/monthly)". **Two tables, because a standing instruction and one firing of it are different facts with different lifetimes**: the schedule can be edited and cancelled, while a run is what happened at 06:00 on a Tuesday and is what an auditor asks about — folding the last outcome onto the schedule row would keep exactly one answer and overwrite the rest. 🔴 **The scope is deliberately NOT stored, and that is the whole security design.** A report is scoped to whoever runs it (§2), and a schedule runs with nobody logged in, so the obvious move is to freeze the creator's role and projects onto the row. That is wrong in a way that never announces itself: roles change, and a PM moved to Developer would go on receiving project-wide figures every Monday from a row recording what they used to be — with the mail arriving on time being exactly what stops anybody noticing. Only `created_by` is kept, and the runner re-reads that user's *current* role and *current* project memberships on every run, so a demotion narrows the next email and deactivating a leaver stops it. **Recipients are stored as text rather than user ids** although they must resolve to active users: the report was addressed to a person at an address, and if that user is later deleted the history of who received last quarter's audit extract must not be erased by a foreign key — `email_log.to_email` makes the same argument one table over. **No date range is stored**, because the period is derived from the cadence — a stored window would freeze it and the second run would email a copy of the first, a failure indistinguishable from a working schedule until two files are compared. `report_schedule_runs` is append-only **by intent and not by trigger**, and says so rather than claiming a guarantee it does not have: it is none of CLAUDE.md's four hash-chained tables. Its `ck_report_schedule_runs_outcome` is the constraint worth naming — a SUCCEEDED row without a `storage_key` is a 500 waiting for the first person to click download, so the two are tied where they cannot drift. **The file is not in the email**: `storage_key` points at the object store and the mail carries a link to an authenticated download, `ImportReportStore`'s argument arriving somewhere it matters more — an attachment is an uncontrolled copy that outlives every permission change made afterwards, sitting in an inbox and in every relay between here and there. The seeded template uses `{{portal_url}}`, the merge tag this task adds, because until now a tag could only be filled from a ticket and **no mail that is not about a ticket could carry a working link at all** — the daily digest has the same gap and can now close it. None of the four protected tables is touched |
| 57 | `V20260819_1336__product_modules_and_where_it_happened.sql` | D | C-065 | schema + seed | `product_modules` (8 rows) and §7.5's four columns on `tickets`: `module_id`, `screen_name`, `feature`, `steps_to_generate`, plus `ix_tickets_module`. **ALTERs `tickets`, so it needs Stream A's review** (TEAM-PLAN §7.1) — nothing here implies mutation of an append-only table, and `tickets` itself is ordinarily mutable. **The eight rows are seed data and never a Java enum**, which PLAN.md §3.9 states outright: the ninth module is a row somebody inserts, not a migration and a deployment, and that is the whole reason this is a master rather than a `CHECK`. **`steps_to_generate` is `MEDIUMTEXT` rather than `TEXT`** on the same section's instruction — `TEXT` is 64 KB and a few screenshots pasted as data URIs exceed it, truncating silently into invalid markup; the 20 000-character bound belongs on the DTO where springdoc emits it into the contract, not on the column. **All four are nullable with no default**: §7.5 is explicit that a change request may span three modules and that a draft is saved before anybody knows the answer, so a `NOT NULL` would force a wrong value at the moment the truth is least known. **The foreign key carries no `ON DELETE` action, deliberately** — modules are deactivated rather than deleted, because a ticket raised against a since-retired module must still render its name, and the default `RESTRICT` is what makes deleting one impossible while a ticket still points at it. Carried on a task reassigned from Stream C to Stream D on 19 Aug, which is why the owner column reads D against a `C-` id |
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
