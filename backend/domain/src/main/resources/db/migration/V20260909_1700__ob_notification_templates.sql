-- =====================================================================
-- B-113 · ob_notification_templates — OB-12's wording, as rows.
--
-- Source:  docs/streams/STREAM-B-MASTERS.md B-113 — "OB-11 and OB-12 —
--            TAT settings and email templates"
--          contracts/openapi.yaml `listObNotificationTemplates` /
--            `updateObNotificationTemplate`
--          docs/Onboarding-Module-Plan.md §7, §9 (OB-12)
--
-- WHY NOT notification_templates, ONE MODULE OVER.
-- The contract states three reasons and only the third is fatal on its
-- own. (1) The channels differ: NotificationChannel is IN_APP·EMAIL·PUSH,
-- ObChannel is EMAIL·WHATSAPP·IN_APP, and neither is a subset of the
-- other. (2) The categories differ: MENTION and STATUS_REQUEST mean
-- nothing here, PREREQUISITE and SIGNOFF mean nothing there. (3) A-115's
-- ArchUnit rule asserts the two modules stay separable, and one screen
-- writing rows another module's renderer resolves is exactly the coupling
-- it exists to refuse.
--
-- WHAT THIS DOES NOT REPLACE, AND THE HAND-OVER THAT IS OWED.
-- B-111's `ObMailTemplate` is 22 compile-time constants and IS STILL THE
-- RENDERER'S SOURCE. This table is authored wording for OB-12; repointing
-- `ObMailRenderer` at it is a behaviour change to the mail path that
-- wants its own review — and `worker/onboarding/outbox/` is Stream B's
-- carve-out but a running sender either way. Seeded from the same event
-- catalogue so the two agree on day one; the switch-over is stated on the
-- PR rather than smuggled in.
--
-- WHY (event_code, channel) IS THE KEY.
-- The identity `notification_templates` uses one module over, for the
-- same reason: the renderer resolves by what happened and how it is being
-- sent, and nothing else distinguishes two rows. Both are immutable —
-- changing either is a different template, which is why neither is on
-- ObNotificationTemplateUpdateRequest.
--
-- WHY is_mandatory IS NOT A COLUMN.
-- It is derived: channel = EMAIL and the category is ESCALATION or
-- SIGNOFF. A stored flag would be a second copy of a rule deliberately
-- stated over the category, and an escalation event added next month
-- would need somebody to remember to set it. Derived, it is covered the
-- moment the event is declared. Same argument for is_deliverable, which
-- is a fact about the deployment's adapters rather than about the row.
--
-- WHY category IS STORED THOUGH.
-- Unlike the two above it is not derivable from anything in this row —
-- it is a property of the event, and the event code is a free string
-- because the producers own the list. Storing it here is the only way a
-- filter over the table can work without the API resolving all twenty
-- events on every read.
-- =====================================================================

