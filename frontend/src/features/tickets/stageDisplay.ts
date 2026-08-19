/**
 * `DEVELOPMENT` → `Development`. A display formatter, not a workflow-template
 * lookup — resolving a ticket's *real* stage display name means resolving
 * which workflow template it belongs to, which `list/README.md` documents as
 * a heavier problem the ticket list also declined to take on for its dropped
 * ribbon column. Good enough for a one-line "stamped to X" caption; not a
 * stand-in for the ribbon.
 */
/**
 * Segments that are acronyms, not words, and must survive title-casing intact.
 *
 * Seeded from the actual stage vocabulary — `SELECT DISTINCT stage_code FROM
 * workflow_stages` is `CLOSED DEPLOY DEV INTAKE QA SIGNOFF TRIAGE VERIFY`, and
 * `QA` is the only acronym in it. Deliberately an explicit list rather than a
 * rule about short or vowel-less words: `DEV` is an abbreviation that reads
 * correctly as `Dev`, so any clever heuristic would have to special-case it
 * back out again.
 *
 * Add to this when a workflow template introduces another one (`UAT` is the
 * likely next). Not pre-populated with acronyms nobody uses — a guess here
 * renders as fact on five screens.
 */
const ACRONYMS = new Set(['QA'])

export function titleCase(stageCode: string): string {
  return stageCode
    .split('_')
    .map((word) => {
      const upper = word.toUpperCase()
      if (ACRONYMS.has(upper)) return upper
      return upper.charAt(0) + word.toLowerCase().slice(1)
    })
    .join(' ')
}
