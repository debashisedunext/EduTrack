import type { Ribbon } from '@/api/generated/model/ribbon'
import type { RibbonSegment } from '@/api/generated/model/ribbonSegment'
import type { RoleCode } from '@/api/generated/model/roleCode'
import { SegmentState } from '@/api/generated/model/segmentState'
import type { Stage } from '@/api/generated/model/stage'

/**
 * B-041 · §7.4's *"a live ribbon preview renders as the Admin edits, so the flow
 * is validated visually before saving"*.
 *
 * <h2>Why this is a function and not a component</h2>
 *
 * B-050 already built the ribbon — `RibbonSegment`, `RibbonStrip`, six states,
 * five data points, the loop-back badge, the keyboard handling and the ARIA. A
 * second renderer here would be a second thing to keep in step with §4A.3, and
 * the first time the two diverged the preview would stop being a preview: it
 * would be showing the Admin something other than what a ticket will show.
 *
 * So the preview is a **data** problem rather than a rendering one. This turns a
 * stage list into the `Ribbon` shape `RibbonStrip` already draws, and the strip
 * is used unmodified.
 *
 * <h2>What a template does not have</h2>
 *
 * A `Ribbon` is a *ticket's* journey. Most of what makes a segment interesting —
 * who owned it, when it was entered and left, how long it sat idle, how many
 * times it was reworked — is history, and a template has none. Every one of
 * those fields is therefore left `undefined` rather than filled with a zero or a
 * placeholder date:
 *
 * - `RibbonSegment` renders each data point only when it is present, so absent
 *   fields produce a segment that shows what a template knows and nothing more.
 * - A `0` in `effortHrs` or `durationMins` would render as a real measurement of
 *   zero, which is a claim about a ticket that does not exist. `enteredAt` is
 *   worse: a date would make the preview look like a journey somebody could
 *   click into.
 *
 * `slaHours` is the exception and is deliberately carried through, because it is
 * the one number that belongs to the *stage definition* rather than to a run of
 * it — it is what the Admin is editing on tab 2, and seeing it on the segment is
 * half of what makes the preview worth having.
 *
 * <h2>The states</h2>
 *
 * Every segment is `PENDING`, and there is **no `CURRENT`**. That is the
 * decision most likely to be revisited, so: a current segment would place the
 * ribbon's "you are here" ring somewhere on a flow no ticket is standing in, and
 * `RibbonStrip` hangs its contextual action button off exactly that index. The
 * preview would then be inviting a handoff on a template. `canAdvance: false`
 * would suppress the button and would not suppress the ring.
 *
 * A deprecated stage is the one exception, and it uses `SKIPPED`. §4A.3's
 * skipped state is the closest thing the vocabulary has to "part of this flow,
 * not entered" — B-042 retires a stage precisely so that nothing new enters it
 * while it keeps rendering. Inventing a seventh state for the preview alone
 * would put a shape on the ribbon that no ticket page can produce.
 */
export function buildPreviewRibbon(stages: Stage[]): Ribbon {
  const ordered = [...stages].sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0))

  const segments: RibbonSegment[] = ordered.map((stage, index) => ({
    stageCode: stage.stageCode,
    displayName: stage.displayName,
    // `icon` is the template's arbitrary lucide name and `RibbonSegment` takes a
    // closed `RibbonSegmentIcon`. C-051 records that no screen resolves an
    // arbitrary one — it costs the whole icon set or a dynamic import per
    // segment — so the preview omits it and gets §4A.3's state icon, which is
    // what the blueprint's own diagram puts in that position anyway.
    state: stage.isDeprecated ? SegmentState.SKIPPED : SegmentState.PENDING,
    sequence: index + 1,
    ownerRole: stage.ownerRole as RoleCode | undefined,
    // Working hours on the definition, converted to the minutes the segment
    // renders. The only measurement a template legitimately has.
    durationMins: undefined,
    effortHrs: undefined,
    idleMins: undefined,
    iterationNo: 1,
    skipReason: stage.isDeprecated
      ? 'Deprecated — this stage keeps rendering on tickets already past it and accepts nothing new.'
      : undefined,
  }))

  return {
    cycleNo: 1,
    iterationNo: 1,
    // Not sealed: sealing means "a past cycle, read-only", and a template is
    // neither past nor a cycle. It changes nothing `RibbonStrip` renders, and
    // claiming it would be claiming something false about a ticket.
    isSealed: false,
    currentStageCode: undefined,
    // The one field that must be false. `RibbonStrip` hangs its handoff button
    // off `canAdvance` and the current index — and with no current segment the
    // button cannot render anyway, so this is belt and braces on the one control
    // that would let a preview look actionable.
    canAdvance: false,
    segments,
  }
}

/**
 * The sentence under the preview: what this flow does, in order.
 *
 * §4A.9 describes each template as an arrow chain ("Intake → Triage →
 * Development → Sign-off → Closed"), and that is how an Admin recognises a flow
 * they are about to map to a project. Live stages only — a retired one is not
 * part of the route a new ticket takes, and including it would describe a longer
 * flow than any new ticket will follow.
 */
export function previewChain(stages: Stage[]): string {
  return [...stages]
    .sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0))
    .filter((s) => !s.isDeprecated)
    .map((s) => s.displayName ?? s.stageCode ?? '?')
    .join(' → ')
}
