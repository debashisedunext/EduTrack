-- =====================================================================
-- D-034 · email_suppressions — addresses the provider has told us are bad
--
-- Blueprint §4B.6: "Bounce and complaint webhooks from the provider mark
-- the address invalid and alert the Admin."
--
-- `email_log.status = 'BOUNCED'` records that one *message* bounced. It
-- cannot answer "is this address dead?", which is the question every
-- subsequent send needs to ask — otherwise the outbox keeps mailing a
-- dead mailbox, the provider keeps bouncing it, and the sender reputation
-- that decides whether our escalation mails reach anyone at all degrades.
-- Address-level state needs its own row.
--
-- NOTE FOR STREAM A: this is a new, additive table. It touches none of
-- `tickets`, `ticket_history`, `ticket_effort_logs` or
-- `ticket_stage_transitions`, so it is outside CLAUDE.md's mandatory
-- review set — but `db/migration/` is your territory and this was written
-- by Stream D on Debashis's instruction. Please review the shape.
--
-- A complaint (the recipient pressed "this is spam") is suppressed just
-- as hard as a hard bounce. Continuing to mail someone who reported us is
-- what gets a sending domain blocklisted.
-- =====================================================================

CREATE TABLE email_suppressions (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  -- utf8mb4_0900_ai_ci is case-insensitive, so the unique key treats
  -- Ravi@example.com and ravi@example.com as one address, which is what
  -- every mail provider does.
  email           VARCHAR(150)  NOT NULL,
  reason          VARCHAR(20)   NOT NULL,     -- BOUNCE | COMPLAINT
  detail          TEXT          NULL,         -- provider diagnostic, verbatim
  provider_msg_id VARCHAR(200)  NULL,         -- the send that triggered it
  suppressed_at   DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  -- One row per address. A second bounce updates the existing row rather
  -- than accumulating duplicates, so the outbox's pre-send check stays a
  -- single indexed lookup.
  UNIQUE KEY uq_email_suppressions_email (email)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
