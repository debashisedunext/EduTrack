import * as React from 'react'
import { useQueryClient } from '@tanstack/react-query'

import {
  getListObClientEscalationsQueryKey,
  resolveObClientEscalation,
} from '@/api/generated/onboarding/onboarding'
import type { ObClientEscalation } from '@/api/generated/model/obClientEscalation'
import { ApiError } from '@/api/http'
import { Button } from '@/components/ui/button'
import { ReasonDialog } from '@/components/ui/reason-dialog'
import { toast } from '@/components/ui/use-toast'

/**
 * The staff banner plan §4/§9 asks for: every open escalation this client
 * has raised, each with resolve-and-acknowledge. Absent entirely when there
 * are none — an empty "0 escalations" banner sitting on every client's page
 * forever would train a reader to stop looking at it.
 *
 * Resolving requires the same mandatory note the internal ladder's own
 * {@code resolveObEscalation} does, through the same {@link ReasonDialog}
 * CP-03's Escalate control uses — the note is outward-facing here (the
 * contract's own description of {@code resolveObClientEscalation}: it "goes
 * to the client"), which the description below says explicitly rather than
 * leaving staff to guess.
 */
export function EscalationBanner({
  obClientId,
  escalations,
}: {
  obClientId: number
  escalations: readonly ObClientEscalation[]
}) {
  const queryClient = useQueryClient()
  const [resolving, setResolving] = React.useState<ObClientEscalation | null>(null)
  const [isPending, setIsPending] = React.useState(false)

  if (escalations.length === 0) return null

  const onConfirmResolve = async (note: string) => {
    if (!resolving) return
    setIsPending(true)
    try {
      await resolveObClientEscalation(resolving.id, { note })
      toast({ title: 'Escalation resolved', description: 'The client has been notified.' })
      await queryClient.invalidateQueries({
        queryKey: getListObClientEscalationsQueryKey({ obClientId, state: 'OPEN' }),
      })
      setResolving(null)
    } catch (error) {
      toast({
        title: 'Could not resolve',
        description: error instanceof ApiError ? error.problem.detail : undefined,
        variant: 'danger',
      })
    } finally {
      setIsPending(false)
    }
  }

  return (
    <section
      className="rounded-card border border-danger bg-level-critical-soft p-4"
      role="alert"
      aria-label="Open client escalations"
    >
      <h2 className="text-sm font-semibold text-danger-text">
        {escalations.length} open {escalations.length === 1 ? 'escalation' : 'escalations'} from this client
      </h2>
      <ul className="mt-2 flex flex-col divide-y divide-border">
        {escalations.map((escalation) => (
          <li key={escalation.id} className="flex items-start justify-between gap-3 py-2">
            <div className="flex flex-col">
              <span className="text-sm font-medium text-content">{escalation.stepTitle ?? 'A service'}</span>
              <span className="text-caption text-content-muted">
                {escalation.raisedByContact.name} · {new Date(escalation.raisedAt).toLocaleString()}
              </span>
              <p className="mt-1 text-sm text-content">{escalation.comment}</p>
            </div>
            <Button variant="danger" size="sm" onClick={() => setResolving(escalation)}>
              Resolve
            </Button>
          </li>
        ))}
      </ul>

      <ReasonDialog
        open={resolving != null}
        onOpenChange={(open) => {
          if (!open) setResolving(null)
        }}
        title="Resolve escalation"
        description="This note goes back to the client — say what was done. It also appears on the service's communication timeline."
        fieldLabel="Resolution note"
        confirmLabel="Resolve"
        isPending={isPending}
        onConfirm={onConfirmResolve}
      />
    </section>
  )
}
