-- =====================================================================
-- B-125 · Client Onboarding — per-client prerequisite instances
--
-- Tables: ob_client_prereqs, ob_client_prereq_tasks,
--         ob_prereq_comments (append-only),
--         ob_prereq_history   (append-only, hash-chained)
-- Alters: ob_attachments (A-102) — the fifth owner arm, the one its own
--         comment predicted. See §5.
--
-- Source:  docs/Onboarding-Module-Plan.md §4 (data model), §5.3 (the hard
--          gate), §5.4 (client-attributed time), §7 (events), §9 OB-05/CP-04
--          contracts/openapi.yaml — the eleven instance operations A-118
--          published; this file is built to them.
--
-- Applied: PLAN.md §3.1 PostgreSQL → MySQL translation (normative).
--          utf8mb4 / utf8mb4_0900_ai_ci on every table.
--
-- Depends on B-124's `V20260908_1100__ob_prereq_template.sql`, which is the
-- master these rows are snapshotted from.
--
-- WHO IS WAITING ON THIS FILE:
--     C-118 PrerequisiteGateService · C-121 CP-01..CP-04 (the portal)
--     A-118's `prereq-aging` report, declared unavailable until it exists
--
-- =====================================================================
-- §1. ob_client_prereqs — the header, and the version pin
-- =====================================================================
--
-- One row per client, created at boarding. It exists to hold two facts the
-- task rows cannot:
--
--   `template_version_id` — WHICH master version this client was
--   snapshotted from. Plan §1.1 #2: publishing a newer master leaves every
--   boarded client on the checklist they were actually given. Without the
--   pin, "what was this client asked for" becomes unanswerable the moment
--   OB-14 is edited, which is precisely the question a waiver dispute
--   turns on.
--
--   `status` — the header's own record that the gate condition was met.
--   Not a second opinion about `ob_journeys.gate_status`: the two move in
--   the same transaction (C-118), and this one survives a client whose
--   journeys have all been archived.
--
-- `template_version` is denormalised beside the id **deliberately**. The id
-- is the referential truth; the number is what OB-05 and CP-03 display and
-- what a report groups by, and joining `ob_prereq_template_versions` for an
-- integer that can never change once pinned is a join every read would pay.
-- It cannot drift because a published version's `version` is immutable —
-- B-124 refuses every write to a published row.
-- ---------------------------------------------------------------------

