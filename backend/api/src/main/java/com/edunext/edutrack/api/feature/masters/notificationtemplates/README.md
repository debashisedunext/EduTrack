# `masters/notificationtemplates` — the Notification Template Master (S-15, B-022)

| File | What it is |
|---|---|
| `NotificationTemplateController` | Five operations at `/api/v1/masters/notification-templates`. No `DELETE`. |
| `NotificationTemplateDtos` | The wire types, and the Bean Validation that actually enforces the contract's `maxLength`. |
| `NotificationTemplateService` | The five rules the schema does not encode. |
| `NotificationTemplateExceptionHandler` | RFC 9457 problem documents, scoped to this controller. |

The vocabularies live one module down, in `domain/notifications/` — `MergeTag`
and `NotificationRecipient`, both added by B-022 into Stream D's package and
both flagged for Debashis's sign-off. They have to be there: `api` validates
what an Admin types and `worker` substitutes it when D-010 renders, and neither
module can see the other. A copy in each is a catalogue that drifts, and it
drifts in the direction where the screen accepts a tag the renderer leaves as
literal braces in a client-facing mail.

## Nothing reached this table until B-022

`notification_templates` was created by A-007, the entity and repository landed
with B-005, and `email_log.template_id` has pointed at it since
`V20260805_1530`. There was **no controller, no contract operation and no caller
anywhere in the codebase**, and the table held no rows.

That is a different shape from the five gaps `MasterRoutesTest` documents —
B-023's nine calendar operations, B-014's status patch, B-018's two SLA
operations, B-020's `listTaskTypes` and B-021's `listPriorities` were all
declared and mocked and never mounted. This one was modelled and never reached.
The outcome was the same: every mail the system sends today builds its subject
in Java and enqueues with `templateId = null`.

`V20260815_1100` seeds one row per (event, channel) pair blueprint §11 ticks,
for every event `NotificationEvent` declares, so the screen opens onto content
and D-010 has something to render.

## The channel vocabulary is `IN_APP | EMAIL | PUSH`

A-007's column comment predicted `POPUP | BELL | EMAIL`. It was written before
Stream D built the notification centre, and it is superseded.

Everything that runs keys on `NotificationChannel`: `notification_preferences`
rows, `OutboxEnqueuer`'s mandatory-mail check, `push_subscriptions`. S-15's own
line in blueprint §7.4 reads "channel (in-app / email / push)" — the same three.
A template stored as `POPUP` would never be found by a renderer looking up
`IN_APP`, and the failure is silent: the lookup misses, the send falls back to a
hand-built string, and nobody discovers that the master an Admin has been
editing was never read.

**The bell is not a channel** — D-042's javadoc already argues why. It counts
what was written, and a preference that emptied it would be hiding the record
rather than quieting a delivery. So §11's popup and bell columns are one
template: `notifications.title` and `notifications.body` are what the bell shows
and what the toast shows, and they are the same two strings. An event ticked
bell-only in §11 still has an `IN_APP` template; what makes it bell-only is
D-043's popup rule.

Neither `channel` nor `recipients` has a `CHECK` constraint, deliberately. B-016
pinned `ck_projects_status` because blueprint S-10 fixes those three values;
these lists are Stream D's and grow with their producers, so a `CHECK` here would
mean D cannot add a channel without a migration in Stream B's directory. The
vocabulary is closed in `NotificationTemplateService` instead — the one write
path that reaches the column.

## Recipients are positions, not roles

Blueprint §4B.6 asks for a "per-role recipient list" and the backlog line repeats
the phrase. Taken literally it produces the wrong table.

Of the ten things §11's "To" column names, two look like role codes. The other
eight are resolved per send from the ticket: `ASSIGNEE` is a column on `tickets`,
`WATCHERS` a join table, `MENTIONED_USER` comes out of the comment that fired the
event, and `CLIENT_CONTACT` is not a platform user at all — which is why
`email_log.to_user_id` is nullable. A join onto `roles` could carry two of the
ten, and the eight that did not fit would need somewhere second to live.

