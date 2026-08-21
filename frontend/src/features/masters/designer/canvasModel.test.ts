import { describe, expect, it } from 'vitest'

import type { Stage } from '@/api/generated/model/stage'
import type { WorkflowTemplate } from '@/api/generated/model/workflowTemplate'

import {
  arcAreaHeight,
  arcPath,
  canvasWidth,
  dropIndex,
  insertAt,
  moveAnnouncement,
  resequence,
  returnArcs,
  returnPathRefusal,
  stageVocabulary,
} from './canvasModel'

/**
 * B-043 · the canvas as arithmetic.
 *
 * Every assertion here is one a screenshot cannot make: which lane an arc is
 * drawn on, which index a drop lands at, which of three refusals a drawn arrow
 * earns. `WorkflowDesignerPage.test.tsx` drives the gestures; this drives the
 * answers they depend on.
 */

function stage(over: Partial<Stage> & { stageCode: string }): Stage {
  return {
    id: over.id ?? 1,
    templateId: 1,
    stageCode: over.stageCode,
    displayName: over.displayName ?? over.stageCode,
    ownerRole: over.ownerRole ?? 'DEVELOPER',
    seq: over.seq ?? 10,
    position: over.position ?? 1,
    slaHours: over.slaHours ?? null,
    isOptional: over.isOptional ?? false,
    canReturnTo: over.canReturnTo ?? [],
    icon: over.icon ?? null,
    isDeprecated: over.isDeprecated ?? false,
    deprecatedAt: null,
    isCodeEditable: over.isCodeEditable ?? true,
    isDeletable: over.isDeletable ?? true,
    transitionCount: over.transitionCount ?? 0,
    openTicketCount: over.openTicketCount ?? 0,
  } as Stage
}

/** The seeded shape of Standard Dev Flow, near enough for geometry. */
function ribbon(): Stage[] {
  return [
    stage({ id: 1, stageCode: 'TRIAGE', position: 1 }),
    stage({ id: 2, stageCode: 'DEV', position: 2, canReturnTo: ['TRIAGE'] }),
    stage({ id: 3, stageCode: 'QA', position: 3, canReturnTo: ['DEV'] }),
    stage({ id: 4, stageCode: 'SIGNOFF', position: 4, canReturnTo: ['DEV', 'QA'] }),
  ]
}

const GEOMETRY = { nodeWidth: 200, gap: 40, baseline: 100, laneHeight: 28 }

describe('the palette is a vocabulary, not a catalogue', () => {
  const templates: WorkflowTemplate[] = [
    {
      id: 1,
      name: 'Standard Dev Flow',
      stages: [
        { stageCode: 'TRIAGE', displayName: 'Triage', ownerRole: 'SUPPORT' },
        { stageCode: 'DEV', displayName: 'Development', ownerRole: 'DEVELOPER' },
      ],
    },
    {
      id: 2,
      name: 'Support Fast-Track',
      stages: [
        { stageCode: 'TRIAGE', displayName: 'Triage', ownerRole: 'SUPPORT' },
        { stageCode: 'RESOLVE', displayName: 'Resolve', ownerRole: 'SUPPORT' },
      ],
    },
  ] as WorkflowTemplate[]

  it('unions the codes every template already uses', () => {
    const codes = stageVocabulary(templates, []).map((e) => e.stageCode)
    expect(codes).toContain('TRIAGE')
    expect(codes).toContain('DEV')
    expect(codes).toContain('RESOLVE')
  })

  it('names every template a code came from, because dropping it does not link to any of them', () => {
    const triage = stageVocabulary(templates, []).find((e) => e.stageCode === 'TRIAGE')
    expect(triage?.usedBy).toEqual(['Standard Dev Flow', 'Support Fast-Track'])
  })

  it('puts the most widely agreed code first', () => {
    // TRIAGE is on both templates; DEV and RESOLVE on one each.
    expect(stageVocabulary(templates, [])[0].stageCode).toBe('TRIAGE')
  })

  it('excludes what is already on this canvas — a second DEV is a 409 the palette can see coming', () => {
    const codes = stageVocabulary(templates, [stage({ stageCode: 'DEV' })]).map((e) => e.stageCode)
    expect(codes).not.toContain('DEV')
    expect(codes).toContain('TRIAGE')
  })

  it('carries the owner role forward, which is the reason to reuse a code at all', () => {
    const dev = stageVocabulary(templates, []).find((e) => e.stageCode === 'DEV')
    expect(dev?.ownerRole).toBe('DEVELOPER')
  })
})

