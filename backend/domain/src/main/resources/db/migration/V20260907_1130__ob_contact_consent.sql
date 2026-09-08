-- =====================================================================
-- B-103 · SPOC consent — when it was given, how, and on whose word.
--
-- Adds three columns to `ob_client_contacts` and one insert-only table.
--
-- Source:  docs/streams/STREAM-B-MASTERS.md B-103 — "capture
--            `whatsapp_opt_in` with its timestamp and source NOW"
--          docs/PHASE-2-BUILD-PLAN.md §6.1 — WhatsApp is out of scope
--            for phase 2, and this column is the single named exception
--          docs/Onboarding-Module-Plan.md §4 (`ob_client_contacts`)
--
-- WHY A DEFERRED FEATURE GETS A MIGRATION ANYWAY.
-- PHASE-2-BUILD-PLAN.md §6.1 defers WhatsApp entirely — no provider, no
-- adapter, no webhook — and then lists exactly one thing that is built
-- regardless: "`whatsapp_opt_in` capture on contacts (B-103). Consent
-- cannot be backfilled. Every SPOC boarded without it has to be
-- re-approached before a single message can be sent … this is the one
-- item that is genuinely irreversible."
--
-- V20260903_1210 gave that column a TINYINT and nothing else. A bare
-- boolean is not a consent record: it says somebody, at some point,
-- ticked a box, and it cannot answer the two questions that matter when
-- the consent is challenged — WHEN, and ON WHAT BASIS. Those are
-- exactly the facts that cannot be reconstructed later, which is the
-- whole argument for building this ahead of the feature that reads it.
--
-- WHY THE EVENT TABLE, AND NOT JUST THE THREE COLUMNS.
-- Three columns record the state consent is in. They cannot record that
-- it was ever in another one. Consent is withdrawable — a SPOC asks to
-- stop being messaged and the flag flips to 0 — and if the flip
-- overwrites the stamp, the organisation loses its evidence that
-- consent stood at the time the messages it already sent went out. That
-- is the dispute the record exists for. So the grant and the withdrawal
-- are both events, and the columns are a cache of the latest one.
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
-- The three columns.
--
-- `whatsapp_opt_in_source` is HOW the client gave consent, not which
-- screen recorded it. That distinction decides who supplies the value:
-- if it named the screen, every staff-entered consent in the system
-- would read `OB05_FORM` and the column would carry no information at
-- all. Who typed it and when they typed it are recorded separately, in
-- the two columns beside it.
--
-- `whatsapp_opt_in_by` is NULL-able and stays that way even after
-- B-126: a consent the client gives through the portal is attributed to
-- no staff user, and inventing one would be a false attribution on the
-- one column whose job is attribution. No `ON DELETE` clause by design
-- — `users` rows are deactivated, never deleted.
-- ---------------------------------------------------------------------
ALTER TABLE ob_client_contacts
  ADD COLUMN whatsapp_opt_in_at     DATETIME(6)  NULL AFTER whatsapp_opt_in,
  ADD COLUMN whatsapp_opt_in_source VARCHAR(32)  NULL AFTER whatsapp_opt_in_at,
  ADD COLUMN whatsapp_opt_in_by     BIGINT       NULL AFTER whatsapp_opt_in_source,
  ADD CONSTRAINT fk_ob_client_contacts_opt_in_by
    FOREIGN KEY (whatsapp_opt_in_by) REFERENCES users (id);


-- ---------------------------------------------------------------------
-- Backfill, and it is deliberately honest rather than tidy.
--
-- Rows already carrying `whatsapp_opt_in = 1` were written before this
-- file existed, so their timestamp and basis are genuinely not known.
-- `UNRECORDED` says so, and `created_at` is the timestamp because it is
-- the one true bound available: the consent cannot have been recorded
-- before the row was.
--
-- The alternative — inventing a plausible source — would put the module
-- in exactly the position B-103 exists to prevent, and worse: an
-- unrecorded consent looks unrecorded and gets re-approached, while a
-- fabricated one looks settled and never does.
--
-- `UNRECORDED` is not in the API's vocabulary. Nothing can write it
-- after this statement — see ObConsentSource.
-- ---------------------------------------------------------------------
UPDATE ob_client_contacts
   SET whatsapp_opt_in_at = created_at,
       whatsapp_opt_in_source = 'UNRECORDED'
 WHERE whatsapp_opt_in = 1
   AND whatsapp_opt_in_at IS NULL;


