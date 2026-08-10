-- =====================================================================
-- B-023 · working_calendar — the org-level third of S-14
--
-- Table:  working_calendar
-- Source: blueprint §7.4 S-14, §5; PLAN.md §6 M3
--
-- S-14 is three things: org holidays, the weekly-off pattern, and
-- per-resource leave. A-007 created `holidays` and `resource_leaves`.
-- This is the third, and it is also the piece B-024 cannot run without.
--
-- Why the table has to exist: `addWorkingHours(start, n)` has no answer
-- to "add four working hours to Friday 18:00" unless the length of a
-- working day is recorded somewhere, and nothing recorded it. The
-- weekly-off pattern had the same problem — `GET /masters/holidays`
-- has always returned `weeklyOff`, `workDayStart` and `workDayEnd`,
-- and until now every one of those three came from a mock.
--
-- Org-level, not per-resource. That is what the reviewed contract
-- returns, and blueprint §7.4's per-resource "Weekly off pattern" is a
-- field on the resource record (S-08 / B-011), a narrower override that
-- can land later without changing this shape.
-- =====================================================================


-- ---------------------------------------------------------------------
-- One row, enforced.
--
-- `ck_working_calendar_singleton` pins the key to 1. An org has one
-- working week; a second row would mean two answers to "when does the
-- working day end", and every SLA figure would depend on which one the
-- query happened to read. A UNIQUE index cannot express this — the
-- constraint is not that ids are distinct but that there is only ever
-- the one.
--
-- weekly_off · ISO-8601 day numbers: 1=Mon … 7=Sun, so `[6,7]` is a
-- standard weekend. This is exactly `java.time.DayOfWeek.getValue()`,
-- which is the point — the service reads the column into DayOfWeek with
-- no arithmetic in between, and arithmetic is where an off-by-one gets
-- in. `0` is not a valid value.
--
-- The contract previously described this field as "ISO" while
-- constraining it to 0–6 and mocking `[0, 6]` — JavaScript's
-- Sunday-zero convention wearing an ISO label. Read literally by a
-- backend using DayOfWeek, Sunday becomes a working day and every SLA
-- spanning a weekend is quietly wrong. `ck_working_calendar_weekly_off`
-- is why that cannot be stored here now: JSON_SCHEMA_VALID rejects 0,
-- rejects 8, rejects duplicates, and `maxItems: 6` rejects a
-- seven-day-weekend that would send `addWorkingHours` looking for a
-- working day it can never find. PLAN.md §3.1 already translated
-- PostgreSQL arrays to MySQL JSON for `workflow_stages.can_return_to`;
-- this follows it, and goes further than that column's ARRAY check
-- because here the element values carry the defect.
--
-- The working day is stored as MINUTES FROM MIDNIGHT, not as TIME.
--
-- This is not a stylistic choice; TIME was tried and is wrong here.
-- Both `api` and `worker` set `hibernate.jdbc.time_zone: UTC`, and the
-- JVM's default zone is not UTC, so a TIME round-trips through a UTC
-- calendar and comes back shifted by the offset: 09:30 stored, 15:00
-- read, on an IST machine. A working-day boundary is a wall-clock fact
-- with no instant attached, so any type the driver is willing to
-- convert is the wrong type for it. PLAN.md §3.1 already made this call
-- once — DATETIME(6) over TIMESTAMP, precisely to avoid "silent
-- session-timezone conversion" — and a SMALLINT count of minutes is
-- immune to every zone setting in the stack. 570 = 09:30, 1110 = 18:30.
--
-- 0–1440 rather than 0–1439 so a working day may end at midnight. A
-- start of 1440 is unreachable: end must exceed start and cannot pass
-- 1440.
--
-- `timezone` is what those minutes are counted in. It lets B-024
-- resolve either boundary to an instant, which it cannot do from the
-- minutes alone. Not in the contract's response yet — flagged for
-- Stream D rather than left implicit, because a consumer computing
-- against these boundaries without the zone is guessing.
-- ---------------------------------------------------------------------
CREATE TABLE working_calendar (
  id                  TINYINT       NOT NULL DEFAULT 1,
  weekly_off          JSON          NOT NULL,
  work_day_start_min  SMALLINT      NOT NULL,   -- minutes from midnight, local
  work_day_end_min    SMALLINT      NOT NULL,
  timezone            VARCHAR(50)   NOT NULL DEFAULT 'Asia/Kolkata',
  updated_at          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                        ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT ck_working_calendar_singleton
    CHECK (id = 1),
  CONSTRAINT ck_working_calendar_weekly_off
    CHECK (JSON_SCHEMA_VALID(
      '{"type":"array",
        "items":{"type":"integer","minimum":1,"maximum":7},
        "uniqueItems":true,
        "maxItems":6}',
      weekly_off)),
  CONSTRAINT ck_working_calendar_start_in_day
    CHECK (work_day_start_min BETWEEN 0 AND 1440),
  CONSTRAINT ck_working_calendar_end_in_day
    CHECK (work_day_end_min BETWEEN 0 AND 1440),
  CONSTRAINT ck_working_calendar_day_bounds
    CHECK (work_day_end_min > work_day_start_min)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- The default working week.
--
-- Seeded, not left empty: `GET /masters/holidays` has no sensible
-- answer without a row, and B-024 would have to special-case its
-- absence on every call. Saturday and Sunday off, 09:30–18:30
-- Asia/Kolkata — the same values the mock has served since D-002, so
-- the first run against the real backend does not silently change
-- anybody's SLA arithmetic.
-- ---------------------------------------------------------------------
INSERT INTO working_calendar
  (id, weekly_off, work_day_start_min, work_day_end_min, timezone)
VALUES
  (1, CAST('[6, 7]' AS JSON), 570, 1110, 'Asia/Kolkata');   -- 09:30 → 18:30
