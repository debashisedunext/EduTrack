#!/usr/bin/env bash
#
# Everything that can be checked about a branch without running the test suite.
#
# The suite is `make verify` and it takes minutes; these checks take a second
# and catch the mistakes that are expensive precisely because they are cheap to
# make — a task ID in the wrong place, a file from somebody else's stream, a
# credential staged by accident.
#
#     preflight.sh [STREAM]        default D
#
# Exit 0 = nothing to answer for. Exit 1 = at least one thing needs a decision.
# Nothing here is fatal on its own; several are judgement calls, and the script
# says which is which rather than pretending they are all the same.

set -uo pipefail
cd "$(git rev-parse --show-toplevel)"

STREAM="${1:-D}"
BASE="${BASE:-origin/develop}"
PROBLEMS=0

red()   { printf '   \033[31m✗\033[0m %s\n' "$1"; PROBLEMS=$((PROBLEMS + 1)); }
amber() { printf '   \033[33m?\033[0m %s\n' "$1"; PROBLEMS=$((PROBLEMS + 1)); }
green() { printf '   \033[32m✓\033[0m %s\n' "$1"; }
head_() { printf '\n\033[1m── %s\033[0m\n' "$1"; }

git fetch -q origin develop 2>/dev/null || true
BRANCH=$(git branch --show-current)
COMMITS=$(git log --format=%s "$BASE"..HEAD 2>/dev/null)
FILES=$(git diff --name-only "$BASE"...HEAD 2>/dev/null)

# ── the branch ───────────────────────────────────────────────────────────────
head_ "Branch"
if [ "$BRANCH" = develop ] || [ "$BRANCH" = main ]; then
    red "you are on $BRANCH — branch before committing anything"
elif ! printf '%s' "$BRANCH" | grep -qE '^(feat|fix|chore|docs|test|refactor)/(platform|masters|tickets|engines)/.+'; then
    amber "branch name '$BRANCH' is not <type>/<stream>/<slug>"
else
    green "$BRANCH"
fi

# ── the commit-subject convention ────────────────────────────────────────────
#
# This is the check that exists because the mistake has now been made four
# times. An ID in a SUBJECT that reaches develop marks the task DONE, and the
# plan cannot tell "the commit that mentions D-045" from "D-045 is finished".
head_ "Commit subjects"
if [ -z "$COMMITS" ]; then
    amber "no commits on top of $BASE"
else
    IDS=$(printf '%s\n' "$COMMITS" | grep -oE '[A-D]-[0-9]{3}' | sort -u || true)
    if [ -n "$IDS" ]; then
        printf '   \033[33m?\033[0m subject(s) carry: %s\n' "$(echo "$IDS" | tr '\n' ' ')"
        printf '     This marks each one DONE the moment it reaches develop.\n'
        printf '     Finishing them? Correct. Partial work? Move the ID to the commit BODY.\n'
        PROBLEMS=$((PROBLEMS + 1))
    else
        green "no task ID in a subject — nothing will be marked done"
    fi

    # Lowercase is invisible to the tracker: the pattern is [A-D]-\d{3}, case
    # sensitive. A-010, A-013 and A-030 all landed unattributed this way.
    LOWER=$(printf '%s\n' "$COMMITS" | grep -oE '\b[a-d]-[0-9]{3}\b' | sort -u || true)
    [ -n "$LOWER" ] && amber "lowercase ID(s) $(echo "$LOWER" | tr '\n' ' ') — the tracker's pattern is case-sensitive and will not match"

    # A-010/A-013 landed under a subject reading 'A011'. Git could prove nothing.
    NOHYPHEN=$(printf '%s\n' "$COMMITS" | grep -oE '\b[A-D][0-9]{3}\b' | sort -u || true)
    [ -n "$NOHYPHEN" ] && amber "missing hyphen in $(echo "$NOHYPHEN" | tr '\n' ' ') — the tracker needs A-011, not A011"

    if ! printf '%s\n' "$COMMITS" | grep -qE '^(feat|fix|chore|docs|test|refactor)(\([a-z-]+\))?: .'; then
        amber "no conventional-commit subject found (feat(engines): …)"
    fi
fi

# ── stream ownership ─────────────────────────────────────────────────────────
head_ "Paths touched (stream $STREAM)"
# TEAM-PLAN.md §6 draws a package map; this has to match paths on disk. The
# domain module mirrors the same feature split as the api module, and it is
# where most of a stream's real code lives — D-045 put ten of its files under
# domain/notifications/. Omitting it made this check flag nineteen of its own
# files as foreign, which is the CODEOWNERS mistake in a different costume:
# a rule that fires on everything gets ignored exactly like one that fires on
# nothing.
case "$STREAM" in
  # feature/fixtures/ sits with A: TEAM-PLAN §6 marks it "B, unowned until
  # A-040", and A-040 landed on 14 Aug.
  A) MINE='backend/common/|db/migration/|api/security/|api/arch/|(api|domain)/(feature/)?(auth|dashboard|reports|fixtures)/|domain/(appendonly|audit|identity|journal)/|frontend/src/features/(auth|dashboard|reports)/' ;;
  B) MINE='(api|domain)/(feature/)?(masters|clients|imports|workflow)/|frontend/src/features/(masters|clients)/' ;;
  C) MINE='(api|domain)/(feature/)?(tickets|transitions)/|frontend/src/(features/tickets|components/ui|components/ribbon|styles)/' ;;
  D) MINE='backend/worker/|(api|domain)/(feature/)?(notifications|chat|mail|outbox)/|api/realtime/|frontend/src/(features/(chat|notifications)|mocks)/|frontend/public/' ;;
  *) echo "unknown stream $STREAM"; exit 2 ;;
