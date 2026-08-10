# feature/masters

**Owner: Stream B · Ayush**

Resource, role, project, task type, priority, calendar, notification templates. Screens S-07…S-16.

## What is here

| Task | Files | Screen |
|---|---|---|
| **B-023** Working calendar | `Calendar*` | S-14 |
| **B-024** Working-hours service | `domain.masters.WorkingHoursService` | — |

### B-023 · the working calendar

`CalendarController` serves four resources that an Admin sees as one screen and
B-024's working-hours service reads as one question — is this instant working
time?

- `GET /masters/holidays` — the read model B-024 consumes: holidays for a window
  **plus** the working week, in one response, so the two cannot disagree by the
  width of a concurrent edit.
- `POST|PATCH|DELETE /masters/holidays[/{id}]` — the holiday master.
- `GET|PUT /masters/working-calendar` — the weekly-off pattern and working-day
  bounds. A singleton resource, guarded by `ETag`/`If-Match`.
- `GET|POST|PATCH|DELETE /masters/leaves[/{id}]` — per-resource leave.

**Days are ISO-8601 — Mon=1 … Sun=7**, which is exactly
`DayOfWeek.getValue()`. Nothing in this package does arithmetic on a day number;
`CalendarMapper` converts through `DayOfWeek.of(int)` and that is the only
crossing point.

**Nothing here computes durations.** That is `domain.masters.WorkingHoursService`
(B-024), deliberately: CLAUDE.md's rule is that every SLA, duration and
utilisation figure routes through one implementation, and a second one growing
here because it was convenient is how four answers to the same question appear.
It lives in `domain`, not here, because Stream D's SLA scanner (`worker`) and
Stream C's transition service both need to call it and only `domain` is on
every stream's classpath.

`CalendarMapper` is the first mapper on B-006's shared `BaseMapperConfig`. Its
javadoc records why it overrides `collectionMappingStrategy` and why that
override was **not** promoted to the shared config.
