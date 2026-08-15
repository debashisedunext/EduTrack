-- =====================================================================
-- B-022 · S-15 Notification Template Master — the recipient list, and
-- the first rows this table has ever held.
--
-- Table: notification_templates (created by A-007, V20260805_1106)
--
-- FLAGGED FOR STREAM A. `notification_templates` is A-007's table and
-- this adds a column to it. CLAUDE.md mandates Stream A's review only
-- for `tickets`, `ticket_history`, `ticket_effort_logs` and
-- `ticket_stage_transitions`, and this touches none of those — but the
-- baseline should not grow a column on a masters branch unremarked.
-- Same precedent B-011, B-016, B-017 and B-019 each set.
--
-- FLAGGED FOR STREAM D. The rows seeded below are the wording D-010's
-- mail engine and D-043's toast will render, and the channel vocabulary
-- section states a decision that contradicts A-007's own column comment.
-- Read that section before building the renderer.
--
-- WHO IS WAITING ON THIS FILE:
--     D-010  outbox worker — `email_log.template_id` has pointed here
--            since V20260805_1530 and has never been non-null, because
--            there was nothing to point at
--     D-043  in-app toast — same wording, same rows, IN_APP channel
--     D-036  the mandatory-mail rule, which this master must not be
--            able to defeat (see section 3)
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. `recipients` — WHO a template goes to, and why it is NOT a role
--
-- Blueprint §4B.6 asks for a "per-role recipient list" and the backlog
-- line repeats the phrase, so the obvious shape is a join table onto
-- `roles`. That shape is wrong, and §11's own "To" column is the proof:
--
--     Assignee · New stage owner · Previous assignee · Reporter ·
--     Watchers · Mentioned user · Client contact · Reporting Manager ·
--     PM · Support Desk
--
-- Two of those ten are role codes. The other eight are positions
-- **relative to one ticket**, resolved per send: "Assignee" is a column
-- on `tickets`, "Watchers" is a join table, "Client contact" is not a
-- platform user at all — `email_log.to_user_id` is nullable for exactly
-- that case. A foreign key into `roles` could carry PM and
-- REPORTING_MANAGER and nothing else, and the eight that did not fit
-- would end up somewhere second, which is how one list becomes two.
--
-- So this is one closed vocabulary covering all ten, held as a
-- comma-delimited set — `NotificationRecipient` in the domain module is
-- the spelling, and `NotificationTemplateService` is what refuses a
-- token outside it.
--
-- THE B-011 TEST — is this a set somebody queries ACROSS rows? Nothing
-- asks "which templates go to the Reporting Manager"; the renderer asks
-- one template who its recipients are, while already holding that row.
-- `users.skills` (V20260811_1520) and `projects.mandatory_fields`
-- (V20260814_1120) made the same call, and this set is smaller and more
-- bounded than either.
--
-- NOT NULL with no default. Every seeded row below names its
-- recipients, and a template with an empty list is a notification with
-- nowhere to go — a row that looks configured and sends nothing. The
-- service refuses an empty list with a 400 rather than letting the
-- column carry a state that means "silently does nothing"; the column
-- default exists only so this ALTER can run against the zero rows the
-- table holds today, and is dropped immediately after.
-- ---------------------------------------------------------------------
ALTER TABLE notification_templates
  ADD COLUMN recipients VARCHAR(255) NOT NULL DEFAULT '' AFTER channel;

ALTER TABLE notification_templates
  ALTER COLUMN recipients DROP DEFAULT;


