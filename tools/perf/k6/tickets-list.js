// =====================================================================
// A-073 · ticket-list load test
//
// The other half of PLAN.md §8's "dashboard and ticket-list p95 on a
// 50,000-ticket dataset". This is the query the index review found was a
// full table scan and a filesort for the default sort — see
// V20260820_0445__ticket_list_sort_indexes.sql — so this script is also
// the regression guard for those three indexes. Run it against a
// database without the migration and the deep-page scenario is what goes
// red first.
//
// WHAT THE SCENARIOS ARE FOR
//
//   first_page   what everyone does: open the list, take the default
//                sort, read the top of it.
//   filtered     the list with a status/level filter, which is the
//                second thing everyone does and which uses a completely
//                different index from the first.
//   deep_page    page 40 via the A-053 keyset cursor. This is the one
//                that distinguishes a working index from a working
//                fixture: with ix_tickets_created it is a range scan
//                whose cost does not grow with depth, and without it
//                every page repeats the whole 50,000-row sort. At 200
//                fixture rows the two are indistinguishable, which is
//                exactly why this needs the corpus.
//   drill_down   the reported-date window every S-05 widget emits
//                (A-060). Kept as its own scenario because it is the
//                query ix_tickets_created ALONE makes three times worse
//                than no index at all — if ix_tickets_reported is ever
//                dropped as redundant, this scenario is what says no.
//
// RUN
//   tools/perf/run.sh tickets
// =====================================================================

import http from 'k6/http'
import { check, sleep } from 'k6'
import { Trend } from 'k6/metrics'

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080'
const TOKEN = __ENV.TOKEN || ''

// THINK TIME, AND WHY THE FIRST VERSION OF THIS FILE WAS WRONG WITHOUT IT
//
// Originally every scenario looped with no pause, so each VU issued
// requests back to back. 25 VUs then produced ~35 req/s — the load of
// roughly 250 people, not 25 — and the p95 that came out (1.5 s) was a
// queueing figure at saturation, not anything a user would experience.
// Reported as-is it would have looked like the ticket list failing at
// 25 users, which is false and would have sent someone optimising a
// non-problem.
//
// A user opens the list, reads it, and clicks something several seconds
// later. SLEEP models that, so VU count means "people" and the p95 means
// "what a person waits".
//
// To measure the CEILING instead — the capacity question, "how many
// requests per second before it falls over" — set THINK=0. The README
// reports both, because they answer different questions and only one of
// them is a user-facing promise.
const THINK = Number(__ENV.THINK ?? 8)

function think() {
  if (THINK > 0) {
    // Jittered, never a fixed interval: 40 VUs sleeping exactly 8 s march
    // in lockstep and arrive as a burst every 8 s, which measures a
    // thundering herd of our own construction.
    sleep(THINK * (0.5 + Math.random()))
  }
}

const firstPage = new Trend('list_first_page_ms', true)
const filtered = new Trend('list_filtered_ms', true)
const deepPage = new Trend('list_deep_page_ms', true)
const drillDown = new Trend('list_drill_down_ms', true)

// VU counts are PEOPLE, given the think time above. The blueprint's org is
// ~40 staff across 6 roles, so 45 concurrent users spread over the four
// things they do to a ticket list is a busy hour, not a quiet one.
export const options = {
  scenarios: {
    first_page: { executor: 'constant-vus', vus: 20, duration: '60s', exec: 'firstPageScenario' },
    filtered: { executor: 'constant-vus', vus: 10, duration: '60s', exec: 'filteredScenario' },
    deep_page: { executor: 'constant-vus', vus: 5, duration: '60s', exec: 'deepPageScenario' },
    drill_down: { executor: 'constant-vus', vus: 10, duration: '60s', exec: 'drillDownScenario' },
  },
  thresholds: {
    // 300 ms rather than the dashboard's 500: the list is one query
    // returning one page, where the dashboard is eleven calls. A list
    // that needs half a second at 50,000 rows will not survive 200,000.
    list_first_page_ms: ['p(95)<300'],
    list_filtered_ms: ['p(95)<300'],
    // Deep paging gets the same budget as the first page ON PURPOSE.
    // That equality IS the assertion — keyset pagination's whole claim is
    // that page 40 costs what page 1 costs, and a laxer threshold here
    // would let the property A-053 exists to provide quietly lapse.
    list_deep_page_ms: ['p(95)<300'],
    list_drill_down_ms: ['p(95)<300'],
    http_req_failed: ['rate<0.01'],
  },
}

