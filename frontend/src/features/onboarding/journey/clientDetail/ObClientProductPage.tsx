import * as React from 'react'
import { Link, useParams } from 'react-router-dom'
import { format, parseISO } from 'date-fns'

import { useGetObClient, useGetObClientPrereqs } from '@/api/generated/onboarding/onboarding'
import { useListUsers } from '@/api/generated/users/users'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'

import { ObClientAccountPanel } from '../../clients/ObClientAccountPanel'
import { ClientCommunicationsPanel } from '../communications/ClientCommunicationsPanel'
import { EscalationBanner } from './EscalationBanner'
import { JourneyAccordion } from './JourneyAccordion'
import { ObClientInfoCard } from './ObClientInfoCard'
import { PrereqAccordion } from './PrereqAccordion'
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
 * that is not on the screen.
 *
 * <h2>It absorbed the client page</h2>
 *
 * OB-05's client half is gone. Everything on it a reader could act on moved
 * here — the prerequisites gate, B-126's portal-login panel, the client info
 * card, C-112's stitched communications panel and the LIVE banner — and
 * `/onboarding/clients/:obClientId` is now {@link ObClientRedirect}, a
 * forwarder into this page rather than a screen of its own.
 *
 * <p>The gate is the change worth explaining. This page used to carry a banner
 * saying prerequisites had not cleared and a link to go and clear them, because
 * the checklist was one page up. It is not any more, so the banner would be a
 * signpost to the room it is standing in: the real accordion is here, and a
 * reader who finds out a ribbon is gated can act on it without leaving.
 *
 * <p><b>These four are client-level, on a page that is one product.</b> A
 * client with two products draws the same gate and the same portal login on
 * both — the same rows, the same writes, one React Query cache entry behind
 * them, so the second page is a repetition rather than a divergence. That is
 * the cost of not having a client page, and it is the cheaper half: the
 * alternative was a screen whose only job was to hold four panels above a list
 * of links to here.
 */
/**
 * Both ids, for the one caller that has them without a matching URL.
 *
 * `/onboarding/projects/:obProjectId` renders this same surface — a project
 * *is* a client-and-product pair — but reaches it by project id, so it resolves
 * the pair itself and passes it in. Optional, so the route this page was built
 * for is unchanged and still reads its own params.
 *
 * <p>Stream B's addition, in Stream C's directory, with C's sign-off: two
 * props and a default. The alternative was a second copy of this page's
 * accordion, escalation-scoping and anchoring logic, which is exactly the
 * duplication `PHASE-2-BUILD-PLAN.md` §7 draws the directory line to prevent.
 */
export interface ObClientProductPageProps {
  obClientId?: number
  productId?: number
  /**
   * The host is already carrying the client-level context, so this page must
   * not draw a second copy of it.
   *
   * It suppresses the prerequisites checklist, the portal-login panel, the
   * client info card and the communications panel — everything on this page
   * that belongs to the client rather than to the product. Named for the
   * checklist because that is the one a host visibly duplicates:
   * `ObProjectDetailPage` mounts `PrereqAccordion` itself under its own
   * **View** control, and states in its own docstring why the communications
   * panel is deliberately not there either.
   *
   * <p>Left false on the standalone route, which is the only caller today and
   * the one place these four have no other home.
   */
  checklistShownAbove?: boolean
}

