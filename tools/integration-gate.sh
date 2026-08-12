#!/usr/bin/env bash
#
# EduTrack — the integration gate.
#
# Runs, locally, every check .github/workflows/ci.yml runs. This exists because
# the GitHub Actions allowance was exhausted on 11 Aug 2026 and no job will run
# until the calendar month resets on 1 Sep. Merging on an unverified branch for
# three weeks is not an option on a codebase whose central guarantee is
# append-only hash-chained history, so the verification moved here.
#
# Run it on the MERGE RESULT, not on the branch:
#
#     git checkout develop && git pull
#     git merge --no-ff --no-commit <branch>
#     tools/integration-gate.sh
#
# A pull_request run in CI tests refs/pull/N/merge for the same reason — a
# branch that is green alone can still break develop.
#
# Exit 0 means every check passed and the merge is safe. Any non-zero means
# stop; the failing section is named.
#
# Retire this when CI is running again. It is a stand-in, not a second system:
# every check below is copied from ci.yml and must be kept in step with it.

set -uo pipefail
cd "$(dirname "$0")/.."

FAILED=()
UNREPRODUCIBLE=()
step() { printf '\n\033[1m── %s\033[0m\n' "$1"; }
ok()   { printf '   \033[32m✓\033[0m %s\n' "$1"; }
bad()  { printf '   \033[31m✗ %s\033[0m\n' "$1"; FAILED+=("$1"); }
note() { printf '   \033[33m·\033[0m %s\n' "$1"; }

# ── nothing else may be building while this runs ─────────────────────────────
#
# On 12 Aug this gate produced two failures that could not be reproduced a
# minute later on untouched files — a jar reported as missing its frontend, and
# six Spring contexts reported as unable to find a datasource driver. What both
# runs had in common was another Maven process writing into the same
# backend/*/target while they read from it.
#
# That is worth refusing outright rather than tolerating. A gate that reports
# failures nobody can reproduce teaches everyone to re-run until green, which is
# precisely how a real failure gets waved through.
LOCK="${TMPDIR:-/tmp}/edutrack-integration-gate.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    HOLDER=$(cat "$LOCK/pid" 2>/dev/null || echo "unknown")
    if [ "$HOLDER" != unknown ] && ! kill -0 "$HOLDER" 2>/dev/null; then
        # The holder died without cleaning up — its lock is meaningless.
        note "clearing a stale lock from pid $HOLDER"
        rm -rf "$LOCK" && mkdir "$LOCK"
    else
        printf '\033[31mAnother integration gate is already running (pid %s).\033[0m\n' "$HOLDER"
        printf 'Two gates sharing one target/ is what produced this gate’s only false failures.\n'
        exit 2
    fi
fi
echo $$ > "$LOCK/pid"
trap 'rm -rf "$LOCK"' EXIT INT TERM

# A lock only stops two gates. A developer running `mvn` in another terminal, or
# a backgrounded build left over from earlier, writes into the same target/ and
# this cannot lock that — so it looks, before starting anything of its own.
if OTHERS=$(pgrep -fl "MavenWrapperMain|maven.*edutrack" 2>/dev/null | grep -v "^$$ " || true); [ -n "${OTHERS:-}" ]; then
    printf '\033[31mA Maven process is already running against this repository:\033[0m\n'
    printf '  %s\n' "$OTHERS"
    printf 'Let it finish first — concurrent writes to target/ make this gate lie.\n'
    exit 2
fi

# ── run a check, and do not report a failure this cannot reproduce ───────────
#
# A first failure is a question, not an answer. Anything that fails here is
# retried once from a clean tree; only a failure that survives that is reported.
# One that does not survive is surfaced loudly and separately — it does not
# block the batch, because blocking on an unreproducible failure is the same
# cry-wolf problem from the other direction, but it is never printed as if
# everything were fine.
attempt() {
    local label="$1" fn="$2"
    if "$fn"; then ok "$label"; return 0; fi

    note "$label failed — cleaning and retrying once before reporting it"
    (cd backend && ./mvnw -q clean) >/dev/null 2>&1

    if "$fn"; then
        UNREPRODUCIBLE+=("$label")
        note "$label PASSED on a clean retry — the first failure did not reproduce"
        return 0
    fi
    bad "$label"
    return 1
}