function params() {
  return { headers: TOKEN ? { Authorization: `Bearer ${TOKEN}` } : {} }
}

function checked(res, name, trend) {
  trend.add(res.timings.duration)
  check(res, {
    [`${name} 200`]: (r) => r.status === 200,
    // A page that came back empty would be fast and meaningless. The
    // corpus guarantees 50,000 rows, so an empty first page means the
    // seeder did not run and the whole run should be disbelieved rather
    // than celebrated.
    [`${name} non-empty`]: (r) => r.body && r.body.length > 100,
  })
}

export function firstPageScenario() {
  checked(http.get(`${BASE}/api/v1/tickets?limit=50`, params()), 'first page', firstPage)
  think()
}

export function filteredScenario() {
  // Two filters that resolve through different indexes:
  // status+level should stay on ix_tickets_project_status-shaped access,
  // while assignee uses ix_tickets_assignee_status. Alternating means one
  // fast path cannot carry the other's p95.
  const q = __ITER % 2 === 0
    ? 'status=IN_PROGRESS&level=CRITICAL'
    : 'assigneeId=5&excludeClosed=true'
  checked(http.get(`${BASE}/api/v1/tickets?limit=50&${q}`, params()), 'filtered', filtered)
  think()
}

export function deepPageScenario() {
  // Walk forward through pages using the cursor the API returns, rather
  // than a hardcoded cursor: a fabricated cursor would test the parser,
  // not the paging. Every VU restarts from page 1 when it runs out,
  // so the scenario keeps measuring depth rather than drifting off the
  // end of the table.
  let url = `${BASE}/api/v1/tickets?limit=50`
  for (let page = 0; page < 40; page++) {
    const res = http.get(url, params())
    if (res.status !== 200) {
      checked(res, 'deep page', deepPage)
      return
    }
    let next = null
    try {
      next = JSON.parse(res.body)?.meta?.nextCursor ?? null
    } catch (e) {
      next = null
    }
    if (!next) break
    url = `${BASE}/api/v1/tickets?limit=50&cursor=${encodeURIComponent(next)}`
    // Only the pages past the shallow ones are recorded. Including page 1
    // in this trend would dilute exactly the signal the scenario exists
    // to produce.
    if (page >= 30) {
      checked(res, 'deep page', deepPage)
    }
    // Paging is a person clicking "next", so it gets think time too —
    // but a shorter one, because scanning a page you have already got the
    // shape of is quicker than reading a fresh screen.
    if (THINK > 0) {
      sleep(THINK / 4)
    }
  }
}

export function drillDownScenario() {
  // A one-week reported window well back in the corpus, which is what a
  // widget drill-down on an older period produces. The window is old on
  // purpose: a recent one sits at the near end of ix_tickets_created and
  // would be fast whether or not ix_tickets_reported exists.
  // ISO.DATE, not an instant — TicketListController binds these two with
  // @DateTimeFormat(iso = DATE). Sending 2025-04-01T00:00:00Z is a 400,
  // and a 400 comes back in 9 ms, which would have made this the fastest
  // scenario in the run rather than the slowest.
  const q = 'reportedFrom=2025-04-01&reportedTo=2025-04-08'
  checked(http.get(`${BASE}/api/v1/tickets?limit=50&${q}`, params()), 'drill down', drillDown)
  think()
}
