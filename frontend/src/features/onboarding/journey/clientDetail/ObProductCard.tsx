import { Link } from 'react-router-dom'

import type { ObApplication } from '@/api/generated/model'
import { Chip } from '@/components/ui/chip'
import { cn } from '@/lib/utils'

import { formatTatUsage, ragLabel, ragVariant } from './journeyStrip'
import { openEscalationCount, type ObProductGroup } from './productGroups'
import { productIcon } from './productIcon'

/**
 * One purchased product on OB-05, as a card that opens that product's own page.
 *
 * <h2>Why this is the launcher's card and not a smaller one</h2>
 *
 * A-116's `ModuleCard` is the shape this platform already uses for "pick the
 * thing you are going to work in": a glyph, a name, a line saying what is
 * inside, and the figures that tell you whether it needs you today. A client
 * with two products is the same question one level down, so it gets the same
 * answer rather than a second visual language for it.
 *
 * <h2>The figures are the ones the strip used to carry</h2>
 *
 * §9's OB-05 row specifies the journey strip's contents — "% complete, RAG
 * step dots, and TAT `used / total` with overrun highlighting" — and the card
 * keeps all of it except the dots, which belong to a service and are drawn on
 * the product page beside the ribbon they index. What the card adds is the
 * licence line, because a reader who has just been asked to choose between two
 * products is choosing between two purchases.
 *
 * <h2>A card is a link, not a control</h2>
 *
 * `/onboarding/clients/{id}/products/{productId}` is a place, so the whole card
 * is one anchor: middle-click and copy-link work, the browser's own focus ring
 * lands on it, and a screen reader announces one destination rather than a
 * button that turns out to navigate. Every figure inside it is therefore
 * decorative-plus-text — the `aria-label` says the state in words so nothing
 * depends on reading a colour.
 */
export interface ObProductCardProps {
  obClientId: number
  group: ObProductGroup
  /** This product's row from the client's `applications`, when it has one. A
   * journey can exist with no application behind it (a product boarded before
   * B-104 made the licence editable), and a card that refused to render
   * without one would hide a live journey to protect a caption. */
  application?: ObApplication
  /** C-126 · the client's open escalations, scoped to this card by
   * {@link openEscalationCount}. A product with an open escalation is the one
   * a reader should open first, and that is not derivable from RAG. */
  openEscalationStepIds?: ReadonlySet<number>
}

export function ObProductCard({
  obClientId,
  group,
  application,
  openEscalationStepIds,
}: ObProductCardProps) {
  const { product, journeys, percentComplete, servicesSettled, servicesTotal, hold, rag, tat } = group
  const escalations = openEscalationCount(group, openEscalationStepIds)
  const isComplete = servicesTotal > 0 && servicesSettled === servicesTotal

  const stateInWords = hold === 'GATE_LOCKED'
    ? 'prerequisites pending'
    : hold === 'HELD_BY_SIBLING'
      ? 'held behind another service'
      : isComplete
        ? 'all services settled'
        : ragLabel(rag).toLowerCase()

  return (
    <Link
      to={`/onboarding/clients/${obClientId}/products/${product.id}`}
      aria-label={`Open ${product.name} — ${percentComplete}% complete, ${stateInWords}`}
      className={cn(
        'block w-80 max-w-full rounded-card border border-border bg-surface p-5 text-left no-underline shadow-rest',
        'transition duration-150 ease-out hover:-translate-y-0.5 hover:shadow-modal',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2',
      )}
    >
      <span
        aria-hidden="true"
        className="mb-3.5 flex size-11 items-center justify-center rounded-[11px] bg-primary-soft text-xl"
      >
        {productIcon(product.code)}
      </span>

      <span className="block text-base font-semibold leading-6 text-content">{product.name}</span>

      {/* What is inside the card, then what was bought. Two facts, one line
          each: the first decides whether opening it is worth it, the second is
          what the client is paying for. */}
      <span className="mt-1 block text-xs leading-4 text-content-muted">
        {journeys.length} module {journeys.length === 1 ? 'service' : 'services'}
        {servicesTotal > 0 && ` · ${servicesTotal} ${servicesTotal === 1 ? 'service' : 'services'}`}
      </span>
      {application && (application.licenseType || application.units != null) && (
        <span className="mt-0.5 block text-xs leading-4 text-content-muted">
          {application.licenseType}
          {application.licenseType && application.units != null && ' · '}
          {application.units != null && `${application.units} units`}
        </span>
      )}

      {/*
        A meter, not a progress bar, and `aria-valuetext` in services rather
        than percent — `PrereqAccordion`'s argument, which holds for the same
        reason here: this is a measurement of a known set, not the progress of
        an operation the page is running.
      */}
      <span className="mt-3 flex items-center gap-2">
        <span
          role="meter"
          aria-label={`${product.name} services complete`}
          aria-valuemin={0}
          aria-valuemax={servicesTotal}
          aria-valuenow={servicesSettled}
          aria-valuetext={`${servicesSettled} of ${servicesTotal} services complete`}
          className="h-1.5 min-w-[80px] flex-1 overflow-hidden rounded-full bg-subtle"
        >
          <span
            className={cn(
              'block h-full rounded-full',
              isComplete ? 'bg-success' : rag === 'RED' ? 'bg-danger' : rag === 'AMBER' ? 'bg-warning' : 'bg-primary',
            )}
            style={{ width: `${percentComplete}%` }}
          />
        </span>
        <span className="shrink-0 text-sm font-medium tabular-nums text-content-muted">
          {percentComplete}%
        </span>
      </span>

      <span className="mt-3 flex flex-wrap gap-1.5">
        {/* The strip's own chip order and the strip's own precedence: a hold
            replaces the colour rather than sitting beside it, because a locked
            product has no health to report. */}
        {hold === 'GATE_LOCKED' ? (
          <Chip variant="neutral">Prerequisites pending</Chip>
        ) : hold === 'HELD_BY_SIBLING' ? (
          <Chip variant="info">Held for another service</Chip>
        ) : isComplete ? (
          <Chip variant="success">● Complete</Chip>
        ) : (
          rag && <Chip variant={ragVariant(rag)}>{ragLabel(rag)}</Chip>
        )}

        {tat ? (
          <Chip
            className="tabular-nums"
            variant={tat.band === 'over' ? 'danger' : tat.band === 'amber' ? 'warning' : 'neutral'}
          >
            <span aria-hidden="true">⏱</span>
            {formatTatUsage(tat)}
          </Chip>
        ) : (
          <Chip variant="neutral">No TAT budget</Chip>
        )}

        {escalations > 0 && (
          <Chip variant="danger">
            {escalations} open {escalations === 1 ? 'escalation' : 'escalations'}
          </Chip>
        )}
      </span>
    </Link>
  )
}
