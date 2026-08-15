import { describe, expect, it } from 'vitest'

import type { ProjectSettings } from '@/api/generated/model/projectSettings'

import {
  allowListSummary,
  draftFor,
  isDirty,
  isUnrestricted,
  retiredAllowed,
  TICKET_FIELDS,
  toWriteRequest,
  toggle,
} from './projectSettings'

/**
 * B-019 · the Settings tab's decisions, without rendering anything.
 *
 * Most of this file is one rule approached from several sides: **an empty
 * allow-list means unrestricted, not empty.** The function that gets it wrong is
 * `draftFor` — the server answers `isAllowed: true` for every row on an
 * unrestricted project, and seeding the draft from those flags would turn the
 * first save into an allow-list naming every task type that happened to exist
 * that day. Nothing would look wrong until an Admin added a twelfth and found it
 * silently barred here.
 */

const settings = (over: Partial<ProjectSettings> = {}): ProjectSettings => ({
  projectId: 1,
  autoAssignRule: 'MANUAL',
  mandatoryFields: [],
  restrictsTaskTypes: false,
  taskTypes: [
    { taskTypeId: 1, name: 'Change Request', isAllowed: true, isActive: true },
    { taskTypeId: 2, name: 'Production Bug', isAllowed: true, isActive: true },
    { taskTypeId: 3, name: 'Client Request', isAllowed: true, isActive: true },
  ],
  ...over,
})

const restricted = (): ProjectSettings =>
  settings({
    restrictsTaskTypes: true,
    taskTypes: [
      { taskTypeId: 1, name: 'Change Request', isAllowed: false, isActive: true },
      { taskTypeId: 2, name: 'Production Bug', isAllowed: true, isActive: true },
      { taskTypeId: 3, name: 'Client Request', isAllowed: false, isActive: true },
    ],
  })

describe('the settings draft', () => {
  it('starts an unrestricted project with nothing ticked, though every row says isAllowed', () => {
    // The whole hazard. `isAllowed: true` on every row is what unrestricted
    // *means*; copying it into the draft would materialise an allow-list on the
    // first save and silently bar the twelfth task type an Admin adds later.
    const draft = draftFor(settings())

    expect(draft.allowedTaskTypeIds.size).toBe(0)
    expect(isUnrestricted(draft)).toBe(true)
  })

  it('starts a restricted project with exactly its allowed types ticked', () => {
    const draft = draftFor(restricted())

    expect([...draft.allowedTaskTypeIds]).toEqual([2])
    expect(isUnrestricted(draft)).toBe(false)
  })

  it('carries the rule and the mandatory fields through unchanged', () => {
    const draft = draftFor(settings({
      autoAssignRule: 'LEAST_LOADED',
      mandatoryFields: ['MODULE', 'ASSIGNEE'],
    }))

    expect(draft.autoAssignRule).toBe('LEAST_LOADED')
    expect([...draft.mandatoryFields]).toEqual(['MODULE', 'ASSIGNEE'])
  })
})

describe('the request', () => {
  it('sends an empty allow-list for an unrestricted project, and that is the point', () => {
    // Not a degenerate request — it is the one that keeps the project
    // unrestricted, and the only one that can remove a restriction.
    const s = settings()
    const body = toWriteRequest(s, draftFor(s))

    expect(body.allowedTaskTypeIds).toEqual([])
  })

  it('sends the ticked ids in the master’s order, not in click order', () => {
    // A request whose array order depends on click order makes two saves of one
    // state look like two different states in a log.
    const s = settings()
    const draft = draftFor(s)
    draft.allowedTaskTypeIds = new Set([3, 1])

    expect(toWriteRequest(s, draft).allowedTaskTypeIds).toEqual([1, 3])
  })

  it('sends mandatory fields in the vocabulary’s order', () => {
    const s = settings()
    const draft = draftFor(s)
    draft.mandatoryFields = new Set(['ASSIGNEE', 'DESCRIPTION'])

    expect(toWriteRequest(s, draft).mandatoryFields).toEqual(['DESCRIPTION', 'ASSIGNEE'])
  })

  it('always sends all three fields, because the operation is a replace', () => {
    const s = settings()
    const body = toWriteRequest(s, draftFor(s))

    expect(Object.keys(body).sort()).toEqual(
      ['allowedTaskTypeIds', 'autoAssignRule', 'mandatoryFields'],
    )
  })

  it('keeps a retired task type this project allows', () => {
    // The PUT is assembled from the rows the screen was given, so a retired
    // type that is allowed must survive a save it was not part of.
    const s = settings({
      restrictsTaskTypes: true,
      taskTypes: [
        { taskTypeId: 2, name: 'Production Bug', isAllowed: true, isActive: true },
        { taskTypeId: 9, name: 'Browser Issue', isAllowed: true, isActive: false },
      ],
    })

    expect(toWriteRequest(s, draftFor(s)).allowedTaskTypeIds).toEqual([2, 9])
  })
})

