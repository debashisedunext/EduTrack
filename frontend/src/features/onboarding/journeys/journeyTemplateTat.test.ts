import { describe, expect, it } from 'vitest'

import { formatTemplateTotalTatDays, templateTotalTatDays } from './journeyTemplateTat'

describe('templateTotalTatDays', () => {
  it('sums tatDays across every step', () => {
    expect(templateTotalTatDays([{ tatDays: 3 }, { tatDays: 5 }, { tatDays: 2 }])).toBe(10)
  })

  it('does not net out steps that run in parallel', () => {
    // Two steps with no dependsOnStepId between them still both count —
    // the figure is the work the template carries, not the shortest path.
    expect(templateTotalTatDays([{ tatDays: 4 }, { tatDays: 4 }])).toBe(8)
  })

  it('is zero for a template with no steps yet', () => {
    expect(templateTotalTatDays([])).toBe(0)
  })
})

describe('formatTemplateTotalTatDays', () => {
  it('pluralises', () => {
    expect(formatTemplateTotalTatDays(0)).toBe('0 working days')
    expect(formatTemplateTotalTatDays(1)).toBe('1 working day')
    expect(formatTemplateTotalTatDays(5)).toBe('5 working days')
  })
})