CREATE TABLE ob_notification_templates (
  id                BIGINT        NOT NULL AUTO_INCREMENT,

  -- One of plan §7's events. A string rather than an enum column for the
  -- reason notification_templates.event_code is one: the producers own the
  -- list and it grows with them, and a migration per event would make
  -- adding a producer a schema change.
  event_code        VARCHAR(60)   NOT NULL,

  category          VARCHAR(20)   NOT NULL,
  channel           VARCHAR(10)   NOT NULL,

  -- Comma-separated role codes. Deliberately not a child table: the set is
  -- tiny, always read whole with its parent, and never queried across rows
  -- — which is the case a join table would exist to serve. `recipients`
  -- must not be empty; ck_ob_notification_templates_recipients holds that,
  -- because a template with nobody to send to is a row that looks
  -- configured and sends nothing.
  recipients        VARCHAR(200)  NOT NULL,

  -- Required for EMAIL, NULL on IN_APP — which has a title rather than a
  -- subject. The CHECK below pairs them so a mail template cannot be saved
  -- with no subject line.
  subject_template  VARCHAR(255)  NULL,

  -- HTML for EMAIL, plain text otherwise. TEXT rather than VARCHAR: the
  -- contract caps a body at 20000 characters, which is beyond the 16383
  -- an entirely-4-byte utf8mb4 VARCHAR can hold within the 65535-byte row
  -- limit, and a column that refuses a legal request is worse than one
  -- statement of the cap in the DTO.
  body_template     TEXT          NOT NULL,

  is_active         TINYINT(1)    NOT NULL DEFAULT 1,

  updated_by        BIGINT        NULL,
  created_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                      ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),

  UNIQUE KEY uq_ob_notification_templates (event_code, channel),
  KEY ix_ob_notification_templates_channel (channel, category),

  CONSTRAINT fk_ob_notification_templates_updated_by
    FOREIGN KEY (updated_by) REFERENCES users (id),

  CONSTRAINT ck_ob_notification_templates_channel
    CHECK (channel IN ('EMAIL', 'WHATSAPP', 'IN_APP')),
  CONSTRAINT ck_ob_notification_templates_category
    CHECK (category IN ('PREREQUISITE', 'SERVICE', 'ESCALATION', 'SIGNOFF', 'ACCOUNT')),
  CONSTRAINT ck_ob_notification_templates_recipients
    CHECK (recipients <> ''),
  -- An EMAIL row carries a subject; anything else does not.
  CONSTRAINT ck_ob_notification_templates_subject
    CHECK ((channel = 'EMAIL' AND subject_template IS NOT NULL)
        OR (channel <> 'EMAIL' AND subject_template IS NULL))
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- Seeds — one EMAIL row per plan §7 event, categorised as OB-12 groups
-- them.
--
-- The category mapping is by subject matter and is stated here because
-- it is a judgement rather than a lookup: PREREQ_* are PREREQUISITE,
-- SIGNOFF_* are SIGNOFF, the two CLIENT_* credential events are ACCOUNT,
-- anything about lateness or an objection is ESCALATION, and the rest —
-- gates, steps, journeys, go-live — is SERVICE.
--
-- Note this is NOT ObCategory, which is OB-13's tab vocabulary
-- (ASSIGNMENT · ESCALATION · REMINDER · UPDATE) and answers a different
-- question. An event has both: what kind of thing happened (here) and
-- what the reader is expected to do about it (there). SIGNOFF_OBJECTED
-- is ObCategory.ESCALATION and ObNotificationCategory.SIGNOFF, and both
-- are right.
--
-- Bodies are deliberately terse. B-111's ObMailTemplate holds the real
-- wording and is still what the renderer uses; these are the starting
-- point OB-12 edits, and seeding a second copy of 22 long templates here
-- would create two texts to keep in step before anybody had edited
-- either.
-- ---------------------------------------------------------------------

