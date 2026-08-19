# Ticket detail — S-20

**C-019 · Detail shell + summary panel.** The shell, the header, the tab strip
and the right-hand summary panel with every entity linked. The ribbon and the
contents of the six tabs each belong to their own task and are not here.

| File | What it is |
|---|---|
| `TicketDetailPage.tsx` | The shell — one aggregated fetch, URL-held cycle and tab, loading/404 states, layout. |
| `TicketDetailHeader.tsx` | ID, level, status, delay and cycle chips, title, and the action bar driven by `availableActions`. |
| `TicketSummaryPanel.tsx` | S-20's right rail. Read-only apart from the Level row (C-020). |
| `TicketLevelControl.tsx` | C-020 · §4B.1's level editor — colour chips, the PCD preview, the conditional reason. |
| `levelChange.ts` | C-020 · who may change it, when the reason is mandatory, and where the SLA clock starts. |
| `TicketDetailTabs.tsx` | The tab strip. APG keyboard pattern, `?tab=` in the URL. |
| `ticketSummary.ts` | The panel's arithmetic, pure and clock-injected. |
| `entityLinks.ts` | Every destination path, in one place. |
| `PendingSection.tsx` | A named "built by task X" region, so an unbuilt tab does not read as a broken one. |

---

## Decisions

### One call, not seven

`GET /tickets/{id}/full` returns ticket, cycles, ribbon, history, effort,
comments, attachments, watchers and `availableActions` together, and the
contract says why: *"the waterfall is what makes the page feel slow, not the
payload size"*. Two masters lookups sit beside it — `taskTypeId` and
`clientContactId` arrive as bare IDs — and nothing else on this page fetches.
When the tabs are built they render from this payload; a tab that fires its own
request re-introduces exactly the waterfall the endpoint exists to remove.

### Cycle and tab live in the URL, not in component state

Same rule C-014 set for the list, for the same reason: a filtered, cycle-scoped
view is a link a manager pastes into chat. `?cycle=` is not a client-side
filter — it re-fetches, because an earlier cycle is a **different, sealed
journey**, not a subset of this one. `?tab=` replaces rather than pushes, so
arrowing across six tabs does not bury the ticket list six entries deep in the
back button.

### The cycle link carries `cycle` **and** `tab`

`cycleEffortPath` sets both. A link that only set `tab=effort` would show the
*current* cycle's hours under an earlier cycle's label — the same cross-cycle
contamination PLAN.md §3.4 corrects in the roll-up query, arriving through the
front door instead. `ticketSummary.totalEffortHrs` guards the mirror image:
totals are summed from `cycles[]`, never from the payload's `effortLogs`, which
are cycle-scoped and would make the grand total *shrink* when a reader selected
cycle 1. Both failure modes have a test.

### Destinations live in `entityLinks.ts`, and their routes are declared from it

Three of the five link targets are other people's screens: the project
dashboard and the resource 360 (A-069 / S-28) are Stream A's, the client 360 is
Stream B's. `App.tsx` registers `PROJECT_ROUTE`, `CLIENT_ROUTE` and
`RESOURCE_ROUTE` — the same constants the links are built from, so a builder
and its route cannot drift — against `ScreenPlaceholder`. A link therefore
lands on a named "not built yet" screen rather than the catch-all **Not found**,
which reads as a broken link. Each owner replaces one `element`.

### The action bar renders what the server allows, and nothing else

`availableActions` is resolved server-side *"so the client renders buttons from
this rather than re-deriving permissions — two implementations of the same rule
always diverge"*. So the header maps server action codes to buttons and no
other condition adds one.

`handoff`, `rework` and `skip-stage` arrive in that list and are deliberately
**not** header buttons: §4A puts them on the ribbon's current segment (C-052),
and a stage action reachable from two places is two code paths to keep in
agreement. `priority` is inline in the panel (C-020). `comment`, `effort` and
`attach` are surfaces, not buttons.

`Close` and `Reopen` render **disabled with a title naming their task**. Hiding
them would misreport the caller's permissions — the server just said they have
them — and enabling them would post nothing. Quick Update is live: C-018 built
the S-21 panel, and this page reuses `QuickUpdateTrigger` unchanged.

### 404 is the only "you cannot see this" answer

Out-of-scope IDs return 404, not 403, so nothing leaks a ticket's existence.
The error state says *"No ticket X is available to you"* and never mentions
permission; `TicketDetailPage.test.tsx` asserts the absence of the words
*permission*, *access*, *not allowed* and *forbidden*, so a well-meaning copy
edit cannot reintroduce the leak.

