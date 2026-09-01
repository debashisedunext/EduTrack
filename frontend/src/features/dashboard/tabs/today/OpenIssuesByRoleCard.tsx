import { Chip } from '@/components/ui/chip'
import { Skeleton } from '@/components/ui/skeleton'

import { useDrillDownStore } from '../../drillDownStore'

/**
 * S-05 tab 1 · the Open Issues card (PR 7).
 *
 * Answers "who currently holds the work", not "how much of it is there" —
 * which is why it sits apart from the seven `TodaySummaryCard`s rather than
 * being an eighth one. Absent on the OWN_WORK variant: a delivery role's own
 * figures are already the seven cards above, and a by-role split of other
 * people's tickets answers nothing for them.
 *
 * Same button-not-link contract as `TodaySummaryCard` — every role chip is a
 * real `<button>` with visible focus, per the plan.
 */
export interface OpenIssuesRoleFigure {
  role: string
  label: string
  value: number
  drillDown?: string | null
}

export interface OpenIssuesByRoleCardProps {
  total: { value: number; drillDown?: string | null }
  roles: OpenIssuesRoleFigure[]
}

const LABEL = 'Open Issues'

export function OpenIssuesByRoleCard({ total, roles }: OpenIssuesByRoleCardProps) {
  const openPanel = useDrillDownStore((s) => s.open)

  return (
    <div
      role="group"
      aria-label={LABEL}
      className="rounded-card border border-[color:var(--border)] bg-[color:var(--bg-surface)] p-4 flex flex-col gap-3"
    >
      <span className="text-[11px] font-semibold uppercase tracking-wide text-[color:var(--text-tertiary)]">
        {LABEL}
      </span>

      <TotalFigure total={total} onOpen={openPanel} />

      <div className="flex flex-wrap gap-1.5">
        {roles.map((role) => (
          <RoleChip key={role.role} role={role} onOpen={openPanel} />
        ))}
      </div>
    </div>
  )
}

type OpenDrillDown = (drillDown: string, title: string, count?: number | null) => void

function TotalFigure({
  total,
  onOpen,
}: {
  total: { value: number; drillDown?: string | null }
  onOpen: OpenDrillDown
}) {
  const body = (
    <>
      <span className="text-lg font-semibold tabular-nums leading-none text-[color:var(--text-primary)]">
        {total.value}
      </span>
      <span className="mt-1 text-[10px] font-semibold uppercase tracking-wide text-[color:var(--text-tertiary)]">
        Open, not closed
      </span>
    </>
  )

  if (!total.drillDown) {
    return (
      <div className="flex flex-col items-start" aria-label={`Open, not closed: ${total.value}`}>
        {body}
      </div>
    )
  }

  const drillDown = total.drillDown
  return (
    <button
      type="button"
      className="flex w-fit flex-col items-start rounded-control text-left transition-colors
                 hover:bg-[color:var(--bg-subtle)] focus-visible:outline focus-visible:outline-2
                 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--primary)]"
      aria-label={`Open, not closed: ${total.value}. Open the filtered ticket list.`}
      onClick={() => onOpen(drillDown, `${LABEL} — open, not closed`, total.value)}
    >
      {body}
    </button>
  )
}

function RoleChip({ role, onOpen }: { role: OpenIssuesRoleFigure; onOpen: OpenDrillDown }) {
  const variant = role.role === 'UNASSIGNED' ? 'neutral' : 'info'
  const chip = <Chip variant={variant}>{role.label} {role.value}</Chip>

  if (!role.drillDown) return chip

  const drillDown = role.drillDown
  return (
    <button
      type="button"
      className="rounded-chip focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2
                 focus-visible:outline-[color:var(--primary)]"
      aria-label={`${role.label}: ${role.value}. Open the filtered ticket list.`}
      onClick={() => onOpen(drillDown, role.label, role.value)}
    >
      {chip}
    </button>
  )
}

/** Shown while the first request is in flight — same footprint, so nothing reflows on arrival. */
export function OpenIssuesByRoleCardSkeleton() {
  return (
    <div className="rounded-card border border-[color:var(--border)] bg-[color:var(--bg-surface)] p-4 flex flex-col gap-3">
      <Skeleton className="h-3 w-24" />
      <div className="flex flex-col gap-1">
        <Skeleton className="h-6 w-10" />
        <Skeleton className="h-2 w-20" />
      </div>
      <div className="flex flex-wrap gap-1.5">
        {Array.from({ length: 6 }, (_, i) => (
          <Skeleton key={i} className="h-5 w-16 rounded-chip" />
        ))}
      </div>
    </div>
  )
}
