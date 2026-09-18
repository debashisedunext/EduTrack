import { describe, expect, it } from 'vitest'

import type { ObDashboardCard } from '@/api/generated/model'

import {
  cardAccessibleName,
  cardLook,
  describeDelta,
  seesProjectBoard,
  visibleCardCount,
  visibleCards,
} from './obDashboardCards'

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

describe('visibleCards', () => {
  const sent = [
    { key: 'ongoing-projects' },
    { key: 'this-weeks-deadlines' },
    { key: 'todays-delivery' },
    { key: 'overdue-clients' },
    { key: 'client-escalations' },
    { key: 'live' },
    { key: 'at-risk' },
  ]

  it('gives an Admin every card the server sent, in that order', () => {
    expect(visibleCards(sent, true)).toEqual(sent)
  })

  /**
   * `at-risk` and `client-escalations` are readings of other people's work.
   * The server sends both to everybody — the contract's enum is one list — so
   * this filter is the only thing keeping them off a non-admin's row.
   */
  it('takes At risk and Client escalations off a non-admin board', () => {
    expect(visibleCards(sent, false).map((card) => card.key)).toEqual([
      'ongoing-projects',
      'this-weeks-deadlines',
      'todays-delivery',
      'overdue-clients',
      'live',
    ])
  })

  /**
   * `live` was filtered here once, for everybody. It is a counter about the
   * reader's own clients rather than a management figure, so it stayed on the
   * board when the two management ones came off.
   */
  it('keeps Live for both readers', () => {
    expect(visibleCards(sent, true).some((card) => card.key === 'live')).toBe(true)
    expect(visibleCards(sent, false).some((card) => card.key === 'live')).toBe(true)
  })

  /**
   * An unknown key means a server newer than this bundle, and it should add a
   * card rather than be dropped by a filter that only knows last month's enum.
   * The two admin keys are deliberately not covered by that tolerance.
   */
  it('passes an unrecognised key through for either reader', () => {
    const withNew = [...sent, { key: 'sales-pipeline' }]

    expect(visibleCards(withNew, false).map((card) => card.key)).toContain('sales-pipeline')
    expect(visibleCards(withNew, true).map((card) => card.key)).toContain('sales-pipeline')
  })
})

describe('visibleCardCount', () => {
  /**
   * The skeleton's width. It has to agree with what {@link visibleCards} will
   * return for the same reader, or the row reflows the moment the figures
   * land — which is the single thing a fixed count exists to prevent.
   */
  it('matches what the same reader is actually drawn', () => {
    const sent = [
      { key: 'ongoing-projects' },
      { key: 'this-weeks-deadlines' },
      { key: 'todays-delivery' },
      { key: 'overdue-clients' },
      { key: 'client-escalations' },
      { key: 'live' },
      { key: 'at-risk' },
    ]

    expect(visibleCardCount(true)).toBe(visibleCards(sent, true).length)
    expect(visibleCardCount(false)).toBe(visibleCards(sent, false).length)
    expect(visibleCardCount(true)).toBe(7)
    expect(visibleCardCount(false)).toBe(5)
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


/**
 * Who the project board and its four tabs are drawn for.
 *
 * <p>The board is the same screen for all of them; what differs is the rows
 * behind it, and that is decided server-side from `CallerIdentity` — so these
 * cases are about which screen is offered, never about what anyone is allowed
 * to read.
 */
describe('seesProjectBoard', () => {
  it('draws the board for the three onboarding roles that deliver work', () => {
    for (const role of ['OB_ADMIN', 'OB_MANAGER', 'OB_STEP_OWNER']) {
      expect(seesProjectBoard(role, false)).toBe(true)
    }
  })

  /*
    Neither delivers work, so the four tabs — who is delivering what, and
    when — are a reading of somebody else's week. Both keep the counter row.
  */
  it('leaves Sales and a Viewer on the counter row', () => {
    expect(seesProjectBoard('OB_SALES', false)).toBe(false)
    expect(seesProjectBoard('OB_VIEWER', false)).toBe(false)
  })

  /*
    The gate this replaced. A platform admin who holds no ONBOARDING grant
    would otherwise have lost the board they already have, which is a
    regression rather than a permission change.
  */
  it('keeps the board for a platform admin with no onboarding grant', () => {
    expect(seesProjectBoard(undefined, true)).toBe(true)
    expect(seesProjectBoard(null, true)).toBe(true)
  })

  it('draws nothing for a caller with no onboarding standing at all', () => {
    expect(seesProjectBoard(undefined, false)).toBe(false)
    expect(seesProjectBoard(null, false)).toBe(false)
    // An unknown role is not a role this screen knows to widen for.
    expect(seesProjectBoard('OB_SOMETHING_NEW', false)).toBe(false)
  })
})
