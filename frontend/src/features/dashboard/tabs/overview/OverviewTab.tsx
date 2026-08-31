import { BarChart3 } from 'lucide-react'
import { EmptyState } from '@/components/ui/empty-state'

/**
 * Dashboard Rework Dev 1 · tab 2, built by Dev 2 in PR 10. This placeholder
 * is what PR 2's shell mounts in the meantime, so `?tab=overview` is a real,
 * working link before that PR lands.
 */
export function OverviewTab() {
  return (
    <EmptyState
      icon={<BarChart3 className="h-6 w-6" strokeWidth={1.5} />}
      title="Ticket Overview is not built yet"
      description="The range cards, Top Assignees bar and status donut land in PR 10."
    />
  )
}
