import { useCallback, useEffect, useRef } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import {
  getListNotificationsQueryKey,
  listPendingNotifications,
  markNotificationsDelivered,
  useMarkNotificationRead,
} from '@/api/generated/notifications/notifications'
import { ToastAction } from '@/components/ui/toast'
import { toast } from '@/components/ui/use-toast'
import { getListChatThreadsQueryKey } from '@/api/generated/chat/chat'
import { ownQueue } from '@/realtime/destinations'
import { useRealtime } from '@/realtime/useRealtime'
import {
  isChatNotification,
  parseNotificationEvent,
  type NotificationCreated,
} from './notificationEvents'

/**
 * D-043 · the toast · D-044 · the live badge.
 *
 * <p>Both surfaces come off one subscription because both react to the same
 * three frames, and two subscriptions to the same queue would double every
 * toast the day somebody mounts the second one twice.
 *
 * **The badge is updated by invalidation, not by arithmetic.** The obvious
 * implementation — decrement on read, increment on created — drifts the moment
 * a frame is missed, and realtime here is explicitly best-effort (see
 * `realtime/client.ts`): a browser that was asleep misses everything published
 * while it was away, with no replay. A count kept by counting would then be
 * quietly wrong until the next full reload, which is the exact failure D-046
 * exists to prevent elsewhere. Invalidating makes the server's number the only
 * number, so a missed frame costs a moment of staleness rather than a
 * permanently wrong badge.
 *
 * <p>This is also why nothing here writes to Divyansh's `NotificationBell`: it
 * already reads `useListNotifications`, so invalidating that key updates his
 * badge with no change to his file.
 *
 * **Toasts are silenced on the onboarding module's routes.** Every notification
 * this hook raises is a ticketing event, so on a client-onboarding screen it is
 * an interruption the reader cannot act on without leaving the page. The badge
 * and the bell are untouched — only the pop-over goes — and a silenced
 * notification is left *unacknowledged* so it still arrives the next time the
 * reader is on a ticketing screen. See `SILENT_ROUTE_PREFIX` and `raise`'s
 * return value, which is what keeps that promise.
 */

/** How long a snoozed toast stays away. */
const SNOOZE_MS = 10 * 60 * 1000

/**
 * Routes that get no toasts at all.
 *
 * <p>The onboarding module asked for a silent screen, and the request is a fair
 * one: every notification this hook raises is a <em>ticketing</em> event — an
 * SLA breach, a handoff, a stale assignment — and none of them is actionable
 * from a client-onboarding screen. Five of them thrown over OB-04 on load is
 * five things the reader cannot do anything about without leaving the page.
 *
 * <p>Deliberately a route prefix rather than a user setting or a module claim:
 * "which module am I looking at" is a property of where the user currently is,
 * and the router is the only thing that knows it moment to moment.
 * {@code Sidebar.tsx} decides the same question the same way, from its own
 * private {@code ONBOARDING_PREFIX} — the duplication is two lines and the
 * alternative is this feature importing from the shell it is mounted by.
 */
const SILENT_ROUTE_PREFIX = '/onboarding'

/**
 * Whether the screen on display right now takes toasts.
 *
 * <p>Matched on the segment, not the raw string, so a future `/onboardings`
 * route would not silently inherit the rule.
 */
function useToastsSilenced(): boolean {
  const { pathname } = useLocation()
  return pathname === SILENT_ROUTE_PREFIX || pathname.startsWith(`${SILENT_ROUTE_PREFIX}/`)
}

