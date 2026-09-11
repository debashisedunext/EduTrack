import { describe, expect, it } from 'vitest'

import { buildStepTree, scheduleBar, scheduleLabel } from './journeyTemplateTree'

/**
 * The designer's tree and the day ranges beside it. Every case here is about
 * one of two things: that no step can disappear from a screen that edits it,
 * and that a child never starts before its parent has ended.
 */
type Step = { id: number; tatDays: number; dependsOnStepId?: number | null; name: string }

const step = (id: number, tatDays: number, dependsOnStepId: number | null = null): Step => ({
  id,
  tatDays,
  dependsOnStepId,
  name: `step ${id}`,
})

describe('buildStepTree', () => {
  it('nests a chain and schedules each step from the day its parent ends', () => {
    const tree = buildStepTree([step(1, 2), step(2, 3, 1), step(3, 5, 2)])

    expect(tree.flat.map((n) => [n.step.id, n.depth, n.startDay, n.endDay])).toEqual([
      [1, 0, 1, 2],
      [2, 1, 3, 5],
      [3, 2, 6, 10],
    ])
    expect(tree.spanDays).toBe(10)
  })

  it('numbers depth-first, not along the flat sequence', () => {
    // Two roots, the second declared first in the list; its child must still
    // take the number directly after it rather than after every other root.
    const tree = buildStepTree([step(1, 1), step(2, 1), step(3, 1, 1)])
    expect(tree.flat.map((n) => [n.step.id, n.number])).toEqual([
      [1, 1],
      [3, 2],
      [2, 3],
    ])
  })

  it('starts every root on day one — parallel, not sequential', () => {
    const tree = buildStepTree([step(1, 4), step(2, 6)])

    expect(tree.roots).toHaveLength(2)
    expect(tree.flat.map((n) => n.startDay)).toEqual([1, 1])
    // The span is the longer of the two, not their sum: they run at once.
    expect(tree.spanDays).toBe(6)
  })

  it('keeps a step whose predecessor is not in the list, as a root', () => {
    // The staged-reorder view mid-edit, or a payload that disagrees with
    // itself. Either way a step vanishing from the screen that edits it is
    // the one outcome that must not be possible.
    const tree = buildStepTree([step(2, 3, 99)])

    expect(tree.flat.map((n) => n.step.id)).toEqual([2])
    expect(tree.flat[0].depth).toBe(0)
    expect(tree.flat[0].startDay).toBe(1)
  })

  it('terminates on a cycle, and still draws every step once', () => {
    // The backend computes `parallelGroups`, so it rejects a cycle before it
    // reaches here — which is exactly why the guard has to be cheap and has
    // to be tested: nothing else would ever exercise it.
    const tree = buildStepTree([step(1, 1, 2), step(2, 1, 1)])

    expect(tree.flat.map((n) => n.step.id).sort()).toEqual([1, 2])
    expect(new Set(tree.flat.map((n) => n.number)).size).toBe(2)
  })

  it('is empty for a template with no steps', () => {
    const tree = buildStepTree([])
    expect(tree.roots).toEqual([])
    expect(tree.flat).toEqual([])
    expect(tree.spanDays).toBe(0)
  })
})

describe('scheduleLabel', () => {
  it('prints a range, and a single day without one', () => {
    expect(scheduleLabel({ startDay: 1, endDay: 2 })).toBe('Day 1–2')
    expect(scheduleLabel({ startDay: 7, endDay: 7 })).toBe('Day 7')
  })
})

describe('scheduleBar', () => {
  it('offsets and sizes the bar against the template span', () => {
    expect(scheduleBar({ startDay: 6, endDay: 10 }, 20)).toEqual({ left: '25%', width: '25%' })
    expect(scheduleBar({ startDay: 1, endDay: 4 }, 4)).toEqual({ left: '0%', width: '100%' })
  })

  it('draws nothing rather than NaN for an empty template', () => {
    expect(scheduleBar({ startDay: 1, endDay: 1 }, 0)).toEqual({ left: '0%', width: '0%' })
  })
})
