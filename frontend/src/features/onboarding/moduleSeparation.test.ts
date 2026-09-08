import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'

/**
 * A-115 · the frontend half of "the two modules stay separable".
 *
 * ArchUnit holds the backend boundary (`ObModuleSeparationTest`) and cannot see
 * TypeScript, so the same rule needs saying twice — once per language. This is
 * the second half, and the one the backlog names explicitly: no import from
 * `components/ribbon`.
 *
 * ## The decision this locks was already made, by hand, and written down
 *
 * `features/onboarding/journey/ribbon/` is a second ribbon, built fresh. Its
 * own files say so — "Built fresh, not imported from `components/ribbon/`",
 * "solved again here rather than shared" — and its README carries the argument.
 * PHASE-2-BUILD-PLAN made that call because the two ribbons answer different
 * questions: a ticket's stage ribbon and a journey's service ribbon share a
 * shape and not a meaning, and the collapsed-grouping pass one needs is
 * precisely what makes the other wrong.
 *
 * So nothing here is being changed. What changes is that the decision stops
 * depending on the next person reading those comments before reaching for the
 * nearest existing component.
 *
 * ## Why a test rather than an ESLint rule
 *
 * `no-restricted-imports` with zones would express this too, and better in
 * principle — it fires in the editor rather than in CI. It is not used here
 * because the config carries no zone rules at all today, so adding the first
 * one means choosing a convention for every future boundary, and that is a
 * decision for whoever owns the lint config rather than a rider on this task.
 * A test that names its own reason is the smaller change, and it fails in the
 * same run as everything else.
 */
const ONBOARDING = join(__dirname)
const FORBIDDEN = [
  { pattern: /from\s+['"][^'"]*components\/ribbon/, what: 'components/ribbon' },
  { pattern: /from\s+['"][^'"]*features\/tickets/, what: 'features/tickets' },
  { pattern: /from\s+['"][^'"]*features\/transitions/, what: 'features/transitions' },
]

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) return sourceFiles(full)
    return /\.tsx?$/.test(entry) ? [full] : []
  })
}

describe('the onboarding module stays separable from ticketing', () => {
  it('imports nothing from the ticketing UI', () => {
    const offences: string[] = []

    for (const file of sourceFiles(ONBOARDING)) {
      const source = readFileSync(file, 'utf8')
      for (const { pattern, what } of FORBIDDEN) {
        if (pattern.test(source)) {
          offences.push(`${file.replace(ONBOARDING, 'features/onboarding')} imports ${what}`)
        }
      }
    }

    // Matched on `from '...'` rather than on the bare path, deliberately: every
    // current mention of `components/ribbon` under this directory is a comment
    // explaining why it is NOT imported, and a substring check would fail on
    // the very notes that document the decision.
    expect(offences).toEqual([])
  })

  it('finds files to check, so an empty pass is not a passing empty', () => {
    // The counterweight. A traversal that silently returned nothing would
    // satisfy the assertion above for ever, and a moved directory is exactly
    // how that happens.
    expect(sourceFiles(ONBOARDING).length).toBeGreaterThan(20)
  })
})
