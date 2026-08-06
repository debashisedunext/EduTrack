#!/usr/bin/env python3
"""
EduTrack plan engine — derive status from git, schedule, and publish.

Run it any time; it is idempotent:

    python3 tools/plan/schedule.py            # refresh everything
    python3 tools/plan/schedule.py --dry-run  # print, write nothing

What it does, in order:

1. **Derives status from git**, not from anybody's self-report. Merged into
   `develop` = done; open PR = in-review; pushed branch = in-progress. The only
   way to contradict git is `docs/plan/overrides.json`, which demands a reason.
2. **Schedules** the remaining work with resource-constrained list scheduling —
   one developer does one task at a time, so the chart shows a queue rather than
   the fiction of sixty parallel tasks. Half-day granularity; a developer may
   pick up their own next task the same afternoon, but a handoff to somebody
   else lands the following working day.
3. **Computes float and the critical path** with a backward pass over both the
   dependency edges and the resource links.
4. **Writes** `tasks.csv` (forecast columns), `GANTT.md`, `gantt.html` and
   today's `standup/YYYY-MM-DD.md`.

Baseline dates are written once and never rewritten. That is deliberate: if the
plan re-baselines itself every morning, every slip quietly erases itself and no
one can ever say how late the project is.
"""
import argparse
import csv
import datetime as dt
import json
import os
import re
import subprocess
import sys
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PLAN = os.path.join(ROOT, "docs", "plan")
TASKS_CSV = os.path.join(PLAN, "tasks.csv")
CALENDAR = os.path.join(PLAN, "calendar.json")
OVERRIDES = os.path.join(PLAN, "overrides.json")

TASK_ID = re.compile(r"\b([A-D]-\d{3})\b")
# A task ID counts as a claim only in the commit *subject* or an explicit
# trailer. Prose in a commit body routinely names a dozen task IDs it did not
# touch — a doc commit listing the whole backlog would otherwise mark the whole
# backlog done.
TRAILER = re.compile(r"^\s*(?:Task|Tasks|Closes|Fixes)\s*:\s*(.+)$", re.I | re.M)

STREAM_META = {
    "A": ("Platform & Security", "Shivendra", "#4F46E5"),
    "B": ("Masters & Clients",   "Ayush",     "#F59E0B"),
    "C": ("Tickets & Ribbon",    "Divyansh",  "#BE185D"),
    "D": ("Engines & Realtime",  "Debashis",  "#06B6D4"),
}

STATUS_RANK = {"done": 4, "in-review": 3, "in-progress": 2, "blocked": 1, "todo": 0}
DEFAULT_PCT = {"done": 100, "in-review": 90, "in-progress": 50, "blocked": 0, "todo": 0}


# ─────────────────────────────── calendar ────────────────────────────────────

class Calendar:
    """Working-day arithmetic. Weekends, org holidays, per-developer leave."""

    def __init__(self, cfg):
        self.start = dt.date.fromisoformat(cfg["project_start"])
        self.weekdays = set(cfg.get("working_weekdays", [0, 1, 2, 3, 4]))
        self.holidays = {dt.date.fromisoformat(d) for d in cfg.get("holidays", [])}
        self.leave = {
            who: {dt.date.fromisoformat(d) for d in days}
            for who, days in cfg.get("leave", {}).items()
        }
        self._index = {}
        self._dates = []

    def is_working(self, day, owner=None):
        if day.weekday() not in self.weekdays or day in self.holidays:
            return False
        if owner and day in self.leave.get(owner, ()):
            return False
        return True

    def next_working(self, day, owner=None):
        while not self.is_working(day, owner):
            day += dt.timedelta(days=1)
        return day

    def add_days(self, day, n, owner=None):
        """n working days after `day` (n may be 0)."""
        day = self.next_working(day, owner)
        for _ in range(n):
            day = self.next_working(day + dt.timedelta(days=1), owner)
        return day

    def index(self, day):
        """Global working-day index — the unit float is measured in."""
        self._grow(day)
        return self._index[day]

    def date_at(self, i):
        while len(self._dates) <= i:
            nxt = self._dates[-1] if self._dates else self.start - dt.timedelta(days=1)
            self._dates.append(self.next_working(nxt + dt.timedelta(days=1)))
            self._index[self._dates[-1]] = len(self._dates) - 1
        return self._dates[i]

    def _grow(self, day):
        if day in self._index:
            return
        i = len(self._dates)
        while True:
            d = self.date_at(i)
            if d >= day:
                self._index.setdefault(day, self._index.get(d, i))
                return
            i += 1

    def span(self, a, b):
        """Working days from a to b inclusive."""
        return self.index(b) - self.index(a) + 1


