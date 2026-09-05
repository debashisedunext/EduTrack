import * as React from 'react'
import { useSearchParams } from 'react-router-dom'
import { CheckCheck } from 'lucide-react'
import { useListObNotifications } from '@/api/generated/onboarding-notifications/onboarding-notifications'
import type { ObNotification } from '@/api/generated/model'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'
import { ObNotificationRow } from './ObNotificationBell'
import {
  OB_TABS,
  isObTabId,
  useObNotificationActions,
  type ObTabId,
} from './obNotificationQueries'

/**
 * B-112 · OB-13's full page — `/onboarding/notifications`.
 *
 * <h2>Why a page as well as a popover</h2>
 *
 * PHASE-2-BUILD-PLAN.md §73: "**Both.** The popover is the daily surface; a
 * full page is needed for history and for the digest links to land somewhere."
 * The second half of that sentence is the load-bearing one — B-114 mails
 * managers a daily digest of stuck journeys, and a mail whose links open a
 * dropdown that only exists while you hold it open is a mail with no
 * destination. This is that destination, and it is routed before the digest
 * exists rather than after.
 *
 * <h2>The tab is in the URL</h2>
 *
 * `?tab=escalations` rather than component state, so a tab is a link somebody
 * can paste into a message — the reason {@code TabItem.id} is "also the `?tab=`
 * value" on the shared strip. It also means the browser's back button walks
 * back through tabs, which is what a person who clicked three of them expects.
 *
 * <h2>Manual tab activation, not the shared `Tabs` component</h2>
 *
 * `components/ui/tabs.tsx` selects on focus and says so: *"selection follows
 * focus, which only suits a caller whose panels are all already loaded — a
 * caller that fetches per-tab should not assume that holds without checking."*
 * This screen fetches per tab, so arrowing across four tabs would fire four
 * requests and land on whichever answered last. So the strip below is the APG's
 * manual-activation variant — arrows move focus, Enter or Space selects — which
 * is the pattern APG names for exactly this case. Hand-rolled here rather than
 * added as a mode to the shared component, because `components/ui/` is Stream
 * C's and a second activation mode is their decision to take, not a side effect
 * of this screen.
 */
