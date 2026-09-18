import type { ObDashboardCard, ObDashboardCardKey } from '@/api/generated/model'

/**
 * B-121 · how OB-02's seven cards are labelled and read.
 *
 * Here rather than inside the page for the reason `obNotificationQueries.ts`
 * exists: B-127 adds the slide-over behind these cards and B-128 opens the same
 * panel from the workload grid's cells, so "what does `at-risk` mean" is about
 * to have three readers. Two copies of that answer is how they come to disagree
 * on the one screen where a user sees both at once.
 */

interface CardLook {
  label: string
  /** What the number is *of*, for the accessible name. The label alone is a heading. */
  unit: string
  /**
   * The short sentence under the number — the mockup's `.kpi .d` line. Static
   * copy rather than a second server field: every one of these is true of the
   * card by definition (an overdue client is, by definition, one that needs
   * action), so nothing here can drift from what the count already means.
   */
  caption: string
  /**
   * Whether a rise is bad. Not "is a rise good" — several of these are neither,
   * and colouring every rise green would congratulate somebody on their backlog
   * growing. `KpiCard`'s DeltaBadge makes the same call one module over and
   * states direction without judging it; this only decides whether the tile
   * takes a warning accent at all.
   */
  tone: 'neutral' | 'warning' | 'danger'
}

/**
 * Keyed by the contract's wire tokens, and the object's key order is the
 * board's order — the server sends all seven "in `ObDashboardCardKey` order"
 * and this renders them in the order received, so this map is a lookup rather
 * than a layout.
 */
const LOOK: Record<ObDashboardCardKey, CardLook> = {
  'ongoing-projects': {
    label: 'Ongoing projects', unit: 'journeys in progress', tone: 'neutral',
    caption: 'clients being onboarded',
  },
  'this-weeks-deadlines': {
    label: "This week's deadlines", unit: 'items due Mon–Sun', tone: 'neutral',
    caption: 'client tasks due Mon–Sun',
  },
  'todays-delivery': {
    label: "Today's delivery", unit: 'items due today', tone: 'neutral',
    caption: 'services due today',
  },
  'overdue-clients': {
    label: 'Overdue clients', unit: 'clients past a date', tone: 'danger',
    caption: 'past a due date — need action now',
  },
  live: {
    label: 'Live', unit: 'clients live', tone: 'neutral',
    caption: 'fully onboarded',
  },
  'at-risk': {
    label: 'At risk', unit: 'journeys amber or red', tone: 'warning',
    caption: 'close to a service TAT limit',
  },
  'client-escalations': {
    label: 'Client escalations', unit: 'clients with an open escalation', tone: 'danger',
    caption: 'clients escalated from the portal',
  },
}

/**
 * Cards the board receives and draws for a platform Admin alone.
 *
 * `at-risk` and `client-escalations` are the two figures the module is
 * *managed* by rather than worked from: one is the amber/red pipeline somebody
 * triages across every implementor, the other is what a client has escalated
 * past the team handling them. Neither is a number the person doing the
 * onboarding acts on, and both invite a reading of somebody else's work.
 *
 * Filtered here rather than asked for differently: `GET /onboarding/dashboard/
 * summary` sends all seven in `ObDashboardCardKey` order, that shape is the
 * contract, and the drill-over still resolves either key through {@link cardLook}
 * if anything links to one. **This is a display decision and it stays on the
 * display side** — `ObModuleRoleFilter` and the module's own scoping are what
 * refuse data a caller may not have, exactly as `Sidebar`'s `adminOnly` rows
 * decide what is easy to find and never what is permitted.
 *
 * `live` used to be filtered here too, on the argument that a count of finished
 * clients is not a call to action. It is drawn again for everybody: it is the
 * one card that answers "how much of this has actually landed", and the row
 * holds it now that two cards come off it for most readers.
 */
const ADMIN_ONLY_KEYS: ReadonlySet<string> = new Set<ObDashboardCardKey>([
  'at-risk',
  'client-escalations',
])

/**
 * The cards the board draws, in the order the server sent them.
 *
 * Unknown keys survive on purpose — the same tolerance {@link cardLook} has. A
 * server newer than this bundle should add a card to the row, not have it
 * silently dropped by a filter that only knows last month's enum.
 */
export function visibleCards<T extends { key: string }>(
  cards: readonly T[],
  isAdmin: boolean,
): T[] {
  return cards.filter((card) => isAdmin || !ADMIN_ONLY_KEYS.has(card.key))
}

/**
 * How many tiles the row is expected to hold — what the loading skeleton draws,
 * so nothing reflows when the real numbers land. Derived from the maps above
 * rather than typed as a literal: hiding a card would otherwise leave a
 * skeleton a tile too wide until somebody noticed.
 *
 * It takes `isAdmin` for the same reason {@link visibleCards} does. A
 * non-admin's skeleton drawing seven tiles would reflow to five the moment the
 * figures arrived, which is the single thing the fixed count exists to prevent.
 */
export function visibleCardCount(isAdmin: boolean): number {
  return Object.keys(LOOK).filter((key) => isAdmin || !ADMIN_ONLY_KEYS.has(key)).length
}

