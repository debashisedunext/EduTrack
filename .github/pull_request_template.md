## What changed

<!-- One or two sentences. What does this do, and why. -->

## Stream

<!-- platform | masters | tickets | engines -->

## Task IDs

<!-- e.g. A-034, C-051 — from docs/streams/STREAM-*.md -->

## Paths touched

- [ ] Only my stream's owned paths (docs/TEAM-PLAN.md §6)
- [ ] If not — who signed off:

## Migration

- [ ] None
- [ ] Added `V<YYYYMMDD>_<HHMM>__<name>.sql`
- [ ] No already-applied migration was edited

## Checklist

- [ ] Works against the real backend, not only mocks
- [ ] Unit tests for logic; integration tests for new endpoints
- [ ] New routes have permission-matrix entries for **all six roles**
- [ ] OpenAPI spec updated and client regenerated (if endpoints changed)
- [ ] Storybook entry for any new shared component
- [ ] No append-only table gained an update or delete path
- [ ] No new lint or compiler warnings
- [ ] Rebased on current `develop`, CI green

---

<sub>Target branch must be `develop`. Do not merge this yourself — Claude integrates.</sub>
