import { describe, expect, it } from 'vitest'

import {
  actionRefusal,
  allowedActions,
  completionGate,
  completionGateSummary,
  mayActOnStep,
  startRefusal,
} from './stepActions'

/**
 * C-111 · OB-06's rules. Each of these mirrors a refusal the server actually
 * makes, and the test names say which — a rule that drifts from
 * `ObJourneyStepLifecycleService` is a panel offering a button that 422s.
 */
describe('allowedActions', () => {
  it.each([
    ['PENDING', ['start']],
    ['IN_PROGRESS', ['complete', 'block', 'waiting']],
    ['BLOCKED', ['resume']],
    ['WAITING_ON_CLIENT', ['resume']],
  ] as const)('offers %s exactly what the service accepts', (status, expected) => {
    expect(allowedActions(status)).toEqual(expected)
  })

  /**
   * There is no reopen transition in the module. Offering one would invent a
   * lifecycle the plan does not have, and the server would refuse it.
   */
  it.each(['DONE', 'SKIPPED'])('offers nothing on a terminal step (%s)', (status) => {
    expect(allowedActions(status)).toEqual([])
  })

  it('offers nothing for a status it does not recognise', () => {
    expect(allowedActions('SOMETHING_NEW')).toEqual([])
    expect(allowedActions(undefined)).toEqual([])
  })
})

describe('mayActOnStep', () => {
  const step = { ownerUserId: 3, backupOwnerUserId: 7 }

  it('admits the owner', () => {
    expect(mayActOnStep(step, 3)).toBe(true)
  })

  /**
   * `ObStepOwnership.mayAct` gives the backup owner the same standing as the
   * owner — a backup exists to cover the step, and one that could not act
   * would be a column with no effect.
   */
  it('admits the backup owner', () => {
    expect(mayActOnStep(step, 7)).toBe(true)
  })

  it('refuses everybody else', () => {
    expect(mayActOnStep(step, 99)).toBe(false)
  })

  /**
   * **No Manager/Admin override.** `NotStepOwnerException`'s javadoc is
   * explicit that the plan's "override steps with logged reason" is absent
   * until the module has a role vocabulary wired to an authority, and that
   * faking it with a ticketing role is not on. A UI that showed the buttons
   * anyway would promise something the server does not implement.
   */
  it('has no override — an unrelated user is refused regardless of seniority', () => {
    expect(mayActOnStep({ ownerUserId: 3, backupOwnerUserId: null }, 1)).toBe(false)
  })

  /**
   * False while `useGetMe()` resolves. A null caller is not a permitted
   * caller, and rendering optimistically means buttons that appear and then
   * vanish under the cursor.
   */
  it('refuses while the caller is still unknown', () => {
    expect(mayActOnStep(step, null)).toBe(false)
    expect(mayActOnStep(step, undefined)).toBe(false)
  })

  it('refuses on an unowned step rather than admitting everyone', () => {
    expect(mayActOnStep({ ownerUserId: null, backupOwnerUserId: null }, 3)).toBe(false)
  })
})

describe('startRefusal', () => {
  /**
   * The server answers both holds with one code, `journey-not-open`. They are
   * not one fact: clearing prerequisites is work somebody can do today,
   * waiting for a sibling journey is not, and a reader told the wrong one goes
   * and does the wrong thing.
   */
  it('distinguishes a locked gate from a sibling hold', () => {
    expect(startRefusal('GATE_LOCKED')).toMatch(/prerequisites/i)
    expect(startRefusal('HELD_BY_SIBLING')).toMatch(/another of the client’s services/i)
  })

  it('does not refuse a start on an open, unheld journey', () => {
    expect(startRefusal(null)).toBeNull()
  })
})

