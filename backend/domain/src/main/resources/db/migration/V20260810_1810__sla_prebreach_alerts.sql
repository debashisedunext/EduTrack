-- D-021 · remembering who has already been warned that their SLA is nearly up.
--
-- Same shape as D-023's `stage_sla_alerts` and for a related reason: the fact
-- belongs to the scanner, not to the ticket. Putting a flag on `tickets` would
-- mean a migration against the table CLAUDE.md singles out for Stream A review,
-- to store something no other feature reads.
--
-- KEYED ON (ticket_id, cycle_no), NOT ON ticket_id ALONE.
--
-- A reopened ticket starts a new cycle with its own Planned Close Date, so it
-- gets its own warning: somebody who was told about cycle 1 in March should
-- still hear about cycle 2 in June. Keying on the ticket would silently
-- suppress every warning after the first reopen, and the failure would be
-- invisible — no error, just a nudge that stops arriving for exactly the
-- tickets that have already gone wrong once.

CREATE TABLE sla_prebreach_alerts (
  ticket_id   BIGINT       NOT NULL,
  cycle_no    SMALLINT     NOT NULL,
  elapsed_pct DECIMAL(5,2) NOT NULL
              COMMENT 'Working-hour percentage of the window used when warned',
  warned_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (ticket_id, cycle_no),
  CONSTRAINT fk_sla_prebreach_alerts_ticket
    FOREIGN KEY (ticket_id) REFERENCES tickets (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
