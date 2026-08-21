import { describe, expect, it } from 'vitest'

import type { Priority } from '@/api/generated/model/priority'

import { levelsIncludingCurrent, selectableLevels } from './levels'

const priority = (p: Partial<Priority>): Priority => ({ id: 1, isActive: true, ...p })

describe('selectableLevels', () => {
  it('keeps active levels in the master order', () => {
    expect(
      selectableLevels([
        priority({ level: 'LOW', seq: 1 }),
        priority({ level: 'MEDIUM', seq: 2 }),
        priority({ level: 'HIGH', seq: 3 }),
        priority({ level: 'CRITICAL', seq: 4 }),
      ]),
    ).toEqual(['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'])
  })

  it('drops a retired level', () => {
    expect(
      selectableLevels([
        priority({ level: 'LOW' }),
        priority({ level: 'MEDIUM', isActive: false }),
        priority({ level: 'HIGH' }),
      ]),
    ).toEqual(['LOW', 'HIGH'])
  })

  // The endpoint is active-only today, so every row arrives with `isActive:
  // true` and a filter that only *looked* like it worked would pass. The
  // retired row above is what proves it; this is what proves it did not
  // overshoot.
  it('keeps a row that omits isActive', () => {
    expect(selectableLevels([{ level: 'HIGH' }, priority({ level: 'LOW' })])).toEqual([
      'HIGH',
      'LOW',
    ])
  })

  it('drops a row with no level rather than emitting undefined', () => {
    expect(selectableLevels([priority({ name: 'Nameless' }), priority({ level: 'LOW' })])).toEqual([
      'LOW',
    ])
  })

  it('is empty while the master is loading', () => {
    expect(selectableLevels(undefined)).toEqual([])
    expect(selectableLevels([])).toEqual([])
  })
})

describe('levelsIncludingCurrent', () => {
  it('does not duplicate a current level the master still lists', () => {
    expect(
      levelsIncludingCurrent([priority({ level: 'LOW' }), priority({ level: 'HIGH' })], 'HIGH'),
    ).toEqual(['LOW', 'HIGH'])
  })

  it('appends the current level when the master has retired it', () => {
    expect(
      levelsIncludingCurrent(
        [priority({ level: 'LOW' }), priority({ level: 'CRITICAL', isActive: false })],
        'CRITICAL',
      ),
    ).toEqual(['LOW', 'CRITICAL'])
  })

  it('offers the current level alone while the master is loading', () => {
    expect(levelsIncludingCurrent(undefined, 'MEDIUM')).toEqual(['MEDIUM'])
  })
})
