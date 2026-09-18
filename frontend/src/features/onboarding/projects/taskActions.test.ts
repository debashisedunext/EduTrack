import { describe, expect, it } from 'vitest'

import type { ObJourneyStepDoc } from '@/api/generated/model/obJourneyStepDoc'

import { ApiError } from '@/api/http'
import { refusalMessage, taskActions, type TaskActionContext, type TaskActionKey } from './taskActions'
import type { ProjectTask } from './useProjectTasks'

/**
 * Which buttons exist, and which of them a given reader may press.
 *
 * <p>Every rule the bar draws is here rather than interleaved with its markup,
 * so this is where they are asserted — seven actions across four statuses and
 * three kinds of reader is a table, and a table is cheap to check and expensive
 * to get right by reading JSX.
 */

const OWNER = 41

function doc(over: Partial<ObJourneyStepDoc> = {}): ObJourneyStepDoc {
  return { id: 1, stepId: 9, label: 'Validation report', isRequired: true, isSatisfied: false, ...over }
}

function task(over: Partial<ProjectTask> = {}): ProjectTask {
  return {
    id: 9,
    journeyId: 500,
    serviceName: 'SIS',
    sequence: 1,
    name: 'Student Dataport',
    status: 'IN_PROGRESS',
    ownerUserId: OWNER,
    ownerIsInherited: false,
    backupOwnerUserId: null,
    tatDays: 1,
    requiresSignoff: false,
    dueAt: null,
    tatUsedPercent: null,
    stageKey: 1,
    stageName: 'Data Migration',
    items: [],
    docs: [],
    ...over,
  }
}

function ctx(over: Partial<TaskActionContext> = {}): TaskActionContext {
  return {
    task: task(),
    yours: true,
    isModerator: false,
    blockers: [],
    ownerName: 'Vikram Mehta',
    busy: false,
    ...over,
  }
}

const keys = (over: Partial<TaskActionContext> = {}): TaskActionKey[] =>
  taskActions(ctx(over)).map((a) => a.key)

const find = (key: TaskActionKey, over: Partial<TaskActionContext> = {}) =>
  taskActions(ctx(over)).find((a) => a.key === key)

describe('which actions exist', () => {
  it('offers the owner every action on a running task', () => {
    expect(keys()).toEqual([
      'complete',
      'waitingOnClient',
      'waitingOnService',
      'block',
      'reassign',
      'communication',
    ])
  })

  /** Complete on an unstarted task is a transition the server refuses. */
  it('offers Start instead of Complete before a task has begun', () => {
    const k = keys({ task: task({ status: 'PENDING' }) })

    expect(k).toContain('start')
    expect(k).not.toContain('complete')
  })

  it.each(['WAITING_ON_CLIENT', 'BLOCKED'] as const)(
    'offers Resume instead of Waiting on client while %s',
    (status) => {
      const k = keys({ task: task({ status }) })

      expect(k).toContain('resume')
      expect(k).not.toContain('waitingOnClient')
    },
  )

  /**
   * The upload is per required document rather than a general file drop, so a
   * task with none has nothing to attach against.
   */
  it('leaves Attach off a task whose template requires no documents', () => {
    expect(keys()).not.toContain('attach')
    expect(keys({ task: task({ docs: [doc()] }) })).toContain('attach')
  })

  it('counts satisfied over required on the Attach badge', () => {
    const docs = [doc({ id: 1 }), doc({ id: 2, isSatisfied: true })]

    expect(find('attach', { task: task({ docs }) })?.badge).toBe('1/2')
  })

  /** Four move the task and its clock; three do not, and the bar divides them. */
  it('splits state changes from everything else', () => {
    const actions = taskActions(ctx({ task: task({ docs: [doc()] }) }))

    expect(actions.filter((a) => a.group === 'state').map((a) => a.key)).toEqual([
      'complete',
      'waitingOnClient',
      'waitingOnService',
      'block',
    ])
    expect(actions.filter((a) => a.group === 'other').map((a) => a.key)).toEqual([
      'reassign',
      'attach',
      'communication',
    ])
  })
})

describe('what a reader may press', () => {
  it('disables every write on somebody else’s task, and names them', () => {
    const actions = taskActions(ctx({ yours: false, task: task({ docs: [doc()] }) }))

    const writes = actions.filter((a) => a.group === 'state')
    writes.forEach((a) => {
      expect(a.enabled).toBe(false)
      expect(a.reason).toBe('Only Vikram Mehta can do this')
    })
  })

  /**
   * Recording that a call happened is a log entry, not a task mutation. It is
   * the one thing a non-owner can still do here.
   */
  it('leaves Communication open to everybody, on any task', () => {
    expect(find('communication', { yours: false })?.enabled).toBe(true)
    expect(find('communication', { task: task({ status: 'DONE' }) })?.enabled).toBe(true)
  })

  it.each(['DONE', 'SKIPPED'] as const)('closes every transition on a %s task', (status) => {
    const actions = taskActions(ctx({ task: task({ status }) }))

    actions
      .filter((a) => a.group === 'state')
      .forEach((a) => {
        expect(a.enabled).toBe(false)
        expect(a.reason).toBe('This task is already closed')
      })
  })

  it('holds Complete while the check list still owes something, and says what', () => {
    const complete = find('complete', { blockers: ['2 unanswered', '1 required document'] })

    expect(complete?.enabled).toBe(false)
    expect(complete?.reason).toBe('Outstanding: 2 unanswered, 1 required document')
  })

  /** Not the owner's: the server gates the PATCH on `requireModerator`. */
  it('offers Reassign to a moderator and nobody else', () => {
    expect(find('reassign')?.enabled).toBe(false)
    expect(find('reassign')?.reason).toMatch(/OB Manager or OB Admin/)
    expect(find('reassign', { isModerator: true })?.enabled).toBe(true)
  })

  /**
   * Reassigning is what somebody does about *another person's* task, so it
   * survives the task not being theirs — unlike every transition.
   */
  it('offers a moderator Reassign on a task that is not theirs', () => {
    expect(find('reassign', { isModerator: true, yours: false })?.enabled).toBe(true)
  })

  it('holds everything that writes while a mutation is in flight', () => {
    const actions = taskActions(ctx({ busy: true, isModerator: true }))

    expect(actions.filter((a) => a.enabled).map((a) => a.key)).toEqual(['communication'])
  })
})

