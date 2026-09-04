-- =====================================================================
-- B-112 · OB-13, the onboarding notification centre.
--
-- Table: ob_notifications
--
-- Source:  docs/Onboarding-Module-Plan.md §7 (notifications), §9 OB-13
--          PHASE-2-BUILD-PLAN.md §73 ("both — the popover is the daily
--          surface; a full page is needed for history and for the digest
--          links to land somewhere") and §210, which assigns
--          `api/feature/onboarding/notifications/` and the notification
--          centre to Stream B.
--
-- WHY A SECOND BELL STORE RATHER THAN `notifications`
--
-- The obvious move is to reuse Stream D's `notifications` (A-007) and add
-- nineteen onboarding codes to its `NotificationEvent`. Three things stop
-- that, and none of them is ownership:
--
--   1. `NewNotification` takes a `NotificationEvent`, not a string, so
--      every onboarding event would have to be declared in Stream D's
--      enum — the cross-stream edit B-110 and B-111 both declined, and
--      the one `ObNotificationEvent` exists to avoid.
--   2. `notifications` carries `ticket_id` and nothing else. An
--      onboarding entry's drill-down is a client, a journey and a step,
--      and there is nowhere in that table to put them.
--   3. S-26's tabs are Mentions / Assignments / Escalations / Status
--      requests. None of those is an onboarding tab, and a developer's
--      ticketing bell filling with TAT reminders for clients they do not
--      own is how a bell stops being read at all.
--
-- The module's separability argument (plan §1.2, `ob_`-prefixed
-- throughout) reaches the same answer from the other side.
--
-- STAFF ONLY, AND THAT IS THE POINT OF THE SINGLE RECIPIENT COLUMN
--
-- Every neighbouring table in this module carries the two-column
-- recipient pair — `ob_notification_outbox` (A-107), `ob_step_history`
-- (A-106) — because §7's events reach staff *and* client SPOCs. This one
-- deliberately does not. OB-13 is a staff screen; §9's portal list
-- (CP-01…CP-07) has no notification centre at all, so a row addressed to
-- a contact would be a notification with no surface — worse than none,
-- because whoever queued it believes the client was told.
--
-- The stronger reason is the one `ObMailAudience` already makes for
-- wording: **a client's notifications must never be one WHERE clause
-- away from a staff member's.** CP-03 hides owner names, internal
-- comments and block reasons; a shared table makes a forgotten predicate
-- the whole distance between that rule and a leak. When the portal earns
-- a surface, it gets its own store and its own visibility rules, decided
-- then. Until then `IN_APP` to a client contact is refused by the adapter
-- with a reason, so it is visible rather than silent.
--
-- MUTABLE, LIKE THE OUTBOX AND FOR A NARROWER REASON
--
-- `is_read`/`read_at` change in place. This is a reading surface, not a
-- record of what happened — `ob_step_history` is that (A-106,
-- hash-chained) and every auditable onboarding event writes there as
-- well. None of the protected tables is touched.
--
-- `outbox_id` IS UNIQUE, AND THAT UNIQUENESS IS THE DELIVERY GUARANTEE.
-- B-110's dispatcher reclaims a lapsed lease and re-delivers, so an
-- adapter may run twice for one queue row — normal, and on EMAIL it
-- costs a duplicate mail. Here it would cost a duplicate bell entry,
-- which the reader cannot tell from two real events. The unique key
-- makes the second write a no-op the adapter reports as delivered, so
-- exactly-once is the database's answer rather than the worker's.
--
-- Nullable, because it is the *queue's* id: B-114's digest and anything
-- else that writes an entry directly has no outbox row, and MySQL's
-- UNIQUE admits many NULLs.
--
-- The FK takes the default RESTRICT, like every other in this module, and
-- the consequence is worth naming rather than discovering: **a queue row a
-- bell entry points at cannot be deleted.** Nothing purges
-- `ob_notification_outbox` today. Whoever writes that job has to decide
-- what happens to the entries — clear the column, delete them, or keep
-- the queue rows a surface still references — and this makes that a
-- decision rather than a silent severing of the trace from an entry back
-- to the message that produced it. `ON DELETE SET NULL` would have chosen
-- for them, which is the shape row 86 argued against for `ob_signoffs`.
--
-- WHO IS WAITING (docs/DEPENDENCIES.md):
--     B-114 the manager digest (its links land on OB-13) ·
--     B-124/B-125 prerequisites · B-115..B-118 sign-off and go-live ·
--     C-115 the escalation matrix · D-102 escalation events
-- =====================================================================

