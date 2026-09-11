import * as React from 'react'
import { Link, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { format, parseISO } from 'date-fns'

import {
  getObClientAccount,
  useGetObClient,
  useGetObClientPrereqs,
} from '@/api/generated/onboarding/onboarding'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'

import { ObClientAccountPanel } from '../../clients/ObClientAccountPanel'
import { ClientCommunicationsPanel } from '../communications/ClientCommunicationsPanel'
import { EscalationBanner } from './EscalationBanner'
import { ObClientInfoCard } from './ObClientInfoCard'
import { PrereqAccordion } from './PrereqAccordion'
import { ragLabel, ragVariant } from './journeyStrip'
import { groupJourneysByProduct } from './productGroups'
import { useOpenEscalations } from './useOpenEscalations'

/**
 * C-110 · OB-05, the onboarding client detail page — `/onboarding/clients/:obClientId`.
 *
 * <h2>The page is the gate, then the closing pair</h2>
 *
 * Onboarding-Module-Plan.md §9 orders it: **prerequisites accordion on top →
 * the journey surface → client portal access + client info**. The order is the
 * argument. While the gate is locked nothing below it can move — journeys are
 * drawn in full with no clock running (plan §5.2) — so the only actionable
 * thing on the page is at the top of it.
 *
 * <h2>The journey surface is a page per product — a §9 deviation</h2>
 *
 * §9's middle row was written when a purchased product meant one journey. It no
 * longer does: a product publishes **any number of Module Services at once**
 * (plan §20, and the multi-service change that implemented it), a client is
 * boarded through one journey per service of every product they bought, and so
 * a client with two products can carry six accordions. The page then stopped
 * answering the question people open it with — *how is the ERP going* — because
 * the ERP's three strips were interleaved with the biometric rollout's.
 *
 * So {@link ObClientProductPage} answers **how is it going** for one product at
 * a time, at a URL somebody can send. This page no longer carries a product
 * chooser of its own: the list page's Products Bought column links straight
 * into a product's ribbons, which is the click a reader already makes, and a
 * second grid of the same links here was a row of cards restating what the
 * header already counts.
 *
 * <h2>PAN is not in the header</h2>
 *
 * §9's OB-05 row opens with it: "**PAN is not shown in the header** (identity
 * data stays in Client info for authorized roles)". The API already masks the
 * value for every role but OB Admin and Onboarding Manager, and the unmasked
 * one comes from its own audited reveal operation — so this is not the control
 * that protects it. It is the one that stops a masked identity string sitting
 * on a screen that gets shared and screenshotted all day.
 *
 * <h2>No payments card</h2>
 *
 * Stated in the task and in the plan, and it is a removal rather than an
 * omission: plan §1.2 took financial tracking out of the module entirely after
 * the prototype had it. The prototype is not the specification here.
 *
 * <h2>The header's "Login:" and B-126's panel share one read</h2>
 *
 * The mockup's caption line ends "Login: CL-30412". The username lives on
 * `getObClientAccount`, which `ObClientAccountPanel` below already fetches
 * under the key `['obClientAccount', id]` — so the header uses the identical
 * key and React Query serves both from one request. The read is enabled only
 * when `hasPortalLogin` says a row exists: for the ordinary boarded client
 * with no login it would be a guaranteed 404 asked solely to render the words
 * "no portal login", which `hasPortalLogin` already carries.
 *
 * <h2>C-112 · the stitched communications panel, and why it is at the bottom</h2>
 *
 * §9's order is the argument the page is built on — the gate first, because
 * nothing below it can move while it is locked, then the journeys, then the
 * two closing cards. The communications panel is not actionable in that
 * sense: it is the record of what has been said, read before a call rather
 * than worked down. So it sits last, where it does not push anything a reader
 * has to act on further down the page.
 *
 * It is a **panel rather than a tab**, which is a departure from the task's own
 * wording. OB-05 has no tab strip — the page is an accordion stack — and
 * introducing one for a single additional section would restructure a screen
 * §9 lays out deliberately. The panel keeps its own two filters instead, which
 * is what the tab would have carried anyway.
 *
 * <h2>Where this file lives</h2>
 *
 * `features/onboarding/journey/`, which PHASE-2-BUILD-PLAN.md §7 assigns to
 * Stream C, rather than `features/onboarding/clients/`, which it assigns to
 * Stream B. OB-05 is a C task (C-110) whose entire content is the journey
 * surface — prerequisites gate, ribbons, step panels — and B's own OB-05 work
 * (B-126's account panel) is a panel added *to* this page rather than the page
 * itself. Putting a C-owned screen in a B-owned directory would make every
 * future edit to it a cross-stream edit. The route path is still
 * `/onboarding/clients/{id}` — B-108's `ObMailLinks` already points its mail
 * there and a directory name is not a URL.
 */
export function ObClientDetailPage() {
  const params = useParams<{ obClientId: string }>()
  const obClientId = Number(params.obClientId)

  const client = useGetObClient(obClientId, { query: { enabled: Number.isFinite(obClientId) } })
  const prereqs = useGetObClientPrereqs(obClientId, { query: { enabled: Number.isFinite(obClientId) } })

  /**
   * The user directory is **no longer read here**.
   *
   * It was one read for the whole page while the ribbons were on it: every
   * journey read returns owners as ids, and one directory fetch served every
   * accordion from cache. No ribbon renders on this page any more, so the
   * fetch moved to `ObClientProductPage` — keeping it here would be a
   * 200-row request every reader pays for and only the ones who click a card
   * ever use.
   */

  const detail = client.data?.data
  // Memoised rather than defaulted inline: a fresh `[]` on every render would
  // re-group the products on each pass for no change in what the header says.
  const journeys = React.useMemo(() => detail?.journeys ?? [], [detail?.journeys])
  const products = React.useMemo(() => groupJourneysByProduct(journeys), [journeys])
  const gate = prereqs.data?.data

  // The header docstring above: B-126's queryKey, so the panel's fetch and
  // this one are the same cache entry.
  const account = useQuery({
    queryKey: ['obClientAccount', obClientId],
    queryFn: () => getObClientAccount(obClientId),
    retry: false,
    enabled: Number.isFinite(obClientId) && detail?.hasPortalLogin === true,
  })
  const username = account.data?.data?.username

  // C-126 · this client's open escalations — the banner below and the red
  // ring on each journey's step dots both read from the one call.
  const { escalations } = useOpenEscalations(obClientId)

  /**
   * "Defaults open until the gate clears, collapsed after" — §9, derived
   * rather than stored.
   *
   * Derived, because the gate can clear *while the page is open*: verifying the
   * last mandatory task flips it, and a remembered default would leave the
   * checklist expanded over products that just came alive. Once the reader has
   * touched it themselves, their choice wins — a screen that re-collapsed a
   * section somebody deliberately opened would be arguing with them, which is
   * what `null` means here and why this is not a plain boolean.
   *
   * One override rather than the set of accordion keys this page used to keep:
   * the gate is the only accordion left on it. The set moved to
   * `ObClientProductPage`, where several ribbons can be open at once.
   */
  const [prereqsOverride, setPrereqsOverride] = React.useState<boolean | null>(null)
  const prereqsOpen = prereqsOverride ?? gate?.gateStatus === 'LOCKED'

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

  return (
    <div className="mx-auto flex w-full max-w-[1280px] flex-col gap-5 p-6">
      {client.isPending || !detail ? (
        <PageSkeleton />
      ) : (
        <>
          <Link
            to="/onboarding/clients"
            className="self-start rounded-control px-2 py-1 text-sm font-medium text-primary no-underline hover:bg-subtle"
          >
            ← All clients
          </Link>

          {/* The mockup's banner-live row — status is the guard, `liveAt` the
              date, so a client flipped LIVE by an old migration with no stamp
              still reads as live rather than losing its banner. */}
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
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <h1 className="m-0 text-2xl font-semibold text-content [text-wrap:balance]">
                  {detail.name}
                </h1>
                <Chip variant="neutral">{detail.status}</Chip>
                {detail.gateStatus === 'LOCKED' && <Chip variant="warning">Prerequisites pending</Chip>}
              </div>
              {/* No PAN — see the page docstring. */}
              <p className="m-0 mt-1 text-sm text-content-muted">
                {detail.licenseType && `${detail.licenseType} · `}
                {detail.salesPerson && `Sales: ${detail.salesPerson.displayName} · `}
                Boarded {formatDay(detail.onboardingDate)}
                {username
                  ? ` · Login: ${username}`
                  : detail.hasPortalLogin
                    ? ' · portal login active'
                    : ' · no portal login'}
              </p>
            </div>
            <span className="min-w-0 flex-1" aria-hidden="true" />
            <div className="text-right">
              {/*
                Health and gate are two facts and get two chips — the gate chip
                stays beside the name, the colour lands here, the mockup's own
                split. `ObRag`'s description is that folding "prerequisites
                pending" into the colour would give a reader an enum where
                "RED" and "LIVE" are the same kind of answer.
              */}
              {detail.status === 'LIVE' ? (
                <Chip variant="success">● Live</Chip>
              ) : (
                detail.rag && <Chip variant={ragVariant(detail.rag)}>{ragLabel(detail.rag)}</Chip>
              )}
              <div className="mt-1.5 text-caption text-content-muted">
                {/* Products, not journeys: a client with two products can
                    carry six journeys, and "6" is not the number anyone opens
                    this page holding. The journey counts live on the product
                    pages, where each belongs to something. */}
                {products.length} {products.length === 1 ? 'product' : 'products'}
              </div>
            </div>
          </header>

          {/* C-126 · absent when there are none — see the component's own note. */}
          <EscalationBanner obClientId={obClientId} escalations={escalations} />

          {/*
            The gate accordion is absent rather than empty while its own read is
            in flight — a "Gate locked" strip drawn from no data would be an
            assertion the page cannot support, and this is the one claim on the
            screen everything below it depends on. A *failed* read is different:
            silence there hides that the page is missing its gate, so the
            failure gets a strip that says so instead of nothing.
          */}
          {gate && (
            <PrereqAccordion
              obClientId={obClientId}
              prereqs={gate}
              isOpen={prereqsOpen}
              onToggle={() => {
                // Written from what is on screen, not flipped blindly. The
                // first click happens while the open state is still derived
                // from the gate, so negating a stale `false` would *open* the
                // accordion the reader is trying to close.
                setPrereqsOverride(!prereqsOpen)
              }}
            />
          )}
          {prereqs.isError && (
            <div
              className="rounded-card border border-level-high bg-level-high-soft px-5 py-3 text-sm text-warning-text"
              role="alert"
            >
              📋 The prerequisites checklist could not be loaded, so the gate cannot be shown.
              Reload the page to try again.
            </div>
          )}

          {/* §9's closing pair — B-126's panel on the left, the info card on
              the right. The panel is Stream B's component, mounted here as
              built; the page owns the grid, not the panel. */}
          <div className="grid items-start gap-4 lg:grid-cols-2">
            <ObClientAccountPanel obClientId={obClientId} />
            <ObClientInfoCard detail={detail} />
          </div>

          {/* C-112 · plan §6's client-level half. See the page docstring for
              why it is a panel here and not a tab. */}
          <ClientCommunicationsPanel obClientId={obClientId} journeys={journeys} />
        </>
      )}
    </div>
  )
}

/** `2026-08-07T11:40:00Z` and `2026-08-07` both → "7 Aug 2026" — the caption line's format. */
function formatDay(value: string): string {
  const parsed = parseISO(value)
  return Number.isNaN(parsed.getTime()) ? value : format(parsed, 'd MMM yyyy')
}

function PageSkeleton() {
  return (
    <div className="flex flex-col gap-4" role="status" aria-label="Loading client">
      <Skeleton className="h-7 w-64" />
      <Skeleton className="h-14 w-full" />
      <Skeleton className="h-14 w-full" />
      <Skeleton className="h-14 w-full" />
    </div>
  )
}
