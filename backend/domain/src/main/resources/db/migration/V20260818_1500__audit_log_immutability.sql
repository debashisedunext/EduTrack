-- =====================================================================
-- A-071 · audit_logs becomes genuinely append-only, and gains the one
-- column S-16 cannot be built without.
--
-- V20260805_1106 created this table and left a note rather than a
-- guarantee: "this table is NOT one of the three hash-chained append-only
-- tables, so A-008 does not put immutability triggers on it. If the audit
-- log needs the same guarantee, that is a separate decision and a separate
-- migration; flagging rather than assuming."
--
-- This is that decision, and A-071's line item is what settles it: "Export
-- only, never editable." An audit log an administrator can edit is not an
-- audit log — and the one role with a motive to alter a row is the only
-- role that can see there is one, because `audit.view` is Admin's alone.
-- So: the same two triggers ticket_history carries, for the same reason.
--
-- WHAT THIS IS AND IS NOT
--
-- These triggers are layer 4 of the same four, and audit_logs now has
-- three of them:
--   layer 1  AuditTrail exposes record() and nothing else       (A-071)
--   layer 2  no PUT/PATCH/DELETE route on /audit-logs           (A-071)
--   layer 3  edutrack_app holds INSERT, SELECT only here        (A-071,
--            docker/grants/apply-app-grants.sql)
--   layer 4  these triggers
--
-- It does NOT have the fifth thing ticket_history has: a hash chain.
-- Nothing here detects tampering by a SUPER user who drops the triggers
-- first, and nothing detects a truncated tail the way A-044's
-- chain_anchors does for the three protected tables. Stated plainly so
-- nobody reads "immutable" here as the stronger guarantee made elsewhere
-- in this schema. Chaining the audit log is a real option and belongs with
-- A-075's external anchoring, not here.
--
-- THE CONSEQUENCE, WHICH IS NOT SMALL
--
-- A DELETE trigger means audit rows can never be pruned by the
-- application, so this table only ever grows — every mutating request in
-- the system writes one row. There is no retention policy in the blueprint
-- and inventing one here would be the wrong order: retention is a decision
-- about evidence and wants to be made deliberately rather than acquired by
-- leaving DELETE available. When one is agreed it is a DBA operation under
-- a role holding DDL — drop the trigger, archive, restore it — and that
-- friction is the point.
--
-- Not one of the four tables in CLAUDE.md's review rule, so this needs no
-- sign-off beyond Stream A's own; audit_logs is Stream A's table.
-- =====================================================================


-- ---------------------------------------------------------------------
-- entity_ref — the half of "which record" that entity_id cannot hold.
--
-- audit_logs was designed in the baseline with entity_id BIGINT, and for
-- users, projects, clients and every master that is exactly right. It is
-- wrong for the one module A-071's line names first: a ticket is addressed
-- by its code — `CRM-26-00347`, contracts/openapi.yaml's TicketId — and
-- every ticket route takes that code in the path. There is no number to
-- put in entity_id at the moment the row is written.
--
-- Two alternatives were rejected. Resolving the code to tickets.id costs a
-- query on every ticket mutation and then hands the viewer a number no
-- human recognises, so the screen needs a join back to render the code it
-- started from. Leaving entity_id NULL loses the subject entirely, which
-- makes "every ticket action" a row saying only that a ticket, some
-- ticket, changed.
--
-- So: the numeric id where there is one, the reference where there is not,
-- and never both. The contract already types AuditLogEntry.entityId as a
-- string, so this arrives on the wire as the field it always declared.
--
-- 40 characters: TicketId's pattern is unbounded in its final group
-- (`\d{5,}`) but bounded in practice by a 10-character project code, a
-- year and a sequence, so 40 is roughly triple the realistic width.
-- Over-length values are truncated in AuditTrail rather than rejected, as
-- with every other bounded column here — losing the record of what
-- happened because the reference was long is the wrong trade.
-- ---------------------------------------------------------------------
ALTER TABLE audit_logs
  ADD COLUMN entity_ref VARCHAR(40) NULL AFTER entity_id;


-- ---------------------------------------------------------------------
-- The index the viewer actually reads through.
--
-- S-16 filters by user, module and date, and the screen's default — no
-- filter at all — is "most recent first, page down". The three indexes
-- from V20260805_1106 are (actor_id, created_at), (entity_type, entity_id)
-- and (action, created_at); none serves an unfiltered
-- ORDER BY created_at DESC, id DESC, which is the query every single open
-- of this screen runs before anybody touches a filter. Without this it is
-- a filesort over the whole table, and this is the table that grows
-- without bound.
--
-- (created_at, id) rather than created_at alone because the keyset cursor
-- is the pair — two rows written in the same microsecond must still have a
-- total order, or paging past them either repeats or skips.
-- ---------------------------------------------------------------------
CREATE INDEX ix_audit_logs_recent ON audit_logs (created_at DESC, id DESC);


DELIMITER $$

-- ---------------------------------------------------------------------
-- audit_logs — fully immutable. No exceptions, for anyone.
-- ---------------------------------------------------------------------
CREATE TRIGGER trg_audit_no_update BEFORE UPDATE ON audit_logs
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'Immutable table: audit_logs rows cannot be updated. The audit log is export-only (S-16).';
END$$

CREATE TRIGGER trg_audit_no_delete BEFORE DELETE ON audit_logs
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'Immutable table: audit_logs rows cannot be deleted. Pruning is a DBA operation, not an application one.';
END$$

DELIMITER ;