export function useNotificationStream(): void {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const markRead = useMarkNotificationRead()

  /**
   * Read through a ref for the reason {@link raiseRef} is, one section down:
   * naming it in the drain effect's dependencies would re-run the drain on
   * every navigation, which is the precise bug that note exists to describe.
   * Every reader of this wants "where the user is now", never "where they were
   * when this callback was built".
   */
  const silenced = useToastsSilenced()
  const silencedRef = useRef(silenced)
  useEffect(() => {
    silencedRef.current = silenced
  }, [silenced])

  // Snoozes outlive the toast that created them, so they are cancelled on
  // unmount — without this a re-raise fires into a torn-down tree in tests and
  // on every logout.
  const snoozes = useRef<Set<ReturnType<typeof setTimeout>>>(new Set())

  /**
   * Ids already toasted in this session, and whether a drain is in flight.
   *
   * Declared here rather than beside the drain that owns them, because the
   * realtime handler below also records into `toasted` and reading a `const`
   * declared further down would be use-before-declaration to anybody scanning
   * the file, even though the closure makes it legal.
   *
   * Both are session-scoped guards against this tab racing *itself*. Delivery
   * across sessions is the server's to track (D-046) and still is — see the
   * drain's own note for why that is not sufficient on its own.
   */
  const draining = useRef(false)
  const toasted = useRef<Set<number>>(new Set())
  useEffect(() => {
    const pending = snoozes.current
    return () => {
      pending.forEach(clearTimeout)
      pending.clear()
    }
  }, [])

  const refreshBadge = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: getListNotificationsQueryKey() })
  }, [queryClient])

  /**
   * Move the header chat panel's badge instead of raising a toast.
   *
   * <p>The same move as `refreshBadge` one line up, for the same reason:
   * `ChatBadge` already reads `useListChatThreads`, so invalidating that key
   * updates its count and its thread list with no change to that file, and
   * without a count kept by arithmetic here that a missed frame would leave
   * permanently wrong.
   */
  const refreshChat = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: getListChatThreadsQueryKey() })
  }, [queryClient])

  /**
   * Show one notification, and say whether it was actually shown.
   *
   * <p>The return value is what keeps delivery honest. A notification this hook
   * swallows — a chat frame, or anything at all while an onboarding screen is
   * open — must **not** be acknowledged: acknowledging is the claim that the
   * user has seen it, and D-046 reads it to decide what never to pop again. Get
   * that wrong and silencing onboarding quietly destroys the backlog for
   * ticketing too, which is the one outcome nobody asked for.
   */
  const raise = useCallback(
    (created: NotificationCreated): boolean => {
      // Chat is read in the header panel, not thrown over the screen — see
      // `isChatNotification`. Guarded here rather than at the call sites
      // because there are three of them: the live frame, D-046's replay, and a
      // snooze re-raising itself ten minutes later. The top of `raise` is the
      // one place a fourth caller cannot forget.
      if (isChatNotification(created)) {
        refreshChat()
        return false
      }

      // Onboarding takes no toasts — see SILENT_ROUTE_PREFIX. Guarded here,
      // beside the chat guard, for that guard's own stated reason: there are
      // three callers and the top of `raise` is the one place a fourth cannot
      // forget. The badge still moves, and the notification is left pending so
      // it surfaces the next time the reader is somewhere it means something.
      if (silencedRef.current) {
        return false
      }

      const open = () => {
        // Opening is reading. Dismissing is not — a toast that marked itself
        // read on the way out would empty the bell of everything the user
        // ignored, which is the one place they can still find it.
        markRead.mutate({ notificationId: created.id })
        if (created.link) navigate(created.link)
      }

      const snooze = () => {
        const timer = setTimeout(() => {
          snoozes.current.delete(timer)
          // A snooze falling due on a silenced screen waits another interval
          // rather than being swallowed. The user asked to be reminded later;
          // dropping it because of where they happened to navigate in the
          // meantime answers a question they did not ask.
          if (!raise(created)) snooze()
        }, SNOOZE_MS)
        snoozes.current.add(timer)
      }

      toast({
        title: created.title,
        description: created.body,
        action: (
          <div className="flex shrink-0 items-center gap-1">
            <ToastAction altText={`Open ${created.title}`} onClick={open}>
              Open
            </ToastAction>
            <ToastAction altText={`Snooze ${created.title} for ten minutes`} onClick={snooze}>
              Snooze
            </ToastAction>
          </div>
        ),
      })
      return true
    },
    [markRead, navigate, refreshChat],
  )

  useRealtime(ownQueue(), (payload) => {
    const event = parseNotificationEvent(payload)
    if (!event) return

    if (event.event === 'notification.created') {
      // D-046. A live toast is a delivery like any other. Without this, every
      // notification the user watched arrive would pop again at next login.
      //
      // Both records are conditional on `raise` having actually shown it, and
      // both for the same reason: a frame swallowed on a silenced screen was
      // never seen, so marking it delivered would retire it unread and adding
      // it to `toasted` would stop this session's own drain from ever showing
      // it. It stays pending instead, and pops the next time the reader is on
      // a screen that takes toasts.
      //
      // `toasted` is still recorded before the acknowledgement completes — the
      // point it has always made — so a drain triggered by a visibility change
      // cannot re-toast something the user has already watched arrive while the
      // round trip is in flight.
      if (raise(event)) {
        toasted.current.add(event.id)
        void acknowledge([event.id])
      }
    }
    // Every one of the three moves the badge: a new notification adds to it,
    // and a read in another tab takes from it.
    refreshBadge()
  })

  // ------------------------------------------------------------- D-046

  /**
   * The drain reads {@link raise} through a ref, and that is load-bearing.
   *
   * <p>`raise` is rebuilt whenever `markRead` or `navigate` changes identity,
   * and **both change constantly**: `useMarkNotificationRead()` returns a fresh
   * object on every render, and react-router's `navigate` is re-created when the
   * location changes. Naming `raise` in the drain effect's dependencies
   * therefore re-ran the drain on essentially every render and every navigation
   * — refetching the pending list and toasting all of it again, several times
   * over, before the asynchronous acknowledge below had cleared anything.
   *
   * <p>A ref, rather than making `raise` stable: `raise` genuinely depends on
   * those two, and pinning it with an empty dependency list would capture a
   * stale `navigate` and send "Open" to the wrong route. What the effect
   * actually wants is "the current raise, without re-subscribing when it
   * changes", which is exactly what a ref expresses.
   */
  const raiseRef = useRef(raise)
  useEffect(() => {
    raiseRef.current = raise
  }, [raise])

  /**
   * Pop whatever was raised while nobody was watching.
   *
   * <p>Runs on mount — which is login, and what blueprint §11 asks for — and
   * again whenever the tab becomes visible. The second is not belt and braces:
   * a laptop that sleeps for two hours drops its socket, and Redis pub/sub has
   * no replay, so everything raised in between is missed by a session that is
   * never reloaded and would otherwise surface only on the next cold start.
   *
   * <p>Re-running is safe **because the server tracks delivery**. Anything
   * already acknowledged is no longer pending, so a second call returns
   * nothing rather than toasting twice.
   *
   * <p>That argument holds only once nothing re-runs the drain *faster than the
   * acknowledgement round-trip*, which is what the ref above fixes and what the
   * two guards below close off. Server-side delivery tracking is still the
   * source of truth across reloads; these only stop one session from racing
   * itself, which no amount of server state can prevent:
   *
   * <ul>
   *   <li>`draining` — mount and a visibility change can overlap, and the
   *       second call would read the same pending rows the first has toasted
   *       but not yet acknowledged.</li>
   *   <li>`toasted` — ids already shown *in this session*. React StrictMode
   *       invokes mount effects twice in development, which is a duplicate the
   *       server cannot possibly distinguish from a genuine second session.</li>
   * </ul>
   *
   * <p>Both are declared at the top of the hook, beside `snoozes`.
   */
  useEffect(() => {
    let cancelled = false

    const drain = async () => {
      if (draining.current) return
      // Nothing is fetched and nothing is acknowledged on a silenced screen.
      // Returning before the request, rather than filtering the toasts out
      // after it, is what leaves the backlog intact: a drain that read the
      // pending list and showed none of it would still acknowledge all of it.
      //
      // The effect below does not re-run on navigation — deliberately, see its
      // own note — so leaving onboarding does not immediately drain. The
      // backlog surfaces on the next reload or tab focus from a ticketing
      // screen, which is the same cadence it already had.
      if (silencedRef.current) return
      draining.current = true
      try {
        const pending = await listPendingNotifications()
        if (cancelled || !pending.data?.length) return

        pending.data.forEach((notification) => {
          if (notification.id == null) return
          if (toasted.current.has(notification.id)) return
          toasted.current.add(notification.id)
          raiseRef.current({
            event: 'notification.created',
            id: notification.id,
            eventCode: notification.eventKey ?? 'UNKNOWN',
            title: notification.title ?? '',
            body: notification.body ?? '',
            link: notification.deepLink ?? null,
          })
        })

        if (pending.hasMore) {
          // Saying so rather than silently dropping the rest: the cap exists
          // so a week's leave does not bury the screen, but a user who cannot
          // tell the difference between "five things happened" and "five of
          // many" has been misled by the quieter design.
          toast({
            title: 'More while you were away',
            description: 'Open the bell to see everything you missed.',
          })
        }

        await acknowledge(pending.data.map((n) => n.id).filter((id): id is number => id != null))
        refreshBadge()
      } catch {
        // A failed drain leaves everything pending, so the next visibility
        // change or reload tries again. Nothing is lost by staying quiet, and
        // an error toast about the notification system is noise on top of
        // whatever is already wrong.
      } finally {
        draining.current = false
      }
    }

    void drain()

    const onVisible = () => {
      if (document.visibilityState === 'visible') void drain()
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      cancelled = true
      document.removeEventListener('visibilitychange', onVisible)
    }
    // `raise` is deliberately absent — it is read through `raiseRef`. Listing
    // it is what made this effect re-run on every render and every navigation,
    // which is how one login's notifications came to pop again on every click.
  }, [refreshBadge])

  /**
   * D-045 · somebody clicked a browser notification.
   *
   * The service worker focuses the existing tab and posts here rather than
   * navigating it itself: `client.navigate` would reload the SPA and throw away
   * whatever the user was typing. So the worker says where to go and the app,
   * which owns the router, goes there.
   *
   * Guarded on `serviceWorker` because jsdom has none, and because a browser
   * without it never registered the worker in the first place.
   */
  useEffect(() => {
    if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) return

    const onMessage = (event: MessageEvent) => {
      const data = event.data as { type?: string; link?: string } | null
      if (data?.type !== 'edutrack:notification-click') return
      // Only ever a same-origin path. The link came from our own payload, but
      // it arrives here through postMessage, and navigating to whatever a
      // message says would be an open redirect if that ever stopped being true.
      const link = typeof data.link === 'string' && data.link.startsWith('/') ? data.link : '/'
      navigate(link)
      refreshBadge()
    }

    navigator.serviceWorker.addEventListener('message', onMessage)
    return () => navigator.serviceWorker.removeEventListener('message', onMessage)
  }, [navigate, refreshBadge])
}

/**
 * Report what was shown. Failures are swallowed deliberately: an unacknowledged
 * notification pops again, which is the direction to be wrong in.
 */
async function acknowledge(ids: number[]): Promise<void> {
  if (!ids.length) return
  try {
    await markNotificationsDelivered({ ids })
  } catch {
    // Left pending on purpose — see above.
  }
}
