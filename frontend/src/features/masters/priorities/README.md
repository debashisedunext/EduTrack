# `features/masters/priorities` — S-12, the Priority / Level Master (B-021)

| File | What it is |
|---|---|
| `PriorityListPage.tsx` | The grid, plus a create and an edit dialog. One route, `/masters/priorities`. |
| `priorityForm.ts` | Form state, validation and the two mappers. |
| `priorityQueries.ts` | The data layer — the two hand-written parts orval cannot emit. |

One page and no detail route, because a level is six fields. B-020 gave S-11
this shape and predicted this screen would take it; it does.

## The grid asks for retired levels. The ticket screens do not.

This is the one place S-12 reads its master differently from S-11 and B-064,
both of which hand retired rows to every caller and tell pickers to filter.

The reason is that the two existing consumers of `listPriorities` **cannot**
filter. `CreateTicketPage` drops `isActive === false` task types before building
its type picker and then maps priorities straight into `LevelPicker` without a
filter, because until B-021 the endpoint could not return a retired one. Both
files are Stream C's.

So `GET /masters/priorities` is active-only by default and takes
`?includeInactive=true`, and the two lists are kept apart **in the cache by
their query keys** — `getListPrioritiesQueryKey({ includeInactive: true })` here
against the bare `getListPrioritiesQueryKey()` there. Sharing one key would put
retired levels into the create form the moment an admin opened this screen: a
cache entry written by one screen and read, unfiltered, by another.

When Stream C adds the filter, the default can widen and this section is the
note explaining why it was ever narrow.

## Three counts on the row, and they are not three shades of one number

Retiring is the consequential act here and its blast radius is invisible from
the row. Unlike the task type master's single `ticketCount`, each of these is a
different consequence and only one of them refuses:

- **Tickets** — keep the level and go on rendering it. Never blocks.
- **SLA rows** — survive the retire but stop resolving, because the level's
  whole column leaves every project's SLA matrix. Never blocks.
- **Task types** — **blocks.** `TaskTypeService` refuses a retired level as a
  `defaultLevel`, so retiring would leave those types unsaveable on their own
  screen.

The retire button is disabled, with the reason stated, rather than enabled into
a 409 the admin then has to interpret.

## The escalation control is one-way, on purpose

Exactly one active level is the SLA engine's escalation target (§6). The server
refuses a clear, because clearing the last flag leaves auto-escalation with
nowhere to promote an overdue ticket.

A checkbox whose only outcome is a 409 should not be operable. So the level that
holds the flag renders it **checked and disabled**, with the sentence that says
how to move it; every other level renders it as an ordinary checkbox that moves
the target here and clears the incumbent server-side.

`toPatchRequest` is the half that makes an "off" unreachable rather than merely
discouraged: it omits `autoEscalates` entirely when false. Sending `false` on
every save would make an ordinary rename of the flagged level carry a clear the
server refuses — so renaming Critical would 409.

## The colour is not the colour the ticket chip uses

The palette leads with §12.1's four **level chips** — `#10B981`, `#3B82F6`,
`#F59E0B`, `#EF4444` — because those are the colours the blueprint states for
exactly these four rows, and what B-002 seeds. **The MSW mock disagreed with
them until B-021**, returning `#84CC16 / #F59E0B / #9A3412 / #BE185D`. Nothing
caught it, because `LevelPicker` renders the frozen `level-*` design tokens
rather than the hex the master returns: the wrong values reached a screen and
were never displayed.

That is also why the colour field carries a note. What an admin picks here is
used by this grid and the Priority Split chart. The ticket chip keeps its token
— §12.1 owns those, and they are the pairs that pass AA at chip text size.

Free-text hex is not offered, per CLAUDE.md: the server only checks the shape,
because it has no palette; the palette lives here, where the choosing happens.

## "New level" is disabled, and the page says why

S-12 promises an Admin can add further levels without a release. The contract's
`Level` is a closed four-value enum typing seven ticket and SLA schemas, so a
fifth would serialise into a response the generated client rejects.

The page states that in a sentence and disables the button, rather than
presenting a form that always fails. The button is not removed: a retired level
leaves its code free, and that is the case the create dialog exists for.

## What is hand-written and why

The same two things every master screen here has had to hand-write, for the
reasons `roleQueries.ts` and `calendarQueries.ts` give: orval omits header
parameters, so `Idempotency-Key` is hand-set, and `http()` drops the response
object, so the `ETag` the `PATCH` needs as `If-Match` comes off a plain `fetch`.
Delete both the day orval emits header params and a response-aware mutator.

## Invalidation reaches further than this screen

A save invalidates `/projects` as well as both priority lists. Every project's
SLA matrix is a task-type × **level** grid built from the active levels — so
retiring one, reordering one or changing one's default hours changes a column,
its position, or every cell that falls through to rung 4. Leaving it stale would
show an SLA grid with a column for a level that has just gone.
