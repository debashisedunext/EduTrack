-- D-046 · offline queueing.
--
-- Blueprint §11: "If the user is offline when the event fires, the notification
-- is queued in the DB and pops the moment they log in — nothing is lost." And
-- §17, on the alert nobody saw: "a per-ticket delivery log so a missed alert is
-- provable rather than deniable."
--
-- Both need one fact the table cannot currently state: whether this
-- notification was ever put in front of the user. `is_read` does not answer it
-- — unread means they have not acted on it, which is true both of a toast they
-- dismissed and of one that fired while their laptop was shut. Replaying on
-- `is_read = 0` would re-pop everything a user has deliberately ignored, every
-- morning, until they cleared the bell.
--
-- Realtime delivery is best-effort (Redis pub/sub has no replay), so the server
-- cannot know a push arrived. The client stamps this instead, acknowledging
-- what it has actually shown — which makes the guarantee at-least-once: a
-- browser that dies between receiving and rendering re-pops on next login
-- rather than losing the alert.

ALTER TABLE notifications
  ADD COLUMN delivered_at DATETIME(6) NULL
    COMMENT 'D-046: when the client confirmed it showed this to the user; NULL = still queued';

-- The replay query is "this user's undelivered, oldest first". Left-most
-- user_id, then the NULL test, then id for the ordering — so it is an index
-- range scan rather than a filesort over every notification the user has ever
-- had. `ix_notifications_unread` cannot serve it: it leads with is_read, which
-- this query does not mention.
CREATE INDEX ix_notifications_undelivered
  ON notifications (user_id, delivered_at, id);
