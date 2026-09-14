import * as React from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'

import { useGetMe } from '@/api/generated/auth/auth'
import { useGetObClientPrereqs } from '@/api/generated/onboarding/onboarding'
import { useListUsers } from '@/api/generated/users/users'
import type { ObClientPrereqs } from '@/api/generated/model/obClientPrereqs'
import type { ObProjectDetail } from '@/api/generated/model/obProjectDetail'
import type { ObProjectStage } from '@/api/generated/model/obProjectStage'

import { Chip } from '@/components/ui/chip'
import { Skeleton } from '@/components/ui/skeleton'

import { EscalationBanner } from '@/features/onboarding/journey/clientDetail/EscalationBanner'
import { PrereqAccordion } from '@/features/onboarding/journey/clientDetail/PrereqAccordion'
import { SignoffPanel } from '@/features/onboarding/journey/clientDetail/SignoffPanel'
import { useOpenEscalations } from '@/features/onboarding/journey/clientDetail/useOpenEscalations'

import { ObProjectStageBody } from './ObProjectStageBody'
import { ObStageRibbon } from './ObStageRibbon'
import { PROJECT_KEY, useObProject } from './projectQueries'
import { delayCell, formatDate, stageProgress } from './projectRow'
import { defaultStageKey } from './stageRibbon'
import { isMine, myStageKeys, tasksOfStage, useProjectTasks } from './useProjectTasks'

/**
 * One project — `/onboarding/projects/:obProjectId`, and the screen the
 * onboarding module is read from.
 *
 * <h2>Five levels, in one order</h2>
 *
 * <pre>
 *   Project → Prerequisites → Implementation stage → Task → Sub-task
 * </pre>
 *
 * <p>The project leads because it is what the page is about; the client is who
 * it is for, which is a different question and one line of caption. The stage
 * ribbon is the third level and it is the navigation — selecting a stop decides
 * which tasks the body underneath shows.
 *
 * <h2>It no longer nests `ObClientProductPage`</h2>
 *
 * <p>It used to, and that nesting was the repetition this page was rebuilt to
 * remove: the inner page drew its own header — the client's name in a back
 * link, the product name as a second heading, the client's name a third time in
 * a caption — underneath a header that had already said all three. It also drew
 * a per-service ribbon of <em>tasks</em>, one level below where the stage ribbon
 * now sits, so the two disagreed about what a ribbon is.
 *
 * <p>The standalone route `/onboarding/clients/:id/products/:productId` still
 * renders that page unchanged. It is where the step panel lives, and therefore
 * the one place a sub-task can actually be ticked.
 *
 * <h2>What stayed from it, and where</h2>
 *
 * <p>Escalations and go-live sign-off are kept **below** the hierarchy. Neither
 * is a task, and dropping them would remove working function; putting them
 * above would push the work a reader came for off the fold.
 *
 * <p>The client-level **communications panel is deliberately not here**. Every
 * task now carries its own append-only timeline inside its panel, and the
 * stitched view repeated all of it a second time at the bottom of the page —
 * the same two entries, under a different heading, a screen further down. It
 * remains on the client page, which is where "everything said to this client,
 * across every service" is the question being asked.
 *
 * <h2>Everyone navigates; only the owner changes anything</h2>
 *
 * <p>Every stage opens for every reader — an admin or a sales person chasing a
 * project needs to see all of it. Permission sits on the <em>controls</em>
 * instead: the task list, the transitions and the upload are the owner's, and
 * for anybody else they render disabled with the owner named. That mirrors the
 * server, which refuses a non-owner's write with `NotStepOwnerException`, so a
 * disabled control is the screen declining to offer something whose only
 * outcome is a refusal.
 *
 * <p>The one exception is <b>Reassign</b>, which is a moderator action rather
 * than the owner's — `ObJourneyStepLifecycleService.update` is gated by
 * `requireModerator` precisely so an owner cannot reassign a task off
 * themselves. The page cannot tell an OB Admin from Sales yet, because `/me`
 * carries no onboarding module role, so that control is shown to nobody as
 * enabled until it does.
 */
