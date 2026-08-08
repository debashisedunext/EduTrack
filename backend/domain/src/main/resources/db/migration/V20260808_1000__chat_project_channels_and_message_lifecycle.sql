-- ---------------------------------------------------------------------------
-- D-050 · what the chat engine needs that the baseline could not express.
--
-- Stream D wrote this. It touches chat_* only — none of the append-only
-- tables — but it alters Stream A's baseline, so it wants Shivendra's eye.
--
-- Two gaps, both found by building against contracts/openapi.yaml:
--
-- 1. Blueprint §7.6 is "three surfaces, one engine": ticket thread, direct
--    message, project channel. The baseline has no way to say which project a
--    channel belongs to, so two of the three surfaces were unrepresentable.
--
-- 2. §7.6 also requires messages to be immutable after a five-minute edit
--    window, with deletions leaving a tombstone — the property that keeps chat
--    admissible as project evidence. The baseline has no edited_at and no
--    deleted_at, so an edit was indistinguishable from the original and a
--    delete would have to be a DELETE. D-057 implements the window; without
--    these columns it has nowhere to record either fact.
--
-- On ASK_STATUS: the baseline models it as a thread_type, while the contract
-- (and §7.6, which says the structured message is posted "into that ticket's
-- thread") models it as a message kind. The blueprint wins on behaviour, so
-- chat_messages.kind is what "Ask Status" will set. thread_type ASK_STATUS is
-- left alone rather than dropped — nothing writes it yet, and removing a value
-- from someone else's baseline is not Stream D's call to make quietly.
-- ---------------------------------------------------------------------------

ALTER TABLE chat_threads
  ADD COLUMN project_id BIGINT NULL AFTER ticket_id,
  ADD KEY ix_chat_threads_project (project_id),
  ADD CONSTRAINT fk_chat_threads_project
    FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE;

-- A thread hangs off exactly one thing, or off nothing when it is a DM.
-- Without this a "project channel" could carry a ticket_id and be delivered to
-- the wrong STOMP destination — a message landing in a room its participants
-- are not in is a disclosure, not a display bug.
ALTER TABLE chat_threads
  ADD CONSTRAINT ck_chat_threads_one_anchor
    CHECK (
      (thread_type = 'PROJECT' AND project_id IS NOT NULL AND ticket_id IS NULL)
      OR (thread_type IN ('TICKET', 'ASK_STATUS') AND ticket_id IS NOT NULL AND project_id IS NULL)
      OR (thread_type = 'DIRECT' AND ticket_id IS NULL AND project_id IS NULL)
    );

ALTER TABLE chat_messages
  -- TEXT | STATUS_REQUEST | SYSTEM, matching ChatMessage.kind in the contract.
  ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'TEXT' AFTER body,
  -- NULL means never edited. The five-minute window is enforced in the service;
  -- this records that it happened, so a reader can see the message changed.
  ADD COLUMN edited_at DATETIME(6) NULL AFTER created_at,
  -- The tombstone. The row stays, the body is withheld on read. A deleted
  -- message that vanished entirely would leave a conversation that reads as if
  -- it never happened, which is exactly what §7.6 is guarding against.
  ADD COLUMN deleted_at DATETIME(6) NULL AFTER edited_at,
  ADD COLUMN deleted_by BIGINT NULL AFTER deleted_at,
  ADD CONSTRAINT fk_chat_messages_deleted_by
    FOREIGN KEY (deleted_by) REFERENCES users (id);

-- is_system predates kind and means the same thing for the rows that exist.
-- Backfilled rather than dropped: dropping a column from Stream A's baseline
-- is their call, and leaving it unsynchronised would give two answers to the
-- same question.
UPDATE chat_messages SET kind = 'SYSTEM' WHERE is_system = 1;
