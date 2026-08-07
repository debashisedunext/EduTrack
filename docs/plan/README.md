# The plan — how it works

Everything in this folder is **generated**, except three files you edit.

| File | Generated? | What it is |
|---|---|---|
| [`GANTT.md`](GANTT.md) | yes | The schedule — summary, driving chain, milestone bars, all 222 tasks |
| [`gantt.html`](gantt.html) | yes | The same thing, interactive. Open it in a browser |
| [`standup/`](standup/) | yes | One file per working day, four briefs inside |
| **[`tasks.csv`](tasks.csv)** | **no** | **The ledger.** Estimates and dependencies live here |
| **[`calendar.json`](calendar.json)** | **no** | Working days, org holidays, developer leave |
| **[`overrides.json`](overrides.json)** | **no** | Status that git cannot see, each with a reason |

Never hand-edit `GANTT.md` or `gantt.html` — they are regenerated on every push
and again at 09:00, so an edit survives about a minute.

---

## Status comes from git, not from anybody's word

| Git says | Status |
|---|---|
| Task ID in a commit subject merged to `develop` | **done** |
| Task ID on an open pull request | **in review** |
| Task ID in a branch name or an unmerged commit subject | **in progress** |
| Nothing | **to do** |

**So put the task ID in the commit subject.** That single habit is what keeps this
plan honest without a status meeting:

```
feat(platform): A-012 dev-noauth profile with configurable fake principal
```

A body trailer works too, for a commit that finishes several at once:

```
Tasks: A-020, A-021
```

Task IDs mentioned in prose in a commit body are ignored on purpose. A docs
commit that lists the whole backlog would otherwise mark the whole backlog done —
which is exactly what happened the first time this ran.

When git genuinely cannot see the truth — work that predates the convention,
or a task half-finished with nothing pushed — add an entry to `overrides.json`
**with a reason**. An override without one is how a plan starts reporting what
people wish were true.

---

## Baseline vs forecast

Two sets of dates, and the difference is the whole point.

- **Baseline** is frozen the first time a task is scheduled and never rewritten.
- **Forecast** is recomputed every morning from the real state of git.

The gap between them is the slip. If the plan re-baselined itself daily, every
slip would quietly erase itself overnight and nobody could ever say how late the
project is. Re-baseline deliberately, at milestone boundaries, by clearing the
baseline columns — not by accident.

---

## How the dates are computed

1. **Working calendar** — Monday to Friday, minus org holidays and per-developer
   leave from `calendar.json`. Half-day granularity.
2. **Resource-constrained scheduling** — one developer does one thing at a time.
   Each morning each developer takes whichever of their *ready* tasks can start
   soonest; ties go to whatever another stream is waiting on, then straight down
   the backlog. Work is pulled forward to fill a gap rather than sitting idle,
   which is why a milestone bar can start earlier than its heading suggests.
3. **A handoff costs a day.** You can pick up your own next task the same
   afternoon; work passed to somebody else lands the next working day.
4. **Float and the driving chain** — a backward pass over both the dependency
   edges and the resource links.

**Float and "critical" are not the same thing.** With developers loaded above
90%, almost everything has zero float — that says the team is saturated, not
what to watch. The **driving chain** is the single path that actually sets the
finish date. That is the one to defend.

---

## Every morning at 09:00

`plan daily` runs, and:

1. fetches and fast-forwards (never clobbers a dirty tree)
2. re-extracts tasks from the four stream backlogs
3. re-derives status from git and reschedules
4. writes `GANTT.md`, `gantt.html`, `tasks.csv`, today's briefs
5. commits and pushes the refreshed plan
6. posts each developer's brief to their own GitHub issue, @-mentioning them

Run it by hand any time:

```bash
plan refresh                 # recompute the schedule and rebuild the outputs
plan daily                   # what the 09:00 job runs — refresh, commit, push, brief
PLAN_NOTIFY=0 plan daily     # refresh, but message nobody
plan cron status             # is it scheduled, when did it last run
plan check                   # prove the chart renders
```

---

## And within 60 seconds of any push

A watcher polls the remote every minute. When any branch moves it pulls,
reschedules, and republishes the shareable chart:

**[EduTrack — Master Schedule](https://claude.ai/code/artifact/3d96e4c6-7008-45ba-aeb5-01ce1e7e2483)**

```bash
plan watch status            # is it watching, what did it last do
plan publish                 # push the current chart to that link by hand
```

That link is a **snapshot, not a live view**. A published artifact cannot fetch
anything — its CSP blocks every external host, and this repo is private, so a
token embedded in a shareable page would be a credential leak. It is pushed to,
never pulled from. `GANTT.md` in this repo is the source of truth; the link is a
copy of it for people who will never clone.

Publishing is skipped when the chart is byte-identical to what was last
published, so a refresh that changes nothing costs nothing.

**The watcher sees pushes, not PR open/close.** Those usually follow a push
within minutes, and the 09:00 job catches whatever slipped through.

The engine lives in [plan-tracker](https://github.com/debashisedunext/plan-tracker),
not in this repo — the same code runs every project's plan. This project's
settings are in [`../../plan.config.json`](../../plan.config.json).

---

## Changing the plan

| To change | Edit | Then |
|---|---|---|
| An estimate | `estimate_days` in `tasks.csv` | `plan refresh` |
| A dependency | `predecessors` in `tasks.csv` | same |
| Confirm an inferred edge | `pred_confidence` → `confirmed` | same |
| Add a holiday or leave | `calendar.json` | same |
| Correct a status | `overrides.json`, with a reason | same |
| Add or reword a task | the `docs/streams/STREAM-*.md` backlog | `plan refresh` |

`tasks.csv` is the source of truth. Re-running the extractor never overwrites
what is already in it — it only appends genuinely new task IDs, and a task
deleted from a stream file is flagged rather than dropped, because a row that
vanishes takes its history with it.

---

## Read the `ᶦ` marks

Roughly half the dependency edges are marked **inferred** — derived from task
ordering and wording, not confirmed by the person who owns the task. The
critical path through an inferred edge is a hypothesis.

The first job for all four developers is to read their own stream's edges and
correct them. After that the chain means something.
