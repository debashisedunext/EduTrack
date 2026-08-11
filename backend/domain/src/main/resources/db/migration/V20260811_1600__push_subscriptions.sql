-- D-045 · browser push subscriptions, for users who opt in.
--
-- A row here is one browser that has granted permission, not one user: the
-- same person on a laptop and a phone has two, and the Web Push API gives each
-- its own endpoint on the push service (FCM, Mozilla, WNS) plus the two keys
-- needed to encrypt for it (RFC 8291).
--
-- THE UNIQUE KEY IS THE ENDPOINT ALONE, NOT (user_id, endpoint), and that is a
-- privacy decision rather than a modelling one. The endpoint identifies the
-- *browser*. If a second person signs in on a shared machine and subscribes,
-- the browser's endpoint is unchanged — so a row per user per endpoint would
-- leave the first person's subscription live and push their ticket alerts onto
-- somebody else's screen. Unique on the endpoint makes re-subscribing move it,
-- which is the only correct answer.
--
-- No `enabled` column: the subscription IS the opt-in. Revoking permission in
-- the browser deletes the row, and D-042's preference matrix stays about which
-- *events* are worth interrupting somebody for, on channels that exist for
-- every user. Push exists only where a browser has agreed to it.
--
-- The keys are stored as the base64url the browser hands over, untouched. They
-- are public-key material and a 16-byte shared salt, not credentials — but
-- they are still per-device identifiers, so the row dies with the user's
-- account through ON DELETE CASCADE rather than lingering.

CREATE TABLE push_subscriptions (
  id            BIGINT        NOT NULL AUTO_INCREMENT,
  user_id       BIGINT        NOT NULL,
  endpoint      VARCHAR(500)  NOT NULL
                COMMENT 'Push service URL for this browser — RFC 8030',
  p256dh        VARCHAR(128)  NOT NULL
                COMMENT 'base64url of the client public key, 65 bytes uncompressed',
  auth_secret   VARCHAR(32)   NOT NULL
                COMMENT 'base64url of the 16-byte auth secret',
  user_agent    VARCHAR(255)  NULL
                COMMENT 'So a user can tell which of their devices a row is',
  created_at    DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  last_seen_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                COMMENT 'Last successful push — a stale one is a browser that never came back',
  PRIMARY KEY (id),
  UNIQUE KEY uq_push_subscriptions_endpoint (endpoint),
  KEY ix_push_subscriptions_user (user_id),
  CONSTRAINT fk_push_subscriptions_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
