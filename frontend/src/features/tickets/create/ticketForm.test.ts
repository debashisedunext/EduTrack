import { describe, expect, it } from 'vitest'
import { createTicketBody } from '@/api/generated/zod/tickets/tickets.zod'
import {
  CLIENT_REQUIRING_TASK_TYPES,
  clientRequiringTaskTypeIds,
  emptyTicketForm,
  ticketFormSchema,
  toCreateRequest,
  type TicketFormValues,
} from './ticketForm'

const TASK_TYPES = [
  { id: 1, name: 'Change Request', isActive: true },
  { id: 2, name: 'Production Bug', isActive: true },
  { id: 5, name: 'Internal Bug', isActive: true },
  { id: 6, name: 'Client Bug', isActive: true },
]

const clientRequired = clientRequiringTaskTypeIds(TASK_TYPES)
const schema = ticketFormSchema(clientRequired)

const valid: TicketFormValues = {
  ...emptyTicketForm,
  projectId: 1,
  title: 'Payment gateway times out on checkout',
  description: 'Card payments hang at the confirmation step for about 30 seconds, then fail.',
  taskTypeId: 5,
  level: 'HIGH',
  estimatedHrs: '4.5',
}

/** The field a given parse complained about, so assertions name the field not the index. */
function errorsByField(values: TicketFormValues): Record<string, string> {
  const result = schema.safeParse(values)
  if (result.success) return {}
  return Object.fromEntries(result.error.issues.map((i) => [i.path.join('.'), i.message]))
}

describe('ticketFormSchema', () => {
  it('accepts a fully filled form', () => {
    expect(schema.safeParse(valid).success).toBe(true)
  })

  it('names every empty required field at once rather than one at a time', () => {
    const errors = errorsByField(emptyTicketForm)
    expect(Object.keys(errors).sort()).toEqual(
      ['description', 'estimatedHrs', 'level', 'projectId', 'taskTypeId', 'title'].sort(),
    )
  })

  it('rejects a title shorter than the contract minimum', () => {
    expect(errorsByField({ ...valid, title: 'hi' })).toHaveProperty('title')
  })

  it('requires a description even though the contract leaves it optional', () => {
    // Blueprint §7.5 marks Task Description mandatory, and the blueprint wins
    // on behaviour. This is the test that keeps the two from being reconciled
    // the wrong way round.
    expect(errorsByField({ ...valid, description: '   ' })).toHaveProperty('description')
  })

  it('rejects effort that is not a plain decimal number of hours', () => {
    for (const bad of ['4,5', '4h', 'four', '-2', '0', '']) {
      expect(errorsByField({ ...valid, estimatedHrs: bad }), bad).toHaveProperty('estimatedHrs')
    }
    for (const good of ['4', '4.5', '0.25', '12.75']) {
      expect(errorsByField({ ...valid, estimatedHrs: good }), good).not.toHaveProperty('estimatedHrs')
    }
  })

  it('requires a client for client-facing task types and not for internal ones', () => {
    const clientBug = TASK_TYPES.find((t) => t.name === 'Client Bug')!.id
    const internalBug = TASK_TYPES.find((t) => t.name === 'Internal Bug')!.id

    expect(errorsByField({ ...valid, taskTypeId: clientBug, clientId: null })).toHaveProperty('clientId')
    expect(errorsByField({ ...valid, taskTypeId: clientBug, clientId: 3 })).not.toHaveProperty('clientId')
    expect(errorsByField({ ...valid, taskTypeId: internalBug, clientId: null })).not.toHaveProperty('clientId')
  })

  it('rejects a contact without its client', () => {
    expect(errorsByField({ ...valid, clientId: null, clientContactId: 7 })).toHaveProperty('clientContactId')
  })

  it('accepts a blank planned close date and rejects an unparseable one', () => {
    expect(errorsByField({ ...valid, plannedCloseDate: '' })).not.toHaveProperty('plannedCloseDate')
    expect(errorsByField({ ...valid, plannedCloseDate: 'next tuesday' })).toHaveProperty('plannedCloseDate')
  })
})

describe('clientRequiringTaskTypeIds', () => {
  it('resolves the §4B.2 names against the loaded master', () => {
    expect([...clientRequired].sort()).toEqual([2, 6])
  })

  it('yields nothing when the master renames a type out from under it', () => {
    // The rule matches on a display string because `TaskType` has no
    // `requiresClient` flag yet. This test exists so the failure mode is
    // written down rather than discovered when a client ticket saves without
    // a client — see this folder's README.
    const renamed = TASK_TYPES.map((t) => ({ ...t, name: `${t.name} (v2)` }))
    expect(clientRequiringTaskTypeIds(renamed).size).toBe(0)
    expect(CLIENT_REQUIRING_TASK_TYPES).toContain('Client Bug')
  })
})

describe('toCreateRequest', () => {
  it('produces a body the generated contract schema accepts', () => {
    // Parsed against the *generated* Zod, not a copy of it. The mapper and the
    // contract cannot drift apart without this failing.
    const body = toCreateRequest({ ...valid, clientId: 1, clientContactId: 2, assigneeId: 3, watcherIds: [4, 5] })
    expect(createTicketBody.safeParse(body)).toMatchObject({ success: true })
  })

  it('omits plannedCloseDate entirely when blank', () => {
    // Omitted means "compute it from the SLA policy". An explicit null would
    // mean "this ticket has no planned close date", which takes it out of every
    // delay calculation — a different thing, and a silent one.
    const body = toCreateRequest({ ...valid, plannedCloseDate: '' })
    expect('plannedCloseDate' in body).toBe(false)
  })

  it('sends an overridden planned close date as an ISO instant', () => {
    const body = toCreateRequest({ ...valid, plannedCloseDate: '2026-08-20T17:30' })
    expect(body.plannedCloseDate).toBe(new Date('2026-08-20T17:30').toISOString())
    expect(createTicketBody.safeParse(body)).toMatchObject({ success: true })
  })

  it('trims the title and description', () => {
    const body = toCreateRequest({ ...valid, title: '  Spaced out  ', description: '  Body  ' })
    expect(body.title).toBe('Spaced out')
    expect(body.description).toBe('Body')
  })

  it('never sends saveAsDraft true — the draft action is C-013', () => {
    expect(toCreateRequest(valid).saveAsDraft).toBe(false)
  })

  it('throws rather than sending a null id if it is ever called unvalidated', () => {
    expect(() => toCreateRequest(emptyTicketForm)).toThrow(/never passed validation/)
  })
})
