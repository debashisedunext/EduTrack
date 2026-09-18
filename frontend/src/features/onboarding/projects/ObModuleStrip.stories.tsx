import type { Meta, StoryObj } from '@storybook/react-vite'

import type { ObProjectStage } from '@/api/generated/model/obProjectStage'

import { ObModuleStrip } from './ObModuleStrip'
import type { TreeService, TreeStage } from './projectTree'
import type { ProjectTask } from './useProjectTasks'
import type { ObViewerScope } from './viewerScope'

const meta: Meta<typeof ObModuleStrip> = {
  title: 'Onboarding/ObModuleStrip',
  component: ObModuleStrip,
  tags: ['autodocs'],
  parameters: {
    docs: {
      description: {
        component:
          'The header row of a Module Service’s accordion on the project page: the service, who is on it, a dot per Step, the three ' +
          'counts, the percentage, the Steps done, its TAT, its start and expected end dates, and its delivery status. A name opens ' +
          'that person’s own figures — see `ObImplementorPopover`. It was three rows until the popover replaced the ' +
          '`By implementor` accordion.\n\n' +
          'The status chip is a four-step ramp — On time, At risk, Delayed, Stuck — folded from the service’s **whole** task list, ' +
          'never the reader’s share of it. The four stories at the end of this page are that ramp, top to bottom.',
      },
    },
  },
}
export default meta

type Story = StoryObj<typeof ObModuleStrip>

const PRIYA = 41
const ARJUN = 42
const ZOYA = 43
const DEV = 44
const RAVI = 45

const NAMES: Record<number, string> = {
  [PRIYA]: 'Priya Nair',
  [ARJUN]: 'Arjun Mehta',
  [ZOYA]: 'Zoya Khan',
  [DEV]: 'Dev Prasad',
  [RAVI]: 'Ravi Iyer',
}
const nameOf = (id: number) => NAMES[id] ?? null

const ADMIN: ObViewerScope = { kind: 'ALL', meId: PRIYA, showsBreakdown: true }
const SALES: ObViewerScope = { kind: 'ALL', meId: PRIYA, showsBreakdown: false }
const MINE: ObViewerScope = {
  kind: 'IMPLEMENTOR',
  meId: PRIYA,
  showsBreakdown: false,
}

