-- ---------------------------------------------------------------------
-- A-125 · client_accounts — the identity layer for the CLIENT principal.
--
-- The portal bridge. Everything in the product so far authenticates a
-- member of staff: `users` has an emp_code, a reporting manager, a daily
-- capacity and a role from blueprint §2. A client contact has none of
-- those and must never acquire them by being squeezed into that table —
-- a nullable emp_code and a CLIENT role_id would put an external party
-- one mis-written WHERE clause away from every staff-scoped query in the
-- system.
--
-- So this is a separate table, and the separation is the security
-- property. `ScopeResolver` and `OnboardingScopeResolver` both start from
-- a `users` id; a CLIENT has none, so a portal caller cannot accidentally
-- satisfy a staff scope check by resolving to user 0 or to anybody. That
-- mirrors CallerIdentity's own rule for an unreadable id: scoped to
-- nothing rather than to somebody.
--
-- WHY TWO NULLABLE CLIENT REFERENCES AND NOT ONE
--
-- There are two client masters and they are not the same table:
--
--   clients      the ticketing master (B-027) — support plan, SLA policy,
--                account manager. A client who raises tickets.
--   ob_clients   the onboarding master (A-101) — onboarding date, PAN,
--                sales person, overall status. A client being boarded.
--
-- The same organisation is often both, sometimes only one, and the two
-- rows are not linked: nothing in the schema says clients.id 5 and
-- ob_clients.id 9 are the same company, because nothing yet has had to
-- know. A portal login has to work for a client who exists in either or
-- both, which is what the pair of nullable columns plus the at-least-one
-- CHECK expresses. When the two masters are eventually reconciled this
-- table is where the correspondence already lives.
--
-- MYSQL 8.4 · ERROR 3823 SHAPES THESE FOREIGN KEYS
--
-- A column cannot sit inside a CHECK constraint and be the target of a
-- foreign key's referential action. Probed against the container before
-- this file was written, and the results are worth recording so nobody
-- re-derives them:
--
--   ON DELETE SET NULL + the CHECK   ERROR 3823, refused outright
--   ON DELETE CASCADE  + the CHECK   accepted
--   no referential action + CHECK    accepted
--
-- Both survivors work, and CASCADE is the wrong one. An account linked to
-- both masters would be destroyed by deleting either: remove an
-- ob_clients row and the client silently loses the ticketing portal
-- access that had nothing to do with onboarding. RESTRICT — the default,
-- written by naming no action — refuses the delete instead and makes
-- somebody decide. Deactivate the account first; `is_active` exists for
-- exactly that, and a deactivated login preserves what it signed.
-- ---------------------------------------------------------------------

CREATE TABLE client_accounts (
  id                    BIGINT        NOT NULL AUTO_INCREMENT,

  -- Generated, never chosen. A client does not pick a username: the
  -- service mints one from the client and contact (A-125's generator) so
  -- that two clients cannot race for the same string and so that a
  -- guessable handle is not derived from an email a competitor may know.
  username              VARCHAR(50)   NOT NULL,

  -- Argon2id, written by common/security/PasswordHashing — the same
  -- encoder `users.password_hash` uses. One hashing path, deliberately:
  -- a second one is a second set of parameters to keep in step, and
  -- AuthenticationService's constant-cost property (every outcome pays a
  -- full verification) only holds if both sides cost the same.
  password_hash         VARCHAR(255)  NOT NULL,

  -- The two masters. Nullable individually, never both — see the CHECK.
  -- No referential action on either, for the 3823 reason above.
  client_id             BIGINT        NULL,
  ob_client_id          BIGINT        NULL,

  -- Who this login belongs to, denormalised on purpose.
  --
  -- ob_client_contacts already holds the SPOC's name and email, and a FK
  -- to it was the obvious design. It is not used, because contacts are
  -- deactivated and replaced — that table's own comment says so — and a
  -- login whose identity is a join to a row that can be deactivated is a
  -- login that stops being able to describe itself. The credential mail
  -- also has to have somewhere to go after the contact who received it
  -- has left. Keeping the pair here means the account survives the
  -- contact record, which is what an audit of who signed what requires.
  display_name          VARCHAR(160)  NOT NULL,
  email                 VARCHAR(200)  NOT NULL,

  -- Deliberately NOT unique. ob_client_contacts records that the same
  -- consultant can be the SPOC at two clients; the same is true here, and
  -- a unique email would refuse the second client a portal login for a
  -- reason nobody could act on. `username` is the login key.

  is_active             TINYINT(1)    NOT NULL DEFAULT 1,

  -- A-026's rule, unchanged for the portal: the first password is issued
  -- by us and must be replaced before anything else resolves. Defaulting
  -- to 1 means an account created by any path — wizard, admin panel, a
  -- fixture — starts in the state that forces the change, rather than
  -- relying on every creator to remember.
  must_change_password  TINYINT(1)    NOT NULL DEFAULT 1,

  -- A-021's lockout, same shape and same thresholds as `users`. Portal
  -- logins are the internet-facing half of the product and need this more
  -- than staff logins do, not less.
  failed_attempts       SMALLINT      NOT NULL DEFAULT 0,
  locked_until          DATETIME(6)   NULL,
  last_login_at         DATETIME(6)   NULL,

  -- The member of staff who issued this login. Nullable because an
  -- account created by the OB-04 wizard's "create client portal login
  -- now" checkbox is created by whoever ran the wizard, and a fixture has
  -- nobody.
  created_by            BIGINT        NULL,

  created_at            DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at            DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                          ON UPDATE CURRENT_TIMESTAMP(6),

  PRIMARY KEY (id),

  UNIQUE KEY uq_client_accounts_username (username),

  -- One portal login per client per master. Two logins for one client
  -- would mean two credentials to revoke when somebody leaves, and the
  -- one nobody remembered is the one that stays live. MySQL's unique
  -- index ignores NULLs, so an account linked only to ob_clients does not
  -- occupy the ticketing slot — which is the behaviour wanted, and the
  -- same idiom ob_client_contacts uses for one-primary-per-client.
  UNIQUE KEY uq_client_accounts_client (client_id),
  UNIQUE KEY uq_client_accounts_ob_client (ob_client_id),

  KEY ix_client_accounts_active (is_active),
  KEY ix_client_accounts_email (email),

  CONSTRAINT fk_client_accounts_client
    FOREIGN KEY (client_id) REFERENCES clients (id),
  CONSTRAINT fk_client_accounts_ob_client
    FOREIGN KEY (ob_client_id) REFERENCES ob_clients (id),
  CONSTRAINT fk_client_accounts_created_by
    FOREIGN KEY (created_by) REFERENCES users (id),

  -- The at-least-one rule. An account referencing neither master belongs
  -- to nobody and can still log in, which is the one state this table
  -- must not be able to reach.
  CONSTRAINT ck_client_accounts_has_a_master
    CHECK (client_id IS NOT NULL OR ob_client_id IS NOT NULL)

) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
