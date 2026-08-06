#!/usr/bin/env python3
"""
Build (or refresh) `docs/plan/tasks.csv` from the four stream backlogs.

Titles, milestones and blocker flags come from `docs/streams/STREAM-*.md`, which
stay the human-readable source. Estimates and dependency edges come from
`seed.py` on first run only.

Re-running is safe and non-destructive: anything already in the CSV wins. New
task IDs in a stream file are appended; a task removed from a stream file is
kept in the CSV and flagged `dropped` rather than silently deleted, because a
row that vanishes takes its history with it.

    python3 tools/plan/extract_tasks.py
"""
import csv
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)
import seed  # noqa: E402

STREAMS = {
    "A": ("STREAM-A-PLATFORM.md", "Platform & Security", "Shivendra", "shivendraedunext-18"),
    "B": ("STREAM-B-MASTERS.md", "Masters & Clients", "Ayush", "Ayushedunext"),
    "C": ("STREAM-C-TICKETS.md", "Tickets & Ribbon", "Divyansh", "Divyanshedunext"),
    "D": ("STREAM-D-ENGINES.md", "Engines & Realtime", "Debashis", "debashisedunext"),
}

TASK_RE = re.compile(r"^- \[([ x])\] \*\*([A-D]-\d{3})\*\*\s*(.*)$")
HEAD_RE = re.compile(r"^## (.+)$")
SUBHEAD_RE = re.compile(r"^### (.+)$")
SCREEN_RE = re.compile(r"\*\*(S-\d{2})\*\*")
BLUEPRINT_RE = re.compile(r"§([\d.]+[A-Za-z]?[\d.]*)")

COLUMNS = [
    "id", "stream", "owner", "github", "milestone", "section", "title",
    "screen", "blueprint_ref", "is_cross_stream_blocker",
    "estimate_days", "predecessors", "pred_confidence",
    "baseline_start", "baseline_end",
    "forecast_start", "forecast_end",
    "status", "pct", "actual_start", "actual_end",
    "float_days", "is_critical", "evidence", "notes",
]


def clean_title(text):
    """Strip markdown emphasis and trailing screen/section refs from a title."""
    t = text
    t = re.sub(r"🔴\s*", "", t)
    t = re.sub(r"\*\*(S-\d{2})\*\*", "", t)
    t = re.sub(r"\*\*|`|\*", "", t)
    t = re.sub(r"\s+", " ", t).strip()
    # The first sentence is the task; the rest is rationale that belongs in the
    # stream file, not in a Gantt row.
    for stop in [". ", " — ", " – "]:
        if stop in t and len(t) > 90:
            t = t.split(stop)[0].strip()
            break
    return t[:120].rstrip(" .:—–")


def parse_stream(letter):
    fname, _, owner, gh = STREAMS[letter]
    path = os.path.join(ROOT, "docs", "streams", fname)
    milestone, section = "", ""
    found = []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            h = HEAD_RE.match(line)
            if h:
                milestone = h.group(1).replace("·", "—").strip()
                section = ""
                continue
            s = SUBHEAD_RE.match(line)
            if s:
                section = s.group(1).strip()
                continue
            m = TASK_RE.match(line)
            if not m:
                continue
            checked, tid, body = m.groups()
            screens = SCREEN_RE.findall(body)
            refs = BLUEPRINT_RE.findall(body)
            found.append({
                "id": tid,
                "stream": letter,
                "owner": owner,
                "github": gh,
                "milestone": milestone,
                "section": section,
                "title": clean_title(body),
                "screen": screens[0] if screens else "",
                "blueprint_ref": ("§" + refs[0]) if refs else "",
                "is_cross_stream_blocker": "yes" if "🔴" in body else "",
                "md_checked": checked == "x",
            })
    return found


def main():
    out_path = os.path.join(ROOT, "docs", "plan", "tasks.csv")
    existing = {}
    if os.path.exists(out_path):
        with open(out_path, newline="", encoding="utf-8") as fh:
            for row in csv.DictReader(fh):
                existing[row["id"]] = row

    seeds = seed.load()
    rows, seen = [], set()
    missing_seed = []

    for letter in "ABCD":
        for t in parse_stream(letter):
            seen.add(t["id"])
            prev = existing.get(t["id"], {})
            est, preds, conf = seeds.get(t["id"], (1.0, [], "inferred"))
            if t["id"] not in seeds:
                missing_seed.append(t["id"])
            row = {c: "" for c in COLUMNS}
            row.update({
                "id": t["id"], "stream": t["stream"], "owner": t["owner"],
                "github": t["github"], "milestone": t["milestone"],
                "section": t["section"], "title": t["title"],
                "screen": t["screen"], "blueprint_ref": t["blueprint_ref"],
                "is_cross_stream_blocker": t["is_cross_stream_blocker"],
                # Existing values win — the CSV is the source of truth once written.
                "estimate_days": prev.get("estimate_days") or f"{est:g}",
                "predecessors": prev.get("predecessors") or ",".join(preds),
                "pred_confidence": prev.get("pred_confidence") or conf,
                "baseline_start": prev.get("baseline_start", ""),
                "baseline_end": prev.get("baseline_end", ""),
                "notes": prev.get("notes", ""),
            })
            rows.append(row)

    dropped = [r for tid, r in existing.items() if tid not in seen]
    for r in dropped:
        r["notes"] = (r.get("notes", "") + " | dropped from stream file").strip(" |")
        rows.append({c: r.get(c, "") for c in COLUMNS})

    rows.sort(key=lambda r: r["id"])
    with open(out_path, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=COLUMNS, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)

    print("tasks.csv: %d rows (%d new, %d dropped)"
          % (len(rows), len(rows) - len(existing) - len(dropped), len(dropped)))
    if missing_seed:
        print("  no seed estimate, defaulted to 1.0d: " + ", ".join(missing_seed))
    unseeded = [s for s in seeds if s not in seen]
    if unseeded:
        print("  seeded but not in any stream file: " + ", ".join(unseeded))


if __name__ == "__main__":
    main()
