// B-027 · the `React` import is gone, not unused-suppressed. `jsx: react-jsx`
// means nothing here referenced it and `noUnusedLocals` fails the build on it.
// **Stream A's file, flagged.**
import { Link } from 'react-router-dom'

import { Skeleton } from '@/components/ui/skeleton'

import { Sparkline } from './Sparkline'
import { useCountUp } from './useCountUp'
import { useDrillDownStore } from './drillDownStore'

/**
 * A-055 · widgets 1–6 of §S-05.
 *
 * <h2>The whole card is the link</h2>
 *
 * §S-05: "every card and every chart segment is clickable and deep-links to a
 * pre-filtered ticket list". The target comes from the server — see
 * `DashboardService.cards` — so the filter that produced the number and the
 * filter the list applies are the same string. A card that computed its own
 * link would be a second implementation of "which tickets is this counting",
 * and the day they disagree the user believes the list.
 *
 * Rendered as an anchor rather than a div with an onClick, so it is reachable
 * by keyboard, announced as a link, and openable in a new tab — the three
 * things a clickable div silently takes away.
 */
export interface KpiCardProps {
  label: string
  value: number
  /** Null when there is nothing to compare with — renders no delta at all. */
  deltaPct?: number | null
  sparkline?: number[]
  /** Server-built, e.g. `/tickets?level=CRITICAL&excludeClosed=true&from=…`. */
  drillDown: string
  /** §S-05 colours widgets 4 and 5; the rest are neutral. */
  tone?: 'neutral' | 'critical' | 'delayed'
}

const TONE_ACCENT: Record<NonNullable<KpiCardProps['tone']>, string> = {
  neutral: 'var(--primary)',
  critical: 'var(--danger)',
  delayed: 'var(--warning)',
}

export function KpiCard({
  label,
  value,
  deltaPct,
  sparkline = [],
  drillDown,
  tone = 'neutral',
}: KpiCardProps) {
  const shown = useCountUp(value)
  const accent = TONE_ACCENT[tone]
  const openPanel = useDrillDownStore((s) => s.open)

  return (
    <Link
      to={drillDown}
      /**
       * A-061 · a plain click opens §S-06's panel; anything else still navigates.
       *
       * The card stays an anchor. A-055 chose one over a clickable div for three
       * things a div silently removes — keyboard reach, the announced role, and
       * open-in-new-tab — and swapping to a button now to get a modal would
       * trade all three away for it.
       *
       * So the modifier and middle-click paths are left alone deliberately:
       * ctrl/cmd-click, shift-click and middle-click are how people open a
       * ticket list in a second tab, and intercepting them would break a
       * browser affordance nobody expects an app to take. Only the unmodified
       * primary click — the one that means "show me" rather than "take me
       * there" — is turned into the panel.
       */
      onClick={(event) => {
        if (event.defaultPrevented) return
        if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
        if (event.button !== 0) return
        event.preventDefault()
        // D-064 · `value`, not `shown`: useCountUp animates towards the
        // figure, so mid-animation `shown` is a number that was never the
        // answer to anything. The panel must show what the card means.
        openPanel(drillDown, label, value)
      }}
      className="rounded-card border border-[color:var(--border)] bg-[color:var(--bg-surface)] p-4
                 flex flex-col gap-2 transition-shadow hover:shadow-sm
                 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2
                 focus-visible:outline-[color:var(--primary)]"
      // The visible label is the metric name; the accessible name has to carry
      // the number too, or a screen-reader user tabbing the row hears six
      // identical "Critical, link" and no values.
      aria-label={`${label}: ${value}. Open the filtered ticket list.`}
    >
      <span className="text-sm text-[color:var(--text-secondary)]">{label}</span>

      <span className="flex items-baseline gap-2">
        <span
          className="text-2xl font-semibold tabular-nums text-[color:var(--text-primary)]"
          style={tone === 'neutral' ? undefined : { color: accent }}
        >
          {shown.toLocaleString()}
        </span>
        {deltaPct != null && <DeltaBadge deltaPct={deltaPct} />}
      </span>

      <Sparkline points={sparkline} stroke={accent} label={`${label} trend`} />
    </Link>
  )
}

/**
 * Up is not always good. More tickets closed is an improvement; more delayed is
 * not, and colouring every rise green would congratulate a team on its backlog
 * growing. So the badge states direction and leaves judgement to the reader —
 * the arrow and the sign, in one neutral colour.
 */
function DeltaBadge({ deltaPct }: { deltaPct: number }) {
  const rose = deltaPct > 0
  const flat = deltaPct === 0
  const arrow = flat ? '→' : rose ? '▲' : '▼'
  const magnitude = Math.abs(deltaPct).toFixed(1).replace(/\.0$/, '')

  return (
    <span
      className="text-xs text-[color:var(--text-secondary)] tabular-nums"
      // The glyph alone reads as "black up-pointing triangle" in a screen
      // reader, which is noise. The words carry the meaning instead.
      aria-label={`${flat ? 'unchanged' : rose ? 'up' : 'down'} ${magnitude} percent versus the previous period`}
    >
      <span aria-hidden="true">{arrow} </span>
      {magnitude}%
    </span>
  )
}

/** Shown while the first request is in flight — same footprint, so nothing reflows on arrival. */
export function KpiCardSkeleton() {
  return (
    <div className="rounded-card border border-[color:var(--border)] bg-[color:var(--bg-surface)] p-4 flex flex-col gap-2">
      <Skeleton className="h-4 w-24" />
      <Skeleton className="h-8 w-16" />
      <Skeleton className="h-7 w-24" />
    </div>
  )
}
