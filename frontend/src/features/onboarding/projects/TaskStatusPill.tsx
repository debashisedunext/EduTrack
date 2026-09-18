import { cn } from '@/lib/utils'

import { TASK_STATUS_LABEL } from './taskStatus'

/**
 * A task's status as a pill, in the places that have room to say it in words —
 * the task popup, and anywhere else asking for the status rather than scanning
 * a column of them.
 *
 * <p>The task strip wears {@link TaskStatusDot} instead: four colours in a
 * fixed column, scanned rather than read. Both take their words from
 * `taskStatus.ts`, so the pill and the dot's tooltip cannot spell a status two
 * different ways.
 */

const STATUS_PILL: Record<string, string> = {
  PENDING: 'bg-subtle text-content-muted',
  IN_PROGRESS: 'border border-primary bg-surface text-primary',
  BLOCKED: 'bg-danger-soft text-danger-text',
  WAITING_ON_CLIENT: 'bg-level-high-soft text-warning-text',
  DONE: 'bg-level-low-soft text-success-text',
  SKIPPED: 'bg-subtle text-content-muted',
}

export const PILL = 'rounded-chip px-2 py-px text-[10.5px] font-semibold whitespace-nowrap'

export function TaskStatusPill({ status }: { status: string }) {
  return (
    <span className={cn(PILL, STATUS_PILL[status] ?? STATUS_PILL.PENDING)}>
      {TASK_STATUS_LABEL[status] ?? status}
    </span>
  )
}
