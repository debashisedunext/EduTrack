import { describe, expect, it } from 'vitest'

import type { ObDashboardCard } from '@/api/generated/model'

import { cardAccessibleName, cardLook, describeDelta } from './obDashboardCards'

const card = (over: Partial<ObDashboardCard> = {}): ObDashboardCard => ({
  key: 'live',
  count: 12,
  ...over,
})

describe('cardLook', () => {
  it('labels the seven contract keys', () => {
    expect(cardLook('ongoing-projects').label).toBe('Ongoing projects')
    expect(cardLook('client-escalations').label).toBe('Client escalations')
  })

  /**
   * The key is a closed enum, so an unknown one means a server newer than this
   * bundle. Throwing would give back the whole board to gain nothing.
   */
  it('renders an unrecognised key rather than throwing', () => {
    expect(cardLook('sales-pipeline')).toEqual({
      label: 'sales-pipeline', unit: '', caption: '', tone: 'neutral',
    })
  })
})

describe('describeDelta', () => {
  it('states direction without judging it', () => {
    expect(describeDelta(3)?.arrow).toBe('▲')
    expect(describeDelta(-3)?.arrow).toBe('▼')
    expect(describeDelta(0)?.arrow).toBe('→')
  })

  it('reports magnitude unsigned, since the arrow carries the sign', () => {
    expect(describeDelta(-4)?.magnitude).toBe(4)
  })

  /**
   * Null, not "→ 0". The server sends null on the first day a deployment has
   * data, and drawing an unchanged arrow there claims nothing moved on a day
   * there was nothing to move from.
   */
  it('has nothing to say when there is no previous day', () => {
    expect(describeDelta(null)).toBeNull()
    expect(describeDelta(undefined)).toBeNull()
  })

  /**
   * "the previous day", never "yesterday". The server compares the two most
   * recent *stored* days, so a worker outage makes this a two-day comparison.
   */
  it('does not claim the comparison is against yesterday', () => {
    expect(describeDelta(2)?.spoken).toBe('up 2 since the previous day')
    expect(describeDelta(0)?.spoken).toBe('unchanged since the previous day')
  })
})

describe('cardAccessibleName', () => {
  /**
   * A screen-reader user tabbing this row otherwise hears seven identical
   * announcements and no values — `KpiCard`'s own note one module over.
   */
  it('carries the number, not just the label', () => {
    expect(cardAccessibleName(card())).toBe('Live: 12, clients live.')
  })

  it('carries the delta in words, since the arrow glyph reads as noise', () => {
    expect(cardAccessibleName(card({ deltaFromYesterday: -2 }))).toContain(
      'down 2 since the previous day',
    )
  })

  /**
   * The caveat is in the accessible name as well as on screen, because "≈"
   * announces as "almost equal to" and says nothing about why.
   */
  it('explains an upper bound rather than only marking it', () => {
    const name = cardAccessibleName(card({ countIsUpperBound: true }))

    expect(name).toContain('Live: at most 12')
    expect(name).toContain('bought several products')
  })

  /**
   * The sentence replaces the number. A zero would render as "nothing is live",
   * which is a factual claim about the data and is false.
   */
  it('replaces the number entirely when the card is unavailable', () => {
    const name = cardAccessibleName(
      card({ count: 0, unavailableReason: 'The board counts journeys containing your services.' }),
    )

    expect(name).toBe(
      'Live: unavailable. The board counts journeys containing your services.',
    )
    expect(name).not.toContain('0')
  })
})
