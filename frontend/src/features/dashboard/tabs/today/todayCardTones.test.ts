import { describe, expect, it } from 'vitest'

import { figureTone } from './todayCardTones'

describe('figureTone', () => {
  it('colours the same key differently depending on which card it is on', () => {
    // `wip` is neutral on Today's Work (the whole plate includes it) but the
    // late slice of it on Overdue — the reason the map is keyed by card and
    // figure together rather than by figure key alone.
    expect(figureTone('todays-work', 'wip')).toBe('neutral')
    expect(figureTone('overdue', 'wip')).toBe('danger')
  })

  it('matches the prototype colouring for each card', () => {
    expect(figureTone('todays-work', 'not-started')).toBe('warning')
    expect(figureTone('todays-work', 'on-time')).toBe('success')
    expect(figureTone('todays-work', 'overdue')).toBe('danger')

    expect(figureTone('overdue', 'not-started')).toBe('danger')

    expect(figureTone('not-started', 'overdue-start')).toBe('danger')
    expect(figureTone('not-started', 'due-today')).toBe('warning')

    expect(figureTone('wip', 'updated-today')).toBe('success')
    expect(figureTone('wip', 'not-updated')).toBe('warning')

    expect(figureTone('wip-breakdown', 'near-delay')).toBe('warning')
    expect(figureTone('wip-breakdown', 'delayed')).toBe('danger')
    expect(figureTone('wip-breakdown', 'on-time')).toBe('success')

    expect(figureTone('blocked', 'awaiting-info')).toBe('warning')
  })

  it('defaults to neutral for anything not named in the map', () => {
    expect(figureTone('not-started', 'total')).toBe('neutral')
    expect(figureTone('wip', 'total')).toBe('neutral')
    expect(figureTone('blocked', 'on-hold')).toBe('neutral')
    expect(figureTone('pending-review', 'total')).toBe('neutral')
    expect(figureTone('unknown-card', 'unknown-figure')).toBe('neutral')
  })
})
