import { describe, expect, it } from 'vitest'
import type { ObProject } from '@/api/generated/model/obProject'

import { currentStageLabel, delayCell, formatDate, stageProgress } from './projectRow'

/**
 * The grid's cell logic, tested where the decisions are — every one of these is
 * a sentence somebody will argue with, and three of them turn on the difference
 * between "null" and "zero".
 */

function project(over: Partial<ObProject> = {}): ObProject {
  return {
    id: 1,
    name: 'Horizon ERP Rollout 2026',
    client: { id: 1, name: 'Horizon Schools Trust', clientCode: 'HRZ-001', city: 'Bengaluru' },
    product: { id: 1, code: 'ERP', name: 'EduTrack ERP' },
    startDate: '2026-09-15',
    status: 'RUNNING',
    gateStatus: 'OPEN',
    currentStage: 'Configuration',
    stagesComplete: 2,
    stagesTotal: 5,
    journeyCount: 2,
    delayedByDays: null,
    tentativeCompletion: '2026-11-28',
    totalTatDays: 46,
    ...over,
  } as ObProject
}

describe('delayCell', () => {
  it('says "Not started" for a locked gate rather than "On time"', () => {
    // The two states the server sends as the same null. Folding them would
    // print praise against work nobody has begun.
    const cell = delayCell(project({ gateStatus: 'LOCKED', delayedByDays: null }))
    expect(cell.label).toBe('Not started')
    expect(cell.tone).toBe('unknown')
  })

  it('says "On time" for a running project with nothing overdue', () => {
    expect(delayCell(project({ delayedByDays: null })).label).toBe('On time')
  })

  it('prints the working days when late', () => {
    const cell = delayCell(project({ delayedByDays: 4 }))
    expect(cell.label).toBe('4 d')
    expect(cell.tone).toBe('late')
  })

  it('counts no delay against a held or dropped project', () => {
    // The clock was stopped on purpose. A growing number here would be days
    // nobody is working charged to somebody's record.
    expect(delayCell(project({ status: 'ON_HOLD', delayedByDays: 9 })).label).toBe('—')
    expect(delayCell(project({ status: 'DROPPED', delayedByDays: 9 })).label).toBe('—')
  })

  it('reports a completed project as completed rather than on time', () => {
    expect(delayCell(project({ status: 'COMPLETED' })).label).toBe('Completed')
  })
})

describe('currentStageLabel', () => {
  it('names the running stage', () => {
    expect(currentStageLabel(project())).toBe('Configuration')
  })

  it('says "Prerequisites pending" for a locked project rather than an em dash', () => {
    // §9's own words. An em dash reads as missing data on a column that is
    // reporting a real and actionable state.
    expect(currentStageLabel(project({ currentStage: null, gateStatus: 'LOCKED' }))).toBe(
      'Prerequisites pending',
    )
  })

  it('falls back to an em dash for an open project with nothing running', () => {
    // Every task blocked, or every one held behind a sibling service.
    expect(currentStageLabel(project({ currentStage: null }))).toBe('—')
  })
})

describe('stageProgress', () => {
  it('fills the bar to the fraction complete', () => {
    const { label, fraction } = stageProgress(project({ stagesComplete: 2, stagesTotal: 5 }))
    expect(label).toBe('2/5')
    expect(fraction).toBeCloseTo(0.4)
  })

  it('fills nothing for a project with no stages at all', () => {
    // A misconfigured Module Service, not a finished project. Dividing by zero
    // to reach 100% is the one answer that would be actively misleading.
    const { label, fraction } = stageProgress(project({ stagesComplete: 0, stagesTotal: 0 }))
    expect(label).toBe('0/0')
    expect(fraction).toBe(0)
  })
})

describe('formatDate', () => {
  it('renders an em dash for an absent date', () => {
    expect(formatDate(null)).toBe('—')
    expect(formatDate(undefined)).toBe('—')
  })

  it('does not shift the day across a timezone', () => {
    // The server sends a plain date. Parsing it as an instant would put a
    // project starting on the 15th onto the 14th for anyone west of UTC.
    expect(formatDate('2026-09-15')).toContain('15')
  })
})
