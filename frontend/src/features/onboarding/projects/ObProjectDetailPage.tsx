import * as React from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'

import { useGetMe } from '@/api/generated/auth/auth'
import { useListUsers } from '@/api/generated/users/users'
import type { ObProjectDetail } from '@/api/generated/model/obProjectDetail'

import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { Skeleton } from '@/components/ui/skeleton'

import { EscalationBanner } from '@/features/onboarding/journey/clientDetail/EscalationBanner'
import { SignoffPanel } from '@/features/onboarding/journey/clientDetail/SignoffPanel'
import {
  toTaskEscalation,
  useOpenEscalations,
} from '@/features/onboarding/journey/clientDetail/useOpenEscalations'

import { EditObProjectDialog } from './EditObProjectDialog'
import { ObProjectWorkspace } from './ObProjectWorkspace'
import { buildProjectTree, projectTally, type ProjectTally } from './projectTree'
import { useObProject } from './projectQueries'
import { delayCell, formatDate } from './projectRow'
import { useProjectTasks } from './useProjectTasks'
import { isObAdmin, isObProjectEditor, isMineOnly, obViewerScope } from './viewerScope'

/**
 * One project — `/onboarding/projects/:obProjectId`, and the screen the
 * onboarding module is read from.
 *
 * <h2>The hierarchy, in one order</h2>
 *
 * <pre>
 *   Project → Prerequisites → Module Service → Step → Task → Check list
 * </pre>
 *
 * <p>The project leads because it is what the page is about; the client is who
 * it is for, which is a different question and one line of caption.
 *
 * <p>Below the header the page is an <b>accordion of module services</b>, each
 * opening onto a timeline of its Steps with every task listed under its Step,
 * and a task opening as a popup. See {@link ObProjectWorkspace}.
 *
 * <p><b>Step is this product's word for an implementation stage.</b> The API
 * still says stage — `ObProjectStage`, `stageKey`, `ob_journey_template_stages`
 * — because `ob_journey_steps` is already the *task* table and renaming the
 * model would mean a migration across the append-only history for a word. So
 * the screen says Step and the schema says stage, and this is the sentence that
 * says so.
 *
 * <h2>Steps are per Module Service, never the project-level roll-up</h2>
 *
 * <p>The ribbon this page once drew from `ObProjectDetail.stages` — the roll-up
 * folded across every journey — could not say whether SIS had finished its one
 * task or Attendance none of its two. Each service's timeline draws the
 * server's roll-up at journey grain, which can.
 *
 * <p>The project's own totals stay in the header, folded from the same tree the
 * strips are built from rather than from the project-level roll-up — otherwise
 * a filtered page would carry an unfiltered header.
 *
 * <h2>The page counts whoever is reading it</h2>
 *
 * <p>An implementor gets their own tasks, their own Steps and their own four
 * figures on every Module strip; an admin gets everybody's, with a per-person
 * accordion under each strip. One {@link ObViewerScope}, decided here and
 * passed down, so the header and the tree cannot answer differently.
 *
 * <p><b>It is presentation, never permission.</b> CLAUDE.md's row-scoping rule
 * is categorical and the server has already applied it — this only chooses
 * between two readings of rows the caller was entitled to.
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
 * <p>Escalations and go-live sign-off are kept **below** the modules. Neither
 * is a task, and dropping them would remove working function; putting them
 * above would push the work a reader came for off the fold. Each has a line
 * to say when there is nothing in it, so the section is always there to find.
 *
 * <p>The client-level **communications panel is deliberately not here**. Every
 * task now carries its own append-only timeline inside its panel, and the
 * stitched view repeated all of it a second time at the bottom of the page —
 * the same two entries, under a different heading, a screen further down. It
 * remains on `ObClientProductPage`, which is where "everything said to this
 * client, across every service" is the question being asked.
 *
 * <p>The **prerequisite checklist is not here either**, any more. It sat as a
 * row under the header with a View that unfolded the accordion, on a page
 * whose reader came for the Steps and tasks — and the header's own chip
 * already says the gate is pending and that tasks can start regardless. The
 * checklist itself is on the client's product page, where the client record,
 * the contacts and the portal login are, and the client's name in the header
 * links straight to it.
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

  return <ProjectBody project={data.project} etag={data.etag} />
}

/**
 * The page below its read, split out so the selections can hold state without
 * the guards above having to run first.
 *
 * <p>Keeping these hooks in the component that returns early on `isPending`
 * would either sit above the guards — including one request per module service,
 * fired while the skeleton is still up — or break the rules of hooks by sitting
 * below them.
 */
