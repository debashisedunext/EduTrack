import * as React from 'react'
import { keepPreviousData } from '@tanstack/react-query'
import { ExternalLink } from 'lucide-react'
import { Link, useSearchParams } from 'react-router-dom'

import { useGetMe } from '@/api/generated/auth/auth'
import { useGetObJourney } from '@/api/generated/onboarding-journeys/onboarding-journeys'
import { useListObMyTasks } from '@/api/generated/onboarding/onboarding'
import { useListUsers } from '@/api/generated/users/users'
import type { ObMyTask } from '@/api/generated/model/obMyTask'

import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Tabs, type TabItem } from '@/components/ui/tabs'
import { cn } from '@/lib/utils'

import {
  escalationForStep,
  useOpenEscalations,
} from '@/features/onboarding/journey/clientDetail/useOpenEscalations'
import { ObCursorPager } from '@/features/onboarding/pagination/ObCursorPager'
import { OB_PAGE_SIZE, useCursorPages } from '@/features/onboarding/pagination/useCursorPages'
import { ObTaskDialog } from '@/features/onboarding/projects/ObTaskDialog'
import { StatusDot } from '@/features/onboarding/projects/TaskStatusDot'
import { isMine } from '@/features/onboarding/projects/useProjectTasks'
import { isObAdmin } from '@/features/onboarding/projects/viewerScope'

import {
  DEFAULT_MY_TASKS_TAB,
  focusedTask,
  formatDueDate,
  MY_TASKS_TAB_EMPTY,
  MY_TASKS_TAB_IDS,
  MY_TASKS_TAB_LABEL,
  myTaskDotLabel,
  myTaskDotState,
  type MyTasksTabId,
  projectOnlyLabel,
  taskCrumb,
  tasksForMyTasksTab,
  visibleMyTasksTabs,
} from './myTasks'
import {
  FLAG_BANNER_CLASS,
  FLAG_MARK,
  FLAG_ROW_CLASS,
  FLAG_TEXT_CLASS,
  reviewFlag,
  rows,
  type FlagTone,
} from './myTaskFlags'
import { ObMyTasksLegend } from './ObMyTasksLegend'

/**
 * My Tasks — `/onboarding/my-tasks`. Every task still open against the person
 * reading it, across every project and client.
 *
 * <h2>Why this is not a filter on the Projects grid</h2>
 *
 * <p>The Projects grid answers "how is this engagement going"; an implementor's
 * question is "what do I do next", and it spans projects. Reaching the same
 * answer from there would mean opening each project in turn and reading its
 * tree for the rows with their name on — which is the work this screen exists
 * to remove.
 *
 * <h2>A task opens as a popup, the same one the project page uses</h2>
 *
 * <p>Pressing a task's name opens {@link ObTaskDialog} over the queue — the
 * check list, the facts and the action bar — and closing it puts the reader
 * back on the same page of the same queue, which is what somebody working
 * through a list wants. The standalone page (`/onboarding/my-tasks/:taskId`)
 * is still there behind the small link on each row, and still where a mailed
 * link lands: it is the same task with the whole screen to itself.
 *
 * <h2>The rows are the caller's because the endpoint says so</h2>
 *
 * <p>There is no owner filter here and none on the request.
 * `/onboarding/my-tasks` returns the caller's own tasks and has no
 * `ownerUserId` parameter to say otherwise — a filter applied on this side
 * would be one an implementor could change to read a colleague's queue.
 *
 * <h2>The status is a circle, the same four the task strip uses</h2>
 *
 * <p>Grey pending, blue in process, red overdue, green completed — drawn as a
 * ring by {@link StatusDot} from {@link myTaskDotState}, the same four hues the
 * strip fills its dots with, so a task cannot be red on its project page and
 * grey here. A queue is scanned rather than read, and a column
 * of words in five different widths makes the one thing a reader is looking for
 * the hardest thing to compare. The six statuses are not lost: each dot carries
 * its own in words (hover, and for a screen reader), and the popup prints it.
 *
 * <p>The date still carries the overdue warning as well, because a task can be
 * In progress and overdue at once and the dot has only one colour to spend. Red
 * says "this one", the date beside it says which day it was due.
 *
 * <p>{@link ObMyTasksLegend} above the table is what makes those four hues —
 * and the three a review tints a whole row with — readable without hovering
 * each one in turn. It draws from the same maps the rows do, so the key and the
 * column cannot come to mean different things.
 *
 * <h2>Five columns, because two pairs only mean anything together</h2>
 *
 * <p>Client and project share a column, and module and step share another —
 * each printed as two lines rather than joined into one string, the specific
 * value on top and the value that places it in small type below:
 * {@link projectOnlyLabel} gives the Project column its top line, with the
 * client name beneath it, and the Module column prints the step on top with
 * the module beneath. Each half is ambiguous alone: a project named "ERP"
 * needs its school, and half a queue's step reads "Configuration" until the
 * module beside it says what is being configured. That is the opposite of
 * the date and the status above, which say different things and so keep
 * their own columns.
 *
 * <h2>Five tabs, cut from this page rather than a second request</h2>
 *
 * <p>Pending for verification / Work in progress / Approved by manager /
 * Pending for approval / Pending task — {@link tasksForMyTasksTab} narrows
 * and, for the second tab, reorders the same rows this screen already
 * fetched. The `?tab=` search param is the active one, same as the
 * onboarding dashboard's own strip, so a tab is a link a colleague can paste.
 * See {@link MY_TASKS_TAB_IDS}'s own doc for why these five and not the seven
 * statuses underneath them.
 *
 * <h2>Pending for verification is drawn for whoever the server says reviews</h2>
 *
 * <p>{@link visibleMyTasksTabs} drops it for everyone else — see that
 * function's own doc for why that is `meta.isReviewerForAnyProject` and not
 * the caller's module role. An implementor who is also named manager of some
 * other project gets the tab without anything here having to know that:
 * the server already folded "owns a task" and "reviews a project" into the
 * one page, and this only decides which tab shows what.
 */