export function ObClientProductPage(props: ObClientProductPageProps = {}) {
  const params = useParams<{ obClientId: string; productId: string }>()
  const obClientId = props.obClientId ?? Number(params.obClientId)
  const productId = props.productId ?? Number(params.productId)

  const client = useGetObClient(obClientId, { query: { enabled: Number.isFinite(obClientId) } })
  const users = useListUsers({ isActive: true, limit: 200 })
  const userList = users.data?.data ?? []

  /*
    The client's prerequisites — the gate that used to be one page up.

    Not read when the host says it is already drawing the checklist: the request
    would be paid for and its result thrown away, and `ObProjectDetailPage` has
    made the identical call under the same key by the time this page mounts.
  */
  const prereqs = useGetObClientPrereqs(obClientId, {
    query: { enabled: Number.isFinite(obClientId) && !props.checklistShownAbove },
  })
  const gate = prereqs.data?.data

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

  /**
   * "Defaults open until the gate clears, collapsed after" — §9's rule for the
   * checklist, carried over from the client page with its derivation intact.
   *
   * Derived rather than stored, because the gate can clear while the page is
   * open: verifying the last mandatory task flips it, and a remembered default
   * would leave the checklist expanded over ribbons that just came alive. Once
   * the reader has touched it, their choice wins — which is what `null` means
   * here, and why this is not a plain boolean.
   */
  const [prereqsOverride, setPrereqsOverride] = React.useState<boolean | null>(null)
  const prereqsOpen = prereqsOverride ?? gate?.gateStatus === 'LOCKED'

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
        <BackLink />
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
      <BackLink />

      {/*
        The mockup's banner-live row, and a fact about the *client* rather than
        this product: it is the go-live date and the CSAT score, which nothing
        else on any screen carries. Status is the guard and `liveAt` only the
        date, so a client flipped LIVE by an old migration with no stamp still
        reads as live rather than losing its banner.
      */}
      {detail.status === 'LIVE' && (
        <div
          className="flex flex-wrap items-center gap-2.5 rounded-card border border-level-low bg-level-low-soft px-5 py-3.5 font-semibold text-success-text"
          role="status"
        >
          <span aria-hidden="true">🎉</span>
          <span>
            Fully onboarded &amp; LIVE
            {detail.liveAt && ` since ${formatDay(detail.liveAt)}`} — all {journeys.length}{' '}
            {journeys.length === 1 ? 'journey' : 'journeys'} complete, sign-offs on record
            {detail.csatScore != null && ` · CSAT ${detail.csatScore}/5`}.
          </span>
        </div>
      )}

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
        The gate itself, not a signpost to it.

        While the checklist lived on the client page this was a banner saying
        prerequisites had not cleared with a link to go and clear them. There is
        nowhere to send anybody now — the accordion below is the real thing, and
        a reader who learns here that a ribbon is gated can act on it here.

        Absent rather than empty while its own read is in flight: a "gate locked"
        strip drawn from no data would be an assertion the page cannot support,
        and this is the one claim the ribbons below it depend on. A *failed* read
        is different — silence would hide that the gate is missing, so that gets
        a strip which says so.

        Drawn whatever the gate says, cleared or locked, because it is the only
        copy: `PrereqAccordion` collapses itself once the gate clears, so a
        cleared client pays one row for it.
      */}
      {gate && !props.checklistShownAbove && (
        <PrereqAccordion
          obClientId={obClientId}
          prereqs={gate}
          isOpen={prereqsOpen}
          onToggle={() => {
            // Written from what is on screen, not flipped blindly. The first
            // click happens while the open state is still derived from the
            // gate, so negating a stale `false` would *open* the accordion the
            // reader is trying to close.
            setPrereqsOverride(!prereqsOpen)
          }}
        />
      )}
      {prereqs.isError && !props.checklistShownAbove && (
        <div
          className="rounded-card border border-level-high bg-level-high-soft px-5 py-3 text-sm text-warning-text"
          role="alert"
        >
          📋 The prerequisites checklist could not be loaded, so the gate cannot be shown. Reload
          the page to try again.
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

      {/*
        §9's closing pair, and C-112's panel under it — the rest of what the
        client page carried.

        Below the ribbons rather than above them, which is the order §9 already
        argued for: the gate first because nothing moves while it is locked,
        then the work, then the record. None of these three is worked down — the
        portal login is administered once, the info card is reference, and the
        communications panel is read before a call — so they go where they push
        nothing a reader has to act on further down.

        `journeys` and not `group.journeys` for the panel: its filter names the
        client's services, and "everything said to this client, across every
        service" is the question it answers. Narrowing it to one product would
        make it a different, smaller panel that happens to share a name.
      */}
      {!props.checklistShownAbove && (
        <>
          <div className="grid items-start gap-4 lg:grid-cols-2">
            <ObClientAccountPanel obClientId={obClientId} />
            <ObClientInfoCard detail={detail} />
          </div>
          <ClientCommunicationsPanel obClientId={obClientId} journeys={journeys} />
        </>
      )}
    </div>
  )
}

/** `2026-08-07T11:40:00Z` and `2026-08-07` both → "7 Aug 2026" — the banner's format. */
function formatDay(value: string): string {
  const parsed = parseISO(value)
  return Number.isNaN(parsed.getTime()) ? value : format(parsed, 'd MMM yyyy')
}

/**
 * Back to the roster, not to the client.
 *
 * It used to read "← {client name}" and point at the client page. That page is
 * gone, so the honest destination is the list — and the client is not lost from
 * the screen by saying so: its name is the caption under the product heading,
 * and its whole record is in the info card at the bottom.
 */
function BackLink() {
  return (
    <Link
      to="/onboarding/clients"
      className="self-start rounded-control px-2 py-1 text-sm font-medium text-primary no-underline hover:bg-subtle"
    >
      ← All clients
    </Link>
  )
}
