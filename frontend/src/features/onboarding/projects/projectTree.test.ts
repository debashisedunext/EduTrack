import { describe, expect, it } from 'vitest'

import type { ObProjectModuleService } from '@/api/generated/model/obProjectModuleService'
import type { ObProjectStage } from '@/api/generated/model/obProjectStage'

import {
  buildProjectTree,
  defaultOpenServices,
  findTaskPath,
  projectTally,
  stageNodeKey,
} from './projectTree'
import type { ProjectTask } from './useProjectTasks'
import type { ObViewerScope } from './viewerScope'

/**
 * The fold behind the project tree.
 *
 * <p>The corpus is the one on the seeded DAV project: two module services
 * through the same seven-stage master, where Reports carries work for SIS and
 * none for Attendance. That asymmetry is the whole reason the per-service
 * roll-up exists, so it is what these assert on.
 */

const ME = 41
const SIS = 500
const ATTENDANCE = 501

/**
 * The two readings of one project. `ALL` is what an admin, a manager, sales, a
 * viewer and a session whose `moduleRoles` has not arrived all get; `MINE` is
 * the implementor's.
 */
const ALL: ObViewerScope = { kind: 'ALL', meId: ME, showsBreakdown: true }
const MINE: ObViewerScope = {
  kind: 'IMPLEMENTOR',
  meId: ME,
  showsBreakdown: false,
}

const CONFIG = 1
const MIGRATION = 2
const REPORTS = 3
const TRAINING = 4

function stage(stageKey: number, name: string, sequence: number, over: Partial<ObProjectStage> = {}): ObProjectStage {
  return {
    stageKey,
    name,
    sequence,
    taskCount: 0,
    tasksOutstanding: 0,
    isComplete: false,
    isCurrent: false,
    ...over,
  }
}

/** Every stage the master publishes — each service's template carries all four. */
const MASTER = [
  stage(CONFIG, 'Configuration', 1),
  stage(MIGRATION, 'Data Migration', 2),
  stage(REPORTS, 'Reports', 3),
  stage(TRAINING, 'Training', 4),
]

function service(
  journeyId: number,
  serviceName: string,
  stages: ObProjectStage[] = MASTER,
): ObProjectModuleService {
  return {
    journeyId,
    templateId: 9,
    serviceName,
    gateStatus: 'OPEN',
    isComplete: false,
    stages,
  }
}

function task(over: Partial<ProjectTask> & Pick<ProjectTask, 'id' | 'journeyId'>): ProjectTask {
  return {
    serviceName: 'SIS',
    sequence: 1,
    name: `Task ${over.id}`,
    status: 'PENDING',
    ownerUserId: null,
    ownerIsInherited: false,
    backupOwnerUserId: null,
    tatDays: 1,
    requiresSignoff: false,
    dueAt: null,
    tatUsedPercent: null,
    stageKey: CONFIG,
    stageName: 'Configuration',
    items: [],
    docs: [],
    ...over,
  }
}

const SERVICES = [service(SIS, 'SIS'), service(ATTENDANCE, 'Student Attendance')]

const TASKS: ProjectTask[] = [
  task({ id: 1, journeyId: SIS, name: 'Admission No Scheme', status: 'DONE', ownerUserId: ME }),
  task({ id: 2, journeyId: SIS, stageKey: REPORTS, stageName: 'Reports', name: 'Report card template' }),
  task({ id: 3, journeyId: ATTENDANCE, name: 'Week off', serviceName: 'Student Attendance' }),
  task({ id: 4, journeyId: ATTENDANCE, name: 'School Calendar', serviceName: 'Student Attendance' }),
]

