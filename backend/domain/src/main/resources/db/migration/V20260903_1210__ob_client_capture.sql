-- =====================================================================
-- A-101 · Client Onboarding — client capture
--
-- Tables: ob_products, ob_clients, ob_client_contacts,
--         ob_client_applications, ob_client_requirements
--
-- Source:  docs/Onboarding-Module-Plan.md §4 (data model), §1.2 (non-goals)
--          contracts/openapi.yaml — A-118 wrote the contract for exactly
--          these tables first, so every column below has a field that
--          already declares its nullability, its length and its meaning.
--          Where the two disagree, the contract is wrong and gets fixed;
--          it is five days old and nothing is built against it yet.
--
-- Applied: PLAN.md §3.1 PostgreSQL → MySQL translation (normative)
--            BIGSERIAL     -> BIGINT AUTO_INCREMENT
--            TIMESTAMPTZ   -> DATETIME(6), stored UTC
--            BOOLEAN       -> TINYINT(1)
--          utf8mb4 / utf8mb4_0900_ai_ci on every table.
--
-- THE `ob_` PREFIX IS THE MODULE BOUNDARY, NOT A NAMING HABIT.
-- Plan §2 makes separability a requirement: "every table (`ob_`-prefixed)
-- … separable by `grep ob_` alone". Nothing in this file references a
-- ticketing table and nothing in a ticketing table will reference these.
-- The one sanctioned bridge is `client_accounts` at the identity layer
-- (A-125), which references both client masters and is referenced by
-- neither domain.
--
-- In particular: `ob_clients` is NOT `clients`. They are different
-- companies' data with different lifecycles, the ids do not correspond,
-- and a client present in both is joined by an explicit audited admin
-- action — never by name or PAN, because a false positive there shows
-- one company another company's tickets (plan §2.3).
--
-- NO FINANCIAL COLUMNS ANYWHERE.
-- Plan §1.2 removed financial tracking from this module by explicit
-- product decision. `ob_client_applications` records WHAT was bought and
-- for how long — never for how much. Commercials live in the sales
-- system. PAN is retained as *identity*, which is the next note.
--
-- WHO IS WAITING ON THIS FILE (docs/DEPENDENCIES.md):
--     A-102 ob_attachments · A-104 journey instances · A-113 PAN reveal
--     B-101…B-109 client capture, wizard and list · B-124…B-126 prereqs
-- =====================================================================


