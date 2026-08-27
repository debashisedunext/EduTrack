import { ChevronDown, ChevronUp, GripVertical } from 'lucide-react'
import * as React from 'react'

import type { GetDashboardWidgetParams } from '@/api/generated/model'
import { Skeleton } from '@/components/ui/skeleton'
import { useWidget } from './widgetBatchContext'

/**
 * A-056 · the card every widget 7–12 sits in.
 *
 * <h2>Why the frame owns the request</h2>
 *
 * Six charts each calling `useGetDashboardWidget` themselves would be six
 * places to get the loading, empty, error and *unavailable* states subtly
 * different — and the last of those is the one that matters, because getting it
 * wrong means telling somebody there is no data when the truth is that their
 * role's summary table has no column for it. The charts below receive series
 * and draw them; they make no decisions about what the absence of series means.
 *
 * <h2>The four empty-ish states are not the same state</h2>
 *
 * This is the whole reason this component is not three lines:
 *
 * - **loading** — a skeleton the same height as the chart, so nothing reflows.
 * - **error** — the request failed; say so and let them retry.
 * - **unavailable** — the server says the caller's role has no table that can
 *   answer this. Rendered as the server's sentence, verbatim. Never as an
 *   empty chart, which would read as "nothing matched".
 * - **empty** — the request succeeded, the role can see it, and there is
 *   genuinely nothing in the window. 
 *
 * <h2>Every chart is readable without seeing it</h2>
 *
 * Recharts draws an SVG full of `<path>` elements with no text alternative, so
 * a chart alone is invisible to a screen reader. CLAUDE.md makes accessibility
 * non-optional and blueprint §12.2 asks for ARIA labels on charts specifically.
 * So the drawing is marked `aria-hidden` and the same numbers are rendered
 * beside it as a visually-hidden table — a real `<table>`, which screen readers
 * can navigate by row and column, rather than an `aria-label` cramming twenty
 * numbers into one string nobody can re-read a part of.
 */

/**
 * The keys the server actually serves.
 *
 * It was narrower than the contract's enum on purpose from A-056 to A-059:
 * naming a key the server answered 404 for would have compiled and then
 * rendered an error card, and this way it did not compile at all.
 *
 * **A-058 closes that gap** — the four stage-flow keys are served, and this
 * type is now the contract's enum exactly. The guard has not stopped being
 * worth keeping: it is what a twenty-first widget declared in the contract
 * before it is built will run into.
 *
 * The server side of the same agreement is pinned by
 * `DashboardWidgetIT.everyContractKeyIsServed`, which reads the enum out of
 * `contracts/openapi.yaml` and fails if anything declared has no branch. This
 * union is narrowed by hand rather than generated, so widening it is the
 * deliberate step that says "the server answers this now" — and `FULL_KEYS` in
 * `DashboardWidgets` is checked against it with `satisfies`, so a key rendered
 * without being declared here does not compile.
 */
export type WidgetKey =
  | 'type-donut'
  | 'daily-stacked'
  | 'velocity'
  | 'resource-load'
  | 'priority-bar'
  | 'aging-buckets'
  | 'calendar-heatmap'
  | 'sla-gauge'
  | 'project-treemap'
  | 'client-volume'
  // A-058 · §S-05 widgets 16–19, the four the Workflow Ribbon unlocks.
  | 'stage-funnel'
  | 'rework'
  | 'stage-duration'
  | 'handoff-latency'

export interface WidgetPoint {
  x: string
  y: number
  drillDown: string | null
}

export interface WidgetSeries {
  name: string
  points: WidgetPoint[]
}

/**
 * What a frame needs to be draggable, supplied by whoever owns the order.
 *
 * The frame draws the controls and knows nothing about what a move means —
 * `DashboardWidgets` holds the drag state and the store holds the order. That
 * split is what keeps this component renderable in isolation (Storybook, the
 * existing tests) by simply not passing the prop.
 */
export interface WidgetReorderControls {
  /** 1-based position among the widgets actually drawn, for the control labels. */
  position: number
  total: number
  /** Undefined at the ends of the list, which is what disables the button. */
  onMoveUp?: () => void
  onMoveDown?: () => void
  onDragStart: () => void
  onDragEnd: () => void
  onDropHere: () => void
  /** The pointer has entered or left this card mid-drag. */
  onDragOverHere: () => void
  onDragLeaveHere: () => void
  /** This frame is the one being dragged. */
  isDragging: boolean
  /** Some frame is being dragged, so this one should accept a drop. */
  isDragActive: boolean
  /** The pointer is over this card and releasing would drop here. */
  isDropTarget: boolean
}