function isMyTasksTabId(value: string | null): value is MyTasksTabId {
  return value !== null && (MY_TASKS_TAB_IDS as readonly string[]).includes(value)
}

export function ObMyTasksPage() {
  /*
    One filter set, so nothing invalidates the cursor but paging itself. The
    empty key is deliberate rather than an oversight: when a filter arrives it
    goes here, and the reset comes with it.
  */
  const pages = useCursorPages('')

  const { data, isPending, isError, isFetching } = useListObMyTasks(
    { cursor: pages.cursor, limit: OB_PAGE_SIZE },
    { query: { placeholderData: keepPreviousData } },
  )

  const tasks = data?.data ?? []
  const overdue = tasks.filter((task) => task.isOverdue).length
  /*
    Page-independent — see `visibleMyTasksTabs`'s own doc for why this reads
    the caller's standing rather than this page's own rows, and why that is
    what keeps a dual-hat implementor's tab from appearing and disappearing
    as they page through their own queue.
  */
  const visibleTabIds = visibleMyTasksTabs(data?.meta?.isReviewerForAnyProject ?? false)

  /** The row whose task is open in the popup. */
  const [openRow, setOpenRow] = React.useState<ObMyTask | null>(null)

  const [searchParams, setSearchParams] = useSearchParams()
  const activeTab: MyTasksTabId = isMyTasksTabId(searchParams.get('tab'))
    ? (searchParams.get('tab') as MyTasksTabId)
    : DEFAULT_MY_TASKS_TAB

  function selectTab(id: string) {
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev)
        next.set('tab', id)
        return next
      },
      { replace: true },
    )
  }

  const tabItems: TabItem[] = visibleTabIds.map((id) => {
    const rows = tasksForMyTasksTab(tasks, id)
    return {
      id,
      label: `${MY_TASKS_TAB_LABEL[id]} (${rows.length})`,
      content: (
        <MyTasksTabPanel rows={rows} emptyMessage={MY_TASKS_TAB_EMPTY[id]} onOpen={setOpenRow} />
      ),
    }
  })

  return (
    <div className="mx-auto flex max-w-[88rem] flex-col gap-6 p-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-content">My Tasks</h1>
          <p className="mt-1 max-w-3xl text-sm text-content-muted">
            Everything still open against you, across every project — soonest due first. Pick a
            task to work on it here.
          </p>
        </div>
        {tasks.length > 0 ? (
          <p className="text-caption tabular-nums text-content-muted" role="status">
            {tasks.length} on this page
            {overdue > 0 ? ` · ${overdue} overdue` : ''}
          </p>
        ) : null}
      </header>

      <ReviewSignal tasks={tasks} />

      {isPending ? (
        <Skeleton className="h-64 w-full" />
      ) : isError ? (
        <p className="text-sm text-danger-text">Your tasks could not be loaded.</p>
      ) : tasks.length === 0 && !pages.canGoBack ? (
        <EmptyState
          title="Nothing open against you"
          description="Every task you own is complete or waived. New work appears here as projects reach your steps."
        />
      ) : (
        // The key and the thing it is a key to, in one block — the page's own
        // 24px rhythm would set the legend adrift halfway to the strip, where
        // it reads as a second banner rather than as the table's caption.
        <div className="flex flex-col gap-2">
          <ObMyTasksLegend />
          <Tabs
            tabs={tabItems}
            activeId={activeTab}
            onSelect={selectTab}
            ariaLabel="My Tasks"
            variant="segmented"
          />
        </div>
      )}

      {!isError && (tasks.length > 0 || pages.canGoBack) ? (
        <ObCursorPager
          noun="tasks"
          pageIndex={pages.pageIndex}
          rowsOnPage={tasks.length}
          hasMore={data?.meta?.hasMore ?? false}
          isFetching={isFetching}
          canGoBack={pages.canGoBack}
          onPrevious={pages.previous}
          onNext={() => pages.next(data?.meta?.nextCursor)}
        />
      ) : null}

      <MyTaskDialog row={openRow} onClose={() => setOpenRow(null)} />
    </div>
  )
}