describe('a drop lands where it was aimed', () => {
  it('before the node it was dropped on', () => {
    expect(dropIndex(2, 'before')).toBe(2)
  })

  it('after it', () => {
    expect(dropIndex(2, 'after')).toBe(3)
  })

  it('past the last node appends rather than clamping onto it', () => {
    const items = ['a', 'b', 'c']
    expect(insertAt(items, 'd', dropIndex(2, 'after'))).toEqual(['a', 'b', 'c', 'd'])
  })

  it('clamps out-of-range rather than throwing — the ends of a canvas are ordinary', () => {
    expect(insertAt(['a', 'b'], 'x', 99)).toEqual(['a', 'b', 'x'])
    expect(insertAt(['a', 'b'], 'x', -4)).toEqual(['x', 'a', 'b'])
  })

  it('does not mutate the list it was given', () => {
    const items = ['a', 'b']
    insertAt(items, 'x', 1)
    expect(items).toEqual(['a', 'b'])
  })
})

describe('drawing a return path', () => {
  it('allows a backward arrow', () => {
    expect(returnPathRefusal(ribbon(), 'QA', 'TRIAGE')).toBeNull()
  })

  it('refuses a forward one, naming the pair — the rule a checkbox list expresses by omission', () => {
    const refusal = returnPathRefusal(ribbon(), 'TRIAGE', 'QA')
    expect(refusal?.reason).toBe('forward')
    expect(refusal?.message).toContain('TRIAGE → QA')
  })

  it('refuses a stage returning to itself', () => {
    expect(returnPathRefusal(ribbon(), 'DEV', 'DEV')?.reason).toBe('self')
  })

  it('refuses an arrow into a deprecated stage — B-042', () => {
    const stages = ribbon()
    stages[0] = stage({ id: 1, stageCode: 'TRIAGE', position: 1, isDeprecated: true })
    expect(returnPathRefusal(stages, 'QA', 'TRIAGE')?.reason).toBe('deprecated')
  })

  it('refuses one that is already drawn rather than sending a duplicate', () => {
    expect(returnPathRefusal(ribbon(), 'QA', 'DEV')?.reason).toBe('exists')
  })

  it('says nothing about a code that is not on the canvas', () => {
    expect(returnPathRefusal(ribbon(), 'QA', 'NOPE')).toBeNull()
  })
})

