import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'

import {
  useGetObDashboardSummary,
  useGetObProjectBoard,
} from '@/api/generated/onboarding/onboarding'
import type { ObDashboardCard, ObDashboardCardKey, ObProjectBoardRow } from '@/api/generated/model'
import { Skeleton } from '@/components/ui/skeleton'
import { Tabs, type TabItem } from '@/components/ui/tabs'
import { useAuthStore } from '@/features/auth/authStore'

import { ObDashboardCardRow } from './ObDashboardCardRow'
import { seesProjectBoard } from './obDashboardCards'
import { ObReviewCards } from './ObReviewCards'
import { ObDashboardDrillPanel } from './ObDashboardDrillPanel'
import { OB_DASHBOARD_QUERY } from './obDashboardFreshness'
import { ObDashboardStuckPanel } from './ObDashboardStuckPanel'
import { ObProjectCardBand } from './ObProjectCardBand'
import { ObProjectChartRow } from './ObProjectChartRow'
import { ObMyTaskDonut } from './ObMyTaskDonut'
import { ObMyTaskSummaryGrid } from './ObMyTaskSummaryGrid'
import { ObProjectListPanel } from './ObProjectListPanel'
import { projectsForCard } from './obProjectBoard'
import { ObProjectSummaryLists } from './ObProjectSummaryLists'
import { ObDelayedProjectsGrid } from './ObDelayedProjectsGrid'
import { ObImplementorWorkloadGrid } from './ObImplementorWorkloadGrid'

/**
 * B-127/B-128 · what the S-06 slide-over is currently showing, or nothing.
 *
 * One shape for both readers of {@link ObDashboardDrillPanel} — a card tile,
 * which only ever names its own `cardKey`, and {@link ObImplementorWorkloadGrid},
 * which also narrows by `ownerUserId` and supplies its own `title`. A single
 * piece of state and a single panel instance, rather than one panel per
 * caller, is the reuse B-127 built the panel for in the first place. It lives
 * on the page, not inside a tab, because a click from the Summary tab and a
 * click from the Workload tab open the identical panel and neither tab
 * should own an instance the other one also needs.
 */
interface DrillTarget {
  cardKey: ObDashboardCardKey
  ownerUserId?: number
  title?: string
}

/** The four tabs, and the `?tab=` value each one is. */
const TAB_IDS = ['summary', 'stuck', 'delayed', 'workload'] as const
type TabId = (typeof TAB_IDS)[number]

const DEFAULT_TAB: TabId = 'summary'

function isTabId(value: string | null): value is TabId {
  return value !== null && (TAB_IDS as readonly string[]).includes(value)
}