export interface WidgetFrameProps {
  widgetKey: WidgetKey
  title: string
  /** Project and date filters, straight from the page's URL state. */
  params: GetDashboardWidgetParams
  /** What the x axis holds, for the hidden table's first column header. */
  categoryLabel: string
  /** Drawn only when there is something to draw. */
  children: (series: WidgetSeries[]) => React.ReactNode
  /** Spans two columns in the grid — the wide time-series widgets. */
  wide?: boolean
  /** Omitted where the frame is not arrangeable — Storybook, and every test that predates it. */
  reorder?: WidgetReorderControls
}

/** Shared so a skeleton, a notice and a drawing all occupy the same box and nothing reflows. */
export const CHART_HEIGHT = 240

/**
 * The recharts drawing, hidden from assistive technology.
 *
 * An SVG of `<path>` elements has no text alternative and cannot be focused, so
 * exposing it produces either silence or a stream of "graphics-symbol". The
 * numbers reach a screen reader through `WidgetFrame`'s hidden table and the
 * destinations through `ChartLegend` — both of which sit outside this box.
 */
export function ChartCanvas({ children }: { children: React.ReactNode }) {
  return (
    /*
      `overflow-clip` is the second half of the sideways-scrolling fix, and it
      is the half that holds when the first one is not enough.
      `min-w-0` on the grid item (see `WidgetFrame` below) lets the *column*
      shrink — but it does nothing about an SVG that has already been painted
      wider than this box. Recharts measures on mount and again through a
      ResizeObserver, and a measurement taken before the shell has settled
      leaves an `<svg width="1486">` sitting inside a 600px widget. Nothing is
      drawn out there — the bars and axes are all at the left — so the page
      shows no wider content, it just scrolls as if it had some, and the
      heading and the first KPI card slide off the left edge.

      This box is a fixed-height, `aria-hidden` decorative frame. Nothing it
      contains has any business painting outside it, so clipping is the honest
      description of what it is rather than a patch over the symptom.

      `overflow-clip` rather than `overflow-hidden`, for the reason AppShell
      records at the shell root: both clip identically, but `hidden` also
      establishes a scroll container, and the browser's native focus-scroll
      walks up to the nearest one. `clip` establishes nothing to find.
    */
    <div className="overflow-clip" style={{ height: CHART_HEIGHT }} aria-hidden="true">
      {children}
    </div>
  )
}

