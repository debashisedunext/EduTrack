-- D-042 · the per-user preference matrix (blueprint §7.7, S-26).
--
-- STORES ONLY DEVIATIONS FROM THE DEFAULT.
--
-- The obvious shape is a row per user per event per channel: 25 events x 2
-- channels x every user, written at signup. That is a materialised view of a
-- rule nobody has expressed, and it rots — adding an event to
-- NotificationEvent then means backfilling every user before the new event is
-- deliverable, and a user created between the deploy and the backfill silently
-- receives nothing for it. Worse, the failure is invisible: no error, just an
-- alert that never arrives, which §17 lists as the risk this whole module
-- exists to close.
--
-- So the default is "on" and lives in code (§7.7: everything not mandatory is
-- "opt-out or digestible"), and a row here means one person turned one thing
-- off. A new event is deliverable to everyone the moment it is declared, and
-- the table stays proportional to how much people actually customise rather
-- than to users x events.
--
-- `enabled` is still stored rather than implied by the row's existence: an
-- explicit `true` is how somebody re-enables something and how the UI shows a
-- deliberate choice, and a delete-to-reset model cannot tell "back to default"
-- from "never touched".

CREATE TABLE notification_preferences (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  user_id     BIGINT       NOT NULL,
  -- Not an FK to a lookup table: the vocabulary is NotificationEvent, which is
  -- code, and D-041 already established that reads must tolerate a code this
  -- build does not know rather than fail the page.
  event_code  VARCHAR(60)  NOT NULL,
  channel     VARCHAR(20)  NOT NULL,   -- IN_APP | EMAIL
  enabled     TINYINT(1)   NOT NULL,
  created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                      ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  -- One answer per person per event per channel. Without this a double-submit
  -- leaves two contradictory rows and the winner depends on read order.
  UNIQUE KEY uq_notification_preferences (user_id, event_code, channel),
  CONSTRAINT fk_notification_preferences_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The read is always "everything this one person overrode", loaded once and
-- applied in memory, so the unique key above already serves it on its leading
-- column. No second index: it would be maintained on every save and never
-- chosen.