export function ObProjectDetailPage() {
  const params = useParams<{ obProjectId: string }>()
  const obProjectId = Number(params.obProjectId)
  const { data, isPending, isError } = useObProject(
    Number.isFinite(obProjectId) ? obProjectId : null,
  )

  /*
    The gate, read off the project's client. Enabled only once the project read
    has produced one — the client id is not in the URL here, it is a field on
    the project, so this is necessarily the second request rather than a
    parallel one.
  */
  const obClientId = data?.project.client.id
  const prereqs = useGetObClientPrereqs(obClientId ?? 0, {
    query: { enabled: obClientId != null },
  })
  const gate = prereqs.data?.data

  /*
    Open while the gate is locked, closed once it clears — and an explicit
    click wins over both. Written from what is on screen rather than negating
    the stored value, on `ObClientDetailPage`'s own note: the first click
    happens while the state is still derived, so flipping a stale `false` would
    open the checklist the reader is trying to close.
  */
  const [prereqsOverride, setPrereqsOverride] = React.useState<boolean | null>(null)
  const prereqsOpen = prereqsOverride ?? gate?.gateStatus === 'LOCKED'

  /*
    Keep the header honest when the gate moves under it.

    `PrereqAccordion` invalidates the checklist and the client document — the
    two reads it was built beside — and knows nothing about this project read.
    So verifying the last mandatory task would start every task below while the
    header above still wore "Prerequisites pending", the one inconsistency a
    reader on this page would actually notice.
  */
  const queryClient = useQueryClient()
  const headerGate = data?.project.gateStatus
  const checklistGate = gate?.gateStatus
  React.useEffect(() => {
    if (headerGate && checklistGate && headerGate !== checklistGate) {
      void queryClient.invalidateQueries({ queryKey: PROJECT_KEY(obProjectId) })
    }
  }, [headerGate, checklistGate, obProjectId, queryClient])

  if (isPending) {
    return (
      <div className="mx-auto flex max-w-[88rem] flex-col gap-4 p-6">
        <Skeleton className="h-32 w-full" />
        <Skeleton className="h-64 w-full" />
      </div>
    )
  }

  if (isError || !data) {
    return (
      <div className="mx-auto max-w-3xl p-6">
        <h1 className="text-xl font-semibold text-content">Project not found</h1>
        <p className="mt-2 text-sm text-content-muted">
          It may have been dropped, or it may belong to a client outside your access.{' '}
          <Link to="/onboarding/projects" className="text-primary hover:underline">
            Back to projects
          </Link>
          .
        </p>
      </div>
    )
  }

  return (
    <ProjectBody
      project={data.project}
      gate={gate}
      prereqsError={prereqs.isError}
      prereqsOpen={prereqsOpen}
      onTogglePrereqs={() => setPrereqsOverride(!prereqsOpen)}
    />
  )
}

/**
 * The page below its two reads, split out so the stage selection can hold state
 * without the guards above it having to run first.
 *
 * <p>Keeping these hooks in the component that returns early on `isPending`
 * would either sit above the guards — including one request per module service,
 * fired while the skeleton is still up — or break the rules of hooks by sitting
 * below them.
 */
