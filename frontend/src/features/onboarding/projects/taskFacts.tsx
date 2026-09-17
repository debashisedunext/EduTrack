import { cn } from '@/lib/utils'

import { formatDue, overdue } from './taskDates'

/**
 * The vital signs of one task, as a line on its own strip.
 *
 * <h2>Why these live on the strip rather than under it</h2>
 *
 * <p>Responsible, TAT and due were part of a six-fact band inside the open
 * panel — a row of label/value pairs that only existed once the task was
 * expanded, and that repeated the owner the strip was already printing on its
 * right-hand edge. They are read at a glance rather than studied, so they
 * belong on the row that is already there: the band is gone and the strip
 * carries them, which saves a line per open task and makes the facts legible
 * without opening anything.
 *
 * <h2>Three facts, not five</h2>
 *
 * <p><b>Position and TAT used are gone.</b> The rail is the right-hand end of a
 * row that already carries the task name, its status and its check-list pill,
 * and at laptop width five facts wrapped it onto a second line — so the two
 * that earned their place least came off.
 *
 * <p>"Task 2 of 4" restated the list the reader is looking down: the rows are
 * in that order, under a Step heading that counts them, so it numbered
 * something already numbered by position on the screen. TAT used is a
 * derivative of the two facts either side of it — a due date and a budget —
 * and where it mattered it said what **Due … (missed)** says in words, in red,
 * without asking anybody to read a percentage against 100.
 *
 * <p>Neither is lost data: `tatUsedPercent` is still on `ProjectTask` and still
 * what {@link ObProjectTaskPanel} and the My tasks focus view read. This is a
 * decision about one crowded rail, not about the figure.
 *
 * <h2>Fixed order, right-aligned</h2>
 *
 * <p>Who · TAT · due, in that order on every task in a stage, so the eye lands
 * in the same place down the column rather than re-reading a row whose facts
 * moved because one of them was absent. An absent fact leaves no gap — the rail
 * is a flex line, not a grid — but the ones present never swap places.
 *
 * <h2>Tone is the only colour</h2>
 *
 * <p>Due goes red once missed, which is the whole of what this rail colours.
 */
export interface TaskFactsProps {
  /** Resolved display name, or null where nobody is responsible. */
  ownerName: string | null
  /**
   * The owner came from the project's implementor rather than from the module
   * service — see `ObJourneyStepView.ownerIsInherited`. Labelled, because an
   * inherited owner read as a deliberate one is a quiet way to make somebody
   * accountable for a task nobody assigned them.
   */
  ownerIsInherited?: boolean
  tatDays: number
  dueAt?: string | null
}

export function TaskFacts({ ownerName, ownerIsInherited, tatDays, dueAt }: TaskFactsProps) {
  const missed = overdue(dueAt)
  return (
    <span className="ml-auto flex flex-wrap items-center gap-x-2 gap-y-0.5 text-caption tabular-nums text-content-muted">
      {ownerName ? (
        <span className="inline-flex items-center gap-1.5">
          <Avatar name={ownerName} />
          <span className="font-semibold text-content">{ownerName}</span>
          {ownerIsInherited && (
            <span
              title="No responsible on the module service — the project's implementor picks it up"
              className="rounded-chip border border-primary bg-surface px-1.5 text-[10.5px] font-medium text-primary"
            >
              project implementor
            </span>
          )}
        </span>
      ) : (
        <span className="font-semibold text-danger-text">Nobody responsible</span>
      )}

      <Sep />
      <span>
        TAT{' '}
        <b className="font-semibold text-content">{`${tatDays} ${tatDays === 1 ? 'wd' : 'wds'}`}</b>
      </span>

      {dueAt && (
        <>
          <Sep />
          <span>
            Due{' '}
            <b className={cn('font-semibold', missed ? 'text-danger-text' : 'text-content')}>
              {formatDue(dueAt)}
              {missed ? ' (missed)' : ''}
            </b>
          </span>
        </>
      )}
    </span>
  )
}

function Sep() {
  return (
    <span aria-hidden="true" className="text-border">
      ·
    </span>
  )
}

export function Avatar({ name }: { name: string }) {
  const initials = name
    .split(/\s+/)
    .map((w) => w[0])
    .join('')
    .slice(0, 2)
    .toUpperCase()
  return (
    <span
      aria-hidden="true"
      className="flex size-[19px] shrink-0 items-center justify-center rounded-chip bg-primary-soft text-[9px] font-bold text-primary"
    >
      {initials}
    </span>
  )
}
