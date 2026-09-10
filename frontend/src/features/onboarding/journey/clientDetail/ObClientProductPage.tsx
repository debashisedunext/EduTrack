import * as React from 'react'
import { Link, useParams } from 'react-router-dom'

import { useGetObClient } from '@/api/generated/onboarding/onboarding'
import { useListUsers } from '@/api/generated/users/users'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'

import { EscalationBanner } from './EscalationBanner'
import { JourneyAccordion } from './JourneyAccordion'
import { defaultOpenJourneyId, formatTatUsage, ragLabel, ragVariant } from './journeyStrip'
import { findProductGroup } from './productGroups'
import { productIcon } from './productIcon'
import { useOpenEscalations } from './useOpenEscalations'

/**
 * OB-05, one product at a time — `/onboarding/clients/:obClientId/products/:productId`.
 *
 * <h2>Why OB-05 is two screens now</h2>
 *
 * §9 lays the client page out as "prerequisites accordion on top → one journey
 * accordion per purchased product → client portal access + client info", and
 * that ordering was written when a purchased product meant one journey. Since a
 * product publishes several Module Services at once (plan §20, and the
 * multi-service change that implemented it) a client with two products can
 * carry six accordions, and the page stopped answering the question people
 * actually open it with — *how is the ERP going* — because the ERP's answer was
 * spread across three strips interleaved with the biometric rollout's.
 *
 * So the client page now answers **which product**, as a card each, and this
 * page answers **how is it going** for the one that was clicked. §9's ordering
 * is preserved within each: the gate stays above everything on the client page,
 * because nothing here can move while it is locked, and this page says so
 * rather than pretending its ribbons are live.
 *
 * <h2>It is a route, not a tab</h2>
 *
 * A product is a place a reader is sent to — from a card, from a mail link,
 * from a colleague pasting a URL — so the product id is in the path. Held in
 * component state instead, "look at KV Varanasi's biometric rollout" would be
 * two clicks that cannot be sent to anybody, and a browser Back out of a step
 * panel would land on the client list rather than on the product.
 *
 * <h2>Three reads, all of them the client page's</h2>
 *
 * The client document (which carries every journey strip), the user directory
 * the ribbons resolve owners from, and the client's open escalations. React
 * Query serves all three from cache on a click through from the card, so
 * arriving here costs the one ribbon read the opened accordion makes.
 *
 * The escalations are **scoped to this product's services** before the banner
 * sees them: they are read per client, and a banner on the biometric page
 * shouting about an ERP migration escalation would send a reader to a ribbon
 * that is not on the screen. The client page keeps the unscoped banner.
 */
