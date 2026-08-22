import { describe, expect, it } from 'vitest'

import type { WorkflowStage } from '@/api/generated/model/workflowStage'
import { SegmentState } from '@/api/generated/model/segmentState'
import {
  buildCompactDots,
  compactDotsAriaLabel,
  dotTitle,
  dotWord,
  type CompactDotTicket,
} from './compactDots'

/** §4A.1's Standard Dev Flow, in the vocabulary shape
 * `GET /masters/workflow-templates` serves — the same eight the mock seeds. */
const STANDARD: WorkflowStage[] = [
  { stageCode: 'INTAKE', displayName: 'Intake', sequence: 1, ownerRole: 'SUPPORT' },
  { stageCode: 'TRIAGE', displayName: 'Triage / Planning', sequence: 2, ownerRole: 'PM' },
  { stageCode: 'DEV', displayName: 'Development', sequence: 3, ownerRole: 'DEVELOPER' },
  { stageCode: 'QA', displayName: 'QA / Testing', sequence: 4, ownerRole: 'QA' },
  { stageCode: 'DEPLOY', displayName: 'Deployment', sequence: 5, ownerRole: 'DEPLOYMENT' },
  { stageCode: 'VERIFY', displayName: 'Verification', sequence: 6, ownerRole: 'DEVELOPER' },
  { stageCode: 'SIGNOFF', displayName: 'Sign-off', sequence: 7, ownerRole: 'PM' },
  { stageCode: 'CLOSED', displayName: 'Closed', sequence: 8, ownerRole: 'PM' },
]

function ticket(over: Partial<CompactDotTicket> = {}): CompactDotTicket {
  return { currentStageCode: 'DEV', status: 'IN_PROGRESS', iterationNo: 1, ...over }
}

const states = (dots: ReturnType<typeof buildCompactDots>) => dots?.map((d) => d.state)

describe('B-051 · buildCompactDots — the four states of blueprint line 984', () => {
  it('fills every stage before the current one, rings the current, hollows the rest', () => {
    expect(states(buildCompactDots(STANDARD, ticket({ currentStageCode: 'QA' })))).toEqual([
      SegmentState.COMPLETED,
      SegmentState.COMPLETED,
      SegmentState.COMPLETED,
      SegmentState.CURRENT,
      SegmentState.PENDING,
      SegmentState.PENDING,
      SegmentState.PENDING,
      SegmentState.PENDING,
    ])
  })

  it('gives Standard Dev Flow eight dots — the count the blueprint names', () => {
    expect(buildCompactDots(STANDARD, ticket())).toHaveLength(8)
  })

  it('hollows every dot but the first for a ticket that has only just been raised', () => {
    expect(states(buildCompactDots(STANDARD, ticket({ currentStageCode: 'INTAKE', status: 'NEW' })))).toEqual([
      SegmentState.CURRENT,
      ...Array(7).fill(SegmentState.PENDING),
    ])
  })

  it('carries the stage name and owning role onto each dot', () => {
    const dots = buildCompactDots(STANDARD, ticket())!
    expect(dots[3]).toMatchObject({
      stageCode: 'QA',
      label: 'QA / Testing',
      ownerRole: 'QA',
    })
  })

  it('sorts by sequence rather than trusting arrival order', () => {
    // A journey rendered out of order is a journey that never happened.
    const shuffled = [STANDARD[2], STANDARD[0], STANDARD[1]]
    const dots = buildCompactDots(shuffled, ticket({ currentStageCode: 'TRIAGE' }))!
    expect(dots.map((d) => d.stageCode)).toEqual(['INTAKE', 'TRIAGE', 'DEV'])
    expect(states(dots)).toEqual([SegmentState.COMPLETED, SegmentState.CURRENT, SegmentState.PENDING])
  })
})

describe('B-051 · the amber dot means "sent back", and marks where the ticket is now', () => {
  it('turns the current dot amber once the ticket has been round the loop', () => {
    const dots = buildCompactDots(STANDARD, ticket({ currentStageCode: 'DEV', iterationNo: 2 }))!
    expect(dots[2].state).toBe(SegmentState.REWORKED)
  })

  it('turns it amber on a REWORK status too, before the iteration counter moves', () => {
    const dots = buildCompactDots(
      STANDARD,
      ticket({ currentStageCode: 'DEV', status: 'REWORK', iterationNo: 1 }),
    )!
    expect(dots[2].state).toBe(SegmentState.REWORKED)
  })

  it('leaves the completed dots green — which earlier stage bounced is not on this payload', () => {
    // The prototype paints a *completed* dot amber and hardcodes its index at
    // 2. Doing that here would be inventing the fact the colour is trusted
    // for: only `ticket_stage_transitions` knows, and S-17's row shape carries
    // `iterationNo` and nothing else about the loop.
    const dots = buildCompactDots(STANDARD, ticket({ currentStageCode: 'QA', iterationNo: 3 }))!
    expect(dots.slice(0, 3).map((d) => d.state)).toEqual([
      SegmentState.COMPLETED,
      SegmentState.COMPLETED,
      SegmentState.COMPLETED,
    ])
    expect(dots.filter((d) => d.state === SegmentState.REWORKED)).toHaveLength(1)
  })
})

