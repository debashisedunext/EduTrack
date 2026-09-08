import * as React from 'react'

import type { ObJourneyStrip } from '@/api/generated/model/obJourneyStrip'
import { useListObClientCommunications } from '@/api/generated/onboarding-journeys/onboarding-journeys'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { CommunicationEntryRow } from './CommunicationEntryRow'

/**
 * C-112 · the **client-level stitched view** — every communication on every
 * service of one client, in one timeline.
 *
 * <h2>Why this exists when a per-service timeline already does</h2>
 *
 * Onboarding-Module-Plan.md §6 asks for both, and §9 draws only the first.
 * The module plan calls this one what management actually asks for, and the
 * question it answers is *"what has anyone said to this client lately"* —
 * which no per-service timeline can answer, because the answer spans services.
 * A manager opening OB-05 before a call does not want to open five accordions
 * and read five timelines in five different orders.
 *
 * <h2>Newest first, and that is the whole difference from the panel above it</h2>
 *
 * A service's own timeline is a narrative you read forwards. A client's is a
 * feed you check, and the entry that matters is the last one. Both orders come
 * from the server; neither is re-sorted here.
 *
 * <h2>Two filters, and both of them answer a question somebody actually asks</h2>
 *
 * **By service**, because "what did we say about the ERP rollout" is the
 * natural narrowing once the stitched list is long. It is the same
 * `journeyId` the accordions above are keyed by, so the vocabulary matches
 * what the reader is already looking at.
 *
 * **Client-visible only**, because the question before a call is *did we say
 * that to them, or only to each other*. Getting that wrong is the one mistake
 * on this screen that cannot be taken back, which is why the filter exists at
 * all rather than the reader scanning chips down a long list.
 *
 * <h2>What is not stitched in yet</h2>
 *
 * §6 says prerequisite comment threads join this view. **No prerequisite
 * comment table exists in any migration today** — A-118 drafted the routes and
 * B-124 builds the master — so there is nothing to join. Recorded here and in
 * the contract rather than approximated with an empty section that looks
 * broken.
 */
export function ClientCommunicationsPanel({
  obClientId,
  journeys,
}: {
  obClientId: number
  /**
   * The client's own journeys, for the service filter's labels — the same
   * `ObJourneyStrip` list OB-05's accordions are built from, so the filter
   * names services exactly as the page above it already does.
   */
  journeys: readonly ObJourneyStrip[]
}) {
  const [journeyId, setJourneyId] = React.useState<number | undefined>(undefined)
  const [clientVisibleOnly, setClientVisibleOnly] = React.useState(false)

  const communications = useListObClientCommunications(
    obClientId,
    { journeyId, clientVisibleOnly: clientVisibleOnly || undefined },
    { query: { enabled: Number.isFinite(obClientId) } },
  )

  const entries = communications.data?.data ?? []
  const hasMore = communications.data?.meta?.hasMore ?? false

  return (
    <section
      className="rounded-card border border-border bg-surface p-4"
      aria-labelledby="client-communications"
      data-testid="client-communications"
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 id="client-communications" className="m-0 text-sm font-semibold text-content">
            Communications
          </h2>
          <p className="m-0 mt-0.5 text-caption text-content-muted">
            Everything said to this client, across every service. Newest first.
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <label className="flex items-center gap-1.5 text-caption text-content-muted">
            Service
            <select
              className="h-8 rounded-control border border-border bg-surface px-2 text-sm text-content focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-1"
              value={journeyId ?? ''}
              onChange={(event) =>
                setJourneyId(event.target.value === '' ? undefined : Number(event.target.value))
              }
            >
              <option value="">All services</option>
              {journeys.map((journey) => (
                <option key={journey.id} value={journey.id}>
                  {journey.product.name}
                </option>
              ))}
            </select>
          </label>

          <label className="flex items-center gap-1.5 text-sm text-content">
            <input
              type="checkbox"
              className="h-4 w-4 rounded border-border text-primary focus:ring-2 focus:ring-primary focus:ring-offset-1"
              checked={clientVisibleOnly}
              onChange={(event) => setClientVisibleOnly(event.target.checked)}
            />
            Client-visible only
          </label>
        </div>
      </div>

      {clientVisibleOnly && (
        <p className="m-0 mt-2">
          <Chip variant="info">Showing only what the client can read in the portal</Chip>
        </p>
      )}

      {communications.isPending ? (
        <div className="mt-3 flex flex-col gap-2" role="status" aria-label="Loading communications">
          <Skeleton className="h-14 w-full" />
          <Skeleton className="h-14 w-full" />
          <Skeleton className="h-14 w-full" />
        </div>
      ) : communications.isError ? (
        <EmptyState
          title="Communications unavailable"
          description="This client's timeline could not be loaded. Nothing has been lost — try again shortly."
        />
      ) : entries.length === 0 ? (
        <p className="mt-3 text-sm text-content-muted">
          {clientVisibleOnly || journeyId != null
            ? 'Nothing matches these filters.'
            : 'Nothing has been recorded against this client yet.'}
        </p>
      ) : (
        <ul className="mt-1 flex flex-col divide-y divide-border">
          {entries.map((entry) => (
            <CommunicationEntryRow
              key={entry.id}
              entry={entry}
              // The one line the stitched view adds: which service this came
              // from. It is what distinguishes two adjacent rows here, and it
              // is why this list is not just the step panel with a wider read.
              context={`${entry.productName} · ${entry.stepSequence}. ${entry.stepName}`}
            />
          ))}
        </ul>
      )}

      {/*
        A sentence rather than a "Load more" button, on the same reasoning
        `StepCommunicationsPanel` records: the read is cursored server-side, so
        the control is one prop away, but nothing in this module has yet
        produced a client with more than a page of communications and an
        untested pagination control is worse than an honest one-line boundary.
      */}
      {hasMore && (
        <p className="m-0 mt-2 text-caption text-content-muted">
          Showing the most recent entries. Narrow by service to see further back.
        </p>
      )}
    </section>
  )
}