-- ---------------------------------------------------------------------
-- ob_products — the catalogue journey templates bind to (OB-07).
--
-- SCHEDULING NOTE — this table is A-124's in the backlog, not A-101's.
-- It is created here because `ob_client_applications.product_id` points
-- at it and a foreign key needs its target to exist: A-101 cannot apply
-- without it. A-124 keeps the master screen and its service layer; only
-- the DDL moves. Named here rather than left for whoever hits the
-- ordering problem later.
--
-- `code` is the natural key and is UNIQUE. The collation is
-- utf8mb4_0900_ai_ci, so `erp` and `ERP` are one code as far as the
-- index is concerned — which is the behaviour the contract's
-- `createObProduct` promises ("unique case-insensitively") and the
-- reason the service refuses the second with a field-keyed 409 rather
-- than letting the index refuse it with a message naming a constraint.
--
-- Products are deactivated, never deleted. A product with journeys
-- behind it is a row other rows depend on, and the journeys keep running
-- after it is retired — hence no ON DELETE CASCADE pointing here.
-- ---------------------------------------------------------------------
CREATE TABLE ob_products (
  id           BIGINT        NOT NULL AUTO_INCREMENT,
  code         VARCHAR(32)   NOT NULL,
  name         VARCHAR(160)  NOT NULL,
  is_active    TINYINT(1)    NOT NULL DEFAULT 1,
  created_by   BIGINT        NULL,
  created_at   DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at   DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                 ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_ob_products_code (code),
  KEY ix_ob_products_active (is_active, name),
  CONSTRAINT fk_ob_products_created_by
    FOREIGN KEY (created_by) REFERENCES users (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- ob_clients — the onboarding client master (OB-03/OB-04/OB-05).
--
-- PAN IS TWO COLUMNS, AND THE REASON MATTERS MORE THAN THE SHAPE.
--
-- PHASE-2-BUILD-PLAN.md decision 3 is closed: application-level AES-GCM,
-- key from the A-075 vault. AES-GCM is *randomised* — the same PAN
-- encrypts to different bytes on every call, because the nonce differs.
--
-- So a UNIQUE index on the ciphertext would never collide. It would
-- apply cleanly, look exactly like a working constraint, and silently
-- never fire — and the thing it is supposed to prevent is two rows for
-- one legal entity, which is precisely what the duplicate guard exists
-- to stop (plan §1.1 item 6, and `ob-client-pan-duplicate` in the
-- contract).
--
-- Hence the split:
--   pan_ciphertext   AES-GCM output. Nonce + tag + payload, produced and
--                    read only by A-113's service. No constraint on it,
--                    because no constraint over randomised bytes means
--                    anything.
--   pan_blind_index  Deterministic HMAC-SHA256 of the PAN, normalised
--                    upper-case and trimmed first. THIS carries the
--                    UNIQUE key, and it is what a lookup-by-PAN hashes
--                    and matches against. The plaintext is not derivable
--                    from it.
--
-- The HMAC key is NOT the AES key and has a different lifecycle: the AES
-- key may rotate freely, re-encrypting rows as it goes, but rotating the
-- HMAC key invalidates every stored index at once and takes the
-- uniqueness guarantee with it. A-113 owns both; this note is here
-- because the constraint above is the thing that breaks if anyone treats
-- them as one key.
--
-- BOTH COLUMNS ARE NULL AND NOTHING WRITES THEM UNTIL A-113 (7 Oct).
-- The vault they need (A-075) is not up until 5 November. They are
-- created now so that the schema is correct from its first migration and
-- no later migration ever has to read, encrypt and rewrite live identity
-- data in place — a data-protection incident waiting for its first bug.
-- Until then the API masks PAN for every role, which is the safe
-- direction to be wrong in.
--
-- `overall_status` — LIVE is *earned*, never set. It is the go-live flip
-- that fires when every journey completes with its sign-offs (plan §5.9),
-- and the contract answers 422 to a request for it. Nothing at this layer
-- can enforce that; it is named here so a later writer does not add a
-- setter and assume the schema was indifferent.
--
-- `rag` is deliberately absent even though plan §4 lists it. It is
-- derived worst-wins from the open journeys at read time, and no journey
-- table exists until A-104. A stored colour that nothing recomputes is a
-- colour that disagrees with its own steps — A-104 decides whether it is
-- worth denormalising at all.
-- ---------------------------------------------------------------------
CREATE TABLE ob_clients (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  name              VARCHAR(200)  NOT NULL,
  description       TEXT          NULL,
  onboarding_date   DATE          NOT NULL,
  -- See the PAN note above. Neither column is written before A-113.
  pan_ciphertext    VARBINARY(255) NULL,
  pan_blind_index   BINARY(32)    NULL,
  address           TEXT          NULL,
  sales_person_id   BIGINT        NULL,
  license_type      VARCHAR(64)   NULL,
  overall_status    VARCHAR(20)   NOT NULL DEFAULT 'ONBOARDING',
                                  -- ONBOARDING|LIVE|ON_HOLD|DROPPED
  status_reason     VARCHAR(500)  NULL,   -- required for ON_HOLD / DROPPED
  live_at           DATETIME(6)   NULL,
  created_by        BIGINT        NULL,
  created_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                      ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  -- The duplicate guard. On the blind index, never on the ciphertext.
  UNIQUE KEY uq_ob_clients_pan_blind (pan_blind_index),
  -- OB-03's default ordering — onboarding_date descending, then id. The
  -- id is in the key because two clients boarded the same day is the
  -- normal case after a sales push, and a keyset over the date alone
  -- would skip rows at the boundary.
  KEY ix_ob_clients_onboarding_date (onboarding_date, id),
  KEY ix_ob_clients_status (overall_status, onboarding_date),
  KEY ix_ob_clients_sales_person (sales_person_id, onboarding_date),
  CONSTRAINT fk_ob_clients_sales_person
    FOREIGN KEY (sales_person_id) REFERENCES users (id),
  CONSTRAINT fk_ob_clients_created_by
    FOREIGN KEY (created_by) REFERENCES users (id),
  CONSTRAINT ck_ob_clients_status
    CHECK (overall_status IN ('ONBOARDING', 'LIVE', 'ON_HOLD', 'DROPPED'))
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- ob_client_contacts — the SPOCs (plan §4).
--
-- EXACTLY ONE PRIMARY PER CLIENT, AND IT IS ENFORCED HERE.
--
-- `uq_ob_client_contacts_primary` is over (client_id, is_primary_key),
-- where `is_primary_key` is a generated column that is 1 when the contact
-- is primary and NULL otherwise. MySQL's unique index ignores NULLs, so
-- any number of non-primary contacts coexist while a second primary is
-- refused by the index.
--
-- This is the standard MySQL idiom for a partial unique index, which
-- MySQL 8.4 does not have — PostgreSQL would write
-- `CREATE UNIQUE INDEX … WHERE is_primary`. PLAN.md §3.1 does not cover
-- this translation because nothing in phase 1 needed it; it is recorded
-- here as the pattern.
--
-- Enforcing it in the database rather than only in the service matters
-- because of what depends on it: the primary SPOC receives the kickoff
-- mail, the one-time portal password and every sign-off request. A client
-- with two primaries sends the portal credential to both, and a client
-- with none silently sends it to nobody.
--
-- `whatsapp_opt_in` is consent and is per contact, not per client — a
-- client's SPOCs do not all give it, and WhatsApp templates require prior
-- opt-in.
--
-- Contacts are deactivated, never deleted: `is_active`, following the
-- ticketing master's `client_contacts` precedent (B-027), so a contact
-- who has left stops receiving mail without orphaning what they signed.
-- ---------------------------------------------------------------------
CREATE TABLE ob_client_contacts (
  id               BIGINT        NOT NULL AUTO_INCREMENT,
  ob_client_id     BIGINT        NOT NULL,
  name             VARCHAR(160)  NOT NULL,
  designation      VARCHAR(120)  NULL,
  email            VARCHAR(200)  NOT NULL,
  phone            VARCHAR(32)   NULL,
  whatsapp_opt_in  TINYINT(1)    NOT NULL DEFAULT 0,
  is_primary       TINYINT(1)    NOT NULL DEFAULT 0,
  is_active        TINYINT(1)    NOT NULL DEFAULT 1,
  -- 1 when this contact is the active primary, NULL otherwise. The unique
  -- index below ignores the NULLs, which is how one primary per client is
  -- enforced without a partial index. Deactivating a primary releases the
  -- slot, so their replacement can be promoted without a two-step dance.
  is_primary_key   TINYINT(1)
      GENERATED ALWAYS AS (IF(is_primary = 1 AND is_active = 1, 1, NULL)) STORED,
  created_at       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                     ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_ob_client_contacts_primary (ob_client_id, is_primary_key),
  -- One email may not repeat at the same client while both are active.
  -- Across clients it may: the same consultant can be the SPOC at two.
  UNIQUE KEY uq_ob_client_contacts_email (ob_client_id, email),
  KEY ix_ob_client_contacts_client (ob_client_id, is_active),
  CONSTRAINT fk_ob_client_contacts_client
    FOREIGN KEY (ob_client_id) REFERENCES ob_clients (id) ON DELETE CASCADE
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- ob_client_applications — one purchased product (plan §4).
--
-- A PURCHASE FACT, NOT A COMMERCIAL ONE. No amount, no invoice, no
-- payment status — see the header. What is here is what the onboarding
-- module needs to know: which product, how many seats, and for how long.
--
-- `UNIQUE (ob_client_id, product_id)` because a journey is instantiated
-- per purchased product and `ob_journeys` carries the same uniqueness
-- (plan §4). Buying more seats of something already bought is an edit to
-- this row, not a second row — a second row would mean a second journey
-- for one product, and the client page would render the same accordion
-- twice.
--
-- FK DIRECTION — `ob_journeys` will point *here*, not the reverse. The
-- purchase is the fact; the journey is what the module does about it. A
-- journey may be archived and re-instantiated (a template version change)
-- while the purchase is unchanged, so the nullable side belongs on the
-- journey. A-104 adds that key.
--
-- `license_end` is captured now and read by nothing in this module. Plan
-- §1.1 item 11: the renewals module, when it arrives, finds its data
-- waiting rather than needing a backfill nobody can source.
-- ---------------------------------------------------------------------
CREATE TABLE ob_client_applications (
  id             BIGINT        NOT NULL AUTO_INCREMENT,
  ob_client_id   BIGINT        NOT NULL,
  product_id     BIGINT        NOT NULL,
  license_type   VARCHAR(64)   NULL,
  units          INT           NULL,
  license_start  DATE          NULL,
  license_end    DATE          NULL,
  created_at     DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at     DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                   ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_ob_client_applications (ob_client_id, product_id),
  KEY ix_ob_client_applications_product (product_id),
  -- Renewals will read this without a client in hand.
  KEY ix_ob_client_applications_license_end (license_end),
  CONSTRAINT fk_ob_client_applications_client
    FOREIGN KEY (ob_client_id) REFERENCES ob_clients (id) ON DELETE CASCADE,
  -- No CASCADE: a product is retired, never deleted, and a purchase must
  -- keep resolving its product long after the product stops being sold.
  CONSTRAINT fk_ob_client_applications_product
    FOREIGN KEY (product_id) REFERENCES ob_products (id),
  CONSTRAINT ck_ob_client_applications_units
    CHECK (units IS NULL OR units > 0),
  CONSTRAINT ck_ob_client_applications_licence_window
    CHECK (license_end IS NULL OR license_start IS NULL
           OR license_end >= license_start)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- ob_client_requirements — free-text capture from the OB-04 wizard.
--
-- A table rather than a JSON column on `ob_clients`, for one reason that
-- is already visible in the plan: §9's OB-05 shows requirements as a list
-- somebody works through, and plan §4 lists it as its own table. A JSON
-- array cannot be indexed, ordered or later given a `is_met` flag without
-- rewriting every row.
--
-- `sequence` is the order they were entered, which is the order they are
-- shown. Not unique — reordering is a rewrite of the set, and two rows
-- briefly sharing a position during one is not corruption.
-- ---------------------------------------------------------------------
CREATE TABLE ob_client_requirements (
  id             BIGINT        NOT NULL AUTO_INCREMENT,
  ob_client_id   BIGINT        NOT NULL,
  sequence       INT           NOT NULL DEFAULT 0,
  body           TEXT          NOT NULL,
  created_by     BIGINT        NULL,
  created_at     DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at     DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                   ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY ix_ob_client_requirements_client (ob_client_id, sequence),
  CONSTRAINT fk_ob_client_requirements_client
    FOREIGN KEY (ob_client_id) REFERENCES ob_clients (id) ON DELETE CASCADE,
  CONSTRAINT fk_ob_client_requirements_created_by
    FOREIGN KEY (created_by) REFERENCES users (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
