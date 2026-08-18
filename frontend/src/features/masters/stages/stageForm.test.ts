import { describe, expect, it } from 'vitest'

import type { Stage } from '@/api/generated/model/stage'
import {
  EMPTY_STAGE_FORM,
  forwardReturnPaths,
  formToCreate,
  formToPatch,
  moveStage,
  orderChanged,
  retireBlockers,
  returnTargetOptions,
  stageFormErrors,
  stageToForm,
} from './stageForm'

/**
 * B-040 · the validation subset, the two mappers, and the reorder model.
 *
 * `StagesTab.test.tsx` exercises these through the screen against the mock
 * server; this pins the rules directly, where each one can be put in the single
 * state that exercises it.
 */

function stage(overrides: Partial<Stage> = {}): Stage {
  return {
    id: 3,
    templateId: 1,
    stageCode: 'DEV',
    displayName: 'Development',
    ownerRole: 'DEVELOPER',
    slaHours: null,
    isOptional: false,
    canReturnTo: ['TRIAGE'],
    icon: 'code-2',
    seq: 30,
    position: 3,
    transitionCount: 0,
    openTicketCount: 0,
    isCodeEditable: true,
    isDeprecated: false,
    deprecatedAt: null,
    isDeletable: true,
    ...overrides,
  }
}

const ribbon: Stage[] = [
  stage({ id: 1, stageCode: 'INTAKE', displayName: 'Intake', seq: 10, position: 1, canReturnTo: [] }),
  stage({ id: 2, stageCode: 'TRIAGE', displayName: 'Triage', seq: 20, position: 2, canReturnTo: [] }),
  stage({ id: 3, stageCode: 'DEV', displayName: 'Development', seq: 30, position: 3, canReturnTo: ['TRIAGE'] }),
  stage({ id: 4, stageCode: 'QA', displayName: 'QA', seq: 40, position: 4, canReturnTo: ['DEV'] }),
]

describe('stageFormErrors', () => {
  const good = {
    ...EMPTY_STAGE_FORM,
    stageCode: 'DEPLOY',
    displayName: 'Deployment',
    ownerRole: 'DEPLOYMENT',
  }

  it('accepts a well-formed stage', () => {
    expect(stageFormErrors(good)).toEqual({})
  })

  it('requires a code, a name and an owner role', () => {
    const errors = stageFormErrors(EMPTY_STAGE_FORM)
    expect(Object.keys(errors).sort()).toEqual(['displayName', 'ownerRole', 'stageCode'])
  })

  it('refuses a code with a space — it is stored on every transition row', () => {
    expect(stageFormErrors({ ...good, stageCode: 'GO LIVE' }).stageCode).toBeDefined()
  })

  it('refuses a code that does not start with a letter', () => {
    expect(stageFormErrors({ ...good, stageCode: '2ND_PASS' }).stageCode).toBeDefined()
  })

  it('skips the code rules entirely when the server says it is frozen', () => {
    // Not cosmetic: the field is disabled and empty-ish on a frozen stage, and a
    // client-side "required" on it would block every unrelated edit.
    const frozen = stageFormErrors({ ...good, stageCode: '' }, { codeEditable: false })
    expect(frozen.stageCode).toBeUndefined()
  })

  it('accepts an empty SLA — that is Development, resolved from the SLA matrix', () => {
    expect(stageFormErrors({ ...good, slaHours: '' }).slaHours).toBeUndefined()
  })

  it('refuses a zero SLA, because a stage that breaches on entry is not "no SLA"', () => {
    expect(stageFormErrors({ ...good, slaHours: '0' }).slaHours).toContain('Leave it empty')
  })

  it('refuses an SLA beyond DECIMAL(6,2)', () => {
    expect(stageFormErrors({ ...good, slaHours: '100000' }).slaHours).toBeDefined()
  })

  it('refuses a non-numeric SLA', () => {
    expect(stageFormErrors({ ...good, slaHours: 'four' }).slaHours).toBeDefined()
  })
})

describe('stageToForm', () => {
  it('renders a null SLA as empty rather than as zero', () => {
    expect(stageToForm(stage({ slaHours: null })).slaHours).toBe('')
  })

  it('round-trips a set SLA', () => {
    expect(stageToForm(stage({ slaHours: 8 })).slaHours).toBe('8')
  })
})