Even the two that share a spelling with a role are joins: `PROJECT_MANAGER` means
the PM *of this ticket's project*, `REPORTING_MANAGER` the manager *of this
ticket's assignee*. Mailing everybody holding the PM role on a breach would
notify every project's manager about one project's ticket.

## The rule this screen exists in order not to break

"Per-event on/off" is in the backlog line and in §4B.6's own description of the
master. Taken unqualified it hands an Admin **one click that silences, org-wide,
the mails D-036 spent a whole method making unmutable per-user**.

So `guardMandatory` refuses `isActive: false` on an `EMAIL` template whose event
is an assignment, an escalation or a status request — exactly
`NotificationEvent.isMandatoryMail()`, stated over the category rather than over
a list of codes so that an escalation event added next month is covered the
moment it is declared. Creating one already switched off is refused the same way,
before the insert rather than after it.

**Mail only.** §7.7 calls mail "the guaranteed channel". An in-app toast reaches
only somebody who is logged in and a push only a browser that is still
subscribed, so neither was ever what made an assignment impossible to miss. An
Admin who wants a quieter interface switches the `IN_APP` template off and the
mail still goes.

**The recipient list is deliberately not guarded.** Removing `ASSIGNEE` from
`TICKET_ASSIGNED`'s mail would silence it as effectively as the toggle, and is
still permitted: §11's "To" column is a sensible default rather than a law, and
an organisation routing assignment mail through a shared desk address is doing
something legitimate that a frozen list would forbid. The list must be non-empty;
what it contains is the Admin's call. One is a switch whose only meaning is
"off"; the other is a configuration whose meaning is the whole feature.
`NotificationTemplateServiceTest` asserts the hole, so removing it later is a
decision rather than an accident.

## Merge tags are validated, because nothing else would catch a typo

§4B.6 says templates are "editable by an Admin without a code release". That
sentence is the point of the screen and also the hazard: an Admin who types
`{{ticketId}}` has written something that is not a tag, the save succeeds, the
mail goes out with `{{ticketId}}` printed in it, to a client, and the first
person to notice is the client.

An unknown tag is a `400` naming the ones that exist. The check runs over the
whole body rather than over a picker, because somebody pasting wording from a
document will not have used the picker. `NotificationTemplateMasterIT` runs the
same check across all fifty seeded rows — the migration is data, so nothing else
would.

## There is no delete

The same call B-020 made on a task type and B-021 on a level, for a sharper
reason than either. Deleting a template does not orphan a reference — it removes
the *wording* for an event that goes on firing. The producer keeps raising
`HANDOFF_RECEIVED`; there is simply nothing to render it with, and the failure
appears as a mail that does not arrive rather than as an error anybody sees.

Switching off is `isActive: false`, and on the mails §4B.6 marks never-optional,
not even that.

## Every read is Admin, which no other master in this stream does

Task types, levels, roles and the calendar open their reads to all six roles on
an argument from §2 row 3: every role may raise a ticket, a ticket must carry a
level and a type, so a role that could not read those masters could not raise a
ticket at all.

Nothing on a screen a non-Admin sees is built from this one. And the content is
not neutral — the seeded rows include the mail sent to a **client contact**, the
escalation naming the Reporting Manager as a recipient, and A-044's
chain-verification alarm that goes to Admins. §2 gives the audit log to Admin
alone on that reasoning; a catalogue of who gets told what, when something goes
wrong, belongs on the same side of the line.

There is no §2 row that says so, so this is reasoned rather than read off —
flagged as such, the way B-018 and B-021 flagged theirs, and recorded in
`check-conventions.py`'s `ROWLESS_403` with the reason a 403 leaks nothing here.

## What is not in this task

Rendering. D-010's worker sends what it is handed; wiring `templateId` into the
enqueue path, resolving `NotificationRecipient` to addresses (D-040) and
substituting `MergeTag` are all Stream D's. B-022 ships the master, the
vocabulary and the storage that work will read.
