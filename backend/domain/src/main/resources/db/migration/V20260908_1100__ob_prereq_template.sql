-- =====================================================================
-- B-124 · Client Onboarding — the prerequisites master (OB-14)
--
-- Tables: ob_prereq_template_versions, ob_prereq_template_tasks,
--         ob_prereq_template_task_docs
-- Alters: ob_attachments (A-102) — a fourth owner arm. See §4 below.
--
-- Source:  docs/Onboarding-Module-Plan.md §4 (data model), §1.1 #2
--          (snapshot on instantiation), §5.3 (the hard gate), §9 OB-14
--          contracts/openapi.yaml — the `/onboarding/prereq-template*`
--          operations A-118 published; this file is built to them.
--
-- Applied: PLAN.md §3.1 PostgreSQL → MySQL translation (normative).
--          utf8mb4 / utf8mb4_0900_ai_ci on every table.
--
-- WHO IS WAITING ON THIS FILE:
--     B-125 per-client prerequisite instances · C-118 PrerequisiteGateService
--     C-121 CP-01..CP-04 (the portal's prerequisite screens)
--     A-118's `prereq-aging` report, declared unavailable until these exist
--
-- =====================================================================
-- §1. THE MASTER IS SINGULAR WHERE JOURNEY TEMPLATES ARE PLURAL
-- =====================================================================
--
-- `ob_journey_templates` (A-103) is keyed by product — one active version
-- *per product* — because a journey describes the delivery of a thing that
-- was bought. Prerequisites describe the client's own responsibilities,
-- which plan §4 makes the same set for everybody regardless of what they
-- bought. So there is one active version for the whole organisation, and
-- the contract's paths carry no id: `/onboarding/prereq-template` is the
-- resource and a version is a query parameter on it.
--
-- That difference is why the generated-column idiom below indexes the
-- generated column ALONE, where A-103 pairs it with `product_id`.
--
-- =====================================================================
-- §2. A VERSION HEADER TABLE, WHICH plan §4 DOES NOT LIST
-- =====================================================================
--
-- Plan §4 names `ob_prereq_template_tasks` and
-- `ob_prereq_template_task_docs` and stops there, leaving `version` to be
-- a column on the task rows. That does not survive contact with the
-- contract: `ObPrereqTemplate` carries `isDraft`, `isActive`,
-- `publishedAt` and `publishedBy`, which are facts about a *version*, not
-- about a task.
--
-- Carried on every task row they would be denormalised across the set and
-- free to disagree — publishing would be an UPDATE over N rows with no
-- constraint able to say they all moved together — and a version with no
-- tasks, which is exactly what a fresh draft is until its first task is
-- added, could not be represented at all.
--
-- So: a header row per version, the shape `ob_journey_templates` already
-- has, so a reader who understands one understands the other. Flagged
-- rather than done quietly, because it adds a table the module plan's own
-- §4 list does not mention.
-- ---------------------------------------------------------------------

CREATE TABLE ob_prereq_template_versions (
  id            BIGINT        NOT NULL AUTO_INCREMENT,
  version       INT           NOT NULL,

  -- The version new clients are snapshotted from.
  is_active     TINYINT(1)    NOT NULL DEFAULT 0,

  -- 1 when active, NULL otherwise. MySQL 8.4 has no partial unique index —
  -- PostgreSQL would write `CREATE UNIQUE INDEX … WHERE is_active`. A
  -- unique index ignores NULLs, so retired versions accumulate freely
  -- while a second *active* one is refused by the database rather than by
  -- a service that might forget. Third use of this idiom, after
  -- `ob_client_contacts.is_primary_key` (A-101) and
  -- `ob_journey_templates.active_key` (A-103) — unqualified here, where
  -- both of those pair it with an owner column, because the master is
  -- org-wide and there is nothing to qualify it by.
  active_key    TINYINT(1)
      GENERATED ALWAYS AS (IF(is_active = 1, 1, NULL)) STORED,

  published_at  DATETIME(6)   NULL,
  published_by  BIGINT        NULL,

  -- **`is_draft` is derived, not stored, and that is the point.**
  --
  -- A-103 makes "draft" mean `published_at IS NULL` and enforces it in the
  -- service only (`ObJourneyTemplate`'s javadoc: the mutability test is
  -- `publishedAt == null`, never `!isActive`). A boolean column beside the
  -- timestamp would be a second home for one fact and would eventually
  -- contradict it. Generated from the timestamp, it cannot.
  --
  -- Indexed unique, it also puts the contract's "one draft at a time"
  -- (`beginObPrereqTemplateRevision` answers 409 for the second) in the
  -- database. That rule carries more weight here than on the journey side:
  -- the master is org-wide, so two Admins revising at once is the normal
  -- case rather than the unlucky one, and publishing either of two drafts
  -- would silently discard the other.
  draft_key     TINYINT(1)
      GENERATED ALWAYS AS (IF(published_at IS NULL, 1, NULL)) STORED,

  created_by    BIGINT        NULL,
  created_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                  ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_ob_prereq_template_versions_version (version),
  -- One active version, org-wide. See the note on active_key.
  UNIQUE KEY uq_ob_prereq_template_versions_active (active_key),
  -- One draft, org-wide. See the note on draft_key.
  UNIQUE KEY uq_ob_prereq_template_versions_draft (draft_key),
  CONSTRAINT fk_ob_prereq_template_versions_published_by
    FOREIGN KEY (published_by) REFERENCES users (id),
  CONSTRAINT fk_ob_prereq_template_versions_created_by
    FOREIGN KEY (created_by) REFERENCES users (id),
  CONSTRAINT ck_ob_prereq_template_versions_version
    CHECK (version > 0),
  -- A publish records who did it. Written against the base columns rather
  -- than the generated one: the rule is about these two, and phrasing it
  -- over `draft_key` would read as though the derivation were the rule.
  CONSTRAINT ck_ob_prereq_template_versions_published
    CHECK ((published_at IS     NULL AND published_by IS     NULL)
        OR (published_at IS NOT NULL AND published_by IS NOT NULL)),
  -- A draft is never the active version. Without this, `is_active = 1` on
  -- an unpublished row would take the single active slot and every new
  -- client would snapshot a checklist nobody had published.
  CONSTRAINT ck_ob_prereq_template_versions_draft_not_active
    CHECK (published_at IS NOT NULL OR is_active = 0)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- §3. ob_prereq_template_tasks — one task on one version of the master.
--
-- `sequence` IS PRESENTATION ONLY, and unlike `ob_journey_template_steps`
-- that is the whole story. Journey steps carry `depends_on_step_id` and a
-- reorder has to re-validate a dependency graph (C-119); prerequisites
-- have **no dependency model at all** — the contract's
-- `reorderObPrereqTemplateTasks` says so in as many words, because the
-- client works their own checklist in whatever order suits them and
-- nothing on it activates anything else. The number decides what OB-14 and
-- CP-03 draw first, and nothing else reads it.
--
-- It is still unique per version, so a reorder cannot leave two tasks
-- sharing a position — which is also why the service reorders in two
-- passes through negative placeholders, exactly as `reorderSteps` does.
--
-- `is_mandatory` IS SET HERE AND NEVER ON AN INSTANCE. Plan §14's stated
-- mitigation for "the gate stalls every journey on a slow client" is
-- keeping the mandatory list short *in the master*. A flag editable per
-- client under delivery pressure would not be that mitigation, and
-- `skipObClientPrereqTask` answering 422 for a mandatory task (B-125) is
-- what makes the flag a guarantee rather than a default.
--
-- `tat_days` — WORKING DAYS, where the plan's own §4 line says
-- `tat_hours`. The v1.2 rename changed the unit for journey steps
-- (`ob_journey_template_steps.tat_days`, A-103) and the contract carries
-- days here too. Days is what shipped everywhere else in this module;
-- matching it is what stops a prerequisite TAT being read against a
-- journey TAT in the wrong unit. The divergence from the plan text is
-- named rather than silently resolved.
-- ---------------------------------------------------------------------
CREATE TABLE ob_prereq_template_tasks (
  id            BIGINT        NOT NULL AUTO_INCREMENT,
  version_id    BIGINT        NOT NULL,
  sequence      INT           NOT NULL,
  title         VARCHAR(200)  NOT NULL,
  description   TEXT          NULL,
  -- Working days. `due_at` on an instance is this many working days from
  -- instantiation against the org calendar (B-125), never a naive addition.
  tat_days      INT           NOT NULL DEFAULT 1,
  is_mandatory  TINYINT(1)    NOT NULL DEFAULT 0,
  is_active     TINYINT(1)    NOT NULL DEFAULT 1,
  created_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                  ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_ob_prereq_template_tasks_seq (version_id, sequence),
  -- Exists only as the composite foreign key's target in §4. Redundant as
  -- a uniqueness claim — `id` is already the primary key — and
  -- load-bearing as an index, exactly like
  -- `uq_ob_journey_template_steps_scope`: without it that key cannot be
  -- declared at all.
  UNIQUE KEY uq_ob_prereq_template_tasks_scope (version_id, id),
  -- RESTRICT, matching A-103's measured call on template → steps. A
  -- published version is pinned by every client snapshotted from it
  -- (B-125), so it is retired with `is_active = 0` and kept; only an
  -- unpublished draft is ever a genuine delete, and the service removes
  -- that version's tasks before the version row itself.
  CONSTRAINT fk_ob_prereq_template_tasks_version
    FOREIGN KEY (version_id) REFERENCES ob_prereq_template_versions (id),
  CONSTRAINT ck_ob_prereq_template_tasks_tat
    CHECK (tat_days > 0 AND tat_days <= 365)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- =====================================================================
-- §4. THE ATTACHMENT WIDENING — ONE TASK EARLIER THAN A-102 PREDICTED
-- =====================================================================
--
-- `V20260903_2045__ob_attachments.sql` reserved this widening for B-125:
--
--     "The prerequisite tables are B-124/B-125's (ob_client_prereq_tasks),
--      and they do not exist yet. … B-125 adds the column, its FK and its
--      arm of the CHECK together."
--
-- **It has to happen here instead, and the reason is that there are two
-- prerequisite owners rather than one.** A-102 saw the instance side —
-- what a client uploads against their own task — and that is genuinely
-- B-125's. What it did not account for is the *master* side: plan §4's
-- "admin-attached reference documents", the `kind: REFERENCE` half of its
-- own vocabulary, which hang off a task on the org-wide master and belong
-- to no client, no step and no sign-off.
--
-- `ck_ob_attachments_one_owner` requires exactly one of `ob_client_id`,
-- `step_id`, `signoff_id` to be set, so today a reference document on a
-- master task is not insertable at all — there is no value of those three
-- columns that is true of it. `addObPrereqTemplateTaskDoc` cannot work
-- without this arm, so B-124 adds it and B-125 adds the instance arm on
-- top. The prediction was right about the shape and one task out on the
-- timing.
--
-- **Flagged for Stream A** (`ob_attachments` is A-102's table). It is none
-- of CLAUDE.md's four protected tables, so this is for visibility rather
-- than for sign-off, and the widening is one A-102 anticipated in writing.
--
-- Adding an arm means replacing the constraint: MySQL 8.4 has no
-- ALTER … MODIFY CHECK. Dropped and re-added under its original name in
-- one statement, so the table is never left without the guarantee.
--
-- **CASCADE on this arm, where every other owner arm is RESTRICT, and it
-- is the one decision in this file that argues with an existing rule.**
--
-- A-102's rule is that attachment rows are tombstoned and never deleted,
-- "a file referenced by a sign-off or a completed step is evidence, and
-- the row has to keep resolving". RESTRICT here would make that rule and
-- the contract contradict each other: `removeObPrereqTemplateTask` says
-- "reference documents on the task go with it" and answers 204, but the
-- delete would fail with ERROR 1451 naming a constraint on a table the
-- admin never mentioned — A-103's own measured finding about CASCADE,
-- reproduced exactly, with the arrow the other way round.
--
-- What makes CASCADE right *on this arm specifically* is what these rows
-- are. A master reference document hangs off a task on a version, and a
-- version's tasks are only ever deleted while that version is an
-- unpublished draft — a published version is retired with
-- `is_active = 0` and kept, because clients are snapshotted from it. A
-- draft has never been shown to a client and nothing has ever been
-- snapshotted from it, so a reference document removed from one is not
-- evidence of anything. The instance-side arm B-125 adds is the opposite
-- case and must stay RESTRICT: those files are what a client actually sent.
--
-- **The cost, named rather than discovered:** a cascaded row takes its
-- `storage_key` with it, so the object behind it is orphaned in MinIO —
-- the tombstone path is what normally leaves a pointer for reaping. For
-- draft reference documents that is a small, bounded leak, and no
-- service-side tombstone can close it: a row deleted by a cascade is gone
-- whether or not `deleted_at` was set a moment earlier. **Flagged for
-- Stream A** alongside the CHECK widening: whether orphaned objects are
-- swept is A-102's pipeline's question, not this table's.
--
-- The cascade is also narrower than it first reads. A cloned reference
-- document's attachment stays owned by the task on the version it was
-- uploaded against — see `ObPrereqTemplateService#cloneTasks` — and a
-- published version's tasks are never deleted, only retired with the
-- version. So the only rows this arm can ever take are files uploaded
-- against a draft task that is then removed from that same draft.
-- ---------------------------------------------------------------------
ALTER TABLE ob_attachments
  ADD COLUMN prereq_template_task_id BIGINT NULL AFTER signoff_id,
  ADD KEY ix_ob_attachments_prereq_template_task
      (prereq_template_task_id, deleted_at),
  ADD CONSTRAINT fk_ob_attachments_prereq_template_task
      FOREIGN KEY (prereq_template_task_id)
      REFERENCES ob_prereq_template_tasks (id)
      ON DELETE CASCADE,
  DROP CHECK ck_ob_attachments_one_owner,
  ADD CONSTRAINT ck_ob_attachments_one_owner
    CHECK (( ob_client_id IS NOT NULL AND step_id IS     NULL AND signoff_id IS     NULL AND prereq_template_task_id IS     NULL)
        OR ( ob_client_id IS     NULL AND step_id IS NOT NULL AND signoff_id IS     NULL AND prereq_template_task_id IS     NULL)
        OR ( ob_client_id IS     NULL AND step_id IS     NULL AND signoff_id IS NOT NULL AND prereq_template_task_id IS     NULL)
        OR ( ob_client_id IS     NULL AND step_id IS     NULL AND signoff_id IS     NULL AND prereq_template_task_id IS NOT NULL));


-- ---------------------------------------------------------------------
-- §5. ob_prereq_template_task_docs — the admin's reference documents.
--
-- Plan §4's "admin-attached reference documents", shown **to the client**
-- on CP-04: a sample filled form, a specimen letter, the format a data
-- extract has to arrive in. What comes back the other way is a
-- `SUBMISSION` and hangs off the instance task (B-125), never off this.
--
-- **Why a table beside the attachment rather than the attachment alone.**
-- `ob_attachments` now carries `prereq_template_task_id`, so the ownership
-- is already expressible there — but the contract's
-- `ObPrereqTemplateTaskDoc` also carries a `label`, which is the admin's
-- caption for the document ("Specimen board resolution") and is not the
-- uploaded `file_name`. A caption belongs to the *listing*, not to the
-- bytes: replacing the file should not silently rename the entry the
-- client reads, and the same file legitimately appears under two tasks
-- with two captions.
--
-- **`template_task_id` here and `prereq_template_task_id` on the
-- attachment are not the same fact, so no key ties them together.** The
-- attachment's owner records where a file was first uploaded; this row
-- records where it is listed. They agree for every document an admin
-- attaches — `ObPrereqTemplateService#addTaskDoc` refuses one that does
-- not — and they deliberately differ for a document carried into a new
-- draft by `beginObPrereqTemplateRevision`, which clones the caption and
-- shares the file rather than duplicating the object in storage.
--
-- A composite key would therefore have been wrong rather than merely
-- expensive: it would refuse every cloned row, which is most of them on
-- any version after the first.
-- ---------------------------------------------------------------------
CREATE TABLE ob_prereq_template_task_docs (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  template_task_id  BIGINT        NOT NULL,
  attachment_id     BIGINT        NOT NULL,
  -- The admin's caption, not the file name. See the note above.
  label             VARCHAR(200)  NOT NULL,
  sequence          INT           NOT NULL DEFAULT 0,
  created_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                      ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY ix_ob_prereq_template_task_docs_task (template_task_id, sequence),
  -- One attachment is listed once per task. A second row citing the same
  -- file under a different caption is a mistake rather than a feature.
  UNIQUE KEY uq_ob_prereq_template_task_docs_attachment
      (template_task_id, attachment_id),
  -- CASCADE, unlike the version → tasks edge above. The contract's
  -- `removeObPrereqTemplateTask` says "reference documents on the task go
  -- with it", and a task is only ever deleted while its version is an
  -- unpublished draft, so nothing has been snapshotted from either.
  CONSTRAINT fk_ob_prereq_template_task_docs_task
    FOREIGN KEY (template_task_id) REFERENCES ob_prereq_template_tasks (id)
      ON DELETE CASCADE,
  -- CASCADE for the same reason, and it is what makes the two edges agree
  -- under a task delete: §4 cascades the attachment row away, and this
  -- takes the caption with it rather than leaving a doc row citing an
  -- attachment id that no longer resolves.
  CONSTRAINT fk_ob_prereq_template_task_docs_attachment
    FOREIGN KEY (attachment_id) REFERENCES ob_attachments (id)
      ON DELETE CASCADE
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
