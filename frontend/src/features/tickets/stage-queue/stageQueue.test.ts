import { describe, expect, it } from 'vitest'
import type { WorkflowTemplate } from '@/api/generated/model/workflowTemplate'

import { defaultStageFor, findStage, isBreached, queueStages, queueTitle } from './stageQueue'

const TEMPLATES = [
  {
    id: 1,
    name: 'Standard Dev Flow',
    stages: [
      { stageCode: 'TRIAGE', displayName: 'Triage', ownerRole: 'PM', sequence: 2 },
      { stageCode: 'DEVELOPMENT', displayName: 'Development', ownerRole: 'DEVELOPER', sequence: 3 },
      { stageCode: 'QA', displayName: 'QA', ownerRole: 'QA', sequence: 4 },
      { stageCode: 'DEPLOYMENT', displayName: 'Deployment', ownerRole: 'DEPLOYMENT', sequence: 5 },
      { stageCode: 'CLOSED', displayName: 'Closed', ownerRole: 'PM', sequence: 8 },
      { stageCode: 'FAX', displayName: 'Fax', ownerRole: 'SUPPORT', sequence: 9, isDeprecated: true },
    ],
  },
  {
    id: 2,
    name: 'Support Fast-Track',
    // The same code on a second template — `workflow_stages.template_id` is
    // NOT NULL, so these are two rows in the database.
    stages: [{ stageCode: 'QA', displayName: 'QA', ownerRole: 'QA', sequence: 4 }],
  },
] as unknown as WorkflowTemplate[]

describe('queueStages', () => {
  it('collapses a stage code that appears on more than one template', () => {
    const codes = queueStages(TEMPLATES).map((s) => s.stageCode)
    expect(codes.filter((c) => c === 'QA')).toHaveLength(1)
  })

  it('leaves out CLOSED and deprecated stages', () => {
    // A closed ticket is not waiting for anybody, and a retired stage accepts no
    // new entry — its queue can only ever shrink.
    const codes = queueStages(TEMPLATES).map((s) => s.stageCode)
    expect(codes).not.toContain('CLOSED')
    expect(codes).not.toContain('FAX')
  })

  it('orders by the stage sequence, not by which template was read first', () => {
    expect(queueStages(TEMPLATES).map((s) => s.stageCode)).toEqual([
      'TRIAGE',
      'DEVELOPMENT',
      'QA',
      'DEPLOYMENT',
    ])
  })

  it('survives a master that has not loaded', () => {
    expect(queueStages(undefined)).toEqual([])
  })
})

describe('defaultStageFor', () => {
  const stages = queueStages(TEMPLATES)

  it('lands each team on its own queue', () => {
    expect(defaultStageFor('QA', stages)).toBe('QA')
    expect(defaultStageFor('DEPLOYMENT', stages)).toBe('DEPLOYMENT')
    expect(defaultStageFor('DEVELOPER', stages)).toBe('DEVELOPMENT')
  })

  it('matches on the owner role, never on the stage code', () => {
    // "Waiting in QA" is the blueprint's example, not its rule. §7.4's designer
    // lets a project call that stage anything it likes, and a screen keyed on
    // the literal 'QA' would land its team nowhere the day somebody does.
    const renamed = [
      { stageCode: 'RELEASE', displayName: 'Release', ownerRole: 'DEPLOYMENT', sequence: 5 },
    ]
    const templates = [{ id: 1, stages: renamed }] as unknown as WorkflowTemplate[]
    expect(defaultStageFor('DEPLOYMENT', queueStages(templates))).toBe('RELEASE')
  })

  it('falls back to the first stage for a role that owns no queue', () => {
    // A PM or Admin owns none. An empty picker would read as a broken page
    // rather than as "pick a team".
    expect(defaultStageFor('ADMIN', stages)).toBe('TRIAGE')
    expect(defaultStageFor(undefined, stages)).toBe('TRIAGE')
  })

  it('gives nothing to select while the master is still loading', () => {
    expect(defaultStageFor('QA', [])).toBeUndefined()
  })
})

describe('queueTitle', () => {
  it('uses the blueprint’s own words, built from the display name', () => {
    expect(queueTitle(findStage(queueStages(TEMPLATES), 'QA'))).toBe('Waiting in QA')
    expect(queueTitle(findStage(queueStages(TEMPLATES), 'DEPLOYMENT'))).toBe('Waiting in Deployment')
  })

  it('does not claim a queue before one is chosen', () => {
    expect(queueTitle(undefined)).toBe('Stage queue')
  })
})

describe('isBreached', () => {
  it('reads the server’s answer and never recomputes one', () => {
    // The server measures working minutes against the calendar, holidays and
    // leave. A client recomputing from wall-clock would mark a Friday-evening
    // handoff breached by Saturday morning — the case CLAUDE.md names.
    expect(isBreached({ stageSlaBreached: true })).toBe(true)
    expect(isBreached({ stageSlaBreached: false })).toBe(false)
  })

  it('leaves a row unmarked when the server did not say', () => {
    // An unmarked breach is a missing cue; a phantom one is a lie.
    expect(isBreached({})).toBe(false)
  })
})