describe('completionGate', () => {
  const item = (id: number, label: string, isMandatory: boolean, isDone: boolean) =>
    ({ id, stepId: 3, sequence: id, label, isMandatory, isDone })

  it('holds on an unticked mandatory item and names it', () => {
    const gate = completionGate([item(1, 'Staff master reconciled', true, false)], [])
    expect(gate.isSatisfied).toBe(false)
    expect(gate.unansweredMandatory).toEqual(['Staff master reconciled'])
  })

  /**
   * Optional items do not hold completion — plan §5.8's gate is about the
   * mandatory ones. Treating every item as blocking would make the checklist
   * a chore rather than a gate.
   */
  it('does not hold on an unticked optional item', () => {
    const gate = completionGate([item(1, 'Legacy ledger archived', false, false)], [])
    expect(gate.isSatisfied).toBe(true)
  })

  it('holds on a required document with nothing against it', () => {
    const gate = completionGate([], [{ id: 1, stepId: 3, label: 'Signed SOW', isRequired: true, isSatisfied: false }])
    expect(gate).toMatchObject({ isSatisfied: false, missingRequiredDocs: 1 })
  })

  it('does not hold on an optional document, or a satisfied required one', () => {
    const gate = completionGate([], [
      { id: 1, stepId: 3, label: 'Nice to have', isRequired: false, isSatisfied: false },
      { id: 2, stepId: 3, label: 'Signed SOW', isRequired: true, isSatisfied: true },
    ])
    expect(gate.isSatisfied).toBe(true)
  })

  /**
   * A step with no checklist at all is completable. The gate is a constraint
   * the template imposed, not a default.
   */
  it('is satisfied when the step has no checklist', () => {
    expect(completionGate(undefined, undefined).isSatisfied).toBe(true)
    expect(completionGate([], []).isSatisfied).toBe(true)
  })
})

describe('completionGateSummary', () => {
  /**
   * Named, not counted. "2 items outstanding" sends the reader hunting through
   * a checklist they are already looking at — which is why
   * `ObCompletionGateProblem` carries labels rather than a number too.
   */
  it('names a single outstanding item', () => {
    const summary = completionGateSummary(
      completionGate([{ id: 1, stepId: 3, sequence: 1, label: 'Student master reconciled', isMandatory: true, isDone: false }], []),
    )
    expect(summary).toBe('“Student master reconciled” is not ticked')
  })

  it('names several, and adds the document count beside them', () => {
    const summary = completionGateSummary(
      completionGate(
        [
          { id: 1, stepId: 3, sequence: 1, label: 'A', isMandatory: true, isDone: false },
          { id: 2, stepId: 3, sequence: 2, label: 'B', isMandatory: true, isDone: false },
        ],
        [{ id: 9, stepId: 3, label: 'Signed SOW', isRequired: true, isSatisfied: false }],
      ),
    )
    expect(summary).toContain('2 mandatory items are not ticked: “A”, “B”')
    expect(summary).toContain('1 required document has nothing against it')
  })

  it('says nothing when the gate is clear', () => {
    expect(completionGateSummary(completionGate([], []))).toBeNull()
  })
})

describe('actionRefusal', () => {
  const clear = completionGate([], [])
  const held = completionGate([{ id: 1, stepId: 3, sequence: 1, label: 'A', isMandatory: true, isDone: false }], [])

  it('refuses complete while the gate holds, and says which item', () => {
    const refusal = actionRefusal('complete', { hold: null, gate: held })
    expect(refusal).toContain('“A” is not ticked')
  })

  it('allows complete once the gate is clear', () => {
    expect(actionRefusal('complete', { hold: null, gate: clear })).toBeNull()
  })

  it('refuses start on a locked journey', () => {
    expect(actionRefusal('start', { hold: 'GATE_LOCKED', gate: clear })).toMatch(/prerequisites/i)
  })

  /**
   * The task-list gate is about *finishing*, never about starting or pausing.
   * A step with outstanding items can still be blocked or handed to the
   * client — those are reports about reality, not claims that the work is done.
   */
  it.each(['block', 'waiting', 'resume'] as const)('never refuses %s over the task list', (action) => {
    expect(actionRefusal(action, { hold: null, gate: held })).toBeNull()
  })
})
