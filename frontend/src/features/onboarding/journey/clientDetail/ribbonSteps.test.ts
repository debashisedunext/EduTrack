import { describe, expect, it } from 'vitest'

import type { ObJourneyStepView, UserRef } from '@/api/generated/model'

import type { JourneyStep } from '../ribbon/types'
import { focusStepId, ribbonStatus, stepDotLabel, toRibbonStep, toRibbonSteps } from './ribbonSteps'

/**
 * C-110 · the one translation between `ObJourneyStepView` and the ribbon's own
 * vocabulary. Everything the ribbon draws wrongly on OB-05 would be wrong here
 * first, which is why this file is longer than the adapter.
 */
function step(overrides: Partial<ObJourneyStepView> = {}): ObJourneyStepView {
  return {
    id: 1,
    journeyId: 1,
    sequence: 1,
    name: 'Kickoff & Requirement Sign-off',
    status: 'PENDING',
    tatDays: 3,
    ...overrides,
  } as ObJourneyStepView
}

const RAVI: UserRef = { id: 3, displayName: 'Ravi Kulkarni' }
const resolve = (id: number) => (id === RAVI.id ? RAVI : undefined)

describe('ribbonStatus', () => {
  it.each([
    ['PENDING', 'PENDING'],
    ['IN_PROGRESS', 'CURRENT'],
    ['BLOCKED', 'BLOCKED'],
    ['WAITING_ON_CLIENT', 'WAITING'],
    ['DONE', 'DONE'],
    ['SKIPPED', 'SKIPPED'],
  ] as const)('maps the server %s to the ribbon %s', (wire, ribbon) => {
    expect(ribbonStatus(wire)).toBe(ribbon)
  })

  /**
   * **A skipped step must never read as done.** C-107 shipped the skip
   * transition and `ObJourneyStepStatus` carries `SKIPPED`; folding it into
   * `DONE` would have OB-05 tell a reader that a waived service was delivered,
   * on the screen sign-off is decided from. This is the assertion that stops
   * anyone doing it as a "simplification".
   */
  it('never reports a skipped step as done', () => {
    expect(ribbonStatus('SKIPPED')).not.toBe('DONE')
  })

  /**
   * A status the ribbon has never heard of draws as PENDING — the least
   * claim any tile can make. Not a crash and not "In progress": a new server
   * state arriving before the frontend knows about it should under-report,
   * never over-report.
   */
  it('falls back to PENDING for a status it does not know', () => {
    expect(ribbonStatus('SOMETHING_NEW')).toBe('PENDING')
    expect(ribbonStatus(undefined)).toBe('PENDING')
  })
})

