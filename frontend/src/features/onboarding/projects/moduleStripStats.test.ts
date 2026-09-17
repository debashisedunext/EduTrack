import { describe, expect, it } from 'vitest'

import type { ObJourneyStepItem } from '@/api/generated/model/obJourneyStepItem'
import type { ObProjectStage } from '@/api/generated/model/obProjectStage'

import {
  AT_RISK_TAT_PERCENT,
  STUCK_AFTER_DAYS,
  defaultStepKey,
  deliveryHealth,
  ownerSummary,
  reviewCounts,
  segmentState,
  serviceDates,
  statsByImplementor,
  stripStats,
  taskProgress,
} from './moduleStripStats'
import type { TreeService, TreeStage } from './projectTree'
import type { ProjectTask } from './useProjectTasks'

/**
 * The strip's three buckets and the ribbon's six states.
 *
 * <p>The buckets matter most: they are what a reader adds up, and the rule for
 * "partial" is a judgement rather than a field, so it is asserted from both
 * directions — the status that makes a task partial with an untouched check
 * list, and the check list that makes one partial with an untouched status.
 */

const PRIYA = 41
const ARJUN = 42

function item(over: Partial<ObJourneyStepItem> = {}): ObJourneyStepItem {
  return {
    id: 1,
    stepId: 1,
    sequence: 1,
    label: 'Extract received',
    isMandatory: true,
    isDone: false,
    answer: null,
    remark: null,
    ...over,
  }
}

function task(over: Partial<ProjectTask> = {}): ProjectTask {
  return {
    id: 1,
    journeyId: 500,
    serviceName: 'SIS',
    sequence: 1,
    name: 'Validate student master extract',
    status: 'PENDING',
    ownerUserId: PRIYA,
    ownerIsInherited: false,
    backupOwnerUserId: null,
    tatDays: 2,
    requiresSignoff: false,
    dueAt: null,
    tatUsedPercent: null,
    stageKey: 1,
    stageName: 'Configuration',
    items: [],
    docs: [],
    ...over,
  }
}

function stage(over: Partial<ObProjectStage> = {}): ObProjectStage {
  return {
    stageKey: 1,
    name: 'Configuration',
    sequence: 1,
    taskCount: 0,
    tasksOutstanding: 0,
    isComplete: false,
    isCurrent: false,
    ...over,
  }
}

function node(tasks: ProjectTask[], over: Partial<ObProjectStage> = {}): TreeStage {
  return {
    stage: stage(over),
    tasks,
    settled: tasks.filter((t) => t.status === 'DONE' || t.status === 'SKIPPED').length,
    hasMine: false,
  }
}

const NAMES: Record<number, string> = { [PRIYA]: 'Priya Nair', [ARJUN]: 'Arjun Mehta' }
const nameOf = (id: number) => NAMES[id] ?? null

describe('taskProgress', () => {
  it('counts a waived task as completed, the same as the server does', () => {
    expect(taskProgress(task({ status: 'DONE' }))).toBe('COMPLETED')
    expect(taskProgress(task({ status: 'SKIPPED' }))).toBe('COMPLETED')
  })

  /**
   * Both are states a task reaches by being worked on. Reporting either as "not
   * started" puts a task waiting on a client since the 11th in the same bucket
   * as one nobody has opened.
   */
  it.each(['IN_PROGRESS', 'BLOCKED', 'WAITING_ON_CLIENT'] as const)(
    'counts %s as partial even with nothing ticked',
    (status) => {
      expect(taskProgress(task({ status }))).toBe('PARTIAL')
    },
  )

  it('counts an untouched task with an untouched check list as pending', () => {
    expect(taskProgress(task({ items: [item(), item({ id: 2 })] }))).toBe('PENDING')
  })

  /** Somebody has been ticking through it all morning without moving it. */
  it('counts a ticked check list as partial whatever the status says', () => {
    const ticked = task({ items: [item({ isDone: true, answer: true }), item({ id: 2 })] })

    expect(ticked.status).toBe('PENDING')
    expect(taskProgress(ticked)).toBe('PARTIAL')
  })

  /**
   * `isDone` is the server's `answer IS NOT NULL`. An item answered "False, and
   * here is why" is progress and satisfies the completion gate exactly as a
   * True does.
   */
  it('counts an item answered False as progress', () => {
    const refused = task({ items: [item({ isDone: true, answer: false, remark: '14 duplicates' })] })

    expect(taskProgress(refused)).toBe('PARTIAL')
  })
})