describe('buildProjectTree', () => {
  it('gives every service its own branch, in the order the server sent them', () => {
    const tree = buildProjectTree(SERVICES, TASKS, ALL)

    expect(tree.map((t) => t.service.serviceName)).toEqual(['SIS', 'Student Attendance'])
  })

  it('files each task under its own service, never the other one', () => {
    const tree = buildProjectTree(SERVICES, TASKS, ALL)

    const sisConfig = tree[0].stages.find((s) => s.stage.stageKey === CONFIG)
    const attendanceConfig = tree[1].stages.find((s) => s.stage.stageKey === CONFIG)

    expect(sisConfig?.tasks.map((t) => t.name)).toEqual(['Admission No Scheme'])
    expect(attendanceConfig?.tasks.map((t) => t.name)).toEqual(['Week off', 'School Calendar'])
  })

  /**
   * The failure the whole per-service roll-up exists to fix. Folded, Reports is
   * one stop reading 0/1 and there is nowhere to say that Attendance never
   * scheduled it.
   */
  it('separates a stage one service uses from the same stage another never scheduled', () => {
    const tree = buildProjectTree(SERVICES, TASKS, ALL)

    const sisReports = tree[0].stages.find((s) => s.stage.stageKey === REPORTS)
    const attendanceReports = tree[1].stages.find((s) => s.stage.stageKey === REPORTS)

    expect(sisReports?.tasks).toHaveLength(1)
    // Attendance scheduled nothing into Reports, so it has no Reports Step.
    expect(attendanceReports).toBeUndefined()
  })

  /**
   * The default master publishes seven Steps and a service schedules work into
   * two. Drawn, the other five are dead segments saying something about the
   * master rather than about this project.
   */
  it('drops a stage the template published and nothing was scheduled into', () => {
    const tree = buildProjectTree(SERVICES, TASKS, ALL)

    // Training is empty for both services, so it is on neither.
    expect(tree[0].stages.map((s) => s.stage.name)).not.toContain('Training')
    expect(tree[1].stages.map((s) => s.stage.name)).not.toContain('Training')
  })

  it('counts settled work per service, counting a waived task as settled', () => {
    const tasks = [
      ...TASKS,
      task({ id: 5, journeyId: SIS, stageKey: MIGRATION, stageName: 'Data Migration', status: 'SKIPPED' }),
    ]
    const tree = buildProjectTree(SERVICES, tasks, ALL)

    expect(tree[0].taskCount).toBe(3)
    expect(tree[0].settled).toBe(2)
    expect(tree[1].settled).toBe(0)
  })

  /** An empty stage has nothing outstanding either, which is not the same as done. */
  it('counts only the stages that carry work', () => {
    const tree = buildProjectTree(SERVICES, TASKS, ALL)

    // Configuration and Reports. Migration and Training carry nothing.
    expect(tree[0].stageCount).toBe(2)
    // Configuration, whose one task is DONE. Not Training, not Migration.
    expect(tree[0].stagesComplete).toBe(1)
    expect(tree[1].stagesComplete).toBe(0)
  })

  it('marks the branches holding the signed-in user’s own work', () => {
    const tree = buildProjectTree(SERVICES, TASKS, ALL)

    expect(tree[0].hasMine).toBe(true)
    expect(tree[1].hasMine).toBe(false)
    expect(tree[0].stages.find((s) => s.stage.stageKey === CONFIG)?.hasMine).toBe(true)
  })

  /**
   * Both sides fold `implementation_stage_id` identically, so this should not
   * happen. It is asserted because the alternative to surfacing it is a task
   * that exists, is assigned to somebody, and appears nowhere on the page.
   */
  it('surfaces a task whose stage key matches nothing the service published', () => {
    const stray = task({ id: 9, journeyId: SIS, stageKey: 88, stageName: 'Retired stage' })
    const tree = buildProjectTree(SERVICES, [...TASKS, stray], ALL)

    const orphan = tree[0].stages.find((s) => s.stage.stageKey === 88)
    expect(orphan?.tasks.map((t) => t.id)).toEqual([9])
    // Last, after even the Ungrouped bucket's 9999.
    expect(tree[0].stages.at(-1)?.stage.stageKey).toBe(88)
    expect(tree[0].taskCount).toBe(3)
  })

  it('keeps a service with no tasks, with no Steps under it', () => {
    const tree = buildProjectTree(SERVICES, [], ALL)

    expect(tree).toHaveLength(2)
    expect(tree[0].taskCount).toBe(0)
    // Every Step it publishes is empty, so the ribbon has nothing to draw.
    expect(tree[0].stages).toHaveLength(0)
  })
})

