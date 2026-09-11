-- =====================================================================
-- A Module Service waits behind SEVERAL other services, not one.
--
-- Source:  OB-07's "Depends on" column. The picker was a single-choice
--            <select>, because `ob_journey_templates.depends_on_template_id`
--            (V20260903_1420) could hold one id. The real shape is a set:
--            "Biometric Device Rollout" waits for the ERP service *and*
--            the network survey, and neither of those waits for the other.
--          V20260903_1420__ob_journey_templates.sql — the column and the
--            self-FK this migration replaces, and the file that carries
--            the reasoning about why cycle-freedom is the service layer's
--            job. That reasoning survives this change unaltered; only its
--            arity moves.
--
-- WHY A TABLE AND NOT A SECOND COLUMN, OR A JSON ARRAY.
--
-- The dependency is read two ways and both want rows. "What does this
-- service wait for" resolves at instantiation, one journey at a time.
-- "Who waits for this service" is what a delete has to answer before it
-- runs — `ObJourneyTemplateRepository.findByDependsOnTemplateIdIn`, the
-- query behind ModuleServiceInUseException's refusal — and that direction
-- is a plain indexed lookup here, versus a JSON_CONTAINS scan of every
-- template row. A JSON column would also give up the foreign key, which
-- is the one guarantee the old column genuinely bought (V20260903_1420:
-- "the self-FK buys referential integrity and nothing more") and the only
-- reason a dependency cannot name a template that was deleted underneath
-- it.
--
-- THE OLD COLUMN IS DROPPED, NOT KEPT AS "THE FIRST ONE".
--
-- Carrying both would mean two places to read a dependency from and two
-- to write it to, and every reader would have to know which wins. The
-- rows are backfilled first, so nothing declared through the old column
-- is lost, and the column then goes — along with its index and its FK,
-- which MySQL requires dropped in that order (the FK's supporting index
-- cannot be the last one able to serve it).
--
-- THE SELF-REFERENCE CHECK IS FINALLY POSSIBLE, AND IS STILL NOT ENOUGH.
--
-- V20260903_1420 could not write `CHECK (depends_on_template_id <> id)`:
-- MySQL 8.4 answers ERROR 3818, "check constraint cannot refer to an
-- auto-increment column". Here both columns are ordinary BIGINTs on a
-- table of their own, so the one-hop case IS expressible and is written
-- below. **The transitive case remains entirely the service layer's** —
-- a CHECK still cannot see another row, and the graph is unbounded in
-- depth and cross-product by design. So this constraint closes exactly
-- one of the cycles ObJourneyTemplateService#updateDependsOn refuses, and
-- a reader who takes it as evidence the database now guards cycles will
-- be wrong in the way that matters.
--
-- PRIMARY KEY IS THE PAIR, so declaring the same dependency twice is a
-- duplicate-key refusal rather than a journey held twice behind one
-- service. No surrogate id: nothing points at a dependency row, and the
-- pair is the fact.
--
-- Stream A review: none of the four append-only tables is touched.
-- =====================================================================

CREATE TABLE ob_journey_template_dependencies (
  template_id             BIGINT      NOT NULL,
  depends_on_template_id  BIGINT      NOT NULL,
  created_at              DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (template_id, depends_on_template_id),
  -- The reverse direction — "which services wait for this one" — is what
  -- a delete consults. Without this it is a full scan of the table.
  KEY ix_ob_journey_template_dependencies_reverse (depends_on_template_id),
  -- RESTRICT on both sides, inherited from the column this replaces: a
  -- service other services depend on cannot be deleted out from under
  -- them, and the designer re-points first. CASCADE on `template_id`
  -- would be defensible — deleting a service should take its own
  -- declarations with it — but the service layer already clears them in
  -- `deleteModuleService`, and one deletion path is easier to reason
  -- about than a database half that fires only sometimes.
  CONSTRAINT fk_ob_jt_dependencies_template
    FOREIGN KEY (template_id) REFERENCES ob_journey_templates (id),
  CONSTRAINT fk_ob_jt_dependencies_depends_on
    FOREIGN KEY (depends_on_template_id) REFERENCES ob_journey_templates (id),
  -- The one-hop cycle, which V20260903_1420 could not express. See the
  -- header: this is not cycle detection, it is the base case of it.
  CONSTRAINT ck_ob_jt_dependencies_not_self
    CHECK (template_id <> depends_on_template_id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- Every dependency declared through the old column, carried over. The
-- `<> id` guard is not defensive tidiness: the old table had no way to
-- refuse a self-reference, so a row could exist that the CHECK above
-- would reject, and the migration would fail on data the previous schema
-- considered legal.
INSERT INTO ob_journey_template_dependencies (template_id, depends_on_template_id)
SELECT id, depends_on_template_id
  FROM ob_journey_templates
 WHERE depends_on_template_id IS NOT NULL
   AND depends_on_template_id <> id;

-- FK before index before column: MySQL refuses to drop an index that is
-- the last one able to support a foreign key (errno 150), and refuses to
-- drop a column a foreign key is defined on.
ALTER TABLE ob_journey_templates
  DROP FOREIGN KEY fk_ob_journey_templates_depends_on;

ALTER TABLE ob_journey_templates
  DROP INDEX ix_ob_journey_templates_depends_on;

ALTER TABLE ob_journey_templates
  DROP COLUMN depends_on_template_id;