describe('stripStats', () => {
  const TASKS = [
    task({ id: 1, status: 'DONE' }),
    task({ id: 2, status: 'SKIPPED' }),
    task({ id: 3, status: 'IN_PROGRESS' }),
    task({ id: 4, items: [item({ isDone: true, answer: true })] }),
    task({ id: 5 }),
    task({ id: 6 }),
  ]

  /** A reader who adds the three up and misses the total has found a bug. */
  it('puts every task in exactly one bucket', () => {
    const stats = stripStats(TASKS)

    expect(stats).toMatchObject({ pending: 2, partial: 2, completed: 2, total: 6 })
    expect(stats.pending + stats.partial + stats.completed).toBe(stats.total)
  })

  /**
   * Partial is never credited as half. A percentage that quietly counts work
   * nobody has finished is how a project reads as 60% done on the day it slips.
   */
  it('counts only completed work in the percentage', () => {
    expect(stripStats(TASKS).percent).toBe(33)
  })

  it('reports nothing rather than dividing by zero on an empty service', () => {
    expect(stripStats([])).toEqual({ pending: 0, partial: 0, completed: 0, total: 0, percent: 0 })
  })
})

describe('reviewCounts', () => {
  /**
   * The three figures a Step header prints. Deliberately not a partition —
   * see the doc on `ReviewCounts` — so each is asserted on its own rule rather
   * than by adding them up.
   */
  it('counts what the review approved, what it sent back, and what is moving', () => {
    const counts = reviewCounts([
      task({ id: 1, status: 'DONE' }),
      task({ id: 2, status: 'IN_PROGRESS' }),
      task({ id: 3, status: 'PENDING_REVIEW' }),
      task({ id: 4 }),
    ])

    expect(counts).toEqual({ verified: 1, rejected: 0, inProgress: 2 })
  })

  /**
   * The one rule the status cannot express. A rejection puts the task back on
   * `IN_PROGRESS`, so reading the status alone would leave the red figure at
   * zero for ever and the bucket would count nothing.
   */
  it('reads a rejection off the rows, not off the status', () => {
    const returned = task({
      id: 5,
      status: 'IN_PROGRESS',
      items: [item({ rowState: 'REJECTED', remark: 'Roll numbers are duplicated' })],
    })

    expect(reviewCounts([returned])).toEqual({ verified: 0, rejected: 1, inProgress: 0 })
  })

  /**
   * Waived is settled without ever being read. Counting it as verified would
   * report a manager's approval that nobody gave, on the one figure somebody
   * might act on.
   */
  it('counts a waived task as neither verified nor in progress', () => {
    expect(reviewCounts([task({ id: 6, status: 'SKIPPED' })])).toEqual({
      verified: 0,
      rejected: 0,
      inProgress: 0,
    })
  })

  it('counts a task nobody has started in none of the three', () => {
    expect(reviewCounts([task({ id: 7 })])).toEqual({ verified: 0, rejected: 0, inProgress: 0 })
  })
})

describe('statsByImplementor', () => {
  const TASKS = [
    task({ id: 1, ownerUserId: PRIYA, status: 'DONE' }),
    task({ id: 2, ownerUserId: PRIYA, status: 'IN_PROGRESS' }),
    task({ id: 3, ownerUserId: ARJUN }),
    task({ id: 4, ownerUserId: null }),
  ]

  it('gives each owner their own four figures', () => {
    const rows = statsByImplementor(TASKS, nameOf)

    expect(rows.map((r) => r.name)).toEqual(['Arjun Mehta', 'Priya Nair', 'Unassigned'])
    expect(rows[1]).toMatchObject({ pending: 0, partial: 1, completed: 1, total: 2, percent: 50 })
  })

  /**
   * Grouped on the accountable owner alone. Counting a backup owner's tasks
   * too would file one task under two people and leave the rows summing to more
   * than the strip above them.
   */
  it('does not double-count a task somebody only backs up', () => {
    const rows = statsByImplementor(
      [task({ id: 1, ownerUserId: PRIYA, backupOwnerUserId: ARJUN })],
      nameOf,
    )

    expect(rows).toHaveLength(1)
    expect(rows[0].name).toBe('Priya Nair')
  })

  it('names an owner the directory has not heard of rather than filing them as unassigned', () => {
    const rows = statsByImplementor([task({ ownerUserId: 99 })], nameOf)

    expect(rows[0]).toMatchObject({ userId: 99, name: 'User 99' })
  })

  it('rows add up to the strip above them', () => {
    const rows = statsByImplementor(TASKS, nameOf)
    const total = rows.reduce((sum, r) => sum + r.total, 0)

    expect(total).toBe(stripStats(TASKS).total)
  })
})

