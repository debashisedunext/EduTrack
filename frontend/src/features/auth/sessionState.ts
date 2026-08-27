import { useCurrentProjectStore } from '@/app/currentProjectStore'
import { queryClient } from '@/app/queryClient'
import { useDrillDownStore } from '@/features/dashboard/drillDownStore'
import { realtime } from '@/realtime/client'

/**
 * Throw away everything the app is holding *about the person who was signed in*.
 *
 * Called from `authStore` on the two events that change who that person is:
 * signing out, and signing in as somebody other than the current user.
 *
 * ## The bug this exists for
 *
 * Sign in as Karthik, sign out, sign in as Nikhil, open My Tasks — and it lists
 * Karthik's tickets. Nothing here was refetching stale rows; the second session
 * simply never asked. `signOut()` cleared the token and the store and left the
 * TanStack Query cache untouched, so `useGetMe()` answered Nikhil's page out of
 * Karthik's cache entry (keyed on nothing but the route, and inside its 30s
 * `staleTime`), and every viewer-scoped query downstream was then keyed by
 * Karthik's id. Eight screens read identity through `useGetMe`, so clearing the
 * cache in one place fixes all of them; teaching My Tasks alone to read the
 * auth store would have fixed the one screen that got reported.
 *
 * Only a *page load* used to clear any of this, which is why the bug looked
 * intermittent — anyone who reloaded between the two logins never saw it.
 *
 * ## What is deliberately kept
 *
 * `localStorage` preferences — theme, the remembered username, dashboard widget
 * order, the My Tasks list/kanban choice. Those belong to the browser rather
 * than to the session, they contain no one's data, and clearing the remembered
 * username in particular would break the feature it exists for.
 */
export function discardSessionState(): void {
  // The cache. This is the fix; the rest is the same class of leak found while
  // establishing that it was the only one.
  queryClient.clear()

  // The socket authenticated as the previous user. stompjs keeps it open across
  // a logout, and `/user/queue/events` is resolved by the broker from whoever
  // the *connection* authenticated as — so without this, the next user's tab
  // goes on receiving the previous user's notifications until something forces
  // a reconnect. Not awaited: `deactivate()` drops the client synchronously and
  // the close handshake is of no interest to anyone here.
  void realtime.deactivate()

  // The project switcher's selection, which may well be a project the next user
  // is not on. Written through `setState` rather than by adding a `clear()`
  // action: `currentProjectStore` is C-005, Stream C's file, and this needs no
  // change to it.
  useCurrentProjectStore.setState({ project: null })

  // A drill-down left open holds a server-built `/tickets?…` query string from
  // the previous user's dashboard, and would reopen with it.
  useDrillDownStore.getState().close()
}
