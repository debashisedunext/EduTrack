# S-19 Create Ticket — C-010 · C-013

All five blueprint §7.5 field groups — **Identity · Core · People · Effort ·
Extra** — and its four actions.

| File | What it is |
|---|---|
| `CreateTicketPage.tsx` | The screen. Route `/tickets/new`. |
| `ticketForm.ts` | Form state, per-action validation, and the mapping onto `TicketCreateRequest`. |
| `createTicketMutation.ts` | `POST /tickets` carrying an `Idempotency-Key`. |
| `FormField.tsx` · `LevelPicker.tsx` · `WatcherPicker.tsx` | Feature-local controls. Not shared — see below. |

## The four actions — C-013

**Cancel · Save as Draft · Save & Create Another · Save & Assign**, in that
order, with Save & Assign as the primary and as what Enter does.

**A draft relaxes exactly three rules, and which three is a contract fact
rather than a preference.** `TicketCreateRequest.required` is
`[projectId, title, taskTypeId, level]`, so a draft missing any of those earns a
400 whatever the form allows. What it *can* waive is the three rules that are
the blueprint's rather than the contract's: the mandatory **description**
(§7.5), the mandatory **estimated effort** (§7.5), and **§4B.2's client rule for
client-facing task types** — you often park a ticket precisely because you are
still chasing which client it belongs to. Level pre-fills from the task type, so
a draft costs project + task type + title.

A draft still rejects a *malformed* entry: `4h` in the effort field fails on
every path. A draft is permission to leave a field empty, not permission to
store something that will have to be fixed later.

**Validation is chosen per attempt, not per mount.** The resolver reads a ref
that the clicked button sets, and the ref falls back to `assign` once the
attempt settles — so blur validation, and the revalidate-on-change that follows
a failed submit, keep measuring against the primary action. A draft attempt must
not leave the form permanently lenient, and `applies the draft rules per attempt
rather than latching them on` is the test that says so.

**Blank optional fields are omitted, never sent empty.** Already true of
`plannedCloseDate`; the draft path makes it matter for two more.
`estimatedHrs: 0` is a genuine zero-hour estimate and `description: ''` a
genuine empty description — both are different claims from "not filled in yet",
and both would survive into the finished ticket.

**Save & Create Another rotates the idempotency key, and that is the sharpest
bug in this task.** It is the only path that reaches the form a second time
without a remount. Carrying the key over would make the second save replay the
first response — the server returns the original for 24 hours — so the user
would be shown the first ticket's ID again and the second ticket would never
exist. The MSW mock does not implement replay, so nothing but an assertion that
the two keys *differ* catches it; `mints a fresh idempotency key for the second
ticket of a batch` is that assertion.

It keeps what a batch shares — project, client, contact, task type, level,
assignee, watchers — and clears what describes one ticket. Client-raised is
not named here because C-022 made it derived rather than carried state; see
below. Then
it moves focus back to the title, which has to happen in an effect rather than
inline: `reset` drops React Hook Form's field-ref registry and lets the inputs
re-register as they render, so a `setFocus` in the same tick looks up a field
that is momentarily absent and silently does nothing.

**It confirms in the action bar, not with a toast — and that is not a style
preference.** The shared toast viewport is `fixed bottom-0 right-0 z-[100]`,
which is exactly where this screen's primary action sits, so a success toast
physically covers Save & Assign. Every other path navigates away before that
matters. Save & Create Another is the one that leaves the user here and expects
them to press a button again, so a toast there blocks the very action it is
congratulating them for — clicks land on the toast and nothing happens. Caught
by driving real Chrome, not by the test suite: jsdom has no layout, so no
amount of unit testing would have found it.

> **Worth knowing for the other streams.** This is not specific to this screen.
> Any screen that pairs a fixed bottom action bar with right-aligned buttons
> will collide with the toast viewport. Confirming inline is the local fix;
> moving the viewport would change every screen in all four streams, so it is
> not something to do from one feature branch. If a second screen hits it, that
> is the point to raise moving the viewport as a shared change.

