import * as React from 'react'

import { useGetDashboardWidget } from '@/api/generated/dashboard/dashboard'
import type { GetDashboardWidgetParams } from '@/api/generated/model'
import { Skeleton } from '@/components/ui/skeleton'

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

export type WidgetKey =
  | 'type-donut'
  | 'daily-stacked'
  | 'velocity'
  | 'resource-load'
  | 'priority-bar'
  | 'aging-buckets'

export interface WidgetPoint {
  x: string
  y: number
  drillDown: string | null
}

export interface WidgetSeries {
  name: string
  points: WidgetPoint[]
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
    <div style={{ height: CHART_HEIGHT }} aria-hidden="true">
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
}: WidgetFrameProps) {
  const { data, isPending, isError } = useGetDashboardWidget(widgetKey, params)

  const series = normalise(data?.data?.series)
  const unavailable = data?.data?.unavailableReason
  const hasPoints = series.some((s) => s.points.length > 0)

  return (
    <section
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
      className={`relative rounded-card border border-[color:var(--border)] bg-[color:var(--bg-surface)] p-4
                  flex flex-col gap-3 ${wide ? 'xl:col-span-2' : ''}`}
      aria-labelledby={`widget-${widgetKey}-title`}
    >
      <h2
        id={`widget-${widgetKey}-title`}
        className="text-sm font-semibold text-[color:var(--text-primary)]"
      >
        {title}
      </h2>

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