/**
 * B-121 · OB-02, the onboarding dashboard — `/onboarding/dashboard`.
 *
 * <h2>The counters, then four tabs an Admin sees</h2>
 *
 * {@link ObDashboardCardRow} first and always, then — for a platform Admin —
 * Summary / Where it's stuck / Delayed projects / Implementor workload &amp;
 * performance on the ticketing dashboard's own {@link Tabs} shell — the same
 * `?tab=` URL state so a tab is a link a colleague can paste, and the same
 * reason for tabbing at all: only the active tab's content mounts, so a manager checking Delayed
 * projects no longer also pays for the stuck-panel and workload-grid requests
 * on every load.
 *
 * The cards sit **above** the strip rather than inside Summary because they
 * are the page's standing figures, not one tab's content — the row's own file
 * carries that argument. What is left in Summary is the three project lists:
 * what lands today, what is overdue, what is at risk of slipping.
 *
 * <h2>Who gets the board, and who gets the counters</h2>
 *
 * The board and its four tabs are drawn for the three onboarding roles that
 * deliver work — <b>OB_ADMIN</b>, <b>OB_MANAGER</b> and <b>OB_STEP_OWNER</b>,
 * the admin, the implementor manager and the implementor. Each of them is
 * served their own rows: every route behind the board derives its scope from
 * `CallerIdentity` server-side, so an implementor's tabs are their own
 * journeys and an admin's are the whole book, through the identical screen.
 * `seesProjectBoard` in `obDashboardCards.ts` carries the full argument,
 * including why widening it leaks nothing.
 *
 * OB_SALES and OB_VIEWER keep the counter row alone. The tabs are cuts of who
 * is delivering what and when, which is a question about somebody else's week
 * for both.
 *
 * The gate used to be the *platform* `ADMIN` role alone, a stand-in for an
 * onboarding division the session could not answer. `Me.moduleRoles` answers
 * it now — `POST /auth/login` returns it and `Sidebar` already reads it — so
 * the three onboarding roles are added <em>beside</em> that test rather than
 * replacing it: a platform admin holding no `ONBOARDING` grant keeps the board
 * they have today. `isAdmin` has a second, narrower job as well — the two
 * cards `visibleCards` keeps off a non-admin counter row.
 *
 * **It is a display decision, never a permission.** The server scopes and
 * refuses on its own — anyone reaching these routes by another path gets
 * exactly the answer they always did. Choosing a screen here guards nothing,
 * and nothing should be relied on as if it did.
 *
 * <h2>No page header</h2>
 *
 * No title, no strapline, no "as of" line and no boarding button. The module's
 * own shell already names the screen in the sidebar, and the counters are a
 * faster answer to "what am I looking at" than a sentence describing them.
 * Boarding a client is OB-03's action and lives on OB-03's own page, where the
 * list it adds to is visible. The `h1` survives for screen readers, which have
 * no sidebar to read the page's name from.
 *
 * <h2>Seven counters, one request</h2>
 *
 * `GET /onboarding/dashboard/summary` is the board's whole first paint. Not
 * seven requests and not a request per card: the contract's own reasoning, and
 * the same call A-073 made for the ticketing dashboard after the per-widget
 * shape cost eleven round trips on load. An Admin is drawn all seven;
 * everybody else five, with `at-risk` and `client-escalations` filtered in
 * `obDashboardCards.ts` and the reasoning there. `live` is drawn for both — it
 * was hidden while the row was read as a to-do list, and is back on it.
 *
 * <h2>Every number is pre-aggregated</h2>
 *
 * CLAUDE.md forbids a live `COUNT(*)` behind a dashboard, so these come from
 * `ob_dashboard_summary` as B-120's job last wrote it. That makes them up to
 * one refresh interval stale by design. The line that said so came off with
 * the rest of the header; each tile still carries its own caveat
 * (`countIsUpperBound`, `unavailableReason`), which is where a reader
 * questioning a specific number actually looks.
 *
 * <h2>Every card opens the S-06 slide-over — B-127</h2>
 *
 * `drill` is the whole handoff: a tile's `onOpen` sets it, and
 * {@link ObDashboardDrillPanel} reads it to fetch and render
 * `listObDashboardCardItems`. Nothing about *which* card is decided twice —
 * the tile passes its own `card.key` straight through, so the panel opens
 * exactly the card that was clicked.
 */
