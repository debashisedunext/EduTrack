import type { ObProject } from '@/api/generated/model/obProject'

/**
 * The cell logic behind the Projects grid. Pure, and tested on its own, because
 * every one of these decisions is a sentence somebody will argue with.
 */

export type DelayTone = 'ok' | 'late' | 'unknown'

export interface DelayCell {
  label: string
  tone: DelayTone
  /** The `title` a reader gets on hover, saying why the cell reads as it does. */
  hint: string
}

/**
 * Three states, not two, and the third is the one that matters.
 *
 * `delayedByDays` is null both for "on time" and for "the question does not
 * apply", and folding them would print **On time** against a project whose gate
 * has never opened — which reads as praise for work nobody has started.
 * `gateStatus` is what separates them.
 */
export function delayCell(project: ObProject): DelayCell {
  if (project.gateStatus === 'LOCKED') {
    return {
      label: 'Not started',
      tone: 'unknown',
      hint: 'No clock is running: this project’s journeys are still behind the prerequisite gate.',
    }
  }
  if (project.status === 'ON_HOLD' || project.status === 'DROPPED') {
    return {
      label: '—',
      tone: 'unknown',
      hint: 'Delay is not counted while a project is on hold or dropped — the clock was stopped on purpose.',
    }
  }
  if (project.status === 'COMPLETED') {
    return { label: 'Completed', tone: 'ok', hint: 'Every module service of this project is done.' }
  }
  if (project.delayedByDays == null) {
    return { label: 'On time', tone: 'ok', hint: 'Nothing is past its due date.' }
  }
  return {
    label: `${project.delayedByDays} d`,
    tone: 'late',
    hint: 'Working days past the earliest overdue task’s due date — weekends and holidays excluded.',
  }
}

/**
 * What the Current stage column prints.
 *
 * A locked project has no running stage and saying "—" would look like missing
 * data; §9's own words for that state are "Prerequisites pending", and this is
 * the column that carries them.
 */
export function currentStageLabel(project: ObProject): string {
  if (project.currentStage) return project.currentStage
  if (project.gateStatus === 'LOCKED') return 'Prerequisites pending'
  if (project.status === 'COMPLETED') return 'Complete'
  return '—'
}

/**
 * `2/5`, and the fraction of the bar to fill.
 *
 * A project with no stages at all is a misconfigured Module Service rather than
 * a finished project, so it fills nothing — dividing by zero to reach 100% is
 * the one answer that would be actively misleading.
 */
export function stageProgress(project: ObProject): { label: string; fraction: number } {
  const total = project.stagesTotal ?? 0
  const complete = project.stagesComplete ?? 0
  return {
    label: `${complete}/${total}`,
    fraction: total > 0 ? Math.min(1, complete / total) : 0,
  }
}

/** `15 Sep 2026`, in the viewer's locale, from the server's plain date. */
export function formatDate(value: string | null | undefined): string {
  if (!value) return '—'
  const parsed = new Date(`${value}T00:00:00`)
  if (Number.isNaN(parsed.getTime())) return '—'
  return parsed.toLocaleDateString(undefined, { day: '2-digit', month: 'short', year: 'numeric' })
}
