import * as React from 'react'

import type { ObJourneyStepView, ObJourneyStrip, UserRef } from '@/api/generated/model'
import { useGetObJourney } from '@/api/generated/onboarding-journeys/onboarding-journeys'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'

import { JourneyRibbonStrip } from '../ribbon/JourneyRibbonStrip'
import { ObAccordion } from './ObAccordion'
import { JourneyStepPanel } from './JourneyStepPanel'
import { SignoffPanel } from './SignoffPanel'
import { StepDotStrip } from './StepDotStrip'
import { formatTatUsage, journeyHold, ragLabel, ragVariant, tatUsage } from './journeyStrip'
import { productIcon } from './productIcon'
import { focusStepId, toRibbonSteps, type ResolveUser } from './ribbonSteps'

/**
 * C-110 · one **Module Service's** accordion on OB-05 — §9's "journey
 * accordions (strip: product, % complete, RAG step dots, and TAT
 * `used / total` with overrun highlighting; expanded: ribbon + step panel)".
 *
 * ## The strip is titled with the service, not the product
 *
 * A product publishes several services at once and a client who bought it is
 * boarded through each of them, one journey — one ribbon — each. Two strips
 * both headed "EduTrack ERP" would be two identical rows carrying different
 * progress, so `serviceName` is the heading and the product is the caption
 * underneath it.
 *
 * `serviceName` is the journey's **pinned** service, matching the tasks in
 * the ribbon rather than whatever the catalogue has been renamed to since.
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
  /** C-126 · forwarded to {@link StepDotStrip} verbatim — see that prop's own note. */
  openEscalationStepIds?: ReadonlySet<number>
}

/**
 * Whether every service is settled, which is what
 * `ob-signoff-journey-incomplete` tests server-side.
 *
 * Undefined steps answer **false**: the detail read is still in flight, and a
 * go-live panel that flickered into existence before the ribbon arrived would
 * offer the request on a journey nobody has seen the state of yet.
 */
function journeyComplete(steps: readonly ObJourneyStepView[] | undefined): boolean {
  return steps != null && steps.length > 0
    && steps.every((s) => s.status === 'DONE' || s.status === 'SKIPPED')
}

export function JourneyAccordion({
  journey,
  isOpen,
  onToggle,
  siblings,
  users,
  obClientId,
  openEscalationStepIds,
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
  // A strip from a server older than `serviceName` still has to render
  // something a reader can tell apart; the product name is the honest
  // fallback and never the preferred title.
  const serviceName = journey.serviceName ?? productName

  return (
    <ObAccordion
      id={`ob-journey-${journey.id}`}
      isOpen={isOpen}
      onToggle={onToggle}
      label={`${serviceName} — ${journey.percentComplete}% complete, ${ragLabel(journey.rag).toLowerCase()}`}
      summary={
        <>
          {/* The mockup's `jstrip` order: icon, name, the template caption,
              the status chip, TAT, then dots and percent pushed to the right. */}
          <span className="shrink-0 text-lg leading-none" aria-hidden="true">
            {productIcon(journey.product?.code)}
          </span>
          <span className="min-w-0 truncate text-sm font-semibold text-content">{serviceName}</span>

          {/* The product this service belongs to, and how many tasks the
              journey has. The strip still carries no pinned *version* — only
              the expanded detail read does. */}
          <span className="shrink-0 text-caption text-content-muted">
            {productName} · {(journey.steps ?? []).length} services
          </span>

          {hold === 'GATE_LOCKED' ? (
            <Chip variant="neutral">Prerequisites pending</Chip>
          ) : hold === 'HELD_BY_SIBLING' ? (
            <Chip variant="info">
              Held for {heldBy?.serviceName ?? heldBy?.product?.name ?? 'another service'}
            </Chip>
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
            <Chip
              className="shrink-0 tabular-nums"
              variant={usage.band === 'over' ? 'danger' : usage.band === 'amber' ? 'warning' : 'neutral'}
            >
              <span aria-hidden="true">⏱</span>
              {formatTatUsage(usage)}
            </Chip>
          ) : (
            <span className="shrink-0 text-caption text-content-muted">No TAT budget</span>
          )}

          <span className="min-w-0 flex-1" aria-hidden="true" />

          <StepDotStrip steps={journey.steps ?? []} openEscalationStepIds={openEscalationStepIds} />

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

          {/*
            §8 · the journey's go-live sign-off, offered only once every
            service is settled.
            `ob-signoff-journey-incomplete` is the server's 422 for asking
            earlier, and the steps that decide it are in hand right here — so
            the gate lives at this call site rather than inside the panel,
            which has no journey to inspect. `SKIPPED` counts as settled
            because the server's own check does: a waived service is a
            finished one for this purpose.
          */}
          {journeyComplete(steps) && (
            <SignoffPanel kind="GO_LIVE" journeyId={journey.id} obClientId={obClientId} />
          )}
        </>
      )}
    </ObAccordion>
  )
}
