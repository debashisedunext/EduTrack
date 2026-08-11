# S-18 My Tasks — C-018, plus S-21 Quick Update — C-036/C-037

The developer's home screen. Route `/tickets` has a sibling at `/my-tasks`,
wired in `App.tsx`, already linked from `Sidebar.tsx`'s nav.

| File | What it is |
|---|---|
| `MyTasksPage.tsx` | The screen. Fetches, owns the List/Kanban toggle, renders the summary line. |
| `groupTickets.ts` | Pure bucketing — Overdue / Due Today / This Week / Later. |
| `MyTasksGroupedList.tsx` | The List view — grouped card rows. |
| `MyTasksKanbanBoard.tsx` | The Kanban view — status columns, drag and an accessible "Move to" per card. |
| `taskCardHelpers.tsx` | Small render helpers (`dueDateLabel`, `isOverdue`, `reworkBadge`) shared by both views. |
| `../stageDisplay.ts` | `titleCase(stageCode)` — shared with `quick-update/`, so it lives one level up rather than inside this folder. |
| `../quick-update/QuickUpdatePanel.tsx` | S-21's slide-over and its ⚡ trigger — see below. Not inside this folder because "opens from any list row" means the ticket list (and eventually the detail page) need it too. |

## Hard-scoped to `assigneeId = me`, explicitly — not just inherited from the row-scope guard

`scopedTickets` in the mock (mirroring the real `ScopeResolver`) already
narrows a Developer's `GET /tickets` to their own assignments by default. But
PM and Admin's default scope is *every project* or *everything* — for them,
"my tasks" has to mean their own assignments specifically, the same
distinction C-015's "My Open" saved view already draws. So `MyTasksPage`
always sends `assigneeId` and `excludeClosed=true` itself rather than relying
on whatever the caller's role happens to default to.

## Grouping is by calendar day against `plannedCloseDate`, not the `isDelayed` flag

`groupTickets.ts` compares **local calendar days**, not exact timestamps — a
ticket due at 14:00 today is "Due Today" all day, not "Overdue" from 14:01.
`columns.tsx`'s `rowCueClassName` and the list's PCD-column ⚠ use the precise
`isPast()` comparison instead, because that one is answering "has the SLA
clock breached", a different question from "which day should this be worked
today". A revised ETA from Quick Update overwrites `plannedCloseDate`
server-side, so a ticket's group reshuffles the moment the list refetches —
no separate reconciliation needed, both read the same field.

**Group order is Overdue → Due Today → This Week → Later** — the prototype's
own ordering (`docs/prototype/index.html`, the `GRP` array behind S-18), most
urgent first, even though the blueprint's prose happens to list them "Due
Today / Overdue / This Week / Later".

A ticket with no `plannedCloseDate` — should not happen once C-012's SLA
computation has run, but possible for data that predates it — lands in Later
rather than being silently dropped from the screen.

## One fetch, no pager

A developer cannot be the assignee of thousands of open tickets, so this
fetches one page of 100 rather than building the same cursor-pager the
ticket list needs — the prototype's own S-18 screen never draws one either.
If `meta.hasMore` comes back true anyway, a visible note links out to
`/tickets?assigneeId=…&excludeClosed=true` rather than silently truncating.

## Kanban: the "Move to" select is the accessible path, drag is the fast one

Native HTML5 drag-and-drop has no keyboard equivalent — a screen reader user
or keyboard-only user cannot reach it, and CLAUDE.md's accessibility rule
(WCAG AA, keyboard navigation) is not optional. Every card also carries a
"Move to" `<Select>` that fires the identical status-change mutation, fully
keyboard-operable, and each column is `role="group"` with an
`aria-label`. Drag is layered on top for a mouse and is not the only way to
move a card.

Columns are every `StatusCode` except `CLOSED` — this page already fetches
with `excludeClosed: true`, so a Closed column would only ever be empty.
Changing a card's status is the same `quick-update` endpoint S-21 uses with
just `{ status }`, not a workflow-ribbon stage transition — the golden rule
("only the current stage owner may advance a ticket") governs the ribbon's
stage field, a different one from `status`, which S-21's own Status dropdown
already lets the assignee change freely.

No optimistic UI on a move: the card's row dims (`aria-busy`) while the
mutation is in flight and the board re-renders once the invalidated list
query refetches, same as every other mutation in this codebase (create,
quick update) waits for the server round trip rather than patching the cache
by hand.

## Quick Update (S-21) — built here, lives in `../quick-update/`

`QuickUpdateTrigger` bundles the ⚡ button and its slide-over as one
component specifically so every consumer — this screen, the ticket list
later, the detail page eventually — drops in one component instead of wiring
open state itself.

Fields: Status, Log Effort (hours + date), Work note, % Complete, Revised ETA
with a reason that becomes required the moment a date is entered
(`quickUpdateForm.ts`'s `superRefine`). Matches blueprint's S-21 wireframe and
the contract's `QuickUpdateRequest` doc comment on what must **not** be
editable here: ticket ID, reported by, assigned by, date reported, cycle
history, the ribbon, prior effort logs, level (unless PM — no field to hang
that exception on yet) and project.

**📎 Attach is deliberately not a field.** C-023 is what builds the
attachment picker every upload surface shares, and quick update is explicitly
one of C-023's listed surfaces — wiring `attachmentIds` before that picker
exists would mean building a second, throwaway one just for this panel. Same
kind of deferral C-014's README already made for the compact ribbon column.

**The idempotency key is minted once per open panel**, in a ref, before the
first submit attempt — never inside the mutation function, which TanStack
Query re-invokes on retry. A key regenerated per attempt defends against
nothing; the whole point is that a retried request after a network timeout
must not double-log the effort hours it carries. `useQuickUpdateMutation`
hand-writes the call for the same reason `createTicketMutation.ts` does:
orval drops header parameters, so the generated `useQuickUpdateTicket` has no
way to carry `Idempotency-Key`.

Every field is send-if-touched — an unfilled effort field must not log a
zero-hour entry, and an unmoved % complete slider must not overwrite the
ticket's actual progress with whatever the slider happened to default to.
`quickUpdateForm.ts`'s `toQuickUpdateRequest` has the full rule.

## Testing notes

- Radix `Select`'s listbox portals to `document.body`, not into whatever
  dialog it opened from — `within(dialog).getByRole('option', …)` will never
  find it. Query the option from the top-level `screen`, same as the ticket
  list's own filter-dropdown tests already do.
- The List/Kanban toggle persists to `localStorage`, which jsdom keeps alive
  across every test in one file. `MyTasksPage.test.tsx` clears it in
  `afterEach` so the Kanban test does not leak its choice into whichever test
  runs after it.
