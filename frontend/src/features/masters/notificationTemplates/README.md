# `features/masters/notificationTemplates` — S-15, the Notification Template Master (B-022)

| File | What it is |
|---|---|
| `NotificationTemplateListPage.tsx` | The grid, plus a create and an edit dialog. One route, `/masters/notification-templates`. |
| `templateForm.ts` | Form state, validation, the merge-tag scanner and the two mappers. |
| `templateQueries.ts` | The data layer — the two hand-written parts orval cannot emit. |

One page and no detail route, because a template is six fields. B-020 gave S-11
this shape, B-021 repeated it for S-12, and it holds here.

## The grid groups by event, which the other masters do not

Every other master in this stream is a flat list of rows. This one is a section
per event with a small table of channels inside it, because the unit an Admin
thinks in is the event: *what does a handoff say, and where does it go*. A flat
list of fifty rows makes the in-app and email wording of one event two unrelated
entries, and the question people actually arrive with — is this event noisy —
becomes a scan.

The server already returns `(eventCode, channel)` order, so the grouping
preserves a sort rather than doing one.

## The lock is stated on the row, not discovered at the save

Blueprint §4B.6 marks assignment, handoff, escalation and status-request mail as
**never optional**, and the server refuses to switch one off with a `409`.

`isMandatory` comes back on every row, derived server-side from the event's
category, so the toggle for those templates renders as a **checked, disabled
statement** with the reason beside it — and the grid says "Always on" rather than
"On", which is deliberately not the same word: "On" invites a click that would be
refused. A control whose only outcome is a `409` is a control that should not be
operable. B-021 made the same call on the escalation flag.

`templateFormErrors` deliberately does **not** refuse `isActive: false`. Putting
the rule there would mean the form and the page each held a copy of it, and the
page's copy is the one a user meets.

## The merge-tag catalogue is offered, not only validated

`unknownMergeTags` mirrors `MergeTag.unknownIn` exactly, whitespace tolerance
included — `{{ ticket_id }}` is what a paste from a specification document
produces, and a client that refused it while the server accepted it (or the
reverse) would be a validator arguing with a renderer.

It runs while the Admin types, so the underline appears before the save rather
than after it. The tag palette inserts **at the caret**: appending would put
`{{ticket_id}}` after the closing `</p>` of a body somebody was editing the
middle of, which is a helper that makes more work than it saves.

The catalogue comes from
`GET /masters/notification-templates/vocabulary` rather than from a constant
here, so the enum is the single source of truth for *which* values exist —
`templateForm.ts` only supplies the wording. A code with no label renders as
itself; a screen that silently dropped a recipient the server had just added
would be worse than one showing `SUPPORT_DESK` in capitals.

While that read is in flight the scanner reports nothing unknown. A validator
that is wrong in the strict direction is worse than one that is briefly quiet —
refusing every tag in the body because a second request has not landed would
block a save that is perfectly correct.

## Client-facing wording is flagged before the dialog opens

`CLIENT_CONTACT` is the one recipient that reaches outside the organisation, and
it is called out twice: as a warning-tinted chip on the row, and as the edit
button's own label. Knowing that a body is read by a customer is the difference
between a reword and an incident.

## Two round trips per edit, on purpose

The dialog re-reads the template rather than seeding from the grid row, because
that read is what carries the `ETag`. Editing values the tag does not cover would
make the precondition a formality — the same reasoning `calendarQueries.ts`,
`roleQueries.ts`, `taskTypeQueries.ts` and `priorityQueries.ts` all record.

The vocabulary read is cached with `staleTime: Infinity`, because those are enum
values: they change when the server is redeployed and at no other time. The
template list is deliberately **not** given the same treatment — a template is
edited by people, and two admins on one screen is exactly the case `If-Match`
exists for.

## A save here reaches no further than this screen

Worth stating, because every other master in this stream's save reaches somewhere
else — retiring a level changes a column on every project's SLA matrix, retiring
a task type changes what a project offers.

Rewording a template changes what a mail says, and nothing in this application
reads a template. The renderer that will is Stream D's worker, in another
process, holding no React Query cache to invalidate. So `invalidate()` touches
the list and the edited row, and nothing else.

## Hints sit outside their `<label>`

On the subject and body fields, the hint and error spans are siblings of the
label rather than children of it. A wrapping label contributes its whole text
content to the control's accessible name, so a hint inside one makes a screen
reader announce three sentences of guidance every time focus lands on the box —
and that guidance is already reachable through `aria-describedby`, which is
announced separately and at the right moment. The compact fields above keep the
plain wrapper form, because their hints live outside already.