describe('segmentState', () => {
  /*
    No `empty` case. `buildProjectTree` drops a Step nothing was scheduled into
    before anybody sees it, so a node with no tasks is not an input this
    function can receive — and a test asserting what it does with one would be
    pinning a branch the product no longer has.
  */

  it('is complete only when every task is settled, waived ones included', () => {
    expect(segmentState(node([task({ status: 'DONE' }), task({ id: 2, status: 'SKIPPED' })]))).toBe(
      'complete',
    )
    expect(segmentState(node([task({ status: 'DONE' }), task({ id: 2 })]))).not.toBe('complete')
  })

  /** A blocked task needs somebody, and outranks the Step merely running. */
  it('reports blocked ahead of running', () => {
    expect(
      segmentState(node([task({ status: 'IN_PROGRESS' }), task({ id: 2, status: 'BLOCKED' })])),
    ).toBe('blocked')
  })

  it('reports running from this reader’s own tasks', () => {
    expect(segmentState(node([task({ status: 'IN_PROGRESS' })]))).toBe('current')
  })

  /**
   * The roll-up's `isCurrent` is consulted second. In a filtered view a Step
   * running for somebody else still reads as running, which is true and is what
   * a reader comparing their Step against the project needs.
   */
  it('falls back to the roll-up’s own current Step', () => {
    expect(segmentState(node([task()], { isCurrent: true }))).toBe('current')
  })

  it('reports waiting where the client holds it and nothing else is moving', () => {
    expect(segmentState(node([task({ status: 'WAITING_ON_CLIENT' })]))).toBe('waiting')
  })

  it('reports not started where nothing has been touched', () => {
    expect(segmentState(node([task(), task({ id: 2 })]))).toBe('pending')
  })

})

describe('ownerSummary', () => {
  it('names one owner', () => {
    expect(ownerSummary(node([task({ ownerUserId: PRIYA })]), nameOf)).toBe('Priya Nair')
  })

  it('names the first and counts the rest where a Step is shared', () => {
    const shared = node([task({ ownerUserId: PRIYA }), task({ id: 2, ownerUserId: ARJUN })])

    expect(ownerSummary(shared, nameOf)).toBe('Priya Nair +1')
  })

  it('answers null where nobody is responsible', () => {
    expect(ownerSummary(node([task({ ownerUserId: null })]), nameOf)).toBeNull()
  })
})

describe('defaultStepKey', () => {
  function service(stages: TreeStage[]): TreeService {
    return {
      service: {
        journeyId: 500,
        templateId: 9,
        serviceName: 'SIS',
        gateStatus: 'OPEN',
        isComplete: false,
        stages: [],
      },
      stages,
      tasks: stages.flatMap((s) => s.tasks),
      allTasks: stages.flatMap((s) => s.tasks),
      taskCount: stages.reduce((n, s) => n + s.tasks.length, 0),
      settled: stages.reduce((n, s) => n + s.settled, 0),
      stagesComplete: 0,
      stageCount: stages.length,
      hiddenStageCount: 0,
      totalTatDays: 0,
      hasMine: false,
    }
  }

  it('opens on the running Step', () => {
    const tree = service([
      node([task({ status: 'DONE' })], { stageKey: 1 }),
      node([task({ id: 2, status: 'IN_PROGRESS' })], { stageKey: 2 }),
      node([task({ id: 3 })], { stageKey: 3 }),
    ])

    expect(defaultStepKey(tree)).toBe(2)
  })

  it('opens on the first Step with work left when none is running', () => {
    const tree = service([
      node([task({ status: 'DONE' })], { stageKey: 1 }),
      node([task({ id: 2 })], { stageKey: 2 }),
    ])

    expect(defaultStepKey(tree)).toBe(2)
  })

  /** A finished service opens on something rather than on nothing. */
  it('opens on the first Step when every one of them is done', () => {
    const tree = service([
      node([task({ id: 1, status: 'DONE' })], { stageKey: 1 }),
      node([task({ id: 2, status: 'DONE' })], { stageKey: 2 }),
    ])

    expect(defaultStepKey(tree)).toBe(1)
  })

  it('answers null for a service with no Step to select', () => {
    expect(defaultStepKey(service([]))).toBeNull()
  })
})