class OwnerTimeline:
    """A developer's half-day slots. Slot k → (date, first/second half)."""

    def __init__(self, cal, owner, first_day):
        self.cal = cal
        self.owner = owner
        self.origin = cal.next_working(first_day, owner)
        self._days = [self.origin]

    def day(self, slot):
        i = slot // 2
        while len(self._days) <= i:
            self._days.append(
                self.cal.next_working(self._days[-1] + dt.timedelta(days=1), self.owner))
        return self._days[i]

    def first_slot_on_or_after(self, day, from_slot=0):
        k = from_slot
        while self.day(k) < day:
            k += 1
        return k


# ──────────────────────────────── git ────────────────────────────────────────

def sh(cmd):
    try:
        return subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True,
                              timeout=60, check=False).stdout
    except Exception:
        return ""


def git_evidence():
    """
    Map every task ID to what git can prove about it.

    Returns {id: {"status", "evidence", "start", "end"}}. Precedence is
    merged > PR open > branch pushed, so a task cannot regress because somebody
    pushed a follow-up branch after it merged.
    """
    ev = {}

    def record(tid, status, evidence, day=None):
        cur = ev.get(tid)
        if cur and STATUS_RANK[cur["status"]] >= STATUS_RANK[status]:
            if day:
                cur["start"] = min(cur["start"], day) if cur["start"] else day
            return
        prev_start = cur["start"] if cur else None
        ev[tid] = {"status": status, "evidence": evidence,
                   "start": min(prev_start, day) if (prev_start and day) else (day or prev_start),
                   "end": day if status == "done" else None}

    def claimed(subject, body):
        ids = set(TASK_ID.findall(subject))
        for m in TRAILER.finditer(body or ""):
            ids |= set(TASK_ID.findall(m.group(1)))
        return ids

    integrated = "origin/develop"
    if not sh(["git", "rev-parse", "--verify", "--quiet", integrated]).strip():
        integrated = "develop"

    def walk(rev):
        for entry in sh(["git", "log", rev, "--date=short",
                         "--pretty=%ad%x1f%s%x1f%b%x1e"]).split("\x1e"):
            parts = entry.strip().split("\x1f")
            if len(parts) < 3:
                continue
            try:
                day = dt.date.fromisoformat(parts[0])
            except ValueError:
                continue
            yield day, claimed(parts[1], parts[2])

    for day, ids in walk(integrated):
        for tid in ids:
            record(tid, "done", "merged to develop " + day.isoformat(), day)

    for br in sh(["git", "branch", "-r", "--format=%(refname:short)"]).split():
        if br.endswith("/HEAD") or br.endswith("/develop") or br.endswith("/main"):
            continue
        ids, first_day = set(TASK_ID.findall(br)), None
        for day, cids in walk(integrated + ".." + br):
            first_day = min(first_day, day) if first_day else day
            ids |= cids
        for tid in ids:
            record(tid, "in-progress", "branch " + br, first_day)

    pr_json = sh(["gh", "pr", "list", "--state", "open", "--limit", "100",
                  "--json", "number,title,headRefName,body"])
    try:
        for pr in json.loads(pr_json or "[]"):
            blob = " ".join(str(pr.get(k, "")) for k in ("title", "headRefName", "body"))
            for tid in set(TASK_ID.findall(blob)):
                record(tid, "in-review", "PR #%s open" % pr["number"])
    except Exception:
        pass

    return ev


# ────────────────────────────── scheduling ───────────────────────────────────

