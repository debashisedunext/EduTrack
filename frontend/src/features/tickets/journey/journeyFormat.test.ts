import { describe, expect, it } from 'vitest'

import { formatDuration, formatEffortHrs } from './journeyFormat'

describe('formatDuration', () => {
  it('renders the two shapes blueprint §4A.4 puts in the grid', () => {
    expect(formatDuration(70)).toBe('1h 10m')
    expect(formatDuration(2940)).toBe('2d 1h')
  })

  it('drops the smaller unit when it is zero, so the column scans down evenly', () => {
    expect(formatDuration(120)).toBe('2h')
    expect(formatDuration(2880)).toBe('2d')
  })

  it('renders an open hop as an em dash, never as zero', () => {
    // The hop the ticket is sitting in has no exit and no duration. `0m` would
    // read as "took no time" rather than "still running".
    expect(formatDuration(null)).toBe('—')
    expect(formatDuration(undefined)).toBe('—')
  })

  it('treats a day as 24 hours, not a working day', () => {
    // This is an elapsed wall-clock span the server measured, not SLA maths on
    // the working calendar. 1440 minutes is one day however many of them were
    // working hours.
    expect(formatDuration(1440)).toBe('1d')
    expect(formatDuration(1500)).toBe('1d 1h')
  })

  it('handles the sub-minute and negative edges without emitting nonsense', () => {
    expect(formatDuration(0)).toBe('0m')
    expect(formatDuration(-5)).toBe('—')
  })
})

describe('formatEffortHrs', () => {
  it('always shows one decimal so the column stays aligned', () => {
    expect(formatEffortHrs(9)).toBe('9.0 h')
    expect(formatEffortHrs(9.5)).toBe('9.5 h')
    expect(formatEffortHrs(0.5)).toBe('0.5 h')
  })

  it('distinguishes no effort logged from none recorded', () => {
    expect(formatEffortHrs(0)).toBe('0.0 h')
    expect(formatEffortHrs(null)).toBe('—')
  })
})
