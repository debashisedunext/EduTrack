import { describe, expect, it } from 'vitest'
import { createTicketBody } from '@/api/generated/zod/tickets/tickets.zod'
import {
  BUG_TASK_TYPE_CODES,
  bugTaskTypeIds,
  CLIENT_REQUIRING_TASK_TYPES,
  clientRequiringTaskTypeIds,
  emptyTicketForm,
  retainedForNextTicket,
  ticketFormSchema,
  toCreateRequest,
  type TicketFormValues,
} from './ticketForm'

const TASK_TYPES = [
  { id: 1, code: 'CHANGE_REQUEST', name: 'Change Request', isActive: true },
  { id: 2, code: 'PRODUCTION_BUG', name: 'Production Bug', isActive: true },
  { id: 5, code: 'INTERNAL_BUG', name: 'Internal Bug', isActive: true },
  { id: 6, code: 'CLIENT_BUG', name: 'Client Bug', isActive: true },
  { id: 7, code: 'SERVER_ISSUE', name: 'Server Issue', isActive: true },
]

const clientRequired = clientRequiringTaskTypeIds(TASK_TYPES)
const bugTypes = bugTaskTypeIds(TASK_TYPES)
const rules = { clientRequired, bugTypes }
const schema = ticketFormSchema(rules)

const valid: TicketFormValues = {
  ...emptyTicketForm,
  projectId: 1,
  title: 'Payment gateway times out on checkout',
  description: 'Card payments hang at the confirmation step for about 30 seconds, then fail.',
  taskTypeId: 5,
  level: 'HIGH',
  // Task type 5 is Internal Bug, so §7.5 makes the module mandatory here —
  // `valid` has to carry one or every assertion below would be measuring the
  // module rule instead of the field it names.
  moduleId: 3,
  estimatedHrs: '4.5',
}