def topo_order(tasks):
    indeg = {t: 0 for t in tasks}
    succ = defaultdict(list)
    for tid, t in tasks.items():
        for p in t["preds"]:
            if p in tasks:
                succ[p].append(tid)
                indeg[tid] += 1
    ready = sorted(t for t, d in indeg.items() if d == 0)
    order = []
    while ready:
        cur = ready.pop(0)
        order.append(cur)
        for s in sorted(succ[cur]):
            indeg[s] -= 1
            if indeg[s] == 0:
                ready.append(s)
                ready.sort()
    if len(order) != len(tasks):
        stuck = sorted(set(tasks) - set(order))
        raise SystemExit("Dependency cycle involving: " + ", ".join(stuck[:12]))
    return order


def schedule(tasks, cal, today):
    """
    Resource-constrained forward pass — greedy earliest-start list scheduling.

    Done work is pinned to the dates git proves. Everything else queues behind
    its dependencies *and* behind whatever else its owner is already doing —
    which is the whole point. A plan that lets one person run six tasks at once
    is not a plan.

    The selection rule matters. Taking tasks in plain topological order lets one
    task that is waiting on another stream drag its owner's entire queue forward
    behind it, inventing months of idle time that nobody would actually sit
    through. So at each step each developer takes whichever of their *ready*
    tasks can start soonest, filling the gap instead of waiting in it. Ties go
    to whatever unblocks the most other people.
    """
    order = topo_order(tasks)
    downstream = defaultdict(int)
    for t in tasks.values():
        for p in t["preds"]:
            if p in tasks:
                downstream[p] += 1

    lines, cursor, pending = {}, defaultdict(int), defaultdict(list)
    for tid in order:
        t = tasks[tid]
        t["res_pred"] = None
        if t["owner"] not in lines:
            lines[t["owner"]] = OwnerTimeline(cal, t["owner"], today)
        if t["status"] == "done":
            # Finished work is pinned to what actually happened and consumes no
            # future capacity. Where git cannot date it — work that predates the
            # task-ID commit convention — it is pinned to today so successors are
            # free to start rather than waiting on a phantom.
            t["start"] = t["actual_start"] or today
            t["end"] = t["actual_end"] or t["actual_start"] or today
        else:
            t["start"] = t["end"] = None
            pending[t["owner"]].append(tid)

    def earliest(t):
        day = today
        for p in t["preds"]:
            pt = tasks.get(p)
            if pt and pt.get("end") and pt["owner"] != t["owner"]:
                day = max(day, cal.add_days(pt["end"], 1, t["owner"]))
        return day

    def ready(t):
        return all(tasks[p].get("end") for p in t["preds"] if p in tasks)

    last_for_owner = {}
    remaining = sum(len(v) for v in pending.values())
    while remaining:
        progressed = False
        for owner, queue in pending.items():
            options = [tasks[tid] for tid in queue if ready(tasks[tid])]
            if not options:
                continue
            # Work already started finishes first; then anything another stream
            # is waiting on; then straight down the backlog in order. A developer
            # should be able to predict their own next task without reading code.
            pick = min(options, key=lambda t: (
                earliest(t),
                t["status"] not in ("in-progress", "in-review"),
                not t["blocker"],
                t["id"]))
            line = lines[owner]
            k = line.first_slot_on_or_after(earliest(pick), cursor[owner])
            slots = max(1, int(round(pick["estimate"] * (1 - pick["pct"] / 100.0) * 2)))
            pick["start"] = line.day(k)
            pick["end"] = line.day(k + slots - 1)
            pick["res_pred"] = last_for_owner.get(owner)
            last_for_owner[owner] = pick["id"]
            cursor[owner] = k + slots
            queue.remove(pick["id"])
            remaining -= 1
            progressed = True
        if not progressed:
            stuck = [tid for q in pending.values() for tid in q]
            raise SystemExit("Cannot schedule (unknown predecessor?): "
                             + ", ".join(sorted(stuck)[:12]))

    return sorted(tasks, key=lambda tid: (tasks[tid]["start"], tid))


