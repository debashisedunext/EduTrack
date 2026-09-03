-- =====================================================================
-- A-109 · Module access — the staff gate's table
--
-- Tables: user_module_access
--
-- Source:  docs/Onboarding-Module-Plan.md §2.1 ("user_module_access
--          (user_id, module, module_role, granted_by, granted_at) + a
--          `modules` JWT claim; a ModuleGuard before RolesGuard on every
--          /api/onboarding/** route; no entitlement → 404"), §3 (the roles)
--
-- THIS TABLE IS NOT `ob_`-PREFIXED, AND THAT IS THE POINT.
--
-- Every other table this module owns is `ob_`-prefixed so the two modules
-- stay separable by grep (plan §2). This one is deliberately not: it is
-- **identity-layer**, owned by the auth kernel, and it answers "which
-- modules may this user reach" for *any* module — onboarding today, a
-- third one later. Prefixing it `ob_` would make the general fact look
-- like the onboarding module's private business, and the first person
-- adding a module would either bend the name or add a second table.
--
-- Same reasoning that keeps `client_accounts` (A-125) out of the prefix.
--
-- `module_role` IS THE MODULE'S ROLE, NOT THE PLATFORM'S.
--
-- A user's platform role lives in `users.role_id` — the six of blueprint
-- §2. The onboarding module has its own six (plan §3: OB Admin,
-- Onboarding Manager, Sales, Step Owner, Viewer, and the external Client),
-- and they do not map onto the platform's. A ticketing Developer may be an
-- OB Admin; a ticketing Admin may have no onboarding access at all.
--
-- Storing it here rather than widening `users.role_id` is what keeps that
-- true. It also means revoking module access is one row, not an edit to
-- the user's identity.
--
-- WHY THERE IS NO FOREIGN KEY ON `module`
--
-- A `modules` master exists in the ticketing schema (`product_modules`,
-- row 57) and means something entirely different — a functional area of a
-- product that a ticket is raised against. Keying this to it would be a
-- collision of two unrelated meanings on one word. The value here is a
-- short code the platform itself defines (`TICKETING`, `ONBOARDING`), and
-- a CHECK holds the vocabulary, so a typo is refused at write time rather
-- than silently granting access to a module that does not exist.
--
-- A CHECK rather than a lookup table because the set changes by migration,
-- not by a screen — a third module is a deployment, not an admin action.
--
-- WHAT THIS TABLE DOES NOT DO
--
-- It does not grant anything by itself. §2.1's chain is ModuleGuard *before*
-- RolesGuard, and this row is only the first of the two questions. A-110
-- puts `modules` in the JWT so the guard need not read this table per
-- request; A-111 is the guard. Until those land, this table is inert.
--
-- **No entitlement is a 404, not a 403** (§2.1, and the contract's
-- `ObModuleGated`). That is not enforced here — it cannot be — but it is
-- the reason this table exists rather than a boolean on `users`, and it is
-- written down because a reader who assumes 403 will build the wrong guard.
-- =====================================================================

CREATE TABLE user_module_access (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  user_id       BIGINT       NOT NULL,
  -- Platform-defined, not a foreign key. See the note above.
  module        VARCHAR(20)  NOT NULL,   -- TICKETING|ONBOARDING
  -- The module's own role vocabulary, independent of users.role_id.
  module_role   VARCHAR(30)  NOT NULL,
  granted_by    BIGINT       NULL,
  granted_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  -- Revoked rather than deleted, so an access audit can answer "who could
  -- see this module in August" — which is the question that gets asked
  -- after something is seen that should not have been. A DELETE would
  -- leave nothing to answer it with.
  revoked_at    DATETIME(6)  NULL,
  revoked_by    BIGINT       NULL,
  -- 1 while the grant is live, NULL once revoked. The unique index below
  -- ignores NULLs, so a user may accumulate revoked grants for a module
  -- while never holding two live ones. Fifth use of the generated-column
  -- partial-index idiom in this work, after A-101, A-103, A-104 and A-128.
  live_key      TINYINT(1)
      GENERATED ALWAYS AS (IF(revoked_at IS NULL, 1, NULL)) STORED,
  created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                 ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  -- One live grant per (user, module). Two would mean two module_roles,
  -- and nothing says which one the guard should believe.
  UNIQUE KEY uq_user_module_access_live (user_id, module, live_key),
  -- A-110 builds the JWT claim from this: one user's live grants.
  KEY ix_user_module_access_user (user_id, live_key),
  -- OB-08's administration screen: everyone who can reach a module.
  KEY ix_user_module_access_module (module, live_key),
  KEY ix_user_module_access_granted_by (granted_by),
  KEY ix_user_module_access_revoked_by (revoked_by),
  CONSTRAINT fk_user_module_access_user
    FOREIGN KEY (user_id) REFERENCES users (id),
  CONSTRAINT fk_user_module_access_granted_by
    FOREIGN KEY (granted_by) REFERENCES users (id),
  CONSTRAINT fk_user_module_access_revoked_by
    FOREIGN KEY (revoked_by) REFERENCES users (id),
  CONSTRAINT ck_user_module_access_module
    CHECK (module IN ('TICKETING', 'ONBOARDING')),
  CONSTRAINT ck_user_module_access_module_role
    CHECK (module_role IN ('OB_ADMIN', 'OB_MANAGER', 'OB_SALES',
                           'OB_STEP_OWNER', 'OB_VIEWER',
                           'TICKETING_MEMBER')),
  -- Revoked means somebody revoked it. The two columns move together, or
  -- an access audit cannot say who withdrew the grant.
  CONSTRAINT ck_user_module_access_revoked
    CHECK ((revoked_at IS NULL AND revoked_by IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL))
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