**The action bar's hint is `min-w-0 flex-1`, and that is load-bearing.** Two
buttons fitted the bar; four plus a hint do not. Without the shrink the hint
holds its full width and pushes the primary action onto a second row, off the
bar entirely — also caught only in a real browser. The hint is what wraps when
the bar runs out of room; the button group is `shrink-0` and never does.

**Save & Assign does not require an assignee.** §7.5 does not mark Assigned To
with an asterisk and C-015 ships an *Unassigned* saved view, so an unassigned
ticket is a supported outcome, not an oversight — making it mandatory here would
contradict a screen we are about to build. The label still implies otherwise, so
the action bar states which way it will go: the assignee's name, or that it
saves unassigned and shows in the Unassigned view.

## Decisions worth knowing about

**Validation bounds come out of the generated Zod, not a copy.** `ticketForm.ts`
imports `createTicketBodyTitleMin/Max` and `createTicketBodyDescriptionMax` from
`api/generated/zod`, and `ticketForm.test.ts` parses the mapper's output against
the generated `createTicketBody`. The form and the contract cannot drift apart
without a test failing. This is the same guard C-011 put on `TicketCode`.

**`plannedCloseDate` is omitted, never nulled, when the user leaves it blank.**
Omitting it is the contract's signal to compute it from the SLA policy against
the working calendar. An explicit `null` means "this ticket has no planned close
date", which silently takes it out of every delay calculation. The override
input is only offered to PM and Admin, matching the contract; the server is what
actually enforces that.

**The description is required here although the contract has it optional.**
Blueprint §7.5 marks it mandatory and the blueprint wins on behaviour.

**Level is a chip radio group, not a dropdown.** §7.5 draws a colour-chip
dropdown. With four options a radio group shows all four chips at once — which
is the information the colour carries — and stays operable from the keyboard
with no popup. Which levels exist, and their order, still come from the priority
master. The chips use the frozen `level-*` tokens rather than the hex the master
returns, because §12.1 owns those colours and the token variants are the pair
that passes AA at chip text size.

**Level pre-fills from the task type's default until the user picks one.** After
that, changing the task type does not overwrite their choice.

**One idempotency key per logical ticket.** Minted on mount, rotated only once a
ticket actually exists. A key regenerated per click defends against nothing — the
resubmit-after-timeout case is exactly the one it is for, and without it that
resubmit allocates a second ID the sequence never gives back. This is why the
page does not use the generated `useCreateTicket`: orval drops header
parameters, so the generated function cannot send the header at all.

**`components/ui/searchable-dropdown.tsx` gained four optional props** — `id`,
`aria-labelledby`, `aria-describedby`, `aria-invalid` — forwarded to the trigger.
Purely additive; every existing call site renders identically. Without `id` the
control reaches a screen reader unnamed, which fails AA. Documented in Storybook
as `LabelledWithError`.

**The watcher multi-select is feature-local.** It is the only multi-select in the
product so far, and anything in `components/ui/` becomes a contract the other
three streams depend on. Promote it when a second caller appears.

**Client-raised is derived, not a field — C-022, §4B.2.** "When the client is
set and the reporter is a client contact, the ticket is marked client-raised"
is not a row in §7.5's own S-19 table (Ticket ID and Assigned By are the same
kind of absence, for the same reason), so the Extra group's checkbox this task
started as was a stand-in that let the wrong thing drift onto the wire: a user
could mark a ticket client-raised with no client on it at all, or leave a
genuinely client-raised ticket unmarked. `toCreateRequest` now sends
`clientId != null && clientContactId != null` — `clientContactId` already
means "the individual who reported it", so its presence alongside a client
*is* the rule, not a proxy for it. The mock recomputes the same way rather
than trusting `body.isClientRaised`, the same trust boundary C-012 draws for
the SLA preview: a value the browser could derive right most of the time and
be silently wrong the rest is one the server should not have to take on
faith. Drives the client-wise reports and the CSAT survey per §4B.2 — neither
built yet, both Stream A/D's. The third half of §4B.2's sentence, the
client-visible default on comments, is a **recorded deviation**: `CommentBox`
(C-031) defaults every comment to internal regardless of this flag, per §16's
later recommendation — see its own doc comment, not repeated here.

