-- =====================================================================
-- A-128 · Client Onboarding — client escalations from the portal
--
-- Tables: ob_client_escalations
--
-- Source:  docs/Onboarding-Module-Plan.md §4 (data model, v1.2 addition),
--          §7 (notifications), §9 CP-03 and OB-02
--
-- SCOPE — A-128 is "service dependency & escalation schema", and **the
-- service-dependency half already landed**: `ob_journey_templates
-- .depends_on_template_id` and its self-FK are in A-103
-- (`V20260903_1420`), and the per-client hold it produces is
-- `ob_journeys.held_by_journey_id` in A-104 (`V20260903_1600`). Both were
-- created there rather than deferred here because a column belongs in the
-- migration that creates its table — adding it a file later would mean an
-- ALTER on a table nobody had used yet, for no gain.
--
-- So what is left of A-128 is the escalation table, and this is it.
--
-- WHO IS WAITING ON THIS FILE (docs/DEPENDENCIES.md):
--     C-126 portal escalation flow · B-127 the OB-02 Client Escalations
--     card and its drill-down
-- =====================================================================


-- ---------------------------------------------------------------------
-- ob_client_escalations — raised by the CLIENT, against one service.
--
-- Plan §4: "raised by the client from the portal against a specific
-- service: step_id, comment (required), raised_by contact, raised_at,
-- resolved_by/at."
--
-- THIS IS THE ONE TABLE IN THE MODULE A CLIENT WRITES TO DIRECTLY, and
-- the column types say so. `raised_by_contact_id` points at
-- `ob_client_contacts`, not at `users`: the author is an external
-- principal (plan §2.3's `principal_type: CLIENT`), and there is no staff
-- user to attribute it to. `resolved_by` points at `users`, because only
-- staff resolve. Two different people-columns pointing at two different
-- tables is the shape of the fact, not an inconsistency to tidy up.
--
-- "ONE OPEN ESCALATION PER SERVICE" IS ENFORCED HERE, and it is the
-- fourth use of the generated-column partial-index idiom in this module —
-- after `ob_client_contacts.is_primary_key` (A-101),
-- `ob_journey_templates.active_key` (A-103) and `ob_journeys.live_key`
-- (A-104). `open_key` is 1 while `resolved_at` is NULL; the unique index
-- ignores NULLs, so a service accumulates any number of *resolved*
-- escalations while never carrying two open ones.
--
-- Worth stating why that constraint matters rather than being tidy: plan
-- §7 says raising one notifies the onboarding manager and the service
-- owner **immediately, and it cannot be muted**. Without this key, a
-- client tapping Escalate twice on a slow connection sends two
-- unmutable notifications for one complaint, and OB-02's "Client
-- Escalations" card counts one client twice.
--
-- `comment` IS NOT NULL AND NOT EMPTY. §9 CP-03 makes the comment
-- mandatory on the portal control, and the check is repeated here because
-- an escalation with no text is a red chip on the dashboard that nobody
-- can act on — the notification fires, the manager opens it, and there is
-- nothing to read.
--
-- NO FK TO `ob_step_communications`. Plan §4 says a raised escalation is
-- "mirrored into the service's communication timeline as an ESCALATION
-- entry", and that table is A-106's append-only pair. The mirror is a
-- service-layer insert, deliberately one-directional: this row is the
-- record of the escalation, the timeline entry is a rendering of it, and
-- a foreign key between them would invite the timeline to be treated as
-- the source of truth for something a client can still resolve.
-- ---------------------------------------------------------------------
CREATE TABLE ob_client_escalations (
  id                    BIGINT        NOT NULL AUTO_INCREMENT,
  -- Denormalised from the step, so OB-02's card and its drill-over can
  -- count and group by client without joining three tables per row.
  -- CLAUDE.md forbids a live COUNT(*) behind a dashboard; this keeps the
  -- eventual summary refresh cheap rather than making it a join.
  ob_client_id          BIGINT        NOT NULL,
  journey_id            BIGINT        NOT NULL,
  step_id               BIGINT        NOT NULL,
  -- An external principal. Not a `users` row — see the note above.
  raised_by_contact_id  BIGINT        NOT NULL,
  comment               VARCHAR(2000) NOT NULL,
  raised_at             DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  resolved_by           BIGINT        NULL,
  resolved_at           DATETIME(6)   NULL,
  resolution_note       VARCHAR(2000) NULL,
  -- 1 while open, NULL once resolved. The unique index below ignores the
  -- NULLs, which is how one open escalation per service is enforced.
  open_key              TINYINT(1)
      GENERATED ALWAYS AS (IF(resolved_at IS NULL, 1, NULL)) STORED,
  created_at            DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at            DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                          ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_ob_client_escalations_open (step_id, open_key),
  -- OB-02's card: open escalations, newest first, grouped by client.
  KEY ix_ob_client_escalations_open (open_key, ob_client_id, raised_at),
  KEY ix_ob_client_escalations_journey (journey_id),
  KEY ix_ob_client_escalations_contact (raised_by_contact_id),
  KEY ix_ob_client_escalations_resolved_by (resolved_by),
  CONSTRAINT fk_ob_client_escalations_client
    FOREIGN KEY (ob_client_id) REFERENCES ob_clients (id),
  CONSTRAINT fk_ob_client_escalations_journey
    FOREIGN KEY (journey_id) REFERENCES ob_journeys (id),
  CONSTRAINT fk_ob_client_escalations_step
    FOREIGN KEY (step_id) REFERENCES ob_journey_steps (id),
  CONSTRAINT fk_ob_client_escalations_contact
    FOREIGN KEY (raised_by_contact_id) REFERENCES ob_client_contacts (id),
  CONSTRAINT fk_ob_client_escalations_resolved_by
    FOREIGN KEY (resolved_by) REFERENCES users (id),
  -- A red chip nobody can act on is worse than no chip.
  CONSTRAINT ck_ob_client_escalations_comment
    CHECK (comment <> ''),
  -- Resolved means somebody resolved it. The two columns move together or
  -- the OB-02 count and the client's own view disagree about who is waiting.
  CONSTRAINT ck_ob_client_escalations_resolved
    CHECK ((resolved_at IS NULL AND resolved_by IS NULL)
        OR (resolved_at IS NOT NULL AND resolved_by IS NOT NULL))
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
