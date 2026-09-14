import { QueryClient } from '@tanstack/react-query'

import { ApiError } from '@/api/http'

/**
 * The app's one query cache.
 *
 * Lifted out of `main.tsx` so the one other thing that has to reach it can:
 * `features/auth/sessionState.ts`, which empties it when a session ends. A
 * client created inline in the render tree is reachable only through
 * `useQueryClient` — that is, only from inside a component — and the moment a
 * session ends is not a render.
 *
 * ## Why it must be emptied when a session ends
 *
 * Everything in here is *somebody's* data, and almost none of the keys say
 * whose. `GET /me` is cached under a key with no user in it, because there is
 * only ever one "me"; every viewer-scoped list is then keyed by the id that
 * `/me` answered.
 *
 * So without a clear, signing out and signing in as somebody else serves the
 * second user the first user's `/me` straight from cache — still inside
 * `staleTime`, so not even refetched — and every screen that asks "who am I"
 * gets the wrong answer. My Tasks lists the previous user's tickets; the ticket
 * list's My Open view filters to them; the ribbon decides what this user may do
 * from the previous user's role.
 *
 * This is not a server-side leak: the rows were fetched under the first user's
 * token and the scope guard is untouched. It is a leak from one user to the
 * next person at the same browser, and to that person it simply looks like the
 * app showing somebody else's work.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      refetchOnWindowFocus: false,
      retry: retryServerFailuresOnly,
    },
  },
})

/**
 * Retry what might succeed next time. Never retry an answer.
 *
 * <p>React Query's default is three retries for <em>any</em> rejection, which
 * is right for a dropped connection and wrong for a 4xx: the server has already
 * decided, and asking again three times with exponential backoff spends about
 * seven seconds arriving at the same answer. Every one of those seconds is
 * spent with {@code isPending} still true, so the screen holds a loading
 * skeleton rather than rendering what the status actually means.
 *
 * <p><b>The screen this was found on is the case worth remembering.</b> The
 * prerequisites master answers {@code 404} when nobody has authored a
 * checklist yet — its own documentation calls that "the first visit, not a
 * failure", and it renders an empty state offering to write one. With the
 * default policy the most common first experience of that screen was seven
 * seconds of skeleton, which reads as a page that will not open.
 *
 * <p>A 5xx and a network failure still get three attempts, because those are
 * the ones a retry can actually fix. 408 and 429 are deliberately on that side
 * of the line too: a timeout and a rate limit are both "not now" rather than
 * "no".
 */
function retryServerFailuresOnly(failureCount: number, error: unknown): boolean {
  if (error instanceof ApiError && error.status >= 400 && error.status < 500
      && error.status !== 408 && error.status !== 429) {
    return false;
  }
  return failureCount < 3;
}
