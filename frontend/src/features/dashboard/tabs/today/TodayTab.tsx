import { LayoutDashboard } from 'lucide-react'
import { EmptyState } from '@/components/ui/empty-state'

/**
 * Dashboard Rework Dev 1 · tab 1. The seven cards, the Open Issues card and
 * the Assignee MIS table land in PR 7 and PR 8, once PR 4's counters and
 * PR 6's endpoint exist to read. This placeholder is what PR 2's shell
 * mounts in the meantime, so `?tab=today` is a real, working link before
 * any of that lands.
 */
export function TodayTab() {
  return (
    <EmptyState
      icon={<LayoutDashboard className="h-6 w-6" strokeWidth={1.5} />}
      title="Today's Progress is not built yet"
      description="Cards, the Open Issues card and the assignee grid land in PR 7 and PR 8."
    />
  )
}