CREATE TABLE ob_client_prereqs (
  id                  BIGINT        NOT NULL AUTO_INCREMENT,
  ob_client_id        BIGINT        NOT NULL,
  template_version_id BIGINT        NOT NULL,
  -- Display copy of the pinned version's number. See the note above.
  template_version    INT           NOT NULL,
  status              VARCHAR(12)   NOT NULL DEFAULT 'IN_PROGRESS',
                      -- IN_PROGRESS|CLEARED
  cleared_at          DATETIME(6)   NULL,
  created_at          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                        ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  -- One checklist per client. The contract's paths say so — `/onboarding/
  -- clients/{obClientId}/prereqs` addresses it with no id of its own — and
  -- a second row would make "the client's gate" ambiguous.
  UNIQUE KEY uq_ob_client_prereqs_client (ob_client_id),
  KEY ix_ob_client_prereqs_status (status, cleared_at),
  CONSTRAINT fk_ob_client_prereqs_client
    FOREIGN KEY (ob_client_id) REFERENCES ob_clients (id),
  -- RESTRICT: a pinned version can never be deleted. B-124 only ever
  -- deletes draft versions' tasks, and a draft is never pinned here.
  CONSTRAINT fk_ob_client_prereqs_version
    FOREIGN KEY (template_version_id) REFERENCES ob_prereq_template_versions (id),
  CONSTRAINT ck_ob_client_prereqs_status
    CHECK (status IN ('IN_PROGRESS', 'CLEARED')),
  -- CLEARED says when. An unstamped CLEARED would leave the gate's opening
  -- undateable, which is the one date §5.4's attribution is measured from.
  CONSTRAINT ck_ob_client_prereqs_cleared
    CHECK ((status = 'IN_PROGRESS' AND cleared_at IS     NULL)
        OR (status = 'CLEARED'     AND cleared_at IS NOT NULL))
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- =====================================================================
-- §2. ob_client_prereq_tasks — the snapshot, and the ad-hoc additions
-- =====================================================================
--
-- THE SNAPSHOT IS A COPY, NOT A REFERENCE, AND THAT IS THE WHOLE DESIGN.
--
-- `title`, `description`, `tat_days` and `is_mandatory` are copied from the
-- master task rather than read through `template_task_id`. Reading through
-- would make an OB-14 edit rewrite what a client is being asked for — the
-- exact corruption B-124's versioning exists to prevent, reintroduced one
-- join later. `template_task_id` is kept anyway, nullable, so a waiver can
-- be read back against the master row it came from; it is provenance, not
-- the source of the wording.
--
-- `is_ad_hoc` RATHER THAN INFERRING IT FROM A NULL `template_task_id`.
-- Plan §4 allows per-client tasks. OB-05 marks them, because "why is this
-- client being asked for something the others are not" is the first
-- question about one — and a screen inferring it from a null would also
-- mark a snapshotted task whose master row was later deleted, which is a
-- different thing entirely.
--
-- `due_at` IS WORKING-CALENDAR DERIVED AND IS STORED, NOT COMPUTED ON READ.
-- CLAUDE.md's rule sends every duration through the calendar; storing the
-- result is what makes it stable when the calendar itself is edited later.
-- `is_overdue` is the opposite call — derived on read, never stored, so it
-- cannot disagree with the timestamp beside it.
--
-- THE STATUS SET IS FOUR VALUES AND THE TWO ABSENCES ARE DELIBERATE.
-- There is no RETURNED: a returned submission is PENDING again, because
-- that is what the client has to act on, and a fifth value would split
-- "the client owes us this" across two states every count and every
-- reminder would have to remember to add together. There is no EXPIRED
-- either: a task past `due_at` is overdue rather than closed, and one that
-- timed itself out would clear nothing while making the gate look
-- permanently unopenable.
--
-- WHO SUBMITTED IS TWO COLUMNS, for `ob_step_communications`' reason — a
-- staff user and a client contact live in different tables. `submitted_via`
-- is kept beside them rather than inferred from which is set, because the
-- question it answers is whether the *portal* is working, and a SPOC who
-- emails a document that staff then record is a different fact from staff
-- doing the work themselves.
-- ---------------------------------------------------------------------
CREATE TABLE ob_client_prereq_tasks (
  id                     BIGINT        NOT NULL AUTO_INCREMENT,
  ob_client_prereqs_id   BIGINT        NOT NULL,
  -- Denormalised from the header for the scope guard and every per-client
  -- read. A-112's rule is expressed in SQL over this column (C-118, the
  -- portal), and routing every one of those through the header row would
  -- add a join to the module's most-read table for a value that is fixed
  -- at insert and cannot change: a task never moves between clients.
  ob_client_id           BIGINT        NOT NULL,
  -- Provenance, not the source of the wording. NULL on an ad-hoc task.
  template_task_id       BIGINT        NULL,
  sequence               INT           NOT NULL,
  title                  VARCHAR(200)  NOT NULL,
  description            TEXT          NULL,
  tat_days               INT           NOT NULL DEFAULT 1,
  is_mandatory           TINYINT(1)    NOT NULL DEFAULT 0,
  is_ad_hoc              TINYINT(1)    NOT NULL DEFAULT 0,
  status                 VARCHAR(12)   NOT NULL DEFAULT 'PENDING',
                         -- PENDING|SUBMITTED|VERIFIED|SKIPPED
  -- Working-calendar derived from tat_days at instantiation. Never a naive
  -- date addition — a Friday task with a two-day TAT is not overdue on
  -- Sunday.
  due_at                 DATETIME(6)   NOT NULL,
  submitted_at           DATETIME(6)   NULL,
  submitted_via          VARCHAR(10)   NULL,   -- PORTAL|STAFF
  submitted_by_user      BIGINT        NULL,
  submitted_by_contact   BIGINT        NULL,
  verified_at            DATETIME(6)   NULL,
  verified_by            BIGINT        NULL,
  skipped_at             DATETIME(6)   NULL,
  skipped_by             BIGINT        NULL,
  skip_reason            TEXT          NULL,
  created_at             DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at             DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                           ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_ob_client_prereq_tasks_seq (ob_client_prereqs_id, sequence),
  -- The gate's own query: this client's tasks by status, which C-118
  -- evaluates on every transition.
  KEY ix_ob_client_prereq_tasks_gate (ob_client_id, status, is_mandatory),
  -- The scanner's sweep (plan §5.4) and the aging report: what is
  -- outstanding and past due.
  KEY ix_ob_client_prereq_tasks_due (status, due_at),
  KEY ix_ob_client_prereq_tasks_template (template_task_id),
  KEY ix_ob_client_prereq_tasks_verified_by (verified_by),
  KEY ix_ob_client_prereq_tasks_skipped_by (skipped_by),
  KEY ix_ob_client_prereq_tasks_submitter_user (submitted_by_user),
  KEY ix_ob_client_prereq_tasks_submitter_contact (submitted_by_contact),
  CONSTRAINT fk_ob_client_prereq_tasks_header
    FOREIGN KEY (ob_client_prereqs_id) REFERENCES ob_client_prereqs (id),
  CONSTRAINT fk_ob_client_prereq_tasks_client
    FOREIGN KEY (ob_client_id) REFERENCES ob_clients (id),
  -- RESTRICT, and it is load-bearing rather than incidental: it is what
  -- stops B-124 deleting a master task any client was snapshotted from.
  -- B-124 only deletes draft tasks and a draft is never snapshotted, so
  -- this should never fire — which is exactly the kind of assumption worth
  -- having the database hold.
  CONSTRAINT fk_ob_client_prereq_tasks_template
    FOREIGN KEY (template_task_id) REFERENCES ob_prereq_template_tasks (id),
  CONSTRAINT fk_ob_client_prereq_tasks_verified_by
    FOREIGN KEY (verified_by) REFERENCES users (id),
  CONSTRAINT fk_ob_client_prereq_tasks_skipped_by
    FOREIGN KEY (skipped_by) REFERENCES users (id),
  CONSTRAINT fk_ob_client_prereq_tasks_submitter_user
    FOREIGN KEY (submitted_by_user) REFERENCES users (id),
  CONSTRAINT fk_ob_client_prereq_tasks_submitter_contact
    FOREIGN KEY (submitted_by_contact) REFERENCES ob_client_contacts (id),
  CONSTRAINT ck_ob_client_prereq_tasks_status
    CHECK (status IN ('PENDING', 'SUBMITTED', 'VERIFIED', 'SKIPPED')),
  CONSTRAINT ck_ob_client_prereq_tasks_tat
    CHECK (tat_days > 0 AND tat_days <= 365),
  -- An ad-hoc task has no master row; a snapshotted one has. Stated as a
  -- constraint because `is_ad_hoc` is the field OB-05 renders and a row
  -- where the two disagree would mislabel a client's checklist.
  CONSTRAINT ck_ob_client_prereq_tasks_ad_hoc
    CHECK ((is_ad_hoc = 1 AND template_task_id IS     NULL)
        OR (is_ad_hoc = 0 AND template_task_id IS NOT NULL)),
  -- A submission says when and by which route. Exactly one submitter,
  -- matching the side of the portal it came from — ck_ob_attachments_
  -- uploader's shape.
  CONSTRAINT ck_ob_client_prereq_tasks_submitted
    CHECK ((submitted_at IS     NULL AND submitted_via IS     NULL
                                     AND submitted_by_user IS NULL
                                     AND submitted_by_contact IS NULL)
        OR (submitted_at IS NOT NULL AND submitted_via = 'PORTAL'
                                     AND submitted_by_contact IS NOT NULL
                                     AND submitted_by_user IS NULL)
        OR (submitted_at IS NOT NULL AND submitted_via = 'STAFF'
                                     AND submitted_by_user IS NOT NULL
                                     AND submitted_by_contact IS NULL)),
  -- VERIFIED says who and when.
  CONSTRAINT ck_ob_client_prereq_tasks_verified
    CHECK ((verified_at IS     NULL AND verified_by IS     NULL)
        OR (verified_at IS NOT NULL AND verified_by IS NOT NULL)),
  -- **A SKIP CARRIES ITS REASON, AT THE COLUMN.**
  --
  -- Plan §5.3 makes skipping the gate's only valve and §14's mitigation for
  -- the stalling risk depends on skips being visible and accountable. A
  -- skipped row with no reason is the one row in this module a dispute is
  -- most likely to turn on, and "the service always sets it" is not the
  -- same guarantee as the column refusing the row.
  CONSTRAINT ck_ob_client_prereq_tasks_skipped
    CHECK ((status <> 'SKIPPED' AND skipped_at IS NULL AND skipped_by IS NULL)
        OR (status =  'SKIPPED' AND skipped_at IS NOT NULL
                                AND skipped_by IS NOT NULL
                                AND skip_reason IS NOT NULL)),
  -- **A MANDATORY TASK CAN NEVER BE SKIPPED, AND THE DATABASE SAYS SO.**
  --
  -- The service refuses it with 422 and that is where the caller meets the
  -- rule. This is here because it is the difference between the gate being
  -- a guarantee and being a convention: plan §5.3's "no open-gate-anyway
  -- override" is worth exactly as much as the narrowest path to a cleared
  -- gate, and a bug, a fixture or a console session that skipped a
  -- mandatory task would open every journey the client has.
  CONSTRAINT ck_ob_client_prereq_tasks_mandatory_not_skipped
    CHECK (NOT (status = 'SKIPPED' AND is_mandatory = 1))
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- =====================================================================
-- §3. ob_prereq_comments — append-only, both principals
-- =====================================================================
--
-- Plan §4: "append-only; author_type STAFF·CLIENT". Two author columns,
-- exactly one set, the shape `ob_step_communications` already uses and for
-- the same reason: a staff user and a client contact are rows in different
-- tables, and a single polymorphic id would need a discriminator anyway.
--
-- **NOT HASH-CHAINED, where §4 puts `ob_prereq_history` on the chain.**
-- The distinction is the same one `ob_step_communications` and
-- `ob_step_history` already draw one table over: the *state changes* are
-- the record that has to be provably untampered, and the conversation is
-- append-only because it is a conversation. A return's mandatory comment
-- is written to both — the chain gets the reason, this gets the message —
-- so the fact a dispute turns on is chained either way.
--
-- `is_system` marks a comment a transition wrote rather than a person
-- typed. CP-04 renders those as events, and it is also what stops a client
-- appearing to have been answered by a string the server composed.
-- ---------------------------------------------------------------------
CREATE TABLE ob_prereq_comments (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  prereq_task_id    BIGINT        NOT NULL,
  author_type       VARCHAR(10)   NOT NULL,   -- STAFF|CLIENT
  author_user_id    BIGINT        NULL,
  author_contact_id BIGINT        NULL,
  body              TEXT          NOT NULL,
  is_system         TINYINT(1)    NOT NULL DEFAULT 0,
  created_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  -- The thread read, oldest first, cursor-paginated on id.
  KEY ix_ob_prereq_comments_task (prereq_task_id, id),
  KEY ix_ob_prereq_comments_author_user (author_user_id),
  KEY ix_ob_prereq_comments_author_contact (author_contact_id),
  -- RESTRICT rather than CASCADE. A task carrying a conversation is not
  -- deleted: ad-hoc tasks are the only deletable ones and the service does
  -- not delete them either — nothing in the contract removes a client's
  -- task. Stated so a later task that wants one has to decide what happens
  -- to the thread rather than discovering a cascade did.
  CONSTRAINT fk_ob_prereq_comments_task
    FOREIGN KEY (prereq_task_id) REFERENCES ob_client_prereq_tasks (id),
  CONSTRAINT fk_ob_prereq_comments_author_user
    FOREIGN KEY (author_user_id) REFERENCES users (id),
  CONSTRAINT fk_ob_prereq_comments_author_contact
    FOREIGN KEY (author_contact_id) REFERENCES ob_client_contacts (id),
  CONSTRAINT ck_ob_prereq_comments_author_type
    CHECK (author_type IN ('STAFF', 'CLIENT')),
  CONSTRAINT ck_ob_prereq_comments_author
    CHECK ((author_type = 'STAFF'  AND author_user_id IS NOT NULL
                                   AND author_contact_id IS NULL)
        OR (author_type = 'CLIENT' AND author_contact_id IS NOT NULL
                                   AND author_user_id IS NULL))
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- =====================================================================
-- §4. ob_prereq_history — append-only AND hash-chained
-- =====================================================================
--
-- **THE CHAIN IS PER CLIENT.**
--
-- `ob_step_history` chains per journey, on PLAN.md §3.7's reasoning: global
-- would serialise the whole module, per step is too fine because one action
-- touches several steps. The same argument lands on the client here, and
-- for the mirror-image reason: a prerequisite transition can touch several
-- *tasks* at once is not the case, but the thing every transition already
-- locks is the client — the gate evaluation reads every task the client
-- has, and the header row moves to CLEARED in the same transaction. Per
-- task would mean the gate-clearing verification holding N locks in an
-- order nothing guarantees; per client is the parent every append already
-- touches.
--
-- So the append is, exactly as C-107's `ObStepJournal` does it:
--
--     BEGIN
--       SELECT id FROM ob_clients WHERE id = ? FOR UPDATE;
--       SELECT row_hash FROM ob_prereq_history
--         WHERE ob_client_id = ? ORDER BY id DESC LIMIT 1;   -- prev_hash
--       row_hash = SHA256(prev_hash || canonical_json(payload))
--       INSERT INTO ob_prereq_history (...);
--     COMMIT
--
-- `canonical_json` is `common`'s, golden-file tested, and must not be
-- re-implemented — a second serialiser with a different key order produces
-- hashes the verifier cannot reproduce, and it would report tampering that
-- is our own bug. That failure is indistinguishable from the real thing.
--
-- **THIS IS CHAINED WHERE B-101 AND B-103 DECIDED NOT TO BE, AND THE
-- REASON THEY GAVE HAS SINCE BEEN ANSWERED.** Both left their journals
-- append-only-but-unchained because the onboarding chain payload was
-- unwritten and inventing one would hand A-123's verifier rows that fail to
-- verify. C-107 then wrote it — `ObStepJournal.chainPayload`, with its own
-- version constant. So the payload exists, the shape is settled, and the
-- reason to defer has gone. §4 asks for this table on the chain and it is
-- now possible to put it there honestly.
--
-- `prev_hash` IS NULL FOR THE FIRST ROW OF EACH CLIENT — the chain's
-- anchor, not a missing value. The verifier walks per client from there.
--
-- `reason` IS COPIED, NOT REFERENCED. The skip reason and the return
-- comment are written here as text at the moment they were given. A history
-- row pointing at a comment row would be a hash-chained record whose
-- meaning lived in a table with no chain of its own — the chain would prove
-- that *something* was skipped and nothing about why.
-- ---------------------------------------------------------------------
CREATE TABLE ob_prereq_history (
  id                 BIGINT        NOT NULL AUTO_INCREMENT,
  -- The chain key. Every hash walk is scoped to one client.
  ob_client_id       BIGINT        NOT NULL,
  prereq_task_id     BIGINT        NOT NULL,
  occurred_at        DATETIME(6)   NOT NULL,
  actor_type         VARCHAR(10)   NOT NULL DEFAULT 'STAFF',  -- STAFF|CLIENT|SYSTEM
  actor_user_id      BIGINT        NULL,
  actor_contact_id   BIGINT        NULL,
  -- NULL on the first entry — instantiation has nothing to come from.
  from_status        VARCHAR(12)   NULL,
  to_status          VARCHAR(12)   NOT NULL,
  reason             TEXT          NULL,
  is_correction      TINYINT(1)    NOT NULL DEFAULT 0,
  corrects_entry_id  BIGINT        NULL,
  -- Bumped alongside any change to the payload the hash covers, on
  -- ObStepJournal's own precedent: a verifier reading an old row has to
  -- know which payload shape to reproduce.
  chain_payload_version INT        NOT NULL DEFAULT 1,
  -- ascii_bin so the comparison is byte-exact and case-sensitive; a
  -- case-insensitive collation would make two different hashes compare
  -- equal, which is the one thing a hash column must never do.
  prev_hash          CHAR(64)      CHARACTER SET ascii COLLATE ascii_bin NULL,
  row_hash           CHAR(64)      CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  -- The chain walk: one client's rows, DESC for the append's prev_hash
  -- read and ASC for the verifier's sweep.
  KEY ix_ob_prereq_history_client (ob_client_id, id),
  -- The per-task timeline the contract's history route serves.
  KEY ix_ob_prereq_history_task (prereq_task_id, id),
  KEY ix_ob_prereq_history_actor (actor_user_id),
  KEY ix_ob_prereq_history_contact (actor_contact_id),
  KEY ix_ob_prereq_history_corrects (corrects_entry_id),
  CONSTRAINT fk_ob_prereq_history_client
    FOREIGN KEY (ob_client_id) REFERENCES ob_clients (id),
  CONSTRAINT fk_ob_prereq_history_task
    FOREIGN KEY (prereq_task_id) REFERENCES ob_client_prereq_tasks (id),
  CONSTRAINT fk_ob_prereq_history_actor
    FOREIGN KEY (actor_user_id) REFERENCES users (id),
  CONSTRAINT fk_ob_prereq_history_contact
    FOREIGN KEY (actor_contact_id) REFERENCES ob_client_contacts (id),
  -- Self-referencing: a correction names the entry it compensates.
  CONSTRAINT fk_ob_prereq_history_corrects
    FOREIGN KEY (corrects_entry_id) REFERENCES ob_prereq_history (id),
  CONSTRAINT ck_ob_prereq_history_actor_type
    CHECK (actor_type IN ('STAFF', 'CLIENT', 'SYSTEM')),
  CONSTRAINT ck_ob_prereq_history_actor
    CHECK ((actor_type = 'STAFF'  AND actor_user_id IS NOT NULL
                                  AND actor_contact_id IS NULL)
        OR (actor_type = 'CLIENT' AND actor_contact_id IS NOT NULL
                                  AND actor_user_id IS NULL)
        OR (actor_type = 'SYSTEM' AND actor_user_id IS NULL
                                  AND actor_contact_id IS NULL)),
  CONSTRAINT ck_ob_prereq_history_to_status
    CHECK (to_status IN ('PENDING', 'SUBMITTED', 'VERIFIED', 'SKIPPED')),
  CONSTRAINT ck_ob_prereq_history_from_status
    CHECK (from_status IS NULL
        OR from_status IN ('PENDING', 'SUBMITTED', 'VERIFIED', 'SKIPPED')),
  -- A correction names what it corrects, and only a correction may.
  CONSTRAINT ck_ob_prereq_history_correction
    CHECK ((is_correction = 0 AND corrects_entry_id IS     NULL)
        OR (is_correction = 1 AND corrects_entry_id IS NOT NULL))
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- =====================================================================
-- §5. THE ATTACHMENT ARM A-102 ACTUALLY PREDICTED
-- =====================================================================
--
-- `V20260903_2045__ob_attachments.sql` reserved a prerequisite owner column
-- for B-125, and B-124 has since added a different one — the *master* side,
-- for the admin's reference documents, which belong to no client. This is
-- the one that comment meant: what a client sends back.
--
-- **RESTRICT, where B-124's master arm cascades, and the asymmetry is the
-- point.** A master reference document hangs off a draft that no client has
-- ever seen. A submission is what a client actually sent, against a task
-- they were actually asked to clear — A-102's own rule, "a file referenced
-- by a sign-off or a completed step is evidence, and the row has to keep
-- resolving", is about exactly this kind of file. Nothing deletes a client's
-- prerequisite task, so this never fires; it is here so that if something
-- ever tries, it fails rather than taking the evidence with it.
-- ---------------------------------------------------------------------
ALTER TABLE ob_attachments
  ADD COLUMN prereq_task_id BIGINT NULL AFTER prereq_template_task_id,
  ADD KEY ix_ob_attachments_prereq_task (prereq_task_id, kind, deleted_at),
  ADD CONSTRAINT fk_ob_attachments_prereq_task
      FOREIGN KEY (prereq_task_id) REFERENCES ob_client_prereq_tasks (id),
  DROP CHECK ck_ob_attachments_one_owner,
  ADD CONSTRAINT ck_ob_attachments_one_owner
    CHECK (( ob_client_id IS NOT NULL AND step_id IS     NULL AND signoff_id IS     NULL AND prereq_template_task_id IS     NULL AND prereq_task_id IS     NULL)
        OR ( ob_client_id IS     NULL AND step_id IS NOT NULL AND signoff_id IS     NULL AND prereq_template_task_id IS     NULL AND prereq_task_id IS     NULL)
        OR ( ob_client_id IS     NULL AND step_id IS     NULL AND signoff_id IS NOT NULL AND prereq_template_task_id IS     NULL AND prereq_task_id IS     NULL)
        OR ( ob_client_id IS     NULL AND step_id IS     NULL AND signoff_id IS     NULL AND prereq_template_task_id IS NOT NULL AND prereq_task_id IS     NULL)
        OR ( ob_client_id IS     NULL AND step_id IS     NULL AND signoff_id IS     NULL AND prereq_template_task_id IS     NULL AND prereq_task_id IS NOT NULL));


-- =====================================================================
-- §6. THE APPEND-ONLY TRIGGERS
-- =====================================================================
--
-- Layer four of the four CLAUDE.md's append-only rule names, and the one
-- that holds when the other three are bypassed: the repository fragment
-- (`AppendOnly`), `@Immutable` on the entity, and the `edutrack_app` grant
-- all live in the application. A console session, a fixture or a migration
-- meets only this.
--
-- `ob_prereq_history` refuses UPDATE and DELETE outright — editing breaks
-- the client's chain, and a correction is a new compensating row.
-- `ob_prereq_comments` likewise: this thread is half the record of what a
-- client was asked for and what they said back, and a deletable comment is
-- a conversation either side can rewrite once a dispute starts.
-- ---------------------------------------------------------------------
DELIMITER $$

CREATE TRIGGER trg_ob_prereq_comments_no_update BEFORE UPDATE ON ob_prereq_comments
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'Immutable table: ob_prereq_comments cannot be updated. A correction is a new comment.';
END$$

CREATE TRIGGER trg_ob_prereq_comments_no_delete BEFORE DELETE ON ob_prereq_comments
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'Immutable table: ob_prereq_comments rows cannot be deleted.';
END$$

CREATE TRIGGER trg_ob_prereq_history_no_update BEFORE UPDATE ON ob_prereq_history
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'Immutable table: ob_prereq_history cannot be updated. Editing breaks the client hash chain; append a correction.';
END$$

CREATE TRIGGER trg_ob_prereq_history_no_delete BEFORE DELETE ON ob_prereq_history
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'Immutable table: ob_prereq_history cannot be deleted. Deleting breaks the hash chain for its client.';
END$$

DELIMITER ;
