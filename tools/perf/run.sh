#!/usr/bin/env bash
# =====================================================================
# A-073 · run one of the k6 load tests
#
#   tools/perf/run.sh dashboard
#   tools/perf/run.sh tickets
#   tools/perf/run.sh first-paint
#   tools/perf/run.sh both        # dashboard + tickets (not first-paint)
#
# WHY THIS RUNS k6 IN DOCKER RATHER THAN ASKING YOU TO INSTALL IT
#
# PLAN.md §8 names k6 and nothing in the repo had ever installed it.
# Adding a toolchain dependency to a hardening task is the kind of thing
# that gets skipped on the machine that most needs to run it — the same
# failure tools/integration-gate.sh had, where the verification nobody
# could afford was the verification nobody performed. Docker is already
# required by docker-compose.yml, so `docker run --rm grafana/k6` costs a
# one-time image pull and nothing after that.
#
# HOST NETWORKING
#
# The container reaches the API on the host through host.docker.internal,
# which Docker Desktop provides on Windows and macOS. On Linux, pass
# --add-host (handled below) since it is not resolvable by default there.
#
# BEFORE YOU RUN IT
#
#   1. docker compose up -d
#   2. the API up on 8080 with `local,dev-noauth`
#   3. the corpus seeded:
#        docker exec -i edutrack-mysql mysql -uroot -prootpw edutrack \
#          < tools/perf/seed-50k.sql
#
# Skipping (3) does not fail the run, it makes it meaningless — every
# threshold passes comfortably against 200 rows. The scripts check that
# responses are non-empty, which catches an empty database but not a
# small one, so this is on you.
# =====================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="${1:-both}"
BASE_URL="${BASE_URL:-http://host.docker.internal:8080}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:latest}"
# A-073's browser half needs Chromium, which the image above does not carry.
# Only the first-paint target pays for it — it is several hundred MB, and
# making the two HTTP scripts pull it would tax the common case for the rare
# one. Same one-time-pull reasoning as the comment above.
K6_BROWSER_IMAGE="${K6_BROWSER_IMAGE:-grafana/k6:master-with-browser}"
OUT_DIR="${OUT_DIR:-$SCRIPT_DIR/results}"

mkdir -p "$OUT_DIR"

extra_args=()
if [[ "$(uname -s)" == "Linux" ]]; then
  extra_args+=(--add-host=host.docker.internal:host-gateway)
fi

run_one() {
  local name="$1" file="$2"
  echo
  echo "=== k6: $name ============================================"
  # The script arrives on stdin rather than as a mounted volume: a bind
  # mount of a Windows path into a Linux container is the one part of
  # this that reliably differs between machines, and `run -` sidesteps it
  # entirely. The cost is that k6 cannot import a second local module,
  # which is why both scripts are self-contained.
  docker run --rm -i \
    "${extra_args[@]}" \
    -e BASE_URL="$BASE_URL" \
    -e TOKEN="${TOKEN:-}" \
    "$K6_IMAGE" run \
      --summary-export="/dev/stdout" \
      - < "$file" | tee "$OUT_DIR/$name.txt"
}

# The browser target. Chromium in a container needs --no-sandbox: it drops
# privileges into a namespace the container does not grant, and the failure
# is a browser that never launches rather than a test that fails. Given more
# memory than the default 64 MB /dev/shm too — Chromium uses it for renderer
# surfaces and crashes mid-navigation without it, which presents as a flaky
# measurement rather than as an out-of-space error.
run_browser() {
  local name="$1" file="$2"
  echo
  echo "=== k6 browser: $name ====================================="
  docker run --rm -i \
    "${extra_args[@]}" \
    --shm-size=1g \
    -e BASE_URL="$BASE_URL" \
    -e K6_BROWSER_HEADLESS=true \
    -e K6_BROWSER_ARGS="no-sandbox,disable-dev-shm-usage" \
    "$K6_BROWSER_IMAGE" run \
      --summary-export="/dev/stdout" \
      - < "$file" | tee "$OUT_DIR/$name.txt"
}

case "$TARGET" in
  dashboard) run_one dashboard "$SCRIPT_DIR/k6/dashboard.js" ;;
  first-paint) run_browser first-paint "$SCRIPT_DIR/first-paint.js" ;;
  tickets)   run_one tickets   "$SCRIPT_DIR/k6/tickets-list.js" ;;
  both)
    run_one dashboard "$SCRIPT_DIR/k6/dashboard.js"
    run_one tickets   "$SCRIPT_DIR/k6/tickets-list.js"
    ;;
  *)
    echo "usage: $0 [dashboard|tickets|first-paint|both]" >&2
    exit 2
    ;;
esac

echo
echo "Results written to $OUT_DIR/"
