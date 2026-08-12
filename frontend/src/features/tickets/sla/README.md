# features/tickets/sla

**C-012 · SLA resolution and the inline planned-close-date preview.**

Blueprint §4B.1: *"Resolves the SLA policy → recomputes the **Planned Close Date**
and shows the new date inline before the user commits."* §7.5 marks Planned Close
Date as *"Auto-computed from SLA policy, editable with reason"*.

| File | What it is |
|---|---|
| `usePlannedCloseDate.ts` | Gating around the generated `usePreviewPlannedCloseDate` |
| `SlaPreview.tsx` | The inline notice — presentational, no fetching |

Its own folder rather than `create/` because C-020 needs it too: the priority
dropdown on the detail page recomputes and previews the same date on change, and
S-22's reopen dialog asks for a new one. A copy in each screen is three
resolutions that drift.

## The preview is a server round trip, and has to be

The date depends on the SLA matrix, the org working week, org and project
holidays, and the assignee's approved leave. Computing it in the browser would be
a second implementation of `WorkingHoursService` — the exact thing that class
exists to prevent — and one that *cannot* agree with the first, because the
browser cannot see leave for a resource the user has no permission to read.

The failure that matters is not that it would be wrong. It is that it would be
wrong **quietly**: the number shown before saving and the number stored on save
would differ by a weekend, and only one of the two is ever on screen.

`GET`, no side effects, no request body. Safe to fire on every change to project,
task type, level or assignee, and cacheable per parameter set. It deliberately
does not take the ticket body — previewing must not require a valid draft, and
§4B.1's rule is that the date recomputes *while the form is still being filled in*.

## Decisions

**Not debounced.** Every input is a picker, so changes arrive one click at a time,
not one keystroke at a time; there is nothing to coalesce. TanStack Query dedupes
and caches per parameter set, so flicking between two levels re-reads the second
pick from cache.

**`from` is omitted rather than passed as `new Date()`.** A fresh instant every
render would mint a new query key and refetch forever. The server defaults it to
now, which is what "a ticket raised right now" means. The parameter exists at all
because §7.5 lets a PM or Admin backdate Date Reported, and C-020 will pass the
ticket's own reported date rather than today's.

**The assignee is an input.** Their approved leave stops the SLA clock, so the
date moves when the assignee changes. That is worth the extra fetch: promising a
date against someone who is away all next week is the kind of commitment that
reaches a client.

**Three distinct states, because each is a different fact.** A date, with its
target in working hours and which rung produced it, so the number is checkable.
*No SLA at all* — a warning, not a blank, because `plannedCloseDate: null` takes
the ticket out of the breach sweep, the delayed KPI and Due Today, and an empty
field reads as "not computed yet". *Could not be reached* — muted and explicitly
non-blocking; the server computes the real date on save either way.

**`aria-live="polite"`, not assertive.** This region updates on every priority
change, and an assertive one would interrupt a screen-reader user mid-field.

**The source is rendered as prose, not as the enum.** `ORG_DEFAULT` on a form
tells a support agent nothing. The wording names which master to go and edit if
the number looks wrong, which is the only action any of these labels can lead to.

**No Storybook entry.** `SlaPreview` is ticket-specific — it hardcodes SLA
wording and takes the hook's state shape — not a shared-library control. Same
exemption `SavedViewsMenu` and `ColumnChooserMenu` carry, and for the same
reason: Storybook is the contract for `components/ui/`, and putting screen
components in it makes that contract mean less.

## The date the user sees is local; the date on the wire is UTC

`SlaPreview` formats through `date-fns` with no explicit zone, so it renders in
the reader's timezone — CLAUDE.md's rule that storage is UTC and the presentation
layer is where a timezone is applied. `SlaPreview.test.tsx` formats its expected
string the same way rather than hardcoding `12:30`, which would pass only on a
machine running in UTC and fail on the team's own for a screen behaving exactly
as specified.

`Use the computed date` builds its `datetime-local` value from **local**
components. `toISOString().slice(0, 16)` would hand the input a UTC wall-clock
reading and shift the date the user sees by their offset — 05:00 IST on the wire
becoming 23:30 the previous day in the box.

## Where else this landed

- **`create/CreateTicketPage.tsx`** — the preview sits below both date fields in
  the Effort group, full width, because it is about the pair of them: for
  everyone it explains the date the server will store, and for a PM who has typed
  one it is the alternative they are choosing against.
- **`mocks/` — Stream D's files, changed with a flag.** `db.ts` gains a real
  `slaPolicies` table (the endpoint used to derive the matrix from
  `taskTypes.defaultSlaHrs`, which gave every level identical hours — a preview
  that never moves when the level changes is exactly the feature failing);
  `handlers/sla.ts` is new and holds the resolution and the calendar walk;
  `handlers/tickets.ts`'s create path now uses that same walk instead of
  `now + defaultSlaHrs` in wall-clock milliseconds; `handlers/rest.ts`'s
  `sla-policies` matrix resolves each cell rather than deriving it.

## Open for Stream D

- ⚠ **`contracts/openapi.yaml`** — `previewPlannedCloseDate` and its three schemas
  were added in-session, additive only, `check-conventions.py` clean. Same
  precedent as C-011 and C-015.
- ⚠ **The mock's working window is read as UTC**, not as `calendar.week.timezone`
  (`Asia/Kolkata`). `util.ts`'s `workingMinutes` already does this and the seeded
  §14 walkthrough hours were built against that convention — two mock helpers
  disagreeing by 5.5 hours would break the fixture the ribbon and the Journey
  roll-up are judged on. The real service uses the calendar's zone. A mock
  artefact, recorded rather than fixed, because fixing it means re-deriving the
  walkthrough fixture.
- **`TicketCreateRequest` still carries no `slaPolicyId`**, so the ticket does not
  record which policy it was created under. The contract's own note on
  `replaceSlaPolicies` says "tickets carry the policy they were created under",
  and today nothing does. Not blocking C-012 — the preview is stateless — but
  C-038's reopen and the breach report both want it.