/**
 * The server's status rule, mirrored — `requireStatus` in
 * `ObJourneyStepLifecycleService` accepts complete, block and waiting-on-client
 * from IN_PROGRESS and nothing else. Every case here is a button that used to
 * be offered where the only possible outcome was a refusal.
 */
describe('what the current status allows', () => {
  const byKey = (actions: ReturnType<typeof taskActions>, key: TaskActionKey) =>
    actions.find((a) => a.key === key)

  it('holds Waiting on client, Waiting on service and Block on a task not yet started', () => {
    const actions = taskActions(ctx({ task: task({ status: 'PENDING' }) }))

    for (const key of ['waitingOnClient', 'waitingOnService', 'block'] as const) {
      expect(byKey(actions, key)?.enabled, key).toBe(false)
      expect(byKey(actions, key)?.reason, key).toBe('Start the task first')
    }
    expect(byKey(actions, 'start')?.enabled).toBe(true)
  })

  /** The product rule: while waiting on the client, only Resume moves the task. */
  it('while waiting on the client, leaves only Resume live among the state actions', () => {
    const actions = taskActions(ctx({ task: task({ status: 'WAITING_ON_CLIENT' }) }))
    const state = actions.filter((a) => a.group === 'state')

    expect(state.filter((a) => a.enabled).map((a) => a.key)).toEqual(['resume'])
    for (const a of state.filter((s) => s.key !== 'resume')) {
      expect(a.reason, a.key).toBe('Waiting on the client — resume the task first')
    }
  })

  it('does the same while blocked, and says so', () => {
    const actions = taskActions(ctx({ task: task({ status: 'BLOCKED' }) }))
    const state = actions.filter((a) => a.group === 'state')

    expect(state.filter((a) => a.enabled).map((a) => a.key)).toEqual(['resume'])
    expect(byKey(actions, 'complete')?.reason).toBe('Blocked — resume the task first')
  })

  /** Logging the call is what somebody does while waiting; that button stays. */
  it('keeps Communication open while waiting on the client', () => {
    const actions = taskActions(ctx({ task: task({ status: 'WAITING_ON_CLIENT' }) }))

    expect(byKey(actions, 'communication')?.enabled).toBe(true)
  })

  /** Status outranks the check list: a paused task cannot be completed whatever it owes. */
  it('names the pause, not the outstanding items, on a paused task with an unfinished check list', () => {
    const actions = taskActions(
      ctx({ task: task({ status: 'WAITING_ON_CLIENT' }), blockers: ['1 item unanswered'] }),
    )

    expect(byKey(actions, 'complete')?.reason).toBe('Waiting on the client — resume the task first')
  })

  it('offers everything again once the task is running', () => {
    const actions = taskActions(ctx({ task: task({ status: 'IN_PROGRESS' }) }))

    for (const key of ['complete', 'waitingOnClient', 'waitingOnService', 'block'] as const) {
      expect(byKey(actions, key)?.enabled, key).toBe(true)
    }
  })
})

/**
 * The line under the bar describes the *latest* ask. A refusal that outlived
 * the state it described is what a reader saw: "cannot mark waiting-on-client
 * from status PENDING" still on screen after Start had succeeded and the task
 * read In progress.
 */
describe('refusalMessage', () => {
  const refused = (submittedAt: number, detail = 'refused') => ({
    isError: true,
    submittedAt,
    error: new ApiError(
      422,
      { type: 'about:blank', title: 'x', status: 422, detail },
      new Response(null, { status: 422 }),
    ),
  })
  const succeeded = (submittedAt: number) => ({ isError: false, submittedAt, error: null })
  const untouched = { isError: false, submittedAt: 0, error: null }

  it('says nothing on a bar nobody has pressed', () => {
    expect(refusalMessage([untouched, untouched])).toBeNull()
  })

  it('shows the refusal when the latest ask was refused', () => {
    expect(refusalMessage([untouched, refused(10, 'cannot do that')])).toContain('cannot do that')
  })

  it('clears the refusal once a later ask succeeds, whichever mutation it was', () => {
    expect(refusalMessage([succeeded(20), refused(10)])).toBeNull()
  })

  it('shows the newest refusal, not the first mutation that ever failed', () => {
    expect(refusalMessage([refused(10, 'old'), refused(30, 'new')])).toContain('new')
  })
})

describe('which actions ask before they act', () => {
  /**
   * The two whose endpoints take no body just act. Everything that needs input
   * opens a dialog for it.
   */
  it('acts immediately only where the endpoint takes nothing', () => {
    const actions = taskActions(ctx({ task: task({ docs: [doc()] }) }))
    const immediate = actions.filter((a) => !a.opensDialog).map((a) => a.key)

    expect(immediate).toEqual(['complete', 'waitingOnClient'])
  })

  it('sends Start and Resume without a dialog too', () => {
    expect(find('start', { task: task({ status: 'PENDING' }) })?.opensDialog).toBe(false)
    expect(find('resume', { task: task({ status: 'BLOCKED' }) })?.opensDialog).toBe(false)
  })
})
