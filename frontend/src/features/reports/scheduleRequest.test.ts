import { describe, expect, it } from 'vitest'

import { filtersFrom, splitAddresses } from './scheduleRequest'

/**
 * A-065 · the two pure parts of turning the viewer into a schedule request.
 */

describe('filtersFrom', () => {
  /**
   * 🔴 The one that matters. The viewer posts back the filter bar it rendered,
   * dates included; if they were kept they would win over the cadence and every
   * run would email the same frozen window for ever — which looks exactly like
   * a working schedule until two files are compared.
   */
  it('drops the date range, because the period comes from the cadence', () => {
    const params = new URLSearchParams({
      from: '2026-08-01',
      to: '2026-08-07',
      projectId: '4',
    })

    expect(filtersFrom(params)).toEqual({ projectId: '4' })
  })

  it('keeps every other filter the viewer set', () => {
    const params = new URLSearchParams({
      projectId: '4',
      resourceId: '9',
      clientId: '2',
      taskTypeId: '7',
      level: 'CRITICAL',
    })

    expect(filtersFrom(params)).toEqual({
      projectId: '4',
      resourceId: '9',
      clientId: '2',
      taskTypeId: '7',
      level: 'CRITICAL',
    })
  })

  /**
   * `?projectId=` with nothing after it is a filter bar that has been cleared.
   * Storing it would be a filter nobody chose, and on a schedule that runs for
   * months it is a filter nobody can see either.
   */
  it('drops cleared filters rather than storing an empty one', () => {
    const params = new URLSearchParams('projectId=&level=HIGH')

    expect(filtersFrom(params)).toEqual({ level: 'HIGH' })
  })

  it('is an empty object when nothing is filtered', () => {
    expect(filtersFrom(new URLSearchParams())).toEqual({})
  })
})

describe('splitAddresses', () => {
  it('splits and trims', () => {
    expect(splitAddresses('a@example.test, b@example.test')).toEqual([
      'a@example.test',
      'b@example.test',
    ])
  })

  /** The most common way this field is actually typed. */
  it('tolerates a trailing comma rather than making it an error', () => {
    expect(splitAddresses('a@example.test,')).toEqual(['a@example.test'])
  })

  it('is empty for a blank field', () => {
    expect(splitAddresses('   ')).toEqual([])
  })
})