### Tabs are hand-rolled

`@radix-ui/react-tabs` is not a dependency and there is no
`components/ui/tabs.tsx`. Adding a package for a control this page expresses in
forty lines would put a lockfile change into a frontend-only PR. **If a second
screen needs tabs, that is the moment to promote this into `components/ui` with
a Storybook entry** — that promotion is what makes a component shared, and it
is an additive change, so it needs no sign-off from the other streams.

No Storybook entry for anything in this folder: every component here is
detail-page-specific (the panel takes a `Ticket`, the header takes
`availableActions`), the same exemption `SavedViewsMenu`, `ColumnChooserMenu`
and `DensityToggle` carry.

### `AvatarStack` is hidden from assistive tech here

`AvatarStack` hardcodes `role="group" aria-label="Watchers"` — it was built for
§12.3's watcher row. Used for a single assignee it announces them as
"Watchers", so it is wrapped in `aria-hidden`; the name beside it is a link and
already carries the information. Fixing the label properly means a new prop on
a component all four streams consume. That is additive and worth doing, but on
its own task rather than inside this one.

### C-020 · the Level row is the only editable field, and the dialog is why

§4B.1 asks for "editable directly on the detail page from the summary panel —
one click, no full-page edit mode", and the first draft was a popover over the
chip that committed on selection. It was wrong for a reason specific to this
field: **the change has two required parts.** Picking Critical is half the act;
saying why is the other half on any assigned ticket, and §4B.1 also insists the
recomputed date is shown *before the user commits*. A control that commits on
selection can do neither — it would have to write first and ask afterwards, or
grow a second step, at which point it is a dialog that opened sideways.

One click on the chip still opens it, no page mode changes, and nothing else on
the panel becomes editable. **A second editable row should be argued for on its
own terms rather than inherited from this one.**

### C-020 · the preview measures from the cycle's start, not from now

`usePlannedCloseDate` defaults `from` to now, which is right on the create form
— a ticket being raised starts its clock now. An existing ticket's clock started
when it was **reported**, so `slaClockStart` passes the current cycle's start
date (falling back to `createdAt`) and `PriorityChangeService.slaClockStart` is
the server's copy of the same function.

The consequence is deliberate: **escalating a three-day-old ticket to a
four-hour level produces a planned close date in the past, and the dialog shows
it.** That is not a rendering bug to be clamped to now — it is the user being
told, before they commit, that the ticket they are about to call Critical is
already late. Measuring from now would have the dialog and the row it writes
disagree by however long the ticket has been open, and only the dialog would be
visible.

### C-020 · no Storybook entry, and where the prefill actually lives

`TicketLevelControl` is S-20-specific — it hardcodes §4B.1's rules, reads a
`Ticket`, and calls `changeTicketPriority` — so it is not a shared-library
control and carries the same exemption as `SavedViewsMenu`, `ColumnChooserMenu`
and `DensityToggle`. The reusable half of it *is* in Storybook already:
`LevelPicker` draws the colour chips and is imported from `create/` rather than
redrawn here.

C-020's backlog line also asks for the level to be **"pre-filled from the task
type default"**, which is the *create form*, not this panel — a ticket that
already exists has a level, and re-deriving one from its task type would
overwrite a decision somebody made. `CreateTicketPage` has done it since C-011
and `CreateTicketPage.test.tsx` pins both halves: the prefill, and that changing
the task type afterwards must not overwrite a level the user picked themselves.

### ⚠ C-020 · the excluded roles get no button, not a disabled one

§4B.1 gives Developer, QA and Deployment a *request* path — "which raises a
notification to the PM" — and that path does not exist: it is Stream D's. So the
Level row renders as the plain chip C-019 drew, with no affordance at all. A
disabled "Request a change" button would be an invitation with a refusal
attached, and a live one that silently did nothing would be worse.

The same absence covers a sealed earlier cycle. The write would land on the
*current* cycle — the server has no notion of "change the level as it was in
cycle 1" — so offering it there would be offering the wrong act under the right
label.

---

## Open for other streams

### ⚠ Stream A / C-038 — `{ticketId}` is a code, and two backend routes take a `long`

