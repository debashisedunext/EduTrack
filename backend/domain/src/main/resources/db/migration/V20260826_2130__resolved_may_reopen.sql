-- =====================================================================
-- RESOLVED -> REOPENED, for the three roles that already hold CLOSED ->
-- REOPENED.
--
-- Table touched: workflow_transitions. B-003's seed data — flagged for
-- Stream B in the pull request. Not one of the four append-only tables.
--
-- WHY. The Support desk's Reopen now starts a new cycle rather than a
-- rework iteration, and it fires on a ticket that is RESOLVED — the
-- status a sign-off handoff leaves behind (V20260826_1520 put the
-- terminal stage in Support's hands; TransitionService resolves the
-- ticket on arrival there). `ReopenService` accepts that status as of
-- the same change, and this row is the matching entry in the whitelist
-- so the table and the service agree about what is legal.
--
-- V20260807_1100 called row 13 LOCKED: "CLOSED -> REOPENED:
-- Admin/PM/Support Desk only, per the §2 permission matrix". That lock
-- is about **which roles** may reopen, and it is kept exactly — the same
-- three, no Developer, no QA, no Deployment. What changes is the
-- from_status, which that header never argued about.
--
-- WHY NOT RESOLVED -> REWORK INSTEAD. That row already exists (row 9)
-- and stays; it is the other counter. §4A.2 keeps two and they answer
-- two questions: an iteration is work bouncing backwards inside a cycle
-- (QA fails a build, Verification rejects a deploy), a cycle is the
-- whole attempt started again. A desk refusing a sign-off is saying the
-- attempt delivered something the client would not take, so the counter
-- that moves is reopen_count.
--
-- requires_reason = 1, matching row 13 exactly. A reopen must say why on
-- both from_statuses; S-22's dialog makes the field mandatory and
-- ReopenService refuses a blank.
--
-- requires_effort = 0, also matching row 13. G-1's blocking rule applies
-- to * -> RESOLVED, work being claimed complete, which this is the
-- opposite of.
--
-- SUPPORT_DESK, not SUPPORT, because that is the code this table
-- carries: V20260808_1400 fixed the role code in `workflow_transitions`
-- and settled on it here even though `roles.code` reads SUPPORT. Matched
-- against the existing CLOSED -> REOPENED rows rather than typed fresh,
-- so the pair cannot drift apart if that ever changes again.
--
-- Idempotent: selects the role codes from the rows already present and
-- inserts only what is missing, so re-running changes nothing.
-- =====================================================================

INSERT INTO workflow_transitions (from_status, to_status, role_code, requires_reason, requires_effort)
SELECT 'RESOLVED', 'REOPENED', existing.role_code, 1, 0
  FROM (SELECT DISTINCT role_code
          FROM workflow_transitions
         WHERE from_status = 'CLOSED' AND to_status = 'REOPENED') existing
 WHERE NOT EXISTS (
        SELECT 1
          FROM workflow_transitions already
         WHERE already.from_status = 'RESOLVED'
           AND already.to_status = 'REOPENED'
           AND already.role_code = existing.role_code);
