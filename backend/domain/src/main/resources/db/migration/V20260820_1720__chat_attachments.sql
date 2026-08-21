-- ---------------------------------------------------------------------
-- chat_attachments — D-053, blueprint §7.6's "file and image share".
--
-- WHY A SECOND TABLE RATHER THAN A NULLABLE ticket_id ON ticket_attachments
--
-- The blocked half of D-053 was recorded for months as "ticket_attachments.
-- ticket_id is NOT NULL, so an attachment on a DM or a project channel is
-- unrepresentable". The obvious fix is to drop that NOT NULL and add a
-- chat_message_id beside it. It is the wrong one:
--
--   * ticket_attachments.ticket_id NOT NULL is load-bearing for C-025's
--     scope check, C-028's delete window, C-060's cycle/stage grouping and
--     the AV worker's claim query. Making it nullable weakens a constraint
--     four features currently lean on, to serve a fifth that shares none of
--     them — a chat file has no cycle, no stage, no client-visibility flag
--     and no 15-minute delete window.
--   * A CHECK that exactly one of two FKs is set is a constraint every
--     future query has to remember, and the one that forgets reads a chat
--     file into a ticket's attachments tab.
--   * A new table breaks nobody's Flyway checksum and changes no existing
--     behaviour. An ALTER on a table five features read is a migration that
--     needs Stream A's review and can fail on contact with real data.
--
-- WHAT IS *NOT* DUPLICATED
--
-- The safety pipeline. AttachmentTypePolicy (sniff the bytes, reconcile
-- against the declared name), ImageMetadataStripper (EXIF) and
-- AttachmentScanner (AV) are already separate injectable beans, so a chat
-- upload calls the same three and gets the same verdict. D-053's own note
-- warned against "two answers next to each other for 'is this file safe'" —
-- there is still exactly one answer, called from a second place. Only the
-- storage key was ticket-shaped, and StorageKey now covers both namespaces.
--
-- NO is_client_visible COLUMN, DELIBERATELY
--
-- ticket_attachments carries it because §4B.4's client portal serves ticket
-- files. A chat thread is internal: §7.6's three surfaces are a ticket
-- thread, a direct message and a project channel, none of which a client
-- contact can reach. A flag that is always 0 is a flag somebody will
-- eventually set to 1, and there is no portal to honour it.
--
-- NO 15-MINUTE DELETE WINDOW COLUMN EITHER
--
-- D-057 already governs chat immutability: a message is editable by its
-- author for five minutes and deletion leaves a tombstone with the body
-- withheld rather than destroyed. An attachment belongs to its message and
-- follows it — deleted_at here mirrors the message's tombstone rather than
-- inventing a second, differently-sized window over the same evidence.
-- ---------------------------------------------------------------------
CREATE TABLE chat_attachments (
  id             BIGINT        NOT NULL AUTO_INCREMENT,
  thread_id      BIGINT        NOT NULL,
  -- NULL between the upload and the message that carries it being posted.
  -- The two are separate requests by design: the file is sniffed, stripped,
  -- stored and queued for the AV scan while the author is still typing, so
  -- the send is instant and a rejected file is rejected before they have
  -- written anything. An orphan is a file nobody ever sent — see the index
  -- below, which is what a future sweeper would claim on.
  message_id     BIGINT        NULL,
  file_name      VARCHAR(255)  NOT NULL,
  storage_key    VARCHAR(400)  NOT NULL,   -- chat/{threadId}/{uuid}
  mime_type      VARCHAR(100)  NOT NULL,   -- sniffed, never trusted from the client
  size_bytes     BIGINT        NOT NULL,
  thumbnail_key  VARCHAR(400)  NULL,
  scan_status    VARCHAR(15)   NOT NULL DEFAULT 'PENDING',
                               -- PENDING|CLEAN|INFECTED, same vocabulary as
                               -- ticket_attachments so one AV worker serves both
  uploaded_by    BIGINT        NULL,
  deleted_at     DATETIME(6)   NULL,
  created_at     DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_chat_attachments_storage_key (storage_key),
  KEY ix_chat_attachments_message (message_id),
  -- The listing: one thread's files, newest last, matching how a thread reads.
  KEY ix_chat_attachments_thread (thread_id, created_at),
  -- The AV worker claims PENDING rows.
  KEY ix_chat_attachments_scan (scan_status),
  -- Orphan sweep: uploaded, never attached to a message.
  KEY ix_chat_attachments_orphans (message_id, created_at),
  KEY ix_chat_attachments_uploader (uploaded_by),
  CONSTRAINT fk_chat_attachments_thread
    FOREIGN KEY (thread_id)  REFERENCES chat_threads (id) ON DELETE CASCADE,
  -- No ON DELETE CASCADE from the message: §7.6 keeps a deleted message as a
  -- tombstone rather than removing the row, so a cascade here would never
  -- fire in normal operation — and if a row ever were removed, silently
  -- destroying the evidence attached to it is the opposite of what §7.6 asks.
  CONSTRAINT fk_chat_attachments_message
    FOREIGN KEY (message_id) REFERENCES chat_messages (id),
  CONSTRAINT fk_chat_attachments_uploader
    FOREIGN KEY (uploaded_by) REFERENCES users (id),
  CONSTRAINT ck_chat_attachments_size CHECK (size_bytes > 0)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