-- ---------------------------------------------------------------------
-- 2. THE CHANNEL VOCABULARY IS `IN_APP | EMAIL | PUSH`, AND A-007's
--    COLUMN COMMENT SAYING `POPUP | BELL | EMAIL` IS SUPERSEDED
--
-- A-007 wrote that comment before Stream D built the notification
-- centre. D-042 then shipped `NotificationChannel` as IN_APP, EMAIL and
-- PUSH, and everything that runs today keys on those three:
-- `notification_preferences` rows, `OutboxEnqueuer`'s mandatory-mail
-- check and `push_subscriptions`. S-15's own line in blueprint §7.4
-- reads "channel (in-app / email / push)" — the same three.
--
-- A template stored as `POPUP` would therefore never be found by a
-- renderer looking up `IN_APP`, and the failure is silent: the lookup
-- misses, the send falls back to a hand-built string, and nobody
-- discovers that the master an Admin has been editing was never read.
--
-- THE BELL IS NOT A CHANNEL, and D-042's javadoc already argues why —
-- it counts what was written, and a preference that emptied it would be
-- hiding the record rather than quieting a delivery. So §11's popup and
-- bell columns are one template, not two: `notifications.title` and
-- `notifications.body` are what the bell renders and what the toast
-- renders, and they are the same two strings. An event marked bell-only
-- in §11 (a reassignment away, an 80%-elapsed warning) gets an IN_APP
-- template all the same; what makes it bell-only rather than a toast is
-- D-043's popup rule, not the absence of wording.
--
-- NO CHECK CONSTRAINT ON `channel`, DELIBERATELY. B-016 pinned
-- `ck_projects_status` in the schema because blueprint S-10 fixes those
-- three values. This list is not fixed: §7.7 marks Teams, Slack and
-- WhatsApp as optional-with-no-owner, and `NotificationChannel` is
-- Stream D's enum. A CHECK here would mean D cannot add a channel
-- without a migration in Stream B's directory — a cross-stream block,
-- bought for a vocabulary that is already validated on the one write
-- path that reaches this column. Same argument V20260814_1120 made
-- about `projects.mandatory_fields`, and the same for `recipients`.
-- ---------------------------------------------------------------------


-- ---------------------------------------------------------------------
-- 3. THE SEED — every event this build can raise, with wording
--
-- One row per (event, channel) pair §11 marks with a tick, plus the
-- four codes `NotificationEvent` carries that §11 does not: D-022's
-- stale-ticket nudge, D-033's mail-delivery failure, D-034's address
-- suppression and A-044's chain-verification alarm. Those four are
-- real events with real producers, and an event without a template is
-- an event whose wording lives in code — which is the state this whole
-- table exists to end.
--
-- §11's popup and bell columns collapse into IN_APP per section 2, so
-- an event ticked in either gets one IN_APP row. `subject_template` is
-- NULL on every IN_APP row: the column is nullable precisely because a
-- bell entry has a title, not a subject, and `notifications.title` is
-- the first line of the body.
--
-- TWO EVENTS IN "MANDATORY" CATEGORIES GET NO EMAIL ROW, AND THAT IS
-- §11 READ CORRECTLY RATHER THAN A GAP.
--
-- `NotificationEvent.isMandatoryMail()` is stated over the event's
-- CATEGORY — that is what keeps it in step with D-036 and what makes a
-- new escalation event mandatory the moment it is declared — so it
-- necessarily says true for a few events whose §11 row has a dash in the
-- Email column. There are exactly two:
--
--   STATUS_REQUEST_ANSWERED   D-055 already wrote this one down and
--                             called it moot: an event that sends no
--                             mail cannot have its mail silenced.
--
--   TICKET_REASSIGNED_AWAY    An ASSIGNMENT, because being taken off a
--                             ticket does change who is responsible —
--                             and §11 gives it a bell entry and no mail,
--                             which is right. The person is being told
--                             that work has LEFT them, not arrived.
--
-- Seeding an email for either, to satisfy a category rule, would be
-- inventing a mail the blueprint says not to send.
-- `NotificationTemplateMasterIT` asserts both directions, so a third one
-- appearing is a failure and a decision rather than a silent omission.
--
-- TWO ROWS ARE ABSENT ON PURPOSE:
--
--   MAIL_DELIVERY_FAILED has no EMAIL row. §4B.6 says the failure
--   surfaces "in-app so nobody assumes a mail arrived that never did",
--   and mailing somebody about a mail that would not send is a loop
--   whose best case is that it also fails.
--
--   No event has a PUSH row. §11 has no push column, and D-045 sends a
--   push from the notification it has already written rather than from
--   separate wording. A PUSH template is creatable on S-15 the day
--   somebody wants push text to differ; seeding 25 rows nothing reads
--   would be inventing content to fill a matrix.
--
-- THE EMAIL BODIES ARE DELIBERATELY PLAIN. §4B.6 fixes what a mail
-- carries — level, project, client, stage, planned close, who acted and
-- what they said, an "Open ticket" button — and these carry it, in
-- table-free HTML that survives Outlook. The house style, the logo and
-- the button chrome belong in D-010's layout, wrapped around these;
-- baking them into 24 seeded rows would mean a restyle is 24 Admin
-- edits. The subject lines follow §4B.6's table exactly, minus the
-- `[CRM-26-00347]` prefix — `OutboxEnqueuer` prepends the ticket code
-- itself (D-031) so that no event can ship without it, and repeating it
-- here would double it on every mail the system sends.
--
-- Every `{{tag}}` below is in `MergeTag`, and `NotificationTemplate-
-- Service` refuses one that is not. A typo'd `{{ticketId}}` renders as
-- literal braces in a client-facing mail and the Admin who typed it
-- would never find out.
-- ---------------------------------------------------------------------

