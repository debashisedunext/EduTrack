import { useListObStepCommunications } from '@/api/generated/onboarding-journeys/onboarding-journeys'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { CommunicationEntryRow } from './CommunicationEntryRow'
import { RecordCommunicationForm } from './RecordCommunicationForm'

/**
 * C-112 · one service's own communication timeline — plan §6's per-step half,
 * inside OB-06's step panel.
 *
 * **Oldest first**, which is the server's order and the opposite of the
 * client-level view beside it. A service's timeline is a narrative you read
 * forwards: the kickoff call, then the mail, then the client's reply. A feed
 * ordering would put the ending first on the one screen whose whole purpose is
 * the sequence.
 *
 * **The first page only, deliberately.** `Meta.hasMore` is rendered as a plain
 * sentence rather than a "Load more" button. A service accumulating more than
 * fifty recorded conversations is not a case any fixture or screen in this
 * module has produced, and a pagination control nobody can reach the end of is
 * a control that has never been exercised. The read is cursored server-side so
 * the affordance is one prop away when a real timeline outgrows a page — see
 * the client-level view, whose own note says the same about a different
 * threshold.
 */
export function StepCommunicationsPanel({
  stepId,
  obClientId,
  canRecord = true,
}: {
  stepId: number
  /** Lets a record here also refresh the client's stitched view. */
  obClientId?: number
  /**
   * A sealed or read-only rendering hides the form rather than disabling it —
   * `JourneyStepPanel`'s own "no dead controls" rule, and B-121's reasoning
   * behind it: a dead control teaches the user the board is broken.
   */
  canRecord?: boolean
}) {
  const communications = useListObStepCommunications(stepId, undefined, {
    query: { enabled: Number.isFinite(stepId) },
  })

  const entries = communications.data?.data ?? []
  const hasMore = communications.data?.meta?.hasMore ?? false

  return (
    <section className="mt-4" aria-labelledby={`step-comms-${stepId}`}>
      <h5 id={`step-comms-${stepId}`} className="m-0 text-sm font-semibold text-content">
        Communications
      </h5>
      <p className="m-0 mt-0.5 text-caption text-content-muted">
        What was said about this service, and to whom. Recording an entry here does not send
        anything.
      </p>

      {communications.isPending ? (
        <div className="mt-3 flex flex-col gap-2" role="status" aria-label="Loading communications">
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-12 w-full" />
        </div>
      ) : communications.isError ? (
        <EmptyState
          title="Communications unavailable"
          description="This service's timeline could not be loaded. Nothing has been lost — try again shortly."
        />
      ) : entries.length === 0 ? (
        <p className="mt-3 text-sm text-content-muted">
          Nothing recorded against this service yet.
        </p>
      ) : (
        <ul className="mt-1 flex flex-col divide-y divide-border" data-testid="step-communications">
          {entries.map((entry) => (
            <CommunicationEntryRow key={entry.id} entry={entry} />
          ))}
        </ul>
      )}

      {hasMore && (
        <p className="m-0 mt-2 text-caption text-content-muted">
          Showing the earliest entries. The client-level timeline carries the whole record.
        </p>
      )}

      {canRecord && <RecordCommunicationForm stepId={stepId} obClientId={obClientId} />}
    </section>
  )
}
