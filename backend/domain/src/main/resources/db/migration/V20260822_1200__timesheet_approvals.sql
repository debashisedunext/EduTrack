-- B-065 · §21's second half, split out of B-063 rather than dropped: "an
-- approval step for the manager."
--
-- WHY A TABLE, AND WHY NOT A FLAG ON ticket_effort_logs
--
-- ticket_effort_logs is one of CLAUDE.md's three append-only, hash-chained
-- tables — trg_effort_no_update and trg_effort_no_delete refuse every
-- mutation unconditionally, so an "approved" column on it is not a thing
-- that can exist. The review is recorded as its own row instead, the same
-- move ticket_stage_transitions' sealing exception and
-- ticket_status_requests (V20260813_1030) both make: a fact ABOUT an
-- append-only stream lives beside it, never inside it.
--
-- THE GRAIN IS ONE ROW PER RESOURCE PER WEEK
--
-- TimesheetService already answers "a resource's week" as one document —
-- Monday to Sunday, mondayOf()'s own resolution of weekOf — so the review
-- that document is put up for is the same shape: a manager reviews the week
-- as a whole, not a ticket or a day within it. uq_timesheet_approvals_week
-- makes a second approval of the same week a conflict the service reports
-- as 409, rather than a silent second row two people could each believe was
-- the record.
--
-- WHO MAY APPROVE, AND WHY THIS IS INSERT-ONLY
--
-- Decided 22 Aug 2026, closing the open question STREAM-B-MASTERS.md and
-- feature/tickets/effort/README.md both raised: Admin, or the resource's own
-- direct reporting manager (PM, via users.reporting_manager_id — the same
-- direct-only reading Profile360Repository.isVisibleTo already uses, rather
-- than a transitive walk nobody has decided to allow). Enforced in
-- TimesheetApprovalService, not here: a CHECK cannot see a caller's identity
-- or the reporting chain at write time the way ResourceWriteService's
-- manager-cycle guard does over several rows.
--
-- There is no UPDATE or DELETE anywhere in this feature and none is planned:
-- a week is reviewed once, and un-reviewing one is a policy nobody has asked
-- for. So this table carries no hash chain of its own — unlike the three
-- protected tables it sits beside, a correction here has no compensating-row
-- convention to preserve, because there is nothing yet to correct.
--
-- STREAM A: please review the shape. New table, foreign keys into users
-- only, alters none of the three protected tables or ticket_effort_logs
-- itself.

CREATE TABLE timesheet_approvals (
  id               BIGINT      NOT NULL AUTO_INCREMENT,
  user_id          BIGINT      NOT NULL COMMENT 'whose week this is',
  week_start_date  DATE        NOT NULL COMMENT 'the Monday TimesheetService.mondayOf resolves weekOf to',
  approved_by_id   BIGINT      NOT NULL COMMENT 'the Admin or direct reporting manager who reviewed it',
  approved_at      DATETIME(6) NOT NULL,
  note             VARCHAR(500) NULL,

  PRIMARY KEY (id),

  -- One review per resource per week. A second attempt at the same week is a
  -- 409 the service reports, naming who already reviewed it and when —
  -- never a second row two managers could each believe was the record.
  UNIQUE KEY uq_timesheet_approvals_week (user_id, week_start_date),

  -- "What has this manager reviewed, most recently first" — the shape a
  -- manager's own history of approvals is read in.
  KEY ix_timesheet_approvals_approved_by (approved_by_id, approved_at),

  CONSTRAINT fk_timesheet_approvals_user
    FOREIGN KEY (user_id) REFERENCES users (id),
  CONSTRAINT fk_timesheet_approvals_approved_by
    FOREIGN KEY (approved_by_id) REFERENCES users (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
