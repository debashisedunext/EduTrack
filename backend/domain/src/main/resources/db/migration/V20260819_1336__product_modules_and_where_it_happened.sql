-- =====================================================================
-- C-065 · product_modules master + §7.5's "Where it happened" columns
--
-- Tables created: product_modules
-- Tables altered: tickets  ← NEEDS STREAM A'S REVIEW (TEAM-PLAN §7.1)
-- Source: blueprint §7.3 (the master), §7.5 (the four ticket fields),
--         PLAN.md §3.9 (MEDIUMTEXT and the sanitisation rule above it)
--
-- The eight rows are SEED DATA, NOT A JAVA ENUM. PLAN.md §3.9 says so
-- outright: "nothing in Java may hard-code the list". The ninth module is
-- a row somebody inserts, not a migration and a deployment — which is the
-- whole reason this is a master rather than a CHECK constraint.
--
-- steps_to_generate is MEDIUMTEXT rather than TEXT, per PLAN.md §3.9:
-- TEXT is 64 KB and a few screenshots pasted as data URIs exceed it, with
-- a silent truncation to invalid markup as the failure mode. The 20 000
-- character bound is enforced by Bean Validation on the DTO (C-067) so
-- springdoc emits it into the contract, not by the column width.
--
-- All four ticket columns are NULLABLE and none has a default. A change
-- request may genuinely span three modules and a draft is saved before
-- anybody knows the answer (§7.5) — a NOT NULL here would force a wrong
-- value at the moment the truth is least known.
--
-- No ON DELETE action on the FK, deliberately. Modules are deactivated
-- (is_active = 0), never deleted — the contract's own note says a ticket
-- raised against a since-retired module must still render its name. The
-- default RESTRICT is what makes deleting one impossible while any ticket
-- still points at it.
-- =====================================================================

CREATE TABLE product_modules (
    id         INT          NOT NULL AUTO_INCREMENT,
    code       VARCHAR(40)  NOT NULL,
    name       VARCHAR(80)  NOT NULL,
    seq        SMALLINT     NOT NULL DEFAULT 0,
    is_active  TINYINT(1)   NOT NULL DEFAULT 1,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_product_modules_code (code),
    KEY ix_product_modules_active (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Blueprint §7.3's eight, in the order it lists them.
INSERT INTO product_modules (code, name, seq) VALUES
    ('STUDENT',     'Student',     10),
    ('ADMISSION',   'Admission',   20),
    ('FEES',        'Fees',        30),
    ('EXAMINATION', 'Examination', 40),
    ('ATTENDANCE',  'Attendance',  50),
    ('LIBRARY',     'Library',     60),
    ('INVENTORY',   'Inventory',   70),
    ('PARENT_APP',  'Parent App',  80);

ALTER TABLE tickets
    ADD COLUMN module_id         INT           NULL,
    ADD COLUMN screen_name       VARCHAR(120)  NULL,
    ADD COLUMN feature           VARCHAR(120)  NULL,
    ADD COLUMN steps_to_generate MEDIUMTEXT    NULL,
    ADD KEY ix_tickets_module (module_id),
    ADD CONSTRAINT fk_tickets_module FOREIGN KEY (module_id) REFERENCES product_modules (id);