-- ---------------------------------------------------------------------
-- The two columns move with the flag, or not at all.
--
-- Added AFTER the backfill, because MySQL validates a CHECK against the
-- rows already in the table: stated before it, this would refuse to
-- apply on any database that has loaded the B-101 corpus, six of whose
-- SPOCs carry consent.
--
-- Withdrawal clears both back to NULL rather than leaving a stale stamp
-- beside a 0. The history of the grant lives in
-- `ob_contact_consent_events`; these columns are only ever the current
-- position, and a position reading "not consented, since March" is two
-- facts pretending to be one.
-- ---------------------------------------------------------------------
ALTER TABLE ob_client_contacts
  ADD CONSTRAINT ck_ob_client_contacts_consent
    CHECK ((whatsapp_opt_in = 0
            AND whatsapp_opt_in_at IS NULL
            AND whatsapp_opt_in_source IS NULL)
        OR (whatsapp_opt_in = 1
            AND whatsapp_opt_in_at IS NOT NULL
            AND whatsapp_opt_in_source IS NOT NULL));


-- ---------------------------------------------------------------------
-- ob_contact_consent_events — every grant and every withdrawal, kept.
--
-- INSERT-ONLY, enforced by the two triggers below on the pattern A-105
-- and A-106 set for `ob_step_clock_events` and `ob_step_communications`.
-- NOT hash-chained: B-101 recorded that the onboarding chain payload is
-- unwritten and that inventing one would hand A-123's verifier rows
-- that fail to verify. Same call, same debt.
--
-- `channel` exists on day one although only WHATSAPP is ever written
-- today. Plan §7 specifies email as well, and a per-contact preference
-- for it is a plausible next ask; a table named for one channel
-- acquires the second as a column called `is_email` and then disagrees
-- with itself. One column now is cheaper than that.
--
-- NO CASCADE on the contact key, unlike every other child of
-- `ob_client_contacts`. MySQL does not fire triggers on cascading
-- foreign-key actions, so a CASCADE here would be a documented hole
-- straight through the two triggers below — the consent record removed
-- silently by a delete one table away. RESTRICT never fires in
-- practice, because a contact who leaves is deactivated rather than
-- deleted (`is_active`, B-027's precedent), and where it does fire the
-- refusal is the correct answer.
-- ---------------------------------------------------------------------
CREATE TABLE ob_contact_consent_events (
  id                   BIGINT       NOT NULL AUTO_INCREMENT,
  ob_client_contact_id BIGINT       NOT NULL,
  channel              VARCHAR(16)  NOT NULL DEFAULT 'WHATSAPP',
  -- 1 = consent given, 0 = consent withdrawn. Both are events.
  opted_in             TINYINT(1)   NOT NULL,
  -- NULL only on a withdrawal: "how it was given" has no counterpart
  -- when it is being taken away, and a withdrawal is honoured whatever
  -- channel the client said it through.
  source               VARCHAR(32)  NULL,
  -- The staff user who recorded it. NULL for a client-portal action.
  recorded_by          BIGINT       NULL,
  recorded_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  note                 VARCHAR(500) NULL,
  PRIMARY KEY (id),
  KEY ix_ob_contact_consent_contact (ob_client_contact_id, recorded_at),
  CONSTRAINT fk_ob_contact_consent_contact
    FOREIGN KEY (ob_client_contact_id) REFERENCES ob_client_contacts (id),
  CONSTRAINT fk_ob_contact_consent_recorded_by
    FOREIGN KEY (recorded_by) REFERENCES users (id),
  CONSTRAINT ck_ob_contact_consent_source
    CHECK (opted_in = 0 OR source IS NOT NULL)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- MESSAGE_TEXT stays under 128 characters. Over that, MySQL replaces
-- the whole SIGNAL with ERROR 1648 and the caller is told nothing about
-- immutability — V20260903_1745's note, which found it the hard way.
-- ---------------------------------------------------------------------
DELIMITER $$

CREATE TRIGGER trg_ob_consent_no_update BEFORE UPDATE ON ob_contact_consent_events
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'Immutable table: consent events cannot be updated. Withdrawal is a new row.';
END$$

CREATE TRIGGER trg_ob_consent_no_delete BEFORE DELETE ON ob_contact_consent_events
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'Immutable table: consent events cannot be deleted.';
END$$

DELIMITER ;
