-- =====================================================================
-- ob_implementation_stages — the Implementation Stage master.
--
-- Source:  the request that opened this task — one new Administration
--            menu item, seeded with Configuration, Data Migration,
--            Reports, Training, Communication and Third Party
--            Integration, with an admin able to add more values and
--            change their order.
--          contracts/openapi.yaml `listObImplementationStages` and the
--            three writes beside it.
--
-- WHY A TABLE AND NOT AN ENUM.
-- "There should be an option of adding more values" is the whole
-- requirement, stated in one line. An enum — Java, a CHECK constraint or
-- a closed contract vocabulary — makes a seventh value a release rather
-- than a row, which is exactly what this master exists to avoid. The six
-- below are seeds, not the set.
--
-- This is the opposite call from `ob_escalation_rungs`, whose three rungs
-- ARE closed and say so; the difference is that a fourth rung would have
-- no ObEscalationLevel to be, while a seventh implementation stage is
-- just a seventh row.
--
-- WHY THERE IS NO DELETE, ONLY is_active.
-- `ob_products`' rule, one master over, for a reason that gets stronger
-- rather than weaker with time: nothing points at these rows today, but
-- a stage is the kind of value that ends up on a journey step, a report
-- filter and a mail subject, and by then a DELETE is a row other rows
-- resolve to nothing through. Retiring drops a value out of the pickers
-- and leaves every historical reference readable. The API offers no
-- delete route at all, so this is not a convention somebody has to
-- remember.
-- =====================================================================

CREATE TABLE ob_implementation_stages (
  id          BIGINT       NOT NULL AUTO_INCREMENT,

  -- Unique case-insensitively, which utf8mb4_0900_ai_ci gives us for
  -- free. The service still checks first so the 409 names the field
  -- rather than a MySQL constraint — the call `createClient` was
  -- corrected for and `ob_products` repeats.
  name        VARCHAR(120) NOT NULL,

  -- 1-based display position, contiguous across every row of the table.
  --
  -- NOT UNIQUE, deliberately, and for the reason
  -- V20260903_1420__ob_journey_templates.sql already records for its own
  -- `sequence`: renumbering is a set of UPDATEs, and any ordering of
  -- them passes through a moment where two rows share a position. A
  -- unique index would refuse a legitimate reorder halfway through it —
  -- and MySQL has no DEFERRABLE to escape with. Contiguity is
  -- ObImplementationStageService's invariant instead, applied over the
  -- whole table inside one transaction, which is the only place that
  -- ever sees every row at once.
  --
  -- Retired rows keep their slot rather than being pushed to the end:
  -- the screen shows one list, and a stage that comes back from retirement
  -- should come back where it was.
  sequence    INT          NOT NULL,

  is_active   BOOLEAN      NOT NULL DEFAULT TRUE,

  -- Nullable for `ob_settings`' reason: the seeded rows below were
  -- written by nobody, and a NOT NULL would need a sentinel user, which
  -- is an account that can be granted things.
  created_by  BIGINT       NULL,
  created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                               ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_ob_implementation_stages_name (name),
  KEY ix_ob_implementation_stages_sequence (sequence, id),
  CONSTRAINT fk_ob_implementation_stages_created_by
    FOREIGN KEY (created_by) REFERENCES users (id),
  -- 1-based, so a row that arrives at 0 is a bug rather than a first
  -- slot. `ob_journey_templates.sequence` numbers from 0; this one does
  -- not, because unlike that column it is typed into a form by a person,
  -- and "position 0" is not what anybody means by first.
  CONSTRAINT ck_ob_implementation_stages_sequence
    CHECK (sequence >= 1)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- Seeds — the six values the master was asked for, in the order it asked
-- for them. The starting point, not the contract: everything below can be
-- renamed, reordered and retired from OB-15, and a seventh is added the
-- same way.
-- ---------------------------------------------------------------------

INSERT INTO ob_implementation_stages (name, sequence) VALUES
  ('Configuration',             1),
  ('Data Migration',            2),
  ('Reports',                   3),
  ('Training',                  4),
  ('Communication',             5),
  ('Third Party Integration',   6);