function ProjectBody({
  project,
  gate,
  prereqsError,
  prereqsOpen,
  onTogglePrereqs,
}: {
  project: ObProjectDetail
  gate: ObClientPrereqs | undefined
  prereqsError: boolean
  prereqsOpen: boolean
  onTogglePrereqs: () => void
}) {
  const obClientId = project.client.id
  const stages = React.useMemo(() => project.stages ?? [], [project.stages])
  const services = React.useMemo(() => project.moduleServices ?? [], [project.moduleServices])

  const me = useGetMe()
  const meId = me.data?.data?.id
  const users = useListUsers({ isActive: true, limit: 200 })
  const userList = React.useMemo(() => users.data?.data ?? [], [users.data?.data])

  const { tasks, isPending: tasksPending } = useProjectTasks(project)
  const mine = React.useMemo(() => myStageKeys(tasks, meId), [tasks, meId])

  /**
   * Which stage is selected, and therefore which stage's tasks show below.
   *
   * <p>Derived until the reader touches it, and the default is the first stage
   * holding their own work — every other stop is locked, so opening on one
   * would be opening on a dead end. It moves while the page is open (finishing
   * your last task in a stage hands you the next), which is why it is derived
   * rather than stored. Once they choose, their choice wins: that is what
   * `null` means here.
   */
  const [chosenStageKey, setChosenStageKey] = React.useState<number | null>(null)
  const fallbackKey = React.useMemo(() => {
    const ordered = [...stages].sort((a, b) => a.sequence - b.sequence || a.stageKey - b.stageKey)
    return ordered.find((st) => mine.has(st.stageKey))?.stageKey ?? defaultStageKey(stages)
  }, [stages, mine])
  const selectedStageKey = chosenStageKey ?? fallbackKey

  const selectedStage = stages.find((st) => st.stageKey === selectedStageKey)
  const stageTasks = React.useMemo(
    () => tasksOfStage(tasks, selectedStageKey),
    [tasks, selectedStageKey],
  )

  const myOutstanding = React.useMemo(
    () => tasks.filter((t) => isMine(t, meId) && t.status !== 'DONE' && t.status !== 'SKIPPED'),
    [tasks, meId],
  )
  const firstMine = myOutstanding[0]

  const { escalations } = useOpenEscalations(obClientId)

  /*
    Keyed by task so the panel can draw its own banner. The page-level
    `EscalationBanner` below still lists them all — one is "this task is
    escalated", the other is "this project has escalations", and a reader
    scrolling to a stage they have not opened needs the second.
  */
  const escalationsByTask = React.useMemo(
    () =>
      new Map(
        escalations.map((e) => [
          e.stepId,
          {
            id: e.id,
            raisedBy: e.raisedByContact?.name ?? 'Client',
            raisedAt: e.raisedAt,
            note: e.comment,
          },
        ]),
      ),
    [escalations],
  )

  return (
    <div className="flex flex-col">
      <ProjectHeader project={project} taskTally={taskTotals(stages)} />

      <div className="mx-auto flex w-full max-w-[88rem] flex-col gap-2.5 px-6 py-4">
        {/* The gate and your own work share one row: each is a sentence and a
            control, and neither earns a full band of its own. */}
        <div className="flex flex-wrap gap-2.5">
          {gate && (
            <div
              className={
                gate.gateStatus === 'LOCKED'
                  ? 'flex min-w-[16rem] flex-1 items-center gap-2.5 rounded-control border border-warning bg-level-high-soft px-3.5 py-2.5 text-caption text-warning-text'
                  : 'flex min-w-[16rem] flex-1 items-center gap-2.5 rounded-control border border-success bg-level-low-soft px-3.5 py-2.5 text-caption text-success-text'
              }
            >
              <span aria-hidden="true" className="font-bold">
                {gate.gateStatus === 'LOCKED' ? '!' : '✓'}
              </span>
              <span>
                <strong className="font-semibold">
                  {gate.gateStatus === 'LOCKED' ? 'Prerequisites pending' : 'Prerequisites cleared'}
                </strong>
                {` — ${gate.mandatoryVerified} of ${gate.mandatoryTotal}`}
                {gate.clearedAt ? `, ${shortDay(gate.clearedAt)}` : ''}
              </span>
              <span className="min-w-0 flex-1" aria-hidden="true" />
              <button
                type="button"
                onClick={onTogglePrereqs}
                aria-expanded={prereqsOpen}
                className="rounded-chip border border-current px-2.5 py-0.5 text-[11px] font-semibold focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
              >
                {prereqsOpen ? 'Hide' : 'View'}
              </button>
            </div>
          )}

          <div className="flex min-w-[16rem] flex-1 items-center gap-2.5 rounded-control border border-primary bg-primary-soft px-3.5 py-2.5 text-caption text-primary">
            <span aria-hidden="true">●</span>
            <span>
              {firstMine ? (
                <>
                  <strong className="font-semibold">
                    {myOutstanding.length} task{myOutstanding.length === 1 ? '' : 's'} assigned to you
                  </strong>
                  {` — ${firstMine.name}`}
                  {firstMine.dueAt ? `, due ${shortDay(firstMine.dueAt)}` : ''}
                </>
              ) : (
                <strong className="font-semibold">No tasks assigned to you</strong>
              )}
            </span>
            <span className="min-w-0 flex-1" aria-hidden="true" />
            {firstMine?.stageKey != null && (
              <button
                type="button"
                onClick={() => setChosenStageKey(firstMine.stageKey)}
                className="rounded-chip border border-current px-2.5 py-0.5 text-[11px] font-semibold focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
              >
                Go
              </button>
            )}
          </div>
        </div>

        {/* Rendered only while open: the strip above *is* this accordion's
            collapsed state, so drawing both would say one thing twice. */}
        {gate && prereqsOpen && (
          <PrereqAccordion obClientId={obClientId} prereqs={gate} isOpen onToggle={onTogglePrereqs} />
        )}
        {prereqsError && (
          <div
            className="rounded-card border border-danger bg-danger-soft px-5 py-3 text-sm text-danger-text"
            role="status"
          >
            The prerequisites checklist could not be loaded, so this page cannot say what is holding
            the tasks below. Reload to try again.
          </div>
        )}

        <ObStageRibbon
          stages={stages}
          selectedKey={selectedStageKey}
          onSelect={setChosenStageKey}
          isYours={(stage) => mine.has(stage.stageKey)}
          caption="Every stage opens — only the task owner can change anything"
        />

        <ObProjectStageBody
          stage={selectedStage}
          tasks={stageTasks}
          meId={meId}
          users={userList}
          isPending={tasksPending}
          showServiceName={services.length > 1}
          escalations={escalationsByTask}
        />

        {/* Kept below the hierarchy rather than dropped: none of these is a
            task, and all three are things somebody on this page needs. */}
        <EscalationBanner obClientId={obClientId} escalations={escalations} />

        {services
          .filter((service) => service.isComplete)
          .map((service) => (
            <SignoffPanel
              key={service.journeyId}
              kind="GO_LIVE"
              journeyId={service.journeyId}
              obClientId={obClientId}
            />
          ))}
      </div>
    </div>
  )
}