INSERT INTO notification_templates
  (event_code, channel, recipients, subject_template, body_template, is_active)
VALUES

-- ── assignments — who is responsible, or what is expected of them ────
  ('TICKET_ASSIGNED', 'IN_APP', 'ASSIGNEE', NULL,
   '{{ticket_id}} assigned to you — {{level}}. {{ticket_title}}. Due {{planned_close}}.', 1),
  ('TICKET_ASSIGNED', 'EMAIL', 'ASSIGNEE',
   'New ticket assigned to you — {{level}}',
   '<p>{{actor}} assigned <strong>{{ticket_id}}</strong> to you.</p><p>{{ticket_title}}</p><p>Level {{level}} · {{project}} · {{client}} · stage {{stage}} · planned close {{planned_close}}</p><p><a href="{{ticket_url}}">Open ticket</a></p>', 1),

  ('HANDOFF_RECEIVED', 'IN_APP', 'STAGE_OWNER', NULL,
   '{{ticket_id}} handed to you at {{stage}} by {{actor}}. {{ticket_title}}.', 1),
  ('HANDOFF_RECEIVED', 'EMAIL', 'STAGE_OWNER',
   'Handed to you at {{stage}} by {{actor}}',
   '<p>{{actor}} moved <strong>{{ticket_id}}</strong> to <strong>{{stage}}</strong>, and you own that stage.</p><p>{{ticket_title}}</p><p>{{comment}}</p><p>Level {{level}} · {{project}} · {{client}} · planned close {{planned_close}}</p><p><a href="{{ticket_url}}">Open ticket</a></p>', 1),

  ('QA_FAILED_REWORK', 'IN_APP', 'ASSIGNEE,PROJECT_MANAGER', NULL,
   '{{ticket_id}} failed QA and is back with you — iteration {{iteration}}.', 1),
  ('QA_FAILED_REWORK', 'EMAIL', 'ASSIGNEE,PROJECT_MANAGER',
   'QA failed — returned for rework',
   '<p>{{actor}} sent <strong>{{ticket_id}}</strong> back from QA. This is iteration {{iteration}}.</p><p>{{ticket_title}}</p><p>{{comment}}</p><p>Level {{level}} · {{project}} · planned close {{planned_close}}</p><p><a href="{{ticket_url}}">Open ticket</a></p>', 1),

  ('DEPLOYMENT_DONE_VERIFY', 'IN_APP', 'ASSIGNEE', NULL,
   '{{ticket_id}} deployed to production by {{actor}} — please verify.', 1),
  ('DEPLOYMENT_DONE_VERIFY', 'EMAIL', 'ASSIGNEE',
   'Deployed to production — please verify',
   '<p>{{actor}} deployed <strong>{{ticket_id}}</strong>.</p><p>{{ticket_title}}</p><p>{{comment}}</p><p>{{project}} · {{client}} · planned close {{planned_close}}</p><p><a href="{{ticket_url}}">Open ticket</a></p>', 1),

  ('TICKET_REASSIGNED_AWAY', 'IN_APP', 'PREVIOUS_ASSIGNEE', NULL,
   '{{ticket_id}} is no longer yours — {{actor}} reassigned it to {{assignee}}.', 1),

  ('TICKET_REOPENED', 'IN_APP', 'ASSIGNEE,PROJECT_MANAGER', NULL,
   '{{ticket_id}} reopened — cycle {{cycle}}. {{ticket_title}}.', 1),
  ('TICKET_REOPENED', 'EMAIL', 'ASSIGNEE,PROJECT_MANAGER',
   'Reopened — cycle {{cycle}}',
   '<p>{{actor}} reopened <strong>{{ticket_id}}</strong>. It is now on cycle {{cycle}} and assigned to {{assignee}}.</p><p>{{ticket_title}}</p><p>{{comment}}</p><p>Level {{level}} · {{project}} · {{client}} · planned close {{planned_close}}</p><p><a href="{{ticket_url}}">Open ticket</a></p>', 1),

  ('NEW_UNASSIGNED_TICKET', 'IN_APP', 'PROJECT_MANAGER,SUPPORT_DESK', NULL,
   '{{ticket_id}} raised on {{project}} with nobody assigned — {{level}}.', 1),
  ('NEW_UNASSIGNED_TICKET', 'EMAIL', 'PROJECT_MANAGER,SUPPORT_DESK',
   'Raised with nobody assigned — {{level}}',
   '<p><strong>{{ticket_id}}</strong> was raised by {{actor}} on {{project}} and has no assignee.</p><p>{{ticket_title}}</p><p>Level {{level}} · {{client}} · planned close {{planned_close}}</p><p><a href="{{ticket_url}}">Open ticket</a></p>', 1),

