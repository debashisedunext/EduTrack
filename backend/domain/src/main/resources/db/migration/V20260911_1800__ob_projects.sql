-- =====================================================================
-- ob_projects — the engagement becomes a row, and the client stops
-- being two things at once.
--
-- Source: "Page New Client — provide list view … on Add mode just
--         capture Client Name, Address, City and Client Code. Page
--         Client → change the menu name to Project … on click of new
--         project it will provide me Project Name, Client Name, Project
--         start Date, Sales Person, implementor name, Product bought."
--
-- WHAT THIS TABLE IS, AND WHY IT IS A PROMOTION RATHER THAN A NEW IDEA.
-- The pair (client, product) is already the unit this module runs on. It
-- has a uniqueness rule — `uq_ob_client_applications` — a screen of its
-- own at /onboarding/clients/:id/products/:productId, and an analytics
-- query in ObDelayedProjectsRepository that reports on exactly that
-- grain. What it has never had is a row, so it could not carry a name, a
-- start date, an implementor or a status. Everything below gives the
-- pair a row and points the journeys at it; nothing changes about what a
-- journey is or how one runs.
--
-- WHY THE BACKFILL UNIONS TWO TABLES.
-- A journey ought to imply a purchase row, and mostly does. But nothing
-- in the schema enforces it — ObJourneyInstantiationService writes
-- ob_journeys and the wizard writes ob_client_applications, in that
-- order, and a fixture or a half-finished create can leave one without
-- the other. Reading only the purchases would leave a journey with no
-- project to point at, and `project_id NOT NULL` at the bottom of this
-- file would then fail the migration on live data. The union makes the
-- set provably complete: every journey's pair is in it by construction.
--
-- WHY ob_journeys KEEPS ob_client_id AND product_id.
-- Both are now derivable from project_id, and 155 files across the
-- backend read them — scope resolvers, the dashboard's four repositories,
-- the notification outbox, the scanner. Dropping them here would make
-- this a 155-file change riding on a feature migration, and every one of
-- those files would be touched for no behaviour. They stay denormalised
-- and cannot diverge, because from here on the three are written
-- together in one service method and by nothing else.
--
-- WHY ob_projects → ob_clients IS RESTRICT WHERE THE PURCHASE ROW
-- CASCADES.
-- Sixteen tables carry ob_client_id and several cascade, two of them
-- being ob_step_history and ob_prereq_history — hash-chained and
-- append-only. A DELETE on a client that had reached the point of having
-- projects would therefore take an audit chain with it. The service
-- refuses that delete (OB_CLIENT_IN_USE), and this constraint is the
-- second layer under it: a client with a project cannot be removed even
-- by a hand at a SQL prompt. A client with nothing but a purchase row —
-- typed in wrong five minutes ago — still deletes, which is the only
-- case the new Clients screen offers the button for.
--
-- NONE OF THE FOUR PROTECTED TABLES IS TOUCHED.
-- =====================================================================


-- ---------------------------------------------------------------------
-- The client shrinks to a master: name, address, city, code.
--
-- WHY BOTH COLUMNS ARE NULLABLE.
-- Neither existed until now, so every row already here has neither, and
-- there is no value to backfill that would not be invented. `city` is
-- simply optional. `client_code` is required of every *new* client and
-- enforced in ObClientService, not here: MySQL treats NULLs as distinct
-- in a unique index, so the existing rows coexist under
-- `uq_ob_clients_client_code` while a second "HRZ-001" is refused. The
-- alternative — NOT NULL with a generated placeholder — would mint codes
-- nobody chose into the column the operations team will file by.
--
-- No column is dropped. `description`, `onboarding_date`, `pan_*`,
-- `sales_person_id` and `license_type` stop being captured and keep
-- being read: OB-05 still prints them for every client boarded through
-- the wizard, and dropping a populated column to tidy a form is not a
-- trade this file is willing to make. `onboarding_date` stays NOT NULL
-- and the lean create stamps the current date.
-- ---------------------------------------------------------------------