/**
 * Tasks settled over tasks scheduled, summed across the stage roll-up.
 *
 * <p>Read off `stages` rather than off the per-journey reads, so the figure
 * agrees with the ribbon beneath it even while those are still in flight. Two
 * places counting one thing from two sources is how a header ends up
 * disagreeing with the list under it.
 */
function taskTotals(stages: readonly ObProjectStage[]): { done: number; total: number } {
  return stages.reduce(
    (acc, s) => ({
      done: acc.done + (s.taskCount - s.tasksOutstanding),
      total: acc.total + s.taskCount,
    }),
    { done: 0, total: 0 },
  )
}

/** `2026-09-14T09:42:02Z` and `2026-09-14` both → "14 Sep". */
function shortDay(value: string): string {
  const parsed = new Date(value.length <= 10 ? `${value}T00:00:00` : value)
  if (Number.isNaN(parsed.getTime())) return value
  return parsed.toLocaleDateString(undefined, { day: '2-digit', month: 'short' })
}

function ProjectHeader({
  project,
  taskTally,
}: {
  project: ObProjectDetail
  taskTally: { done: number; total: number }
}) {
  const delay = delayCell(project)
  const stages = stageProgress(project)

  return (
    <header className="border-b border-default bg-surface px-6 py-4">
      <div className="mx-auto flex max-w-[88rem] flex-col gap-3">
        <div className="flex flex-wrap items-start gap-x-5 gap-y-3">
          <div className="min-w-0 flex-1 basis-80">
            {/*
              The breadcrumb carries the route and nothing else. The client used
              to sit here *as well as* on the caption line below and again in
              the nested page's back link — three prints of one name on one
              screen. It is named once now, on the line under the title, where
              it is one of four facts rather than a second heading.
            */}
            <p className="text-caption text-content-muted">
              <Link to="/onboarding/projects" className="hover:underline">
                Projects
              </Link>
            </p>

            <div className="mt-0.5 flex flex-wrap items-center gap-2">
              {/* The project leads. It is what this page is about; the client
                  is who it is for, which is a different question. */}
              <h1 className="text-2xl font-semibold text-content [text-wrap:balance]">
                {project.name}
              </h1>
              {project.gateStatus === 'LOCKED' ? (
                <Chip
                  variant="warning"
                  title="The client's prerequisites have not cleared. Tasks can still be started — the checklist reports, it does not hold."
                >
                  Prerequisites pending
                </Chip>
              ) : null}
              <Chip
                variant={
                  delay.tone === 'late' ? 'danger' : delay.tone === 'ok' ? 'success' : 'neutral'
                }
                title={delay.hint}
              >
                {delay.label}
              </Chip>
            </div>

            {/*
              One line, four facts: who it is for, what they bought, who sold
              it, when it started. The client is a link because the prerequisite
              checklist and the portal login live on the client record.
            */}
            <p className="mt-1 flex flex-wrap items-center gap-x-1.5 text-sm text-content-muted">
              <Link
                to={`/onboarding/clients/${project.client.id}`}
                className="font-medium text-content hover:underline"
                title="The client record — prerequisites, contacts and portal login"
              >
                {project.client.name}
              </Link>
              {project.client.clientCode ? (
                <span className="tabular-nums">({project.client.clientCode})</span>
              ) : null}
              <Sep />
              <span>{project.product.name}</span>
              <Sep />
              <span>Sales: {project.salesPerson?.displayName ?? 'unassigned'}</span>
              <Sep />
              <span>Started {formatDate(project.startDate)}</span>
            </p>
          </div>

          {/*
            Four figures. "Current stage" is left to the ribbon, which says the
            same thing where the reader is already looking, and the city is a
            client fact rather than one about this engagement.
          */}
          <dl className="flex flex-wrap gap-x-7 gap-y-2">
            <Fact label="Stages" value={stages.label} />
            <Fact label="Tasks done" value={`${taskTally.done}/${taskTally.total}`} />
            <Fact label="Total TAT" value={`${project.totalTatDays}d`} />
            <Fact
              label="Tentative"
              value={formatDate(project.tentativeCompletion)}
              hint={`${project.totalTatDays} working days of TAT from the start date`}
            />
          </dl>
        </div>

        {project.statusReason ? (
          <p className="rounded-card bg-subtle p-3 text-sm text-content-muted">
            <span className="font-medium text-content">
              {project.status === 'ON_HOLD' ? 'On hold' : 'Dropped'}:
            </span>{' '}
            {project.statusReason}
          </p>
        ) : null}
      </div>
    </header>
  )
}

/** The faint interpunct between facts. Hidden from the accessibility tree — a
    screen reader reading "middle dot" four times is noise, not structure. */
function Sep() {
  return (
    <span aria-hidden="true" className="text-border">
      ·
    </span>
  )
}

function Fact({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div title={hint}>
      <dt className="text-[10.5px] uppercase leading-4 tracking-wide text-content-muted">{label}</dt>
      <dd className="mt-0.5 text-base font-semibold tabular-nums text-content">{value}</dd>
    </div>
  )
}