/** What is left of a ticket someone parked half-written — C-013's Save as Draft. */
const draftable: TicketFormValues = {
  ...emptyTicketForm,
  projectId: 1,
  title: 'Half-written — finish tomorrow',
  taskTypeId: 5,
  level: 'HIGH',
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

  it('requires a module for bug-type task types and not for the others — §7.5', () => {
    const productionBug = TASK_TYPES.find((t) => t.code === 'PRODUCTION_BUG')!.id
    const changeRequest = TASK_TYPES.find((t) => t.code === 'CHANGE_REQUEST')!.id
    const serverIssue = TASK_TYPES.find((t) => t.code === 'SERVER_ISSUE')!.id

    expect(errorsByField({ ...valid, taskTypeId: productionBug, clientId: 3, moduleId: null })).toHaveProperty(
      'moduleId',
    )
    expect(
      errorsByField({ ...valid, taskTypeId: productionBug, clientId: 3, moduleId: 2 }),
    ).not.toHaveProperty('moduleId')
    // The half §7.5 argues hardest for: a change request may genuinely span
    // three modules, and forcing a choice there teaches people to pick the
    // first item in the list.
    expect(errorsByField({ ...valid, taskTypeId: changeRequest, moduleId: null })).not.toHaveProperty('moduleId')
    expect(errorsByField({ ...valid, taskTypeId: serverIssue, moduleId: null })).not.toHaveProperty('moduleId')
  })

  it('bounds screen name, feature and steps at the contract lengths', () => {
    expect(errorsByField({ ...valid, screenName: 'x'.repeat(121) })).toHaveProperty('screenName')
    expect(errorsByField({ ...valid, screenName: 'x'.repeat(120) })).not.toHaveProperty('screenName')
    expect(errorsByField({ ...valid, feature: 'x'.repeat(121) })).toHaveProperty('feature')
    expect(errorsByField({ ...valid, stepsToGenerate: `<p>${'x'.repeat(20001)}</p>` })).toHaveProperty(
      'stepsToGenerate',
    )
  })

  it('leaves all four blank fields alone when nothing requires them', () => {
    const changeRequest = TASK_TYPES.find((t) => t.code === 'CHANGE_REQUEST')!.id
    const errors = errorsByField({ ...valid, taskTypeId: changeRequest, ...({ moduleId: null } as const) })
    expect(errors).not.toHaveProperty('moduleId')
    expect(errors).not.toHaveProperty('screenName')
    expect(errors).not.toHaveProperty('feature')
    expect(errors).not.toHaveProperty('stepsToGenerate')
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

describe('bugTaskTypeIds', () => {
  it('resolves §7.5’s three bug codes against the loaded master', () => {
    expect([...bugTypes].sort()).toEqual([2, 5, 6])
  })

  it('survives a rename in the Task Type master, which the client rule does not', () => {
    // This is the whole reason the module rule matches on `code` and the client
    // rule matches on `name`. `TaskType.code` is documented in the contract as
    // immutable once created; a display name is whatever an admin last typed
    // into S-13, and the test directly above this describe block pins the fact
    // that renaming one silently disables the older rule.
    const renamed = TASK_TYPES.map((t) => ({ ...t, name: `${t.name} (2026)` }))
    expect([...bugTaskTypeIds(renamed)].sort()).toEqual([2, 5, 6])
    expect(clientRequiringTaskTypeIds(renamed).size).toBe(0)
  })

  it('names the three codes rather than testing for a _BUG suffix', () => {
    // A suffix test would capture whatever a future admin happens to call a
    // row. A validation rule should change when somebody decides it changes.
    expect([...BUG_TASK_TYPE_CODES].sort()).toEqual(['CLIENT_BUG', 'INTERNAL_BUG', 'PRODUCTION_BUG'])
    expect(bugTaskTypeIds([{ id: 99, code: 'HARDWARE_BUG', name: 'Hardware Bug' }]).size).toBe(0)
  })
})

describe('toCreateRequest', () => {
  it('produces a body the generated contract schema accepts', () => {
    // Parsed against the *generated* Zod, not a copy of it. The mapper and the
    // contract cannot drift apart without this failing.
    const body = toCreateRequest({ ...valid, clientId: 1, clientContactId: 2, assigneeId: 3, watcherIds: [4, 5] })
    expect(createTicketBody.safeParse(body)).toMatchObject({ success: true })
  })

  it('marks a ticket client-raised only when both the client and its contact are set — C-022, §4B.2', () => {
    expect(toCreateRequest({ ...valid, clientId: 1, clientContactId: 2 }).isClientRaised).toBe(true)
    expect(toCreateRequest({ ...valid, clientId: 1, clientContactId: null }).isClientRaised).toBe(false)
    expect(toCreateRequest({ ...valid, clientId: null, clientContactId: null }).isClientRaised).toBe(false)
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

  it('marks the body as a draft only on the draft action', () => {
    expect(toCreateRequest(valid, 'assign').saveAsDraft).toBe(false)
    expect(toCreateRequest(valid, 'another').saveAsDraft).toBe(false)
    expect(toCreateRequest(valid, 'draft').saveAsDraft).toBe(true)
    // The default is the primary action, so an unmigrated caller cannot
    // accidentally start saving live tickets as drafts.
    expect(toCreateRequest(valid).saveAsDraft).toBe(false)
  })

  it('carries the four §7.5 fields, sanitised, and omits the blank ones', () => {
    const body = toCreateRequest({
      ...valid,
      moduleId: 3,
      screenName: '  Fee Receipt Print  ',
      feature: '',
      stepsToGenerate: '<p>Open Fees<script>alert(1)</script></p>',
    })
    expect(body.moduleId).toBe(3)
    expect(body.screenName).toBe('Fee Receipt Print')
    expect('feature' in body).toBe(false)
    // §3.9 applies to *both* rich-text fields, not only the description.
    expect(body.stepsToGenerate).toBe('<p>Open Fees</p>')
    expect(createTicketBody.safeParse(body)).toMatchObject({ success: true })
  })

  it('omits steps the editor was focused and left empty', () => {
    // A focused-then-abandoned contentEditable holds `<p><br></p>` — 13
    // characters of nothing that a truthiness check reads as present, and the
    // detail page would then render an empty Steps section for every ticket
    // whose author clicked into the field and thought better of it.
    const body = toCreateRequest({ ...valid, stepsToGenerate: '<p><br></p>' })
    expect('stepsToGenerate' in body).toBe(false)
  })

  it('omits description and effort a draft left blank rather than sending empties', () => {
    // `estimatedHrs: 0` is a genuine zero-hour estimate and `description: ''`
    // is a genuine empty description; both are different claims from "not
    // filled in yet", and both would survive into the finished ticket.
    const body = toCreateRequest({ ...draftable, description: '  ', estimatedHrs: '' }, 'draft')
    expect('description' in body).toBe(false)
    expect('estimatedHrs' in body).toBe(false)
    expect(createTicketBody.safeParse(body)).toMatchObject({ success: true })
  })

  it('throws rather than sending a null id if it is ever called unvalidated', () => {
    expect(() => toCreateRequest(emptyTicketForm)).toThrow(/never passed validation/)
  })
})

describe('ticketFormSchema — the draft action (C-013)', () => {
  const draftSchema = ticketFormSchema(rules, 'draft')

  it('accepts a ticket that has only what the contract requires', () => {
    // `TicketCreateRequest.required` is [projectId, title, taskTypeId, level],
    // and level pre-fills from the task type — so a draft costs project, task
    // type and title. Relaxing further would just earn a 400.
    expect(draftSchema.safeParse(draftable).success).toBe(true)
    expect(schema.safeParse(draftable).success).toBe(false)
  })

  it('still insists on the four fields the contract makes required', () => {
    const result = draftSchema.safeParse(emptyTicketForm)
    expect(result.success).toBe(false)
    const fields = result.success ? [] : result.error.issues.map((i) => i.path.join('.'))
    expect(fields.sort()).toEqual(['level', 'projectId', 'taskTypeId', 'title'].sort())
  })

  it('waives the §4B.2 client rule, which is the blueprint’s and not the contract’s', () => {
    const clientBug = TASK_TYPES.find((t) => t.name === 'Client Bug')!.id
    // Chasing down which client a half-written ticket belongs to is a common
    // reason to park it as a draft in the first place.
    expect(draftSchema.safeParse({ ...draftable, taskTypeId: clientBug }).success).toBe(true)
    expect(schema.safeParse({ ...valid, taskTypeId: clientBug }).success).toBe(false)
  })

  it('waives the §7.5 module rule too — "Save as Draft waives it either way"', () => {
    const productionBug = TASK_TYPES.find((t) => t.code === 'PRODUCTION_BUG')!.id
    expect(draftSchema.safeParse({ ...draftable, taskTypeId: productionBug, moduleId: null }).success).toBe(true)
    expect(schema.safeParse({ ...valid, taskTypeId: productionBug, clientId: 3, moduleId: null }).success).toBe(
      false,
    )
  })

  it('still rejects effort the user actually typed but typed wrongly', () => {
    // A draft is permission to leave a field empty, not permission to store
    // "4h" and discover it when the ticket is finished.
    expect(draftSchema.safeParse({ ...draftable, estimatedHrs: '4h' }).success).toBe(false)
    expect(draftSchema.safeParse({ ...draftable, estimatedHrs: '' }).success).toBe(true)
    expect(draftSchema.safeParse({ ...draftable, estimatedHrs: '4.5' }).success).toBe(true)
  })
})

describe('retainedForNextTicket', () => {
  const submitted: TicketFormValues = {
    ...valid,
    clientId: 3,
    clientContactId: 7,
    assigneeId: 9,
    watcherIds: [4, 5],
    plannedCloseDate: '2026-08-20T17:30',
  }

  it('keeps what a batch shares and clears what describes one ticket', () => {
    const next = { ...emptyTicketForm, ...retainedForNextTicket(submitted) }

    expect(next).toMatchObject({
      projectId: 1,
      clientId: 3,
      clientContactId: 7,
      taskTypeId: 5,
      level: 'HIGH',
      assigneeId: 9,
      watcherIds: [4, 5],
    })
    // A title or an estimate surviving is how a batch ends up as five copies
    // of the same ticket.
    expect(next).toMatchObject({ title: '', description: '', estimatedHrs: '', plannedCloseDate: '' })
  })

  it('clears the module rather than carrying it into the next ticket', () => {
    // Deliberately unlike task type and level beside it. Module is the field
    // §7.5's mandatoriness rule exists to make somebody think about, and a
    // value pre-filled from the last ticket makes accepting the default the
    // path of least resistance on exactly that field — which is the outcome
    // §7.5 warns poisons the reporting the whole feature was asked for.
    const next = { ...emptyTicketForm, ...retainedForNextTicket(submitted) }
    expect(next.moduleId).toBeNull()
    expect(next).toMatchObject({ screenName: '', feature: '', stepsToGenerate: '' })
  })

  it('copies the watcher list rather than aliasing the one just sent', () => {
    const next = retainedForNextTicket(submitted)
    expect(next.watcherIds).not.toBe(submitted.watcherIds)
    expect(next.watcherIds).toEqual(submitted.watcherIds)
  })
})
