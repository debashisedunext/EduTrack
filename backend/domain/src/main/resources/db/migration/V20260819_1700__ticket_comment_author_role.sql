-- =====================================================================
-- C-032 · `ticket_comments.author_role` — the role half of §4B.5's stamp.
--
-- Needs Stream A's review before merge (CLAUDE.md — a migration touching a
-- table another stream reads; `ticket_comments` is none of the four
-- append-only tables, following V20260818_1030's precedent for the same
-- flag on `tickets.pct_complete`).
--
-- `CommentDtos.CommentDto.authorRole` has read the author's CURRENT role
-- since C-029 — `roles.get(row.getAuthorId())` — which is a stand-in the
-- DTO's own javadoc has named since that task: §4B.5 asks for the role held
-- AT THE TIME OF WRITING, and the two differ only when someone's role
-- changes after they commented, which is rare and exactly the case the
-- stamp exists for. `cycle_no`, `stage_code` and `iteration_no` already
-- solved this for the ribbon position (baseline migration, C-032); this is
-- the same stamp, one column later.
--
-- VARCHAR(20) NULL, no FK to `roles` and no CHECK against RoleCode's six
-- values — `workflow_transitions.role_code` and `notification_templates
-- .recipients` are this schema's own precedent for a role held as a plain
-- string rather than a foreign key, and `RolePermissions.ROLE_CODES` is
-- already where the vocabulary is enforced on the write path.
--
-- NULL and never backfilled, on `iteration_no`'s own reasoning one column
-- over: every comment posted before this migration was written with no
-- stamp captured, and there is no honest value to invent for it now — the
-- CommentDto fallback to the author's current role (unchanged) is what a
-- pre-existing row still renders.
-- =====================================================================

ALTER TABLE ticket_comments
  ADD COLUMN author_role VARCHAR(20) NULL
    COMMENT 'C-032. The authors role AT THE TIME OF WRITING, copied never joined - same rule as cycle_no/stage_code/iteration_no beside it. Null on any row posted before this column existed.'
    AFTER author_id;
