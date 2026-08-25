import { describe, expect, it } from 'vitest'

import type { Ribbon } from '@/api/generated/model/ribbon'
import type { WorkflowTemplate } from '@/api/generated/model/workflowTemplate'

import {
  emptyHandoffForm,
  handoffSchema,
  handoffStageOptions,
  nextStageCode,
  stageOwnerRole,
  toHandoffRequest,
  type HandoffFormValues,
} from './handoffForm'

function ribbonWithCurrent(stageCode: string, extraSegments: Ribbon['segments'] = []): Ribbon {
  return {
    cycleNo: 1,
    iterationNo: 1,
    isSealed: false,
    currentStageCode: stageCode,
    canAdvance: true,
    segments: [
      { stageCode: 'TRIAGE', sequence: 1, state: 'COMPLETED', ownerRole: 'SUPPORT' },
      { stageCode: 'DEV', sequence: 2, state: 'COMPLETED', ownerRole: 'DEVELOPER' },
      { stageCode, sequence: 3, state: 'CURRENT', ownerRole: 'QA' },
      { stageCode: 'DEPLOY', sequence: 4, state: 'PENDING', ownerRole: 'DEPLOYMENT' },
      ...extraSegments,
    ],
  }
}

describe('nextStageCode — C-044', () => {
  it('picks the segment immediately after the CURRENT one, by sequence', () => {
    expect(nextStageCode(ribbonWithCurrent('QA'))).toBe('DEPLOY')
  })

  it('is undefined with no ribbon at all — the real backend today, C-051/C-053 not built yet', () => {
    expect(nextStageCode(undefined)).toBeUndefined()
  })

  it('is undefined with no CURRENT segment', () => {
    const ribbon: Ribbon = {
      segments: [{ stageCode: 'TRIAGE', sequence: 1, state: 'COMPLETED' }],
    }
    expect(nextStageCode(ribbon)).toBeUndefined()
  })

  it('is undefined when CURRENT is already the last segment', () => {
    const ribbon: Ribbon = {
      segments: [
        { stageCode: 'TRIAGE', sequence: 1, state: 'COMPLETED' },
        { stageCode: 'SIGNOFF', sequence: 2, state: 'CURRENT' },
      ],
    }
    expect(nextStageCode(ribbon)).toBeUndefined()
  })
})

describe('stageOwnerRole — C-044', () => {
  const ribbon = ribbonWithCurrent('QA')

  it('reads the owner role off the matching ribbon segment', () => {
    expect(stageOwnerRole('DEPLOY', ribbon, undefined)).toBe('DEPLOYMENT')
  })

  it('falls back to deduplicating across workflow templates when the ribbon has no such stage', () => {
    const templates: WorkflowTemplate[] = [
      {
        id: 1,
        name: 'Standard',
        isDefault: true,
        isActive: true,
        stageCount: 1,
        stages: [{ stageCode: 'SIGNOFF', ownerRole: 'PM', sequence: 5 }],
      },
    ]
    expect(stageOwnerRole('SIGNOFF', undefined, templates)).toBe('PM')
  })

  it('is undefined when neither source names the stage — never guessed', () => {
    expect(stageOwnerRole('RELEASE', ribbon, [])).toBeUndefined()
  })

  it('is undefined for a blank stage code', () => {
    expect(stageOwnerRole('  ', ribbon, [])).toBeUndefined()
  })
})

/*
 * BUG-003 · the Next stage field was free text, so a mistyped code reached
 * `ticket_stage_transitions` — append-only, so it cannot be edited away.
 */
