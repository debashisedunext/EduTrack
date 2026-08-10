-- D-023 · remembering which stage-SLA breaches have already been announced.
--
-- The scanner runs every fifteen minutes and must alert once per breached
-- stage segment, not once per pass. D-020 solves the same problem with
-- `tickets.is_delayed`, but that option does not exist here:
-- `ticket_stage_transitions` is APPEND-ONLY and hash-chained, and the single
-- permitted mutation is sealing a segment (exited_at NULL -> timestamp).
-- Adding an `alerted` flag to it — or worse, updating one — is exactly the
-- erosion CLAUDE.md says is hardest to restore, and A-008's trigger would
-- reject it anyway.
--
-- So the fact lives outside the chain, in its own table. One row means "this
-- segment's breach has been announced". The UNIQUE key is what makes the claim
-- atomic: the scanner inserts, and the row count tells it whether this pass is
-- the one that gets to alert. Two instances racing produce one alert and one
-- silent no-op rather than two mails.
--
-- Keyed on the transition, not the ticket: a ticket that goes DEV -> QA -> DEV
-- has three segments and can breach each of them separately, and each is a
-- different thing to tell somebody about.

CREATE TABLE stage_sla_alerts (
  transition_id BIGINT       NOT NULL,
  breached_by   DECIMAL(6,2) NOT NULL
                COMMENT 'Working hours over the stage SLA when first announced',
  alerted_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (transition_id),
  -- ON DELETE CASCADE is safe in the direction that matters: transitions are
  -- never deleted (A-008 forbids it), so this only fires if a ticket is purged
  -- wholesale, where keeping the alert record would be pointless.
  CONSTRAINT fk_stage_sla_alerts_transition
    FOREIGN KEY (transition_id) REFERENCES ticket_stage_transitions (id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
