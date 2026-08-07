import * as PopoverPrimitive from '@radix-ui/react-popover'
import { Bell } from 'lucide-react'
import { useQueryClient } from '@tanstack/react-query'
import {
  getListNotificationsQueryKey, useListNotifications, useMarkAllNotificationsRead, useMarkNotificationRead,
} from '@/api/generated/notifications/notifications'
import { EmptyState } from '@/components/ui/empty-state'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

// Notification bell with unread badge — blueprint §7.2.
export function NotificationBell() {
  const queryClient = useQueryClient()
  const { data } = useListNotifications({ limit: 8 })
  const notifications = data?.data ?? []
  const unreadCount = data?.meta?.unreadCount ?? 0

  const invalidate = () => queryClient.invalidateQueries({ queryKey: getListNotificationsQueryKey() })
  const markRead = useMarkNotificationRead({ mutation: { onSuccess: invalidate } })
  const markAllRead = useMarkAllNotificationsRead({ mutation: { onSuccess: invalidate } })

  return (
    <PopoverPrimitive.Root>
      <PopoverPrimitive.Trigger asChild>
        <button
          type="button"
          aria-label={`Notifications${unreadCount > 0 ? ` (${unreadCount} unread)` : ''}`}
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
          className="z-50 w-80 overflow-hidden rounded-control border border-border bg-surface shadow-modal"
        >
          <div className="flex items-center justify-between border-b border-border px-3 py-2">
            <p className="text-sm font-semibold text-content">Notifications</p>
            {unreadCount > 0 && (
              <Button variant="ghost" size="sm" onClick={() => markAllRead.mutate()}>
                Mark all read
              </Button>
            )}
          </div>
          <div className="max-h-80 overflow-y-auto">
            {notifications.length === 0 && <EmptyState title="No notifications" className="py-8" />}
            {notifications.map((n) => (
              <button
                key={n.id}
                type="button"
                onClick={() => n.id && !n.isRead && markRead.mutate({ notificationId: n.id })}
                className={cn(
                  'flex w-full flex-col items-start gap-0.5 border-b border-border px-3 py-2.5 text-left transition-colors last:border-0 hover:bg-subtle',
                  !n.isRead && 'bg-primary-soft/40',
                )}
              >
                <p className="text-sm font-medium text-content">{n.title}</p>
                <p className="text-xs text-content-muted">{n.body}</p>
              </button>
            ))}
          </div>
        </PopoverPrimitive.Content>
      </PopoverPrimitive.Portal>
    </PopoverPrimitive.Root>
  )
}
