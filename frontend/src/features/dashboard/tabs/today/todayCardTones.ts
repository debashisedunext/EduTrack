export type FigureTone = 'neutral' | 'success' | 'warning' | 'danger'

/**
 * Which of a card's sub-figures reads as a problem, a caution or a clean
 * state — the colouring the prototype's `.v.red/.amb/.grn` classes carry.
 *
 * The contract sends no tone: `DashboardFigure` is only a value and a
 * drill-down. Keying by figure `key` alone is not enough either — the same
 * key means different things on different cards (`wip` is a neutral count
 * of everything in progress on Today's Work, but the late slice of it on
 * Overdue), so the map is keyed by card *and* figure, per the prototype's
 * own per-card colouring.
 */
const FIGURE_TONE: Record<string, Record<string, FigureTone>> = {
  'todays-work': { 'not-started': 'warning', 'on-time': 'success', overdue: 'danger' },
  overdue: { 'not-started': 'danger', wip: 'danger' },
  'not-started': { 'overdue-start': 'danger', 'due-today': 'warning' },
  wip: { 'updated-today': 'success', 'not-updated': 'warning' },
  'wip-breakdown': { 'near-delay': 'warning', delayed: 'danger', 'on-time': 'success' },
  blocked: { 'awaiting-info': 'warning' },
}

export function figureTone(cardKey: string, figureKey: string): FigureTone {
  return FIGURE_TONE[cardKey]?.[figureKey] ?? 'neutral'
}
