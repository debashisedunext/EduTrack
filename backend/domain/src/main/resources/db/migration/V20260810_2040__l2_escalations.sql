-- D-024 · which tickets have already been escalated to L2.
--
-- L1 needs no table: it fires at the moment of breach, and `tickets.is_delayed`
-- already records that that moment has passed exactly once. L2 fires 48 working
-- hours later, which is a second event with its own once-only requirement and
-- nothing on the ticket to hang it from.
--
-- One row per ticket rather than per cycle. A reopened ticket that breaches
-- again goes through `is_delayed` being cleared and re-set, and if that ever
-- needs L2 to re-arm the key gains `cycle_no` the way D-021's did — but a
-- second L2 to the same manager about the same ticket is noise until somebody
-- asks for it.

CREATE TABLE l2_escalations (
  ticket_id     BIGINT       NOT NULL,
  escalated_to  BIGINT       NULL
                COMMENT 'The RM''s manager who was told, or NULL if the chain ran out',
  overdue_hours DECIMAL(8,2) NOT NULL
                COMMENT 'Working hours beyond the Planned Close Date when L2 fired',
  escalated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (ticket_id),
  CONSTRAINT fk_l2_escalations_ticket
    FOREIGN KEY (ticket_id) REFERENCES tickets (id) ON DELETE CASCADE,
  CONSTRAINT fk_l2_escalations_user
    FOREIGN KEY (escalated_to) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
