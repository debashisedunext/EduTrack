#!/usr/bin/env python3
"""
Read the plan ledger and answer the four questions that actually get asked.

    status.py mine [--stream D]   what can I pick up, what's in flight, what's stuck
    status.py team                one block per stream
    status.py ready  [--stream D] every task whose predecessors are all done
    status.py blocked [--stream D] every stuck task, and who owes the unblock
    status.py owed   [--stream D] my unfinished work that another stream waits on
    status.py task D-028          one task in full, both directions of the graph

Everything comes from `docs/plan/tasks.csv`, which `plan refresh` derives from
git. This script never guesses status — if the CSV is stale it says so and
tells you to refresh, rather than answering from a ledger that predates the
last four merges.
"""

from __future__ import annotations

import argparse
import csv
import json
import subprocess
import sys
from pathlib import Path

DONE = "done"
OPEN_STATES = ("todo", "in-progress", "in-review", "blocked")


# ---------------------------------------------------------------- loading

def find_config(start: Path) -> Path:
    """Walk up for plan.config.json, the same way the `plan` CLI does."""
    for directory in [start, *start.parents]:
        candidate = directory / "plan.config.json"
        if candidate.exists():
            return candidate
    sys.exit("no plan.config.json found — run this from inside the project")


def load(config_path: Path):
    config = json.loads(config_path.read_text())
    root = config_path.parent
    csv_path = root / config["plan_dir"] / "tasks.csv"
    if not csv_path.exists():
        sys.exit(f"{csv_path} missing — run `plan refresh`")
    with csv_path.open(newline="") as handle:
        tasks = {row["id"]: row for row in csv.DictReader(handle)}
    return config, root, csv_path, tasks


def preds(task) -> list[str]:
    raw = (task.get("predecessors") or "").strip()
    return [p.strip() for p in raw.split(",") if p.strip()]


def staleness(root: Path, csv_path: Path, branch: str) -> list[str]:
    """
    Every reason the ledger might not describe reality, in order of severity.

    A stale ledger is the one failure mode that produces a confident wrong
    answer — it will happily report a task as todo that was merged an hour ago.

    The first version of this compared the ledger against local HEAD, which is
    the wrong reference and quietly said nothing while this checkout sat 89
    commits behind origin. Status is derived from what has merged, so the
    question is always "behind the REMOTE", never "behind my copy of it".
    """
    def git(*args) -> str:
        return subprocess.run(
            ["git", *args], cwd=root, capture_output=True, text=True,
        ).stdout.strip()

    warnings: list[str] = []
    remote = f"origin/{branch}"
    subprocess.run(["git", "fetch", "-q", "origin", branch], cwd=root,
                   capture_output=True, text=True, timeout=30)

    # 1. Is this checkout behind the team? Everything else is downstream of it:
    #    `plan refresh` derives status from local git history, so refreshing
    #    without pulling first rebuilds the same stale answer more confidently.
    behind = git("rev-list", "--count", f"HEAD..{remote}")
    if behind and behind != "0":
        warnings.append(
            f"this checkout is {behind} commit(s) behind {remote} — "
            f"status is derived from what has merged, so pull before trusting any of it"
        )

    # 2. Has anything merged since the ledger was last rebuilt?
    #
    #    Only askable of a CLEAN file. Once the ledger has been rebuilt but not
    #    committed, the last commit touching it says nothing about how fresh it
    #    is — and this check duly told somebody to run `plan refresh` in the
    #    same second that `plan refresh` finished. A check that fires after its
    #    own remedy teaches people to ignore it, which costs more than the
    #    warning was ever worth.
    relative = str(csv_path.relative_to(root))
    dirty = bool(git("status", "--porcelain", "--", relative))

    if dirty:
        # 3. Rebuilt locally, not committed: the file on disk is not the file
        #    anybody else sees, so a number quoted from it is not reproducible
        #    by the team until it is pushed.
        warnings.append(
            f"{relative} was rebuilt locally but is not committed — "
            f"the team still sees the old numbers until this is pushed"
        )
    else:
        last = git("log", "-1", "--format=%H", "--", relative)
        if last:
            since = git("rev-list", "--count", f"{last}..{remote}")
            if since and since != "0":
                warnings.append(
                    f"{since} commit(s) on {remote} since the ledger was rebuilt — run `plan refresh`"
                )

    return warnings


