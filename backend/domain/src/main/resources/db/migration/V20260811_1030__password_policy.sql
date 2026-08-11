-- =====================================================================
-- A-028 · password policy — the history table and the expiry clock.
--
-- Blueprint §10.3: "Min 8 chars with upper + lower + digit + symbol ·
-- no reuse of last 3 · optional 90-day expiry".
--
-- Composition is not here. It is checkable from the request alone, so it
-- lives in Bean Validation where springdoc turns it into schema and orval
-- turns that into Zod — one rule enforced in the browser and on the
-- server (PLAN.md §2.2, deviation D-4). This migration carries only the
-- two rules that need state the request does not have: what the password
-- USED to be, and when it last changed.
--
-- WHY HISTORY IS A SEPARATE TABLE AND NOT COLUMNS ON `users`
--
-- The obvious shape is `prev_hash_1/2/3` on `users`, and it is wrong for
-- three reasons. Changing the depth from three to five becomes a
-- migration plus a rewrite of every read; the shift-along UPDATE has to
-- move all three columns in the right order or it silently loses one;
-- and the columns are NULL for the entire first three changes, so every
-- reader carries the same three null checks. A row per historical
-- password makes depth a LIMIT, insertion an INSERT, and absence simply
-- an empty result.
--
-- WHAT IS STORED
--
-- The Argon2id hash, exactly as `users.password_hash` held it. Not a
-- SHA-256 of it, and not the password: reuse is checked by running the
-- candidate through `PasswordEncoder.matches` against each stored hash,
-- which is the only way to compare a plaintext to a salted digest. Each
-- row is therefore as sensitive as the live hash and no more — a leak of
-- this table is a leak of passwords that have already been retired.
--
-- THE COST, STATED PLAINLY
--
-- Checking three history rows costs three Argon2id verifications at
-- 64 MB and 3 iterations each — roughly half a second of CPU and 192 MB
-- of transient memory per password change. That is acceptable because
-- setting a password is rare and already costs one such hash. It would
-- NOT be acceptable on a login path, which is why nothing in A-020 ever
-- consults this table. `PasswordPolicy` checks composition first and
-- only reaches history if the cheap rules pass, so a caller sending
-- "abc" pays nothing.
--
-- NO FK ON DELETE CASCADE, DELIBERATELY
--
-- ON DELETE CASCADE would be convenient and is wrong here: a user row is
-- never hard-deleted in this system (`is_active = 0` is how an account
-- ends, per A-003), so the cascade would only ever fire on a manual
-- DELETE — which is exactly the case where the history is evidence
-- somebody may want.
-- =====================================================================

CREATE TABLE password_history (
  id            BIGINT        NOT NULL AUTO_INCREMENT,
  user_id       BIGINT        NOT NULL,
  -- The full Argon2id encoding, same shape as users.password_hash.
  -- VARCHAR(255) matches that column so a hash that fits there fits here.
  password_hash VARCHAR(255)  NOT NULL,
  -- When this password STOPPED being current — i.e. when it was replaced.
  -- UTC, per PLAN.md §3.1. DATETIME(6), never TIMESTAMP.
  retired_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  -- The one read this table has: "the N most recently retired passwords
  -- for this user". Descending on retired_at so the LIMIT walks the index
  -- rather than sorting.
  KEY ix_password_history_user (user_id, retired_at DESC),
  CONSTRAINT fk_password_history_user
    FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- users.password_changed_at — the clock the optional 90-day expiry reads.
--
-- NOT NULL with a default, and backfilled below, so there is no "never
-- changed" case for a reader to interpret. A nullable column here would
-- force every caller to decide what NULL means, and the two plausible
-- answers — "brand new, not expired" and "unknown, therefore expired" —
-- differ by whether the whole organisation is locked out on the morning
-- the flag is first switched on.
-- ---------------------------------------------------------------------
ALTER TABLE users
  ADD COLUMN password_changed_at DATETIME(6) NOT NULL
    DEFAULT CURRENT_TIMESTAMP(6) AFTER must_change_password;

-- Existing rows: treat the account's creation as the last password
-- change. That is true for any account still on its admin-generated
-- password, and for the rest it is the most conservative reading
-- available — it can only make a password look older than it is, which
-- errs towards asking someone to rotate rather than towards letting a
-- stale one stand.
--
-- Runs before any expiry check can exist, so no session is affected.
UPDATE users SET password_changed_at = created_at;
