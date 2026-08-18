-- =====================================================================
-- B-039 · S-13 tab 1 — statuses.category
--
-- Table altered: statuses (one column, eight rows backfilled)
-- Source: blueprint §7.4, S-13 tab 1 — "status list, categories
--         (To-do / In progress / Done), allowed-transition matrix per role"
--
-- WHY A COLUMN AND NOT A DERIVATION
--
-- The obvious reading is that this is already in the table: `is_open`
-- and `is_terminal` between them look like they say where a status sits.
-- They do not, and the seed proves it. NEW and REOPENED are To-do;
-- ON_HOLD, AWAITING_INFO and REWORK are In progress. All five carry
-- is_open = 1 and is_terminal = 0 — identical on both columns, three
-- categories apart. Nothing in the existing schema separates "nobody has
-- started this yet" from "somebody started it and then stopped".
--
-- That distinction is the whole point of the category: it is the axis a
-- board groups by and the one a "not started after N days" figure counts
-- on. Deriving it would have to hard-code the eight seeded codes in
-- Java, which puts the master's own vocabulary back in the application —
-- the thing this screen exists to take out of it.
--
-- WHY VARCHAR + CHECK RATHER THAN ENUM
--
-- PLAN.md §3.1's standing substitution. A MySQL ENUM reorders its
-- ordinal set on every ALTER and compares by ordinal, so adding a fourth
-- category later silently renumbers the three already stored. The CHECK
-- gives the same refusal with none of that, and MySQL 8.4 enforces it
-- (8.0.16+), unlike the parse-and-ignore behaviour older guidance warns
-- about.
--
-- THE BACKFILL IS A JUDGEMENT, NOT A LOOKUP
--
-- Blueprint §3.2's lifecycle diagram names no categories, so the mapping
-- below is reasoned from what each status means to somebody reading a
-- board. Stated row by row rather than as a bulk UPDATE with a CASE, so
-- each call is reviewable on its own line:
--
--   NEW           TODO         raised, nobody has picked it up
--   REOPENED      TODO         a fresh cycle, and cycle 2 starts unstarted
--                              exactly as cycle 1 did. This is the row that
--                              cannot be derived: is_open = 1 and
--                              is_terminal = 0, identical to ON_HOLD
--   IN_PROGRESS   IN_PROGRESS
--   ON_HOLD       IN_PROGRESS  paused is not unstarted — the work exists and
--                              somebody owns it. Putting it in TODO would
--                              let a stalled ticket hide in the backlog
--                              column, which is the opposite of what §4A.7's
--                              "no movement for 3 working days" nudge is for
--   AWAITING_INFO IN_PROGRESS  same: blocked on the reporter, not unstarted
--   REWORK        IN_PROGRESS  bounced back by QA. Work in hand, iteration 2
--   RESOLVED      DONE         the work is claimed complete. DONE is about
--                              the work, not about the ticket record — the
--                              ticket stays open (is_open = 1) until sign-off,
--                              and that is why category is not is_open renamed
--   CLOSED        DONE
--
-- NOT NULL with no default, added in two statements: the column arrives
-- nullable, the eight rows are named, then the constraint lands. A
-- DEFAULT would survive this file and let a ninth status be inserted
-- with a category nobody chose.
-- =====================================================================


-- 1. The column, nullable for the length of this file only.
ALTER TABLE statuses
  ADD COLUMN category VARCHAR(20) NULL AFTER name;


-- 2. The eight seeded rows, one statement each.
UPDATE statuses SET category = 'TODO'        WHERE code = 'NEW';
UPDATE statuses SET category = 'TODO'        WHERE code = 'REOPENED';
UPDATE statuses SET category = 'IN_PROGRESS' WHERE code = 'IN_PROGRESS';
UPDATE statuses SET category = 'IN_PROGRESS' WHERE code = 'ON_HOLD';
UPDATE statuses SET category = 'IN_PROGRESS' WHERE code = 'AWAITING_INFO';
UPDATE statuses SET category = 'IN_PROGRESS' WHERE code = 'REWORK';
UPDATE statuses SET category = 'DONE'        WHERE code = 'RESOLVED';
UPDATE statuses SET category = 'DONE'        WHERE code = 'CLOSED';


-- 3. Anything this file did not name is a row that arrived after B-003
-- and before this migration — impossible today, since nothing has ever
-- served this table. Categorised by the same rule the eight follow, so
-- the NOT NULL below cannot fail on a database we have not seen.
UPDATE statuses
   SET category = CASE WHEN is_terminal = 1 OR is_open = 0 THEN 'DONE'
                       ELSE 'IN_PROGRESS' END
 WHERE category IS NULL;


-- 4. Now it is mandatory, and constrained.
ALTER TABLE statuses
  MODIFY COLUMN category VARCHAR(20) NOT NULL,
  ADD CONSTRAINT ck_statuses_category
    CHECK (category IN ('TODO', 'IN_PROGRESS', 'DONE'));


-- 5. The board groups by this and orders by seq, and S-13 tab 1 reads
-- the whole table in category order. Eight rows do not need an index for
-- speed; it is here so the grouping stays cheap if an organisation grows
-- the vocabulary, which S-13 exists to let them do.
CREATE INDEX ix_statuses_category ON statuses (category, seq);