# ---------------------------------------------------------------- queries

def classify(tasks: dict, stream: str | None):
    """Split one stream's open tasks into ready / in-flight / blocked."""
    ready, in_flight, blocked = [], [], []
    for task in tasks.values():
        if stream and task["stream"] != stream:
            continue
        if task["status"] == DONE:
            continue

        waiting = [
            tasks[p] for p in preds(task)
            if p in tasks and tasks[p]["status"] != DONE
        ]
        if task["status"] in ("in-progress", "in-review"):
            in_flight.append((task, waiting))
        elif task["status"] == "blocked" or waiting:
            # A declared `blocked` outranks the dependency graph. An override
            # saying so is somebody who looked at the task and found a reason
            # the predecessor list cannot express — D-053 has every predecessor
            # done and still cannot start, because the table it needs to write
            # to has no column for what it would write. Trusting the graph over
            # the human here is how a task gets picked up twice and abandoned
            # twice.
            blocked.append((task, waiting))
        else:
            ready.append((task, waiting))
    return ready, in_flight, blocked


def owed_by(tasks: dict, stream: str | None):
    """
    My unfinished tasks that somebody else's task names as a predecessor.

    This is the list worth reading first each morning: it is the work where
    my slip becomes another developer's idle day.
    """
    out = []
    for task in tasks.values():
        if stream and task["stream"] != stream:
            continue
        if task["status"] == DONE:
            continue
        waiters = [
            other for other in tasks.values()
            if other["stream"] != task["stream"]
            and other["status"] != DONE
            and task["id"] in preds(other)
        ]
        if waiters:
            out.append((task, waiters))
    return out


# ---------------------------------------------------------------- printing

def line(task, note: str = "") -> str:
    critical = " ⚑critical" if task.get("is_critical") else ""
    due = task.get("forecast_end") or ""
    return f"  {task['id']}  {task['title'][:58]:<58} {due}{critical}{note}"


def why_blocked(task, waiting, tasks, config) -> str:
    """
    What is actually holding this task, in one line.

    Predecessors when there are any; otherwise the override's own reason, which
    is the only place a blocker that isn't another task gets recorded.
    """
    if waiting:
        return "waiting on " + ", ".join(owner_of(tasks, w["id"], config) for w in waiting)
    reason = (task.get("evidence") or task.get("notes") or "").strip()
    reason = reason.removeprefix("override:").strip()
    if not reason:
        return "marked blocked, no reason recorded — that is worth chasing"
    return "blocked: " + (reason[:150] + "…" if len(reason) > 150 else reason)


def owner_of(tasks, task_id, config):
    task = tasks.get(task_id)
    if not task:
        return task_id
    return f"{task_id} ({task['owner']}, {task['status']})"


def show_mine(tasks, config, stream):
    ready, in_flight, blocked = classify(tasks, stream)
    owed = owed_by(tasks, stream)
    title = config["streams"].get(stream, {}).get("title", stream) if stream else "all streams"
    print(f"\nStream {stream} — {title}\n")

    print(f"IN FLIGHT ({len(in_flight)})")
    for task, _ in sorted(in_flight, key=lambda t: t[0]["id"]):
        print(line(task, f"  [{task['status']}]"))
    if not in_flight:
        print("  nothing")

    print(f"\nREADY TO PICK UP ({len(ready)}) — every predecessor is done")
    for task, _ in sorted(ready, key=lambda t: t[0]["id"]):
        print(line(task))
    if not ready:
        print("  nothing")

    print(f"\nBLOCKED ({len(blocked)})")
    for task, waiting in sorted(blocked, key=lambda t: t[0]["id"]):
        print(line(task, f"\n      ← {why_blocked(task, waiting, tasks, config)}"))
    if not blocked:
        print("  nothing")

    print(f"\nOTHERS ARE WAITING ON ME ({len(owed)})")
    for task, waiters in sorted(owed, key=lambda t: t[0]["id"]):
        who = ", ".join(sorted({f"{w['owner']} ({w['id']})" for w in waiters}))
        print(line(task, f"\n      → blocks {who}"))
    if not owed:
        print("  nothing")
    print()