describe('toRibbonStep', () => {
  it('resolves an owner id to the name the tile prints', () => {
    const converted = toRibbonStep(step({ ownerUserId: 3 }), [], resolve)
    expect(converted.owner).toEqual({ displayName: 'Ravi Kulkarni' })
    expect(converted.ownerRole).toBeNull()
  })

  /**
   * An unresolved owner (C-103's null) and a user missing from the directory
   * page are different facts. The second reading as the first is how a screen
   * ends up under-reporting who is on the hook for a late step.
   */
  it('does not report an owner it could not look up as unassigned', () => {
    const converted = toRibbonStep(step({ ownerUserId: 99 }), [], resolve)
    expect(converted.owner).toBeNull()
    expect(converted.ownerRole).toBe('Assigned')
  })

  it('reports a genuinely unowned step as unowned', () => {
    const converted = toRibbonStep(step({ ownerUserId: null }), [], resolve)
    expect(converted.owner).toBeNull()
    expect(converted.ownerRole).toBeNull()
  })

  /**
   * `dependsOnStepId` is a row id; the ribbon's `↳ N` badge prints a sequence
   * number. Getting this wrong prints "depends on step 42" under a journey
   * with five services, which reads as a bug in the data rather than in the
   * badge.
   */
  it('translates a dependency row id into the sequence number the badge prints', () => {
    const first = step({ id: 41, sequence: 1 })
    const second = step({ id: 42, sequence: 2, dependsOnStepId: 41 })
    const [, converted] = toRibbonSteps([first, second])
    expect(converted.dependsOnSeqNo).toBe(1)
  })

  it('leaves a parallel step with no dependency at all', () => {
    const converted = toRibbonStep(step({ dependsOnStepId: null }), [], resolve)
    expect(converted.dependsOnSeqNo).toBeNull()
  })

  /**
   * A dependency pointing outside the set is not sequence 0 and not step 1.
   * Null renders the parallel badge, which is the honest answer when the
   * journey we were given cannot say what the step waits for.
   */
  it('reports an unresolvable dependency as none rather than guessing', () => {
    const converted = toRibbonStep(step({ dependsOnStepId: 999 }), [], resolve)
    expect(converted.dependsOnSeqNo).toBeNull()
  })

  describe('the on-time / early / delayed marker', () => {
    it('calls a step that finished after its due date delayed', () => {
      const converted = toRibbonStep(
        step({ status: 'DONE', dueAt: '2026-08-20T12:00:00Z', finishedAt: '2026-08-25T09:00:00Z' }),
        [],
      )
      expect(converted.closed).toBe('late')
    })

    it('calls a step that finished before its due date early', () => {
      const converted = toRibbonStep(
        step({ status: 'DONE', dueAt: '2026-08-20T12:00:00Z', finishedAt: '2026-08-18T09:00:00Z' }),
        [],
      )
      expect(converted.closed).toBe('early')
    })

    /**
     * No marker, which the ribbon's own `JourneyStepClosed` contract reads as
     * "closed on time" — the safe default for a step that closed with nothing
     * to say it was late. Inventing "early" from a missing `dueAt` would
     * flatter every journey that predates C-105.
     */
    it('makes no claim when either timestamp is missing', () => {
      expect(toRibbonStep(step({ status: 'DONE', finishedAt: '2026-08-18T09:00:00Z' }), []).closed).toBeNull()
      expect(toRibbonStep(step({ status: 'DONE', dueAt: '2026-08-20T12:00:00Z' }), []).closed).toBeNull()
    })
  })

  /**
   * `getObJourney` carries `rag` per step and no elapsed figure at all, so a
   * percentage here would have to be invented. It stays null and the ribbon
   * draws the breach from `rag` — the reason `JourneyStep.rag` exists.
   */
  it('carries the server RAG through and invents no TAT percentage', () => {
    const converted = toRibbonStep(step({ status: 'IN_PROGRESS', rag: 'RED' }), [], resolve)
    expect(converted.rag).toBe('RED')
    expect(converted.tatPercent).toBeNull()
  })

  it('shows the hold reason on a blocked step and the waiver on a skipped one', () => {
    expect(toRibbonStep(step({ status: 'BLOCKED', blockedNote: 'Awaiting sign-off' }), []).note)
      .toBe('Awaiting sign-off')
    expect(toRibbonStep(step({ status: 'SKIPPED', skipReason: 'Client already migrated' }), []).note)
      .toBe('Client already migrated')
  })
})

describe('focusStepId', () => {
  const tile = (id: string, seqNo: number, status: JourneyStep['status']): JourneyStep =>
    ({ id, seqNo, name: `Service ${seqNo}`, status, tatDays: 3, tatPercent: null })

  it('prefers a step that is actually in progress', () => {
    expect(
      focusStepId([tile('1', 1, 'DONE'), tile('2', 2, 'BLOCKED'), tile('3', 3, 'CURRENT')]),
    ).toBe('3')
  })

  /**
   * Northwind's seeded ERP journey, and the case that made this function
   * exist: two done, one blocked, one waiting, one pending, and **nothing in
   * progress**. A panel that looked only for `CURRENT` would open empty on
   * exactly the journey somebody opened the page to ask about.
   */
  it('falls back to the blocked or waiting step when nothing is running', () => {
    expect(
      focusStepId([
        tile('1', 1, 'DONE'),
        tile('2', 2, 'DONE'),
        tile('3', 3, 'BLOCKED'),
        tile('4', 4, 'WAITING'),
        tile('5', 5, 'PENDING'),
      ]),
    ).toBe('3')
  })

  /** A journey past the gate but not started yet opens on its next service. */
  it('falls back to the next pending step on a journey nobody has begun', () => {
    expect(focusStepId([tile('8', 1, 'PENDING'), tile('9', 2, 'PENDING')])).toBe('8')
  })

  /** A finished journey opens on its last service rather than on nothing. */
  it('falls back to the last step on a journey that is complete', () => {
    expect(focusStepId([tile('1', 1, 'DONE'), tile('2', 2, 'SKIPPED'), tile('3', 3, 'DONE')])).toBe('3')
  })

  it('has nothing to focus on an empty journey', () => {
    expect(focusStepId([])).toBeNull()
  })
})

describe('stepDotLabel', () => {
  /**
   * Colour is never the only signal — CLAUDE.md's WCAG line. Nine dots in
   * three colours is exactly the case, so every dot names its service, its
   * state and its colour in words.
   */
  it('names the service, its state and its colour', () => {
    expect(stepDotLabel({ id: 3, sequence: 3, name: 'Data Migration', status: 'BLOCKED', rag: 'RED' }))
      .toBe('3. Data Migration — Blocked · red')
  })

  it('says nothing about health where there is none to report', () => {
    expect(stepDotLabel({ id: 5, sequence: 5, name: 'Go-live Readiness', status: 'PENDING' }))
      .toBe('5. Go-live Readiness — Pending')
  })
})
