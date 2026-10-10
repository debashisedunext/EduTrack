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
 * apply", and folding them would print **On time** against a project nobody
 * has started — which reads as praise for work that has not happened.
 *
 * <h2>Started is a question about the work, not about the gate</h2>
 *
 * This used to answer it with `gateStatus`, and returned **Not started** for
 * every `LOCKED` project before it looked at anything else. The gate is the
 * *client's prerequisite checklist* — `ObPrerequisiteGateService` is the only
 * thing that opens it — and it is advisory: a journey activates its steps
 * "whether or not its client's checklist has cleared". So a project could run
 * to 100%, with every module service complete, and still print **Not started**
 * under a header reading 5 of 5 tasks completed. That is the bug this fixed.
 *
 * What the wire can actually answer it with is the work: a project with a
 * finished stage behind it, or one running now, has plainly started. The
 * remaining gap is a project whose tasks have all begun and are all blocked —
 * `currentStage` is null there too, so it still reads as not started. Closing
 * that needs a started-at on the project rather than a better guess here.
 */
export function delayCell(project: ObProject): DelayCell {
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
  if (notStarted(project)) {
    return {
      label: 'Not started',
      tone: 'unknown',
      hint: 'No clock is running: no task of this project has been finished, and none is running now.',
    }
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
 * Nothing finished, and nothing running — see the note on {@link delayCell}.
 *
 * <p>`stagesComplete` counts the project's finished stages across every module
 * service, and `currentStage` names the earliest one running in any of them.
 * Both empty is the only reading of the wire under which no task has ever been
 * worked.
 */
function notStarted(project: ObProject): boolean {
  return project.stagesComplete === 0 && project.currentStage == null
}

/**
 * What the Current stage column prints.
 */
export function currentStageLabel(project: ObProject): string {
  if (project.currentStage) return project.currentStage
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