CREATE TABLE ob_notifications (
  id                  BIGINT        NOT NULL AUTO_INCREMENT,

  -- Staff only. See the note above before adding a contact column.
  recipient_user_id   BIGINT        NOT NULL,

  -- `ObNotificationEvent.key()`. VARCHAR(60) to match
  -- `ob_notification_outbox.event_key`; read tolerantly, so a row written
  -- by a newer deploy still renders under All.
  event_key           VARCHAR(60)   NOT NULL,
  -- OB-13's tabs. Stored rather than derived from `event_key` so the
  -- tab filter is an indexed predicate instead of an IN-list the API
  -- rebuilds from an enum on every request.
  category            VARCHAR(20)   NOT NULL,
                      -- ASSIGNMENT|ESCALATION|REMINDER|UPDATE

  title               VARCHAR(200)  NOT NULL,
  body                TEXT          NULL,
  -- Relative, in-app, and derived from the ids below — never from the
  -- payload's `action_url`, which is minted for a *mail* recipient and on
  -- two events carries a one-time token belonging to a client.
  link_url            VARCHAR(500)  NULL,

  -- Drill-down context. All nullable: a client-login notice has no step,
  -- and a digest has no single journey.
  ob_client_id        BIGINT        NULL,
  journey_id          BIGINT        NULL,
  step_id             BIGINT        NULL,

  -- The queue row this entry was delivered from. See the note above.
  outbox_id           BIGINT        NULL,

  is_read             TINYINT(1)    NOT NULL DEFAULT 0,
  read_at             DATETIME(6)   NULL,
  created_at          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (id),

  -- Exactly-once delivery per queue row. NULLs are not compared, so
  -- directly-written entries are unaffected.
  UNIQUE KEY uq_ob_notifications_outbox (outbox_id),

  -- The badge count and the unread tab, which run on every page load for
  -- every user of the module. `id` last so the list's cursor paging stays
  -- on the same index as the count.
  KEY ix_ob_notifications_unread (recipient_user_id, is_read, id),
  -- The tab filter, and the full page's default ordering.
  KEY ix_ob_notifications_feed (recipient_user_id, category, id),
  KEY ix_ob_notifications_client (ob_client_id, created_at),
  KEY ix_ob_notifications_journey (journey_id),
  KEY ix_ob_notifications_step (step_id),

  CONSTRAINT fk_ob_notifications_user
    FOREIGN KEY (recipient_user_id) REFERENCES users (id),
  CONSTRAINT fk_ob_notifications_client
    FOREIGN KEY (ob_client_id) REFERENCES ob_clients (id),
  CONSTRAINT fk_ob_notifications_journey
    FOREIGN KEY (journey_id) REFERENCES ob_journeys (id),
  CONSTRAINT fk_ob_notifications_step
    FOREIGN KEY (step_id) REFERENCES ob_journey_steps (id),
  CONSTRAINT fk_ob_notifications_outbox
    FOREIGN KEY (outbox_id) REFERENCES ob_notification_outbox (id),

  CONSTRAINT ck_ob_notifications_category
    CHECK (category IN ('ASSIGNMENT', 'ESCALATION', 'REMINDER', 'UPDATE')),
  -- A read row has a timestamp and an unread row has none. The pair is
  -- what "when did you see this" is read from, and a row that is read
  -- with no `read_at` answers that question with silence.
  CONSTRAINT ck_ob_notifications_read
    CHECK ((is_read = 1 AND read_at IS NOT NULL)
        OR (is_read = 0 AND read_at IS NULL))
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