Found by C-020. The contract's `TicketId` is `type: string` matching
`^[A-Z][A-Z0-9]{1,9}-\d{2}-\d{5,}$`, this page's URL is `/tickets/CRM-26-00347`,
and the generated client sends exactly that — but
`TicketDetailController.full` (A-052) and `ReopenController.reopen` (C-038) both
declare `@PathVariable long ticketId`. **Against the real backend that is a 400
on page load**, hidden so far because both have only run against D-004's mock,
which routes on the string.

`PATCH /tickets/{ticketId}/priority` follows the contract and is the first caller
of `ScopedTickets.requireByCode` (unused since A-035). The other two are raised
rather than changed from this branch. Full argument in
`backend/api/.../feature/tickets/README.md`.

### ⚠ Stream D — `contracts/openapi.yaml`: two S-20 fields have nowhere to come from

- **Linked tickets.** *"linked ticket → that ticket"* is in C-019's own backlog
  line and in the S-20 wireframe, and `Ticket` has no field for it — the same
  gap C-010 flagged on the create form. The row is **not rendered** rather than
  rendered as a permanent em dash, because a dash reads as "this ticket has no
  links" when the truth is "the API cannot say". C-064 (ticket linking) needs
  the same field.
- **Assigned by.** In the wireframe, absent from `Ticket`. Derived instead from
  the most recent history entry that changed `assigneeId`, which is exactly
  right for a reassignment and `null` for a ticket still with its original
  assignee — the `CREATED` row does not record an assignment separately from
  the creation. It falls back to an em dash rather than guessing at
  `reportedBy`, which is a different person for anything the desk raises on a
  client's behalf. A real `assignedBy` on `Ticket` would make the row exact.

### ⚠ Stream D — `frontend/src/mocks/`: watchers were accepted and dropped

`TicketCreateRequest.watcherIds` and `TicketPatchRequest.watcherIds` have
existed since D-001, and `TicketDetailResponse.data.watchers` returns them — but
`POST /tickets` never stored the field and `/full` answered `watchers: []`
unconditionally. C-010's watcher picker therefore looked wired end to end and
silently was not, and the summary panel's Watchers row had nothing to render.

Fixed here, in the same spirit as C-015's `dueFrom`/`dueTo` fix: `Ticket` in
`mocks/db.ts` gains `watcherIds`, `POST /tickets` keeps what it is sent, the
`PATCH` handler already assigns it, and `/full` maps it through `userRef`. The
walkthrough ticket is seeded with Meera and Anil, matching the wireframe's
*"Watchers  Meera, Anil"*. Held on the ticket rather than in a join table
because that is all the mock needs to round-trip the contract.

### ⚠ For everyone — the mock's richest ticket is invisible to the default user

`CRM-26-00347` is the §14 walkthrough fixture and Stream C's exit criterion, and
it is assigned to **Meera**. The mock's default signed-in user is **Ravi**, a
Developer, whose row scope is `assigned_to = me` — so under `npm run dev` the
ticket 404s, and the three seeded notifications that deep-link to it all land on
"Ticket not found". That is the scope guard working correctly on data that was
not built for it. `TicketDetailPage.test.tsx` sets `currentUserId` to the Admin
where it needs the fixture, and deliberately does not where it is asserting the
404. Untouched here because changing either the fixture's assignee or the
default user would move ground under every other stream's tests.

---

## Not in this task

| | Task |
|---|---|
| The ribbon, its segments and its cycle selector | C-051, C-053 |
| Journey roll-up · History · Comments · Attachments · Effort | C-055, C-059, C-029, C-060, C-061 |
| Ticket chat tab | Stream D |
| Close / Reopen dialogs behind the disabled buttons | C-040, C-039 |

---

## Also changed outside this folder

- `App.tsx` — `/tickets/:ticketId` now renders the real page;
  `app/TicketDetailPlaceholder.tsx` deleted; three placeholder routes added
  (above).
- `App.test.tsx` — the `/me` assertion waited on a real MSW round trip with
  `waitFor`'s **default 1000 ms**, the only network-waiting assertion in the
  repo that did not pass `4000`. It passes alone every time and fails
  intermittently in a full run, on `develop`, without any change from this
  branch. Raised to 4000 to match every other such assertion.
- `mocks/db.ts`, `mocks/handlers/tickets.ts` — the watcher fix (above).

### ⚠ Stream D sign-off — `features/notifications/useNotificationStream.test.tsx`

Both failures above were red on a clean `develop` with this branch stashed, so
neither is a regression from C-019. The second one is **not** a timeout, though
it looks exactly like one, and it is worth writing down because the obvious fix
makes it worse rather than better.

