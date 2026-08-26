import { describe, expect, it } from 'vitest'
import { createTicketBody } from '@/api/generated/zod/tickets/tickets.zod'
import type { ProjectSettings } from '@/api/generated/model/projectSettings'
import type { TicketFieldCode } from '@/api/generated/model/ticketFieldCode'
import {
  allowedTaskTypes,
  BUG_TASK_TYPE_CODES,
  bugTaskTypeIds,
  CLIENT_REQUIRING_TASK_TYPES,
  clientRequiringTaskTypeIds,
  emptyTicketForm,
  projectRulesFrom,
  retainedForNextTicket,
  ticketFormSchema,
  toCreateRequest,
  type ProjectRules,
  type TicketFormValues,
  type TicketSaveAction,
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
/** D-066 · the four the priority master seeds; `Level` is no longer a type to check against. */
const rules = { clientRequired, bugTypes, levels: new Set(['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']) }
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
  // Same reasoning as the module directly above: the assignee is mandatory on
  // the three live save actions, so a `valid` without one would make every
  // assertion in this file measure the assignee rule as well as its own.
  assigneeId: 9,
  // And again for the client. §4B.2 used to ask for one only on the three
  // client-facing types — task type 5 is Internal Bug, which was exactly the
  // case that went without — and it is asked for on every type now.
  clientId: 3,
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
      [
        'assigneeId',
        'clientId',
        'description',
        'estimatedHrs',
        'level',
        'moduleId',
        'projectId',
        'taskTypeId',
        'title',
      ].sort(),
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

  it('requires an assignee, which the contract and §7.5 both leave optional', () => {
    // A recorded deviation, not a blueprint rule — §7.5 puts no asterisk on
    // Assigned To. It is the *screen's* rule: unassigned tickets still exist
    // and still have to, because D-036's email-to-ticket and B-053's import
    // raise them with nobody standing in front of a form. This test is what
    // keeps the deviation from being reconciled the wrong way round, the same
    // job the description test above it does.
    expect(errorsByField({ ...valid, assigneeId: null })).toHaveProperty(
      'assigneeId',
      'Pick who this ticket is assigned to',
    )
    expect(errorsByField({ ...valid, assigneeId: 9 })).not.toHaveProperty('assigneeId')
  })

  it('rejects effort that is not a plain decimal number of hours', () => {
    for (const bad of ['4,5', '4h', 'four', '-2', '0', '']) {
      expect(errorsByField({ ...valid, estimatedHrs: bad }), bad).toHaveProperty('estimatedHrs')
    }
    for (const good of ['4', '4.5', '0.25', '12.75']) {
      expect(errorsByField({ ...valid, estimatedHrs: good }), good).not.toHaveProperty('estimatedHrs')
    }
  })

  it('requires a client on every task type, not only the client-facing three', () => {
    const clientBug = TASK_TYPES.find((t) => t.name === 'Client Bug')!.id
    const internalBug = TASK_TYPES.find((t) => t.name === 'Internal Bug')!.id

    expect(errorsByField({ ...valid, taskTypeId: clientBug, clientId: null })).toHaveProperty('clientId')
    expect(errorsByField({ ...valid, taskTypeId: clientBug, clientId: 3 })).not.toHaveProperty('clientId')
    // The deliberate deviation from §4B.2, which said in as many words that
    // "Internal Bug does not" require a client. The assertion is written the
    // way round it is on purpose: if the rule is ever narrowed back, this is
    // the line that says so.
    expect(errorsByField({ ...valid, taskTypeId: internalBug, clientId: null })).toHaveProperty('clientId')
    // ...and a client clears it there exactly as it does on a Client Bug.
    expect(errorsByField({ ...valid, taskTypeId: internalBug, clientId: 3 })).not.toHaveProperty('clientId')
  })

  it('names the client-facing type and the internal one differently in the same refusal', () => {
    // `clientRequired` stopped gating the rule and now only picks the wording,
    // the same job `bugTypes` was left with. If it ever stops doing that too it
    // is dead weight in `TaskTypeRules` — this is the test that would notice.
    const clientBug = TASK_TYPES.find((t) => t.name === 'Client Bug')!.id
    const internalBug = TASK_TYPES.find((t) => t.name === 'Internal Bug')!.id

    expect(errorsByField({ ...valid, taskTypeId: clientBug, clientId: null }).clientId).toBe(
      'This task type is client-facing — pick the client it was raised for',
    )
    // Telling somebody an Internal Bug is client-facing reads as a bug in the
    // form, which is the whole reason the wording still forks.
    expect(errorsByField({ ...valid, taskTypeId: internalBug, clientId: null }).clientId).toBe(
      'Pick the client this ticket was raised for',
    )
  })

  it('requires a module on every task type, not only the bug-type three', () => {
    const productionBug = TASK_TYPES.find((t) => t.code === 'PRODUCTION_BUG')!.id
    const changeRequest = TASK_TYPES.find((t) => t.code === 'CHANGE_REQUEST')!.id
    const serverIssue = TASK_TYPES.find((t) => t.code === 'SERVER_ISSUE')!.id

    expect(errorsByField({ ...valid, taskTypeId: productionBug, moduleId: null })).toHaveProperty(
      'moduleId',
    )
    expect(
      errorsByField({ ...valid, taskTypeId: productionBug, moduleId: 2 }),
    ).not.toHaveProperty('moduleId')
    // The deliberate deviation from §7.5, which called these two optional. The
    // assertion is written the way round it is on purpose: if the rule is ever
    // narrowed back, this is the line that says so.
    expect(errorsByField({ ...valid, taskTypeId: changeRequest, moduleId: null })).toHaveProperty('moduleId')
    expect(errorsByField({ ...valid, taskTypeId: serverIssue, moduleId: null })).toHaveProperty('moduleId')
    // ...and a module clears it on those two exactly as it does on a bug.
    expect(errorsByField({ ...valid, taskTypeId: changeRequest, moduleId: 2 })).not.toHaveProperty('moduleId')
  })

  it('names the bug and the non-bug differently in the same refusal', () => {
    // `bugTypes` stopped gating the rule and now only picks the wording. If it
    // ever stops doing that too, it is dead weight in `TaskTypeRules` — this is
    // the test that would notice.
    const productionBug = TASK_TYPES.find((t) => t.code === 'PRODUCTION_BUG')!.id
    const changeRequest = TASK_TYPES.find((t) => t.code === 'CHANGE_REQUEST')!.id

    expect(errorsByField({ ...valid, taskTypeId: productionBug, moduleId: null }).moduleId).toBe(
      'Pick the module this bug is in — it is what routes it to the right team',
    )
    expect(errorsByField({ ...valid, taskTypeId: changeRequest, moduleId: null }).moduleId).toBe(
      'Pick the module this ticket belongs to — it is what routes it to the right team',
    )
  })

  it('bounds screen name, feature and steps at the contract lengths', () => {
    expect(errorsByField({ ...valid, screenName: 'x'.repeat(121) })).toHaveProperty('screenName')
    expect(errorsByField({ ...valid, screenName: 'x'.repeat(120) })).not.toHaveProperty('screenName')
    expect(errorsByField({ ...valid, feature: 'x'.repeat(121) })).toHaveProperty('feature')
    expect(errorsByField({ ...valid, stepsToGenerate: `<p>${'x'.repeat(20001)}</p>` })).toHaveProperty(
      'stepsToGenerate',
    )
  })

  it('leaves the three remaining blank fields alone when nothing requires them', () => {
    // Module used to be the fourth. It is required unconditionally now, so it
    // is supplied here rather than asserted blank — the point of this test is
    // that a blank *optional* field is not an error, and Module is no longer
    // one of those.
    const changeRequest = TASK_TYPES.find((t) => t.code === 'CHANGE_REQUEST')!.id
    const errors = errorsByField({ ...valid, taskTypeId: changeRequest, moduleId: 3 })
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
    // `requiresClient` flag yet. The failure mode this pins is far cheaper than
    // it was: a rename used to disable the client rule outright, and since the
    // rule was widened to every task type it costs only the more specific of
    // two refusal messages — see this folder's README.
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
    // that renaming one silently blunts the older rule's wording.
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
    const internalBug = TASK_TYPES.find((t) => t.name === 'Internal Bug')!.id
    // Chasing down which client a half-written ticket belongs to is a common
    // reason to park it as a draft in the first place.
    expect(draftSchema.safeParse({ ...draftable, taskTypeId: clientBug }).success).toBe(true)
    expect(schema.safeParse({ ...valid, taskTypeId: clientBug, clientId: null }).success).toBe(false)
    // The waiver had to widen with the rule, exactly as the module's did. An
    // internal bug now needs a client to be saved live, so a draft is the only
    // way to park one before the reporter knows who it was raised for — and
    // `draftable` is task type 5, which is that internal bug.
    expect(draftSchema.safeParse({ ...draftable, taskTypeId: internalBug }).success).toBe(true)
    expect(schema.safeParse({ ...valid, taskTypeId: internalBug, clientId: null }).success).toBe(false)
  })

  it('waives the §7.5 module rule too — "Save as Draft waives it either way"', () => {
    const productionBug = TASK_TYPES.find((t) => t.code === 'PRODUCTION_BUG')!.id
    const changeRequest = TASK_TYPES.find((t) => t.code === 'CHANGE_REQUEST')!.id
    expect(draftSchema.safeParse({ ...draftable, taskTypeId: productionBug, moduleId: null }).success).toBe(true)
    expect(schema.safeParse({ ...valid, taskTypeId: productionBug, moduleId: null }).success).toBe(
      false,
    )
    // The waiver had to widen with the rule. A change request now needs a
    // module to be saved live, so a draft is the only way to park one before
    // the reporter knows where it happened — if the waiver did not cover the
    // non-bug types, widening the rule would have taken that away.
    expect(draftSchema.safeParse({ ...draftable, taskTypeId: changeRequest, moduleId: null }).success).toBe(true)
    expect(schema.safeParse({ ...valid, taskTypeId: changeRequest, moduleId: null }).success).toBe(false)
  })

  it('waives the assignee, which is this screen’s rule and not the contract’s', () => {
    // Deciding who picks a half-written ticket up is a common reason to park it
    // as a draft — the same argument the client rule directly above makes.
    expect(draftSchema.safeParse({ ...draftable, assigneeId: null }).success).toBe(true)
    expect(schema.safeParse({ ...valid, assigneeId: null }).success).toBe(false)
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

/* ── C-071 — the project's own settings ────────────────────────────────── */

const settings = (over: Partial<ProjectSettings> = {}): ProjectSettings => ({
  projectId: 1,
  autoAssignRule: 'MANUAL',
  mandatoryFields: [],
  restrictsTaskTypes: false,
  taskTypes: TASK_TYPES.map((t) => ({
    taskTypeId: t.id,
    code: t.code,
    name: t.name,
    isAllowed: true,
    isActive: t.isActive,
  })),
  ...over,
})

describe('projectRulesFrom', () => {
  it('reads an unrestricted project as null, not as an empty set', () => {
    // The decision B-019 turns on. An empty *set* would mean "no task type may
    // be raised", which is the state that does not exist — a project allowing
    // none could raise no ticket, and every project is unconfigured until
    // somebody configures one.
    expect(projectRulesFrom(settings()).allowedTaskTypeIds).toBeNull()
  })

  it('reads the allow-list off isAllowed when the project restricts', () => {
    const rules = projectRulesFrom(
      settings({
        restrictsTaskTypes: true,
        taskTypes: settings().taskTypes.map((t) => ({ ...t, isAllowed: t.taskTypeId === 2 })),
      }),
    )
    expect(rules.allowedTaskTypeIds).toEqual(new Set([2]))
  })

  it('falls back to no rules at all while the settings are in flight', () => {
    // Not "everything is forbidden". A form that has not been told the rules yet
    // must behave exactly as it did before this feature existed.
    const rules = projectRulesFrom(undefined)
    expect(rules.allowedTaskTypeIds).toBeNull()
    expect(rules.mandatoryFields.size).toBe(0)
  })
})

describe('allowedTaskTypes', () => {
  it('offers everything on an unrestricted project', () => {
    expect(allowedTaskTypes(TASK_TYPES, projectRulesFrom(settings()))).toHaveLength(TASK_TYPES.length)
  })

  it('offers only the allow-list on a restricted one', () => {
    const rules = projectRulesFrom(
      settings({
        restrictsTaskTypes: true,
        taskTypes: settings().taskTypes.map((t) => ({ ...t, isAllowed: t.taskTypeId !== 1 })),
      }),
    )
    expect(allowedTaskTypes(TASK_TYPES, rules).map((t) => t.id)).not.toContain(1)
  })
})

describe('ticketFormSchema — the project rules (C-071)', () => {
  const requiring = (...fields: TicketFieldCode[]) =>
    projectRulesFrom(settings({ mandatoryFields: fields }))

  const parseWith = (values: TicketFormValues, project: ProjectRules, action: TicketSaveAction = 'assign') => {
    const result = ticketFormSchema(rules, action, project).safeParse(values)
    if (result.success) return {}
    return Object.fromEntries(result.error.issues.map((i) => [i.path.join('.'), i.message]))
  }

  it('leaves a form alone when the project requires nothing', () => {
    expect(parseWith(valid, projectRulesFrom(settings()))).toEqual({})
  })

  it('refuses a field the project requires', () => {
    expect(parseWith(valid, requiring('SCREEN_NAME'))).toEqual({
      screenName: 'This project requires a screen name on every ticket',
    })
  })

  it('accepts it once answered', () => {
    expect(parseWith({ ...valid, screenName: 'Fee Receipt Print' }, requiring('SCREEN_NAME'))).toEqual({})
  })

  it('does not accept an editor that was focused and left as an answer', () => {
    // `<p><br></p>` is what a contentEditable holds after a click and a click
    // away — thirteen characters that `.min(1)` would call present.
    // `ProjectTicketRules.hasRichText` is the same rule on the server.
    expect(parseWith({ ...valid, stepsToGenerate: '<p><br></p>' }, requiring('STEPS_TO_GENERATE'))).toEqual(
      { stepsToGenerate: 'This project requires steps to generate on every ticket' },
    )
  })

  it('counts a pasted screenshot as an answer', () => {
    expect(
      parseWith(
        { ...valid, stepsToGenerate: '<p><img src="https://edutrack.test/shot.png" alt=""></p>' },
        requiring('STEPS_TO_GENERATE'),
      ),
    ).toEqual({})
  })

  it('does not measure a plain field through the sanitiser', () => {
    // `isRichTextEmpty` strips markup before it looks, so a screen name typed as
    // `<3` would come back empty and be refused — a rule rejecting a value the
    // server stores happily. Only the two rich-text fields are measured that way.
    expect(parseWith({ ...valid, screenName: '<3' }, requiring('SCREEN_NAME'))).toEqual({})
  })

  it('holds a draft to the project’s fields, where §7.5’s own are waived', () => {
    // The draft still waives the description and the estimate — C-013's rule,
    // untouched. It does not waive the project's: `saveAsDraft` is accepted by
    // the server and acted on nowhere, so a draft that waived them would be a
    // one-click opt-out of a project's configuration producing a ticket
    // indistinguishable from any other.
    const errors = parseWith(draftable, requiring('SCREEN_NAME'), 'draft')
    expect(errors).toEqual({ screenName: 'This project requires a screen name on every ticket' })
  })

  it('refuses a task type the project does not accept', () => {
    const project = projectRulesFrom(
      settings({
        restrictsTaskTypes: true,
        taskTypes: settings().taskTypes.map((t) => ({ ...t, isAllowed: t.taskTypeId === 1 })),
      }),
    )
    // `valid` is task type 5. The picker no longer offers it, so this covers the
    // path a filtered list cannot: one carried across projects by Save & Create
    // Another, whose `retainedForNextTicket` keeps the task type on purpose.
    expect(parseWith(valid, project)).toEqual({
      taskTypeId: 'This project does not accept that task type — pick one from the list',
    })
  })

  it('reports every missing field at once', () => {
    // CLIENT_CONTACT rather than CLIENT, which this named until the client
    // became mandatory on the form — and CLIENT rather than ASSIGNEE before
    // that, for the same reason each time. `valid` now carries both a client
    // and an assignee, so the project's copy of either rule has nothing left to
    // catch here, and an issue raised by the form's own rule would be the one
    // measured instead of the project's. The contact is the field `valid` still
    // leaves empty.
    expect(Object.keys(parseWith(valid, requiring('SCREEN_NAME', 'FEATURE', 'CLIENT_CONTACT')))).toEqual([
      'screenName',
      'feature',
      'clientContactId',
    ])
  })

  it('still refuses an unassigned draft when the project requires an assignee', () => {
    // The one path the form's own rule leaves open, and the reason the ASSIGNEE
    // row stays in `MANDATORY_FIELD_PATHS` now that Save & Assign covers the
    // other three actions. A project's rules are never waived by `saveAsDraft`.
    expect(parseWith(draftable, requiring('ASSIGNEE'), 'draft')).toEqual({
      assigneeId: 'This project requires an assignee on every ticket',
    })
  })

  it('never requires a planned close date on the form', () => {
    // Blank there does not mean absent: it means "compute it from the SLA
    // policy", and the server does. The control is read-only for four of the six
    // roles, so a rule here would refuse a form those roles cannot fix — the
    // server measures that code against the resolved date instead.
    expect(parseWith(valid, requiring('PLANNED_CLOSE_DATE'))).toEqual({})
  })
})
