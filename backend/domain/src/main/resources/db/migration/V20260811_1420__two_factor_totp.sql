-- =====================================================================
-- A-029 · two-factor authentication (TOTP). Blueprint §10.1 and S-04:
-- "Two-Factor Verification (recommended, optional per user) — 6-digit
-- TOTP", reached in the login flow at `2FA enabled? → TOTP challenge`.
--
-- THREE COLUMNS, NOT ONE FLAG
--
-- The intuitive schema is `totp_secret` plus `totp_enabled`, and it has
-- no way to express the state every enrolment passes through: a secret
-- has been generated and shown to the user, but they have not yet proved
-- they can read codes from it. Enabling at the moment the secret is
-- created locks out anyone whose QR did not scan, whose clock is wrong,
-- or who closed the tab — and locks them out of the account they were
-- trying to protect.
--
--   totp_secret        set at setup, meaningless until confirmed
--   totp_confirmed_at  NULL until the user echoes a valid code back
--   totp_enabled       the only column the login path reads
--
-- So setup writes a secret and nothing else changes; confirmation is
-- what flips `totp_enabled`. A secret that is never confirmed is inert
-- and is simply overwritten by the next setup attempt.
--
-- THE SECRET IS ENCRYPTED, NOT HASHED
--
-- Unlike a password, this value has to be recoverable: verifying a code
-- means recomputing HMAC-SHA1 over the shared secret, so a one-way hash
-- would make it useless. It is therefore encrypted at rest with a key
-- held outside the database (`edutrack.auth.totp.encryption-key`,
-- following the JWT_SIGNING_SECRET pattern), which means a dump of this
-- table alone yields nothing usable.
--
-- VARCHAR(255) rather than the ~32 characters a Base32 secret needs:
-- the stored form is ciphertext plus its IV, hex-encoded, and sizing the
-- column to the plaintext is how a later key or cipher change turns into
-- silent truncation.
--
-- RECOVERY CODES ARE NOT OPTIONAL, THOUGH THE BLUEPRINT DOES NOT MENTION
-- THEM
--
-- A second factor that lives on one device means a lost, wiped or
-- replaced phone is a permanently locked account whose only exit is an
-- administrator. Every real TOTP deployment carries recovery codes for
-- exactly this, and adding them later would mean a second migration and
-- a re-enrolment for everyone who signed up before it. They are stored
-- hashed and single-use, for the reasons A-027's reset tokens are.
--
-- Argon2id here, not SHA-256 — unlike a reset token, a recovery code is
-- short enough to be worth attacking offline if this table leaks. The
-- cost is paid once per redemption, which is rare by definition.
-- =====================================================================

ALTER TABLE users
  -- Encrypted at rest. NULL until the user starts enrolment.
  ADD COLUMN totp_secret       VARCHAR(255) NULL AFTER password_changed_at,
  -- The only column the login path consults. Flipped by confirmation,
  -- never by setup — see the header.
  ADD COLUMN totp_enabled      TINYINT(1)   NOT NULL DEFAULT 0 AFTER totp_secret,
  -- When the user proved they could read a code from the secret. UTC.
  ADD COLUMN totp_confirmed_at DATETIME(6)  NULL AFTER totp_enabled;


-- ---------------------------------------------------------------------
-- totp_recovery_codes — the way back in when the authenticator is gone.
--
-- One row per code rather than a JSON array on `users`: single-use has
-- to be recorded per code, and a JSON column would mean read-modify-write
-- on every redemption, which two concurrent uses of different codes can
-- interleave and lose.
--
-- `used_at` rather than a delete, for A-027's reason — a spent code is
-- evidence, and "this account was recovered on the 3rd" is exactly what
-- an investigation asks for.
-- ---------------------------------------------------------------------
CREATE TABLE totp_recovery_codes (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  user_id     BIGINT       NOT NULL,
  -- Argon2id, same encoding as users.password_hash.
  code_hash   VARCHAR(255) NOT NULL,
  used_at     DATETIME(6)  NULL,
  created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  -- "the unused codes for this user" — the redemption read, and the
  -- count shown on the settings screen.
  KEY ix_totp_recovery_user (user_id, used_at),
  CONSTRAINT fk_totp_recovery_user
    FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
