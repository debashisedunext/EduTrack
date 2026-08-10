-- =====================================================================
-- A-027 · password_reset_tokens — the "forgot password" flow.
--
-- Blueprint §10.3: "reset tokens single-use, 30-min TTL, hashed at rest".
-- Screen S-02. Contract: POST /auth/forgot-password, POST /auth/reset-password.
--
-- WHY A TABLE AND NOT REDIS
--
-- A-023's refresh tokens live in Redis precisely because the key carries
-- its own TTL and nothing has to sweep expired sessions. The opposite
-- reasoning applies here, for two reasons:
--
--   1. **A reset is an account-recovery event and belongs in the audit
--      trail.** `used_at` is evidence that a specific token was redeemed
--      at a specific time — the thing an investigation asks for after a
--      compromise. A Redis key that expires leaves no record that anything
--      ever happened.
--   2. **Losing Redis must not hand out sessions.** If the store is
--      unreachable A-023 degrades to a shorter session (fails towards
--      *less* access). A reset token vanishing early is harmless, but a
--      reset flow that cannot record single-use because the store blinked
--      would let one emailed link be redeemed twice — failing towards
--      *more* access, which is never acceptable for the one endpoint that
--      hands over an account without a password.
--
-- WHAT IS STORED, AND WHAT DELIBERATELY IS NOT
--
-- `token_hash`, never the token. The raw value exists in exactly one
-- place — the mail sent to the address on file — and is unrecoverable
-- from this table. Anyone reading a backup, a replica or this row over
-- someone's shoulder holds nothing usable, which is the same posture
-- A-023 takes for refresh tokens and A-020 for passwords.
--
-- SHA-256 and not Argon2id, for the reason `Digests` gives: the input is
-- 256 bits from SecureRandom, so there is no dictionary to slow down and
-- a work factor would buy nothing while costing ~175 ms and 64 MB per
-- redemption. Argon2id is for low-entropy secrets a human chose.
--
-- SINGLE USE IS A COLUMN, NOT A DELETE
--
-- Redemption sets `used_at` rather than removing the row. A deleted row
-- is indistinguishable from one that never existed, so a second click on
-- the same emailed link would report "invalid token" — true, but it
-- throws away the fact that the link *was* real and has already been
-- spent, which is exactly what an admin investigating "someone else
-- reset my password" needs to see. Both cases answer 410 to the caller;
-- only the record differs.
-- =====================================================================

CREATE TABLE password_reset_tokens (
  id           BIGINT        NOT NULL AUTO_INCREMENT,
  user_id      BIGINT        NOT NULL,
  -- SHA-256, hex-encoded — 64 characters, fixed. CHAR rather than
  -- VARCHAR because every value is exactly this wide.
  token_hash   CHAR(64)      NOT NULL,
  -- UTC, per PLAN.md §3.1. DATETIME(6), never TIMESTAMP.
  expires_at   DATETIME(6)   NOT NULL,
  -- NULL until redeemed. Presence is what makes the token single-use.
  used_at      DATETIME(6)   NULL,
  created_at   DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  -- The redemption lookup is by hash and nothing else — the caller
  -- presents a token, not a user id. UNIQUE because two rows sharing a
  -- hash would mean SecureRandom repeated itself, and silently honouring
  -- the first is worse than refusing the write.
  UNIQUE KEY uq_password_reset_token_hash (token_hash),
  -- "Invalidate every outstanding token for this user", run on every
  -- successful redemption.
  KEY ix_password_reset_user (user_id, used_at),
  -- Housekeeping: delete rows whose expiry has long passed. Nothing
  -- depends on this running — `expires_at` is checked on every
  -- redemption, so an unswept table is a storage cost, not a hole.
  KEY ix_password_reset_expiry (expires_at),
  CONSTRAINT fk_password_reset_user
    FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
