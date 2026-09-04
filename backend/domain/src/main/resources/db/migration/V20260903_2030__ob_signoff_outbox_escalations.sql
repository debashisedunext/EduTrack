-- =====================================================================
-- A-107 · Client Onboarding — sign-off, the notification outbox, and
--         the staff escalation ladder
--
-- Tables: ob_signoffs, ob_notification_outbox, ob_escalations
--
-- Source:  docs/Onboarding-Module-Plan.md §4, §7 (notifications), §8
--          (client sign-off), §5.11 (the scanner's L1→L2→L3 ladder),
--          §9 CP-05 and OB-09
--
-- ERROR 3823, PROBED BEFORE COMMITTING AS THE BACKLOG ASKED
--
-- PHASE-2-BUILD-PLAN.md §258 says `ob_signoffs` "has exactly the shape
-- that triggers 3823. Probe against the live container before
-- committing." It does, and it was:
--
--     ERROR 3823: Column 'signed_by_contact_id' cannot be used in a check
--     constraint 'ck_signed': needed in a foreign key constraint 'fk_sb'
--     referential action.
--
-- MySQL will not let a foreign key null a column that a CHECK asserts
-- over. The migration skill documents the fix as leaving that column out
-- of the CHECK.
--
-- **This file does something different, and it is not a workaround.** The
-- referential action itself is wrong here on its own terms: §8 calls the
-- recorded acceptance "the legal record", and `ON DELETE SET NULL` means
-- that deleting a contact row silently erases who signed. There is nothing
-- to work around — the correct schema has no referential action on that
-- column at all, and `ob_client_contacts` is deactivated rather than
-- deleted (A-101) precisely so that never comes up.
--
-- With plain RESTRICT the CHECK is accepted, verified on the container. So
-- the constraint that says "signed means somebody signed" survives intact,
-- which the documented workaround would have given up.
--
-- WHO IS WAITING (docs/DEPENDENCIES.md):
--     A-120 the public sign-off surface · A-121 OTP · B-110 the outbox
--     dispatcher · B-111 mail templates · B-115 OB-09 · B-116 the
--     acceptance PDF · B-117 the objection path · B-118 the go-live flip ·
--     C-115 the escalation matrix · D-101 the WhatsApp adapter ·
--     D-102 escalation notification events
-- =====================================================================


-- ---------------------------------------------------------------------
-- ob_signoffs — client acceptance, per flagged service and at go-live.
--
-- §8: secure link + OTP, recorded acceptance, PDF archived, objection
-- reverts the step. The link+OTP path works without a portal login and
-- remains the legal record, so this table must stand on its own — a
-- sign-off is not a portal artefact that happens to be stored.
--
-- `step_id` IS NULLABLE, AND THAT IS THE GO-LIVE CASE. A sign-off is
-- either against one flagged service or against the journey as a whole at
-- go-live (§5.9's Live-Green requires every journey complete "each with
-- its sign-offs"). `kind` says which, and a CHECK keeps the two agreeing —
-- a STEP sign-off naming no step, or a GO_LIVE sign-off naming one, is a
-- row nothing can render.
--
-- THE TOKEN AND THE OTP ARE BOTH STORED HASHED, and neither is the other's
-- backup. The token authenticates the *link* — it arrives by email and
-- identifies which sign-off is being answered. The OTP authenticates the
-- *person*, sent separately at the moment of signing. A leaked mailbox
-- yields the first and not the second, which is the entire point of having
-- two; storing either in the clear would collapse that back to one.
--
-- `otp_attempts` is here rather than in Redis because the lockout has to
-- survive a restart. §11 asks for rate limits and lockout on the public
-- surface, and a counter that resets when the process does is not a
-- lockout.
--
-- WHAT IS DELIBERATELY ABSENT: no CSAT column. §1.1 item 9 puts a
-- one-question survey with the final sign-off confirmation, and B-119 owns
-- it as "a public one-question page, storage, and a summary" — its own
-- page and its own storage. Hanging a score off this row would make the
-- legal record and a satisfaction survey the same object, and an objection
-- that reverts a step would take the score with it.
-- ---------------------------------------------------------------------
CREATE TABLE ob_signoffs (
  id                    BIGINT        NOT NULL AUTO_INCREMENT,
  ob_client_id          BIGINT        NOT NULL,
  journey_id            BIGINT        NOT NULL,
  -- NULL for a GO_LIVE sign-off. See the note above.
  step_id               BIGINT        NULL,
  kind                  VARCHAR(10)   NOT NULL,   -- STEP|GO_LIVE
  status                VARCHAR(12)   NOT NULL DEFAULT 'PENDING',
                        -- PENDING|SIGNED|OBJECTED|EXPIRED|CANCELLED

  -- ── the secure link (§8) ────────────────────────────────────────────
  -- SHA-256 of the token, never the token. The value is emailed once and
  -- is not recoverable from here; a leak of this table does not yield a
  -- working link.
  token_hash            CHAR(64)      CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  token_expires_at      DATETIME(6)   NOT NULL,

  -- ── the OTP, which authenticates the person rather than the link ────
  otp_hash              CHAR(64)      CHARACTER SET ascii COLLATE ascii_bin NULL,
  otp_expires_at        DATETIME(6)   NULL,
  -- Persisted rather than held in Redis: a lockout that resets when the
  -- process restarts is not a lockout (§11).
  otp_attempts          INT           NOT NULL DEFAULT 0,

  -- ── who was asked, and who answered ─────────────────────────────────
  requested_by          BIGINT        NULL,
  requested_at          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  sent_to_contact_id    BIGINT        NOT NULL,
  -- No ON DELETE SET NULL. See the header: this is the legal record, and
  -- nulling the signatory is the one thing it must never do.
  signed_by_contact_id  BIGINT        NULL,
  signed_at             DATETIME(6)   NULL,
  -- Recorded acceptance (§8, and decision 5 of PHASE-2-BUILD-PLAN: recorded
  -- rather than statutory e-sign for v1). These two are the record.
  signed_ip             VARCHAR(45)   NULL,       -- 45 = an IPv6 literal
  signed_user_agent     VARCHAR(500)  NULL,

  -- ── the objection path (§8: an objection reverts the step) ──────────
  objected_at           DATETIME(6)   NULL,
  objection_note        VARCHAR(2000) NULL,

  -- B-116's archived PDF. Object-storage key, never the bytes.
  pdf_storage_key       VARCHAR(400)  NULL,

  created_at            DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at            DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                          ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  -- The public surface's only lookup: A-120 resolves a link by hashing the
  -- token it was given. Unique because two sign-offs sharing a token is a
  -- cross-client leak, not a collision to tolerate.
  UNIQUE KEY uq_ob_signoffs_token (token_hash),
  KEY ix_ob_signoffs_client (ob_client_id, status),
  KEY ix_ob_signoffs_journey (journey_id, status),
  KEY ix_ob_signoffs_step (step_id),
  -- CP-05's list: one client's pending and past sign-offs, newest first.
  KEY ix_ob_signoffs_contact (sent_to_contact_id, status, requested_at),
  KEY ix_ob_signoffs_signed_by (signed_by_contact_id),
  KEY ix_ob_signoffs_requested_by (requested_by),
  CONSTRAINT fk_ob_signoffs_client
    FOREIGN KEY (ob_client_id) REFERENCES ob_clients (id),
  CONSTRAINT fk_ob_signoffs_journey
    FOREIGN KEY (journey_id) REFERENCES ob_journeys (id),
  CONSTRAINT fk_ob_signoffs_step
    FOREIGN KEY (step_id) REFERENCES ob_journey_steps (id),
  CONSTRAINT fk_ob_signoffs_sent_to
    FOREIGN KEY (sent_to_contact_id) REFERENCES ob_client_contacts (id),
  CONSTRAINT fk_ob_signoffs_signed_by
    FOREIGN KEY (signed_by_contact_id) REFERENCES ob_client_contacts (id),
  CONSTRAINT fk_ob_signoffs_requested_by
    FOREIGN KEY (requested_by) REFERENCES users (id),
  CONSTRAINT ck_ob_signoffs_kind
    CHECK (kind IN ('STEP', 'GO_LIVE')),
  CONSTRAINT ck_ob_signoffs_status
    CHECK (status IN ('PENDING', 'SIGNED', 'OBJECTED', 'EXPIRED', 'CANCELLED')),
  -- A STEP sign-off names its step; a GO_LIVE one does not.
  CONSTRAINT ck_ob_signoffs_step_matches_kind
    CHECK ((kind = 'STEP'    AND step_id IS NOT NULL)
        OR (kind = 'GO_LIVE' AND step_id IS NULL)),
  -- Signed means somebody signed, at a moment. This is the constraint the
  -- documented 3823 workaround would have cost; RESTRICT keeps it.
  CONSTRAINT ck_ob_signoffs_signed
    CHECK ((signed_at IS NULL     AND signed_by_contact_id IS NULL)
        OR (signed_at IS NOT NULL AND signed_by_contact_id IS NOT NULL)),
  -- An objection carries its reason — it reverts a step, and the owner has
  -- to be told what to fix.
  CONSTRAINT ck_ob_signoffs_objection
    CHECK (objected_at IS NULL OR objection_note IS NOT NULL)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- ob_notification_outbox — §7's queue.
--
-- **THIS ONE IS MUTABLE ON PURPOSE, and it is the only table in this
-- module's write path that is.** Every neighbour A-105 and A-106 created
-- is append-only, so the asymmetry deserves saying out loud rather than
-- being noticed later and "fixed": a queue row's whole life is a state
-- change. PENDING → SENDING → SENT, or → FAILED and back to PENDING on
-- retry. An append-only outbox would mean a new row per attempt and a
-- dispatcher that has to fold them to decide whether anything was actually
-- sent — which is how a message gets delivered twice.
--
-- What makes that safe is that this table is not a record of what
-- happened. `ob_step_history` is (A-106, hash-chained), and every event
-- worth auditing writes there as well. This is the machinery that carries
-- it, and machinery is allowed to change state.
--
-- The same argument `email_log` already makes on the ticketing side, which
-- PLAN.md §2.2 makes the queue itself rather than a journal beside one.
--
-- TWO RECIPIENT COLUMNS, exactly as `ob_step_communications` has two
-- author columns and for the same reason: §7's events go to staff (a
-- verifier, a manager digest, a service owner) and to clients (the SPOC,
-- the one-time password, a sign-off request), and those live in different
-- tables. Exactly one is set, enforced below.
--
-- `provider_message_id` and `delivered_at` are the webhook's half. D-101's
-- WhatsApp adapter and the mail provider both report delivery
-- asynchronously, after the send has already succeeded — so "sent" and
-- "delivered" are two facts with two timestamps, and collapsing them would
-- make a bounced message indistinguishable from a read one.
--
-- `dedupe_key` IS THE UNMUTABLE-NOTIFICATION PROBLEM'S OTHER HALF. A-128
-- stops a client raising two open escalations; this stops the *same event*
-- being queued twice when a scanner pass overlaps its predecessor — the
-- scanner sweeps on a timer and a slow pass can still be running when the
-- next begins. Unique among unsent rows only, so a genuine repeat of the
-- same event later (a second TAT reminder, a re-requested sign-off) is
-- allowed once the first has left the queue. Sixth use of the
-- generated-column partial-index idiom.
-- ---------------------------------------------------------------------
CREATE TABLE ob_notification_outbox (
  id                     BIGINT        NOT NULL AUTO_INCREMENT,
  event_key              VARCHAR(60)   NOT NULL,
                         -- GATE_OPENED|PREREQ_SUBMITTED|PREREQ_VERIFIED|
                         -- TAT_REMINDER|ESCALATION_RAISED|SIGNOFF_REQUESTED|…
  channel                VARCHAR(12)   NOT NULL,   -- EMAIL|WHATSAPP|IN_APP
  -- Exactly one recipient column is set, matching the type.
  recipient_type         VARCHAR(10)   NOT NULL,   -- STAFF|CLIENT
  recipient_user_id      BIGINT        NULL,
  recipient_contact_id   BIGINT        NULL,

  -- Context, for the deep link and for the dashboard drill-down. All
  -- nullable: a "client login created" notification has no step, and a
  -- manager digest has no single journey.
  ob_client_id           BIGINT        NULL,
  journey_id             BIGINT        NULL,
  step_id                BIGINT        NULL,

  -- The rendered message's variables, not the rendered message. B-111
  -- renders from a template at send time, so a template correction reaches
  -- everything still queued.
  payload                JSON          NULL,

  status                 VARCHAR(12)   NOT NULL DEFAULT 'PENDING',
                         -- PENDING|SENDING|SENT|FAILED|CANCELLED
  attempts               INT           NOT NULL DEFAULT 0,
  next_attempt_at        DATETIME(6)   NULL,
  last_error             VARCHAR(1000) NULL,

  -- The provider's half, reported asynchronously by webhook.
  provider_message_id    VARCHAR(200)  NULL,
  sent_at                DATETIME(6)   NULL,
  delivered_at           DATETIME(6)   NULL,
  failed_at              DATETIME(6)   NULL,

  -- One queued copy per event. See the note above.
  dedupe_key             VARCHAR(200)  NOT NULL,
  -- The dedupe key while the row is still queued; NULL once it has left.
  queued_dedupe_key      VARCHAR(200)
      GENERATED ALWAYS AS (IF(status IN ('PENDING', 'SENDING'), dedupe_key, NULL)) STORED,

  created_at             DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at             DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                           ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_ob_outbox_queued (queued_dedupe_key),
  -- B-110's dispatcher claim: the oldest due work, by status.
  KEY ix_ob_outbox_due (status, next_attempt_at, id),
  -- The webhook's lookup, arriving with only the provider's own id.
  KEY ix_ob_outbox_provider (provider_message_id),
  KEY ix_ob_outbox_client (ob_client_id, created_at),
  KEY ix_ob_outbox_recipient_user (recipient_user_id),
  KEY ix_ob_outbox_recipient_contact (recipient_contact_id),
  KEY ix_ob_outbox_journey (journey_id),
  KEY ix_ob_outbox_step (step_id),
  CONSTRAINT fk_ob_outbox_recipient_user
    FOREIGN KEY (recipient_user_id) REFERENCES users (id),
  CONSTRAINT fk_ob_outbox_recipient_contact
    FOREIGN KEY (recipient_contact_id) REFERENCES ob_client_contacts (id),
  CONSTRAINT fk_ob_outbox_client
    FOREIGN KEY (ob_client_id) REFERENCES ob_clients (id),
  CONSTRAINT fk_ob_outbox_journey
    FOREIGN KEY (journey_id) REFERENCES ob_journeys (id),
  CONSTRAINT fk_ob_outbox_step
    FOREIGN KEY (step_id) REFERENCES ob_journey_steps (id),
  CONSTRAINT ck_ob_outbox_channel
    CHECK (channel IN ('EMAIL', 'WHATSAPP', 'IN_APP')),
  CONSTRAINT ck_ob_outbox_status
    CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'CANCELLED')),
  CONSTRAINT ck_ob_outbox_recipient_type
    CHECK (recipient_type IN ('STAFF', 'CLIENT')),
  CONSTRAINT ck_ob_outbox_recipient
    CHECK ((recipient_type = 'STAFF'  AND recipient_user_id IS NOT NULL
                                      AND recipient_contact_id IS NULL)
        OR (recipient_type = 'CLIENT' AND recipient_contact_id IS NOT NULL
                                      AND recipient_user_id IS NULL))
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- ob_escalations — the scanner's L1 → L2 → L3 ladder (§5.11).
--
-- NOT THE SAME THING AS `ob_client_escalations` (A-128), AND THE NAMES ARE
-- CLOSE ENOUGH THAT THIS HAS TO BE SAID:
--
--   ob_escalations         raised by the SYSTEM. A TAT breach the scanner
--                          found. Climbs L1 → L2 → L3 through the matrix
--                          C-115 configures. The client never sees it.
--   ob_client_escalations  raised by the CLIENT, from the portal, against
--                          a service they are unhappy with. One open per
--                          service. Staff resolve it and the client is
--                          told.
--
-- Different origin, different audience, different lifecycle. Merging them
-- would put a client's complaint and a missed deadline in one list and
-- make "how many escalations" a question with no useful answer.
--
-- ONE OPEN ESCALATION PER (STEP, LEVEL), not per step. The ladder is meant
-- to climb: a step that breaches badly enough gets an L1, then an L2 above
-- it, then an L3, and all three may be open at once because each names a
-- different person who now owns it. Uniqueness per step alone would make
-- the second rung overwrite the first and lose who was told. Seventh use
-- of the partial-index idiom.
-- ---------------------------------------------------------------------
CREATE TABLE ob_escalations (
  id                  BIGINT        NOT NULL AUTO_INCREMENT,
  ob_client_id        BIGINT        NOT NULL,
  journey_id          BIGINT        NOT NULL,
  step_id             BIGINT        NOT NULL,
  level               VARCHAR(4)    NOT NULL,   -- L1|L2|L3
  reason              VARCHAR(30)   NOT NULL,   -- TAT_BREACH|AMBER|BLOCKED_TOO_LONG|…
  -- Who it was escalated to. NULL only if the matrix resolved nobody,
  -- which is itself worth seeing on the dashboard.
  escalated_to        BIGINT        NULL,
  escalated_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  acknowledged_by     BIGINT        NULL,
  acknowledged_at     DATETIME(6)   NULL,
  resolved_by         BIGINT        NULL,
  resolved_at         DATETIME(6)   NULL,
  resolution_note     VARCHAR(2000) NULL,
  -- 1 while open, NULL once resolved. Scoped with `level` below so the
  -- ladder can hold all three rungs at once.
  open_key            TINYINT(1)
      GENERATED ALWAYS AS (IF(resolved_at IS NULL, 1, NULL)) STORED,
  created_at          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                        ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_ob_escalations_open (step_id, level, open_key),
  -- OB-02 and §10's breach log: what is open, oldest first.
  KEY ix_ob_escalations_open (open_key, escalated_at),
  KEY ix_ob_escalations_client (ob_client_id, escalated_at),
  KEY ix_ob_escalations_journey (journey_id),
  -- "What has been escalated to me": the owner's own queue.
  KEY ix_ob_escalations_to (escalated_to, open_key),
  KEY ix_ob_escalations_acknowledged_by (acknowledged_by),
  KEY ix_ob_escalations_resolved_by (resolved_by),
  CONSTRAINT fk_ob_escalations_client
    FOREIGN KEY (ob_client_id) REFERENCES ob_clients (id),
  CONSTRAINT fk_ob_escalations_journey
    FOREIGN KEY (journey_id) REFERENCES ob_journeys (id),
  CONSTRAINT fk_ob_escalations_step
    FOREIGN KEY (step_id) REFERENCES ob_journey_steps (id),
  CONSTRAINT fk_ob_escalations_to
    FOREIGN KEY (escalated_to) REFERENCES users (id),
  CONSTRAINT fk_ob_escalations_acknowledged_by
    FOREIGN KEY (acknowledged_by) REFERENCES users (id),
  CONSTRAINT fk_ob_escalations_resolved_by
    FOREIGN KEY (resolved_by) REFERENCES users (id),
  CONSTRAINT ck_ob_escalations_level
    CHECK (level IN ('L1', 'L2', 'L3')),
  CONSTRAINT ck_ob_escalations_acknowledged
    CHECK ((acknowledged_at IS NULL AND acknowledged_by IS NULL)
        OR (acknowledged_at IS NOT NULL AND acknowledged_by IS NOT NULL)),
  CONSTRAINT ck_ob_escalations_resolved
    CHECK ((resolved_at IS NULL AND resolved_by IS NULL)
        OR (resolved_at IS NOT NULL AND resolved_by IS NOT NULL))
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
