import * as React from 'react'
import { useParams } from 'react-router-dom'

import { useGetObClient, useGetObClientPrereqs } from '@/api/generated/onboarding/onboarding'
import { useListUsers } from '@/api/generated/users/users'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'

import { JourneyAccordion } from './JourneyAccordion'
import { PrereqAccordion } from './PrereqAccordion'
import { ragLabel, ragVariant } from './journeyStrip'

/**
 * C-110 · OB-05, the onboarding client detail page — `/onboarding/clients/:obClientId`.
 *
 * <h2>The page is the accordion stack</h2>
 *
 * Onboarding-Module-Plan.md §9 orders it: **prerequisites accordion on top →
 * one journey accordion per purchased product → client portal access + client
 * info**. The order is the argument. While the gate is locked nothing below it
 * can move — journeys are drawn in full with no clock running (plan §5.2) — so
 * the only actionable thing on the page is at the top of it.
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
 * <h2>What this page is not, yet</h2>
 *
 * - **The step update panel** — start, complete, block, the task-list gate and
 *   the history — is **C-111** (OB-06). `JourneyStepPanel` renders the step
 *   read-only until then, with no dead controls standing in for the actions.
 * - **SD/FD in the ribbon meta line and the animated status emojis** are
 *   **C-125**, which names C-110 as its dependency for exactly this reason.
 * - **The client-account panel** — create, reset and disable a portal login —
 *   is **B-126**, on Stream B's side of the ownership map. The page's
 *   `hasPortalLogin` line is the read-only fact until it lands.
 * - **The stitched client-level communications tab** is **C-112**.
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
   * One directory read for the whole page, the pattern `ClientListPage` and
   * `AnalyticsTab` already use.
   *
   * Every journey read returns owners as ids on the contract's own convention,
   * and every expanded ribbon needs names. Fetching per accordion would mean
   * one request per product for a list that is identical each time; fetching
   * here means React Query serves the second accordion from cache.
   */
  const users = useListUsers({ isActive: true, limit: 200 })
  const userList = users.data?.data ?? []

  const detail = client.data?.data
  const journeys = detail?.journeys ?? []
  const gate = prereqs.data?.data

  /**
   * Which accordions are open, as a set of keys.
   *
   * A set rather than a single id: §9's rule is about *an* accordion expanding
   * without moving the page, not about them being mutually exclusive, and a
   * client comparing two products wants both ribbons at once.
   */
  const [open, setOpen] = React.useState<ReadonlySet<string>>(() => new Set())
  const [touchedPrereqs, setTouchedPrereqs] = React.useState(false)

  const setOpenState = React.useCallback((key: string, isOpen: boolean) => {
    setOpen((current) => {
      const next = new Set(current)
      if (isOpen) next.add(key)
      else next.delete(key)
      return next
    })
  }, [])

  const toggle = React.useCallback(
    (key: string) => setOpen((current) => {
      const next = new Set(current)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    }),
    [],
  )

  /**
   * "Defaults open until the gate clears, collapsed after" — §9, derived
   * rather than stored.
   *
   * Derived, because the gate can clear *while the page is open*: verifying the
   * last mandatory task flips it, and a remembered default would leave the
   * checklist expanded over journeys that just came alive. Once the reader has
   * touched it themselves, their choice wins — a screen that re-collapsed a
   * section somebody deliberately opened would be arguing with them.
   */
  const prereqsOpen = touchedPrereqs
    ? open.has('prereqs')
    : gate?.gateStatus === 'LOCKED'

  if (client.isError) {
    return (
      <div className="mx-auto w-full max-w-5xl p-6">
        <EmptyState
          title="Client not found"
          description="It may have been removed, or it may be outside the clients you can see."
        />
      </div>
    )
  }

  return (
    <div className="mx-auto flex w-full max-w-5xl flex-col gap-4 p-6">
      {client.isPending || !detail ? (
        <PageSkeleton />
      ) : (
        <>
          <header className="flex flex-col gap-2">
            <div className="flex flex-wrap items-center gap-2">
              <h1 className="m-0 text-lg font-semibold text-content">{detail.name}</h1>
              <Chip variant="neutral">{detail.status}</Chip>
              {/*
                Health and gate are two facts and get two chips. `ObRag`'s own
                description is that folding "prerequisites pending" into the
                colour would give a reader an enum where "RED" and "LIVE" are
                the same kind of answer, which they are not.
              */}
              {detail.rag && <Chip variant={ragVariant(detail.rag)}>{ragLabel(detail.rag)}</Chip>}
              {detail.gateStatus === 'LOCKED' && <Chip variant="warning">Prerequisites pending</Chip>}
            </div>
            {/* No PAN — see the header docstring. */}
            <p className="m-0 text-sm text-content-muted">
              Boarded {detail.onboardingDate}
              {detail.salesPerson && ` · ${detail.salesPerson.displayName}`}
              {` · ${journeys.length} ${journeys.length === 1 ? 'journey' : 'journeys'}`}
              {detail.hasPortalLogin ? ' · portal login active' : ' · no portal login'}
            </p>
          </header>

          {/*
            The gate accordion is absent rather than empty while its own read is
            in flight or has failed. A "Gate locked" strip drawn from no data
            would be an assertion the page cannot support, and this is the one
            claim on the screen everything below it depends on.
          */}
          {gate && (
            <PrereqAccordion
              obClientId={obClientId}
              prereqs={gate}
              isOpen={prereqsOpen}
              onToggle={() => {
                // Written from what is on screen, not toggled in the set. The
                // first click happens while the open state is still derived
                // from the gate, so a blind toggle of a key that was never
                // added would *open* an accordion the reader is trying to
                // close.
                setTouchedPrereqs(true)
                setOpenState('prereqs', !prereqsOpen)
              }}
            />
          )}

          {journeys.length === 0 ? (
            <EmptyState
              title="No journeys"
              description="This client has no purchased products, so there is nothing to onboard yet."
            />
          ) : (
            journeys.map((journey) => (
              <JourneyAccordion
                key={journey.id}
                journey={journey}
                siblings={journeys}
                users={userList}
                isOpen={open.has(`journey-${journey.id}`)}
                onToggle={() => toggle(`journey-${journey.id}`)}
              />
            ))
          )}
        </>
      )}
    </div>
  )
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
