-- =====================================================================
-- A-106 · Client Onboarding — the append-only pair, hash-chained
--
-- Tables: ob_step_communications, ob_step_history  (+ their triggers)
--
-- Source:  docs/Onboarding-Module-Plan.md §4 (both annotated append-only,
--          history hash-chained), §6 (communication capture), §1.1 item 10
--          PLAN.md §3.5 (immutable tables), §3.7 (hash chaining under
--          concurrency), CLAUDE.md "The append-only rule"
--
-- THIS IS THE FILE CLAUDE.md WARNS ABOUT.
--
--   "This is the guarantee that erodes first and is hardest to restore.
--    If a task seems to need mutation, the design is wrong — raise it."
--
-- Nothing here has an update path, and none may be added later. A
-- correction is a new compensating row (`is_correction`,
-- `corrects_entry_id`), exactly as an accounting reversal, exactly as
-- A-043 does for `ticket_effort_logs`.
--
-- FOUR LAYERS, of which this file is the fourth (A-008's own listing,
-- applied to the onboarding module):
--   layer 1  no update()/delete() method on the service       Stream C
--   layer 2  no PUT/PATCH/DELETE route registered             Stream C
--   layer 3  edutrack_app holds INSERT, SELECT only here      A-109
--   layer 4  the triggers at the bottom of this file          here
--
-- Three limits of trigger protection, and how each is closed (PLAN §3.5):
--   1. Triggers do not fire on TRUNCATE  -> the app user has no DROP
--   2. Triggers do not survive DROP/recreate -> the app user has no DDL
--   3. A SUPER user can drop them        -> the hash chain is the backstop;
--      tampering that bypasses a trigger still breaks the chain, and the
--      verifier reports it
--
-- A-123 is the mutation test that proves all of this by attempting UPDATE
-- and DELETE and asserting the exception. **Until A-123 covers these two
-- tables, this file is unverified in the same sense A-008 was until
-- A-013** — the triggers exist and are exercised by hand below, but
-- nothing yet re-checks them on every CI run.
--
-- WHY ONLY ONE OF THE TWO IS CHAINED
--
-- Plan §4 annotates `ob_step_communications` "append-only" and
-- `ob_step_history` "append-only, hash-chained", and that asymmetry is
-- deliberate rather than an omission to be tidied up.
--
-- The chain costs a pessimistic lock on the parent row per append
-- (§3.7). It buys tamper-evidence for the record that says *what happened
-- and who did it* — which is the record an escalation, a TAT dispute or a
-- sign-off argument turns on. Communications are the correspondence
-- *alongside* that record: they are already attributable, they are
-- frequently written by the client through the portal, and a forged
-- comment is a far less useful lie than a forged transition.
--
-- Chaining both would double the lock traffic on the same parent for no
-- additional guarantee, since every communication worth disputing has a
-- history row beside it inside the chain.
-- =====================================================================