/**
 * The onboarding roles the project board and its four tabs are drawn for:
 * <b>OB_ADMIN</b>, <b>OB_MANAGER</b> and <b>OB_STEP_OWNER</b> — the admin, the
 * implementor manager and the implementor.
 *
 * <p>An implementor and their manager were shown the plain counter row and
 * nothing else, because the strip was gated on the <em>platform</em> `ADMIN`
 * role. That was a stand-in: the division this screen wants is an onboarding
 * one, and the page's own note said so and recorded exposing `moduleRoles` on
 * `Me` as the change that would let it switch over. It has since been exposed —
 * `POST /auth/login` returns it and `Sidebar` already reads it — so this is
 * that switch.
 *
 * <p>OB_SALES and OB_VIEWER stay on the counter row. Neither delivers work:
 * the four tabs are cuts of who is delivering what and when, which is a
 * question about somebody else's week for both of them.
 *
 * <h2>Why widening this leaks nothing</h2>
 *
 * <p>Every route behind the board and its tabs takes `CallerIdentity` and
 * derives its own scope from it server-side — `ObDashboardScope` for the
 * summary, the card items, the delayed grid and the workload grid,
 * `ObClientScope` for the project board. An OB_STEP_OWNER is narrowed to
 * "journeys containing their steps" by `OnboardingScopeResolver`, and the
 * figures come from `ob_scope_dashboard_summary`, which is keyed by
 * `scope_user_id` for exactly this. So an implementor opening these tabs is
 * served their own rows and nobody else's, and this predicate decides which
 * screen is *offered* — never what is permitted, which is the same bargain
 * {@link visibleCards} and `Sidebar`'s `adminOnly` make.
 *
 * <p><b>Not a client-side filter, and it must never become one.</b> CLAUDE.md
 * forbids narrowing rows in the frontend; if a tab ever shows a reader
 * somebody else's work, the fix belongs in that route's scope and not here.
 *
 * <h2>Completed projects are already out</h2>
 *
 * <p>Nothing here has to exclude them. `ObRunningProjectReader` asks for
 * status `RUNNING`, and the delayed grid and the stuck table are both built on
 * `JOURNEY_IS_RUNNING` — so every figure on the board is about work in flight
 * before it reaches this screen.
 */
const BOARD_ROLES: ReadonlySet<string> = new Set([
  'OB_ADMIN',
  'OB_MANAGER',
  'OB_STEP_OWNER',
])

/**
 * Whether this reader gets the project board and the four tabs.
 *
 * <p><b>Additive.</b> A platform `ADMIN` keeps the board whatever their
 * onboarding grant says, which is what `isPlatformAdmin` is for. The gate used
 * to be that test alone, and a platform admin who happens to hold no
 * `ONBOARDING` module role would otherwise have lost the screen they have
 * today — a regression dressed up as a permission change. The three onboarding
 * roles are added beside it, not in place of it.
 *
 * <p>Takes two plain values rather than the whole `Me` so the page keeps its
 * `useAuthStore` selectors narrow and this stays a pure function a test can
 * drive with strings.
 */
export function seesProjectBoard(
  onboardingRole: string | undefined | null,
  isPlatformAdmin: boolean,
): boolean {
  return isPlatformAdmin || (onboardingRole != null && BOARD_ROLES.has(onboardingRole))
}

/**
 * How one card is labelled.
 *
 * **Tolerant, on purpose.** The key is a closed enum in the contract, so an
 * unknown one means a server newer than this bundle — and a board that threw on
 * it would give back the whole screen to gain nothing. An unrecognised card
 * renders with its own token as the label and no accent.
 */
export function cardLook(key: string): CardLook {
  return LOOK[key as ObDashboardCardKey] ?? { label: key, unit: '', caption: '', tone: 'neutral' }
}

/**
 * The delta, as an arrow and a signed number, or null when there is none.
 *
 * Null rather than "0" when `deltaFromYesterday` is absent: the server sends
 * null on the first day a deployment has data, and drawing "→ 0" there is a
 * claim that nothing moved on a day there was nothing to move from.
 *
 * The comparison is against the previous **stored** day, which may not be
 * yesterday — the server reads the two most recent days the summary table
 * holds, so a worker outage makes this a two-day comparison rather than no
 * comparison. That is why the wording below says "the previous day" and not
 * "yesterday".
 */
export function describeDelta(delta: number | null | undefined): {
  arrow: string
  magnitude: number
  /** Spoken form. The glyph alone reads as "black up-pointing triangle", which is noise. */
  spoken: string
} | null {
  if (delta == null) return null
  const arrow = delta === 0 ? '→' : delta > 0 ? '▲' : '▼'
  const direction = delta === 0 ? 'unchanged' : delta > 0 ? 'up' : 'down'
  const magnitude = Math.abs(delta)
  return {
    arrow,
    magnitude,
    spoken:
      delta === 0
        ? 'unchanged since the previous day'
        : `${direction} ${magnitude} since the previous day`,
  }
}

/**
 * The accessible name for one tile — everything the sighted reader gets from
 * the tile's layout, in one sentence.
 *
 * A screen-reader user tabbing this row otherwise hears seven identical
 * "button" announcements and no values, which is `KpiCard`'s own note. The
 * caveat and the delta are in here as well as on screen, because they are the
 * two things that change what the number *means*.
 */
export function cardAccessibleName(card: ObDashboardCard): string {
  const { label, unit } = cardLook(card.key)

  if (card.unavailableReason) {
    return `${label}: unavailable. ${card.unavailableReason}`
  }

  const parts = [`${label}: ${card.countIsUpperBound ? 'at most ' : ''}${card.count}`]
  if (unit) parts.push(unit)

  const delta = describeDelta(card.deltaFromYesterday)
  if (delta) parts.push(delta.spoken)
  if (card.countIsUpperBound) {
    parts.push('an upper bound, because clients who bought several products are counted once each')
  }
  return `${parts.join(', ')}.`
}
