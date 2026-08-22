import { describe, expect, it } from 'vitest'

import type { RibbonSegment as RibbonSegmentData } from '@/api/generated/model/ribbonSegment'
import { SegmentState } from '@/api/generated/model/segmentState'
import { buildRibbonRows, collapsedGroupAriaLabel } from './collapsedGroup'

function seg(stageCode: string, state: SegmentState, over: Partial<RibbonSegmentData> = {}): RibbonSegmentData {
  return {
    stageCode,
    displayName: stageCode[0] + stageCode.slice(1).toLowerCase(),
    state,
    sequence: 1,
    iterationNo: 1,
    loopBackCount: 0,
    ...over,
  }
}

describe('buildRibbonRows', () => {
  it('leaves every segment as its own row when three or fewer are completed', () => {
    const segments = [
      seg('INTAKE', SegmentState.COMPLETED),
      seg('TRIAGE', SegmentState.COMPLETED),
      seg('DEV', SegmentState.COMPLETED),
      seg('QA', SegmentState.CURRENT),
      seg('DEPLOY', SegmentState.PENDING),
    ]

    const rows = buildRibbonRows(segments)
    expect(rows).toEqual(segments.map((segment) => ({ kind: 'segment', segment })))
  })

  it('collapses completed stages beyond the first three into one group', () => {
    const segments = [
      seg('INTAKE', SegmentState.COMPLETED),
      seg('TRIAGE', SegmentState.COMPLETED),
      seg('DEV', SegmentState.COMPLETED),
      seg('QA', SegmentState.COMPLETED),
      seg('DEPLOY', SegmentState.COMPLETED),
      seg('VERIFY', SegmentState.CURRENT),
      seg('SIGNOFF', SegmentState.PENDING),
    ]

    const rows = buildRibbonRows(segments)

    expect(rows).toHaveLength(6)
    expect(rows[0]).toEqual({ kind: 'segment', segment: segments[0] })
    expect(rows[1]).toEqual({ kind: 'segment', segment: segments[1] })
    expect(rows[2]).toEqual({ kind: 'segment', segment: segments[2] })
    expect(rows[3]).toMatchObject({ kind: 'group', segments: [segments[3], segments[4]], expanded: false })
    expect(rows[4]).toEqual({ kind: 'segment', segment: segments[5] })
    expect(rows[5]).toEqual({ kind: 'segment', segment: segments[6] })
  })

  it('never collapses a non-completed state, even past the third completed segment', () => {
    const reworked = seg('DEV', SegmentState.REWORKED, { loopBackCount: 1 })
    const segments = [
      seg('INTAKE', SegmentState.COMPLETED),
      seg('TRIAGE', SegmentState.COMPLETED),
      seg('DESIGN', SegmentState.COMPLETED),
      seg('BUILD', SegmentState.COMPLETED),
      reworked,
      seg('QA', SegmentState.CURRENT),
    ]

    const rows = buildRibbonRows(segments)

    // BUILD is the only segment past the third completed one, so it is a
    // group of one rather than three separate tiles — and the reworked
    // stage right after it is never folded in.
    expect(rows).toHaveLength(6)
    expect(rows[3]).toMatchObject({ kind: 'group', segments: [segments[3]], expanded: false })
    expect(rows[4]).toEqual({ kind: 'segment', segment: reworked })
    expect(rows[5]).toEqual({ kind: 'segment', segment: segments[5] })
  })

  it('splits into two groups when a non-completed stage interrupts a completed run', () => {
    const skipped = seg('TRIAGE', SegmentState.SKIPPED, { skipReason: 'hotfix' })
    const segments = [
      seg('INTAKE', SegmentState.COMPLETED),
      seg('DESIGN', SegmentState.COMPLETED),
      seg('SPEC', SegmentState.COMPLETED),
      seg('BUILD', SegmentState.COMPLETED),
      skipped,
      seg('QA', SegmentState.COMPLETED),
      seg('DEPLOY', SegmentState.COMPLETED),
      seg('VERIFY', SegmentState.CURRENT),
    ]

    const rows = buildRibbonRows(segments)

    expect(rows.filter((row) => row.kind === 'group')).toHaveLength(2)
    expect(rows).toEqual([
      { kind: 'segment', segment: segments[0] },
      { kind: 'segment', segment: segments[1] },
      { kind: 'segment', segment: segments[2] },
      { kind: 'group', key: 'BUILD:1:1', segments: [segments[3]], expanded: false },
      { kind: 'segment', segment: skipped },
      { kind: 'group', key: 'QA:1:2', segments: [segments[5], segments[6]], expanded: false },
      { kind: 'segment', segment: segments[7] },
    ])
  })

  it('expands a group back into its individual segments when its key is passed', () => {
    const segments = [
      seg('INTAKE', SegmentState.COMPLETED),
      seg('TRIAGE', SegmentState.COMPLETED),
      seg('DESIGN', SegmentState.COMPLETED),
      seg('BUILD', SegmentState.COMPLETED),
      seg('QA', SegmentState.CURRENT),
    ]

    const collapsed = buildRibbonRows(segments)
    const key = (collapsed[3] as { key: string }).key
    const expanded = buildRibbonRows(segments, new Set([key]))

    expect(expanded).toEqual([
      { kind: 'segment', segment: segments[0] },
      { kind: 'segment', segment: segments[1] },
      { kind: 'segment', segment: segments[2] },
      { kind: 'group', key, segments: [segments[3]], expanded: true },
      { kind: 'segment', segment: segments[3] },
      { kind: 'segment', segment: segments[4] },
    ])
  })

  it('handles no segments and no completed segments at all', () => {
    expect(buildRibbonRows([])).toEqual([])

    const allPending = [seg('INTAKE', SegmentState.PENDING), seg('TRIAGE', SegmentState.PENDING)]
    expect(buildRibbonRows(allPending)).toEqual(allPending.map((segment) => ({ kind: 'segment', segment })))
  })
})

describe('collapsedGroupAriaLabel', () => {
  it('names the count and every hidden stage when collapsed', () => {
    const segments = [seg('QA', SegmentState.COMPLETED), seg('DEPLOY', SegmentState.COMPLETED)]
    expect(collapsedGroupAriaLabel(segments, false)).toBe('2 completed stages collapsed: Qa, Deploy')
  })

  it('reads as singular for a group of one', () => {
    expect(collapsedGroupAriaLabel([seg('BUILD', SegmentState.COMPLETED)], false)).toBe(
      '1 completed stage collapsed: Build',
    )
  })

  it('offers to collapse, not expand, once it already is expanded', () => {
    const segments = [seg('QA', SegmentState.COMPLETED)]
    expect(collapsedGroupAriaLabel(segments, true)).toBe('Collapse 1 completed stage: Qa')
  })

  it('falls back to the stage code when a segment has no display name', () => {
    const segment = seg('QA', SegmentState.COMPLETED, { displayName: undefined })
    expect(collapsedGroupAriaLabel([segment], false)).toBe('1 completed stage collapsed: QA')
  })
})