# The jar smoke test and Maven need Java 25. In VS Code-spawned shells JAVA_HOME
# is 25 but PATH still resolves `java` to 21, which fails with a class-file
# version error that reads like a project bug and is not one.
if JH=$(/usr/libexec/java_home -v 25 2>/dev/null); then
    export JAVA_HOME="$JH"
    export PATH="$JAVA_HOME/bin:$PATH"
fi
printf 'java: %s\n' "$(java -version 2>&1 | head -1)"

# ── migration guard ──────────────────────────────────────────────────────────
# ci.yml runs this against origin/<base>...HEAD on a PR. Here the merge is
# already staged, so the comparison is against origin/develop.
step "Migration guard"
BASE=origin/develop
CHANGED=$(git diff --name-status "$BASE"...HEAD -- '**/db/migration/*.sql' 2>/dev/null \
          | awk '$1 ~ /^(M|D|R)/ {print $2}')
if [ -n "$CHANGED" ]; then
    bad "An applied migration was modified or deleted: $CHANGED"
else
    ok "No applied migration was altered"
fi

BADNAME=$(git diff --name-only --diff-filter=A "$BASE"...HEAD -- '**/db/migration/*.sql' 2>/dev/null \
          | grep -Ev '/V[0-9]{8}_[0-9]{4}__[a-z0-9_]+\.sql$' || true)
if [ -n "$BADNAME" ]; then
    bad "Migration name is not V<YYYYMMDD>_<HHMM>__<snake_case>.sql: $BADNAME"
else
    ok "Migration naming is correct"
fi

# ── backend ──────────────────────────────────────────────────────────────────
backend_check() { (cd backend && ./mvnw -B verify -DskipFrontend); }

step "Backend — build & test"
attempt "Backend build & tests" backend_check

# ── frontend ─────────────────────────────────────────────────────────────────
step "Frontend — lint, test & build"
(cd frontend && npm ci --silent) || bad "npm ci failed"
(cd frontend && npm run lint)          && ok "lint"  || bad "Frontend lint failed"
(cd frontend && npm run test -- --run) && ok "tests" || bad "Frontend tests failed"
(cd frontend && npm run build)         && ok "build" || bad "Frontend build failed"

# ── contract ─────────────────────────────────────────────────────────────────
step "OpenAPI contract — validity, conventions, staleness"
npx --yes @redocly/cli@latest lint contracts/openapi.yaml \
    && ok "valid OpenAPI" || bad "OpenAPI document is invalid"

python3 contracts/check-conventions.py \
    && ok "conventions" || bad "API conventions violated"

(cd frontend && npm run api:generate >/dev/null 2>&1) || bad "Client generation failed"
if [ -n "$(git status --porcelain frontend/src/api/generated)" ]; then
    bad "Generated API client is stale — run 'npm run api:generate' and commit"
    git --no-pager diff --stat frontend/src/api/generated
else
    ok "generated client is up to date"
fi

(cd frontend && npx tsc --noEmit -p tsconfig.app.json) \
    && ok "generated client type-checks" || bad "Generated client does not compile"

