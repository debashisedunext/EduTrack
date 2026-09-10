-- =====================================================================
-- A product runs several Module Services AT ONCE, and a client is boarded
-- through every one of them.
--
-- Source:  docs/prototype/onboarding.html — EduTrack ERP carries "Standard
--            SaaS Onboarding" and "Enterprise (with data migration audit)",
--            and a client who buys the product is boarded through both,
--            one ribbon each.
--          V20260909_1900__ob_journey_template_version_per_service.sql —
--            the migration this continues, which made the *name* the
--            service's identity and re-keyed version numbering to
--            (product_id, name, version). It deliberately left
--            `uq_ob_journey_templates_active` alone, saying the two models
--            "agree on one active per product". That is the half this
--            migration reopens.
--
-- WHY ONE ACTIVE PER PRODUCT WAS THE WRONG HALF TO KEEP.
--
-- With it, a product can own several named services but publish only one:
-- publishing "Enterprise (with data migration audit)" silently retires
-- "Standard SaaS Onboarding", every client boarded afterwards gets the
-- enterprise journey instead of the standard one, and the catalogue shows
-- a card for a service nothing will ever instantiate. The services are not
-- alternatives; they are separate pieces of work sold together, with their
-- own owners, TATs and sign-offs. So the rule becomes **one active version
-- per service**, which is what the name-keyed index below says.
--
-- ONE JOURNEY PER SERVICE, NOT PER PRODUCT.
--
-- `uq_ob_journeys_client_product (ob_client_id, product_id, live_key)`
-- encodes the old rule on the instance side, and it is the harder half:
-- once a product publishes two services, a client buying it needs two live
-- journeys against the same (client, product) pair and that index refuses
-- the second. It is replaced by one keyed on the service.
--
-- `service_name` IS DENORMALISED FROM THE PINNED TEMPLATE, DELIBERATELY.
--
-- A unique index cannot span a join, and the fact that has to be unique —
-- "one live journey per client per service" — is only expressible with the
-- service on the row. It is written once at instantiation from the
-- template the journey pins, and never updated, exactly like `template_id`
-- beside it. That is also why it cannot drift: the pinned template row is
-- frozen, so its name cannot change under the journey, and a rename in the
-- designer starts a new service (V20260909_1900's own decision) rather
-- than renaming this one.
--
-- COLLATION MAKES THE TWO KEYS AGREE. `name` is utf8mb4_0900_ai_ci on both
-- tables, so "Data Migration" and "data migration" are one service to the
-- template index and one journey to this one. A binary collation on either
-- side would let a client hold two journeys for what the catalogue calls a
-- single service.
--
-- ORDER OF OPERATIONS IS LEAD-COLUMN DISCIPLINE, NOT STYLE. Both tables
-- carry a foreign key whose supporting index is being replaced —
-- `fk_ob_journey_templates_product` on (product_id), and the composite
-- `fk_ob_journeys_application` on (ob_client_id, product_id). MySQL
-- refuses to drop the last index able to support one (errno 150). Every
-- ADD therefore precedes its DROP, and each replacement keeps the old
-- key's leading columns.
--
-- Stream A review: none of the four append-only tables is touched.
-- `ob_journeys` is mutable by design (gate, hold, completion).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1 · one active version per SERVICE, not per product
-- ---------------------------------------------------------------------
ALTER TABLE ob_journey_templates
  ADD UNIQUE KEY uq_ob_journey_templates_service_active (product_id, name, active_key);

ALTER TABLE ob_journey_templates
  DROP INDEX uq_ob_journey_templates_active;

-- ---------------------------------------------------------------------
-- 2 · one live journey per client per service
--
-- Backfilled from the pinned template, which every existing journey has:
-- `template_id` is NOT NULL and has been since A-104. No row can be left
-- without a service name, so the column goes NOT NULL in the same
-- migration rather than staying nullable "for now".
-- ---------------------------------------------------------------------
ALTER TABLE ob_journeys
  ADD COLUMN service_name VARCHAR(160) NULL AFTER product_id;

UPDATE ob_journeys j
  JOIN ob_journey_templates t ON t.id = j.template_id
   SET j.service_name = t.name
 WHERE j.service_name IS NULL;

ALTER TABLE ob_journeys
  MODIFY COLUMN service_name VARCHAR(160) NOT NULL;

ALTER TABLE ob_journeys
  ADD UNIQUE KEY uq_ob_journeys_client_service
    (ob_client_id, product_id, service_name, live_key);

ALTER TABLE ob_journeys
  DROP INDEX uq_ob_journeys_client_product;