INSERT INTO ob_notification_templates
       (event_code, category, channel, recipients, subject_template, body_template) VALUES
  ('CLIENT_LOGIN_CREATED',      'ACCOUNT',      'EMAIL', 'CLIENT_CONTACT',
   'Your {{client_name}} portal login',
   '<p>Hello {{contact_name}},</p><p>Your username is <b>{{portal_username}}</b>. Set your password using the link below.</p><p>{{action_url}}</p>'),
  ('CLIENT_PASSWORD_RESET',     'ACCOUNT',      'EMAIL', 'CLIENT_CONTACT',
   'Reset your {{client_name}} portal password',
   '<p>Hello {{contact_name}},</p><p>Use the link below to set a new password. It expires in {{link_expires_in}}.</p><p>{{action_url}}</p>'),
  ('PREREQ_SUBMITTED',          'PREREQUISITE', 'EMAIL', 'STEP_OWNER,ONBOARDING_MANAGER',
   '{{client_name}}: {{prereq_title}} submitted',
   '<p>{{client_name}} has submitted <b>{{prereq_title}}</b> for verification.</p><p>{{action_url}}</p>'),
  ('PREREQ_VERIFIED',           'PREREQUISITE', 'EMAIL', 'CLIENT_CONTACT',
   '{{prereq_title}} verified',
   '<p><b>{{prereq_title}}</b> has been verified.</p>'),
  ('PREREQ_RETURNED',           'PREREQUISITE', 'EMAIL', 'CLIENT_CONTACT',
   '{{prereq_title}} needs another look',
   '<p><b>{{prereq_title}}</b> has been returned. Please review and resubmit.</p>'),
  ('PREREQ_TAT_REMINDER',       'PREREQUISITE', 'EMAIL', 'CLIENT_CONTACT',
   'Reminder: {{prereq_title}}',
   '<p><b>{{prereq_title}}</b> is still outstanding.</p>'),
  ('GATE_OPENED',               'SERVICE',      'EMAIL', 'STEP_OWNER,ONBOARDING_MANAGER',
   '{{client_name}}: onboarding has started',
   '<p>Every mandatory prerequisite is verified and {{client_name}} onboarding is under way.</p>'),
  ('STEP_ASSIGNED',             'SERVICE',      'EMAIL', 'STEP_OWNER',
   'Assigned to you: {{step_name}}',
   '<p><b>{{step_name}}</b> for {{client_name}} is now yours.</p><p>{{action_url}}</p>'),
  ('TAT_REMINDER',              'SERVICE',      'EMAIL', 'STEP_OWNER',
   'Due soon: {{step_name}}',
   '<p><b>{{step_name}}</b> for {{client_name}} is approaching its turnaround time.</p>'),
  ('TAT_BREACHED',              'ESCALATION',   'EMAIL', 'STEP_OWNER,ONBOARDING_MANAGER',
   'Overdue: {{step_name}}',
   '<p><b>{{step_name}}</b> for {{client_name}} has passed its turnaround time.</p>'),
  ('ESCALATION_RAISED',         'ESCALATION',   'EMAIL', 'ONBOARDING_MANAGER,OB_ADMIN',
   'Escalation on {{client_name}}',
   '<p><b>{{step_name}}</b> has escalated.</p>'),
  ('STEP_SKIPPED',              'SERVICE',      'EMAIL', 'ONBOARDING_MANAGER',
   '{{step_name}} was skipped',
   '<p><b>{{step_name}}</b> for {{client_name}} was skipped.</p>'),
  ('JOURNEY_UNBLOCKED',         'SERVICE',      'EMAIL', 'STEP_OWNER',
   '{{client_name}}: work can continue',
   '<p>A blocking dependency has cleared and work on {{client_name}} can continue.</p>'),
  ('CLIENT_ESCALATION_RAISED',  'ESCALATION',   'EMAIL', 'ONBOARDING_MANAGER,OB_ADMIN',
   '{{client_name}} has raised an escalation',
   '<p>{{client_name}} has raised an escalation.</p>'),
  ('CLIENT_ESCALATION_RESOLVED','SERVICE',      'EMAIL', 'CLIENT_CONTACT',
   'Your escalation has been resolved',
   '<p>The escalation you raised has been resolved.</p>'),
  ('MANAGER_DIGEST',            'ESCALATION',   'EMAIL', 'ONBOARDING_MANAGER',
   'Onboarding: what is stuck today',
   '<p>The journeys below have not moved.</p>'),
  ('SIGNOFF_REQUESTED',         'SIGNOFF',      'EMAIL', 'CLIENT_CONTACT',
   'Please confirm: {{step_name}}',
   '<p>Please review and confirm <b>{{step_name}}</b>.</p><p>{{action_url}}</p>'),
  ('SIGNOFF_OTP',               'SIGNOFF',      'EMAIL', 'CLIENT_CONTACT',
   'Your confirmation code',
   '<p>Your code is <b>{{otp_code}}</b>. It expires in {{otp_expires_in}}.</p>'),
  ('SIGNOFF_OBJECTED',          'SIGNOFF',      'EMAIL', 'STEP_OWNER,ONBOARDING_MANAGER',
   '{{client_name}} has objected to {{step_name}}',
   '<p>{{client_name}} has objected. The service has returned to in progress.</p>'),
  ('GO_LIVE',                   'SERVICE',      'EMAIL', 'CLIENT_CONTACT,ONBOARDING_MANAGER',
   '{{client_name}} is live',
   '<p>{{client_name}} is now live.</p>');