# ── package ──────────────────────────────────────────────────────────────────
# The check that caught the fixture leak. Inspecting the artifact is not the
# same as running it: a jar can pass `unzip -l` and die during context refresh.
package_check() {
if (cd backend && ./mvnw -B package -DskipTests -q); then
    JAR=$(ls backend/api/target/edutrack-api-*.jar 2>/dev/null | head -1)

    # The listing is read ONCE into a variable, and the archive's integrity is
    # checked before anything is concluded from its contents.
    #
    # The first version piped `unzip -l` straight into `grep -q` per check, and
    # on its first real run reported "Frontend missing" against a jar that
    # demonstrably contained it — the same command passed seconds later with the
    # file untouched. A zip's central directory is written last, so listing one
    # mid-write yields a short or empty listing rather than an error, and a
    # gate that reports a false failure is worse than no gate: it teaches
    # everyone to re-run until green, which is how a true failure gets waved
    # through. `unzip -t` fails loudly on an incomplete archive instead.
    if [ -z "$JAR" ]; then
        note "no jar was produced"
        return 1
    elif ! unzip -tqq "$JAR" >/dev/null 2>&1; then
        note "jar is not a readable archive — the build may not have finished writing it"
        return 1
    elif ! LISTING=$(unzip -l "$JAR" 2>/dev/null) || [ -z "$LISTING" ]; then
        note "could not list the jar's contents"
        return 1
    elif ! printf '%s' "$LISTING" | grep -q 'BOOT-INF/classes/static/index.html'; then
        note "frontend missing from the jar — the app would serve no UI"
        # Evidence, so the next person does not have to reproduce it to find out
        # whether the jar or the check was wrong.
        printf '%s\n' "$LISTING" | grep 'BOOT-INF/classes/static' | head -5
        printf '   (%s static entries found)\n' \
            "$(printf '%s' "$LISTING" | grep -c 'BOOT-INF/classes/static/')"
        return 1
    else
        printf '     jar contains the built frontend\n'

        # Flyway alone is excluded: it connects unconditionally. JPA stays on,
        # with the dialect named and the metadata lookup refused, so it builds
        # offline — see the long comment in ci.yml for why excluding
        # repositories made this check assert a context nobody runs.
        LOG=$(mktemp)
        java -jar "$JAR" \
            --spring.profiles.active=local \
            --spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration \
            --spring.jpa.hibernate.ddl-auto=none \
            --spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect \
            --spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
            --server.port=8099 \
            --management.health.redis.enabled=false \
            --management.health.mail.enabled=false > "$LOG" 2>&1 &
        APP=$!

        STARTED=no
        for _ in $(seq 1 60); do
            curl -sf http://localhost:8099/ >/dev/null 2>&1 && { STARTED=yes; break; }
            kill -0 $APP 2>/dev/null || break
            sleep 1
        done
        kill $APP 2>/dev/null; wait $APP 2>/dev/null

        if [ "$STARTED" = yes ]; then
            printf '     jar starts and serves a request\n'
            rm -f "$LOG"
            return 0
        fi
        note "packaged jar failed to start"
        grep -A6 "APPLICATION FAILED" "$LOG" | head -12
        rm -f "$LOG"
        return 1
    fi
else
    note "packaging failed"
    return 1
fi
}

step "Package — single deployable jar"
attempt "Packaged jar builds, contains the frontend and starts" package_check

# ── verdict ──────────────────────────────────────────────────────────────────
printf '\n────────────────────────────────────────────────────────\n'

# Reported whether the gate passed or failed. A check that failed once and
# passed on a clean retry is not a pass — it is something nobody has explained,
# and the two occasions this gate has produced one, the explanation mattered.
# Printing it only on failure would hide exactly the cases worth chasing.
if [ ${#UNREPRODUCIBLE[@]} -ne 0 ]; then
    printf '\033[33mNOT REPRODUCIBLE\033[0m — %d check(s) failed once, then passed from a clean tree:\n' \
        "${#UNREPRODUCIBLE[@]}"
    printf '  · %s\n' "${UNREPRODUCIBLE[@]}"
    printf '  These did not block the batch. They are worth understanding before\n'
    printf '  they are assumed to be noise — see the lock notes at the top.\n\n'
fi

if [ ${#FAILED[@]} -eq 0 ]; then
    printf '\033[32mGATE PASSED\033[0m — safe to merge.\n'
    exit 0
fi
printf '\033[31mGATE FAILED\033[0m — %d check(s), each confirmed by a second run. Do not merge:\n' \
    "${#FAILED[@]}"
printf '  · %s\n' "${FAILED[@]}"
exit 1
