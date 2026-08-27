import { QueryClient } from '@tanstack/react-query'

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
  defaultOptions: { queries: { staleTime: 30_000, refetchOnWindowFocus: false } },
})