## Deliberately not here

| Not built | Owner |
|---|---|
| Opening comment, and the rich-text editor the description will share with it | C-029 |

C-012 (the inline planned-close-date preview), C-021 (client dependent
dropdowns, inline "+ Add contact" and the §4B.2 auto-fills) and C-023/C-024
(attachments, clipboard paste) have since landed and are covered below and in
the folder's other notes. The description uses the shared rich-text editor
since C-066; C-029 still wants a rich-text comment box, which is why the
control lives in `components/ui/` rather than here.

## C-021 · the client dependent dropdowns and the §4B.2 auto-fills

The client and client-contact dropdowns themselves, their type-ahead and the
"filtered to this project" rule all landed with C-010/B-028/B-029 — see the
`clientId`/`clientContactId` fields above. What C-021 adds is the rest of
§4B.2's Client group:

- **"Show all clients"**, Admin/PM only (`canViewAllClients`) — the toggle
  beside the Client hint. It drops `projectId` from the `listClients` query
  rather than adding a second list; the client is still shown-and-refused by
  the same `newTicketBlockReason` gate either way, exactly as an in-project
  client is.
- **Inline "+ Add contact"**, Admin only (`canAddContact`) — reuses Stream
  B's `ContactEditorDialog` (`features/clients/`) rather than a second copy of
  the same form; see "Reused, not rebuilt" below for why that dialog has no
  way to hand a fresh id back and how this screen finds it anyway.
- **Auto-fill: the account manager as a watcher.** `Client.accountManager` is
  added to `watcherIds` when a real client change is detected — the same
  `previousClientId` transition that already clears the contact. It is a
  starting point, not a rule: `setValue`, not a chip the user cannot remove,
  and Save & Create Another's retained client does not re-add a manager
  removed earlier in the same batch, because the ref only fires on an actual
  change. `WatcherPicker`'s candidate list is widened to include the manager
  even when they are not a project member — an account manager's job is
  client visibility, not project staffing, and the picker can only render a
  selected id it can find.
- **Auto-fill: the client's time zone for due-date display.** `SlaPreview`
  gained an optional `clientTimezone` prop; when it differs from the viewer's
  own resolved zone, the preview adds a second line with the same instant
  read in the client's zone. Shown only in the resolved-date state — there is
  nothing to convert in the warning or error states.

### Reused, not rebuilt — `ContactEditorDialog`

S-33's Contacts tab already has the row editor `POST /clients/{id}/contacts`
needs (`features/clients/ContactEditorDialog.tsx`, `contactForm.ts`,
`contactQueries.ts`), built for B-027. This screen imports it rather than
writing a second copy of the same fields and validation — the same crossing
`newTicketBlockReason` already makes into `features/clients/` two fields
above, both flagged the same way this one is.