function task(over: Partial<ProjectTask> & Pick<ProjectTask, 'id'>): ProjectTask {
  return {
    journeyId: 500,
    serviceName: 'SIS',
    sequence: 1,
    name: `Task ${over.id}`,
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

const stage: ObProjectStage = {
  stageKey: 1,
  name: 'Configuration',
  sequence: 1,
  taskCount: 6,
  tasksOutstanding: 4,
  isComplete: false,
  isCurrent: true,
}

function node(tasks: ProjectTask[], stagesComplete = 2, stageCount = 7): TreeService {
  const settled = tasks.filter((t) => t.status === 'DONE' || t.status === 'SKIPPED').length
  const step: TreeStage = {
    stage,
    tasks,
    settled,
    hasMine: tasks.some((t) => t.ownerUserId === PRIYA),
  }
  return {
    service: {
      journeyId: 500,
      templateId: 9,
      serviceName: 'SIS — Student Information System',
      gateStatus: 'LOCKED',
      isComplete: false,
      stages: [stage],
    },
    stages: [step],
    tasks,
    allTasks: tasks,
    taskCount: tasks.length,
    settled,
    stagesComplete,
    stageCount,
    hiddenStageCount: 0,
    totalTatDays: 5,
    hasMine: step.hasMine,
  }
}

const TEAM = [
  task({ id: 1, status: 'DONE' }),
  task({ id: 2, status: 'DONE' }),
  task({ id: 3, status: 'IN_PROGRESS', ownerUserId: ARJUN }),
  task({ id: 4, ownerUserId: ARJUN }),
  task({ id: 5, status: 'WAITING_ON_CLIENT', ownerUserId: ZOYA }),
  task({ id: 6, ownerUserId: ZOYA }),
]

const base = { nameOf, isOpen: false, onToggle: () => {}, panelId: 'ob-service-500' }

/** Three implementors, every name a control. */
export const Admin: Story = { args: { ...base, node: node(TEAM), scope: ADMIN } }

/** Open, so the row wears the timeline's own tint. */
export const AdminOpen: Story = { args: { ...base, node: node(TEAM), scope: ADMIN, isOpen: true } }

/** One implementor: the strip's figures already are theirs, so the name is plain text. */
export const SoleImplementor: Story = {
  args: { ...base, node: node(TEAM.filter((t) => t.ownerUserId === PRIYA)), scope: ADMIN },
}

/** Filtered to the reader: their name, their denominator. */
export const Implementor: Story = {
  args: { ...base, node: node(TEAM.filter((t) => t.ownerUserId === PRIYA)), scope: MINE },
}

/** Nothing of theirs on this service — an answer, not an empty row. */
export const NothingOfYours: Story = { args: { ...base, node: node([]), scope: MINE } }

/** Six owners including an unassigned task: four names, then the overflow. */
export const Crowded: Story = {
  args: {
    ...base,
    scope: ADMIN,
    node: node([
      ...TEAM,
      task({ id: 7, ownerUserId: DEV }),
      task({ id: 8, ownerUserId: RAVI, status: 'DONE' }),
      task({ id: 9, ownerUserId: null }),
    ]),
  },
}

/** Sales: the names, but not the workload split behind them. */
export const Sales: Story = { args: { ...base, node: node(TEAM), scope: SALES } }

/* ── the delivery ramp ────────────────────────────────────────────────────
   Four stories rather than one with a control, because the point of a ramp is
   that its steps are read against each other — a docs page showing all four in
   a column is what makes "is orange different enough from amber" answerable. */

const DAY = 86_400_000
const overdueBy = (days: number) => new Date(Date.now() - days * DAY).toISOString()
const dueIn = (days: number) => new Date(Date.now() + days * DAY).toISOString()

/** The service's own start, shared by all four so only the status differs. */
const STARTED = overdueBy(12)

function scheduled(over: Partial<ProjectTask> & Pick<ProjectTask, 'id'>): ProjectTask {
  return task({ startedAt: STARTED, ...over })
}

/** Nothing overdue, nothing near its budget. */
export const OnTime: Story = {
  args: {
    ...base,
    scope: ADMIN,
    node: node([
      scheduled({ id: 1, status: 'DONE', dueAt: overdueBy(4) }),
      scheduled({ id: 2, status: 'IN_PROGRESS', dueAt: dueIn(3), tatUsedPercent: 30, ownerUserId: ARJUN }),
      scheduled({ id: 3, dueAt: dueIn(9) }),
    ]),
  },
}

/** Still inside every due date, but one task has burned most of its TAT. */
export const AtRisk: Story = {
  args: {
    ...base,
    scope: ADMIN,
    node: node([
      scheduled({ id: 1, status: 'DONE', dueAt: overdueBy(4) }),
      scheduled({ id: 2, status: 'IN_PROGRESS', dueAt: dueIn(1), tatUsedPercent: 92, ownerUserId: ARJUN }),
      scheduled({ id: 3, dueAt: dueIn(9) }),
    ]),
  },
}

/** Past a due date, inside the week that separates late from stuck. */
export const Delayed: Story = {
  args: {
    ...base,
    scope: ADMIN,
    node: node([
      scheduled({ id: 1, status: 'DONE', dueAt: overdueBy(8) }),
      scheduled({ id: 2, status: 'IN_PROGRESS', dueAt: overdueBy(3), ownerUserId: ARJUN }),
      scheduled({ id: 3, dueAt: dueIn(5) }),
    ]),
  },
}

/** More than a week past a due date: late enough that nobody is moving it. */
export const Stuck: Story = {
  args: {
    ...base,
    scope: ADMIN,
    node: node([
      scheduled({ id: 1, status: 'DONE', dueAt: overdueBy(20) }),
      scheduled({ id: 2, status: 'BLOCKED', dueAt: overdueBy(11), ownerUserId: ARJUN }),
      scheduled({ id: 3, dueAt: dueIn(2) }),
    ]),
  },
}

/** A service whose gate has never opened — two dashes rather than two guesses. */
export const NotStarted: Story = {
  args: { ...base, scope: ADMIN, node: node([task({ id: 1 }), task({ id: 2, ownerUserId: ARJUN })]) },
}