function ProjectBody({
  project,
  etag,
}: {
  project: ObProjectDetail
  /** The read's `ETag`, for the edit dialog's `If-Match`. */
  etag: string | null
}) {
  const obClientId = project.client.id
  const services = React.useMemo(() => project.moduleServices ?? [], [project.moduleServices])

  const me = useGetMe()
  const users = useListUsers({ isActive: true, limit: 200 })
  const userList = React.useMemo(() => users.data?.data ?? [], [users.data?.data])

  const { tasks, isPending: tasksPending } = useProjectTasks(project)

  /*
    Who is reading, and therefore what the page counts.

    There is no control over it any more. It used to carry an implementor's
    **Show all Steps**, which swapped the whole page between "my work" and
    "everybody's" — and the tree below now has its own two-position switch
    between a reader's outstanding tasks and all of theirs. Two controls in one
    corner, both reading "show all" and meaning different things, is one too
    many; this is the reader's own work, and the switch says which of it.
  */
  const scope = React.useMemo(() => obViewerScope(me.data?.data), [me.data?.data])

  /*
    Edit is offered to the roles the server accepts it from — OB Admin and
    Sales — and to nobody else. The button is the only way in; the dialog is
    fed this page's own read so its `If-Match` names what the reader saw.
  */
  const canEdit = isObProjectEditor(me.data?.data)
  const [editing, setEditing] = React.useState(false)

  /*
    The header, folded from the same tree the strips below are built from.

    It used to sum `ObProjectDetail.stages` — the project-level roll-up — which
    is right for an unfiltered page and wrong the moment the strips are scoped:
    an implementor would read "Steps 2/7, Tasks done 3/9" above three strips
    that between them account for six tasks. `buildProjectTree` is a pure fold
    over props this component already holds, so calling it here as well as in
    the tree costs a pass over a few dozen tasks and buys a header that cannot
    drift from the list under it.
  */
  const tally = React.useMemo(
    () => projectTally(buildProjectTree(services, tasks, scope)),
    [services, tasks, scope],
  )

  /**
   * One task to open the tree onto, from `?task=`.
   *
   * <p>In the URL and not in state, because a task is the one thing on this
   * page worth linking to: a notification mail can carry it so a recipient
   * lands on the row rather than on the project. Which branches are open
   * otherwise stays private to the component — that is a scroll position, not
   * a link.
   *
   * <p>Read-only now. It used to be written here too, by a your-work strip that
   * named the reader's next task and offered a **Go** — and the strip is gone,
   * so nothing on this page sets `?task=` any more. An arriving link still
   * works exactly as it did.
   */
  const [params] = useSearchParams()
  const revealTaskId = numberOrNull(params.get('task'))

  const { escalations } = useOpenEscalations(obClientId)
  const completedServices = React.useMemo(
    () => services.filter((service) => service.isComplete),
    [services],
  )

  /*
    Keyed by task so the panel can draw its own banner. The page-level
    `EscalationBanner` below still lists them all — one is "this task is
    escalated", the other is "this project has escalations", and a reader
    scrolling to a stage they have not opened needs the second.
  */
  const escalationsByTask = React.useMemo(
    // `toTaskEscalation` rather than an object literal here: the My Tasks popup
    // and a task's own page open the same panel and draw the same banner, and
    // three copies of this mapping is how one of them ends up attributing an
    // escalation to a different person.
    () => new Map(escalations.map((e) => [e.stepId, toTaskEscalation(e)])),
    [escalations],
  )

  return (
    <div className="flex flex-col">
      <ProjectHeader
        onEdit={canEdit ? () => setEditing(true) : undefined} project={project} tally={tally} mineOnly={isMineOnly(scope)} />
      <EditObProjectDialog
        project={project}
        etag={etag}
        open={editing}
        onClose={() => setEditing(false)}
        people={userList}
      />

      <div className="mx-auto flex w-full max-w-[88rem] flex-col gap-2.5 px-6 py-4">
        <ObProjectWorkspace
          services={services}
          tasks={tasks}
          scope={scope}
          users={userList}
          isPending={tasksPending}
          escalations={escalationsByTask}
          revealTaskId={revealTaskId}
          canReview={
            isObAdmin(me.data?.data) ||
            (project.implementorManager?.id != null &&
              project.implementorManager.id === scope.meId)
          }
          below={
            <>
              {/*
                Kept below the task rather than dropped: neither is a task, and
                both are things somebody on this page needs. The rail's two
                links land here, so each says something even when it is empty
                — a link that scrolls to nothing teaches the reader it is dead.
              */}
              <div id={ESCALATIONS_ANCHOR} className="scroll-mt-4">
                {escalations.length > 0 ? (
                  <EscalationBanner obClientId={obClientId} escalations={escalations} />
                ) : (
                  <p className="m-0 rounded-card border border-border bg-surface px-4 py-3 text-caption text-content-muted">
                    <span className="font-semibold text-content">Escalations</span> — none open from
                    this client.
                  </p>
                )}
              </div>

              <div id={GO_LIVE_ANCHOR} className="flex scroll-mt-4 flex-col gap-2.5">
                {completedServices.length > 0 ? (
                  completedServices.map((service) => (
                    <SignoffPanel
                      key={service.journeyId}
                      kind="GO_LIVE"
                      journeyId={service.journeyId}
                      obClientId={obClientId}
                    />
                  ))
                ) : (
                  <p className="m-0 rounded-card border border-border bg-surface px-4 py-3 text-caption text-content-muted">
                    <span className="font-semibold text-content">Go-live sign-off</span> — asked for
                    once a module service completes. None has yet.
                  </p>
                )}
              </div>
            </>
          }
        />
      </div>
    </div>
  )
}