export function WidgetFrame({
  widgetKey,
  title,
  params,
  categoryLabel,
  children,
  wide = false,
  reorder,
}: WidgetFrameProps) {
  const { data, isPending, isError } = useWidget(widgetKey, params)

  const series = normalise(data?.series)
  const unavailable = data?.unavailableReason
  const hasPoints = series.some((s) => s.points.length > 0)

  return (
    <section
      // `draggable` lives on the grip, not here. A panel this size marked
      // draggable would swallow ordinary gestures inside it — selecting a figure
      // to copy, dragging across a chart that shows a tooltip — and the legend's
      // drill-down links have native drag behaviour of their own that it would
      // override. The gesture starts on one small control; the whole card is
      // still what accepts a drop, which is why these handlers are here and
      // `dragstart` is caught on its way up rather than bound below.
      onDragStart={(e) => {
        if (!reorder) return
        // Firefox starts no drag at all unless something is on the dataTransfer.
        e.dataTransfer.setData('text/plain', widgetKey)
        e.dataTransfer.effectAllowed = 'move'
        // Without this the drag preview is the grip icon alone — a few pixels
        // that say nothing about what is being moved. Offset so the card sits
        // under the cursor where it was picked up rather than jumping.
        const card = e.currentTarget
        const box = card.getBoundingClientRect()
        e.dataTransfer.setDragImage(card, e.clientX - box.left, e.clientY - box.top)
        reorder.onDragStart()
      }}
      onDragEnd={() => reorder?.onDragEnd()}
      onDragOver={(e) => {
        // Only while one of our own frames is in flight. Without the guard this
        // card would advertise itself as a drop target for files dragged in from
        // the desktop, and then silently do nothing with them.
        if (!reorder?.isDragActive) return
        e.preventDefault()
        reorder.onDragOverHere()
      }}
      onDragLeave={() => reorder?.onDragLeaveHere()}
      onDrop={(e) => {
        if (!reorder?.isDragActive) return
        e.preventDefault()
        reorder.onDropHere()
      }}
      data-widget-key={widgetKey}
      // `relative` is load-bearing, not cosmetic. The hidden data table below
      // carries `sr-only`, which is `position: absolute` — and an absolutely
      // positioned element resolves against its nearest *positioned* ancestor.
      // With none, that is the initial containing block, which means the table
      // is not clipped by AppShell's scrolling `<main>` at all: it escapes to
      // document coordinates, lands at its static position — for the lower
      // widgets, far below the fold — and stretches the document to reach it.
      //
      // The symptom is two scrollbars on the dashboard and a page that scrolls
      // well past the end of the app shell into empty space. Positioning the
      // section puts the table back inside the scroller where it belongs.
      // `min-w-0` is the other half of the sideways-scrolling fix, and it is
      // as load-bearing as the `relative` above. A grid item's `min-width`
      // defaults to `auto`, so it refuses to shrink below its own min-content
      // width — and `ResponsiveContainer` measures this box, then renders an
      // `<svg width="712">` inside it. That SVG is replaced content with an
      // intrinsic width, so from the second render onwards the column's floor
      // *is* whatever width the chart last happened to be drawn at, and the
      // grid can only ever grow from there.
      //
      // `min-width: 0` decouples the used width from min-content, which is
      // what lets the container's ResizeObserver re-measure smaller and redraw
      // the SVG to fit. `ChartCanvas` clips whatever is still too wide while
      // that settles.
      className={`relative min-w-0 rounded-card border border-[color:var(--border)] bg-[color:var(--bg-surface)] p-4
                  flex flex-col gap-3 ${wide ? 'xl:col-span-2' : ''}
                  ${reorder?.isDragging ? 'opacity-50' : ''}
                  ${
                    // Exactly one card is marked at a time — the one the pointer
                    // is actually over. Ringing every card that *could* take a
                    // drop marks thirteen of them and tells the reader nothing
                    // about where releasing would put it.
                    reorder?.isDropTarget ? 'ring-2 ring-[color:var(--primary)]' : ''
                  }`}
      aria-labelledby={`widget-${widgetKey}-title`}
    >
      <div className="flex items-start gap-2">
        {reorder && <WidgetDragHandle title={title} />}
        <h2
          id={`widget-${widgetKey}-title`}
          className="flex-1 text-sm font-semibold text-[color:var(--text-primary)]"
        >
          {title}
        </h2>
        {reorder && <WidgetMoveButtons title={title} reorder={reorder} />}
      </div>

      {isPending ? (
        <Skeleton className="w-full" style={{ height: CHART_HEIGHT }} />
      ) : isError ? (
        <WidgetNotice tone="error">
          This chart could not be loaded. The other figures on this page are unaffected.
        </WidgetNotice>
      ) : unavailable ? (
        // The server's own sentence. Rephrasing it here would put the
        // explanation of a schema limitation in two places, and the frontend's
        // copy would be the one that went stale.
        <WidgetNotice tone="muted">{unavailable}</WidgetNotice>
      ) : !hasPoints ? (
        <WidgetNotice tone="muted">
          Nothing to show for this filter and date range.
        </WidgetNotice>
      ) : (
        <>
          {/* Each chart marks its own drawing aria-hidden and renders a
              keyboard-reachable legend beside it. Hiding this whole block
              instead would take the legend — the only focusable route to the
              drill-downs — away from exactly the users who need it. */}
          {children(series)}
          <WidgetDataTable title={title} categoryLabel={categoryLabel} series={series} />
        </>
      )}
    </section>
  )
}

/**
 * The pointer affordance, and only that.
 *
 * `aria-hidden`, deliberately: HTML5 drag is unreachable by keyboard and
 * unavailable on touch, so announcing a grip would offer a screen-reader or
 * tablet user a control they cannot operate. The accessible route is
 * `WidgetMoveButtons` beside it, which does the same job and is not a lesser
 * path — it is the one that works everywhere.
 */