-- ── escalations — something is late, failed, or has got worse ────────
  ('SLA_BREACHED', 'IN_APP', 'REPORTING_MANAGER,PROJECT_MANAGER,ASSIGNEE', NULL,
   '{{ticket_id}} is overdue by {{overdue_by}} — assigned to {{assignee}}.', 1),
  ('SLA_BREACHED', 'EMAIL', 'REPORTING_MANAGER,PROJECT_MANAGER,ASSIGNEE',
   'Overdue by {{overdue_by}}',
   '<p><strong>{{ticket_id}}</strong> passed its planned close date of {{planned_close}} and is overdue by {{overdue_by}}.</p><p>{{ticket_title}}</p><p>Level {{level}} · {{project}} · {{client}} · stage {{stage}} · assigned to {{assignee}}</p><p><a href="{{ticket_url}}">Open ticket</a></p>', 1),

  ('SLA_80_PERCENT_ELAPSED', 'IN_APP', 'ASSIGNEE', NULL,
   '{{ticket_id}} has used 80% of its SLA — due {{sla_due}}.', 1),
  ('SLA_80_PERCENT_ELAPSED', 'EMAIL', 'ASSIGNEE',
   'Due {{sla_due}} — 80% of the SLA has elapsed',
   '<p><strong>{{ticket_id}}</strong> has used 80% of its allowed time. It is due {{sla_due}}.</p><p>{{ticket_title}}</p><p>Level {{level}} · {{project}} · stage {{stage}}</p><p><a href="{{ticket_url}}">Open ticket</a></p>', 1),

  ('STAGE_SLA_BREACHED', 'IN_APP', 'STAGE_OWNER,PROJECT_MANAGER,REPORTING_MANAGER', NULL,
   '{{ticket_id}} is stuck in {{stage}} past its stage SLA — {{overdue_by}}.', 1),
  ('STAGE_SLA_BREACHED', 'EMAIL', 'STAGE_OWNER,PROJECT_MANAGER,REPORTING_MANAGER',
   'Stuck in {{stage}} past SLA',
   '<p><strong>{{ticket_id}}</strong> has been in <strong>{{stage}}</strong> for {{overdue_by}} longer than that stage allows.</p><p>{{ticket_title}}</p><p>Level {{level}} · {{project}} · {{client}} · owned by {{assignee}} · planned close {{planned_close}}</p><p><a href="{{ticket_url}}">Open ticket</a></p>', 1),

  ('LEVEL_RAISED_CRITICAL', 'IN_APP', 'ASSIGNEE,REPORTING_MANAGER,PROJECT_MANAGER', NULL,
   '{{ticket_id}} escalated to CRITICAL by {{actor}}.', 1),
  ('LEVEL_RAISED_CRITICAL', 'EMAIL', 'ASSIGNEE,REPORTING_MANAGER,PROJECT_MANAGER',
   'Escalated to CRITICAL',
   '<p>{{actor}} raised <strong>{{ticket_id}}</strong> to <strong>Critical</strong>.</p><p>{{ticket_title}}</p><p>{{comment}}</p><p>{{project}} · {{client}} · stage {{stage}} · assigned to {{assignee}} · planned close {{planned_close}}</p><p><a href="{{ticket_url}}">Open ticket</a></p>', 1),

  ('ITERATION_LIMIT_REACHED', 'IN_APP', 'PROJECT_MANAGER,REPORTING_MANAGER', NULL,
   '{{ticket_id}} has reached iteration {{iteration}} — it keeps coming back.', 1),
  ('ITERATION_LIMIT_REACHED', 'EMAIL', 'PROJECT_MANAGER,REPORTING_MANAGER',
   'Iteration {{iteration}} — repeated rework',
   '<p><strong>{{ticket_id}}</strong> has been through {{iteration}} iterations of the same stage.</p><p>{{ticket_title}}</p><p>Level {{level}} · {{project}} · {{client}} · assigned to {{assignee}} · planned close {{planned_close}}</p><p><a href="{{ticket_url}}">Open ticket</a></p>', 1),

  ('DEPLOYMENT_FAILED', 'IN_APP', 'ASSIGNEE,PROJECT_MANAGER,REPORTING_MANAGER', NULL,
   '{{ticket_id}} failed to deploy — {{actor}} reported it.', 1),
  ('DEPLOYMENT_FAILED', 'EMAIL', 'ASSIGNEE,PROJECT_MANAGER,REPORTING_MANAGER',
   'Deployment failed',
   '<p>{{actor}} reported a failed deployment on <strong>{{ticket_id}}</strong>.</p><p>{{ticket_title}}</p><p>{{comment}}</p><p>Level {{level}} · {{project}} · {{client}} · planned close {{planned_close}}</p><p><a href="{{ticket_url}}">Open ticket</a></p>', 1),

  ('CHAIN_VERIFICATION_FAILED', 'IN_APP', 'ADMIN', NULL,
   'The nightly hash-chain verification failed. {{comment}}', 1),
  ('CHAIN_VERIFICATION_FAILED', 'EMAIL', 'ADMIN',
   'Hash chain verification failed on {{org}}',
   '<p>The nightly verifier could not reproduce the hash chain over the append-only history.</p><p>{{comment}}</p><p>This is the backstop for the case where the immutability triggers have been defeated (PLAN.md §3.5). Investigate before the next run.</p>', 1),