/**
 * The service's two dates, folded from the tasks the server sent.
 *
 * <p>Nothing here computes a date — it picks one — so what is worth asserting
 * is *which* one, and that the two questions are answered independently: a
 * service can have started without any task carrying a due date, and the other
 * way round.
 */
describe('serviceDates', () => {
  it('starts when the earliest task started, not when the first was created', () => {
    const dates = serviceDates([
      task({ id: 1, startedAt: '2026-09-09T11:00:00Z' }),
      task({ id: 2, startedAt: '2026-09-02T08:30:00Z' }),
      task({ id: 3, startedAt: '2026-09-14T09:00:00Z' }),
    ])

    expect(dates.startedAt).toBe('2026-09-02T08:30:00Z')
  })

  /**
   * The *last* due date, because that is when the service is meant to be
   * finished. The earliest is when its first task is due, which is a different
   * question and the one a delay is measured from task by task.
   */
  it('is expected to end on the latest task due date', () => {
    const dates = serviceDates([
      task({ id: 1, dueAt: '2026-09-18T17:00:00Z' }),
      task({ id: 2, dueAt: '2026-09-30T17:00:00Z' }),
      task({ id: 3, dueAt: '2026-09-24T17:00:00Z' }),
    ])

    expect(dates.expectedEndAt).toBe('2026-09-30T17:00:00Z')
  })

  it('answers null for a service nothing has started', () => {
    expect(serviceDates([task({ dueAt: '2026-09-18T17:00:00Z' })]).startedAt).toBeNull()
  })

  it('answers null for an expected end no task carries a date for', () => {
    expect(serviceDates([task({ startedAt: '2026-09-02T08:30:00Z' })]).expectedEndAt).toBeNull()
  })

  it('answers null for both on a service with no tasks at all', () => {
    expect(serviceDates([])).toEqual({ startedAt: null, expectedEndAt: null })
  })

  /**
   * Compared as instants rather than as strings. `+05:30` sorts after `Z`
   * lexicographically and before it chronologically, which is the one case a
   * string comparison silently gets wrong.
   */
  it('orders mixed offsets by the instant they name, not by their spelling', () => {
    const dates = serviceDates([
      task({ id: 1, startedAt: '2026-09-09T02:00:00Z' }),
      // 01:00Z — earlier, despite sorting later as text.
      task({ id: 2, startedAt: '2026-09-09T06:30:00+05:30' }),
    ])

    expect(dates.startedAt).toBe('2026-09-09T06:30:00+05:30')
  })
})

/**
 * On time → At risk → Delayed → Stuck.
 *
 * <p>Every branch is a comparison against the clock, so `now` is passed in
 * rather than mocked: the boundaries are the whole point of the fold, and a
 * test that cannot name the exact minute cannot assert them.
 */