describe('arcs are laid out so that none hides another', () => {
  it('gives two non-overlapping arcs the same lane', () => {
    // DEV → TRIAGE spans 0–1, and a hypothetical SIGNOFF → QA spans 2–3.
    const stages = [
      stage({ id: 1, stageCode: 'TRIAGE', position: 1 }),
      stage({ id: 2, stageCode: 'DEV', position: 2, canReturnTo: ['TRIAGE'] }),
      stage({ id: 3, stageCode: 'QA', position: 3 }),
      stage({ id: 4, stageCode: 'SIGNOFF', position: 4, canReturnTo: ['QA'] }),
    ]
    const lanes = returnArcs(stages).map((a) => a.lane)
    expect(lanes).toEqual([0, 0])
  })

  it('separates overlapping arcs', () => {
    const arcs = returnArcs(ribbon())
    const lane = (from: string, to: string) =>
      arcs.find((a) => a.fromCode === from && a.toCode === to)?.lane

    // SIGNOFF → DEV spans 1–3 and contains QA → DEV (1–2), so they cannot share.
    expect(lane('SIGNOFF', 'DEV')).not.toBe(lane('QA', 'DEV'))
  })

  it('pushes a containing arc outward, so a nested loop-back never crosses it', () => {
    // SIGNOFF → DEV spans 1–3 and contains QA → DEV (1–2). Drawn the other way
    // round, the inner arc would dip underneath its own container and cross it
    // twice — one arrow inside another has to look like one arrow inside another.
    const arcs = returnArcs(ribbon())
    const container = arcs.find((a) => a.fromCode === 'SIGNOFF' && a.toCode === 'DEV')!
    const nested = arcs.find((a) => a.fromCode === 'QA' && a.toCode === 'DEV')!
    expect(container.lane).toBeGreaterThan(nested.lane)
  })

  it('treats a shared endpoint as no overlap — two arrows meeting at DEV read fine on one lane', () => {
    const stages = [
      stage({ id: 1, stageCode: 'A', position: 1 }),
      stage({ id: 2, stageCode: 'B', position: 2, canReturnTo: ['A'] }),
      stage({ id: 3, stageCode: 'C', position: 3, canReturnTo: ['B'] }),
    ]
    expect(returnArcs(stages).map((a) => a.lane)).toEqual([0, 0])
  })

  it('is deterministic — the same ribbon lays out the same way twice', () => {
    expect(returnArcs(ribbon())).toEqual(returnArcs(ribbon()))
  })

  it('ignores an arrow whose target is not on the canvas', () => {
    const stages = [stage({ id: 1, stageCode: 'DEV', canReturnTo: ['GONE'] })]
    expect(returnArcs(stages)).toEqual([])
  })

  it('keeps a broken arc and marks it, rather than letting it vanish mid-drag', () => {
    // TRIAGE dragged past DEV, so DEV → TRIAGE now points forwards.
    const stages = [
      stage({ id: 2, stageCode: 'DEV', position: 1, canReturnTo: ['TRIAGE'] }),
      stage({ id: 1, stageCode: 'TRIAGE', position: 2 }),
    ]
    const arcs = returnArcs(stages)
    expect(arcs).toHaveLength(1)
    expect(arcs[0].isBroken).toBe(true)
  })
})

describe('the geometry the canvas is drawn on', () => {
  it('curves from the source down and back up into the target', () => {
    const arcs = returnArcs(ribbon())
    const dev = arcs.find((a) => a.fromCode === 'DEV')!
    const path = arcPath(dev, GEOMETRY)

    // DEV is index 1 → centre 340; TRIAGE is index 0 → centre 100.
    expect(path).toBe('M 340 100 C 340 128, 100 128, 100 100')
  })

  it('sizes the surface to the nodes, so the last one is not clipped', () => {
    expect(canvasWidth(4, GEOMETRY)).toBe(4 * 200 + 3 * 40)
    expect(canvasWidth(0, GEOMETRY)).toBe(0)
  })

  it('reserves no arc band on a template with no loop-backs', () => {
    expect(arcAreaHeight([], GEOMETRY)).toBe(0)
  })

  it('reserves one band per occupied lane', () => {
    expect(arcAreaHeight(returnArcs(ribbon()), GEOMETRY)).toBe(2 * 28)
  })
})

describe('the staged order is renumbered before the preview reads it', () => {
  it('renumbers on B-004’s 10, 20, 30 spacing — what the reorder route will write', () => {
    const dragged = [ribbon()[1], ribbon()[0]]
    expect(resequence(dragged).map((s) => s.seq)).toEqual([10, 20])
    expect(resequence(dragged).map((s) => s.position)).toEqual([1, 2])
  })

  it('makes the array order survive a sort by seq — the whole reason it exists', () => {
    // Both preview builders sort by `seq`. A dragged row keeps the seq it was
    // saved with, so without this the drag is sorted straight back out and the
    // preview shows the saved flow while the canvas shows the dragged one.
    const dragged = [
      stage({ id: 2, stageCode: 'DEV', seq: 20 }),
      stage({ id: 1, stageCode: 'TRIAGE', seq: 10 }),
    ]
    const sorted = [...resequence(dragged)].sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0))
    expect(sorted.map((s) => s.stageCode)).toEqual(['DEV', 'TRIAGE'])
  })

  it('does not mutate the rows it was given — they are the query cache’s', () => {
    const original = ribbon()
    resequence([original[1], original[0]])
    expect(original.map((s) => s.seq)).toEqual([10, 10, 10, 10])
  })
})

describe('the live region says where the node went', () => {
  it('announces a one-based position and the total', () => {
    expect(moveAnnouncement(stage({ stageCode: 'DEV', displayName: 'Development' }), 2, 4)).toBe(
      'Development moved to position 3 of 4.',
    )
  })
})