function WidgetDragHandle({ title }: { title: string }) {
  return (
    <span
      draggable
      aria-hidden="true"
      title={`Drag to move ${title}`}
      className="mt-0.5 cursor-grab select-none text-[color:var(--text-secondary)] active:cursor-grabbing"
    >
      <GripVertical className="h-4 w-4" />
    </span>
  )
}

/**
 * The keyboard path to the same reorder, and the only one on touch.
 *
 * Both buttons are always rendered and disabled at the ends rather than removed,
 * so the control row does not change width as a widget travels up the grid and
 * the next card does not shift under a pointer aiming at it.
 *
 * The labels name the widget and its position because these buttons repeat
 * fourteen times on one screen: "Move up" alone, read out of context, is
 * fourteen identical controls.
 */
function WidgetMoveButtons({
  title,
  reorder,
}: {
  title: string
  reorder: WidgetReorderControls
}) {
  const buttonClass =
    'rounded-control px-1.5 py-0.5 text-[color:var(--text-secondary)] hover:bg-[color:var(--bg-subtle)] ' +
    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[color:var(--primary)] ' +
    'disabled:cursor-not-allowed disabled:opacity-40'

  return (
    <span className="flex shrink-0 items-center gap-0.5">
      <button
        type="button"
        className={buttonClass}
        disabled={!reorder.onMoveUp}
        aria-label={`Move ${title} earlier (currently ${reorder.position} of ${reorder.total})`}
        onClick={reorder.onMoveUp}
      >
        <ChevronUp className="h-4 w-4" />
      </button>
      <button
        type="button"
        className={buttonClass}
        disabled={!reorder.onMoveDown}
        aria-label={`Move ${title} later (currently ${reorder.position} of ${reorder.total})`}
        onClick={reorder.onMoveDown}
      >
        <ChevronDown className="h-4 w-4" />
      </button>
    </span>
  )
}

function WidgetNotice({
  tone,
  children,
}: {
  tone: 'error' | 'muted'
  children: React.ReactNode
}) {
  return (
    <p
      className={`text-xs ${
        tone === 'error'
          ? 'text-[color:var(--danger-text)]'
          : 'text-[color:var(--text-secondary)]'
      }`}
      style={{ minHeight: CHART_HEIGHT / 4 }}
      // Announced when it replaces a chart that was there a moment ago —
      // changing a filter and getting a sentence instead of a drawing is a
      // change a sighted user sees and nobody else would.
      role="status"
    >
      {children}
    </p>
  )
}

/**
 * The chart, as a table, for anybody not looking at it.
 *
 * One column per series so a stacked chart reads the way it is drawn — the
 * three segments of one bar on one row, rather than three separate lists the
 * reader has to hold in their head and re-associate by category name.
 */
function WidgetDataTable({
  title,
  categoryLabel,
  series,
}: {
  title: string
  categoryLabel: string
  series: WidgetSeries[]
}) {
  // Categories in first-appearance order across every series, so a resource
  // present in "Delayed" but absent from "In progress" still gets a row rather
  // than being dropped because the first series did not mention it.
  const categories: string[] = []
  for (const s of series) {
    for (const point of s.points) {
      if (!categories.includes(point.x)) {
        categories.push(point.x)
      }
    }
  }

  return (
    <table className="sr-only">
      <caption>{title}</caption>
      <thead>
        <tr>
          <th scope="col">{categoryLabel}</th>
          {series.map((s) => (
            <th key={s.name} scope="col">
              {s.name}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {categories.map((category) => (
          <tr key={category}>
            <th scope="row">{category}</th>
            {series.map((s) => (
              <td key={s.name}>{s.points.find((p) => p.x === category)?.y ?? 0}</td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  )
}

/**
 * The generated types make every field optional — the contract declares no
 * `required` inside `data`, so `x`, `y` and `name` all arrive as possibly
 * undefined. Narrowed once, here, rather than with a `??` at every point of
 * use in six chart components, where the day one is forgotten is the day a
 * bar renders at `NaN` pixels and silently disappears.
 */
function normalise(
  series: { name?: string; points?: { x?: unknown; y?: number; drillDown?: string | null }[] }[] | undefined,
): WidgetSeries[] {
  if (!series) {
    return []
  }
  return series.map((s) => ({
    name: s.name ?? '',
    points: (s.points ?? []).map((p) => ({
      x: String(p.x ?? ''),
      y: p.y ?? 0,
      drillDown: p.drillDown ?? null,
    })),
  }))
}