-- ── status requests ─────────────────────────────────────────────────
  ('STATUS_REQUESTED', 'IN_APP', 'ASSIGNEE', NULL,
   '{{actor}} asked for a status update on {{ticket_id}}.', 1),
  ('STATUS_REQUESTED', 'EMAIL', 'ASSIGNEE',
   'Status requested by {{actor}}',
   '<p>{{actor}} asked for a status update on <strong>{{ticket_id}}</strong>.</p><p>{{ticket_title}}</p><p>{{comment}}</p><p>Level {{level}} · {{project}} · stage {{stage}} · planned close {{planned_close}}</p><p><a href="{{ticket_url}}">Reply on the ticket</a></p>', 1),

  ('STATUS_REQUEST_ANSWERED', 'IN_APP', 'REQUESTER', NULL,
   '{{actor}} answered your status request on {{ticket_id}}. {{comment}}', 1),

-- ── mentions ────────────────────────────────────────────────────────
  ('MENTIONED', 'IN_APP', 'MENTIONED_USER', NULL,
   '{{actor}} mentioned you on {{ticket_id}}. {{comment}}', 1),
  ('MENTIONED', 'EMAIL', 'MENTIONED_USER',
   'You were mentioned by {{actor}}',
   '<p>{{actor}} mentioned you on <strong>{{ticket_id}}</strong>.</p><p>{{comment}}</p><p>{{ticket_title}} · {{project}} · stage {{stage}}</p><p><a href="{{ticket_url}}">Open ticket</a></p>', 1),

