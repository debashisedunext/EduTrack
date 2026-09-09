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
