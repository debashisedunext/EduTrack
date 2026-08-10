-- D-022 · when each ticket was last nudged for going quiet.
--
-- Unlike D-021's warning, a stale-task nudge is NOT once and done. A ticket
-- that stays untouched for another three working days is stale again, and
-- saying so again is the entire point — a nudge you send once and never repeat
-- is a nudge that gets ignored once and never repeated.
--
-- So this is one row per ticket that is updated in place rather than a row per
-- event: the question it answers is "when did we last say something", which
-- has one answer at a time. The history of nudges is not interesting; the
-- freshness of the last one is.
--
-- No cycle_no here, deliberately, and the contrast with sla_prebreach_alerts
-- is the reason to say so. That one is keyed per cycle because a reopen starts
-- a new commitment that deserves its own warning. Staleness is not a
-- commitment — it is a property of the ticket right now — and a reopen is
-- itself activity, which resets the clock through the normal path.

CREATE TABLE stale_ticket_nudges (
  ticket_id        BIGINT      NOT NULL,
  nudged_at        DATETIME(6) NOT NULL,
  last_activity_at DATETIME(6) NULL
                   COMMENT 'What the scanner believed was the latest activity when it nudged',
  PRIMARY KEY (ticket_id),
  CONSTRAINT fk_stale_ticket_nudges_ticket
    FOREIGN KEY (ticket_id) REFERENCES tickets (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