describe('formToCreate', () => {
  it('upper-cases the code and sends no seq — a create appends', () => {
    const body = formToCreate({
      ...EMPTY_STAGE_FORM,
      stageCode: 'deploy',
      displayName: '  Deployment  ',
      ownerRole: 'DEPLOYMENT',
    })

    expect(body.stageCode).toBe('DEPLOY')
    expect(body.displayName).toBe('Deployment')
    expect(body).not.toHaveProperty('seq')
  })

  it('sends null for an empty SLA and an empty icon', () => {
    const body = formToCreate({
      ...EMPTY_STAGE_FORM,
      stageCode: 'DEPLOY',
      displayName: 'Deployment',
      ownerRole: 'DEPLOYMENT',
    })

    expect(body.slaHours).toBeNull()
    expect(body.icon).toBeNull()
  })
})

describe('formToPatch', () => {
  const original = stage({ slaHours: 8, canReturnTo: ['TRIAGE'], icon: 'code-2' })

  it('sends nothing when nothing changed', () => {
    expect(formToPatch(stageToForm(original), original)).toEqual({})
  })

  it('sends only the field that changed', () => {
    const form = { ...stageToForm(original), displayName: 'Dev' }
    expect(formToPatch(form, original)).toEqual({ displayName: 'Dev' })
  })

  /**
   * The one that would have shipped wrong. An unchanged code round-tripped by the
   * form would turn every unrelated edit into a 409 the moment the screen's idea
   * of `isCodeEditable` lagged the server's by one ticket.
   */
  it('omits stageCode when it is unchanged, even though the form always holds it', () => {
    expect(formToPatch(stageToForm(original), original)).not.toHaveProperty('stageCode')
  })

  it('sends stageCode when it really changed, upper-cased', () => {
    const form = { ...stageToForm(original), stageCode: 'build' }
    expect(formToPatch(form, original).stageCode).toBe('BUILD')
  })

  /**
   * `undefined` leaves the targets alone and `[]` clears them, so a mapper that
   * always sent the array would silently wipe a loop-back somebody authored.
   */
  it('omits canReturnTo when the same targets are present in another order', () => {
    const twoTargets = stage({ canReturnTo: ['TRIAGE', 'INTAKE'] })
    const form = { ...stageToForm(twoTargets), canReturnTo: ['INTAKE', 'TRIAGE'] }

    expect(formToPatch(form, twoTargets)).not.toHaveProperty('canReturnTo')
  })

  it('sends an empty array when the last target is cleared', () => {
    const form = { ...stageToForm(original), canReturnTo: [] }
    expect(formToPatch(form, original).canReturnTo).toEqual([])
  })

  it('sends null when the SLA is cleared, not zero', () => {
    const form = { ...stageToForm(original), slaHours: '' }
    expect(formToPatch(form, original).slaHours).toBeNull()
  })
})

describe('returnTargetOptions', () => {
  it('offers only the stages before this one — a return target is backward', () => {
    expect(returnTargetOptions(ribbon, 3).map((s) => s.stageCode))
      .toEqual(['INTAKE', 'TRIAGE'])
  })

  it('offers everything to a stage being created, which is appended last', () => {
    expect(returnTargetOptions(ribbon, null)).toHaveLength(4)
  })

  it('offers nothing to the first stage', () => {
    expect(returnTargetOptions(ribbon, 1)).toEqual([])
  })
})

describe('moveStage', () => {
  it('moves a row up', () => {
    expect(moveStage([1, 2, 3, 4], 2, 1)).toEqual([1, 3, 2, 4])
  })

  it('moves a row down', () => {
    expect(moveStage([1, 2, 3, 4], 0, 3)).toEqual([2, 3, 4, 1])
  })

  it('returns the same array on a no-op, so the ends of the list are not an error', () => {
    const items = [1, 2, 3]
    expect(moveStage(items, 0, -1)).toBe(items)
    expect(moveStage(items, 2, 3)).toBe(items)
    expect(moveStage(items, 1, 1)).toBe(items)
  })
})