export function ObDashboardPage() {
  const { data, isPending, isError } = useGetObDashboardSummary(undefined, { query: OB_DASHBOARD_QUERY })
  /*
    The project board — the donuts, the six coloured cards and the Summary
    lists, all three from this one response. Its own request rather than more
    fields on the summary above: that one is a product-keyed pre-aggregate
    refreshed on a timer, this is a live read of running projects, and a
    single response carrying both would have one `computedAt` that is true of
    half of it. Both are cheap and neither blocks the other.
  */
  const board = useGetObProjectBoard({ query: OB_DASHBOARD_QUERY })
  const projectBoard = board.data?.data
  /*
    The one role read on this screen — see "The strip is Admin's" above for why
    it is the platform role rather than `OB_ADMIN`. Taken from the session the
    user signed in with rather than `GET /me`, which the contract declares and
    the server has never implemented; `Sidebar`'s own comment records what
    asking for it over HTTP cost there.
  */
  const isAdmin = useAuthStore((s) => s.user?.role) === 'ADMIN'
  /*
    Who gets the board and the tabs — the onboarding role, not the platform
    one. `obDashboardCards.ts` carries which roles and why it leaks nothing.
  */
  const onboardingRole = useAuthStore((s) => s.user?.moduleRoles?.ONBOARDING)
  const seesBoard = seesProjectBoard(onboardingRole, isAdmin)
  /* An implementor and their manager work a queue of their own; everybody
     else with the board is reading the book rather than delivering it. */
  const showsMyTasks = onboardingRole === 'OB_STEP_OWNER' || onboardingRole === 'OB_MANAGER'
  /*
    An implementor gets one tab — their own queue. The other four are
    cross-team readings of the book, which is their manager's job and not
    theirs; `ObMyTaskSummaryGrid` carries the rest of that argument.
  */
  const ownQueueOnly = onboardingRole === 'OB_STEP_OWNER'
  const [drill, setDrill] = useState<DrillTarget | null>(null)
  /* What the project panel is showing, or nothing. Its rows are cut from the
     board response rather than fetched — see `ObProjectListPanel`. */
  const [projectList, setProjectList] = useState<{ title: string; rows: ObProjectBoardRow[] } | null>(null)
  const [searchParams, setSearchParams] = useSearchParams()

  const cards = data?.data?.cards ?? []
  const activeTab: TabId = isTabId(searchParams.get('tab')) ? (searchParams.get('tab') as TabId) : DEFAULT_TAB

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

  function openDrill(card: ObDashboardCard) {
    setDrill({ cardKey: card.key })
  }

  /**
   * What a project card opens: the projects it counted, in a panel.
   *
   * <p>Every figure on this board opens one now — a card in the band and a
   * slice of either people donut — which is what the seven counters above the
   * strip have always done. A card used to select the Summary tab and a slice
   * used to leave for the Projects grid; both made a reader navigate away from
   * the board to answer "which ones?".
   *
   * <p>{@link ObProjectListPanel} carries the rest of the argument, including
   * why this is a second panel rather than {@link ObDashboardDrillPanel}: that
   * one answers in steps and prerequisite tasks, and these figures are counted
   * in projects.
   */
  function openProjectCard(key: string, label: string) {
    if (!projectBoard) return
    setProjectList({ title: label, rows: projectsForCard(key, projectBoard) })
  }

  const tabs: TabItem[] = ownQueueOnly
    ? [{ id: 'summary', label: 'Task summary', content: <ObMyTaskSummaryGrid /> }]
    : [
    {
      id: 'summary',
      label: 'Summary',
      /*
        The three project lists, and nothing under them.

        They are what a reader opens this tab for — what lands today, what is
        beyond rescue, what is slipping — and each names the implementor, so
        the answer to "who do I talk to" is on the row rather than a click
        away.

        The RAG board — Breached / blocked · At risk · On track — sat
        underneath as a second cut of the same clients, by journey colour.
        It is gone: three columns that were empty on most days, answering a
        question the cards above and the Delayed projects tab already answer,
        and paying for three client reads to do it.
      */
      content: projectBoard ? (
        <ObProjectSummaryLists rows={projectBoard.projects} today={projectBoard.today} />
      ) : (
        <Skeleton className="h-64 w-full rounded-card" />
      ),
    },
    { id: 'stuck', label: "Where it's stuck", content: <ObDashboardStuckPanel /> },
    {
      id: 'delayed',
      label: 'Delayed projects',
      /* Cut from the board the page already holds — see the grid's own note
         on why it no longer asks a second endpoint a different question. */
      content: (
        <ObDelayedProjectsGrid
          rows={projectBoard?.projects ?? []}
          isPending={board.isPending}
          isError={board.isError}
        />
      ),
    },
    {
      id: 'workload',
      label: 'Implementor workload & performance',
      content: <ObImplementorWorkloadGrid onDrill={setDrill} />,
    },
  ]

  return (
    <div className="mx-auto flex w-full max-w-[1280px] flex-col gap-5 p-6">
      {/* Named for screen readers only — see the docstring's "No page header". */}
      <h1 className="sr-only">Onboarding dashboard</h1>

      {/*
        Above the seven, not among them. These are the caller's own figures —
        per person, from a different table — and the board below is about this
        scope's clients. Rendered first because "is anything waiting on me"
        outranks every org-wide number on the page.
      */}
      {/* In the chart band for an implementor and their manager — see the
          chart row's `third`. Above it for everybody else, who have no queue
          of their own for it to sit beside. */}
      {!showsMyTasks && <ObReviewCards />}

      {/*
        One band of standing figures, not two.

        An Admin gets the **project** band: six figures on each project's own
        completion date, with the three donuts above them. Everybody else gets
        the seven journey-level counters, which is the board they already had
        and the only one their scope can answer.

        They are not drawn together, and that is the decision worth recording.
        Both rows open with a card called "Ongoing projects" and the two
        numbers are different — one counts journeys per product out of the
        summary pre-aggregate, the other counts running projects live — so a
        screen showing both would put two cards of the same name and different
        values side by side and leave the reader to work out which question
        each was answering. The tabs below already divide by *detail*; this
        divides by *grain*, once, at the top.

        Charts above the cards: the donuts answer "what shape is the book" and
        the cards answer "what needs doing today", and a reader who scrolls no
        further than the first screen should get the shape, since the six
        figures are repeated as lists on the Summary tab.
      */}
      {seesBoard ? (
        <>
          <ObProjectChartRow
            board={projectBoard}
            isPending={board.isPending}
            onSelectSlice={(title, rows) => setProjectList({ title, rows })}
            /* The two delivery roles get their own queue where an admin gets
               the salesperson cut — `ObMyTaskDonut` carries why. */
            mine={showsMyTasks ? <ObMyTaskDonut /> : undefined}
            /* And their review state where the team cut was — it is drawn
               above the charts for everybody else. */
            third={showsMyTasks ? <ObReviewCards /> : undefined}
          />
          <ObProjectCardBand
            board={projectBoard}
            isPending={board.isPending}
            onOpen={openProjectCard}
          />
          {board.isError && (
            <p className="text-xs text-content-muted">
              The project figures could not be loaded. Refresh to try again.
            </p>
          )}
          {projectBoard?.truncated && (
            <p className="text-xs text-content-muted">
              Showing the most recent projects only — the figures above may be short of the total.
            </p>
          )}
        </>
      ) : (
        <ObDashboardCardRow
          cards={cards}
          isPending={isPending}
          isError={isError}
          isAdmin={isAdmin}
          onOpen={openDrill}
        />
      )}

      {/*
        Not rendered at all for a non-admin, rather than rendered with no tabs:
        `Tabs` would otherwise draw an empty segmented strip and a named
        tablist with nothing in it, which reads as a screen that failed to
        load. The `?tab=` parameter is left alone — a link into a tab still
        carries it, and it simply decides nothing until an Admin opens it.
      */}
      <ObProjectListPanel
        title={projectList?.title ?? ''}
        rows={projectList?.rows ?? []}
        open={projectList != null}
        onClose={() => setProjectList(null)}
      />

      {seesBoard && (
        <Tabs
          tabs={tabs}
          activeId={activeTab}
          onSelect={selectTab}
          ariaLabel="Onboarding dashboard"
          variant="segmented"
        />
      )}

      <ObDashboardDrillPanel
        cardKey={drill?.cardKey ?? null}
        onClose={() => setDrill(null)}
        title={drill?.title}
        ownerUserId={drill?.ownerUserId}
      />
    </div>
  )
}
