import { Tabs, type TabItem } from '@/components/ui/tabs'

export type DetailTab = TabItem

export interface TicketDetailTabsProps {
  tabs: DetailTab[]
  activeId: string
  onSelect: (id: string) => void
}

/**
 * Thin wrapper over the shared `components/ui/tabs.tsx`, kept so the
 * ticket-detail page's import and prop shape do not change now that a
 * second screen needs the same strip. See `tabs.tsx` for the implementation.
 */
export function TicketDetailTabs({ tabs, activeId, onSelect }: TicketDetailTabsProps) {
  return <Tabs tabs={tabs} activeId={activeId} onSelect={onSelect} ariaLabel="Ticket detail" />
}
