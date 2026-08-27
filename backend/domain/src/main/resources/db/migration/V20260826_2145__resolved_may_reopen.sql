-- =====================================================================
-- RESOLVED -> REOPENED, for the three roles CLOSED -> REOPENED already
-- names.
--
-- Table touched: workflow_transitions. B-003's seed data — flagged for
-- Stream B in the pull request. Not one of the four append-only tables.
--
-- V20260807_1100 seeded exactly one way into REOPENED (row 13, from
-- CLOSED) and called it LOCKED alongside row 12, because at the time the
-- only thing that could follow a finished cycle was a closure.
--
-- The Support desk's Reopen changes that. When the desk refuses a
-- sign-off, the ticket is RESOLVED rather than CLOSED — the PM handed it
-- over and nobody closed anything — and what failed is the whole attempt,
-- not one hop inside it. §4A.2's two counters answer two questions:
--
--   iteration  work bouncing backwards WITHIN a cycle (QA fails a build,
--              Verification rejects a deploy) — POST /rework
--   cycle      the attempt started again, because what was delivered was
--              not accepted — POST /reopen
--
-- The desk is saying the second. So RESOLVED joins CLOSED as a
-- from_status here, and `ReopenService` accepts both.
--
-- SAME THREE ROLES, deliberately. §2's "Reopen ticket" row ticks Admin,
-- PM and Support Desk and excludes Developer, QA and Deployment; that
-- exclusion is G-3's and is not what this migration is reconsidering.
-- Adding a from_status is not the same as widening who may use it.
--
-- role_code is SUPPORT_DESK to match the rows already in this table.
-- V20260807_1030 renamed the role in `roles`, and V20260808_1400 fixed
-- this table's copy — matching what is here at insert time rather than
-- assuming which spelling won means this file is correct either way.
--
-- requires_reason = 1, matching row 13: a reopen must always say why, and
-- `ReopenDtos.ReopenRequest.reason` is @NotBlank on the route regardless.
-- requires_effort = 0, also matching row 13 — the desk is not confirming
-- anybody's hours by refusing a sign-off.
--
-- Insert-if-absent so a re-run changes nothing.
-- =====================================================================

INSERT INTO workflow_transitions (from_status, to_status, role_code, requires_reason, requires_effort)
SELECT src.from_status, src.to_status, src.role_code, src.requires_reason, src.requires_effort
  FROM (
    SELECT 'RESOLVED' AS from_status, 'REOPENED' AS to_status,
           wt.role_code, 1 AS requires_reason, 0 AS requires_effort
      FROM workflow_transitions wt
     WHERE wt.from_status = 'CLOSED' AND wt.to_status = 'REOPENED'
  ) src
 WHERE NOT EXISTS (
    SELECT 1 FROM (SELECT * FROM workflow_transitions) existing
     WHERE existing.from_status = src.from_status
       AND existing.to_status   = src.to_status
       AND existing.role_code   = src.role_code
 );
