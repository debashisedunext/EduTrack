-- =====================================================================
-- B-106 · client requirements become rows somebody can work through.
--
-- Widens `ob_client_requirements`: the plain `body` becomes the pair
-- `body_html` / `body_text` PLAN.md §3.9 requires of every rich-text
-- field, and the row gains a title, a met flag and its evidence.
--
-- Source:  docs/streams/STREAM-B-MASTERS.md B-106 — "structured rows
--            plus rich text through `api/text/`'s allow-list"
--          docs/PLAN.md §3.9 — the allow-list sanitiser, and the
--            `body_html` / `body_text` pair every field it governs has
--          docs/Onboarding-Module-Plan.md §9 (OB-05)
--
-- WHY THIS TABLE ALREADY EXPECTED THIS MIGRATION.
-- V20260903_1210's own note argues the table into existence against a
-- JSON column on `ob_clients`, and the argument it gives is exactly
-- what is being cashed in here: "a JSON array cannot be indexed,
-- ordered or later given an `is_met` flag without rewriting every row".
-- That flag is added below. Nothing about the shape is new; the row
-- was created with room for it.
--
-- WHY `is_met` IS THREE COLUMNS AND NOT ONE.
-- The same argument V20260907_1130 makes about consent, one table
-- over, and it is the same class of fact: a bare boolean records that
-- somebody, at some point, decided a requirement was satisfied, and
-- cannot answer WHEN or ON WHOSE WORD. Those are the two questions a
-- disputed go-live turns on — "you signed this off in March" — and
-- neither can be reconstructed from a `1`. `ck_ob_client_requirements_met`
-- makes the flag and its stamp move together, so a bare `true` is
-- refused by the column and not only by the service, and un-meeting a
-- requirement clears both rather than leaving a stamp beside a `0`.
--
-- Unlike consent this is NOT given a journal table. Consent is
-- evidence about a person outside the organisation who may later
-- dispute it; a requirement being ticked and unticked while the work is
-- in flight is ordinary editing by the people doing it, and the row's
-- current state plus its `updated_by` is the whole of what anybody has
-- asked to know. If that turns out to be wrong, the journal is a new
-- table and not a change to this one.
--
-- WHY MEDIUMTEXT AND NOT TEXT.
-- §3.9 asked for MEDIUMTEXT and the ticket baseline created TEXT.
-- `CommentSanitizer`'s class note records why that mattered and what it
-- cost: sanitising makes strings LONGER — a bare `&` leaves as `&amp;`
-- — so 20 000 legal SUBMITTED characters can be five times that once
-- escaped, and `TEXT` is 65 535 BYTES against utf8mb4's up-to-four per
-- character. It could not repair the column, which was already applied,
-- so it enforces §3.9's bound over the SANITISED value as well.
--
-- Worth being exact about what that leaves, because the honest version
-- is narrower than the alarming one: with the bound applied after
-- sanitising, the stored value is at most 20 000 UTF-16 units, which is
-- at most 60 000 bytes of utf8mb4 — inside TEXT. So the ticket column
-- is not silently truncating today. What it is doing is depending on a
-- SERVICE check to stay inside a COLUMN limit, and the two have no
-- relationship: raise §3.9's 20 000, or reach the column through a
-- caller that skips the service, and the column truncates mid-tag and
-- stores markup that will never parse again.
--
-- This column is new, so it is created the way §3.9 asked. The service
-- still checks the bound — the bound is a rule about what a person
-- should be asked to read, not a defence of the column — but the two
-- are now independent, which is the whole difference.
--
-- WHY THE RENAME RATHER THAN A SECOND COLUMN.
-- `body` already holds the plain text of every requirement B-101's
-- corpus and B-102's wizard have written. `body_text` is what that
-- column IS under §3.9's vocabulary — the plain-text projection kept
-- beside the markup so neither search nor a mail body has to derive it
-- at read time. Adding `body_text` beside `body` would leave two
-- columns meaning one thing, which is the drift the pair exists to
-- prevent. `CHANGE COLUMN` keeps every row.
--
-- Applied: PLAN.md §3.1 PostgreSQL -> MySQL translation
--            TIMESTAMPTZ -> DATETIME(6), stored UTC
--            BOOLEAN     -> TINYINT(1)
--          utf8mb4 / utf8mb4_0900_ai_ci.
--
-- Stream A review is NOT required: nothing here touches `tickets`,
-- `ticket_history`, `ticket_effort_logs` or `ticket_stage_transitions`
-- (CLAUDE.md, database migrations).
-- =====================================================================


