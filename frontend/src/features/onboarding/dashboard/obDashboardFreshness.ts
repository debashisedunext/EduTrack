import type { QueryClient, UseQueryOptions } from '@tanstack/react-query'

/**
 * What keeps OB-02 showing the current state of the module rather than the
 * state it was in when the tab was last opened.
 *
 * <h2>The two halves of "the dashboard is stale", because they are different bugs</h2>
 *
 * <b>Server-side.</b> Six of the seven cards and the whole workload grid read
 * `ob_dashboard_summary` / `ob_implementor_daily_stats`, which nothing in the
 * `api` process writes — B-120's {@code ObStatsRefreshWorker} does, from the
 * separate `worker` application. If that process is not running the board is
 * not stale, it is *empty*: every card carries "No summary has been computed
 * yet". No amount of refetching fixes that, and this file cannot; `make worker`
 * is what fixes it, and GETTING-STARTED.md §"The worker" says so.
 *
 * <b>Client-side, which is what this file is for.</b> Once the worker is up the
 * figures move every refresh interval, and the RAG board, the stuck panel and
 * the delayed-projects grid are live reads that move the instant a project is
 * created. The app's default `staleTime: 30_000` with `refetchOnWindowFocus:
 * false` (`app/queryClient.ts`) then hides all of it: create a project, return
 * to the dashboard inside thirty seconds, and React Query serves the cache it
 * already has without asking the server anything. The board looked broken and
 * the data was correct the whole time.
 *
 * <h2>Why per-query and not a change to the default</h2>
 *
 * The 30-second default is right for the screens it was written for — a master
 * list does not change while nobody is editing it. A dashboard is the one
 * screen whose entire purpose is to be current, so it opts out here rather than
 * everything else opting out of a weaker default.
 */

/**
 * Every generated dashboard key is `['/onboarding/dashboard/<route>', params]`.
 *
 * <p>Note the route is inside the <em>first element</em>, not a second one, so
 * `invalidateQueries({ queryKey: ['/onboarding/dashboard'] })` matches nothing:
 * React Query compares key elements, and `'/onboarding/dashboard'` is not equal
 * to `'/onboarding/dashboard/summary'`. That silent no-op is the reason this is
 * a prefix predicate rather than the one-line key invalidation every other
 * feature in the module uses.
 */
const DASHBOARD_URL_PREFIX = '/onboarding/dashboard'

/**
 * `/onboarding/clients`, which the RAG board's three columns read.
 *
 * <p>Its keys nest properly, so a plain key invalidation would do — but a
 * caller invalidating "the dashboard" means the screen, not one of its
 * requests, and leaving the RAG columns out is how two of the four tabs end up
 * refreshing and two do not.
 */
const CLIENTS_URL_PREFIX = '/onboarding/clients'

/**
 * Mark every request behind OB-02 stale, so the next render refetches it.
 *
 * <p>Call from any mutation that moves a figure on the board — creating,
 * editing or deleting a project or a client, completing a step, recording a
 * sign-off. Invalidating is not fetching: nothing is requested for a screen
 * that is not mounted, so the cost of calling this from a mutation that turns
 * out not to have moved anything is zero.
 *
 * <p><b>It does not make a card's count move on its own.</b> The counts come
 * from the pre-aggregated tables, so the refetch returns the same figures until
 * B-120's next pass writes new ones — see this file's header. What it does make
 * immediate is every live read on the screen: the RAG columns, the stuck panel,
 * the delayed-projects grid and the card slide-over.
 */
export function invalidateObDashboard(queryClient: QueryClient): void {
  void queryClient.invalidateQueries({
    predicate: (query) => {
      const url = query.queryKey[0]
      return typeof url === 'string'
        && (url.startsWith(DASHBOARD_URL_PREFIX) || url.startsWith(CLIENTS_URL_PREFIX))
    },
  })
}

/**
 * How often an open board re-asks the server, in milliseconds.
 *
 * <p>Sixty seconds against a default refresh interval of five minutes
 * (`edutrack.ob-stats.refresh-interval`) — deliberately several times faster
 * than the thing it is watching, so a manager leaving the board open sees a new
 * figure within a minute of it being written rather than up to a full interval
 * later. Locally `make worker` runs the refresh every thirty seconds, which is
 * what makes "create a project, watch the card move" true on a dev machine.
 *
 * <p>It is cheap to poll: the summary route is a single indexed read of one
 * day's rows and answers a strong `ETag`, so a poll that finds nothing new is
 * one conditional request.
 */
export const OB_DASHBOARD_POLL_MS = 60_000

/**
 * The options every OB-02 query passes as `{ query: OB_DASHBOARD_QUERY }`.
 *
 * <p>`refetchOnMount: 'always'` is the one that matters most and the one a
 * `staleTime` override would not give: navigating back to the dashboard after
 * creating a project refetches *regardless* of how recently the data was
 * fetched. The window-focus refetch covers the other half of the same story —
 * the tab that was left open on the board while the work happened elsewhere.
 */
export const OB_DASHBOARD_QUERY = {
  refetchOnMount: 'always',
  refetchOnWindowFocus: true,
  refetchInterval: OB_DASHBOARD_POLL_MS,
} as const satisfies Partial<UseQueryOptions>
