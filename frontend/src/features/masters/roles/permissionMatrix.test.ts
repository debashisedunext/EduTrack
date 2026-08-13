import { describe, expect, it } from 'vitest'
import type { Permission } from '@/api/generated/model/permission'

import {
  categoryLabel,
  groupByCategory,
  groupState,
  hasChanges,
  toRequest,
  toggle,
  toggleGroup,
} from './permissionMatrix'

const permission = (code: string, category: string, isGrantable = true): Permission => ({
  id: code.length,
  code,
  name: code,
  description: null,
  category,
  isGrantable,
})

const TICKET = [permission('ticket.create', 'ticket'), permission('ticket.close', 'ticket')]
const HISTORY = [
  permission('history.edit_delete', 'history', false),
  permission('history.view_team', 'history'),
]

describe('groupByCategory', () => {
  it('preserves the order the server returned rather than re-sorting', () => {
    // The server already answers in (category, code) order. A second sort here
    // is a second opinion about the order, and the two drift.
    const groups = groupByCategory([...TICKET, ...HISTORY])

    expect(groups.map((g) => g.category)).toEqual(['ticket', 'history'])
    expect(groups[0].permissions.map((p) => p.code)).toEqual(['ticket.create', 'ticket.close'])
  })

  it('gives an unrecognised category a readable heading rather than undefined', () => {
    // A nineteenth permission in a seventh category arrives by migration; it
    // must not render under a blank heading until this map is updated.
    expect(categoryLabel('billing')).toBe('Billing')
    expect(categoryLabel('ticket')).toBe('Tickets')
  })
})

describe('toggle', () => {
  it('ticks and unticks a grantable permission', () => {
    const once = toggle(new Set<string>(), TICKET[0])
    expect([...once]).toEqual(['ticket.create'])
    expect([...toggle(once, TICKET[0])]).toEqual([])
  })

  it('refuses to tick history.edit_delete', () => {
    // Third place this rule holds: disabled checkbox, this guard, and a 422
    // from the server. Belt and braces is right for the one that guards the
    // append-only history.
    expect([...toggle(new Set<string>(), HISTORY[0])]).toEqual([])
  })

  it('does not mutate the set it was given', () => {
    const before = new Set(['ticket.create'])
    toggle(before, TICKET[1])
    expect([...before]).toEqual(['ticket.create'])
  })
})

describe('toggleGroup', () => {
  const historyGroup = groupByCategory(HISTORY)[0]

  it('grants every grantable row and skips the one that cannot be granted', () => {
    const next = toggleGroup(new Set<string>(), historyGroup, true)

    expect([...next]).toEqual(['history.view_team'])
    expect(next.has('history.edit_delete')).toBe(false)
  })

  it('clears the section without touching other categories', () => {
    const next = toggleGroup(
      new Set(['history.view_team', 'ticket.create']),
      historyGroup,
      false,
    )

    expect([...next]).toEqual(['ticket.create'])
  })
})

describe('groupState', () => {
  const ticketGroup = groupByCategory(TICKET)[0]
  const historyGroup = groupByCategory(HISTORY)[0]

  it('reads none, some and all', () => {
    expect(groupState(new Set(), ticketGroup)).toBe('none')
    expect(groupState(new Set(['ticket.create']), ticketGroup)).toBe('some')
    expect(groupState(new Set(['ticket.create', 'ticket.close']), ticketGroup)).toBe('all')
  })

  it('reaches "all" for a section holding an ungrantable permission', () => {
    // Counting history.edit_delete would leave the History header permanently
    // indeterminate — it can never be ticked — and a control that can never
    // reach "all" is a control nobody trusts.
    expect(groupState(new Set(['history.view_team']), historyGroup)).toBe('all')
  })
})

describe('hasChanges', () => {
  it('ignores order', () => {
    // The server returns (category, code) order; the user ticks in whatever
    // order they like. Comparing arrays directly would call every touched-and-
    // untouched screen dirty.
    expect(hasChanges(new Set(['b', 'a']), ['a', 'b'])).toBe(false)
  })

  it('catches an addition, a removal and a swap of equal size', () => {
    expect(hasChanges(new Set(['a', 'b']), ['a'])).toBe(true)
    expect(hasChanges(new Set(['a']), ['a', 'b'])).toBe(true)
    expect(hasChanges(new Set(['a', 'c']), ['a', 'b'])).toBe(true)
  })

  it('treats revoking everything as a change', () => {
    expect(hasChanges(new Set(), ['a'])).toBe(true)
    expect(hasChanges(new Set(), [])).toBe(false)
  })
})

describe('toRequest', () => {
  it('sorts, so two saves of the same selection are byte-identical', () => {
    expect(toRequest(new Set(['ticket.close', 'audit.view', 'ticket.create'])))
      .toEqual(['audit.view', 'ticket.close', 'ticket.create'])
  })
})