-- ---------------------------------------------------------------------
-- `body` -> `body_text`, widened.
--
-- Widened to match `body_html` rather than because the projection is
-- at risk today — it is bounded by the same 20 000 and is always the
-- shorter of the two. The point is that the pair stays a pair: a
-- projection with a tighter limit than the document it projects is a
-- search index that disagrees with what it indexes, and the difference
-- would appear only for the longest requirement anybody ever wrote.
-- ---------------------------------------------------------------------
ALTER TABLE ob_client_requirements
  CHANGE COLUMN body body_text MEDIUMTEXT NOT NULL;


-- ---------------------------------------------------------------------
-- The markup, the label, and the met evidence.
--
-- `body_html` arrives NULL-able so the backfill below has somewhere to
-- write; it is tightened to NOT NULL immediately afterwards. Adding it
-- NOT NULL with no default would be refused outright, and adding it
-- with a `DEFAULT ('')` would leave every historical requirement with
-- an empty document beside a non-empty plain text — a pair that
-- disagrees is worse than one that is missing, because nothing looks
-- wrong.
--
-- `title` is NULL-able and stays that way. A requirement captured in
-- the OB-04 wizard is a sentence somebody typed into a textarea; the
-- label is what OB-05's list shows once the row is being worked
-- through, and demanding one at boarding would be demanding it at the
-- moment nobody has it.
--
-- No index on `is_met`. A client's requirements are a handful and are
-- always read as a set through `ix_ob_client_requirements_client`;
-- an index over a two-valued column on a table read one client at a
-- time would be write cost for no read.
-- ---------------------------------------------------------------------
ALTER TABLE ob_client_requirements
  ADD COLUMN title      VARCHAR(200) NULL       AFTER sequence,
  ADD COLUMN body_html  MEDIUMTEXT   NULL       AFTER title,
  ADD COLUMN is_met     TINYINT(1)   NOT NULL DEFAULT 0 AFTER body_text,
  ADD COLUMN met_at     DATETIME(6)  NULL       AFTER is_met,
  ADD COLUMN met_by     BIGINT       NULL       AFTER met_at,
  ADD COLUMN updated_by BIGINT       NULL       AFTER created_by,
  ADD KEY ix_ob_client_requirements_met_by (met_by),
  ADD KEY ix_ob_client_requirements_updated_by (updated_by),
  ADD CONSTRAINT fk_ob_client_requirements_met_by
    FOREIGN KEY (met_by) REFERENCES users (id),
  ADD CONSTRAINT fk_ob_client_requirements_updated_by
    FOREIGN KEY (updated_by) REFERENCES users (id);


-- ---------------------------------------------------------------------
-- Backfill: every stored requirement is one paragraph of escaped text.
--
-- Escaped, not wrapped. The rows written before this migration were
-- never markup and were never sanitised — `insertRequirements` trimmed
-- them and stored them — so a `<` in one of them is a literal
-- less-than that somebody typed, and copying it into `body_html`
-- unescaped would turn a historical row into markup nobody wrote. The
-- three replacements are ordered `&` first for the usual reason: doing
-- it last would re-escape the ampersands the other two just produced.
--
-- `<p>` rather than nothing, because the value has to be a document
-- the renderer and `RichTextSanitizer.toPlainText` both read the same
-- way — a bare text node round-trips, but every other field §3.9
-- governs stores a block and the projection is block-aware.
-- ---------------------------------------------------------------------
UPDATE ob_client_requirements
   SET body_html = CONCAT('<p>',
                     REPLACE(
                       REPLACE(
                         REPLACE(body_text, '&', '&amp;'),
                       '<', '&lt;'),
                     '>', '&gt;'),
                   '</p>')
 WHERE body_html IS NULL;

ALTER TABLE ob_client_requirements
  MODIFY COLUMN body_html MEDIUMTEXT NOT NULL;


-- ---------------------------------------------------------------------
-- The met flag and its stamp move together.
--
-- Added after the columns rather than with them, V20260907_1130's
-- reason: MySQL validates a CHECK against the rows already present, and
-- a constraint added in the same statement as its columns is checked
-- against whatever the DEFAULT produced. Here the default is 0 and
-- every row would pass — but the ordering is the habit that keeps
-- working when the default is not so convenient.
--
-- `met_by` is deliberately NOT in the constraint. It is NULL-able and
-- has to stay so: B-126 gives the client their own login, and a
-- requirement the client themselves confirms is attributable to no
-- staff user. Inventing one would be a false attribution on the column
-- whose only job is attribution — `ob_client_contacts.whatsapp_opt_in_by`
-- makes the identical call one table over. The stamp is what the
-- constraint holds, because "when" is answerable in every case.
-- ---------------------------------------------------------------------
ALTER TABLE ob_client_requirements
  ADD CONSTRAINT ck_ob_client_requirements_met
    CHECK ((is_met = 0 AND met_at IS NULL) OR (is_met = 1 AND met_at IS NOT NULL));
