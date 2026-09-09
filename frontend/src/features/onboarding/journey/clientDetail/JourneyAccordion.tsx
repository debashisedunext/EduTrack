import * as React from 'react'

import type { ObJourneyStrip, UserRef } from '@/api/generated/model'
import { useGetObJourney } from '@/api/generated/onboarding-journeys/onboarding-journeys'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'

import { JourneyRibbonStrip } from '../ribbon/JourneyRibbonStrip'
import { ObAccordion } from './ObAccordion'
import { JourneyStepPanel } from './JourneyStepPanel'
import { StepDotStrip } from './StepDotStrip'
import { formatTatUsage, journeyHold, ragLabel, ragVariant, tatUsage } from './journeyStrip'
import { focusStepId, toRibbonSteps, type ResolveUser } from './ribbonSteps'

/**
 * C-110 · one product's accordion on OB-05 — §9's "journey accordions (strip:
 * product, % complete, RAG step dots, and TAT `used / total` with overrun
 * highlighting; expanded: ribbon + step panel)".
 *
 * ## Two reads, and the second one only when it is asked for
 *
 * The strip comes from `ObJourneyStrip`, which arrives inside the client
 * document `ObClientDetailPage` already has. The ribbon comes from
 * `GET /onboarding/journeys/{journeyId}`, fired on expand and not before —
 * the contract's own reason for splitting them ("a client with six journeys
 * does not pay for six ribbons on first paint"), and the reason this component
 * takes `isOpen` as a prop rather than owning it: React Query's `enabled` needs
 * to see the open state, and the page needs it to keep more than one accordion
 * open at once.
 *
 * ## `heldByJourneyId` is not a gate and is not a colour
 *
 * A journey can be past the client's prerequisite gate and still held behind a
 * sibling that has to finish first (plan §5.5). Both draw as "not running", and
 * the strip says which — see `journeyHold`. Merging them would leave a reader
 * clearing prerequisites that were already cleared.
 */
export interface JourneyAccordionProps {
  journey: ObJourneyStrip
  isOpen: boolean
  onToggle: () => void
  /** Sibling journeys on the same client, for naming what holds this one.
   * A held journey that can only say "held by #2" is a screen that has sent
   * the reader to look up an id. */
  siblings: readonly ObJourneyStrip[]
  users: readonly UserRef[]
  /**
   * C-112 · the client this journey belongs to. `ObJourneyStrip` does not
   * carry it — the strip is always read in the context of one client, so the
   * contract does not repeat the id on every row — and the step panel's
   * communications timeline needs it to refresh the client-level stitched
   * view when an entry is recorded. Passed down rather than re-fetched.
   */
  obClientId: number
}

export function JourneyAccordion({
  journey,
  isOpen,
  onToggle,
  siblings,
  users,
  obClientId,
}: JourneyAccordionProps) {
  const [selectedStepId, setSelectedStepId] = React.useState<string | null>(null)

  const detail = useGetObJourney(journey.id, { query: { enabled: isOpen } })

  const resolveUser = React.useCallback<ResolveUser>(
    (userId) => users.find((u) => u.id === userId),
    [users],
  )

  const steps = detail.data?.data?.steps
  const ribbonSteps = React.useMemo(() => toRibbonSteps(steps, resolveUser), [steps, resolveUser])

  // A reader who expands an accordion wants the journey's state, and making
  // them click a tile to get it would be an empty panel asking a question the
  // page can already answer. `focusStepId` is deliberately not "the CURRENT
  // step" — see its docstring: the ordinary journey has none.
  const activeStepId = selectedStepId ?? focusStepId(ribbonSteps)
  const activeStep = steps?.find((s) => String(s.id) === activeStepId)

  const usage = tatUsage(journey)
  const hold = journeyHold(journey)
  const heldBy = hold === 'HELD_BY_SIBLING'
    ? siblings.find((s) => s.id === journey.heldByJourneyId)
    : undefined

  const productName = journey.product?.name ?? 'Product'

  return (
    <ObAccordion
      id={`ob-journey-${journey.id}`}
      isOpen={isOpen}
      onToggle={onToggle}
      label={`${productName} — ${journey.percentComplete}% complete, ${ragLabel(journey.rag).toLowerCase()}`}
      summary={
        <>
          {/* The mockup's `jstrip` order: name, the template caption, the
              status chip, TAT, then dots and percent pushed to the right. */}
          <span className="min-w-0 truncate text-sm font-semibold text-content">{productName}</span>

          {/* `ObJourneyStrip` carries no template name or pinned version —
              only the expanded detail read does — so the collapsed caption
              states what the strip knows: how many services the journey has. */}
          <span className="shrink-0 text-caption text-content-muted">
            {(journey.steps ?? []).length} services
          </span>

          {hold === 'GATE_LOCKED' ? (
            <Chip variant="neutral">Prerequisites pending</Chip>
          ) : hold === 'HELD_BY_SIBLING' ? (
            <Chip variant="info">Held for {heldBy?.product?.name ?? 'another service'}</Chip>
          ) : (
            journey.rag && <Chip variant={ragVariant(journey.rag)}>{ragLabel(journey.rag)}</Chip>
          )}

          {/*
            Overrun highlighted red and ≥75% amber — §10, and the figure is the
            server's `utilizedHours` rather than anything summed here, on that
            section's own "never a stored aggregate that can disagree with its
            parts" rule read the other way round.
          */}
          {usage ? (
            <span
              className={cn(
                'shrink-0 text-caption tabular-nums',
                usage.band === 'over'
                  ? 'font-semibold text-danger-text'
                  : usage.band === 'amber'
                    ? 'font-semibold text-warning-text'
                    : 'text-content-muted',
              )}
            >
              {formatTatUsage(usage)}
            </span>
          ) : (
            <span className="shrink-0 text-caption text-content-muted">No TAT budget</span>
          )}

          <span className="min-w-0 flex-1" aria-hidden="true" />

          <StepDotStrip steps={journey.steps ?? []} />

          <span className="shrink-0 text-sm tabular-nums text-content-muted">
            {journey.percentComplete}%
          </span>
        </>
      }
    >
      {detail.isPending ? (
        <div className="flex gap-2" aria-label="Loading services" role="status">
          {Array.from({ length: 4 }, (_, i) => (
            <Skeleton key={i} className="h-28 w-40" />
          ))}
        </div>
      ) : detail.isError ? (
        <EmptyState
          title="The services could not be loaded"
          description="Collapse and expand this journey to try again."
        />
      ) : (
        <>
          {/* The mockup's ribbon header line: eyebrow + working-calendar
              caption, with the pinned template version the strip could not
              show while collapsed — the detail read carries it. */}
          <div className="mb-2 flex flex-wrap items-center gap-2">
            <span className="text-caption font-semibold uppercase tracking-wide text-content-muted">
              Journey ribbon
            </span>
            <span className="text-caption text-content-muted">
              TATs in working days (weekends &amp; holidays excluded)
              {detail.data?.data && ` · template v${detail.data.data.templateVersion}`}
            </span>
          </div>
          <div className="rounded-card border border-border bg-surface p-3">
            <JourneyRibbonStrip
              steps={ribbonSteps}
              selectedStepId={activeStepId ?? undefined}
              onSelectStep={(step) => setSelectedStepId(step.id)}
            />
          </div>
          {activeStep && (
            <JourneyStepPanel
              step={activeStep}
              totalSteps={steps?.length}
              resolveUser={resolveUser}
              hold={hold}
              obClientId={obClientId}
            />
          )}
        </>
      )}
    </ObAccordion>
  )
}