describe('forwardReturnPaths', () => {
  it('finds nothing in the seeded order', () => {
    expect(forwardReturnPaths(ribbon)).toEqual([])
  })

  it('names the pair a drag would invert', () => {
    // DEV past QA — QA → DEV now points forwards.
    const dragged = moveStage(ribbon, 2, 3)
    expect(forwardReturnPaths(dragged)).toEqual(['QA → DEV'])
  })

  it('names every offending pair, not just the first', () => {
    const reversed = [...ribbon].reverse()
    expect(forwardReturnPaths(reversed)).toEqual(['QA → DEV', 'DEV → TRIAGE'])
  })

  it('ignores a target that is not on this ribbon', () => {
    const orphan = [stage({ id: 9, stageCode: 'SIGNOFF', canReturnTo: ['NOWHERE'], position: 1 })]
    expect(forwardReturnPaths(orphan)).toEqual([])
  })
})

describe('orderChanged', () => {
  it('is false for the order the server gave', () => {
    expect(orderChanged(ribbon, ribbon)).toBe(false)
  })

  it('is true once a row has moved', () => {
    expect(orderChanged(moveStage(ribbon, 0, 1), ribbon)).toBe(true)
  })

  /**
   * By id and position, not content — an edit is saved by its own request, and
   * treating it as an unsaved reorder would offer a Save that sends the order
   * back unchanged.
   */
  it('is false when a row was edited but nothing moved', () => {
    const edited = ribbon.map((s, i) => (i === 2 ? { ...s, displayName: 'Dev' } : s))
    expect(orderChanged(edited, ribbon)).toBe(false)
  })
})

/**
 * B-042 · the screen's copy of the two rules a retire is refused by, and the
 * picker's copy of the rule a return target is refused by.
 *
 * The server enforces all three. These exist so the confirm dialog can name the
 * stage in the way before the click rather than render a 409 after it — the same
 * bargain `forwardReturnPaths` makes, and the reason it is worth writing twice.
 */
describe('returnTargetOptions and deprecated stages', () => {
  it('drops a deprecated stage from the picker — the server would refuse the target', () => {
    const stages = [
      stage({ id: 1, stageCode: 'INTAKE', position: 1, canReturnTo: [] }),
      stage({ id: 2, stageCode: 'TRIAGE', position: 2, canReturnTo: [], isDeprecated: true }),
    ]

    expect(returnTargetOptions(stages, 3).map((s) => s.stageCode)).toEqual(['INTAKE'])
  })

  it('keeps a deprecated stage this one already returns to, so an unrelated edit cannot clear it', () => {
    const stages = [
      stage({ id: 1, stageCode: 'INTAKE', position: 1, canReturnTo: [] }),
      stage({ id: 2, stageCode: 'TRIAGE', position: 2, canReturnTo: [], isDeprecated: true }),
    ]

    expect(returnTargetOptions(stages, 3, ['TRIAGE']).map((s) => s.stageCode))
      .toEqual(['INTAKE', 'TRIAGE'])
  })
})

describe('retireBlockers', () => {
  const live = (code: string, id: number, canReturnTo: string[] = []) =>
    stage({ id, stageCode: code, displayName: code, position: id, canReturnTo })

  it('names the arrow that would point at a retired stage', () => {
    const dev = live('DEV', 3)
    const blocked = retireBlockers(dev, [live('INTAKE', 1), dev, live('QA', 4, ['DEV'])])

    expect(blocked).toEqual({ reason: 'return-target', arrows: ['QA \u2192 DEV'] })
  })

  it('ignores an arrow from a stage that is itself deprecated — not a move anything can make', () => {
    const dev = live('DEV', 3)
    const qa = stage({ id: 4, stageCode: 'QA', position: 4, canReturnTo: ['DEV'], isDeprecated: true })

    expect(retireBlockers(dev, [live('INTAKE', 1), dev, qa])).toBeNull()
  })

  it('refuses the last live stage — a workflow with nothing live routes no ticket', () => {
    const intake = live('INTAKE', 1)
    const retired = stage({ id: 2, stageCode: 'TRIAGE', position: 2, canReturnTo: [], isDeprecated: true })

    expect(retireBlockers(intake, [intake, retired])).toEqual({ reason: 'last-live', arrows: [] })
  })

  it('allows a retire with open tickets standing in the stage — that is the case §7.4 exists for', () => {
    const dev = stage({ id: 3, stageCode: 'DEV', position: 3, canReturnTo: [], openTicketCount: 9 })

    expect(retireBlockers(dev, [live('INTAKE', 1), dev, live('QA', 4)])).toBeNull()
  })
})