def backward_pass(tasks, order, cal):
    """
    Float against the resource-constrained schedule, plus the driving chain.

    Two different things get called "critical" and conflating them is useless.
    *Float* is how many working days a task can slip before it pushes something
    else — with developers loaded above 90% almost everything has zero float,
    which tells you the team is saturated but not what to watch. The *driving
    chain* is the single path of tasks that actually sets the finish date, each
    one linked to the next either by a dependency or by the fact that the same
    person has to do both. That is the path worth defending.
    """
    finish = max(t["end"] for t in tasks.values())
    succ = defaultdict(list)
    for tid, t in tasks.items():
        for p in list(t["preds"]) + ([t["res_pred"]] if t.get("res_pred") else []):
            if p in tasks:
                succ[p].append(tid)

    late = {}
    for tid in reversed(order):
        ss = succ.get(tid, [])
        if not ss:
            late[tid] = finish
        else:
            late[tid] = min(
                cal.date_at(max(0, cal.index(late.get(s, finish))
                                - cal.span(tasks[s]["start"], tasks[s]["end"])))
                for s in ss)
    for tid, t in tasks.items():
        t["late_finish"] = late[tid]
        # Clamped at zero: the backward pass works in whole days while the
        # forward pass packs half-days, so a task that hands straight over can
        # come out a day "negative". The schedule is self-consistent by
        # construction — nothing here can genuinely be late against itself.
        t["float"] = max(0, cal.index(late[tid]) - cal.index(t["end"]))
        t["critical"] = False

    chain, cur = [], max(tasks.values(), key=lambda t: (t["end"], t["id"]))["id"]
    seen = set()
    while cur and cur not in seen:
        seen.add(cur)
        chain.append(cur)
        t = tasks[cur]
        links = [p for p in list(t["preds"]) + [t.get("res_pred")]
                 if p in tasks and tasks[p]["end"]]
        if not links:
            break
        driver = max(links, key=lambda p: (tasks[p]["end"], p))
        # It only drove this task if it finished late enough to be the constraint.
        cur = driver if cal.index(tasks[driver]["end"]) >= cal.index(t["start"]) - 1 else None

    for tid in chain:
        tasks[tid]["critical"] = tasks[tid]["status"] != "done"
    return finish, list(reversed(chain))


# ──────────────────────────────── output ─────────────────────────────────────

def fmt(d):
    return d.isoformat() if d else ""


def nice(d):
    return d.strftime("%a %d %b") if d else "—"


def write_csv(tasks, order):
    with open(TASKS_CSV, newline="", encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))
        cols = list(rows[0].keys()) if rows else []
    for r in rows:
        t = tasks.get(r["id"])
        if not t:
            continue
        r["forecast_start"] = fmt(t["start"])
        r["forecast_end"] = fmt(t["end"])
        if not r.get("baseline_start"):
            # First sight of this task: freeze its baseline, then never again.
            r["baseline_start"] = fmt(t["start"])
            r["baseline_end"] = fmt(t["end"])
            t["baseline_start"], t["baseline_end"] = t["start"], t["end"]
        r["status"] = t["status"]
        r["pct"] = str(t["pct"])
        r["actual_start"] = fmt(t["actual_start"])
        r["actual_end"] = fmt(t["actual_end"])
        r["float_days"] = str(t["float"])
        r["is_critical"] = "yes" if t["critical"] else ""
        r["evidence"] = t["evidence"]
    with open(TASKS_CSV, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=cols, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)


def slip(t, cal):
    if not t["baseline_end"]:
        return 0
    return cal.index(t["end"]) - cal.index(t["baseline_end"])