export function ObClientProductPage() {
  const params = useParams<{ obClientId: string; productId: string }>()
  const obClientId = Number(params.obClientId)
  const productId = Number(params.productId)

  const client = useGetObClient(obClientId, { query: { enabled: Number.isFinite(obClientId) } })
  const users = useListUsers({ isActive: true, limit: 200 })
  const userList = users.data?.data ?? []

  const detail = client.data?.data
  // Memoised for the client page's reason: a fresh `[]` each render would
  // re-derive the group, and with it the accordion this page opens by itself.
  const journeys = React.useMemo(() => detail?.journeys ?? [], [detail?.journeys])
  const group = React.useMemo(
    () => (Number.isFinite(productId) ? findProductGroup(journeys, productId) : undefined),
    [journeys, productId],
  )

  const { escalations, openEscalationStepIds } = useOpenEscalations(obClientId)
  const productEscalations = React.useMemo(
    () => escalations.filter((escalation) => group?.stepIds.has(escalation.stepId)),
    [escalations, group],
  )

  /**
   * Which accordions are open — the client page's own mechanism, scoped to
   * this product's journeys.
   *
   * A set rather than a single id, and a derived default rather than a seeded
   * one, for the reasons that page states: a reader comparing two services of
   * the same product wants both ribbons at once, and the journeys arrive one
   * render after the mount, so an initialiser would run against an empty list
   * and open nothing.
   */
  const [open, setOpen] = React.useState<ReadonlySet<string>>(() => new Set())
  const [touched, setTouched] = React.useState(false)

  const defaultJourneyId = defaultOpenJourneyId(group?.journeys ?? [])

  const isOpen = (journeyId: number) =>
    touched ? open.has(`journey-${journeyId}`) : journeyId === defaultJourneyId

  const onToggle = (journeyId: number) => {
    // Seeded from what is on screen before the first toggle, exactly as the
    // client page does it: a blind toggle of a key that was never added would
    // *open* the accordion the reader is trying to close.
    if (!touched) {
      setTouched(true)
      setOpen(
        journeyId === defaultJourneyId
          ? new Set()
          : new Set([`journey-${defaultJourneyId}`, `journey-${journeyId}`]),
      )
      return
    }
    setOpen((current) => {
      const next = new Set(current)
      const key = `journey-${journeyId}`
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }

  if (client.isError) {
    return (
      <div className="mx-auto w-full max-w-[1280px] p-6">
        <EmptyState
          title="Client not found"
          description="It may have been removed, or it may be outside the clients you can see."
        />
      </div>
    )
  }

  if (client.isPending || !detail) {
    return (
      <div className="mx-auto w-full max-w-[1280px] p-6">
        <div className="flex flex-col gap-4" role="status" aria-label="Loading product">
          <Skeleton className="h-7 w-64" />
          <Skeleton className="h-14 w-full" />
          <Skeleton className="h-14 w-full" />
        </div>
      </div>
    )
  }

  /**
   * A product this client never bought is **not found**, not an empty page.
   *
   * The row scope has already decided the reader may see the client, so there
   * is no existence to leak here — what there is instead is a reader who has
   * followed a stale link, and the way back to something real is worth more
   * than a page of empty accordions.
   */
  if (!group) {
    return (
      <div className="mx-auto flex w-full max-w-[1280px] flex-col gap-4 p-6">
        <BackLink obClientId={obClientId} name={detail.name} />
        <EmptyState
          title="Product not found"
          description={`${detail.name} has no onboarding journey for this product. It may not have been bought, or its journeys may not be instantiated yet.`}
        />
      </div>
    )
  }

  const application = (detail.applications ?? []).find((a) => a.product?.id === group.product.id)
  const isComplete = group.servicesTotal > 0 && group.servicesSettled === group.servicesTotal

  return (
    <div className="mx-auto flex w-full max-w-[1280px] flex-col gap-5 p-6">
      <BackLink obClientId={obClientId} name={detail.name} />

      <header className="flex flex-wrap items-start gap-3">
        <span
          aria-hidden="true"
          className="flex size-11 shrink-0 items-center justify-center rounded-[11px] bg-primary-soft text-xl"
        >
          {productIcon(group.product.code)}
        </span>
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="m-0 text-2xl font-semibold text-content [text-wrap:balance]">
              {group.product.name}
            </h1>
            {group.hold === 'GATE_LOCKED' ? (
              <Chip variant="neutral">Prerequisites pending</Chip>
            ) : group.hold === 'HELD_BY_SIBLING' ? (
              <Chip variant="info">Held for another service</Chip>
            ) : isComplete ? (
              <Chip variant="success">● Complete</Chip>
            ) : (
              group.rag && <Chip variant={ragVariant(group.rag)}>{ragLabel(group.rag)}</Chip>
            )}
          </div>
          {/* The client is the caption here, the way the product is the caption
              on a journey strip: this page is one product *of* a client, and a
              reader who arrived from a mail link needs to know whose. */}
          <p className="m-0 mt-1 text-sm text-content-muted">
            {detail.name} · {group.journeys.length} module{' '}
            {group.journeys.length === 1 ? 'service' : 'services'}
            {application?.licenseType && ` · ${application.licenseType}`}
            {application?.units != null && ` · ${application.units} units`}
          </p>
        </div>
        <span className="min-w-0 flex-1" aria-hidden="true" />
        <div className="text-right">
          <div className="text-2xl font-semibold tabular-nums text-content">
            {group.percentComplete}%
          </div>
          <div className="mt-0.5 text-caption text-content-muted">
            {group.servicesSettled} of {group.servicesTotal} services complete
          </div>
          {group.tat && (
            <Chip
              className="mt-1.5 tabular-nums"
              variant={group.tat.band === 'over' ? 'danger' : group.tat.band === 'amber' ? 'warning' : 'neutral'}
            >
              <span aria-hidden="true">⏱</span>
              {formatTatUsage(group.tat)}
            </Chip>
          )}
        </div>
      </header>

      {/*
        The gate is the client's, and it is administered on the client page —
        so this says what is holding the ribbons and points at where to go,
        rather than repeating a checklist that would then exist in two places
        with one copy always slightly behind.
      */}
      {group.hold === 'GATE_LOCKED' && (
        <div
          className="flex flex-wrap items-center gap-2 rounded-card border border-level-high bg-level-high-soft px-5 py-3 text-sm text-warning-text"
          role="status"
        >
          <span aria-hidden="true">📋</span>
          <span>
            Nothing here starts until this client&apos;s prerequisites clear — no step activates and
            no TAT clock runs (plan §5.2).
          </span>
          <Link
            to={`/onboarding/clients/${obClientId}`}
            className="font-semibold text-primary no-underline hover:underline"
          >
            Open the checklist
          </Link>
        </div>
      )}

      <EscalationBanner obClientId={obClientId} escalations={productEscalations} />

      {group.journeys.map((journey) => (
        <JourneyAccordion
          key={journey.id}
          journey={journey}
          // Every journey of the client, not only this product's: a
          // service-level dependency crosses products (plan §5.5), so a strip
          // held behind the ERP rollout has to be able to name it from the
          // biometric page.
          siblings={journeys}
          users={userList}
          obClientId={obClientId}
          openEscalationStepIds={openEscalationStepIds}
          isOpen={isOpen(journey.id)}
          onToggle={() => onToggle(journey.id)}
        />
      ))}
    </div>
  )
}

function BackLink({ obClientId, name }: { obClientId: number; name: string }) {
  return (
    <Link
      to={`/onboarding/clients/${obClientId}`}
      className="self-start rounded-control px-2 py-1 text-sm font-medium text-primary no-underline hover:bg-subtle"
    >
      ← {name}
    </Link>
  )
}
