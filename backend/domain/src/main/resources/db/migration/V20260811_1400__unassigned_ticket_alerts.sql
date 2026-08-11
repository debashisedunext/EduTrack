-- D-026 · tickets nobody has picked up, and when triage was last told.
--
-- Blueprint §11: "New unassigned ticket → bell + email → PM, Support Desk".
-- The trigger is not creation but *time*: a ticket raised and assigned in the
-- same minute needs no alert, while one still sitting in the queue two working
-- hours later is a triage failure nobody has noticed.
--
-- ONE ROW PER TICKET, UPDATED IN PLACE — the D-022 shape rather than D-021's
-- per-cycle one. The question is "when did we last tell anybody this is
-- unassigned", which has one answer at a time. There is no cycle dimension
-- because a reopened ticket that lands unassigned is the same failure as a new
-- one, and the reopen itself resets nothing about who is triaging it.
--
-- The alert REPEATS, once per working day, for the reason D-022 gives: an
-- alert sent once and never again is one that gets ignored once and never
-- again. Two working hours is the first alert; after that it is a daily
-- reminder for as long as nobody picks the ticket up.

CREATE TABLE unassigned_ticket_alerts (
  ticket_id        BIGINT      NOT NULL,
  alerted_at       DATETIME(6) NOT NULL,
  first_alerted_at DATETIME(6) NOT NULL
                   COMMENT 'Never rewritten — how long triage has been failing on this ticket',
  PRIMARY KEY (ticket_id),
  KEY ix_unassigned_ticket_alerts_alerted (alerted_at),
  CONSTRAINT fk_unassigned_ticket_alerts_ticket
    FOREIGN KEY (ticket_id) REFERENCES tickets (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