describe('deliveryHealth', () => {
  /** The fixed "now" every case below is positioned against. */
  const NOW = Date.parse('2026-09-16T12:00:00Z')
  const DAY = 86_400_000

  const daysAgo = (days: number) => new Date(NOW - days * DAY).toISOString()
  const daysAhead = (days: number) => new Date(NOW + days * DAY).toISOString()

  it('is on time when nothing is overdue and nothing is near its budget', () => {
    const health = deliveryHealth(
      [
        task({ id: 1, dueAt: daysAhead(4), tatUsedPercent: 40 }),
        task({ id: 2, status: 'DONE', dueAt: daysAgo(2) }),
      ],
      NOW,
    )

    expect(health).toEqual({ status: 'ON_TIME', lateByDays: 0, worstTask: null })
  })

  it('is at risk once a task has used most of its TAT, before it is late', () => {
    const health = deliveryHealth(
      [
        task({
          id: 1,
          name: 'Migrate the student master',
          dueAt: daysAhead(1),
          tatUsedPercent: AT_RISK_TAT_PERCENT,
        }),
        task({ id: 2, dueAt: daysAhead(6), tatUsedPercent: 10 }),
      ],
      NOW,
    )

    expect(health.status).toBe('AT_RISK')
    expect(health.lateByDays).toBe(0)
    expect(health.worstTask).toBe('Migrate the student master')
  })

  it('is delayed the moment a task is past its own due date', () => {
    const health = deliveryHealth([task({ name: 'Import timetables', dueAt: daysAgo(3) })], NOW)

    expect(health.status).toBe('DELAYED')
    expect(health.lateByDays).toBe(3)
    expect(health.worstTask).toBe('Import timetables')
  })

  /**
   * Ceiling, matching `ObDelayedProjectsService`: a task due an hour ago has
   * slipped, and reporting zero until a whole day has elapsed would call it on
   * time on the one reading a person would argue with.
   */
  it('rounds a part-day slip up rather than reporting it as on time', () => {
    const health = deliveryHealth([task({ dueAt: new Date(NOW - 3_600_000).toISOString() })], NOW)

    expect(health.status).toBe('DELAYED')
    expect(health.lateByDays).toBe(1)
  })

  it('stays delayed at exactly the stuck threshold', () => {
    const health = deliveryHealth([task({ dueAt: daysAgo(STUCK_AFTER_DAYS) })], NOW)

    expect(health.status).toBe('DELAYED')
    expect(health.lateByDays).toBe(STUCK_AFTER_DAYS)
  })

  it('is stuck once a task is more than a week past its due date', () => {
    const health = deliveryHealth(
      [task({ name: 'Sign off the data mapping', dueAt: daysAgo(STUCK_AFTER_DAYS + 1) })],
      NOW,
    )

    expect(health.status).toBe('STUCK')
    expect(health.lateByDays).toBe(STUCK_AFTER_DAYS + 1)
    expect(health.worstTask).toBe('Sign off the data mapping')
  })

  /** Worst-wins, the same fold the RAG colour makes upward — plan §5.9. */
  it('is decided by the worst task, not by the first or the last', () => {
    const health = deliveryHealth(
      [
        task({ id: 1, dueAt: daysAgo(2) }),
        task({ id: 2, name: 'Configure fee heads', dueAt: daysAgo(11) }),
        task({ id: 3, dueAt: daysAhead(5) }),
      ],
      NOW,
    )

    expect(health.status).toBe('STUCK')
    expect(health.worstTask).toBe('Configure fee heads')
  })

  /**
   * A finished task cannot still slip. Without this rule a service would wear
   * **Stuck** for ever on the strength of one task closed a fortnight ago, and
   * the chip would stop tracking anything a reader could act on.
   */
  it('judges neither a done nor a skipped task, however late it closed', () => {
    const health = deliveryHealth(
      [
        task({ id: 1, status: 'DONE', dueAt: daysAgo(30) }),
        task({ id: 2, status: 'SKIPPED', dueAt: daysAgo(20) }),
      ],
      NOW,
    )

    expect(health.status).toBe('ON_TIME')
  })

  /** An overdue task outvotes a merely at-risk one — the ramp only climbs. */
  it('reports delayed rather than at risk when both are true of one service', () => {
    const health = deliveryHealth(
      [
        task({ id: 1, dueAt: daysAhead(2), tatUsedPercent: 98 }),
        task({ id: 2, name: 'Load the fee structure', dueAt: daysAgo(1) }),
      ],
      NOW,
    )

    expect(health.status).toBe('DELAYED')
    expect(health.worstTask).toBe('Load the fee structure')
  })

  it('is on time for a service with no task and nothing to judge', () => {
    expect(deliveryHealth([], NOW).status).toBe('ON_TIME')
  })

  /** A task with no due date is not overdue; it is unscheduled. */
  it('does not read a missing due date as a breach', () => {
    expect(deliveryHealth([task({ dueAt: null })], NOW).status).toBe('ON_TIME')
  })
})
