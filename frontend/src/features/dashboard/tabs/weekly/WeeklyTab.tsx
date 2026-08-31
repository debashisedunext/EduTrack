import { CalendarRange } from 'lucide-react'
import { EmptyState } from '@/components/ui/empty-state'

/**
 * Dashboard Rework Dev 1 · tab 3, built by Dev 2 in PR 13. This placeholder
 * is what PR 2's shell mounts in the meantime, so `?tab=weekly` is a real,
 * working link before that PR lands.
 */
export function WeeklyTab() {
  return (
    <EmptyState
      icon={<CalendarRange className="h-6 w-6" strokeWidth={1.5} />}
      title="Weekly Progress is not built yet"
      description="The week picker, four cards and grouped accordions land in PR 13."
    />
  )
}
