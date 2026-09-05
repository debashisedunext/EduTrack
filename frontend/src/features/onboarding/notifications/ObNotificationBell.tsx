import * as PopoverPrimitive from '@radix-ui/react-popover'
import { Bell } from 'lucide-react'
import { Link } from 'react-router-dom'
import { useListObNotifications } from '@/api/generated/onboarding-notifications/onboarding-notifications'
import type { ObNotification } from '@/api/generated/model'
import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { cn } from '@/lib/utils'
import { categoryLook, useObNotificationActions } from './obNotificationQueries'

/**
 * B-112 · OB-13's bell — the daily surface.
 *
 * PHASE-2-BUILD-PLAN.md §73 settles the shape: the plan asked for a
 * notification centre and the prototype had a bell dropdown, and the answer was
 * **both** — "the popover is the daily surface; a full page is needed for
 * history and for the digest links to land somewhere". This is the first half;
 * {@link ObNotificationCentrePage} is the second. They are one endpoint with a
 * different `limit`, not two.
 *
 * <h2>Where it mounts, and why not yet</h2>
 *
 * Deliberately not added to `app/TopBar.tsx`. That is the *ticketing* shell's
 * chrome, and it already carries Stream D's bell for S-26; a second bell beside
 * it would put onboarding TAT reminders in front of every developer who has
 * never opened the module. OB-01's module switcher and the onboarding shell are
 * still ahead (B-108/B-109), and this component is what that shell mounts when
 * it lands — the same position C-102's designer route took, reachable and
 * finished before the navigation that reaches it exists.
 *
 * Until then the full page carries its own copy of the list, so nothing here is
 * unreachable: `/onboarding/notifications` is routed and is where B-114's
 * digest links land.
 */

/** The popover is a glance. The page is the list. */
const BELL_LIMIT = 8

export function ObNotificationBell() {
  const { data } = useListObNotifications({ limit: BELL_LIMIT })
  const notifications = data?.data ?? []
  const unreadCount = data?.meta?.unreadCount ?? 0
  const { markRead, markAllRead } = useObNotificationActions()

  return (
    <PopoverPrimitive.Root>
      <PopoverPrimitive.Trigger asChild>
        <button
          type="button"
          aria-label={`Onboarding notifications${unreadCount > 0 ? ` (${unreadCount} unread)` : ''}`}
          className="relative flex h-9 w-9 shrink-0 items-center justify-center rounded-control text-content-muted transition-colors hover:bg-subtle hover:text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        >
          <Bell className="h-4 w-4" />
          {unreadCount > 0 && (
            <span className="absolute right-1 top-1 flex h-4 min-w-4 items-center justify-center rounded-chip bg-danger px-1 text-[10px] font-semibold text-white">
              {unreadCount > 9 ? '9+' : unreadCount}
            </span>
          )}
        </button>
      </PopoverPrimitive.Trigger>
      <PopoverPrimitive.Portal>
        <PopoverPrimitive.Content
          align="end"
          sideOffset={8}
          className="z-50 w-96 overflow-hidden rounded-control border border-border bg-surface shadow-modal"
        >
          <div className="flex items-center justify-between border-b border-border px-3 py-2">
            <p className="text-sm font-semibold text-content">Onboarding</p>
            {unreadCount > 0 && (
              <Button variant="ghost" size="sm" onClick={() => markAllRead.mutate()}>
                Mark all read
              </Button>
            )}
          </div>
          <div className="max-h-96 overflow-y-auto">
            {notifications.length === 0 && (
              <EmptyState title="Nothing to catch up on" className="py-8" />
            )}
            {notifications.map((n) => (
              <ObNotificationRow
                key={n.id}
                notification={n}
                onOpen={() => {
                  if (n.id && !n.isRead) markRead.mutate({ notificationId: n.id })
                }}
              />
            ))}
          </div>
          {/*
            The way out of a glance and into the history. Present even when the
            popover is empty: "nothing new" and "nothing ever" are different
            questions, and only the page answers the second.
          */}
          <div className="border-t border-border px-3 py-2">
            <Link
              to="/onboarding/notifications"
              className="text-sm font-medium text-primary hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
            >
              See all notifications
            </Link>
          </div>
        </PopoverPrimitive.Content>
      </PopoverPrimitive.Portal>
    </PopoverPrimitive.Root>
  )
}

/**
 * One entry, shared by the popover and the page.
 *
 * Exported so the two surfaces cannot drift on what an entry looks like — the
 * failure that matters is the one a user sees on the one screen where both are
 * open at once.
 *
 * <h2>A link when there is somewhere to go, a button when there is not</h2>
 *
 * `deepLink` is null for an entry that names no client. Rendering an anchor with
 * no `href` would put a control in the tab order that does nothing when
 * activated, which is worse for a keyboard user than for a mouse one — they
 * cannot see that it goes nowhere before committing to it.
 */
export function ObNotificationRow({
  notification,
  onOpen,
}: {
  notification: ObNotification
  onOpen: () => void
}) {
  const look = categoryLook(notification.category ?? '')
  const unread = !notification.isRead
  const className = cn(
    'flex w-full flex-col items-start gap-1 border-b border-border px-3 py-2.5 text-left transition-colors last:border-0 hover:bg-subtle focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary',
    unread && 'bg-primary-soft/40',
  )

  const inner = (
    <>
      <div className="flex w-full items-start justify-between gap-2">
        <p className="text-sm font-medium text-content">{notification.title}</p>
        <Chip variant={look.variant} className="shrink-0">
          {look.label}
        </Chip>
      </div>
      {notification.body && <p className="text-xs text-content-muted">{notification.body}</p>}
      <p className="text-xs text-content-muted">
        {formatWhen(notification.createdAt)}
        {unread && (
          // Not colour alone — WCAG 1.4.1. The tint above says "unread" to
          // somebody who can see it; this says it to everybody else.
          <span className="ml-2 font-medium text-primary">Unread</span>
        )}
      </p>
    </>
  )

  return notification.deepLink ? (
    <Link to={notification.deepLink} onClick={onOpen} className={className}>
      {inner}
    </Link>
  ) : (
    <button type="button" onClick={onOpen} className={className}>
      {inner}
    </button>
  )
}

/**
 * The timestamp, in the reader's own zone.
 *
 * Storage is UTC everywhere (CLAUDE.md); the presentation layer is the only
 * place a local zone is applied, and this is it.
 */
function formatWhen(iso: string | undefined): string {
  if (!iso) return ''
  const at = new Date(iso)
  if (Number.isNaN(at.getTime())) return ''
  return at.toLocaleString(undefined, {
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  })
}
