-- =====================================================================
-- B-126 · client_credential_tokens — the single-use link that carries a
-- portal login, instead of a password that never stops working.
--
-- Source:  docs/streams/STREAM-B-MASTERS.md B-126 — "explicit
--            create/reset/disable on OB-05/OB-08; one-time credential
--            mail to the primary SPOC; audited"
--          docs/streams/STREAM-B-MASTERS.md:1033 (B-111) — "the mail
--            carries a username and a single-use link B-126 mints"
--          docs/Onboarding-Module-Plan.md §9 (OB-05, OB-08)
--
-- WHY A TABLE AND NOT A TEMPORARY PASSWORD IN THE MAIL PAYLOAD.
-- B-111 already ruled on this and this migration is that ruling made
-- real: `CLIENT_LOGIN_CREATED` "declares no password variable — the
-- payload is JSON on a row that outlives the send, so a temporary
-- password in it is a live credential in the database indefinitely".
-- `ob_notification_outbox` keeps its payload after sending. A password
-- put there is readable by anyone who can read that table, for as long
-- as the row exists, and it still works. A token here is single-use and
-- expires, and the outbox only ever holds the URL.
--
-- WHY NOT password_reset_tokens.
-- That table is A-027's and its foreign key is `REFERENCES users (id)`.
-- A client account is deliberately NOT a user — V20260905_1630's header
-- makes that separation the security property, because `ScopeResolver`
-- starts from a `users` id and a CLIENT must never be able to satisfy a
-- staff scope check. Widening that FK to accept either kind of id is the
-- one change that would undo it, and a nullable second column on a table
-- whose whole purpose is "this token belongs to this staff member" would
-- be the same hole with more steps.
--
-- The shape is otherwise A-027's, deliberately, down to the column
-- names: hash never plaintext, `used_at` NULL until redeemed, expiry
-- checked on redemption rather than swept. Two token tables that behave
-- differently are two sets of rules for one idea.
--
-- WHY `purpose` IS A COLUMN AND NOT TWO TABLES.
-- INITIAL and RESET differ in the mail they arrive in and in nothing
-- else: the same mint, the same redemption, the same expiry. What the
-- column buys is the audit answer to "was this login ever issued, or has
-- it only ever been reset" — a question the OB-05 panel asks and which a
-- second table would answer only by knowing to look in both.
--
-- WHY created_by IS NULLABLE, LIKE client_accounts.created_by.
-- Same reason A-125 gives one table over: an account created by the
-- OB-04 wizard has whoever ran the wizard, and a fixture has nobody.
-- =====================================================================

CREATE TABLE client_credential_tokens (
  id                 BIGINT        NOT NULL AUTO_INCREMENT,
  client_account_id  BIGINT        NOT NULL,

  -- SHA-256, hex-encoded — 64 characters, fixed. CHAR rather than
  -- VARCHAR because every value is exactly this wide. Same idiom and
  -- same collation as ob_signoffs.token_hash: ascii_bin, so a lookup is
  -- a byte comparison and not a case-insensitive one.
  token_hash         CHAR(64)      CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

  -- INITIAL — the credential mail for a newly created login.
  -- RESET    — a staff-initiated reset from the OB-05 panel.
  purpose            VARCHAR(16)   NOT NULL,

  -- UTC, per PLAN.md §3.1. DATETIME(6), never TIMESTAMP.
  expires_at         DATETIME(6)   NOT NULL,

  -- NULL until redeemed. Presence is what makes the token single-use —
  -- A-027's rule, restated because it is the whole point of the column.
  used_at            DATETIME(6)   NULL,

  created_by         BIGINT        NULL,
  created_at         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (id),

  -- The redemption lookup is by hash and nothing else: the caller
  -- presents a token, not an account id. UNIQUE because two rows sharing
  -- a hash would mean SecureRandom repeated itself, and silently
  -- honouring the first is worse than refusing the write.
  UNIQUE KEY uq_client_credential_token_hash (token_hash),

  -- "Invalidate every outstanding token for this account", run whenever
  -- a new one is minted and on every successful redemption. Issuing a
  -- reset must retire the previous link, or a stale mail in an inbox is
  -- a second way in that nobody remembers exists.
  KEY ix_client_credential_account (client_account_id, used_at),

  -- Housekeeping only. Nothing depends on a sweep running: expiry is
  -- checked on every redemption, so an unswept table is a storage cost
  -- rather than a hole.
  KEY ix_client_credential_expiry (expires_at),

  -- ON DELETE CASCADE, unlike client_accounts' own two foreign keys.
  -- Those name no action because RESTRICT is what forces somebody to
  -- decide; here the opposite is right — a token has no meaning without
  -- the account it opens, and a row left behind would be a hash pointing
  -- at nothing that a redemption would then have to defend against.
  CONSTRAINT fk_client_credential_account
    FOREIGN KEY (client_account_id) REFERENCES client_accounts (id) ON DELETE CASCADE,
  CONSTRAINT fk_client_credential_created_by
    FOREIGN KEY (created_by) REFERENCES users (id),

  CONSTRAINT ck_client_credential_purpose
    CHECK (purpose IN ('INITIAL', 'RESET'))

) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