describe('handoffStageOptions — BUG-003', () => {
  it('lists the ribbon’s own segments, in ribbon order', () => {
    const options = handoffStageOptions(ribbonWithCurrent('QA'), undefined)

    expect(options.map((o) => o.stageCode)).toEqual(['TRIAGE', 'DEV', 'QA', 'DEPLOY'])
  })

  it('sorts by sequence rather than the order the segments arrived in', () => {
    const scrambled: Ribbon = {
      cycleNo: 1,
      iterationNo: 1,
      isSealed: false,
      currentStageCode: 'DEV',
      canAdvance: true,
      segments: [
        { stageCode: 'QA', sequence: 3, state: 'PENDING' },
        { stageCode: 'TRIAGE', sequence: 1, state: 'COMPLETED' },
        { stageCode: 'DEV', sequence: 2, state: 'CURRENT' },
      ],
    }

    expect(handoffStageOptions(scrambled, undefined).map((o) => o.stageCode)).toEqual([
      'TRIAGE',
      'DEV',
      'QA',
    ])
  })

  it('carries each segment’s owner role and label through', () => {
    const [triage] = handoffStageOptions(ribbonWithCurrent('QA'), undefined)

    expect(triage.ownerRole).toBe('SUPPORT')
    // No displayName on the segment, so the code stands in for its own label
    // rather than the row rendering blank.
    expect(triage.displayName).toBe('TRIAGE')
  })

  const templates: WorkflowTemplate[] = [
    {
      id: 1,
      name: 'Standard',
      isDefault: true,
      isActive: true,
      stageCount: 3,
      stages: [
        { stageCode: 'DEV', displayName: 'Development', ownerRole: 'DEVELOPER', sequence: 20 },
        { stageCode: 'TRIAGE', displayName: 'Triage', ownerRole: 'SUPPORT', sequence: 10 },
        { stageCode: 'RETIRED', displayName: 'Retired', sequence: 30, isDeprecated: true },
      ],
    },
    {
      id: 2,
      name: 'Support only',
      isDefault: false,
      isActive: true,
      stageCount: 2,
      stages: [
        { stageCode: 'TRIAGE', displayName: 'Triage', ownerRole: 'SUPPORT', sequence: 10 },
        { stageCode: 'CLOSED', displayName: 'Closed', ownerRole: 'PM', sequence: 90 },
      ],
    },
  ]

  it('falls back to the templates when there is no ribbon, deduplicated across them', () => {
    const options = handoffStageOptions(undefined, templates)

    expect(options.map((o) => o.stageCode)).toEqual(['TRIAGE', 'DEV', 'CLOSED'])
  })

  it('keeps CLOSED, which the ticket list’s own stage filter drops', () => {
    // The list filters for open work; a sign-off hands a ticket *to* CLOSED,
    // so dropping it here would make the last hop unreachable.
    expect(handoffStageOptions(undefined, templates).map((o) => o.stageCode)).toContain('CLOSED')
  })

  it('never offers a deprecated stage as a handoff target', () => {
    expect(handoffStageOptions(undefined, templates).map((o) => o.stageCode)).not.toContain(
      'RETIRED',
    )
  })

  it('prefers the ribbon over the templates when both are available', () => {
    const options = handoffStageOptions(ribbonWithCurrent('QA'), templates)

    // This ticket's own path, not every code defined anywhere.
    expect(options.map((o) => o.stageCode)).toEqual(['TRIAGE', 'DEV', 'QA', 'DEPLOY'])
  })

  it('is empty when neither source has anything, rather than inventing a stage', () => {
    expect(handoffStageOptions(undefined, undefined)).toEqual([])
    expect(handoffStageOptions(undefined, [])).toEqual([])
  })
})

describe('emptyHandoffForm', () => {
  it('pre-fills toStageCode from the ribbon and leaves everything else blank', () => {
    expect(emptyHandoffForm(ribbonWithCurrent('QA'))).toEqual({
      toStageCode: 'DEPLOY',
      toUserId: null,
      note: '',
      effortHours: '',
    })
  })

  it('leaves toStageCode blank with no ribbon', () => {
    expect(emptyHandoffForm(undefined).toStageCode).toBe('')
  })
})

describe('handoffSchema — C-044, S-29', () => {
  const valid: HandoffFormValues = { toStageCode: 'QA', toUserId: 42, note: '', effortHours: '2.5' }

  it('accepts the smallest valid handoff — stage, assignee and confirmed hours', () => {
    expect(handoffSchema.safeParse(valid).success).toBe(true)
  })

  it('accepts zero hours — a genuine claim of no time spent, not "not confirmed"', () => {
    expect(handoffSchema.safeParse({ ...valid, effortHours: '0' }).success).toBe(true)
  })

  it('rejects a blank toStageCode', () => {
    expect(handoffSchema.safeParse({ ...valid, toStageCode: '' }).success).toBe(false)
  })

  it('rejects no assignee — toUserId is required on the wire and here', () => {
    expect(handoffSchema.safeParse({ ...valid, toUserId: null }).success).toBe(false)
  })

  it('rejects a blank effortHours — mandatory unless the project allows warn-only (G-1)', () => {
    expect(handoffSchema.safeParse({ ...valid, effortHours: '' }).success).toBe(false)
  })

  it('rejects effort hours that are not a plain decimal', () => {
    expect(handoffSchema.safeParse({ ...valid, effortHours: '4h' }).success).toBe(false)
  })

  it('rejects negative effort hours', () => {
    expect(handoffSchema.safeParse({ ...valid, effortHours: '-1' }).success).toBe(false)
  })

  it('rejects a note over 4000 characters, matching HandoffRequest.note', () => {
    expect(handoffSchema.safeParse({ ...valid, note: 'x'.repeat(4001) }).success).toBe(false)
  })
})

describe('toHandoffRequest', () => {
  const values: HandoffFormValues = { toStageCode: ' QA ', toUserId: 42, note: '', effortHours: '2.5' }

  it('trims toStageCode and sends effortHours as a number', () => {
    expect(toHandoffRequest(values, [])).toEqual({
      toStageCode: 'QA',
      toUserId: 42,
      effortHours: 2.5,
    })
  })

  it('sends a genuine zero rather than omitting it', () => {
    expect(toHandoffRequest({ ...values, effortHours: '0' }, [])).toMatchObject({ effortHours: 0 })
  })

  it('omits a blank note rather than sending an empty string', () => {
    expect(toHandoffRequest(values, [])).not.toHaveProperty('note')
  })

  it('trims and carries a real note', () => {
    expect(toHandoffRequest({ ...values, note: '  see attached  ' }, [])).toMatchObject({ note: 'see attached' })
  })

  it('omits attachmentIds when nothing was attached', () => {
    expect(toHandoffRequest(values, [])).not.toHaveProperty('attachmentIds')
  })

  it('carries attachmentIds when files were picked', () => {
    expect(toHandoffRequest(values, [7, 9])).toMatchObject({ attachmentIds: [7, 9] })
  })
})