export function ObNotificationCentrePage() {
  const [params, setParams] = useSearchParams()
  const tabParam = params.get('tab')
  const tab: ObTabId = isObTabId(tabParam) ? tabParam : 'all'
  const unreadOnly = params.get('unreadOnly') === 'true'

  /**
   * Pages accumulate rather than replace. "Load more" that swapped the list for
   * the next page would be paging by another name, and the reason somebody is
   * on this screen is to read back through history in one column.
   */
  const [cursor, setCursor] = React.useState<string | undefined>(undefined)
  const [older, setOlder] = React.useState<ObNotification[]>([])

  const { data, isPending } = useListObNotifications({
    tab,
    unreadOnly: unreadOnly || undefined,
    cursor,
    limit: 25,
  })
  const { markRead, markAllRead } = useObNotificationActions()

  const page = data?.data ?? []
  const notifications = cursor ? [...older, ...page] : page
  const unreadCount = data?.meta?.unreadCount ?? 0
  const hasMore = data?.meta?.hasMore ?? false

  /** Back to the first page, discarding what has been loaded beneath it. */
  function resetPaging() {
    setCursor(undefined)
    setOlder([])
  }

  /** Any change of filter starts the history again from the top. */
  function setFilter(next: { tab?: ObTabId; unreadOnly?: boolean }) {
    const merged = new URLSearchParams(params)
    if (next.tab !== undefined) merged.set('tab', next.tab)
    if (next.unreadOnly !== undefined) {
      if (next.unreadOnly) merged.set('unreadOnly', 'true')
      else merged.delete('unreadOnly')
    }
    resetPaging()
    setParams(merged)
  }

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-4 p-6">
      <header className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-lg font-semibold text-content">Notifications</h1>
          <p className="text-sm text-content-muted">
            {unreadCount > 0
              ? `${unreadCount} unread across your onboarding clients`
              : 'Everything on your onboarding clients is read'}
          </p>
        </div>
        {unreadCount > 0 && (
          <Button
            variant="secondary"
            size="sm"
            /*
              Reset the paging as well as invalidating. `older` is a snapshot
              taken when "Load older" was pressed, and invalidation refetches
              only the *current* page — so without this, marking everything read
              leaves every previously loaded page still rendering as unread,
              under a header that says nothing is. The pages below the first are
              cheap to fetch again and the alternative is patching a snapshot to
              match a mutation, which is the state duplication the query cache
              exists to avoid.
            */
            onClick={() => markAllRead.mutate(undefined, { onSuccess: resetPaging })}
          >
            <CheckCheck className="h-4 w-4" aria-hidden="true" />
            Mark all read
          </Button>
        )}
      </header>

      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border">
        <ObNotificationTabs tab={tab} onSelect={(next) => setFilter({ tab: next })} />
        <label className="flex items-center gap-2 pb-2 text-sm text-content-muted">
          <input
            type="checkbox"
            checked={unreadOnly}
            onChange={(event) => setFilter({ unreadOnly: event.target.checked })}
            className="h-4 w-4 rounded-control border-border text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          />
          Unread only
        </label>
      </div>

      <div
        id="ob-notification-panel"
        role="tabpanel"
        aria-labelledby={`ob-notification-tab-${tab}`}
        // The panel is the tab's content, so it takes a tab stop of its own —
        // otherwise a keyboard user who selects a tab has no way into the list
        // it just changed. APG's rule for a panel with no focusable first child;
        // this one often has none, because "no notifications" is a real state.
        tabIndex={0}
        className="overflow-hidden rounded-control border border-border bg-surface focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
      >
        {isPending && notifications.length === 0 && (
          <div className="flex flex-col gap-3 p-4" aria-hidden="true">
            {/* Skeletons, never a spinner — blueprint §12.2. */}
            <Skeleton className="h-12 w-full" />
            <Skeleton className="h-12 w-full" />
            <Skeleton className="h-12 w-full" />
          </div>
        )}
        {!isPending && notifications.length === 0 && (
          <EmptyState
            title={unreadOnly ? 'Nothing unread' : 'No notifications yet'}
            description={
              unreadOnly
                ? 'Clear the filter to read back through what has already been seen.'
                : 'Assignments, reminders and escalations on your onboarding clients will appear here.'
            }
          />
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

      {hasMore && (
        <div className="flex justify-center">
          <Button
            variant="secondary"
            size="sm"
            onClick={() => {
              setOlder(notifications)
              setCursor(data?.meta?.nextCursor ?? undefined)
            }}
          >
            Load older
          </Button>
        </div>
      )}
    </div>
  )
}

/**
 * The APG manual-activation tablist. See the page's own note on why this is
 * not the shared `Tabs` component.
 */
function ObNotificationTabs({
  tab,
  onSelect,
}: {
  tab: ObTabId
  onSelect: (next: ObTabId) => void
}) {
  const refs = React.useRef<Record<string, HTMLButtonElement | null>>({})
  const activeIndex = Math.max(
    0,
    OB_TABS.findIndex((t) => t.id === tab),
  )

  /** Focus moves; selection does not follow it. Enter or Space commits. */
  function focusTab(index: number) {
    const next = OB_TABS[(index + OB_TABS.length) % OB_TABS.length]
    refs.current[next.id]?.focus()
  }

  function onKeyDown(event: React.KeyboardEvent<HTMLDivElement>) {
    switch (event.key) {
      case 'ArrowRight':
        focusTab(activeIndex + 1)
        break
      case 'ArrowLeft':
        focusTab(activeIndex - 1)
        break
      case 'Home':
        focusTab(0)
        break
      case 'End':
        focusTab(OB_TABS.length - 1)
        break
      default:
        return
    }
    event.preventDefault()
  }

  return (
    <div role="tablist" aria-label="Notification categories" onKeyDown={onKeyDown} className="flex gap-1">
      {OB_TABS.map((t) => {
        const selected = t.id === tab
        return (
          <button
            key={t.id}
            ref={(node) => {
              refs.current[t.id] = node
            }}
            type="button"
            role="tab"
            id={`ob-notification-tab-${t.id}`}
            aria-selected={selected}
            aria-controls="ob-notification-panel"
            // One tab stop for the whole strip — APG. Arrows move within it.
            tabIndex={selected ? 0 : -1}
            onClick={() => onSelect(t.id)}
            className={cn(
              'border-b-2 px-3 py-2 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary',
              selected
                ? 'border-primary text-content'
                : 'border-transparent text-content-muted hover:text-content',
            )}
          >
            {t.label}
          </button>
        )
      })}
    </div>
  )
}
