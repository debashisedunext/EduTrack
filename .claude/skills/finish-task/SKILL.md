---
name: finish-task
description: Close out an EduTrack task properly — run the preflight checks, run the unit and smoke tests covering what you touched, write a commit subject that says the right thing to the plan tracker, rebase, and open a draft PR for CI. Invoke when a task looks finished, when asked to commit, push, wrap up or open a PR. Do not merge; Claude integrates via the integrate skill.
---

# Finish a task

The work being done is not the same as the work being finished. This is the
second half.

## Step 1 — preflight

```bash
.claude/skills/finish-task/preflight.sh D      # your stream letter
```

A second, and it catches the things that are expensive precisely because they
are cheap to get wrong. `✗` must be fixed. `?` is a judgement call — answer it,
do not skip past it.

## Step 2 — the commit subject, which is a status report

**A task ID in a commit subject means that commit finishes the task.** The plan
reads status from git and cannot tell "the commit that mentions D-045" from
"D-045 is done".

```
feat(engines): D-045 browser push with VAPID and subscription pruning   ← finished
feat(engines): add the push dispatcher                                  ← partial
                                                                          (name D-045 in the BODY)
```

This has gone wrong four times. D-045 and D-053 both read as done on their first
commit and needed correcting in `docs/plan/overrides.json`. A-010 and A-013
landed under a subject reading `A011` — no hyphen — so git could prove nothing
about either, and both needed an override with a hand-written reason.

Two details the preflight checks because they are invisible otherwise:

- **Case matters.** The pattern is `[A-D]-\d{3}`. A lowercase branch slug in a
  merge subject matches nothing — which is how `Merge feat/engines/d-045-…`
  attributes nothing on its own.
- **The hyphen matters.** `A011` is not `A-011`.

For a commit finishing several tasks, the body trailer works:

```
Tasks: D-055, D-056
```

Task IDs in prose in a body are ignored on purpose — a docs commit listing the
backlog would otherwise mark the backlog done, which is exactly what happened
the first time the tracker ran.

## Step 3 — the Definition of Done

From `CLAUDE.md`, and worth reading rather than assuming:

- Works against the **real backend**, not only mocks
- Unit tests for logic; integration tests for new endpoints
- New routes have permission-matrix entries for **all six roles** — taken from
  blueprint §2, not from the annotation you just wrote
- Migrations timestamp-versioned; no applied file edited
- OpenAPI spec updated, client regenerated **and committed**
- Storybook entry for any new shared component
- No new lint or compiler warnings
- Rebased on current `develop` — rebase, never merge `develop` into your branch
- Only your stream's paths touched, or sign-off obtained **and stated**

Two that are Stream D's specifically and are easy to miss:

- **Never write your own date maths.** Every SLA, duration, breach and
  utilisation calculation routes through Stream B's working-hours service
  (B-024). A Friday-18:00 ticket with a 4-hour SLA must not breach on Saturday
  morning.
- **A WebSocket subscription is authorised with the same scope rules as REST.**
  A `ChannelInterceptor` must reject a subscription to a topic the user could
  not `GET`. Skipping it reopens the exact hole Stream A's guard closes.

## Step 4 — verify what you touched, not everything

**GitHub Actions is the authority.** It runs on every push and every PR, and it
runs the whole suite. What belongs on your machine is the subset that covers
your own change, because a failure found here costs minutes and the same failure
found on CI costs a round trip.

```bash
cd frontend && npm run test -- --run src/features/<area>
cd frontend && npm run lint && npx tsc --noEmit -p tsconfig.app.json
cd backend  && ./mvnw -pl api -Dtest=<SomeUnitTest> test     # no container
cd backend  && ./mvnw -pl api -Dit.test=<OneIT> verify       # one smoke IT
```

Pick the ITs by asking what your change could break, not by module. A change in
`domain` that alters a decision every producer consults — a preference, a
channel rule, an enum every caller reads — is felt in `api`, and running only
`-pl domain` proves the half that was never in doubt.

`make verify` still exists and still passes, but it is the best part of an hour
on a machine that can do nothing else meanwhile. It is no longer what stands
between a mistake and `develop`, because CI is. Run it when you want the whole
picture before a large or cross-cutting change, not as a matter of course.

If something fails in a way that looks impossible — a compile error in a file
you did not touch, "TestEngine failed to discover tests", a method reported
missing that you can see on the screen — it is a stale `target/`, or a `domain`
jar in `~/.m2` older than your edits. `-pl api` alone resolves `domain` from the
installed jar, not the reactor:

```bash
cd backend && ./mvnw -pl common,domain install -DskipTests
```

## Step 5 — push and open a draft PR

```bash
git push -u origin <branch>
gh pr create --draft --title "..." --body "..."
```

**Every PR opens as a draft.** Drafts run no CI, which is what makes "push
daily" affordable. Mark it ready (`gh pr ready <n>`) when your own tests are
green and you want CI to take it from there.

Keep opening PRs while a batch is in flight — they cost nothing, they are how
the other three see your work, and the queue is of *merges*, not of PRs.

Body worth including: what the task was, the decision anybody would question,
and what you deliberately did not do. If the branch touches another stream's
path, say whose sign-off you have — in the body, where it is on the record.

If you pushed before rebasing and the PR opened at a stale head, `git push
--force-with-lease` and then **check the PR picked up the right file count** —
this happened on D-054 and the PR sat at the old head until it was checked.

## Step 6 — do not merge

Developers never merge. The PR goes into the next integration batch, which
gates every ready PR together on the merge result. That is the `integrate`
skill, and it is the only path to `develop`.

Check the task off in `docs/streams/STREAM-<X>-<NAME>.md` as part of the commit.
Do not hand-edit `docs/plan/tasks.csv` — it is regenerated from git on the next
refresh, so an edit there survives about a minute and misleads anybody reading
in between.
