# S-14 Working Calendar & Holiday Master — B-023

Three sections, because S-14 is three things feeding one answer: the working
week, org holidays, and per-resource leave. **Every SLA, duration and
utilisation figure in EduTrack is computed against what this screen sets.**

| File | What it is |
|---|---|
| `WorkingCalendarPage.tsx` | The screen. Route `/masters/calendar`. |
| `workingWeek.ts` | The day model, the JS↔ISO conversion, and form validation. |
| `WeeklyOffPicker.tsx` | The seven day toggles. Feature-local — see below. |
| `calendarQueries.ts` | Queries and mutations, including the two the generated client cannot express. |

## Decisions worth knowing about

**Days are ISO-8601 everywhere: Mon=1 … Sun=7.** This is the single most
important thing about this feature. The API, the database and
`DayOfWeek.getValue()` all agree on it; `Date.getDay()` does not — it is
Sunday-zero-based, so the two schemes agree on nothing except Saturday.

`isoDayOf()` in `workingWeek.ts` is the **only** place this app converts, and it
should stay that way. A conversion repeated per component does not fix the
mismatch, it distributes it. The contract previously described the field as
"ISO" while constraining it to `0–6`, and the mock sent `[0, 6]`; under that
reading a backend using `DayOfWeek` treats Sunday as a working day, and every
SLA spanning a weekend is quietly short by a day.

**Validation bounds come from the generated Zod, not a copy.**
`workingWeek.ts` imports `updateWorkingWeekBodyWeeklyOffMax` and
`updateWorkingWeekBodyWeeklyOffItemMax`, the same guard C-010 put on the ticket
form. The screen and the contract cannot drift apart without a test failing.

**The working week is saved with `If-Match`, and a 412 is spelled out.** Two
admins editing from two tabs is not exotic for a master screen, and silently
discarding one of them changes every SLA computed afterwards. `useWorkingWeek`
caches the `ETag` *with* the data deliberately: fetching it fresh at submit time
would send back a value the user never saw, which defeats the point. On 412 the
screen names the conflict and says to reload and reapply, rather than showing a
generic failure.

**Two calls are hand-written, for the reasons C-010 documented.** `http()`
returns a parsed body and drops the response, so the working-week `ETag` needs a
plain `fetch`; and orval omits header parameters, so `Idempotency-Key` on the
creates needs the same treatment `createTicketMutation.ts` gives it. Both can go
the day orval emits header params and a response-aware mutator.

**`WeeklyOffPicker` is feature-local, not shared.** `components/ui/` is Stream
C's and additive-only, and a seven-day toggle group has exactly one caller. It
is a `role="group"` of `aria-pressed` buttons rather than checkboxes — the
question is "which days are off", and seven labelled buttons answer it at a
glance. `aria-pressed` carries the state for a screen reader, since colour is
otherwise the only thing distinguishing on from off.

**A recurring holiday is stored once, not once per year.** The working-hours
service expands it. The form says so, because the natural thing for an admin to
do next January is add it again — which the server answers with a 409.

## Not here

- **Expanding recurring holidays across years** is B-024's job. This screen
  shows the master rows as stored.
- **Per-resource weekly-off** is a field on the resource record (S-08 / B-011),
  a narrower override than the org pattern this screen owns.
- **Project-specific holidays** are supported by the API (`projectId`) but the
  screen only adds org-wide ones so far; the list renders whatever the server
  returns.
- **Leave approval workflow.** The API carries `status`, and only `APPROVED`
  stops the SLA clock. An admin recording leave here is not filing a request, so
  it defaults to approved.