def write_markdown(tasks, order, cal, today, finish, chain):
    by_stream = defaultdict(list)
    for tid in sorted(tasks):
        by_stream[tasks[tid]["stream"]].append(tasks[tid])

    done = [t for t in tasks.values() if t["status"] == "done"]
    wip = [t for t in tasks.values() if t["status"] in ("in-progress", "in-review")]
    eff_done = sum(t["estimate"] for t in done)
    eff_all = sum(t["estimate"] for t in tasks.values())
    crit = [t for t in tasks.values() if t["critical"]]

    L = []
    a = L.append
    a("# EduTrack — Master Schedule\n")
    a("**Generated %s · day %d of the plan · finish forecast %s**\n"
      % (today.strftime("%a %d %b %Y"), cal.index(today) + 1, nice(finish)))
    a("> Regenerated automatically at 09:00 every working day by "
      "`tools/plan/schedule.py`. **Do not hand-edit.** Change an estimate or a "
      "dependency in [`tasks.csv`](tasks.csv); record a status git cannot see in "
      "[`overrides.json`](overrides.json).\n")
    a("Interactive chart: [`gantt.html`](gantt.html) · "
      "Today's briefs: [`standup/%s.md`](standup/%s.md)\n" % (today, today))
    a("---\n")

    a("## Where we are\n")
    a("| | Tasks | Effort (days) |")
    a("|---|---:|---:|")
    a("| Complete | %d of %d (%.0f%%) | %.1f of %.1f (%.0f%%) |"
      % (len(done), len(tasks), 100.0 * len(done) / len(tasks),
         eff_done, eff_all, 100.0 * eff_done / eff_all))
    a("| In flight | %d | %.1f |" % (len(wip), sum(t["estimate"] for t in wip)))
    a("| On the driving chain | %d | %.1f |"
      % (len(crit), sum(t["estimate"] for t in crit)))
    a("| Zero float (no slack at all) | %d | %.1f |"
      % (len([t for t in tasks.values() if t["float"] <= 0 and t["status"] != "done"]),
         sum(t["estimate"] for t in tasks.values()
             if t["float"] <= 0 and t["status"] != "done")))
    a("")

    a("### By developer\n")
    a("| Stream | Developer | Tasks | Done | Effort | Working days available | Load | Finishes |")
    a("|---|---|---:|---:|---:|---:|---:|---|")
    capacity = cal.span(cal.date_at(0), finish)
    for s in "ABCD":
        ts = by_stream[s]
        eff = sum(t["estimate"] for t in ts)
        d = len([t for t in ts if t["status"] == "done"])
        last = max(t["end"] for t in ts)
        load = 100.0 * eff / capacity
        flag = " ⚠️" if load > 100 else ""
        a("| **%s** | %s | %d | %d | %.1f | %d | %.0f%%%s | %s |"
          % (s, STREAM_META[s][1], len(ts), d, eff, capacity, load, flag, nice(last)))
    a("")

    a("### Slipping\n")
    late = sorted((t for t in tasks.values() if slip(t, cal) > 0),
                  key=lambda t: -slip(t, cal))
    if not late:
        a("Nothing is behind its baseline.\n")
    else:
        a("| Task | Owner | Baseline end | Forecast end | Slip |")
        a("|---|---|---|---|---:|")
        for t in late[:20]:
            a("| `%s` %s | %s | %s | %s | +%dd |"
              % (t["id"], t["title"][:48], t["owner"],
                 nice(t["baseline_end"]), nice(t["end"]), slip(t, cal)))
        if len(late) > 20:
            a("\n*…and %d more — see the interactive chart.*" % (len(late) - 20))
        a("")

    a("---\n")
    a("## The chain that sets the finish date\n")
    a("Each of these is held up either by the one before it or by the fact that the "
      "same person has to do both. Shorten this chain and go-live moves; shorten "
      "anything else and it does not.\n")
    a("| # | Task | Owner | Title | Est | Start | End | Held up by |")
    a("|---:|---|---|---|---:|---|---|---|")
    for n, tid in enumerate(chain, 1):
        t = tasks[tid]
        prev = chain[n - 2] if n > 1 else None
        why = "—"
        if prev:
            why = ("`%s` finished" % prev) if prev in t["preds"] \
                else ("%s was busy on `%s`" % (t["owner"], prev))
        a("| %d | `%s` | %s | %s | %g | %s | %s | %s |"
          % (n, t["id"], t["owner"], t["title"][:52], t["estimate"],
             nice(t["start"]), nice(t["end"]), why))
    a("")

    a("---\n")
    a("## Timeline by milestone\n")
    a("Bars reflect where the work is actually scheduled, not the week the backlog "
      "heading names. A developer with nothing else ready pulls later work forward "
      "rather than idling, so a milestone can start earlier than its title suggests.\n")
    for s in "ABCD":
        title, owner, _ = STREAM_META[s]
        a("### Stream %s — %s · %s\n" % (s, title, owner))
        groups = defaultdict(list)
        for t in by_stream[s]:
            groups[t["milestone"]].append(t)
        a("```mermaid")
        a("gantt")
        a("    dateFormat YYYY-MM-DD")
        a("    axisFormat %d %b")
        a("    title Stream %s — %s" % (s, owner))
        a("    excludes weekends")
        a("    section Milestones")
        for n, (ms, ts) in enumerate(
                sorted(groups.items(), key=lambda kv: min(t["start"] for t in kv[1]))):
            st = min(t["start"] for t in ts)
            en = max(t["end"] for t in ts)
            pct = 100 * len([t for t in ts if t["status"] == "done"]) / len(ts)
            tag = "done, " if pct == 100 else ("active, " if pct > 0 else "")
            label = re.sub(r"[:#,]", " ", ms)[:46].strip() or "Unscheduled"
            a("    %s :%s%s%d, %s, %dd" % (label, tag, s.lower(), n, st, cal.span(st, en)))
        a("```\n")

    a("---\n")
    a("## Every task\n")
    a("`▲` critical path · `🔴` another developer is waiting on it · "
      "float is working days of slack before the finish date moves.\n")
    for s in "ABCD":
        title, owner, _ = STREAM_META[s]
        a("<details>")
        a("<summary><b>Stream %s — %s · %s · %d tasks</b></summary>\n"
          % (s, title, owner, len(by_stream[s])))
        a("| | Task | Title | Est | Predecessors | Start | End | Float | Status |")
        a("|---|---|---|---:|---|---|---|---:|---|")
        for t in by_stream[s]:
            marks = ("▲" if t["critical"] else "") + ("🔴" if t["blocker"] else "")
            preds = " ".join("`%s`" % p for p in t["preds"]) or "—"
            if t["pred_confidence"] == "inferred" and t["preds"]:
                preds += " ᶦ"
            st = {"done": "✅ done", "in-review": "🔵 in review",
                  "in-progress": "🟡 %d%%" % t["pct"], "blocked": "⛔ blocked",
                  "todo": "▫️ to do"}[t["status"]]
            a("| %s | `%s` | %s | %g | %s | %s | %s | %d | %s |"
              % (marks, t["id"], t["title"][:70], t["estimate"], preds,
                 nice(t["start"]), nice(t["end"]), t["float"], st))
        a("\n</details>\n")

    a("---\n")
    a("*`ᶦ` marks an **inferred** dependency — derived from task ordering, not "
      "confirmed by its owner. Correct them in `tasks.csv` and the critical path "
      "stops being a hypothesis.*")

    with open(os.path.join(PLAN, "GANTT.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(L) + "\n")


def write_standup(tasks, cal, today, finish):
    lines = ["# Daily briefs — %s\n" % today.strftime("%A %d %B %Y"),
             "Day %d of the plan. Finish forecast **%s**.\n"
             % (cal.index(today) + 1, nice(finish)),
             "---\n"]
    per_dev = {}

    for s in "ABCD":
        title, owner, _ = STREAM_META[s]
        ts = [t for t in tasks.values() if t["stream"] == s]
        ts.sort(key=lambda t: (t["start"], t["id"]))

        finished = [t for t in ts if t["status"] == "done"]
        just_done = sorted([t for t in finished if t["actual_end"]
                            and cal.index(today) - cal.index(t["actual_end"]) <= 1],
                           key=lambda t: t["actual_end"])
        active = [t for t in ts if t["status"] in ("in-progress", "in-review")]
        todo = [t for t in ts if t["status"] == "todo"]

        def ready(t):
            return all(tasks[p]["status"] == "done" for p in t["preds"] if p in tasks)

        startable = [t for t in todo if ready(t)]
        waiting = [t for t in todo if not ready(t)]

        # Today's work is whatever the schedule actually puts on today — not the
        # next thing in the backlog. If the schedule says nothing starts today,
        # fall back to whatever is genuinely startable so nobody sits idle.
        today_work = [t for t in active + startable
                      if t["start"] <= today <= t["end"]]
        if not today_work:
            today_work = (active + startable)[:2]
        # Anything another stream is waiting on goes to the top of the brief.
        today_work = sorted(today_work, key=lambda t: (not t["blocker"], t["start"], t["id"]))[:3]
        nxt = [t for t in (active + startable) if t not in today_work][:3]

        blocked_by_others = []
        for t in todo[:25]:
            for p in t["preds"]:
                pt = tasks.get(p)
                if pt and pt["status"] != "done" and pt["owner"] != owner:
                    blocked_by_others.append((t, pt))
                    break

        # what other people are waiting on *me* for
        owed = []
        for t in ts:
            if t["status"] == "done":
                continue
            waiters = {tasks[o]["owner"] for o in tasks
                       if t["id"] in tasks[o]["preds"] and tasks[o]["owner"] != owner}
            if waiters:
                owed.append((t, sorted(waiters)))
        owed.sort(key=lambda x: x[0]["start"])

        slipped = [t for t in ts if slip(t, cal) > 0]
        worst = max((slip(t, cal) for t in slipped), default=0)

        B = []
        b = B.append
        b("## Stream %s — %s · %s\n" % (s, title, owner))
        b("```")
        b("FINISHED    " + (", ".join("%s %s" % (t["id"], t["title"][:40])
                                      for t in just_done[-3:]) or "—"))
        b("TODAY       " + (", ".join("%s %s" % (t["id"], t["title"][:44])
                                      for t in today_work) or "— nothing ready, see BLOCKED"))
        b("NEXT        " + (", ".join(t["id"] for t in nxt) or "—"))
        b("BLOCKED     " + (", ".join("%s ← %s (%s)" % (t["id"], p["id"], p["owner"])
                                      for t, p in blocked_by_others[:3]) or "nothing"))
        b("OWED        " + (", ".join("%s → %s by %s" % (t["id"], "/".join(w), nice(t["end"]))
                                      for t, w in owed[:3]) or "nobody is waiting on you"))
        b("SLIP        " + ("%d task(s) behind baseline, worst +%dd" % (len(slipped), worst)
                            if slipped else "on baseline"))
        b("```\n")
        if today_work:
            b("**Today in detail**\n")
            for t in today_work:
                flag = " · **critical path — the finish date moves if this slips**" \
                    if t["critical"] else (" · %d days float" % t["float"])
                b("- **`%s` %s**%s" % (t["id"], t["title"], flag))
                if t["blueprint_ref"]:
                    b("  - blueprint %s%s" % (t["blueprint_ref"],
                                              " · screen " + t["screen"] if t["screen"] else ""))
                b("  - %g day estimate · due %s" % (t["estimate"], nice(t["end"])))
                if t["blocker"]:
                    w = sorted({tasks[o]["owner"] for o in tasks
                                if t["id"] in tasks[o]["preds"]
                                and tasks[o]["owner"] != owner})
                    if w:
                        who = w[0] if len(w) == 1 else ", ".join(w[:-1]) + " and " + w[-1]
                        b("  - 🔴 **%s cannot proceed until this lands.**" % who)
            b("")
        if waiting and not startable:
            b("*Everything else in your queue is waiting on somebody. "
              "See `docs/DEPENDENCIES.md` §5 for what to pick up instead.*\n")
        b("---\n")
        per_dev[owner] = "\n".join(B)
        lines.extend(B)

    path = os.path.join(PLAN, "standup", "%s.md" % today)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    return path, per_dev


def write_html(tasks, cal, today, finish):
    payload = []
    for tid in sorted(tasks):
        t = tasks[tid]
        payload.append({
            "id": t["id"], "s": t["stream"], "o": t["owner"], "m": t["milestone"],
            "sec": t["section"], "t": t["title"], "e": t["estimate"],
            "p": t["preds"], "pc": t["pred_confidence"],
            "bs": fmt(t["baseline_start"]), "be": fmt(t["baseline_end"]),
            "fs": fmt(t["start"]), "fe": fmt(t["end"]),
            "st": t["status"], "pct": t["pct"], "fl": t["float"],
            "cr": bool(t["critical"]), "bl": bool(t["blocker"]),
            "ref": t["blueprint_ref"], "scr": t["screen"], "ev": t["evidence"],
        })
    meta = {
        "today": today.isoformat(),
        "start": cal.date_at(0).isoformat(),
        "finish": finish.isoformat(),
        "holidays": sorted(d.isoformat() for d in cal.holidays),
        "streams": {k: {"title": v[0], "owner": v[1], "color": v[2]}
                    for k, v in STREAM_META.items()},
    }
    tpl_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "gantt_template.html")
    with open(tpl_path, encoding="utf-8") as fh:
        tpl = fh.read()
    out = tpl.replace("/*__TASKS__*/", json.dumps(payload, separators=(",", ":"))) \
             .replace("/*__META__*/", json.dumps(meta, separators=(",", ":")))
    with open(os.path.join(PLAN, "gantt.html"), "w", encoding="utf-8") as fh:
        fh.write(out)


# ───────────────────────────────── main ──────────────────────────────────────

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--date", help="pretend today is this date (YYYY-MM-DD)")
    args = ap.parse_args()

    cfg = json.load(open(CALENDAR, encoding="utf-8"))
    cal = Calendar(cfg)
    overrides = {k: v for k, v in json.load(open(OVERRIDES, encoding="utf-8")).items()
                 if not k.startswith("_")}
    today = dt.date.fromisoformat(args.date) if args.date else dt.date.today()
    today = cal.next_working(today)

    ev = git_evidence()

    tasks = {}
    with open(TASKS_CSV, newline="", encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            if "dropped" in (r.get("notes") or ""):
                continue
            g = ev.get(r["id"], {})
            ov = overrides.get(r["id"], {})
            status = ov.get("status") or g.get("status") or "todo"
            pct = ov.get("pct", DEFAULT_PCT[status])
            evidence = ov.get("reason") and ("override: " + ov["reason"]) or g.get("evidence", "")
            tasks[r["id"]] = {
                "id": r["id"], "stream": r["stream"], "owner": r["owner"],
                "github": r["github"], "milestone": r["milestone"],
                "section": r["section"], "title": r["title"],
                "screen": r["screen"], "blueprint_ref": r["blueprint_ref"],
                "blocker": r["is_cross_stream_blocker"] == "yes",
                "estimate": float(r["estimate_days"]),
                "preds": [p for p in r["predecessors"].split(",") if p],
                "pred_confidence": r["pred_confidence"],
                "baseline_start": dt.date.fromisoformat(r["baseline_start"]) if r["baseline_start"] else None,
                "baseline_end": dt.date.fromisoformat(r["baseline_end"]) if r["baseline_end"] else None,
                "status": status, "pct": pct, "evidence": evidence,
                "actual_start": g.get("start"), "actual_end": g.get("end"),
            }

    order = schedule(tasks, cal, today)
    finish, chain = backward_pass(tasks, order, cal)

    done = len([t for t in tasks.values() if t["status"] == "done"])
    print("%d tasks · %d done · finish %s (day %d)"
          % (len(tasks), done, finish, cal.index(finish) + 1))
    for s in "ABCD":
        ts = [t for t in tasks.values() if t["stream"] == s]
        print("  %s %-10s %5.1fd effort, finishes %s"
              % (s, STREAM_META[s][1], sum(t["estimate"] for t in ts),
                 max(t["end"] for t in ts)))

    if args.dry_run:
        return

    write_csv(tasks, order)
    write_markdown(tasks, order, cal, today, finish, chain)
    write_html(tasks, cal, today, finish)
    path, _ = write_standup(tasks, cal, today, finish)
    print("wrote GANTT.md, gantt.html, tasks.csv, %s" % os.path.relpath(path, ROOT))


if __name__ == "__main__":
    main()
