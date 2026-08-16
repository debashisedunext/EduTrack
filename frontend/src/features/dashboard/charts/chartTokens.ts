/**
 * A-056 · the colours every widget 7–12 draws with.
 *
 * CLAUDE.md: **never introduce a colour that isn't a token.** Recharts wants a
 * literal per series, so the tokens are read here once and referenced by name
 * everywhere else — a `#4F46E5` typed into a chart component is exactly how a
 * palette stops being a palette.
 *
 * `var(--chart-N)` works directly as an SVG `fill`/`stroke`, so these stay as
 * custom-property references rather than being resolved to hex at build time.
 * That matters beyond tidiness: blueprint §12.1 is the single place the palette
 * changes, and a resolved hex would not follow it.
 */

/** Colour-blind safe, blueprint §12.1. Eight, and the donut can exceed that — see `categorical`. */
const CHART_PALETTE = [
  'var(--chart-1)',
  'var(--chart-2)',
  'var(--chart-3)',
  'var(--chart-4)',
  'var(--chart-5)',
  'var(--chart-6)',
  'var(--chart-7)',
  'var(--chart-8)',
] as const

/**
 * The palette has eight entries and §S-05's donut draws eleven task types, so
 * it wraps. Deliberately, and worth stating: the alternative is generating
 * colours beyond the token set, which breaks the one palette rule and produces
 * values nobody checked for contrast or colour-blind separation.
 *
 * A repeated colour is legible here because the donut carries a legend keyed by
 * name and the hidden data table carries every figure — colour is never the
 * only signal, which is the same rule the ribbon follows.
 */
export function categorical(index: number): string {
  return CHART_PALETTE[index % CHART_PALETTE.length]
}

/** Widget 11 draws severity, and severity has its own tokens. Never the generic palette. */
export const LEVEL_COLOURS: Record<string, string> = {
  Low: 'var(--level-low)',
  Medium: 'var(--level-medium)',
  High: 'var(--level-high)',
  Critical: 'var(--level-critical)',
}

/**
 * Widget 8's three flows and widget 10's three states.
 *
 * Delayed is the warning token and not the danger one: §S-05 colours widget 5
 * amber, and using red here would give the dashboard two different colours for
 * "delayed" on one screen.
 */
export const FLOW_COLOURS: Record<string, string> = {
  Created: 'var(--chart-1)',
  Closed: 'var(--success)',
  Reopened: 'var(--warning)',
}

export const LOAD_COLOURS: Record<string, string> = {
  Open: 'var(--chart-2)',
  'In progress': 'var(--chart-1)',
  Delayed: 'var(--warning)',
}

/** Axis, grid and tick styling, so six charts do not each invent their own. */
export const AXIS = {
  stroke: 'var(--border)',
  tick: { fill: 'var(--text-secondary)', fontSize: 11 },
} as const

export const GRID_STROKE = 'var(--border)'

export const TOOLTIP_STYLE = {
  backgroundColor: 'var(--bg-surface)',
  border: '1px solid var(--border)',
  borderRadius: 8,
  fontSize: 12,
  color: 'var(--text-primary)',
} as const
