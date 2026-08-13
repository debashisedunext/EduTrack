-- D-055 / D-056 · "Ask Status", and how long the answer took.
--
-- Blueprint §7.6: a Reporting Manager or PM clicks Ask Status, EduTrack posts a
-- structured message into the ticket thread, "the resource's reply is
-- timestamped, and manager response time becomes a reportable metric. Status
-- requests appear as a distinct badge on the ticket and in the manager's
-- 'Awaiting response' list."
--
-- WHY A TABLE AT ALL, WHEN THE MESSAGES ARE ALREADY THERE
--
-- The ask is a chat_messages row with kind = 'STATUS_REQUEST' and the answer is
-- a later row in the same thread, so it is tempting to derive everything and
-- store nothing. That does not survive contact with the two questions this
-- feature exists to answer.
--
--   · "Which asks are still unanswered?" would be a NOT EXISTS correlated over
--     chat_messages per candidate — on the one table in the schema with no
--     natural ceiling, for a list a manager loads on every visit.
--   · "How long did that one take?" has no single answer once derived. Which
--     later message counts as the reply is a *decision*, and re-deriving it on
--     every read means the decision can change under a figure somebody has
--     already reported. Recording which message closed which request makes the
--     metric evidence rather than an opinion recomputed nightly.
--
-- The messages remain the record of what was said. This table records what was
-- asked, of whom, and what closed it.
--
-- RESPONSE TIME IS STORED IN WORKING MINUTES, NOT DERIVED ON READ
--
-- Two reasons, and the second is the one that matters.
--
--   · It is a duration, so CLAUDE.md and D-027 route it through B-024's
--     working calendar. A manager who asks at 18:00 on Friday and reads a
--     reply at 09:30 on Monday waited half a working hour, not sixty-three
--     hours, and a scorecard that says otherwise is one nobody will defend in
--     an appraisal.
--   · The calendar CHANGES. Holidays get added, a resource's leave is approved
--     retrospectively, the weekly-off pattern is edited. Recomputing an old
--     figure against today's calendar would silently restate history — the
--     same objection the append-only rule makes elsewhere. The number is
--     stamped when the request is answered and never revisited.
--
-- Both instants are kept alongside it, so wall-clock remains derivable for
-- anyone who wants it. What is not derivable after the fact is the calendar as
-- it stood.
--
-- STREAM A: please review the shape. This is a new table with foreign keys into
-- tickets, chat_threads, chat_messages and users; it alters none of them, and
-- nothing here touches the append-only three.

CREATE TABLE ticket_status_requests (
  id                  BIGINT      NOT NULL AUTO_INCREMENT,
  ticket_id           BIGINT      NOT NULL,
  thread_id           BIGINT      NOT NULL
                      COMMENT 'Denormalised from the message so closing a request needs no join back to chat_threads on every post',
  request_message_id  BIGINT      NOT NULL
                      COMMENT 'The kind = STATUS_REQUEST message this row is about',
  requested_by_id     BIGINT      NOT NULL COMMENT 'The manager who asked',
  asked_of_id         BIGINT      NOT NULL
                      COMMENT 'The assignee at the moment of asking — the metric is about the person actually asked, so a later reassignment must not rewrite it',
  requested_at        DATETIME(6) NOT NULL,

  answer_message_id   BIGINT      NULL COMMENT 'The message that closed it',
  answered_by_id      BIGINT      NULL,
  answered_at         DATETIME(6) NULL,
  response_working_mins INT       NULL
                      COMMENT 'B-024 working minutes between requested_at and answered_at, stamped once at answer time',

  -- The duplicate guard, in the schema rather than only in the service. One
  -- open request per manager per ticket: a manager who clicks the button twice
  -- is asking the same question twice, and the second click must not post a
  -- second card into the thread, raise a second notification, or put two rows
  -- in their own awaiting-response list. Two DIFFERENT managers may each have
  -- one open — they are each owed an answer — and one reply closes both.
  --
  -- The generated column is A-009's pcd_open idiom: NULL once the row closes,
  -- and NULL is distinct from NULL in a MySQL unique index, so a ticket can
  -- accumulate any number of ANSWERED requests from the same manager — which is
  -- exactly the history the metric reads — while at most one open one.
  --
  -- It is generated from requested_by_id rather than from ticket_id, and that
  -- is not arbitrary. MySQL refuses (error 1215) a foreign key with a
  -- referential action on any column an expression like this reads, and
  -- ticket_id needs ON DELETE CASCADE. requested_by_id's key has no action, so
  -- the pair works either way round and only one of them is available.
  open_requested_by_id BIGINT AS (IF(answered_at IS NULL, requested_by_id, NULL)) STORED,

  PRIMARY KEY (id),
  UNIQUE KEY uq_ticket_status_requests_open (ticket_id, open_requested_by_id),

  -- Every read is "…AND answered_at IS NULL", and NULL is seekable in an InnoDB
  -- index, so the open set is a range rather than a filter over the table.
  --
  -- "Am I waiting on anybody?" — the manager's list, longest wait first.
  KEY ix_ticket_status_requests_awaiting (requested_by_id, answered_at, requested_at),
  -- "Is anybody waiting on me?", and the reportable metric — answered requests
  -- over a period, per person asked. One index serves both.
  KEY ix_ticket_status_requests_asked_of (asked_of_id, answered_at),
  -- The lookup on every single chat post: has this thread anything outstanding?
  KEY ix_ticket_status_requests_thread (thread_id, answered_at),

  CONSTRAINT fk_ticket_status_requests_ticket
    FOREIGN KEY (ticket_id) REFERENCES tickets (id) ON DELETE CASCADE,
  CONSTRAINT fk_ticket_status_requests_thread
    FOREIGN KEY (thread_id) REFERENCES chat_threads (id) ON DELETE CASCADE,
  CONSTRAINT fk_ticket_status_requests_request_message
    FOREIGN KEY (request_message_id) REFERENCES chat_messages (id) ON DELETE CASCADE,
  CONSTRAINT fk_ticket_status_requests_answer_message
    FOREIGN KEY (answer_message_id) REFERENCES chat_messages (id) ON DELETE SET NULL,
  CONSTRAINT fk_ticket_status_requests_requested_by
    FOREIGN KEY (requested_by_id) REFERENCES users (id),
  CONSTRAINT fk_ticket_status_requests_asked_of
    FOREIGN KEY (asked_of_id) REFERENCES users (id),
  CONSTRAINT fk_ticket_status_requests_answered_by
    FOREIGN KEY (answered_by_id) REFERENCES users (id),

  -- A request cannot be half-answered. Every read treats answered_at as the
  -- authority on whether a row is open, and a row with a timestamp but nobody
  -- credited — or a duration with nothing to have measured — would make the
  -- metric and the badge disagree about the same row.
  --
  -- answer_message_id is deliberately NOT in this constraint, and MySQL is what
  -- forced the question: error 3823 refuses a column that is both checked and
  -- the target of an ON DELETE SET NULL foreign key. Given the choice, the
  -- pointer is the right thing to drop. The evidence is that the request WAS
  -- answered, WHEN, by WHOM and how long it took — all of which survive a
  -- message that is somehow removed, while a cascade would have taken the whole
  -- measurement with it. Physical deletion is theoretical anyway: §7.6 deletes
  -- leave a tombstone rather than removing the row.
  CONSTRAINT ck_ticket_status_requests_answered_together
    CHECK ((answered_at IS NULL
            AND answered_by_id IS NULL AND response_working_mins IS NULL)
        OR (answered_at IS NOT NULL
            AND answered_by_id IS NOT NULL AND response_working_mins IS NOT NULL))
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