-- ---------------------------------------------------------------------
-- ob_step_communications — APPEND ONLY. Not chained; see the header.
--
-- §6: per-step timelines, stitched into a client-level view, with the
-- prerequisite threads joining it. §4 also has the escalation mirror
-- landing here as an `ESCALATION` entry.
--
-- `author_type` IS WHY THERE ARE TWO AUTHOR COLUMNS. A comment may come
-- from a staff user or from a client contact through the portal (§2.3's
-- `principal_type: CLIENT`), and those live in different tables. Exactly
-- one is set, enforced below — a row that names both, or neither, is a row
-- whose author cannot be rendered.
--
-- `is_client_visible` IS THE PORTAL'S FILTER, AND IT DEFAULTS TO FALSE.
-- §9 CP-03's never-visible list includes internal comms; §11 repeats it.
-- Defaulting to visible would mean a staff note reaches the client because
-- somebody forgot a flag, which is the failure mode that cannot be undone
-- once the client has read it. A client's own comment is visible to them
-- by construction, and the service sets the flag accordingly.
--
-- There is no `updated_at`. Nothing updates.
-- ---------------------------------------------------------------------
CREATE TABLE ob_step_communications (
  id                 BIGINT        NOT NULL AUTO_INCREMENT,
  step_id            BIGINT        NOT NULL,
  -- Denormalised for the client-level stitched view (§6), which reads
  -- every communication for one client across every journey.
  journey_id         BIGINT        NOT NULL,
  ob_client_id       BIGINT        NOT NULL,
  entry_type         VARCHAR(30)   NOT NULL DEFAULT 'COMMENT',
                     -- COMMENT|CALL|EMAIL|MEETING|ESCALATION|SYSTEM
  body               TEXT          NOT NULL,
  -- Exactly one of the two below is set. See the note above.
  author_type        VARCHAR(10)   NOT NULL DEFAULT 'STAFF',   -- STAFF|CLIENT|SYSTEM
  author_user_id     BIGINT        NULL,
  author_contact_id  BIGINT        NULL,
  -- Defaults to internal. §9 CP-03, §11.
  is_client_visible  TINYINT(1)    NOT NULL DEFAULT 0,
  occurred_at        DATETIME(6)   NOT NULL,
  is_correction      TINYINT(1)    NOT NULL DEFAULT 0,
  corrects_entry_id  BIGINT        NULL,
  created_at         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY ix_ob_comms_step (step_id, id),
  -- The client-level stitched view, and the portal's own read, which adds
  -- is_client_visible to the same prefix.
  KEY ix_ob_comms_client (ob_client_id, is_client_visible, occurred_at),
  KEY ix_ob_comms_journey (journey_id, occurred_at),
  KEY ix_ob_comms_author_user (author_user_id),
  KEY ix_ob_comms_author_contact (author_contact_id),
  KEY ix_ob_comms_corrects (corrects_entry_id),
  CONSTRAINT fk_ob_comms_step
    FOREIGN KEY (step_id) REFERENCES ob_journey_steps (id),
  CONSTRAINT fk_ob_comms_journey
    FOREIGN KEY (journey_id) REFERENCES ob_journeys (id),
  CONSTRAINT fk_ob_comms_client
    FOREIGN KEY (ob_client_id) REFERENCES ob_clients (id),
  CONSTRAINT fk_ob_comms_author_user
    FOREIGN KEY (author_user_id) REFERENCES users (id),
  CONSTRAINT fk_ob_comms_author_contact
    FOREIGN KEY (author_contact_id) REFERENCES ob_client_contacts (id),
  CONSTRAINT fk_ob_comms_corrects
    FOREIGN KEY (corrects_entry_id) REFERENCES ob_step_communications (id),
  CONSTRAINT ck_ob_comms_author_type
    CHECK (author_type IN ('STAFF', 'CLIENT', 'SYSTEM')),
  -- Exactly one author column, matching the type. A SYSTEM entry has
  -- neither, which is the third valid shape and why this is not a simple
  -- "one of two is not null".
  CONSTRAINT ck_ob_comms_author
    CHECK ((author_type = 'STAFF'  AND author_user_id IS NOT NULL
                                   AND author_contact_id IS NULL)
        OR (author_type = 'CLIENT' AND author_contact_id IS NOT NULL
                                   AND author_user_id IS NULL)
        OR (author_type = 'SYSTEM' AND author_user_id IS NULL
                                   AND author_contact_id IS NULL)),
  CONSTRAINT ck_ob_comms_body
    CHECK (body <> ''),
  -- A correction names what it corrects, and only a correction does.
  CONSTRAINT ck_ob_comms_correction
    CHECK ((is_correction = 0 AND corrects_entry_id IS NULL)
        OR (is_correction = 1 AND corrects_entry_id IS NOT NULL))
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- ob_step_history — APPEND ONLY · HASH CHAINED
--
-- The narrative record: what happened to a step, when, and who did it.
-- §1.1 item 10 puts it on the platform's immutability pattern, which is
-- `ticket_history` (A-004/A-008) column for column where the shape
-- carries over.
--
-- THE CHAIN IS PER JOURNEY, NOT PER STEP AND NOT GLOBAL.
--
-- PLAN.md §3.7 resolves the fork problem for tickets by chaining per
-- ticket and taking `SELECT … FOR UPDATE` on the ticket row before every
-- append. The same argument decides the granularity here, and the answer
-- is the journey rather than the step:
--
--   * Global would serialise every append in the module against every
--     other, across every client.
--   * Per step is too fine. §5.6 has dependency-free steps running **in
--     parallel**, and completing any step re-evaluates the whole journey
--     and may activate several others — one action, several steps, several
--     history rows. Per-step chains would mean taking N locks for one
--     transition, in an order nothing guarantees, which is a deadlock
--     against ourselves. §3.7's closing note makes exactly this point for
--     tickets: all three tables chain off the *one* parent lock so a
--     single handoff "holds one lock, not three, and cannot deadlock
--     against itself."
--   * Per journey is the parent every step transition already touches.
--
-- So the append is:
--
--     BEGIN
--       SELECT id FROM ob_journeys WHERE id = ? FOR UPDATE;
--       SELECT row_hash FROM ob_step_history
--         WHERE journey_id = ? ORDER BY id DESC LIMIT 1;      -- prev_hash
--       row_hash = SHA256(prev_hash || canonical_json(payload))
--       INSERT INTO ob_step_history (...);
--     COMMIT
--
-- `canonical_json` is the one in `common` (PLAN.md §3.7, golden-file
-- tested). It must not be re-implemented here — a second serialiser with a
-- different key order or timestamp format produces hashes the verifier
-- cannot reproduce, and the verifier would report tampering that is our
-- own bug. That failure is indistinguishable from the real thing, which is
-- what makes it worth a paragraph.
--
-- `prev_hash` IS NULL FOR THE FIRST ROW OF EACH JOURNEY, and that is the
-- chain's anchor rather than a missing value. The verifier walks per
-- journey and starts where `prev_hash IS NULL`.
-- ---------------------------------------------------------------------
CREATE TABLE ob_step_history (
  id                 BIGINT        NOT NULL AUTO_INCREMENT,
  -- The chain key. Every hash walk is scoped to one journey.
  journey_id         BIGINT        NOT NULL,
  step_id            BIGINT        NULL,   -- NULL = a journey-level event
  ob_client_id       BIGINT        NOT NULL,
  event_type         VARCHAR(40)   NOT NULL,
                     -- STEP_ACTIVATED|STATUS_CHANGED|OWNER_CHANGED|BLOCKED|
                     -- UNBLOCKED|WAITING_ON_CLIENT|RESUMED|COMPLETED|SKIPPED|
                     -- GATE_OPENED|JOURNEY_RELEASED|SIGNOFF_REQUESTED|…
  field_name         VARCHAR(60)   NULL,
  old_value          TEXT          NULL,
  new_value          TEXT          NULL,
  actor_id           BIGINT        NULL,   -- NULL = SYSTEM
  actor_type         VARCHAR(10)   NOT NULL DEFAULT 'USER',  -- USER|SYSTEM|CLIENT
  actor_contact_id   BIGINT        NULL,   -- set when actor_type = CLIENT
  remarks            TEXT          NULL,
  is_correction      TINYINT(1)    NOT NULL DEFAULT 0,
  corrects_entry_id  BIGINT        NULL,
  -- ascii_bin so the comparison is byte-exact and case-sensitive; a
  -- case-insensitive collation would make two different hashes compare
  -- equal, which is the one thing a hash column must never do.
  prev_hash          CHAR(64)      CHARACTER SET ascii COLLATE ascii_bin NULL,
  row_hash           CHAR(64)      CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  -- The chain walk: one journey's rows, ORDER BY id DESC for the append's
  -- prev_hash read and ASC for the verifier's sweep.
  KEY ix_ob_history_journey (journey_id, id),
  KEY ix_ob_history_step (step_id, id),
  KEY ix_ob_history_client (ob_client_id, created_at),
  KEY ix_ob_history_actor (actor_id),
  KEY ix_ob_history_event (event_type),
  KEY ix_ob_history_corrects (corrects_entry_id),
  CONSTRAINT fk_ob_history_journey
    FOREIGN KEY (journey_id) REFERENCES ob_journeys (id),
  CONSTRAINT fk_ob_history_step
    FOREIGN KEY (step_id) REFERENCES ob_journey_steps (id),
  CONSTRAINT fk_ob_history_client
    FOREIGN KEY (ob_client_id) REFERENCES ob_clients (id),
  CONSTRAINT fk_ob_history_actor
    FOREIGN KEY (actor_id) REFERENCES users (id),
  CONSTRAINT fk_ob_history_actor_contact
    FOREIGN KEY (actor_contact_id) REFERENCES ob_client_contacts (id),
  CONSTRAINT fk_ob_history_corrects
    FOREIGN KEY (corrects_entry_id) REFERENCES ob_step_history (id),
  CONSTRAINT ck_ob_history_actor_type
    CHECK (actor_type IN ('USER', 'SYSTEM', 'CLIENT')),
  -- A client actor is a contact, never a user, and vice versa. The portal
  -- writes here too (a prerequisite submission, an escalation), so this is
  -- a live shape rather than a hypothetical one.
  CONSTRAINT ck_ob_history_actor
    CHECK ((actor_type = 'USER'   AND actor_id IS NOT NULL
                                  AND actor_contact_id IS NULL)
        OR (actor_type = 'CLIENT' AND actor_contact_id IS NOT NULL
                                  AND actor_id IS NULL)
        OR (actor_type = 'SYSTEM' AND actor_id IS NULL
                                  AND actor_contact_id IS NULL)),
  CONSTRAINT ck_ob_history_correction
    CHECK ((is_correction = 0 AND corrects_entry_id IS NULL)
        OR (is_correction = 1 AND corrects_entry_id IS NOT NULL))
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- The triggers — layer 4.
--
-- Both tables are FULLY immutable. Neither has the sealing exception
-- `ticket_stage_transitions` needs (PLAN.md §3.6), because neither has a
-- column that legitimately changes after insert: a communication is said
-- and a history row is recorded, and neither is later closed.
--
--
-- MESSAGE_TEXT IS CAPPED AT 128 CHARACTERS, and going over does not
-- truncate — MySQL replaces the whole SIGNAL with ERROR 1648 "Data too
-- long for condition item 'MESSAGE_TEXT'". The write is still refused, so
-- the guarantee holds, but the caller is told nothing about immutability
-- and the SQLSTATE is 22001 rather than the 45000 declared below. Found by
-- exercising these triggers rather than by reading them: three of the six
-- messages here were 139-162 characters on the first draft. A-008's are
-- all under 105, which is why it never surfaced there. Keep them short.
-- Two triggers per table, because MySQL has no BEFORE UPDATE OR DELETE and
-- no shared trigger function. Repetitive by necessity, not by choice.
-- ---------------------------------------------------------------------
DELIMITER $$

CREATE TRIGGER trg_ob_comms_no_update BEFORE UPDATE ON ob_step_communications
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'Immutable table: ob_step_communications cannot be updated. A correction is a new row with is_correction = 1.';
END$$

CREATE TRIGGER trg_ob_comms_no_delete BEFORE DELETE ON ob_step_communications
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'Immutable table: ob_step_communications rows cannot be deleted.';
END$$

CREATE TRIGGER trg_ob_history_no_update BEFORE UPDATE ON ob_step_history
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'Immutable table: ob_step_history cannot be updated. Editing breaks the journey hash chain; append a correction.';
END$$

CREATE TRIGGER trg_ob_history_no_delete BEFORE DELETE ON ob_step_history
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'Immutable table: ob_step_history cannot be deleted. Deleting breaks the hash chain for its journey.';
END$$

DELIMITER ;
