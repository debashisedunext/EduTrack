import { describe, expect, it } from 'vitest'

import {
  checklistCount,
  cycleFreeCandidates,
  defaultImplementor,
  moveTemplate,
  type CatalogueEntry,
  type StepOwnership,
} from './moduleServiceCatalogue'

describe('cycleFreeCandidates', () => {
  const catalogue: CatalogueEntry[] = [
    { activeTemplateId: 1, dependsOnTemplateIds: [] },
    { activeTemplateId: 2, dependsOnTemplateIds: [1] },
    { activeTemplateId: 3, dependsOnTemplateIds: [2] },
    { activeTemplateId: 4, dependsOnTemplateIds: [] },
  ]

  it('excludes the template itself', () => {
    const candidates = cycleFreeCandidates(1, catalogue)
    expect(candidates.map((c) => c.activeTemplateId)).not.toContain(1)
  })

  it('excludes a template that already depends directly on this one', () => {
    // 2 depends on 1 — offering 2 to 1 would close a two-node cycle.
    const candidates = cycleFreeCandidates(1, catalogue)
    expect(candidates.map((c) => c.activeTemplateId)).not.toContain(2)
  })

  it('excludes a template that depends transitively, not just directly', () => {
    // 3 depends on 2 depends on 1 — offering 3 to 1 would close it two hops out.
    const candidates = cycleFreeCandidates(1, catalogue)
    expect(candidates.map((c) => c.activeTemplateId)).not.toContain(3)
  })

  it('includes a template with no relation to this one', () => {
    const candidates = cycleFreeCandidates(1, catalogue)
    expect(candidates.map((c) => c.activeTemplateId)).toContain(4)
  })

  it('a template with nothing depending on it may be depended on by anyone else', () => {
    // 4 has no dependents at all — 1, 2 and 3 may all point at it.
    expect(cycleFreeCandidates(4, catalogue).map((c) => c.activeTemplateId)).toEqual([1, 2, 3])
  })

  it('is stable against a malformed self-referencing chain rather than looping forever', () => {
    const brokenLoop: CatalogueEntry[] = [
      { activeTemplateId: 10, dependsOnTemplateIds: [11] },
      { activeTemplateId: 11, dependsOnTemplateIds: [10] },
    ]
    expect(() => cycleFreeCandidates(10, brokenLoop)).not.toThrow()
  })

  it('excludes a template reachable down the second branch of a fork', () => {
    /*
      The case a single-cursor walk cannot see, and the reason this is a
      breadth-first search:

          20 ─▶ 21 (a dead end)
             └▶ 22 ─▶ 23

      Offering 20 to 23 would close 23 → 20 → 22 → 23. A walk that followed
      only the first edge out of each node reaches 21, runs out, and offers
      the option — which the server then refuses with a 409 the reader has no
      way to have predicted.
    */
    const fork: CatalogueEntry[] = [
      { activeTemplateId: 20, dependsOnTemplateIds: [21, 22] },
      { activeTemplateId: 21, dependsOnTemplateIds: [] },
      { activeTemplateId: 22, dependsOnTemplateIds: [23] },
      { activeTemplateId: 23, dependsOnTemplateIds: [] },
    ]
    expect(cycleFreeCandidates(23, fork).map((c) => c.activeTemplateId)).not.toContain(20)
    // 21 is reachable from 20 but reaches nothing itself, so it stays on offer.
    expect(cycleFreeCandidates(23, fork).map((c) => c.activeTemplateId)).toContain(21)
  })

  it('a template already selected is still offered, so it can be seen ticked', () => {
    // The multi-select draws its current selection from the same candidate
    // list it draws the unticked options from. Excluding what is already
    // chosen would make a set look empty the moment it was saved.
    expect(cycleFreeCandidates(2, catalogue).map((c) => c.activeTemplateId)).toContain(1)
  })
})

describe('moveTemplate', () => {
  it('moves an id from one position to another', () => {
    expect(moveTemplate([1, 2, 3], 0, 2)).toEqual([2, 3, 1])
    expect(moveTemplate([1, 2, 3], 2, 0)).toEqual([3, 1, 2])
  })

  it('is a no-op at the boundaries', () => {
    const ids = [1, 2, 3]
    expect(moveTemplate(ids, 0, -1)).toBe(ids)
    expect(moveTemplate(ids, 2, 3)).toBe(ids)
    expect(moveTemplate(ids, 1, 1)).toBe(ids)
  })
})

describe('checklistCount', () => {
  it('totals the items across every step', () => {
    const steps: StepOwnership[] = [
      { sequence: 1, items: [{}, {}] },
      { sequence: 2, items: [{}] },
      { sequence: 3 },
    ]
    expect(checklistCount(steps)).toBe(3)
  })

  it('is zero for a service with no steps, which is a fact rather than a gap', () => {
    expect(checklistCount([])).toBe(0)
  })
})

describe('defaultImplementor', () => {
  const users = [
    { id: 6, displayName: 'Kavya Sharma' },
    { id: 8, displayName: 'Priya Nair' },
  ]

  it('names the owner of the first step, whatever order the steps arrive in', () => {
    // Sequence, not array position: the detail read is free to hand these
    // back in any order, and "first" means first in the journey.
    const steps: StepOwnership[] = [
      { sequence: 3, ownerUserId: 8 },
      { sequence: 1, ownerUserId: 6 },
    ]
    const implementor = defaultImplementor(steps, users)
    expect(implementor.label).toBe('Kavya Sharma')
    expect(implementor.named).toBe(true)
  })

  it('counts the other distinct owners, so a shared service does not read as one person’s', () => {
    const steps: StepOwnership[] = [
      { sequence: 1, ownerUserId: 6 },
      { sequence: 2, ownerUserId: 8 },
      // The same second owner twice — distinct people, not steps.
      { sequence: 3, ownerUserId: 8 },
      { sequence: 4, ownerRole: 'PM' },
    ]
    expect(defaultImplementor(steps, users).extra).toBe('+1 other')
  })

  it('says nothing extra when every step belongs to the same person', () => {
    const steps: StepOwnership[] = [
      { sequence: 1, ownerUserId: 6 },
      { sequence: 2, ownerUserId: 6 },
    ]
    expect(defaultImplementor(steps, users).extra).toBe('')
  })

  it('falls back to the role when the first step pins no person', () => {
    const implementor = defaultImplementor([{ sequence: 1, ownerRole: 'PM' }], users)
    expect(implementor.label).toBe('Role · PM')
    // A role is not a person — the column must not style it as one.
    expect(implementor.named).toBe(false)
  })

  it('says Unassigned when the first step names neither', () => {
    expect(defaultImplementor([{ sequence: 1 }], users).label).toBe('Unassigned')
  })

  it('names a user the list does not carry by id rather than dropping them', () => {
    // `useListUsers` is capped and filtered to active users, so a step owned
    // by somebody deactivated resolves to nothing — and a blank cell would
    // read as "nobody owns this", which is the opposite of true.
    expect(defaultImplementor([{ sequence: 1, ownerUserId: 99 }], users).label).toBe('user #99')
  })

  it('has its own answer for a service with no steps at all', () => {
    const implementor = defaultImplementor([], users)
    expect(implementor.label).toBe('No steps yet')
    expect(implementor.extra).toBe('')
  })
})
