import * as React from 'react'

import { useListObClientEscalations } from '@/api/generated/onboarding/onboarding'

/**
 * C-126 · this client's open escalations — the source both the banner
 * (`EscalationBanner`) and the ribbon's red dots
 * (`StepDotStrip.openEscalationStepIds`) read from.
 *
 * A separate read rather than a field on `ObStepDot`: `GET
 * /onboarding/client-escalations` is the one door onto
 * `ob_client_escalations` — also the OB-02 card's and the portal raise
 * route's — and adding the same fact to the journey read too would be a
 * second query result that can disagree with the first after a resolve.
 */
export function useOpenEscalations(obClientId: number) {
  const query = useListObClientEscalations(
    { obClientId, state: 'OPEN' },
    { query: { enabled: Number.isFinite(obClientId) } },
  )
  const escalations = React.useMemo(() => query.data?.data ?? [], [query.data])
  const openEscalationStepIds = React.useMemo(
    () => new Set(escalations.map((escalation) => escalation.stepId)),
    [escalations],
  )
  return { escalations, openEscalationStepIds }
}

/** The four fields a task's escalation banner draws, whoever opened the task. */
export interface TaskEscalation {
  id: number
  raisedBy: string
  raisedAt: string
  note: string
}

/**
 * This client's open escalation against one task, in the shape the task panel
 * draws — or null where there is none.
 *
 * <h2>One mapping, three screens</h2>
 *
 * <p>The banner is drawn by `ObProjectTaskPanel`, and that panel is opened from
 * the project page, from the My Tasks popup and from a task's own page. Each of
 * the three has to turn an `ObClientEscalation` into the same four fields, and
 * the first two did it with their own object literal — which is how "Client"
 * becomes "client" on one screen and the note comes from a different column on
 * the next. It is a small mapping; it is small in one place.
 *
 * <p>`raisedByContact` is null on an escalation the staff raised on a client's
 * behalf, so the fallback is a word rather than a blank: a banner attributing a
 * complaint to nobody reads as a bug in the banner.
 */
export function escalationForStep(
  escalations: readonly RaisedEscalation[],
  stepId: number | null | undefined,
): TaskEscalation | null {
  if (stepId == null) return null
  const found = escalations.find((escalation) => escalation.stepId === stepId)
  return found ? toTaskEscalation(found) : null
}

/** One escalation in the panel's shape — what the project page's map holds. */
export function toTaskEscalation(escalation: RaisedEscalation): TaskEscalation {
  return {
    id: escalation.id,
    raisedBy: escalation.raisedByContact?.name ?? 'Client',
    raisedAt: escalation.raisedAt,
    note: escalation.comment,
  }
}

/**
 * The four fields the mapping reads, structurally — not `ObClientEscalation`
 * itself, so a caller holding a narrower row (a test fixture, a list response
 * that carries less) is not forced to fabricate the rest of the schema.
 */
interface RaisedEscalation {
  id: number
  stepId: number
  raisedAt: string
  comment: string
  raisedByContact?: { name: string } | null
}
