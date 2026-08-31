/**
 * S-05 tab 3 · how a weekly figure is written out.
 *
 * Its own module rather than an export from `WeeklyCard`, because a file that
 * exports both components and plain functions breaks React fast refresh — the
 * lint rule that says so is an error here, and the separation is right anyway:
 * this is a pure function with its own tests and no need of React.
 *
 * <h2>The unit comes from the server</h2>
 *
 * Three of the four cards are counts; average progress is a percentage and
 * average delay is days. The card cannot infer which from the number — 42 is a
 * plausible value for all three — so `unit` is on the wire and this switches on
 * it. An unrecognised unit is treated as a count rather than throwing: a new
 * unit should render a slightly plain number, not blank the tab.
 */
export type WeeklyCardUnit = 'COUNT' | 'PERCENT' | 'DAYS'

export function formatValue(value: number, unit: WeeklyCardUnit | string): string {
  const rounded = Math.round(value * 10) / 10
  if (unit === 'PERCENT') return `${rounded}%`
  if (unit === 'DAYS') return `${rounded} ${rounded === 1 ? 'day' : 'days'}`
  // A count is a whole number even if the server sent a float — an average
  // dressed as a count is the server's bug to fix, not one to render as "19.6
  // tickets".
  return String(Math.round(value))
}

/**
 * Whether a rise in this figure is good news.
 *
 * Decided here rather than on the wire. A rise in average progress is good; a
 * rise in delayed tickets is not — but that is a purely visual concern, and
 * putting a sentiment field in the contract would make every future consumer
 * inherit this screen's opinion of its own numbers.
 *
 * Undefined for anything unlisted, which renders neutrally. A card added later
 * is uncoloured until somebody decides what its direction means, rather than
 * being guessed at.
 */
export const HIGHER_IS_BETTER: Readonly<Record<string, boolean>> = {
  'avg-progress': true,
  'delayed-vs-last-week': false,
  'avg-delay-days': false,
}
