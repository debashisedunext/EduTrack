"""
Seed estimates and dependency edges for the EduTrack task ledger.

This file is read ONCE, by `extract_tasks.py`, to build `docs/plan/tasks.csv`.
After that the CSV is the source of truth — re-running the extractor preserves
whatever is already in it and only appends genuinely new task IDs. Edit the CSV
to change an estimate; edit this file only when seeding a fresh ledger.

Format, one task per line:

    ID | estimate_days | predecessor,predecessor,... | confidence

`confidence` is `c` (confirmed — from DEPENDENCIES.md §2 or an explicit note in
the stream file) or `i` (inferred — I derived it from the ordering and the task
text). Inferred edges are what the four developers correct in the first review
pass; until they do, the critical path through them is a hypothesis.

Estimates are in *working days of one developer*, half-day granularity.
They include writing the tests, not just the code.
"""

SEED = """
# ── Stream A · Platform & Security · Shivendra ────────────────────────────────
A-001 | 1.0 |                     | c
A-002 | 1.0 | A-001               | c
A-003 | 1.0 | A-002               | c
A-004 | 1.5 | A-003               | c
A-005 | 1.0 | A-004               | c
A-007 | 1.5 | A-005               | c
A-006 | 1.5 | A-007               | c
A-008 | 1.0 | A-004,A-005         | c
A-009 | 0.5 | A-006               | c
A-010 | 0.5 | A-009               | i
A-011 | 1.5 | A-001               | c
A-012 | 1.5 | A-003               | c
A-013 | 1.0 | A-008               | c
A-020 | 1.5 | A-003,A-012         | i
A-021 | 0.5 | A-020               | i
A-022 | 1.0 | A-020               | i
A-023 | 1.0 | A-022               | i
A-024 | 1.5 | A-023               | c
A-025 | 1.0 | A-023               | i
A-026 | 0.5 | A-020               | i
A-027 | 1.0 | A-020               | i
A-028 | 1.0 | A-020               | i
A-029 | 1.5 | A-022               | i
A-030 | 1.5 | A-020,C-003         | c
A-031 | 0.5 | A-030,B-001         | c
A-032 | 1.0 | A-022               | c
A-033 | 1.5 | A-032,B-001         | c
A-034 | 2.0 | A-033               | c
A-035 | 0.5 | A-034               | c
A-036 | 2.0 | A-035               | c
A-037 | 1.0 | A-034               | i
A-040 | 1.5 | A-010,A-013         | c
A-041 | 1.5 | A-040               | c
A-042 | 2.5 | A-041               | c
A-043 | 1.0 | A-042               | c
A-044 | 2.0 | A-042               | c
A-045 | 1.5 | A-042               | c
A-050 | 1.5 | A-034,B-007         | i
A-051 | 1.0 | A-050               | c
A-052 | 1.0 | A-034               | i
A-053 | 1.5 | A-052,C-003         | i
A-054 | 1.5 | A-051,C-005         | i
A-055 | 2.0 | A-054               | i
A-056 | 3.0 | A-055               | i
A-057 | 2.0 | A-056               | i
A-058 | 2.5 | A-056,C-049         | c
A-059 | 1.0 | A-056,B-029         | c
A-060 | 1.5 | A-056,C-014         | i
A-061 | 1.5 | A-060               | i
A-062 | 1.5 | A-055               | i
A-063 | 2.0 | A-051,C-003         | i
A-064 | 2.0 | A-063               | i
A-065 | 1.0 | A-064,D-029         | c
A-066 | 3.0 | A-063               | i
A-067 | 3.0 | A-066               | i
A-068 | 3.0 | A-067,C-058         | c
A-069 | 1.5 | A-066,B-010         | c
A-070 | 1.0 | A-066,D-028         | c
A-071 | 1.5 | A-034               | i
A-072 | 1.5 | A-009,C-005         | i
A-073 | 2.0 | A-053,B-007         | i
A-074 | 2.0 | A-036               | i
A-075 | 2.0 | A-073,A-074         | i

# ── Stream B · Masters & Clients · Ayush ──────────────────────────────────────
B-001 | 1.0 | A-003               | c
B-002 | 1.0 | A-007               | c
B-003 | 1.0 | A-007               | c
B-004 | 1.0 | A-005               | c
B-005 | 3.0 | A-006,A-007         | c
B-006 | 0.5 | B-005               | i
B-007 | 2.0 | B-004,B-005         | c
B-008 | 0.5 | B-001,B-002,B-003,B-004 | i
B-010 | 2.0 | B-005,C-003,A-012   | c
B-011 | 2.5 | B-010               | i
B-012 | 1.0 | B-011               | i
B-013 | 1.0 | B-011               | i
B-014 | 1.0 | B-011               | i
B-015 | 2.0 | B-001,C-003         | i
B-016 | 2.0 | B-005,C-003         | i
B-017 | 1.5 | B-016               | i
B-018 | 1.5 | B-016               | i
B-019 | 1.5 | B-016               | i
B-020 | 1.5 | B-002,C-003         | i
B-021 | 1.5 | B-002,C-003         | c
B-022 | 2.0 | B-005,C-003         | c
B-023 | 2.0 | B-005,C-003         | c
B-024 | 3.0 | B-023               | c
B-025 | 2.0 | B-005,C-003         | i
B-026 | 3.0 | B-025               | i
B-027 | 1.5 | B-026               | i
B-028 | 1.0 | B-027               | c
B-029 | 1.0 | B-026               | i
B-030 | 2.0 | B-005               | c
B-031 | 1.5 | B-030               | i
B-032 | 2.0 | B-030               | i
B-033 | 2.0 | B-032               | i
B-034 | 2.5 | B-033               | c
B-035 | 2.0 | B-034               | c
B-036 | 1.0 | B-034               | i
B-037 | 1.0 | B-035               | i
B-038 | 1.0 | B-035               | c
B-039 | 2.0 | B-003,C-003         | i
B-040 | 2.0 | B-039               | c
B-041 | 2.5 | B-040,B-050         | c
B-042 | 1.0 | B-040               | c
B-043 | 3.0 | B-041,B-042         | c
B-050 | 2.5 | C-003,C-042         | c
B-051 | 1.0 | B-050               | i
B-052 | 1.5 | B-050               | i
B-053 | 2.0 | B-050               | i
B-060 | 2.0 | A-064,B-029         | c
B-061 | 2.0 | A-064,B-010         | c
B-062 | 1.5 | A-064               | c
B-063 | 2.0 | A-064,C-061         | c

# ── Stream C · Tickets & Ribbon · Divyansh ────────────────────────────────────
C-001 | 1.0 |                     | c
C-002 | 1.5 | C-001               | c
C-003 | 3.0 | C-002               | c
C-004 | 1.5 | C-003               | c
C-005 | 2.0 | C-003               | c
C-006 | 1.0 | C-005               | i
C-010 | 2.5 | C-005,D-004         | c
C-011 | 1.0 | A-003,A-012         | c
C-012 | 1.5 | C-011,B-024         | c
C-013 | 1.5 | C-010,C-011         | i
C-014 | 3.0 | C-005,D-004         | c
C-015 | 1.0 | C-014               | i
C-016 | 0.5 | C-014               | i
C-017 | 1.5 | C-014,A-034         | c
C-018 | 2.5 | C-014               | i
C-019 | 2.0 | C-005,D-004         | c
C-020 | 1.5 | C-019,B-021         | c
C-021 | 2.0 | C-019,B-028         | c
C-022 | 0.5 | C-021               | i
C-023 | 1.5 | C-019               | i
C-024 | 1.0 | C-023               | i
C-025 | 2.0 | C-023               | i
C-026 | 1.5 | C-025               | i
C-027 | 0.5 | C-025               | i
C-028 | 1.0 | C-025               | i
C-029 | 1.5 | C-019               | i
C-030 | 1.5 | C-029               | i
C-031 | 1.0 | C-029               | i
C-032 | 1.0 | C-029,C-042         | c
C-033 | 1.5 | C-029               | i
C-034 | 1.5 | C-029,C-059         | c
C-035 | 1.5 | C-019,A-040         | c
C-036 | 2.5 | C-018,C-035         | i
C-037 | 0.5 | C-036               | i
C-038 | 2.5 | C-013,A-040         | c
C-039 | 1.5 | C-038               | i
C-040 | 1.5 | C-038               | i
C-041 | 1.0 | C-035               | i
C-042 | 2.5 | A-042,B-040         | c
C-043 | 1.5 | C-042,A-033         | c
C-044 | 2.5 | C-042,C-035         | c
C-045 | 2.0 | C-044,D-014         | c
C-046 | 1.5 | C-042               | i
C-047 | 1.0 | C-042               | i
C-048 | 1.0 | C-042               | i
C-049 | 1.5 | C-042               | c
C-050 | 1.0 | C-044               | i
C-051 | 3.0 | B-050               | c
C-052 | 2.0 | C-051               | i
C-053 | 1.5 | C-051,C-038         | c
C-054 | 1.0 | C-051,C-046         | c
C-055 | 2.0 | C-042,B-024         | c
C-056 | 1.5 | C-055               | c
C-057 | 1.5 | C-056               | i
C-058 | 1.0 | C-055               | c
C-059 | 1.5 | C-019,A-040         | c
C-060 | 1.0 | C-026               | i
C-061 | 1.0 | C-041               | i
C-062 | 2.0 | C-014,C-042         | c
C-063 | 2.0 | C-017,B-014         | c
C-064 | 1.5 | C-019               | i

# ── Stream D · Engines & Realtime · Debashis ──────────────────────────────────
D-001 | 3.0 |                     | c
D-002 | 1.0 | D-001               | c
D-003 | 2.0 | D-002               | c
D-004 | 2.5 | D-002               | c
D-005 | 1.0 | D-003               | c
D-010 | 2.5 | A-006,A-012         | c
D-011 | 1.0 | D-010               | i
D-012 | 2.0 | A-012               | i
D-013 | 2.0 | D-012,A-034         | c
D-014 | 1.0 | D-012               | c
D-015 | 1.5 | D-014,C-005         | c
D-020 | 2.0 | D-011,B-024,A-009   | c
D-021 | 1.0 | D-020               | i
D-022 | 1.0 | D-020               | i
D-023 | 2.0 | D-020,C-042         | c
D-024 | 1.5 | D-020,B-018         | c
D-025 | 0.5 | D-023               | i
D-026 | 1.0 | D-020               | i
D-027 | 1.0 | D-020               | c
D-028 | 1.0 | D-020               | i
D-029 | 1.5 | D-010,B-022         | c
D-030 | 1.5 | D-029               | i
D-031 | 0.5 | D-029               | i
D-032 | 1.5 | D-031               | c
D-033 | 1.0 | D-010               | c
D-034 | 1.0 | D-033               | i
D-035 | 1.0 | D-033               | i
D-036 | 1.0 | D-029               | c
D-037 | 2.0 | D-030,D-036         | i
D-038 | 1.5 | D-037               | i
D-039 | 2.0 | D-032               | i
D-040 | 2.0 | D-012,B-022         | c
D-041 | 2.5 | D-040,C-005         | c
D-042 | 1.5 | D-041               | i
D-043 | 1.0 | D-041,D-014         | i
D-044 | 0.5 | D-041               | i
D-045 | 1.5 | D-043               | i
D-046 | 1.5 | D-043               | c
D-050 | 3.0 | D-014,C-005         | c
D-051 | 2.0 | D-050               | i
D-052 | 1.5 | D-050               | i
D-053 | 2.0 | D-050               | i
D-054 | 1.0 | D-050               | i
D-055 | 1.5 | D-050,C-036         | c
D-056 | 1.0 | D-055               | i
D-057 | 1.5 | D-050               | c
D-058 | 1.0 | D-014,C-045         | c
D-059 | 1.0 | D-058,C-062         | c
"""


def load():
    """Return {task_id: (estimate_days, [predecessors], confidence)}."""
    out = {}
    for raw in SEED.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        tid, est, preds, conf = [p.strip() for p in line.split("|")]
        out[tid] = (
            float(est),
            [p for p in preds.split(",") if p],
            "confirmed" if conf == "c" else "inferred",
        )
    return out
