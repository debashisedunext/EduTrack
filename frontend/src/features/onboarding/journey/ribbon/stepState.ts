import { CircleDashed, Loader, CheckCircle2, Clock, Ban } from 'lucide-react'

import type { JourneyStep } from './types'

/**
 * C-109 · the onboarding ribbon's five step states plus the breach overlay,
 * as data — `components/ribbon/segmentState.ts`'s own split between "what a
 * state looks like" and the component that draws it, followed here rather
 * than imported, per PHASE-2-BUILD-PLAN.md's "built fresh" line for this
 * task (§"The four tasks worth watching", C-109).
 *
 * ## Breach is an overlay, not a sixth state
 *
 * Same call the phase-1 ribbon made for `--ribbon-breached` and left unused:
 * a step running over its TAT budget is still CURRENT or BLOCKED, just late.
 * `treatmentFor` reads `tatPercent` alongside `status` for exactly the two
 * statuses Onboarding-Module-Plan.md's prototype (`segClass`) overlays it on
 * — a PENDING step has not started its clock, a WAITING one is paused by
 * definition (§5.7), and DONE has already closed with its own on-time/
 * early/late marker, so none of the three has a "still running" fact left to
 * overlay.
 *
 * ## Colour is never the only signal
 *
 * Every state carries an icon and a word, same rule CLAUDE.md's WCAG AA line
 * and `segmentState.ts`'s own header state — the amber/red pair below is the
 * third channel, not the first.
 */

export interface StepTreatment {
  Icon: typeof CheckCircle2
  label: string
  /** Card surface, border and text — Tailwind utilities onto the `ribbon.*` tokens. */
  card: string
  title: string
  connector: string
  isBreached: boolean
}

const PENDING: StepTreatment = {
  Icon: CircleDashed,
  label: 'Pending',
  card: 'border-ribbon-pending bg-ribbon-pending-bg text-ribbon-pending-text',
  title: 'text-ribbon-pending-text',
  connector: 'border-t-2 border-dashed border-ribbon-pending',
  isBreached: false,
}

const CURRENT: StepTreatment = {
  Icon: Loader,
  label: 'In progress',
  card: 'border-ribbon-current bg-ribbon-current-bg text-ribbon-current-text ring-2 ring-ribbon-current',
  title: 'text-ribbon-current-text',
  connector: 'bg-ribbon-pending',
  isBreached: false,
}

const DONE: StepTreatment = {
  Icon: CheckCircle2,
  label: 'Done',
  card: 'border-ribbon-done bg-ribbon-done-bg text-ribbon-done-text',
  title: 'text-ribbon-done-text',
  connector: 'bg-ribbon-done',
  isBreached: false,
}

const WAITING: StepTreatment = {
  Icon: Clock,
  label: 'Waiting on client',
  card: 'border-ribbon-waiting bg-ribbon-waiting-bg text-ribbon-waiting-text',
  title: 'text-ribbon-waiting-text',
  connector: 'border-t-2 border-dashed border-ribbon-pending',
  isBreached: false,
}

const BLOCKED: StepTreatment = {
  Icon: Ban,
  label: 'Blocked',
  card: 'border-ribbon-blocked bg-ribbon-blocked-bg text-ribbon-blocked-text border-l-4',
  title: 'text-ribbon-blocked-text',
  connector: 'border-t-2 border-dashed border-ribbon-pending',
  isBreached: false,
}

const BREACHED_CURRENT: StepTreatment = {
  ...CURRENT,
  label: 'Breached',
  card: 'border-danger bg-level-critical-soft text-danger-text ring-2 ring-danger',
  title: 'text-danger-text',
  isBreached: true,
}

const BREACHED_BLOCKED: StepTreatment = {
  ...BLOCKED,
  label: 'Blocked · breached',
  card: 'border-danger bg-level-critical-soft text-danger-text border-l-4',
  title: 'text-danger-text',
  isBreached: true,
}

const STEP_TREATMENT: Record<JourneyStep['status'], StepTreatment> = {
  PENDING,
  CURRENT,
  DONE,
  WAITING,
  BLOCKED,
}

/** Whichever the scanner (or, before OB3 lands, `tatPercent` alone) says has
 * run past its TAT budget — §5.11's Amber/Red pattern, Red end. */
