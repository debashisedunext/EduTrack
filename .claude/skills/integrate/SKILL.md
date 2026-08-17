---
name: integrate
description: Run an EduTrack integration batch — collect every ready PR across all four streams, merge them into one integration branch, push it so GitHub Actions verifies the combined result once, then merge the green ones and hand back the offender. Invoke when asked to integrate, merge PRs, clear the queue, or run a batch. Developers never merge; this is the only path to develop.
---

# Integrate — the batch merge

## The rule this implements

> Every PR is verified before it is merged. No exception, and no PR merges any
> other way. — `CLAUDE.md`

**Verified means GitHub Actions was green on the merge result**, not on any one
branch. A branch that is green alone can still break `develop` — which is the
whole reason a batch is gated as one thing rather than four.

`tools/integration-gate.sh` is **retired** (17 Aug 2026). Its header says so.
Do not run it as part of this procedure: a full run is ~50 minutes on a machine
that can do nothing else meanwhile, and that expense is exactly why it stopped
being run at all. It is insurance for the next Actions outage, nothing more.

## Step 1 — take inventory

```bash
.claude/skills/integrate/batch.sh candidates
```

Ready means **open and not a draft**. A draft is deliberate — drafts run no CI,
which is what makes "push daily" affordable — so a draft is never in the batch
and is never reported as stalled.

The batch is **every ready PR, whoever wrote it**. Not one stream's. That scope
is the entire point: on 12 Aug three consecutive verification runs were
invalidated by somebody *else's* merge landing mid-run (A-076, then B-013, then
A-031). Batching one stream fixes nothing, because the race is with the other
three.

If there is one ready PR, that is a batch of one. Do not wait for company — and
a batch of one needs no integration branch at all, because its own PR checks
already ran on the merge result. Just confirm they are green and merge it.

## Step 2 — build the integration branch

```bash
git fetch origin
.claude/skills/integrate/batch.sh head          # record this SHA
git checkout -b integration/$(date +%Y%m%d-%H%M) origin/develop
```

Then, for each candidate branch in turn:

```bash
git merge --no-ff --no-edit origin/<branch>
```

**A conflict is not a failure of the batch.** Resolve it if it is unambiguous
(independent files, a shared import block); if it needs a judgement call about
somebody else's code, drop that PR from the batch and say so — the rest proceed.
Never resolve a conflict inside another stream's logic on their behalf.

## Step 3 — let Actions verify it, once

```bash
git push -u origin integration/<stamp>
gh pr create --base develop --title "integration: <stamp> — #a #b #c" --body "..." --fill
```

The PR is what gets CI. Watch it rather than polling by hand:

```bash
gh pr checks <n> --watch
```

Read the result carefully:

- **All green** — proceed to step 4.
- **A check failed** — go to step 5. Re-run it once (`gh run rerun <id> --failed`)
  before concluding it is real; a hosted runner is not immune to the flake that
  cost several local runs, and `StageSlaScannerIT` has a known MySQL deadlock
  that reproduces on neither a second run nor `develop`.
- **A check is `skipping`** — that is the workflow's path filter, not a problem.
  It is also why branch protection cannot yet require these checks by name; a
  skipped job never reports, so requiring it would deadlock every PR that did
  not touch its paths. Stream A owns the aggregate job that fixes this
  (issue #3).

## Step 4 — merge, but only if develop has not moved

```bash
git fetch origin develop
.claude/skills/integrate/batch.sh head    # same SHA as step 2?
```

**If it changed, the green is void.** Rebuild the integration branch on the new
`develop` and let CI run again. Do not merge on a stale green — that is
precisely the unverified merge this procedure exists to prevent. This window is
now one push wide rather than fifty minutes, but it is not zero.

If unchanged, merge the integration PR, then close out each member PR
(`gh pr merge` where the branch merges cleanly, otherwise `gh pr close` with a
comment naming the integration branch), and refresh the plan:

```bash
plan refresh && git add docs/plan \
  && git commit -m "chore(plan): schedule refresh after <batch>" && git push
```

`develop` moves **once**, at the end. One batch, one CI run, one push.

## Step 5 — when it fails

**One bad PR does not hold three good ones hostage.**

1. Identify which PR broke it — the named check usually points at a file, and
   `git log --oneline origin/develop..HEAD -- <path>` says whose merge brought
   it.
2. Drop that one, rebuild the integration branch **without** it, push, re-run.
3. The green remainder merges.
4. Hand the offender back to its author with **the failing check named** — not
   "CI failed". Their own PR was green; if it passed there and failed here, the
   difference is the merge result, and saying which check is what makes that
   actionable. `/notify-stream` has the format.

Never merge partially, and never merge "the parts that were fine" out of a PR
that failed.

## What is not yours to decide

- **Do not fix another stream's failure to get the batch green.** Hand it back.
  The one exception is a failure in *your own* changed lines that the merge
  exposed.
- **Do not guess at a permission-matrix entry** you did not write. On 13 Aug
  `develop` went red on `PermissionMatrixTest` after two ungated merges landed
  41 seconds apart; the right fix was the one route that was mine and a
  hand-back for the four that were not. `PermissionMatrix.ENTRIES` is taken
  from blueprint §2, not from whatever annotation makes the test pass.
- **Do not merge without a green run because a PR is small.** A-030 reached
  `develop` unverified during the outage, and seven more followed on 16–17 Aug.
  That is the failure this replaces.

## Report

State plainly: which PRs went in, which was dropped and on which check, whether
`develop` moved mid-run, and any check that needed a re-run to pass. If nothing
ever went green, say so — a batch that ended without a merge is a legitimate
outcome and must not be reported as progress.