describe('B-051 · a closed ticket has no current dot', () => {
  it('fills the terminal stage rather than ringing it', () => {
    const dots = buildCompactDots(
      STANDARD,
      ticket({ currentStageCode: 'CLOSED', status: 'CLOSED' }),
    )!
    expect(dots.every((d) => d.state === SegmentState.COMPLETED)).toBe(true)
  })

  it('fills every dot for a closed ticket carrying no stage code at all', () => {
    // The contract types `currentStageCode` nullable. Finishing the journey is
    // what closing a ticket means, so this is a fact rather than a guess.
    const dots = buildCompactDots(STANDARD, ticket({ currentStageCode: null, status: 'CLOSED' }))!
    expect(dots).toHaveLength(8)
    expect(dots.every((d) => d.state === SegmentState.COMPLETED)).toBe(true)
  })

  it('still rings the current stage of a RESOLVED ticket, which can be reopened', () => {
    const dots = buildCompactDots(
      STANDARD,
      ticket({ currentStageCode: 'SIGNOFF', status: 'RESOLVED' }),
    )!
    expect(dots[6].state).toBe(SegmentState.CURRENT)
    expect(dots[7].state).toBe(SegmentState.PENDING)
  })
})

describe('B-051 · null rather than a guess when the row cannot be placed', () => {
  it('returns null for a stage code the template does not contain', () => {
    // A ticket whose template has since been re-cut. Eight hollow dots would
    // claim it has not started; an index would claim where it is.
    expect(buildCompactDots(STANDARD, ticket({ currentStageCode: 'ARCHIVED' }))).toBeNull()
  })

  it('returns null for an open ticket with no stage code', () => {
    expect(buildCompactDots(STANDARD, ticket({ currentStageCode: null, status: 'NEW' }))).toBeNull()
  })

  it('returns null for a template with no stages', () => {
    expect(buildCompactDots([], ticket())).toBeNull()
    expect(buildCompactDots(undefined, ticket())).toBeNull()
  })

  it('ignores a stage row with no code rather than rendering a nameless dot', () => {
    const dots = buildCompactDots(
      [{ displayName: 'Nameless', sequence: 1 }, ...STANDARD],
      ticket({ currentStageCode: 'INTAKE' }),
    )!
    expect(dots).toHaveLength(8)
  })
})

describe('B-051 · a deprecated stage is still a dot', () => {
  it('keeps a retired stage on the ribbon of the ticket standing in it', () => {
    // The opposite call to the stage *filter*, which drops them so nobody
    // filters to a stage nothing new can enter. `WorkflowTemplateDetail.
    // stageCount`'s own doc: "Every stage, deprecated ones included — that is
    // the length a historical ticket renders."
    const withRetired: WorkflowStage[] = [
      ...STANDARD.slice(0, 4),
      { stageCode: 'UAT', displayName: 'UAT', sequence: 5, ownerRole: 'QA', isDeprecated: true },
      ...STANDARD.slice(4).map((s) => ({ ...s, sequence: (s.sequence ?? 0) + 1 })),
    ]
    const dots = buildCompactDots(withRetired, ticket({ currentStageCode: 'UAT' }))!
    expect(dots).toHaveLength(9)
    expect(dots[4]).toMatchObject({ stageCode: 'UAT', state: SegmentState.CURRENT })
  })
})

describe('B-051 · what a hover and a screen reader are told', () => {
  it('names the stage and its owner on a dot, as §S-17 asks', () => {
    const dots = buildCompactDots(STANDARD, ticket({ currentStageCode: 'QA' }))!
    expect(dotTitle(dots[3])).toBe('QA / Testing — QA · current stage')
    expect(dotTitle(dots[0])).toBe('Intake — Support · completed')
    expect(dotTitle(dots[7])).toBe('Closed — PM · not started')
  })

  it('reads a role the six-value map has never seen as its own code', () => {
    // S-09 lets an Admin add a seventh role; an undefined label would render
    // an em dash where a name belongs.
    expect(
      dotTitle({ stageCode: 'X', label: 'Audit', ownerRole: 'AUDITOR', state: SegmentState.PENDING }),
    ).toBe('Audit — AUDITOR · not started')
  })

  it('gives the whole strip one sentence, not eight', () => {
    const dots = buildCompactDots(STANDARD, ticket({ currentStageCode: 'QA' }))!
    expect(compactDotsAriaLabel(dots)).toBe('Journey: QA / Testing (QA), stage 4 of 8. 3 completed')
  })

  it('says so when the ticket has been sent back', () => {
    const dots = buildCompactDots(STANDARD, ticket({ currentStageCode: 'DEV', iterationNo: 2 }))!
    expect(compactDotsAriaLabel(dots)).toContain('sent back')
  })

  it('reads a finished journey as finished rather than as stage 8 of 8', () => {
    const dots = buildCompactDots(STANDARD, ticket({ currentStageCode: 'CLOSED', status: 'CLOSED' }))!
    expect(compactDotsAriaLabel(dots)).toBe('Journey: all 8 stages completed')
  })

  it('degrades an unreadable state to the neutral word rather than undefined', () => {
    expect(dotWord(SegmentState.SKIPPED)).toBe('not started')
  })
})