/**
 * Each service's own TAT: Σ of the pinned per-task figures, whoever owns them.
 * The check a reader will actually make is that the strips add up to the
 * header's Total TAT, and that only holds if this is not scoped.
 */
describe('a service’s total TAT', () => {
  const budgeted = [
    task({ id: 1, journeyId: SIS, tatDays: 2, ownerUserId: ME }),
    task({ id: 2, journeyId: SIS, tatDays: 3, ownerUserId: 99, stageKey: REPORTS, stageName: 'Reports' }),
    task({ id: 3, journeyId: ATTENDANCE, tatDays: 4, ownerUserId: 99, serviceName: 'Student Attendance' }),
  ]

  it('sums the pinned TAT of every task in the service', () => {
    const tree = buildProjectTree(SERVICES, budgeted, ALL)

    expect(tree[0].totalTatDays).toBe(5)
    expect(tree[1].totalTatDays).toBe(4)
  })

  /** The reader owns 2 of SIS's 5 days; the strip still says 5. */
  it('does not shrink to the reader’s share in the filtered view', () => {
    const tree = buildProjectTree(SERVICES, budgeted, MINE)

    expect(tree[0].taskCount).toBe(1)
    expect(tree[0].totalTatDays).toBe(5)
  })

  it('adds up across services to the project’s own figure', () => {
    const tree = buildProjectTree(SERVICES, budgeted, ALL)
    const projectTotal = budgeted.reduce((sum, t) => sum + t.tatDays, 0)

    expect(tree.reduce((sum, s) => sum + s.totalTatDays, 0)).toBe(projectTotal)
  })

  it('is zero for a service with nothing scheduled', () => {
    expect(buildProjectTree(SERVICES, [], ALL)[0].totalTatDays).toBe(0)
  })
})

describe('defaultOpenServices', () => {
  it('opens nothing when the project has more than one service', () => {
    expect(defaultOpenServices(buildProjectTree(SERVICES, TASKS, ALL)).size).toBe(0)
  })

  /** One service means the closed row is a click with nothing to choose. */
  it('opens the only service when there is exactly one', () => {
    const tree = buildProjectTree([service(SIS, 'SIS')], TASKS, ALL)

    expect([...defaultOpenServices(tree)]).toEqual([SIS])
  })

  it('opens nothing for a project boarded through no service at all', () => {
    expect(defaultOpenServices(buildProjectTree([], [], ALL)).size).toBe(0)
  })
})

describe('stageNodeKey', () => {
  /**
   * The key is folded onto the implementation stage, so both services carry the
   * same Configuration. Keyed on that alone, opening one would open the other.
   */
  it('separates the same stage in two different services', () => {
    expect(stageNodeKey(SIS, CONFIG)).not.toBe(stageNodeKey(ATTENDANCE, CONFIG))
  })
})

describe('findTaskPath', () => {
  it('names the service and stage a task sits in', () => {
    const tree = buildProjectTree(SERVICES, TASKS, ALL)

    expect(findTaskPath(tree, 4)).toEqual({ journeyId: ATTENDANCE, stageKey: CONFIG })
    expect(findTaskPath(tree, 2)).toEqual({ journeyId: SIS, stageKey: REPORTS })
  })

  it('answers null for a task this project does not have', () => {
    expect(findTaskPath(buildProjectTree(SERVICES, TASKS, ALL), 404)).toBeNull()
  })
})

/**
 * The implementor's reading.
 *
 * <p>The corpus gives SIS one task owned by ME (Configuration, DONE) and one
 * owned by nobody (Reports), and gives Attendance two owned by nobody — so a
 * filtered fold has to keep exactly one task, in exactly one Step, and account
 * for the Steps it dropped.
 */
