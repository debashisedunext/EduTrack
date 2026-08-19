#!/usr/bin/env bash
# Gathers the raw material for explaining a piece of EduTrack. It does not
# explain anything — it finds the files, their own recorded reasoning, their
# tests and the decisions in their history, so the explaining starts from
# evidence instead of from a guess.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT" || exit 1

BE=backend/api/src/main/java/com/edunext/edutrack/api/feature
FE=frontend/src/features

hdr() { printf '\n\033[1m%s\033[0m\n' "$*"; }

# The leading block comment of a file — where this codebase records *why*.
doc() {
  awk '/^[[:space:]]*\/\*\*/{f=1} f{print} /\*\//{if(f)exit}' "$1" 2>/dev/null \
    | sed 's/^[[:space:]]*\*\{0,2\}[[:space:]]\{0,1\}//' | grep -vE '^/?$|^\*/$' | head -"${2:-25}"
}

usage() {
  cat <<'EOF'
map.sh feature <name>    where a feature lives, front and back, plus its README
map.sh why <path>        a file's own recorded reasoning + the decisions in its history
map.sh route <fragment>  the controller, its DTOs and the contract entry for an endpoint
map.sh concept <name>    the four concepts newcomers get wrong, and where they are defined

  <name> examples: tickets, transitions, auth, clients, masters, chat, reports
EOF
}

case "${1:-}" in
feature)
  n="${2:?feature name required}"
  hdr "BACKEND  $BE/$n/"
  if [ -d "$BE/$n" ]; then
    ls -1 "$BE/$n" | sed 's/^/  /'
    [ -f "$BE/$n/README.md" ] && { hdr "README — read this first"; sed 's/^/  /' "$BE/$n/README.md" | head -40; }
  else
    echo "  no such package. Candidates:"; ls -1 "$BE" | sed 's/^/    /'
  fi
  hdr "FRONTEND  $FE/$n/"
  [ -d "$FE/$n" ] && find "$FE/$n" -type f \( -name '*.ts' -o -name '*.tsx' \) | sed 's/^/  /' | head -30 || echo "  (none)"
  hdr "TESTS"
  find backend -path "*feature/$n/*" -name '*Test.java' -o -path "*feature/$n/*" -name '*IT.java' 2>/dev/null | grep -v /target/ | sed 's/^/  /' | head -20
  hdr "WHO OWNS IT   (TEAM-PLAN.md §6)"
  grep -E "feature/$n/|features/$n" docs/TEAM-PLAN.md | head -4 | sed 's/^/  /'
  ;;
why)
  f="${2:?path required}"
  [ -f "$f" ] || { echo "no such file: $f"; exit 1; }
  hdr "WHAT THE FILE SAYS ABOUT ITSELF"
  doc "$f" 40 | sed 's/^/  /'
  hdr "DECISIONS IN ITS HISTORY   (commit bodies carry the reasoning here)"
  git log --format='  %h %ad %s' --date=short -8 -- "$f"
  hdr "RED-FLAG COMMENTS INSIDE IT   (🔴 marks a cross-stream trap)"
  grep -nE '🔴|⚠|DELIBERATELY|deliberate|NOT a|never' "$f" | head -12 | cut -c1-150 | sed 's/^/  /'
  ;;
route)
  q="${2:?path fragment required, e.g. effort-logs}"
  hdr "CONTROLLERS SERVING IT"
  grep -rln "$q" --include='*Controller.java' backend/api/src/main/java 2>/dev/null | sed 's/^/  /'
  hdr "MAPPINGS"
  grep -rnE "@(Get|Post|Put|Patch|Delete)Mapping|@RequestMapping" \
    $(grep -rln "$q" --include='*Controller.java' backend/api/src/main/java 2>/dev/null) 2>/dev/null \
    | grep -v import | cut -c1-160 | sed 's/^/  /' | head -12
  hdr "IN THE CONTRACT   (contracts/openapi.yaml is the source of truth for shape)"
  grep -n "$q" contracts/openapi.yaml | head -8 | cut -c1-160 | sed 's/^/  /'
  echo
  echo "  Bean Validation on the DTO *is* the schema (PLAN.md §2.2, deviation D-4)."
  echo "  A {ticketId} path segment is the ticket CODE (CRM-26-00347), not the row id."
  ;;
concept)
  hdr "THE FOUR THINGS PEOPLE GET WRONG   (GETTING-STARTED.md §5.3)"
  cat <<'EOF'
  1. iteration_no vs cycle_no — TWO INDEPENDENT COUNTERS. Iteration rises when a
     ticket goes backwards inside a cycle (a QA reject). Cycle rises when a CLOSED
     ticket is reopened. Blueprint §4A. The single most misunderstood thing here.

  2. stage vs status — SEPARATE LAYERS. A ticket can be In Progress in the QA
     stage. Conflating them corrupts the data model. Blueprint §3.

  3. append-only — ticket_history, ticket_effort_logs, ticket_stage_transitions
     are insert-only and hash-chained, enforced at FOUR layers: no service method,
     no route, MySQL grants, DB triggers. A correction is a NEW compensating row.
     Blueprint §4. The one permitted mutation is sealing a stage transition.

  4. row scoping — every ticket query is scoped server-side by ScopeResolver,
     never by a frontend filter. Out-of-scope IDs return 404, NOT 403, so no
     existence leaks. Blueprint §2.
EOF
  hdr "WHERE THEY LIVE IN CODE"
  for p in api/security/scope/ScopeResolver.java domain/journal/TicketJournal.java; do
    find backend -path "*$p" -not -path '*/target/*' | sed 's/^/  /'
  done
  find backend -name 'V*immutability_triggers.sql' -not -path '*/target/*' | sed 's/^/  /'
  ;;
*) usage ;;
esac