**The dialog has no callback for "here is the contact you just created."** It
reports success only by invalidating the `useListClientContacts` query this
screen already reads (`contactQueries.ts`'s `invalidate`), which is enough
for the dropdown to refresh but not enough to auto-select what a support
agent just typed. Widening the dialog with an `onCreated` prop would be a
small, additive change, but it is Stream B's file and this task does not have
their sign-off to make it. So the new contact is found the way the dialog's
own refetch already proves it exists: `priorContactIdsRef` snapshots which
contact ids exist right before the dialog opens, and an effect watches
`contacts` for the first id outside that snapshot once `awaitingNewContactRef`
is set. **The button that opens the dialog is disabled until this client's
contacts have loaded at least once** (`contactsLoaded`) — without that, a
click issued before the first fetch resolves snapshots an empty prior-ids
set, and the effect reads the client's *existing* first contact as "the one
that was just added." Caught by `CreateTicketPage.test.tsx`, not by
inspection — the race does not reproduce on a warm cache, only against a
client picked moments earlier.

### 🔴 Open for Stream B — `createClientContact` is `master.write`, which is Admin alone

§4B.2's line for this control is "so the support desk never has to leave the
form," and the contract's own description of the endpoint says the same:
"callable inline from the ticket form. Without that, the desk picks the wrong
existing contact rather than taking the detour to the client master." But
`ClientController.addContact` is `@PreAuthorize("hasAuthority('master.write')")`,
and `master.write` is granted to `ADMIN` alone
(`V20260806_0900__seed_roles_permissions.sql`). Support Desk — the role the
sentence is about — cannot call it.

`canAddContact` in this file matches the server rather than the blueprint's
sentence: the button and its dialog are Admin-only, and every other role sees
the hint "A new one takes an Admin, from the Client Master" instead of a
button that would 403. Widening `master.write`, or carving out a narrower
"add a contact" capability, is Stream B's and Stream A's call — this task
flags the gap rather than closing over it with a frontend-only role check
that the server would still refuse.

### 🔴 Open for Stream A / D — the client's default SLA policy does not reach the preview

§4B.2 also asks selecting a client to pre-fill "the default SLA policy," and
`Client.slaPolicyId` exists on the wire. But `previewPlannedCloseDate`'s
resolution ladder (`PROJECT_TASK_TYPE → PROJECT_LEVEL → ORG_DEFAULT →
PRIORITY_DEFAULT → TASK_TYPE_DEFAULT`, `sla/README.md`) has no rung for a
client's own policy, the endpoint takes no `clientId` or `slaPolicyId`
parameter, and `TicketCreateRequest` still carries no `slaPolicyId` for a
created ticket to record which one it used — the same gap `sla/README.md`
already flags for C-038 and the breach report.

Not built here, deliberately, rather than approximated: the SLA date is a
server round trip precisely because it depends on the working calendar and
leave the browser cannot see (see `sla/README.md`), so a client-side "apply
the client's policy" would be a second, disagreeing implementation of the
same resolution — exactly what that file argues against. Closing this needs
a rung on the resolution ladder or a way to pass `slaPolicyId` through, which
is Stream A/D's contract to extend.

## 🔴 Open for Stream D — a draft is not yet distinguishable from a ticket

`saveAsDraft` is on `TicketCreateRequest`, but **`StatusCode` has no `DRAFT`**
and `TicketResponse` carries no draft flag — so the field is write-only with no
observable effect. Once saved, a draft is indistinguishable from a live ticket:
it appears in every list and saved view, is handed a planned close date and an
SLA clock it will breach, and notifies whoever is in Assigned To. D-004's mock
encodes the gap exactly, at `mocks/handlers/tickets.ts`:

```ts
status: body.saveAsDraft ? 'NEW' : 'NEW',
```

The form sends the right request today and will need no change. What is missing
is server-side, and it is three things: somewhere to record draft-ness
(a `DRAFT` status or an `isDraft` on `TicketResponse`); suppression of the
assignee notification, the email and the SLA clock while it is one; and
exclusion from the default list views. Until then, "Save as Draft" is honest
about the request and optimistic about what the server does with it.

Second, smaller: `taskTypeId` and `level` are in `TicketCreateRequest.required`,
so a draft cannot be saved from a title alone. That is a reasonable floor and
the form is built to it — but if §7.5's Save as Draft is meant to accept less,
those two need to become optional when `saveAsDraft` is true.

## The rest of C-013's save chain is server-side

§7.5's "on save → ID generated → 🔔 to assignee → history entry `CREATED` →
email" is one line of spec across three owners. ID generation is C-011 and
landed. The `CREATED` history row belongs to the ticket-create service, which
does not exist yet — `backend/api/…/feature/tickets/` holds only the code
generator — and the notification and the email are Stream D's engines. The MSW
mock already inserts the `CREATED` row, so the frontend is exercised against the
intended behaviour; none of the three is implementable from this folder.

## Open for Stream D — `contracts/openapi.yaml`

`TicketCreateRequest` has no field for four things §7.5 asks for, so they are not
rendered rather than rendered and silently dropped:

- **Sub-type / category** — the optional second level under task type.
- **Environment** — Prod / UAT / Dev. §7.5 calls it "critical for bug triage".
- **Linked tickets** — blocks / is blocked by / duplicate of / relates to.
- **Tags** — free-form labels.

Two more are rendered read-only at their server default, which is right for the
common path but blocks the override §7.5 allows:

- **Date reported** — defaults to now. Backdating is Admin/PM only, and there is
  no field to backdate through.
- **Reported by** — defaults to the current user. A Support Desk agent raising a
  ticket on a client contact's behalf cannot say so.

Also for Stream D, smaller:

- `GET /clients?projectId=` is in the contract and the form sends it, but D-004's
  mock ignores it and returns every client. Against the real API the list is
  filtered; in `npm run dev` it looks like it is not.
- §7.5 caps the title at 200 characters, the contract at 300. The form uses the
  contract's, since refusing at 200 would reject input the API accepts.

## Open for Stream B — Task Type master

§4B.2 calls the "requires a client" rule *configurable per task type*, but
`TaskType` carries no `requiresClient` flag. `CLIENT_REQUIRING_TASK_TYPES`
matches on the master's name instead, so a rename in the Task Type master
silently disables the rule. `ticketForm.test.ts` has a test that spells out that
failure mode. Move it onto a flag when one exists.

## C-068 · the "Where it happened" group

§7.5's fourth field group — Module, Screen name, Feature, Steps to generate —
sits between Core and People because that is where §7.5's own field table puts
it, not inside Extra.

**Module is mandatory for bug-type task types only**, and that is the whole
argument of the group. §7.5 states both halves: "a Production Bug without a
module is a bug nobody can route", and "a change request may genuinely span
three modules, and forcing a choice there just teaches people to pick the first
item in the list, which poisons the very reporting the field exists for." Save
as Draft waives it either way, the same shape the description rule already has.

Three decisions in `ticketForm.ts` are worth knowing about:

- **The bug-type rule matches on `code`, not on `name`.** `CLIENT_REQUIRING_TASK_TYPES`
  directly above it matches display strings and carries an apology for it — a
  rename in the Task Type master silently disables that rule, and there is a
  test pinning that failure mode. `TaskType.code` is documented in the contract
  as immutable once created, so the module rule survives an admin renaming
  "Production Bug" in S-13. The three codes are **listed**, not derived from a
  `_BUG` suffix: a suffix test captures whatever a future admin happens to type,
  and a validation rule should change when somebody decides it changes.
- **Server, Network, Browser and Performance issues are deliberately not bug
  types.** Under-applying the rule costs a blank column; over-applying it costs
  invented data in the one field the feature was requested for.
- **Module is not carried into the next ticket by Save & Create Another**,
  unlike task type and level beside it. A module pre-filled from the last ticket
  makes accepting-the-default the path of least resistance on exactly the field
  §7.5 warns about, on the one screen where a stale value is least likely to be
  noticed. The cost is one dropdown per bug in a batch, and
  `CreateTicketPage.test.tsx` pays it in the open.

**The picker filters inactive modules out; the endpoint does not.**
`GET /masters/modules` returns retired rows on purpose (D-060) — a grid still
has to render the name of a module some old ticket was raised against — but
offering one here would be offering a 400, since `ModuleGuard` refuses an
inactive module on write. `Transport` is the fixture's retired row and there is
a test for its absence.

Steps to generate takes the same `RichTextEditor` the description does, with the
same `Controller` binding (`register()` cannot bind a contentEditable) and the
same sanitising on the way out — §3.9 applies to whatever the client handles,
and a rule that covers one of its two rich-text fields is the bug this stream
has already fixed twice.