function MyTaskRow({ task, onOpen }: { task: ObMyTask; onOpen: () => void }) {
  const flag = reviewFlag(task)
  return (
    <TableRow
      data-flag={flag?.tone}
      // A left edge and a wash — and the words in the Task cell below, never
      // colour alone (blueprint §12.1). The classes come from the same map the
      // legend draws its swatches from.
      className={cn(flag && FLAG_ROW_CLASS[flag.tone])}
    >
      <TableCell className="text-content-muted">
        {/*
          The project links to "what else is happening here", and the client
          it belongs to sits underneath in small type — the project is what a
          reader picks a row by, the client is what places it.
        */}
        <Link
          to={`/onboarding/projects/${task.projectId}`}
          className="hover:underline"
          title="The whole project"
        >
          {projectOnlyLabel(task)}
        </Link>
        {task.obClientName ? (
          <span className="mt-0.5 block text-[11px] text-content-muted">
            {task.obClientName}
            {task.obClientCode ? <span className="ml-1 font-mono">({task.obClientCode})</span> : null}
          </span>
        ) : null}
      </TableCell>
      <TableCell className="font-medium">
        <span className="inline-flex items-center gap-1.5">
          <button
            type="button"
            aria-haspopup="dialog"
            onClick={onOpen}
            className="rounded-control text-left text-primary hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          >
            {task.taskName}
          </button>
          {/* The same task with the whole screen to itself — and the address a
              mailed link carries, so it stays one press away. */}
          <Link
            to={`/onboarding/my-tasks/${task.taskId}`}
            aria-label={`Open ${task.taskName} on its own`}
            title="Open this task on its own page"
            className="inline-flex size-6 items-center justify-center rounded-control text-content-muted hover:bg-subtle hover:text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          >
            <ExternalLink aria-hidden="true" className="size-3.5" />
          </Link>
        </span>
        {/* The words the colour is not allowed to carry alone. */}
        {flag ? (
          <span
            data-testid="ob-my-task-review-note"
            className={cn('mt-0.5 block text-[11px] font-medium', FLAG_TEXT_CLASS[flag.tone])}
          >
            {flag.note}
          </span>
        ) : null}
      </TableCell>
      {/*
        The step on top, plain text rather than the chip it used to wear (a
        chip around "Web/App Reflection" wraps to two lines and reads as a
        status, which a stage name is not). The module beneath it in small
        type, because a step name is a stage label reused across every
        module — "Configuration" alone means nothing until the module beside
        it says what is being configured.
      */}
      <TableCell className="text-content">
        {task.stepName}
        {task.serviceName ? (
          <span className="mt-0.5 block text-[11px] text-content-muted">{task.serviceName}</span>
        ) : null}
      </TableCell>
      <TableCell
        className={cn(
          'tabular-nums',
          task.isOverdue ? 'font-semibold text-danger-text' : 'text-content-muted',
        )}
        // Named for a screen reader: the red alone says nothing, and the
        // status column says something different.
        title={task.isOverdue ? 'Past its due date' : undefined}
      >
        {formatDueDate(task.dueAt)}
        {task.isOverdue ? <span className="ml-1 text-[11px]">overdue</span> : null}
      </TableCell>
      <TableCell>
        {/*
          The dot sits where the pill did, in a fixed column — which is the
          point: four hues a reader compares down the page rather than five
          widths of word they have to read one at a time.
        */}
        <StatusDot state={myTaskDotState(task)} label={myTaskDotLabel(task)} hollow />
      </TableCell>
    </TableRow>
  )
}

