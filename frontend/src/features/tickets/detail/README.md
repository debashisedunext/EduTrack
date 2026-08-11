# Ticket detail — S-20

**C-019 · Detail shell + summary panel.** The shell, the header, the tab strip
and the right-hand summary panel with every entity linked. The ribbon and the
contents of the six tabs each belong to their own task and are not here.

| File | What it is |
|---|---|
| `TicketDetailPage.tsx` | The shell — one aggregated fetch, URL-held cycle and tab, loading/404 states, layout. |
| `TicketDetailHeader.tsx` | ID, level, status, delay and cycle chips, title, and the action bar driven by `availableActions`. |
| `TicketSummaryPanel.tsx` | S-20's right rail. Read-only in this task. |
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

---

## Open for other streams

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
| Inline priority editing in the panel | C-020 |
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
