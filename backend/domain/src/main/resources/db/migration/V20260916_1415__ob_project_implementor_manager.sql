-- =====================================================================
-- ob_projects.implementor_manager_user_id — the third person on an
-- engagement.
--
-- Source: "now when we create a project then we have to add one more
--         field which is of the implementor manager so along with sales
--         person implementor we have to add a new field implementor
--         manager."
--
-- WHY A COLUMN RATHER THAN A LOOKUP THROUGH THE IMPLEMENTOR.
-- The obvious alternative is to derive it — read the implementor's
-- reporting manager off `users` and call that the project's implementor
-- manager. It is wrong for two separate reasons, and either alone would
-- be enough.
--
-- First, it is not the same fact. A reporting line says who somebody
-- answers to on an org chart; this says who is accountable for *this
-- engagement*. A senior implementor may report to a head of delivery and
-- still have a different manager overseeing one strategic client, and
-- the derivation has no way to say so.
--
-- Second, it would be retroactive. A derived value changes for every
-- project the moment somebody edits a reporting line, including projects
-- that finished last year — so the answer to "who was managing this in
-- March" would silently become today's org chart. `sales_person_id` sits
-- on this table for exactly that reason rather than being read off the
-- client, and this follows it.
--
-- WHY NULLABLE, WHEN THE CREATE FORM MARKS IT REQUIRED.
-- The same split the other two already live under, and ObProjectDtos
-- spells out the reasoning: `ObProjectCreateRequest` requires all three
-- because a project born without them is a form somebody dropped on the
-- floor, while `ObProjectUpdateRequest` accepts null for all three
-- because a manager leaving mid-project is an ordinary thing that must
-- be recordable. NOT NULL here would make the second impossible, and it
-- would also fail this migration outright: every existing row has no
-- value and there is none to backfill that would not be invented.
--
-- NO BACKFILL, DELIBERATELY.
-- The migration that created this table left `implementor_user_id` NULL
-- on every backfilled row on the same principle — "it is genuinely
-- unknown for every row written before today, and guessing it from a
-- journey's step owner would attribute the whole engagement to whoever
-- happened to own step one". Guessing a manager from a reporting line
-- would be the same mistake one level up. The grid draws an em dash for
-- an unset person, which is the honest answer.
--
-- NONE OF THE FOUR PROTECTED TABLES IS TOUCHED.
-- =====================================================================

ALTER TABLE ob_projects
  ADD COLUMN implementor_manager_user_id BIGINT NULL
    COMMENT 'Who is accountable for this engagement above the implementor. Not derived from the implementor''s reporting line — a different fact, and a derived one would rewrite history every time an org chart changed.'
    AFTER implementor_user_id,
  ADD CONSTRAINT fk_ob_projects_implementor_manager
    FOREIGN KEY (implementor_manager_user_id) REFERENCES users (id);

-- The same (person, start_date) shape as ix_ob_projects_implementor and
-- ix_ob_projects_sales_person. Added as its own statement: MySQL builds
-- the FK's supporting index during the ALTER above, and doing both in
-- one statement makes it harder to read which index exists because
-- somebody chose it and which exists because a constraint needed it.
ALTER TABLE ob_projects
  ADD KEY ix_ob_projects_implementor_manager (implementor_manager_user_id, start_date);