`resetDb()` re-seeds three `TICKET_HANDED_OFF` notifications for the mock user
with no `deliveredAt`, so every `renderStream()` fetches them from
`/notifications/pending` and pops them as toasts. Only the D-046 block drained
that queue, in its own `beforeEach`. Every other block inherited it.

`Dismiss › closes the toast without marking it read` dismisses the pushed toast
and then asserts that *nothing* is showing — which held only while the replay
had not landed yet. Under a full run's CPU contention it lands inside the
window, and the toast left on screen is `"CRM-26-00347 handed to you at
Development"`: a seeded row, not the one under test. So the failure reads as
"dismiss did not close the toast" and sends the reader into the dismiss
handler, which is innocent. **Raising the timeout does not help** — it waits
longer for a toast that is correctly there. That was tried first: at 4000 ms it
failed three runs out of three, having previously failed at 1000 ms, which is
what showed the diagnosis was wrong.

Fixed by moving the drain to the file-level `beforeEach`: every block wants it,
not just D-046's, and each D-046 test still adds exactly what it is about via
`queue()` afterwards. Full suite now green three runs in a row.

Stream D's file, changed without prior sign-off at the developer's direction —
raising it here and in the PR body rather than doing it quietly.

## C-069 · the four §7.5 fields, inline-editable

Blueprint line 1083 places them exactly, and the placement is the design:
Module, Screen and Feature sit in the summary panel **directly under Type** —
the four answers to "what is this and where", read together — while **Steps to
Generate renders below the description**, where the person about to reproduce
the bug is already looking. It is the one of the four that is not a
label-and-value, so it is a section rather than a rail row.

**There is no role gate, and that is not an omission.** "Inline-editable by the
roles that may edit the description" resolves to every role: `PATCH /tickets/{id}`
is `ticket.update_progress`, which all six hold — `PermissionMatrix` carries the
rows C-067 added, taken off blueprint §2. This is the same position
`TicketLinksControl` is in one row below and it takes the same shape. Row scope
still gates everything, server-side: a ticket outside the caller's scope 404s on
the read and this page never renders.

**A sealed cycle does not disable these**, unlike the comment box, the attachment
strip and `TicketLevelControl`. A level is a fact about the current cycle's SLA
clock, so editing one while reading cycle 1 performs the right act under the
wrong label. `module_id`, `screen_name`, `feature` and `steps_to_generate` are
ticket columns with no `cycle_no` — one value, shown by every cycle. Where a bug
happened does not become a different fact when the ticket reopens.
`TicketLinksControl` makes this argument first; this is the same one.

Two rules in `whereItHappened.ts` are worth knowing:

- **The Module editor offers the active rows plus this ticket's own retired
  one.** The create form filters retired modules out flatly and is right to.
  Editing is the case that rule cannot cover: a ticket already on `Transport`,
  opened to fix a typo in the screen name, would find its Module trigger empty
  and saving would look like it had dropped the module. Off, never onto.
- **Empty is `null`, and a no-op is not sent.** `''` would store a second,
  indistinguishable blank beside `NULL`. And `ticket_history` cannot take a row
  back once written, so the client refusing to send an unchanged field is the
  cheaper half of the guarantee `TicketWriteService` also enforces.

### ⚠ No `If-Match` on this PATCH, at either end

CONVENTIONS.md asks a `PATCH` to carry `If-Match` so a lost update is a 412
rather than a silent overwrite, and the contract declares the 412. Neither end
implements it: orval drops header parameters (the gap C-010 found on
`useCreateTicket` and C-064 on the link create), and `TicketWriteController` does
not read the header. The exposure is two people editing the same short field
inside one another's page load, and the loser's value is **recoverable** — every
change writes a `FIELD_CHANGED` row carrying the old value. Raised rather than
worked around with a hand-rolled header only one of the two ends would honour.

### Fixed on the way — the mock PATCH wrote no history

`http.patch('/tickets/:ticketId')` in `frontend/src/mocks/handlers/tickets.ts`
did `Object.assign` and nothing else, so the History tab stayed empty after an
inline edit and a client could be built against a screen production does not
produce. It now writes one `FIELD_CHANGED` row per genuinely changed field, the
same rule `TicketWriteService.patch` follows. Same class of gap C-020 recorded on
the priority handler; `frontend/src/mocks/` is Stream D's, so this one was mine
to fix rather than flag.