/** The two panels under the modules, addressable — a mailed link can name them. */
const ESCALATIONS_ANCHOR = 'ob-escalations'
const GO_LIVE_ANCHOR = 'ob-go-live'

/** A hand-edited `?task=abc` reads as no task rather than as `NaN`. */
function numberOrNull(value: string | null): number | null {
  if (!value) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}


function ProjectHeader({
  project,
  tally,
  mineOnly,
  onEdit,
}: {
  project: ObProjectDetail
  tally: ProjectTally
  /** The figures are this reader's own, so the labels say so. */
  mineOnly: boolean
  /** Present only for a reader the server lets edit — see `isObProjectEditor`. */
  onEdit?: () => void
}) {
  const delay = delayCell(project)

  return (
    <header className="border-b border-default bg-surface px-6 py-4">
      <div className="mx-auto flex max-w-[88rem] flex-col gap-3">
        <div className="flex flex-wrap items-start gap-x-5 gap-y-3">
          <div className="min-w-0 flex-1 basis-80">
            {/*
              The breadcrumb carries the route and the client's code — the one
              short, unique handle a reader quotes on a call — and nothing
              else. The client's *name* used to sit here as well as on the line
              below; it is named once now, under the title, where it is one of
              four facts rather than a second heading.
            */}
            <p className="flex items-center gap-1.5 text-caption text-content-muted">
              <Link to="/onboarding/projects" className="hover:underline">
                Projects
              </Link>
              {project.client.clientCode ? (
                <>
                  <span aria-hidden="true">/</span>
                  <span className="tabular-nums">{project.client.clientCode}</span>
                </>
              ) : null}
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
              One line, five facts: who it is for, what they bought, who sold
              it, who is implementing it, when it started.

              The client is a link to **this project's product page**, not to a
              client page — there is no longer one. A project is a (client,
              product) pair, so that page is the same pair seen from the other
              side, and it is where the prerequisite checklist, the portal login
              and the client record now live. Linking at the client instead
              would go through `ObClientRedirect`, which would resolve the
              client's *first* product and could land a reader on a different
              one than the project they are reading.
            */}
            <p className="mt-1 flex flex-wrap items-center gap-x-1.5 text-sm text-content-muted">
              <Link
                to={`/onboarding/clients/${project.client.id}/products/${project.product.id}`}
                className="font-medium text-content hover:underline"
                title="The client record — prerequisites, contacts and portal login"
              >
                {project.client.name}
              </Link>
              <Sep />
              <span>{project.product.name}</span>
              <Sep />
              <span>Sales: {project.salesPerson?.displayName ?? 'unassigned'}</span>
              <Sep />
              {/*
                Beside the sales person rather than instead of them: they are
                the people a reader chasing this project asks for, and which
                one they want depends on whether the question is about the
                contract, about the work, or about who to escalate it to. All
                three print "unassigned" rather than disappearing — a project
                routinely has one before the others, and a line that silently
                drops the missing half reads as though nobody thought to record
                it.
              */}
              <span>Implementor: {project.implementor?.displayName ?? 'unassigned'}</span>
              <Sep />
              <span>Manager: {project.implementorManager?.displayName ?? 'unassigned'}</span>
              <Sep />
              <span>Started {formatDate(project.startDate)}</span>
            </p>
          </div>

          {/*
            Four figures, and the first two answer the question the header is
            actually asked: how far along is this, and how much of it is
            finished work rather than work in flight.

            They replaced "Steps 3/5" and "Tasks done 5/7", which were the same
            progress said twice in two denominators — a reader comparing 3/5
            against 5/7 has to do the arithmetic the first figure now does for
            them, and neither fraction said anything about the level a project
            is actually reported at, which is the module. The per-Step and
            per-task fractions are not lost: every Module strip below still
            carries its own, over its own work, where they are a fact about
            something a reader can open.

            Completion stays the reader's own where the page is filtered — the
            label says "Your completion" rather than leaving two people to
            compare two percentages neither of them can see the denominator
            for. Modules do not: a module is finished or it is not, whoever is
            reading. See `ProjectTally`.
          */}
          <div className="flex flex-col items-end gap-2">
            <dl className="flex flex-wrap gap-x-7 gap-y-2">
              <Fact
                label={mineOnly ? 'Your completion' : 'Completion'}
                value={`${tally.completionPercent}%`}
                hint={
                  mineOnly
                    ? `${tally.tasksDone} of your ${tally.tasksTotal} tasks completed`
                    : `${tally.tasksDone} of ${tally.tasksTotal} tasks completed`
                }
              />
              <Fact
                label="Modules done"
                value={`${tally.modulesComplete}/${tally.modulesTotal}`}
                hint="Module Services this project was boarded through, and how many have finished"
              />
              <Fact label="Total TAT" value={`${project.totalTatDays}d`} />
              <Fact
                label="Tentative"
                value={formatDate(project.tentativeCompletion)}
                hint={`${project.totalTatDays} working days of TAT from the start date`}
              />
            </dl>
            {/*
              The completion figure, as a bar with its fraction: the percentage
              above says how far, this says out of how much. The bar is
              decorative — the fraction beside it is the accessible form.
            */}
            <p className="m-0 flex w-full items-center gap-2.5 text-caption tabular-nums text-content-muted">
              <span aria-hidden="true" className="flex h-1.5 flex-1 overflow-hidden rounded-chip bg-subtle">
                <span className="block h-full bg-primary" style={{ width: `${tally.completionPercent}%` }} />
              </span>
              <span>
                {tally.tasksDone} of {mineOnly ? 'your ' : ''}
                {tally.tasksTotal} tasks completed
              </span>
            </p>
          </div>

          {onEdit ? (
            <div className="self-start">
              <Button variant="secondary" onClick={onEdit}>
                Edit project
              </Button>
            </div>
          ) : null}
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
