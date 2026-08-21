import { describe, expect, it } from 'vitest'
import { SegmentState } from '@/api/generated/model/segmentState'
import type { Stage } from '@/api/generated/model/stage'
import { buildPreviewRibbon, previewChain } from './previewRibbon'

/**
 * B-041 · the live ribbon preview, tested as data rather than through a render.
 *
 * The rendering is B-050's and has its own 44 tests. What is this task's is the
 * *translation* — a template has no history, and the whole correctness question
 * is which fields are therefore absent and which state each segment carries. A
 * render test would assert that a stage's name appears on screen, which
 * `RibbonSegment.test.tsx` already covers for real ribbons.
 */

const stage = (over: Partial<Stage>): Stage => ({
  id: 1,
  templateId: 1,
  stageCode: 'DEV',
  displayName: 'Development',
  ownerRole: 'DEVELOPER',
  slaHours: 8,
  isOptional: false,
  canReturnTo: [],
  icon: 'code-2',
  seq: 10,
  position: 1,
  transitionCount: 0,
  openTicketCount: 0,
  isCodeEditable: true,
  isDeprecated: false,
  deprecatedAt: null,
  isDeletable: true,
  ...over,
})

describe('the preview carries what a template knows', () => {
  it('orders by seq rather than by the array it was handed', () => {
    const ribbon = buildPreviewRibbon([
      stage({ id: 2, stageCode: 'QA', displayName: 'QA', seq: 20 }),
      stage({ id: 1, stageCode: 'INTAKE', displayName: 'Intake', seq: 10 }),
    ])

    expect(ribbon.segments?.map((s) => s.stageCode)).toEqual(['INTAKE', 'QA'])
    // 1-based and renumbered from the sorted order, not copied from `seq` —
    // B-004 spaces seq 10/20/30 so a stage can be inserted between two others.
    expect(ribbon.segments?.map((s) => s.sequence)).toEqual([1, 2])
  })

  /**
   * The assertion this file exists for. A `0` in `effortHrs` or `durationMins`
   * renders as a measurement of zero, which is a claim about a ticket that does
   * not exist; `enteredAt` would make the preview look like a journey somebody
   * could click into.
   */
  it('leaves every history field undefined rather than zero', () => {
    const [segment] = buildPreviewRibbon([stage({})]).segments ?? []

    expect(segment.durationMins).toBeUndefined()
    expect(segment.effortHrs).toBeUndefined()
    expect(segment.idleMins).toBeUndefined()
    expect(segment.enteredAt).toBeUndefined()
    expect(segment.exitedAt).toBeUndefined()
    expect(segment.owner).toBeUndefined()
    expect(segment.loopBackCount).toBeUndefined()
  })

  it('keeps the owner role, which is a fact about the definition', () => {
    const [segment] = buildPreviewRibbon([stage({ ownerRole: 'QA' })]).segments ?? []

    expect(segment.ownerRole).toBe('QA')
  })
})

describe('the states', () => {
  /**
   * No CURRENT anywhere, and the reason is `RibbonStrip`: it hangs the
   * contextual handoff button off the index of the current segment, so a preview
   * with one would be inviting a handoff on a template.
   */
  it('marks every live stage PENDING and never CURRENT', () => {
    const ribbon = buildPreviewRibbon([
      stage({ id: 1, stageCode: 'INTAKE', seq: 10 }),
      stage({ id: 2, stageCode: 'DEV', seq: 20 }),
      stage({ id: 3, stageCode: 'QA', seq: 30 }),
    ])

    expect(ribbon.segments?.every((s) => s.state === SegmentState.PENDING)).toBe(true)
    expect(ribbon.currentStageCode).toBeUndefined()
  })

  it('never lets the preview claim it can advance', () => {
    expect(buildPreviewRibbon([stage({})]).canAdvance).toBe(false)
  })

  /**
   * B-042's retired stage. SKIPPED rather than a seventh state invented for the
   * preview — a shape no ticket page can produce would be a preview of something
   * that cannot happen.
   */
  it('shows a deprecated stage as skipped, with a reason', () => {
    const [segment] = buildPreviewRibbon([stage({ isDeprecated: true })]).segments ?? []

    expect(segment.state).toBe(SegmentState.SKIPPED)
    expect(segment.skipReason).toMatch(/Deprecated/)
  })

  it('gives a live stage no skip reason at all', () => {
    const [segment] = buildPreviewRibbon([stage({ isDeprecated: false })]).segments ?? []

    expect(segment.skipReason).toBeUndefined()
  })
})

describe('the chain sentence', () => {
  it('reads §4A.9 order with arrows', () => {
    expect(previewChain([
      stage({ id: 2, displayName: 'Triage', seq: 20 }),
      stage({ id: 1, displayName: 'Intake', seq: 10 }),
      stage({ id: 3, displayName: 'Closed', seq: 30 }),
    ])).toBe('Intake → Triage → Closed')
  })

  /**
   * Live stages only. A retired one is not part of the route a *new* ticket
   * takes, so including it would describe a longer flow than any new ticket will
   * follow — while the ribbon above still draws it, because tickets already past
   * it still render it.
   */
  it('omits a deprecated stage, which the ribbon above still draws', () => {
    const stages = [
      stage({ id: 1, displayName: 'Intake', seq: 10 }),
      stage({ id: 2, displayName: 'Old Review', seq: 20, isDeprecated: true }),
      stage({ id: 3, displayName: 'Closed', seq: 30 }),
    ]

    expect(previewChain(stages)).toBe('Intake → Closed')
    expect(buildPreviewRibbon(stages).segments).toHaveLength(3)
  })

  it('is empty for a template with no stages', () => {
    expect(previewChain([])).toBe('')
    expect(buildPreviewRibbon([]).segments).toEqual([])
  })
})
