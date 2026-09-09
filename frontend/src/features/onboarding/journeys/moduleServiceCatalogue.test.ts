import { describe, expect, it } from 'vitest'

import { cycleFreeCandidates, moveTemplate, type CatalogueEntry } from './moduleServiceCatalogue'

describe('cycleFreeCandidates', () => {
  const catalogue: CatalogueEntry[] = [
    { activeTemplateId: 1, dependsOnTemplateId: null },
    { activeTemplateId: 2, dependsOnTemplateId: 1 },
    { activeTemplateId: 3, dependsOnTemplateId: 2 },
    { activeTemplateId: 4, dependsOnTemplateId: null },
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
      { activeTemplateId: 10, dependsOnTemplateId: 11 },
      { activeTemplateId: 11, dependsOnTemplateId: 10 },
    ]
    expect(() => cycleFreeCandidates(10, brokenLoop)).not.toThrow()
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