function isOverTat(step: JourneyStep): boolean {
  return (step.status === 'CURRENT' || step.status === 'BLOCKED') && (step.tatPercent ?? 0) >= 100
}

export function treatmentFor(step: JourneyStep): StepTreatment {
  if (isOverTat(step)) {
    return step.status === 'BLOCKED' ? BREACHED_BLOCKED : BREACHED_CURRENT
  }
  return STEP_TREATMENT[step.status] ?? PENDING
}

/** §5.11's amber line, default 75% — OB-11 will make this admin-editable;
 * until it's wired the module plan's own locked default is the constant. */
export const DEFAULT_AMBER_THRESHOLD = 75

export type TatBarLevel = 'ok' | 'amber' | 'red'

export function tatBarLevel(tatPercent: number | null, amberThreshold = DEFAULT_AMBER_THRESHOLD): TatBarLevel {
  if (tatPercent == null) return 'ok'
  if (tatPercent >= 100) return 'red'
  if (tatPercent >= amberThreshold) return 'amber'
  return 'ok'
}

export interface StepEmoji {
  glyph: string
  /** Tailwind animation utility, `motion-reduce:animate-none` applied at the call site. */
  animationClass: string
  label: string
}

/**
 * Onboarding-Module-Plan.md §9, OB-05's animated status emojis — five states,
 * ported one-for-one from the prototype's own `stEmoji`: 👍 done on time ·
 * 🙌 completed early · 👎 closed delayed (or running breached) · 👏 in
 * progress on time · 😢 blocked/waiting. PENDING draws none, same as the
 * prototype returning `""`.
 */
export function statusEmoji(step: JourneyStep): StepEmoji | null {
  if (step.status === 'DONE') {
    if (step.closed === 'early') return { glyph: '🙌', animationClass: 'animate-emo-pop', label: 'Completed before time' }
    if (step.closed === 'late') return { glyph: '👎', animationClass: 'animate-emo-shake', label: 'Closed delayed' }
    return { glyph: '👍', animationClass: 'animate-emo-bounce', label: 'Done on time' }
  }
  if (step.status === 'BLOCKED' || step.status === 'WAITING') {
    return { glyph: '😢', animationClass: 'animate-emo-sad', label: 'Blocked / waiting' }
  }
  if (step.status === 'CURRENT') {
    return isOverTat(step)
      ? { glyph: '👎', animationClass: 'animate-emo-shake', label: 'Running delayed' }
      : { glyph: '👏', animationClass: 'animate-emo-clap', label: 'On time' }
  }
  return null
}

/** §5.6's connector label — `↳ N` for a declared dependency, `∥` for a step
 * that runs in parallel because it has none. */
export function dependencyBadge(step: JourneyStep): string {
  return step.dependsOnSeqNo != null ? `↳ ${step.dependsOnSeqNo}` : '∥'
}

export function ownerLabel(step: JourneyStep): string {
  if (step.owner?.displayName) return step.owner.displayName
  if (step.ownerRole) return `${step.ownerRole} · unassigned`
  return 'Unassigned'
}

/**
 * §4A.3's precedent, applied to a journey step: name, owner, state and the
 * fact that decides most of what a reader wants to know next — a running
 * step's TAT percent, or a closed one's on-time/early/late marker.
 */
export function stepAriaLabel(step: JourneyStep): string {
  const treatment = treatmentFor(step)
  const parts = [`Step ${step.seqNo}: ${step.name}`, ownerLabel(step), treatment.label]

  if (step.status === 'DONE') {
    parts.push(step.closed === 'early' ? 'completed early' : step.closed === 'late' ? 'closed delayed' : 'closed on time')
  } else if (step.tatPercent != null) {
    parts.push(`${Math.round(step.tatPercent)}% of TAT used`)
  } else {
    parts.push(`${step.tatDays} day TAT budget`)
  }

  parts.push(step.dependsOnSeqNo != null ? `depends on step ${step.dependsOnSeqNo}` : 'runs in parallel')

  if (step.status === 'BLOCKED' && step.note) {
    parts.push(`blocked: ${step.note}`)
  }

  return parts.join(', ')
}