-- COMMENT precedes the positional clause. MySQL's column definition grammar
-- puts every attribute before AFTER/FIRST, so `NULL AFTER address COMMENT '…'`
-- is a syntax error rather than a style preference.
ALTER TABLE ob_clients
  ADD COLUMN city        VARCHAR(120) NULL
    COMMENT 'OB-CL. Free text: the module has no city master and inventing one to hold a label would be a screen nobody asked for.'
    AFTER address,
  ADD COLUMN client_code VARCHAR(32)  NULL
    COMMENT 'OB-CL. The operations team''s own filing key, typed rather than generated. Required on create, nullable here so pre-existing rows stay legal.'
    AFTER name,
  ADD UNIQUE KEY uq_ob_clients_client_code (client_code);


-- ---------------------------------------------------------------------
-- The project itself.
--
-- WHY UNIQUE (ob_client_id, product_id).
-- Carried across from `uq_ob_client_applications`, which has held the
-- same pair unique since the module's first migration. Two reasons to
-- keep it rather than relax it while we are here: the backfill below is
-- provably unambiguous under it and would need a tie-break rule without
-- it, and every join that resolves a journey's project by its pair —
-- including this migration's own — stays single-valued. The cost is that
-- re-implementing the same product for the same client needs the first
-- project moved out of the way. Relaxing it later is one ALTER; starting
-- without it cannot be undone, because by then the ambiguous rows exist.
--
-- WHY `name` IS NOT UNIQUE AND HAS NO CODE.
-- A project name is a label people choose — "Horizon ERP Rollout 2026" —
-- and two clients may well run projects by the same name. The identity
-- is the pair above. Nothing points at a project by name, so a code
-- would be a second identifier to keep in step with the first.
--
-- WHY sales_person_id AND implementor_user_id ARE SEPARATE NULLABLE FKs.
-- They are different people doing different jobs, and both are recorded
-- late in practice — a project is often created before the implementor
-- is assigned, which is precisely the state the Projects grid draws an
-- em dash for. Making either NOT NULL would mean refusing a project that
-- the business considers perfectly real.
--
-- WHY status IS A CHECKed VARCHAR RATHER THAN A TABLE.
-- The opposite call from ob_implementation_stages, and for the reason
-- that master states: these four are closed. RUNNING, COMPLETED,
-- ON_HOLD and DROPPED each mean something the code branches on —
-- COMPLETED stops the delay clock, DROPPED leaves the journeys in place
-- but stops reporting on them — so a fifth value is a release, not a
-- row, exactly like ObClientStatus which this mirrors.
-- ---------------------------------------------------------------------

