import { describe, expect, it } from 'vitest'

import { mergeByCategory } from './mergeByCategory'

/**
 * A-056 · the transpose, and the one thing it must not do.
 *
 * Recharts reads undefined as a break in a line and zero as a value. Filling a
 * gap with zero therefore draws every unsummarised day as a plunge to the axis
 * and back — on every weekend and after every outage — and the shape is the
 * entire content of a trend chart. That is the assertion below, and it is the
 * reason this function is not two lines inline in four components.
 */
describe('mergeByCategory', () => {
  it('gives one row per category with a column per series', () => {
    const rows = mergeByCategory([
      { name: 'Created', points: [{ x: 'Mon', y: 2, drillDown: null }] },
      { name: 'Closed', points: [{ x: 'Mon', y: 1, drillDown: null }] },
    ])

    expect(rows).toEqual([{ category: 'Mon', Created: 2, Closed: 1 }])
  })

  it('leaves a missing point undefined rather than zero, so the line breaks instead of dipping', () => {
    const rows = mergeByCategory([
      {
        name: 'Created',
        points: [
          { x: 'Mon', y: 2, drillDown: null },
          { x: 'Wed', y: 3, drillDown: null },
        ],
      },
      { name: 'Closed', points: [{ x: 'Mon', y: 1, drillDown: null }] },
    ])

    const wednesday = rows.find((r) => r.category === 'Wed')!
    expect(wednesday.Closed).toBeUndefined()
    expect(wednesday.Closed).not.toBe(0)
  })

  it('keeps a category that only a later series mentions', () => {
    const rows = mergeByCategory([
      { name: 'In progress', points: [{ x: 'Ravi', y: 3, drillDown: null }] },
      { name: 'Delayed', points: [{ x: 'Neha', y: 1, drillDown: null }] },
    ])

    expect(rows.map((r) => r.category)).toEqual(['Ravi', 'Neha'])
  })

  it('preserves a genuine zero, which is not the same as a gap', () => {
    const rows = mergeByCategory([
      { name: 'Created', points: [{ x: 'Mon', y: 0, drillDown: null }] },
    ])

    expect(rows[0].Created).toBe(0)
  })

  it('returns nothing for no series rather than a row of undefineds', () => {
    expect(mergeByCategory([])).toEqual([])
  })
})