/** One tab's rows — the grid if there are any, the tab's own empty line if not. */
function MyTasksTabPanel({
  rows,
  emptyMessage,
  onOpen,
}: {
  rows: readonly ObMyTask[]
  emptyMessage: string
  onOpen: (task: ObMyTask) => void
}) {
  if (rows.length === 0) {
    return <EmptyState title={emptyMessage} />
  }
  return (
    <TableContainer>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead scope="col">Project</TableHead>
            <TableHead scope="col">Task</TableHead>
            <TableHead scope="col" className="w-64">
              Module
            </TableHead>
            <TableHead scope="col" className="w-32">
              Due date
            </TableHead>
            <TableHead scope="col" className="w-20">
              Status
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((task) => (
            <MyTaskRow key={task.taskId} task={task} onOpen={() => onOpen(task)} />
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  )
}

/**
 * One banner over the page, summing what the rows below are flagged with.
 *
 * <p>Said once at the top as well as on each row, because the row is only
 * found by somebody already scanning: the question this answers is "is there
 * anything for me", which is asked before the first row is read.
 *
 * <p>Counts across <em>this page</em> and says so. A count over every page
 * would need a second request to produce a number nobody could then act on
 * without paging to find it.
 */
function ReviewSignal({ tasks }: { tasks: readonly ObMyTask[] }) {
  const back = tasks.reduce((n, t) => n + t.rowsReturned, 0)
  const approved = tasks.reduce((n, t) => n + t.rowsApproved, 0)
  const out = tasks.reduce((n, t) => n + t.rowsOut, 0)
  if (back === 0 && approved === 0 && out === 0) return null

  const tone: FlagTone = back > 0 ? 'back' : approved > 0 ? 'good' : 'review'
  const text =
    back > 0
      ? `${rows(back)} came back to you.${approved > 0 ? ` ${rows(approved)} approved in the same pass.` : ''} Open the task to see why.`
      : approved > 0
        ? `${rows(approved)} approved. Nothing to do — your reviewer saying it holds.`
        : `${rows(out)} out for verification on this page.`

  return (
    <div
      role="status"
      data-testid="ob-my-tasks-review-signal"
      data-tone={tone}
      className={cn(
        'flex flex-wrap items-center gap-2 rounded-control border px-3.5 py-2.5 text-sm',
        FLAG_BANNER_CLASS[tone],
      )}
    >
      <span aria-hidden="true">{FLAG_MARK[tone]}</span>
      <span>{text}</span>
    </div>
  )
}

/**
 * The popup for the picked row — the project page's popup, opened from here.
 *
 * <p>The row carries the labels; the journey read carries the check list, the
 * documents and the TAT figures — the same query key the project page uses,
 * so a task already looked at there costs nothing here. The popup opens on the
 * press with a placeholder and fills in when the read lands, rather than
 * appearing a beat later.
 *
 * <h2>The same dialog has to be handed the same things</h2>
 *
 * <p>{@link ObTaskDialog} is shared with `ObProjectWorkspace`, so the check
 * list, the review column and the action bar were never in question. What
 * differed was what the two screens passed it, and a reader who had seen a
 * returned task on the project page found a thinner one here:
 *
 * <ul>
 *   <li><b>The crumb</b> wore a different shape and dropped the task's position
 *       in its step — {@link taskCrumb} now builds the project page's, with the
 *       project kept on the front because this queue spans several.</li>
 *   <li><b>The client escalation banner</b> was simply never passed, so a task
 *       a school had complained about looked ordinary from the queue and
 *       alarming from the project page. It is the same read
 *       ({@link useOpenEscalations}) and the same mapping
 *       ({@link escalationForStep}).</li>
 *   <li><b>The journey itself was whatever this session last cached</b>, which
 *       is the one that made a rejected task read as still out for review. The
 *       read below says why it is no longer served from cache.</li>
 * </ul>
 */
function MyTaskDialog({ row, onClose }: { row: ObMyTask | null; onClose: () => void }) {
  /*
    Read fresh, every time the popup opens.

    The default — serve the cache, refetch behind it only once it is 30s old —
    is right for a screen somebody is browsing and wrong for one they opened to
    act on. The journey under this popup is written to by *other people*: a
    manager verifying rows or sending them back does it in their own session,
    which invalidates their cache and not the reader's. So an implementor who
    had the task open before the verdict landed reopened it afterwards and was
    handed the state it was in when they last looked — answered, sent, waiting
    on a reviewer — with nothing to say it had come back to them.

    That was the whole of the difference between this popup and the project
    page's. Neither draws a returned task differently; the project page is
    simply more often open in the session that did the writing.
  */
  const journey = useGetObJourney(row?.journeyId ?? 0, {
    query: { enabled: row != null, staleTime: 0, refetchOnMount: 'always' },
  })
  const me = useGetMe()
  const users = useListUsers({ isActive: true, limit: 200 }, { query: { enabled: row != null } })
  const userList = React.useMemo(() => users.data?.data ?? [], [users.data?.data])

  /*
    NaN rather than 0 with no row: `useOpenEscalations` gates its request on
    `Number.isFinite`, and 0 is finite — it would ask the server for the open
    escalations of client zero every time the popup closed.
  */
  const { escalations } = useOpenEscalations(row?.obClientId ?? Number.NaN)

  /*
    When this open began — React's own "adjust state while rendering" pattern,
    which is what makes the answer available on the first render rather than an
    effect's worth of paint later.

    Reset on close as well as on a change of task, so opening the same row twice
    is twice a fresh read. Without that, close-and-reopen is the one path that
    still serves the cache, and it is the path somebody takes precisely when
    they suspect the screen is out of date.
  */
  const [openedTaskId, setOpenedTaskId] = React.useState<number | null>(null)
  const [openedAt, setOpenedAt] = React.useState(0)
  if (row == null && openedTaskId !== null) {
    setOpenedTaskId(null)
  } else if (row != null && row.taskId !== openedTaskId) {
    setOpenedTaskId(row.taskId)
    setOpenedAt(Date.now())
  }

  const task = React.useMemo(
    () => (row ? focusedTask(row, journey.data?.data?.steps) : null),
    [row, journey.data?.data?.steps],
  )

  /*
    Hold the placeholder until *this* open's read has landed, rather than until
    there is any data at all.

    `refetchOnMount: 'always'` above means a cached journey is handed over
    immediately and refreshed behind it — so a reader reopening a task that has
    since come back would see the pre-verdict popup paint in full and then
    rearrange itself a moment later, which is a worse way to learn the news than
    a skeleton. `dataUpdatedAt` is what distinguishes "this was read for this
    open" from "this is what we had lying around", and it is 0 when there is
    nothing cached, so the cold case falls out of the same test.

    Deliberately not `isFetching`: that is also true during the invalidation
    that follows every answer, and blinking the check list back to a skeleton on
    each press is exactly what this must not do.

    `isError` counts as landed, and has to: a read that fails never advances
    `dataUpdatedAt`, so without it a dropped connection leaves the placeholder
    up for as long as the popup is open — a screen that says "loading" about a
    request that stopped. With it the popup falls through to whatever it has,
    which is the cached task where there is one and the problem sentence where
    there is not.
  */
  const landed = journey.dataUpdatedAt >= openedAt || journey.isError
  const pending = row != null && (journey.isPending || !landed)
  const problem =
    row != null && !pending && task == null
      ? 'This task’s check list could not be loaded. Reload to try again.'
      : null

  return (
    <ObTaskDialog
      task={task}
      crumb={row ? taskCrumb(row, journey.data?.data?.steps) : ''}
      users={userList}
      yours={task ? isMine(task, me.data?.data?.id) : false}
      // Whether this reader is the one who reviews *this* project — the
      // project's own implementor manager, not anybody holding a role. An
      // OB_ADMIN passes too, so a project whose manager has left is not a
      // review nobody can close. The server checks the same column, so the
      // column is never drawn for a verdict that would be refused.
      canReview={
        isObAdmin(me.data?.data) ||
        (journey.data?.data?.implementorManagerUserId != null &&
          journey.data?.data?.implementorManagerUserId === me.data?.data?.id)
      }
      escalation={escalationForStep(escalations, row?.taskId)}
      pending={pending}
      problem={problem}
      onClose={onClose}
    />
  )
}