describe('buildProjectTree, scoped to one implementor', () => {
  it('keeps only tasks this person owns or backs up', () => {
    const tree = buildProjectTree(SERVICES, TASKS, MINE)

    expect(tree[0].taskCount).toBe(1)
    expect(tree[0].tasks.map((t) => t.name)).toEqual(['Admission No Scheme'])
    expect(tree[1].taskCount).toBe(0)
  })

  /** The backup owner can act on a task, so it is theirs to see. */
  it('counts a task this person is the backup owner of', () => {
    const backup = task({ id: 6, journeyId: ATTENDANCE, backupOwnerUserId: ME })
    const tree = buildProjectTree(SERVICES, [...TASKS, backup], MINE)

    expect(tree[1].tasks.map((t) => t.id)).toEqual([6])
  })

  it('drops the Steps that hold none of their tasks, and counts them', () => {
    const tree = buildProjectTree(SERVICES, TASKS, MINE)

    expect(tree[0].stages.map((s) => s.stage.name)).toEqual(['Configuration'])
    // Reports carries work and none of it is theirs. Migration and Training
    // carry none at all and are nobody's to be offered.
    expect(tree[0].hiddenStageCount).toBe(1)
    expect(tree[0].stageCount).toBe(1)
  })

  /** Nothing of theirs at all is an answer, not an absent service. */
  it('keeps a service they have no work in, with every Step accounted for', () => {
    const tree = buildProjectTree(SERVICES, TASKS, MINE)

    expect(tree).toHaveLength(2)
    expect(tree[1].stages).toHaveLength(0)
    // Configuration alone — the service's only scheduled Step.
    expect(tree[1].hiddenStageCount).toBe(1)
  })

  /** Nothing is withheld from an unfiltered reader, so nothing is counted. */
  it('reports nothing hidden in the unfiltered reading', () => {
    expect(buildProjectTree(SERVICES, TASKS, ALL)[0].hiddenStageCount).toBe(0)
    expect(buildProjectTree(SERVICES, TASKS, ALL)[0].stages).toHaveLength(2)
  })
})

describe('projectTally', () => {
  it('sums the Steps that carry work and the tasks settled in them', () => {
    const tally = projectTally(buildProjectTree(SERVICES, TASKS, ALL))

    // SIS draws Configuration and Reports; Attendance draws Configuration.
    // The same three segments the two ribbons carry.
    expect(tally).toEqual({
      stepsComplete: 1,
      stepsTotal: 3,
      tasksDone: 1,
      tasksTotal: 4,
      modulesComplete: 0,
      modulesTotal: 2,
      completionPercent: 25,
    })
  })

  /**
   * The regression this exists to prevent: a filtered page under an unfiltered
   * header, reading "Tasks done 1/4" above strips accounting for one task.
   */
  it('counts only this implementor’s work when the page is filtered', () => {
    const tally = projectTally(buildProjectTree(SERVICES, TASKS, MINE))

    expect(tally).toMatchObject({
      stepsComplete: 1,
      stepsTotal: 1,
      tasksDone: 1,
      tasksTotal: 1,
      completionPercent: 100,
    })
  })

  /**
   * The header's headline figure, and the one the strips have to agree with:
   * one of four tasks settled is 25%, the same arithmetic `stripStats` does a
   * level down. Partial work is not credited half a task by either of them.
   */
  it('reads completion as settled tasks over all of them, rounded', () => {
    const started: ProjectTask[] = [
      ...TASKS.slice(0, 3),
      { ...TASKS[3], status: 'IN_PROGRESS' as const },
    ]

    expect(projectTally(buildProjectTree(SERVICES, started, ALL)).completionPercent).toBe(25)
  })

  /** A project with no instantiated task is 0%, never 100% by vacuous truth. */
  it('reads a project with no task at all as nought per cent', () => {
    const tally = projectTally(buildProjectTree(SERVICES, [], ALL))

    expect(tally.completionPercent).toBe(0)
    expect(tally.tasksTotal).toBe(0)
  })

  /**
   * Modules are the service's own `isComplete`, not this reader's share of it
   * — an implementor who owns one task in a finished service still reads the
   * project as having finished a module, because it has.
   */
  it('counts finished modules unscoped, over every module service', () => {
    const services = [{ ...SERVICES[0], isComplete: true }, SERVICES[1]]

    expect(projectTally(buildProjectTree(services, TASKS, ALL))).toMatchObject({
      modulesComplete: 1,
      modulesTotal: 2,
    })
    expect(projectTally(buildProjectTree(services, TASKS, MINE))).toMatchObject({
      modulesComplete: 1,
      modulesTotal: 2,
    })
  })
})