-- ── everything else ─────────────────────────────────────────────────
  ('TICKET_CLOSED', 'IN_APP', 'REPORTER,WATCHERS', NULL,
   '{{ticket_id}} was resolved and closed by {{actor}}.', 1),
  ('TICKET_CLOSED', 'EMAIL', 'REPORTER,WATCHERS,CLIENT_CONTACT',
   'Resolved and closed',
   '<p>{{actor}} closed <strong>{{ticket_id}}</strong>.</p><p>{{ticket_title}}</p><p>{{comment}}</p><p>{{project}} · {{client}}</p><p><a href="{{ticket_url}}">Open ticket</a></p><p>Replying to this mail adds a comment to the ticket.</p>', 1),

  ('COMMENT_ADDED', 'IN_APP', 'ASSIGNEE,WATCHERS', NULL,
   '{{actor}} commented on {{ticket_id}}. {{comment}}', 1),
  ('COMMENT_ADDED', 'EMAIL', 'ASSIGNEE,WATCHERS',
   'New comment from {{actor}}',
   '<p>{{actor}} commented on <strong>{{ticket_id}}</strong>.</p><p>{{comment}}</p><p>{{ticket_title}} · {{project}} · stage {{stage}}</p><p><a href="{{ticket_url}}">Open ticket</a></p><p>Replying to this mail adds a comment to the ticket.</p>', 1),

  ('COMMENT_MARKED_CLIENT_VISIBLE', 'IN_APP', 'CLIENT_CONTACT', NULL,
   'An update was published on {{ticket_id}}. {{comment}}', 1),
  ('COMMENT_MARKED_CLIENT_VISIBLE', 'EMAIL', 'CLIENT_CONTACT',
   'An update on your ticket',
   '<p>There is a new update on <strong>{{ticket_id}}</strong>.</p><p>{{comment}}</p><p>{{ticket_title}}</p><p>Current stage {{stage}} · planned close {{planned_close}}</p><p><a href="{{ticket_url}}">Open ticket</a></p>', 1),

  ('ATTACHMENT_ADDED', 'IN_APP', 'ASSIGNEE,WATCHERS', NULL,
   '{{actor}} attached a file to {{ticket_id}}.', 1),
  ('ATTACHMENT_ADDED', 'EMAIL', 'ASSIGNEE,WATCHERS',
   'New attachment from {{actor}}',
   '<p>{{actor}} added an attachment to <strong>{{ticket_id}}</strong>.</p><p>{{ticket_title}} · {{project}} · stage {{stage}}</p><p><a href="{{ticket_url}}">Open ticket</a></p>', 1),

  ('PRIORITY_CHANGED', 'IN_APP', 'ASSIGNEE,PROJECT_MANAGER', NULL,
   '{{ticket_id}} is now {{level}} — changed by {{actor}}.', 1),
  ('PRIORITY_CHANGED', 'EMAIL', 'ASSIGNEE,PROJECT_MANAGER',
   'Level changed to {{level}}',
   '<p>{{actor}} changed the level on <strong>{{ticket_id}}</strong> to <strong>{{level}}</strong>.</p><p>{{comment}}</p><p>{{ticket_title}} · {{project}} · {{client}} · planned close {{planned_close}}</p><p><a href="{{ticket_url}}">Open ticket</a></p>', 1),

  ('STALE_TICKET_NUDGE', 'IN_APP', 'ASSIGNEE', NULL,
   'Nobody has touched {{ticket_id}} in a while. It is still yours.', 1),
  ('STALE_TICKET_NUDGE', 'EMAIL', 'ASSIGNEE',
   'No activity on this ticket for a while',
   '<p><strong>{{ticket_id}}</strong> has had no activity recently and is still assigned to you.</p><p>{{ticket_title}}</p><p>Level {{level}} · {{project}} · stage {{stage}} · planned close {{planned_close}}</p><p>Nothing has gone wrong — this is a reminder, and you can switch it off in your notification preferences.</p><p><a href="{{ticket_url}}">Open ticket</a></p>', 1),

  ('MAIL_DELIVERY_FAILED', 'IN_APP', 'ASSIGNEE', NULL,
   'A notification mail about {{ticket_id}} could not be delivered. {{comment}}', 1),

  ('EMAIL_ADDRESS_SUPPRESSED', 'IN_APP', 'ADMIN', NULL,
   'An email address was suppressed by the provider. {{comment}}', 1),
  ('EMAIL_ADDRESS_SUPPRESSED', 'EMAIL', 'ADMIN',
   'An email address has been suppressed',
   '<p>The mail provider told us to stop writing to an address, so it has been suppressed.</p><p>{{comment}}</p><p>Until it is corrected, everything addressed there will be dropped rather than queued.</p>', 1),

  ('DAILY_DIGEST', 'EMAIL', 'ALL_USERS',
   'Your open tickets',
   '<p>Good morning {{recipient}}.</p><p>Here is where your open tickets stand this morning.</p><p>{{comment}}</p><p>You can switch this digest off in your notification preferences.</p>', 1),

  ('WEEKLY_MANAGER_SUMMARY', 'EMAIL', 'REPORTING_MANAGER,PROJECT_MANAGER',
   'Team summary',
   '<p>Good morning {{recipient}}.</p><p>Here is how your team''s week looks across {{org}}.</p><p>{{comment}}</p><p>You can switch this summary off in your notification preferences.</p>', 1);
