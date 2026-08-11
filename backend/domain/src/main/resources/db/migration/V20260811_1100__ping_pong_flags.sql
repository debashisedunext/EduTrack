-- D-025 · tickets bouncing between stages, and who has been told.
--
-- Blueprint §17 names the risk this exists for: "Teams game the ribbon (bounce
-- tickets to stop their stage clock)", mitigated by "the iteration counter
-- makes ping-pong visible on the PM dashboard within a day". §11 names the
-- event and its audience — Iteration count reaches 3, bell and email, to PM
-- and RM.
--
-- WHY A TABLE AT ALL, when `tickets.current_iteration >= 3` is already the
-- answer. The dashboard could indeed compute the list without this. What it
-- could not do is avoid telling the same manager about the same bounce every
-- hour forever, which is what this records: not the condition, but the fact
-- that somebody has been told about it, and at which iteration.
--
-- KEYED PER CYCLE, unlike stale_ticket_nudges which is keyed per ticket, and
-- the contrast is the point. §4A.2's two counters are independent: iteration
-- counts backward moves *within* a cycle and resets when a reopen starts the
-- next one. A ticket that ping-ponged three times before being closed, then
-- reopened, starts a fresh argument — and the manager should hear about it
-- again rather than have it suppressed by a flag from the previous life.
--
-- iteration_no here is THE HIGHEST ITERATION ALREADY ANNOUNCED, not the
-- ticket's current one. That is what makes "it has bounced again since we
-- last said anything" a comparison rather than a guess, and it is why the
-- claim is an UPDATE with `iteration_no < :iterationNo` in the WHERE.

CREATE TABLE ping_pong_flags (
  ticket_id        BIGINT      NOT NULL,
  cycle_no         SMALLINT    NOT NULL,
  iteration_no     SMALLINT    NOT NULL
                   COMMENT 'The highest iteration already announced, not the current one',
  first_flagged_at DATETIME(6) NOT NULL
                   COMMENT 'When this cycle first crossed the threshold — the age of the problem',
  last_flagged_at  DATETIME(6) NOT NULL,
  PRIMARY KEY (ticket_id, cycle_no),
  KEY ix_ping_pong_flags_flagged (last_flagged_at),
  CONSTRAINT fk_ping_pong_flags_ticket
    FOREIGN KEY (ticket_id) REFERENCES tickets (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