def show_team(tasks, config):
    print()
    for stream, meta in config["streams"].items():
        rows = [t for t in tasks.values() if t["stream"] == stream]
        done = [t for t in rows if t["status"] == DONE]
        ready, in_flight, blocked = classify(tasks, stream)
        pct = round(100 * len(done) / len(rows)) if rows else 0
        bar = "█" * (pct // 5) + "·" * (20 - pct // 5)
        print(f"Stream {stream} — {meta['title']} · {meta['owner']}")
        print(f"  {bar} {len(done)}/{len(rows)} done ({pct}%)")
        print(f"  in flight {len(in_flight)} · ready {len(ready)} · blocked {len(blocked)}")
        if in_flight:
            print(f"  now: {', '.join(t['id'] for t, _ in sorted(in_flight, key=lambda x: x[0]['id'])[:4])}")
        print()

    total = len(tasks)
    done = sum(1 for t in tasks.values() if t["status"] == DONE)
    print(f"Whole project: {done}/{total} done ({round(100 * done / total)}%)\n")


def show_task(tasks, config, task_id):
    task = tasks.get(task_id)
    if not task:
        sys.exit(f"{task_id} is not in the ledger")

    print(f"\n{task['id']}  {task['title']}")
    print(f"  stream {task['stream']} · {task['owner']} · {task['milestone']}")
    print(f"  status {task['status']} ({task['pct']}%) · estimate {task['estimate_days']}d "
          f"· float {task['float_days']}d")
    print(f"  baseline {task['baseline_start']} → {task['baseline_end']}")
    print(f"  forecast {task['forecast_start']} → {task['forecast_end']}")
    if task.get("blueprint_ref"):
        print(f"  blueprint {task['blueprint_ref']}")
    if task.get("screen"):
        print(f"  screen {task['screen']}")
    if task.get("evidence"):
        print(f"  evidence {task['evidence']}")

    upstream = preds(task)
    print(f"\n  waits on ({len(upstream)}):")
    for p in upstream or []:
        print(f"    {owner_of(tasks, p, config)}")
    if not upstream:
        print("    nothing")

    downstream = [t for t in tasks.values() if task_id in preds(t)]
    print(f"\n  blocks ({len(downstream)}):")
    for t in sorted(downstream, key=lambda t: t["id"]):
        print(f"    {t['id']} ({t['owner']}, {t['status']}) {t['title'][:44]}")
    if not downstream:
        print("    nothing")
    print()


def show_list(tasks, config, which, stream):
    ready, in_flight, blocked = classify(tasks, stream)
    if which == "ready":
        rows = sorted(ready, key=lambda t: t[0]["id"])
        print(f"\nREADY ({len(rows)})")
        for task, _ in rows:
            print(line(task, f"  [{task['stream']}·{task['owner']}]"))
    elif which == "blocked":
        rows = sorted(blocked, key=lambda t: t[0]["id"])
        print(f"\nBLOCKED ({len(rows)})")
        for task, waiting in rows:
            print(line(task, f"\n      ← {why_blocked(task, waiting, tasks, config)}"))
    else:
        rows = sorted(owed_by(tasks, stream), key=lambda t: t[0]["id"])
        print(f"\nOWED TO OTHER STREAMS ({len(rows)})")
        for task, waiters in rows:
            who = ", ".join(sorted({f"{w['owner']} ({w['id']})" for w in waiters}))
            print(line(task, f"\n      → {who}"))
    print()


# ---------------------------------------------------------------- entry

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["mine", "team", "ready", "blocked", "owed", "task"])
    parser.add_argument("task_id", nargs="?")
    parser.add_argument("--stream", help="A, B, C or D")
    args = parser.parse_args()

    config_path = find_config(Path.cwd().resolve())
    config, root, csv_path, tasks = load(config_path)

    for warning in staleness(root, csv_path, config.get("branch", "develop")):
        print(f"⚠ {warning}")

    if args.command == "team":
        show_team(tasks, config)
    elif args.command == "task":
        if not args.task_id:
            sys.exit("usage: status.py task D-028")
        show_task(tasks, config, args.task_id.upper())
    elif args.command == "mine":
        show_mine(tasks, config, (args.stream or "D").upper())
    else:
        show_list(tasks, config, args.command, args.stream.upper() if args.stream else None)


if __name__ == "__main__":
    main()
