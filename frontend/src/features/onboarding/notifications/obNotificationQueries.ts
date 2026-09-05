import { useQueryClient } from '@tanstack/react-query'
import {
  getListObNotificationsQueryKey,
  useMarkAllObNotificationsRead,
  useMarkObNotificationRead,
} from '@/api/generated/onboarding-notifications/onboarding-notifications'

/**
 * B-112 · the bits of OB-13 both surfaces need — the popover and the full page.
 *
 * Here rather than duplicated in each, for the reason the endpoint itself is
 * one route: the popover and the page are two renderings of the same list, and
 * two copies of "what does ESCALATION look like" is how they come to disagree
 * about it on the one screen where a user sees both at once.
 */

/** The categories the server stamps. A string on the wire; see `ObCategory`. */
export const OB_CATEGORIES = ['ASSIGNMENT', 'ESCALATION', 'REMINDER', 'UPDATE'] as const
export type ObCategory = (typeof OB_CATEGORIES)[number]

/**
 * OB-13's tabs. `UPDATE` is deliberately absent — a gate opening or a go-live is
 * worth a bell entry and not worth a tab, and those appear under All, which is
 * what All is for.
 */
export const OB_TABS = [
  { id: 'all', label: 'All' },
  { id: 'assignments', label: 'Assignments' },
  { id: 'escalations', label: 'Escalations' },
  { id: 'reminders', label: 'Reminders' },
] as const
export type ObTabId = (typeof OB_TABS)[number]['id']

export const isObTabId = (value: string | null): value is ObTabId =>
  OB_TABS.some((tab) => tab.id === value)

interface CategoryLook {
  label: string
  /** A `Chip` variant — the tokens of blueprint §12.1, never a raw colour. */
  variant: 'info' | 'danger' | 'warning' | 'neutral'
}

const LOOK: Record<ObCategory, CategoryLook> = {
  ASSIGNMENT: { label: 'Assignment', variant: 'info' },
  ESCALATION: { label: 'Escalation', variant: 'danger' },
  REMINDER: { label: 'Reminder', variant: 'warning' },
  UPDATE: { label: 'Update', variant: 'neutral' },
}

/**
 * How one entry is labelled.
 *
 * **Tolerant, on purpose.** The server sends the stored string rather than a
 * closed enum precisely so an entry written by a newer deploy still renders, and
 * a client that threw on an unrecognised value would give that back. An unknown
 * category costs the chip's colour and nothing else.
 */
export function categoryLook(category: string): CategoryLook {
  return LOOK[category as ObCategory] ?? { label: category, variant: 'neutral' }
}

/**
 * Every OB-13 query, invalidated together.
 *
 * The popover and the page hold different `params`, so they are different query
 * keys; marking one entry read changes the badge on both. Invalidating the
 * prefix rather than each key is what stops a bell that still says 3 beside a
 * list that shows none.
 */
export function useObNotificationActions() {
  const queryClient = useQueryClient()
  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: getListObNotificationsQueryKey() })

  const markRead = useMarkObNotificationRead({ mutation: { onSuccess: invalidate } })
  const markAllRead = useMarkAllObNotificationsRead({ mutation: { onSuccess: invalidate } })
  return { markRead, markAllRead }
}