describe('dirty state', () => {
  it('an untouched unrestricted project is not dirty', () => {
    // The mirror of the draft rule: if `draftFor` had copied the isAllowed
    // flags, an untouched screen would report three pending changes.
    const s = settings()
    expect(isDirty(s, draftFor(s))).toBe(false)
  })

  it('an untouched restricted project is not dirty', () => {
    const s = restricted()
    expect(isDirty(s, draftFor(s))).toBe(false)
  })

  it('ticking a task type makes it dirty', () => {
    const s = settings()
    const draft = draftFor(s)

    expect(isDirty(s, { ...draft, allowedTaskTypeIds: toggle(draft.allowedTaskTypeIds, 2, true) }))
      .toBe(true)
  })

  it('unticking the last task type makes it dirty — that save removes the restriction', () => {
    const s = restricted()
    const draft = draftFor(s)

    expect(isDirty(s, { ...draft, allowedTaskTypeIds: toggle(draft.allowedTaskTypeIds, 2, false) }))
      .toBe(true)
  })

  it('changing the rule or a field makes it dirty', () => {
    const s = settings()
    const draft = draftFor(s)

    expect(isDirty(s, { ...draft, autoAssignRule: 'ROUND_ROBIN' })).toBe(true)
    expect(isDirty(s, { ...draft, mandatoryFields: toggle(draft.mandatoryFields, 'MODULE', true) }))
      .toBe(true)
  })

  it('ticking and unticking the same box is not a change', () => {
    const s = restricted()
    const draft = draftFor(s)
    const there = toggle(draft.allowedTaskTypeIds, 1, true)

    expect(isDirty(s, { ...draft, allowedTaskTypeIds: toggle(there, 1, false) })).toBe(false)
  })
})

describe('what the screen says', () => {
  it('describes an empty list as no restriction, never as a count of zero', () => {
    // "0 of 11 selected" is accurate and reads as a restriction that permits
    // nothing, which is the opposite of what it means.
    const summary = allowListSummary(draftFor(settings()), settings().taskTypes)

    expect(summary).toMatch(/no restriction/i)
    expect(summary).not.toMatch(/^0 of/)
  })

  it('counts against the active types once a restriction exists', () => {
    const s = restricted()
    expect(allowListSummary(draftFor(s), s.taskTypes)).toBe(
      '1 of 3 task types may be raised on this project.',
    )
  })
})

describe('the vocabulary', () => {
  it('offers no checkbox for a field every ticket already requires', () => {
    // A control that cannot change the outcome is worse than a missing one:
    // somebody ticks it and believes something happened.
    const codes = TICKET_FIELDS.map((f) => f.value as string)

    expect(codes).not.toContain('TITLE')
    expect(codes).not.toContain('PROJECT')
    expect(codes).not.toContain('TASK_TYPE')
    expect(codes).not.toContain('LEVEL')
  })

  it('separates retired task types so the screen can label them', () => {
    const s = settings({
      taskTypes: [
        { taskTypeId: 2, name: 'Production Bug', isAllowed: true, isActive: true },
        { taskTypeId: 9, name: 'Browser Issue', isAllowed: true, isActive: false },
      ],
    })

    expect(retiredAllowed(s.taskTypes).map((t) => t.taskTypeId)).toEqual([9])
  })
})

describe('toggle', () => {
  it('does not mutate the set it is given — React is holding it', () => {
    const original = new Set([1, 2])
    const next = toggle(original, 3, true)

    expect([...original]).toEqual([1, 2])
    expect([...next]).toEqual([1, 2, 3])
  })
})
