-- ---------------------------------------------------------------------
-- A-044 · the anchor that makes tail truncation detectable.
--
-- A-042 recorded this gap and handed it here. Deleting the LAST few rows of
-- a ticket's chain leaves something that verifies perfectly: every row_hash
-- recomputes, every prev_hash matches its predecessor, exactly one genesis.
-- Nothing anywhere records how long the chain should have been, so every
-- check the verifier can make passes. Chaining cannot close this by itself —
-- it needs a record kept outside the chain.
--
-- WHAT MAKES THE COMPARISON WORK
--
-- These three tables are append-only, so a chain's length only ever grows.
-- That turns detection into arithmetic rather than cryptography: store the
-- row count and the head hash each time a chain is verified clean, and a
-- later run finding FEWER rows — or a head that is no longer present — is
-- looking at a truncation. No proof required, just a number that cannot
-- legitimately go down.
--
-- WHY THE TRIGGER IS THE POINT, NOT THE TABLE
--
-- An anchor an attacker can lower is not an anchor. Having deleted three
-- history rows, the obvious next move is to write row_count = 11 over the
-- 14 that was stored — and trg_chain_anchor_monotonic refuses it, because a
-- count that decreases describes something that cannot have happened.
-- Together with the DELETE trigger, laundering a truncation now means
-- defeating a second trigger on a second table, consistently, in the same
-- breath as the first.
--
-- HONEST LIMIT, stated rather than discovered later: this anchor lives in
-- the same database as the rows it vouches for. It is defence in depth, not
-- proof. Anchoring somewhere the application cannot reach at all — a
-- published head hash, an append-only store off this host — is a go-live
-- concern (A-075), not this one.
--
-- GRANTS: docker/grants/apply-app-grants.sql names this table explicitly.
-- Its default branch would hand out DELETE along with the rest of DML, and
-- deleting an anchor erases precisely the memory it exists to hold.
-- ---------------------------------------------------------------------

CREATE TABLE chain_anchors (
  ticket_id     BIGINT       NOT NULL,
  table_name    VARCHAR(32)  NOT NULL,   -- which of the three chains
  row_count     BIGINT       NOT NULL,   -- monotonic: append-only guarantees it
  head_row_id   BIGINT       NOT NULL,   -- the last row seen, for a direct existence check
  head_row_hash CHAR(64)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  verified_at   DATETIME(6)  NOT NULL,   -- when this chain was last known good
  PRIMARY KEY (ticket_id, table_name),
  -- Spelled out rather than left to the writer. A typo'd table_name would
  -- create a second anchor row that never matches anything and silently
  -- stops covering the chain it was meant to.
  CONSTRAINT ck_chain_anchor_table CHECK (
    table_name IN ('ticket_history', 'ticket_effort_logs', 'ticket_stage_transitions')
  ),
  -- An anchor is only written for a chain that has rows; zero would make
  -- "no rows yet" and "all rows deleted" the same record.
  CONSTRAINT ck_chain_anchor_count CHECK (row_count > 0),
  CONSTRAINT fk_chain_anchor_ticket
    FOREIGN KEY (ticket_id) REFERENCES tickets (id)
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


DELIMITER $$

-- An anchor moves forward or not at all. row_count is the whole defence:
-- append-only means it cannot legitimately decrease, so an UPDATE that
-- lowers it is either a bug in the verifier or somebody covering a deletion.
-- Both are worth refusing loudly.
--
-- ticket_id and table_name are the primary key and are pinned too: silently
-- re-pointing an anchor at another chain would leave the original chain
-- unanchored while a row still appears to vouch for it.
CREATE TRIGGER trg_chain_anchor_monotonic BEFORE UPDATE ON chain_anchors
FOR EACH ROW
BEGIN
  IF NEW.row_count < OLD.row_count THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'chain_anchors.row_count cannot decrease: these tables are append-only, so a shorter chain means rows were deleted.';
  END IF;
  IF NEW.ticket_id <> OLD.ticket_id OR NEW.table_name <> OLD.table_name THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'chain_anchors identity is immutable: re-pointing an anchor leaves the chain it named unanchored.';
  END IF;
END$$

-- Deleting the anchor is the same attack as lowering it, one step further:
-- with no anchor, the next run writes a fresh one from whatever is left and
-- the truncation becomes the new normal.
CREATE TRIGGER trg_chain_anchor_no_delete BEFORE DELETE ON chain_anchors
FOR EACH ROW
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'chain_anchors rows are never deleted: the anchor is the memory of how long a chain was.'$$

DELIMITER ;
