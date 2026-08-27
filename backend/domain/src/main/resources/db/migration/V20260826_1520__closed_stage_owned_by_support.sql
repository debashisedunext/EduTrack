-- =====================================================================
-- The Closed stage belongs to Support, not to whoever signed off.
--
-- Table touched: workflow_stages (owner_role only). Not one of the four
-- append-only tables, so no Stream A review gate applies — but it is
-- B-004's seed data, so flagged for Stream B in the pull request rather
-- than changed quietly.
--
-- V20260807_1700 seeded Closed's owner_role as "whichever role signed
-- off", and said so in its own header: "Closed then reuses whichever
-- role signed off ... the schema requires a non-null owner_role even
-- though §4A.1's Closed row reads '-' (terminal, no real owner)."
--
-- That reading is what put Standard Dev Flow and Infra Flow's Closed
-- stage on PM — the same role that owns Sign-off. The consequence is
-- the whole point of this migration: `TransitionService.resolveAssignee`
-- keeps the outgoing owner whenever the destination stage's role has an
-- active member on the project, and C-044's handoff dialog filters its
-- assignee list to that same role. With Closed owned by PM, a PM signing
-- off hands the ticket to themselves and then closes it — sign-off and
-- closure collapse into one person and one click, and the desk that
-- raised the ticket never sees it again.
--
-- Blueprint §4A.1 gives Sign-off as "PM / Support Desk" precisely
-- because those are two different hands. Closure is the Support Desk's:
-- it is the role that fields the client, so it is the role that decides
-- whether the client's problem is actually over. So Closed is SUPPORT on
-- every template, which is what Support Fast-Track already seeded and
-- what the other two now match.
--
-- Idempotent by predicate rather than by value: Support Fast-Track is
-- already SUPPORT and is left alone, so re-running this against a
-- database that has it changes no rows.
--
-- SUPPORT, not SUPPORT_DESK — V20260807_1030 renamed the role code, and
-- owner_role carries no FK to roles.code, so a stale value here would
-- not fail loudly. It would just mean no Support user ever matched their
-- own Closed segment. V20260807_1700's own header makes the same point
-- about the same column.
-- =====================================================================

UPDATE workflow_stages
   SET owner_role = 'SUPPORT'
 WHERE stage_code = 'CLOSED'
   AND owner_role <> 'SUPPORT';
