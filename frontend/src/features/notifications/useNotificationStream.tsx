import { useCallback, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import {
  getListNotificationsQueryKey,
  listPendingNotifications,
  markNotificationsDelivered,
  useMarkNotificationRead,
} from '@/api/generated/notifications/notifications'
import { ToastAction } from '@/components/ui/toast'
import { toast } from '@/components/ui/use-toast'
import { ownQueue } from '@/realtime/destinations'
import { useRealtime } from '@/realtime/useRealtime'
import { parseNotificationEvent, type NotificationCreated } from './notificationEvents'

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
 */

/** How long a snoozed toast stays away. */
const SNOOZE_MS = 10 * 60 * 1000

export function useNotificationStream(): void {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const markRead = useMarkNotificationRead()

  // Snoozes outlive the toast that created them, so they are cancelled on
  // unmount — without this a re-raise fires into a torn-down tree in tests and
  // on every logout.
  const snoozes = useRef<Set<ReturnType<typeof setTimeout>>>(new Set())
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

  const raise = useCallback(
    (created: NotificationCreated) => {
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
          raise(created)
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
    },
    [markRead, navigate],
  )

  useRealtime(ownQueue(), (payload) => {
    const event = parseNotificationEvent(payload)
    if (!event) return

    if (event.event === 'notification.created') {
      raise(event)
      // D-046. A live toast is a delivery like any other. Without this, every
      // notification the user watched arrive would pop again at next login.
      void acknowledge([event.id])
    }
    // Every one of the three moves the badge: a new notification adds to it,
    // and a read in another tab takes from it.
    refreshBadge()
  })

  // ------------------------------------------------------------- D-046

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
   * nothing rather than toasting twice — the idempotence is in the data, not
   * in a guard here that could drift.
   */
  useEffect(() => {
    let cancelled = false

    const drain = async () => {
      try {
        const pending = await listPendingNotifications()
        if (cancelled || !pending.data?.length) return

        pending.data.forEach((notification) => {
          if (notification.id == null) return
          raise({
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
  }, [raise, refreshBadge])

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