CREATE TABLE ob_projects (
  id                  BIGINT       NOT NULL AUTO_INCREMENT,
  ob_client_id        BIGINT       NOT NULL,
  product_id          BIGINT       NOT NULL,
  name                VARCHAR(200) NOT NULL,
  start_date          DATE         NOT NULL
    COMMENT 'The engagement''s own start, not the client''s onboarding_date. Tentative completion is computed from it.',
  sales_person_id     BIGINT       NULL,
  implementor_user_id BIGINT       NULL
    COMMENT 'Who is running the implementation. Nullable: a project is routinely created before one is assigned.',
  status              VARCHAR(20)  NOT NULL DEFAULT 'RUNNING',
                                   -- RUNNING|COMPLETED|ON_HOLD|DROPPED
  status_reason       VARCHAR(500) NULL
    COMMENT 'Required by the service for ON_HOLD and DROPPED, on ob_clients.status_reason''s precedent.',
  created_by          BIGINT       NULL,
  created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                       ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_ob_projects_client_product (ob_client_id, product_id),
  -- The grid's default ordering — start_date descending, then id. The id
  -- is in the key for ob_clients' own reason: several projects starting
  -- on the same day is the normal case, and a keyset over the date alone
  -- drops rows at the page boundary.
  KEY ix_ob_projects_start_date (start_date, id),
  KEY ix_ob_projects_status (status, start_date),
  KEY ix_ob_projects_implementor (implementor_user_id, start_date),
  KEY ix_ob_projects_sales_person (sales_person_id, start_date),
  KEY ix_ob_projects_product (product_id, start_date),
  -- RESTRICT, and the header says why at length.
  CONSTRAINT fk_ob_projects_client
    FOREIGN KEY (ob_client_id) REFERENCES ob_clients (id),
  -- No CASCADE: a product is retired, never deleted, and a project must
  -- keep resolving its product long after the product stops being sold.
  CONSTRAINT fk_ob_projects_product
    FOREIGN KEY (product_id) REFERENCES ob_products (id),
  CONSTRAINT fk_ob_projects_sales_person
    FOREIGN KEY (sales_person_id) REFERENCES users (id),
  CONSTRAINT fk_ob_projects_implementor
    FOREIGN KEY (implementor_user_id) REFERENCES users (id),
  CONSTRAINT fk_ob_projects_created_by
    FOREIGN KEY (created_by) REFERENCES users (id),
  CONSTRAINT ck_ob_projects_status
    CHECK (status IN ('RUNNING', 'COMPLETED', 'ON_HOLD', 'DROPPED'))
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- Backfill — one project per pair that already exists.
--
-- The name is "<Client> — <Product>", which is what somebody would have
-- typed, truncated to the column rather than trusted to fit: a 200-char
-- client name beside a 160-char product name overruns by half, and MySQL
-- in strict mode refuses the row rather than trimming it.
--
-- start_date comes from the client's onboarding_date because that is the
-- only start this module has ever recorded. sales_person_id and
-- created_by come from the client for the same reason. implementor is
-- left NULL — it is genuinely unknown for every row written before
-- today, and guessing it from a journey's step owner would attribute the
-- whole engagement to whoever happened to own step one.
-- ---------------------------------------------------------------------

INSERT INTO ob_projects (ob_client_id, product_id, name, start_date,
                         sales_person_id, created_by, status)
SELECT pair.ob_client_id,
       pair.product_id,
       LEFT(CONCAT(cl.name, ' — ', pr.name), 200),
       cl.onboarding_date,
       cl.sales_person_id,
       cl.created_by,
       'RUNNING'
  FROM (SELECT ob_client_id, product_id FROM ob_client_applications
        UNION
        SELECT ob_client_id, product_id FROM ob_journeys) AS pair
  JOIN ob_clients  cl ON cl.id = pair.ob_client_id
  JOIN ob_products pr ON pr.id = pair.product_id;


-- ---------------------------------------------------------------------
-- Two status corrections, in this order.
--
-- First: a pair whose every live journey has completed is a finished
-- project, and leaving it RUNNING would put it on the Projects grid
-- with a delay clock the work stopped feeding months ago. A pair with no
-- live journey at all is *not* completed — it is a purchase nobody has
-- started, which is RUNNING with nothing done, and the NOT EXISTS pair
-- below separates the two.
--
-- Second, and last so it wins: a client on hold or dropped carries that
-- onto every project it has, including one whose journeys all finished.
-- The client-level status is the more specific fact — somebody typed it,
-- with a reason — and it outranks anything derived from journey rows.
-- ---------------------------------------------------------------------

UPDATE ob_projects p
   SET p.status = 'COMPLETED'
 WHERE EXISTS (SELECT 1 FROM ob_journeys j
                WHERE j.ob_client_id = p.ob_client_id
                  AND j.product_id   = p.product_id
                  AND j.archived_at IS NULL)
   AND NOT EXISTS (SELECT 1 FROM ob_journeys j
                    WHERE j.ob_client_id = p.ob_client_id
                      AND j.product_id   = p.product_id
                      AND j.archived_at IS NULL
                      AND j.completed_at IS NULL);

UPDATE ob_projects p
  JOIN ob_clients cl ON cl.id = p.ob_client_id
   SET p.status = cl.overall_status,
       p.status_reason = cl.status_reason
 WHERE cl.overall_status IN ('ON_HOLD', 'DROPPED');


-- ---------------------------------------------------------------------
-- The journey gains its project. Nullable for the length of this file
-- only — made NOT NULL below, once every row has one, which the union
-- above guarantees.
-- ---------------------------------------------------------------------

ALTER TABLE ob_journeys
  ADD COLUMN project_id BIGINT NULL AFTER id;

UPDATE ob_journeys j
  JOIN ob_projects p
    ON p.ob_client_id = j.ob_client_id
   AND p.product_id   = j.product_id
   SET j.project_id = p.id;

ALTER TABLE ob_journeys
  MODIFY COLUMN project_id BIGINT NOT NULL
    COMMENT 'ob_projects.id. ob_client_id and product_id remain beside it, written together and derivable from it — see this migration''s header.';

-- Separate statement: MySQL creates the supporting index as part of
-- adding the constraint, and doing that in the same ALTER as the MODIFY
-- above makes the failure mode of either harder to read.
ALTER TABLE ob_journeys
  ADD CONSTRAINT fk_ob_journeys_project
    FOREIGN KEY (project_id) REFERENCES ob_projects (id);
