import { useCallback, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import {
  getListNotificationsQueryKey,
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
    }
    // Every one of the three moves the badge: a new notification adds to it,
    // and a read in another tab takes from it.
    refreshBadge()
  })
}