esac
# Shared surfaces nobody owns outright — a change here is normal but is worth
# naming in the PR, because it is what another stream will be asked to review.
SHARED='contracts/|docs/|\.github/|tools/|Makefile|\.claude/'
# Generated, and owned by nobody. Never hand-edited — but it MUST be committed
# when it changes, or the gate fails the batch on a stale client.
GENERATED='frontend/src/api/generated/'

GEN=$(printf '%s\n' "$FILES" | grep -E "$GENERATED" || true)
FOREIGN=$(printf '%s\n' "$FILES" | grep -vE "$MINE" | grep -vE "$SHARED" | grep -vE "$GENERATED" | grep -v '^$' || true)
if [ -n "$FOREIGN" ]; then
    amber "outside stream $STREAM — needs the owner's sign-off, said out loud, not quietly:"
    printf '       %s\n' $FOREIGN
else
    green "only stream $STREAM paths (and shared surfaces)"
fi
[ -n "$GEN" ] && green "$(printf '%s\n' "$GEN" | wc -l | tr -d ' ') generated client file(s) included — regenerated, not hand-edited, and committed"

# ── things that must never be committed ──────────────────────────────────────
head_ "Never-commit list"
JUNK=$(printf '%s\n' "$FILES" | grep -E '(^|/)(\.env|\.env\..*|application-local\.yml|\.DS_Store)$|(^|/)(target|node_modules|\.idea)/' || true)
if [ -n "$JUNK" ]; then
    red "these must not be committed:"
    printf '       %s\n' $JUNK
else
    green "no credentials, build output or editor state"
fi

# ── append-only ──────────────────────────────────────────────────────────────
#
# The guarantee that erodes first and is hardest to restore.
head_ "Append-only invariant"
ADDED=$(git diff "$BASE"...HEAD -- '*.java' 2>/dev/null | grep '^+' || true)
if printf '%s' "$ADDED" | grep -qiE '(ticket_?(history|effort_?logs|stage_?transitions)).*(update|delete)|@(Put|Patch|Delete)Mapping.*(history|effort-logs)'; then
    red "possible mutation of an append-only table — a correction is a new compensating row"
    printf '     If the design seems to need update() or delete() here, the design is wrong. Raise it.\n'
else
    green "no update/delete against the three protected tables"
fi

# ── migrations ───────────────────────────────────────────────────────────────
MIGRATIONS=$(printf '%s\n' "$FILES" | grep 'db/migration/' || true)
if [ -n "$MIGRATIONS" ]; then
    head_ "Migrations"
    EDITED=$(git diff --name-status "$BASE"...HEAD -- '**/db/migration/*.sql' | awk '$1 ~ /^(M|D|R)/ {print $2}')
    [ -n "$EDITED" ] && red "an applied migration was modified or deleted: $EDITED"
    BADNAME=$(printf '%s\n' "$MIGRATIONS" | grep -Ev '/V[0-9]{8}_[0-9]{4}__[a-z0-9_]+\.sql$' || true)
    [ -n "$BADNAME" ] && red "not V<YYYYMMDD>_<HHMM>__<snake_case>.sql: $BADNAME"
    if printf '%s' "$(cat $MIGRATIONS 2>/dev/null)" | grep -qiE 'tickets|ticket_history|ticket_effort_logs|ticket_stage_transitions'; then
        amber "touches a protected table — Stream A (Shivendra) must review"
    fi
    [ -z "$EDITED$BADNAME" ] && green "naming correct, nothing applied was edited"
fi

# ── rebase ───────────────────────────────────────────────────────────────────
head_ "Rebase"
BEHIND=$(git rev-list --count HEAD.."$BASE" 2>/dev/null || echo 0)
if [ "$BEHIND" != 0 ]; then
    amber "$BEHIND commit(s) behind $BASE — rebase (never merge develop into your branch)"
else
    green "up to date with $BASE"
fi

# ── generated client ─────────────────────────────────────────────────────────
if printf '%s\n' "$FILES" | grep -q 'contracts/openapi.yaml'; then
    head_ "Contract"
    printf '   \033[33m?\033[0m openapi.yaml changed — regenerate the client and commit it:\n'
    printf '     cd frontend && npm run api:generate\n'
    PROBLEMS=$((PROBLEMS + 1))
fi

# ── verdict ──────────────────────────────────────────────────────────────────
printf '\n────────────────────────────────────────────────────────\n'
if [ "$PROBLEMS" -eq 0 ]; then
    printf '\033[32mPreflight clean.\033[0m Run the tests covering what you touched, then open the PR as a draft.\n'
    exit 0
fi
printf '\033[33m%d item(s) need a decision.\033[0m ✗ must be fixed; ? is a judgement call — answer it, do not skip it.\n' "$PROBLEMS"
exit 1
