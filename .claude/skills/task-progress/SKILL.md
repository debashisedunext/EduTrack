---
name: task-progress
description: Answer questions about EduTrack task status — what is pending for me, what can I pick up now, what am I blocked on, who is waiting on me, how is each developer doing, what does one task depend on. Invoke whenever someone asks about progress, next tasks, blockers, or the state of a stream, instead of reading tasks.csv or the backlog files by hand.
---

# Task progress

Answers "what's my status" without anybody reading 231 CSV rows or guessing.

## Run this first

```bash
python3 .claude/skills/task-progress/status.py <command>
```

| Question asked | Command |
|---|---|
| "what's pending for me" · "what can I pick up" | `mine` (defaults to Stream D; `--stream B` for another) |
| "how is everyone doing" · "status of all developers" | `team` |
| "what's unblocked right now" | `ready` (all streams) or `ready --stream C` |
| "what am I stuck on and who owes it" | `blocked --stream D` |
| "who is waiting on me" | `owed --stream D` |
| "tell me about D-028" | `task D-028` |

`mine` is the usual answer: it prints in-flight, ready, blocked with the owner of
each unblock, and what other streams are waiting on — the four lists that
between them cover almost every version of the question.

## Where the numbers come from, and when not to trust them

`docs/plan/tasks.csv` is the ledger. `plan refresh` derives status from git:

| Git says | Status |
|---|---|
| Task ID in a commit subject merged to `develop` | done |
| Task ID on an open PR | in review |
| Task ID in a branch name or unmerged commit | in progress |
| Nothing | to do |

Two consequences worth stating out loud when you report:

1. **The script warns when the ledger is stale.** If it prints `⚠ N commit(s)
   have landed since the ledger was rebuilt`, say so and offer `plan refresh`
   before quoting any number. A stale ledger produces a *confident wrong
   answer* — it will report a task as todo that merged an hour ago, and that is
   worse than no answer.

2. **`todo` can mean "git cannot see it", not "nothing exists".** The regex is
   `[A-D]-\d{3}` and it is case-sensitive — a lowercase branch slug in a merge
   subject matches nothing. This has already gone wrong on A-010, A-013 and
   A-030. Before reporting somebody's task as untouched, check for a branch or
   PR that git could not attribute:

   ```bash
   git branch -r --list '*<slug>*'
   gh pr list --state all --search "<task-id>"
   ```

   If work exists but git cannot prove it, that is an `overrides.json` entry
   **with a reason** — not a silent correction, and not something to leave
   misreported.

## Cross-check the in-flight list against open PRs

The ledger sees a PR only when the task ID is in its title. Worth a look when
reporting in-flight or team status:

```bash
gh pr list --state open --json number,title,isDraft,author,headRefName
```

A draft PR is deliberate — drafts run no CI, which is what makes "push daily"
affordable. Do not report a draft as stalled.

## Reporting

Lead with the answer to what was asked, not the whole dump. For "what can I pick
up", the ready list plus one line on why each is now unblocked is enough; for
"how is everyone doing", the `team` block plus who is on the critical path.

Two things always worth surfacing unprompted, because they are how a plan slips
quietly:

- **A ready task that another stream is waiting on.** `mine` prints both lists —
  when an ID appears in READY *and* in OTHERS ARE WAITING ON ME, say so. That
  is the one to start.
- **A blocked task whose blocker is also unstarted.** `D-030 ← D-029 (todo)` is
  a chain, not a wait — the whole tail moves only when the head does.

Do not re-derive dates. `forecast_end`, `float_days` and `is_critical` are
computed by the scheduler against the working calendar (weekends, org holidays,
developer leave). Hand arithmetic on top of them will disagree, and the
scheduler is right.

## What this skill does not do

- **It does not change status.** Status comes from git. If reality and git
  disagree, the fix is a commit subject carrying the task ID, or an
  `overrides.json` entry with a reason — never an edit to `tasks.csv` status
  columns, which are regenerated on the next refresh.
- **It does not rebuild the schedule.** That is `plan refresh`, and it runs at
  09:00 and on every push already.
- **It does not decide what to work on.** It reports what is startable; the
  ownership rules in `CLAUDE.md` and the sign-off rules in `TEAM-PLAN.md` §6
  still decide whether a given developer may start it.
