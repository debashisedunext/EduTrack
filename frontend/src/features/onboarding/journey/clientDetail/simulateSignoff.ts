import type { ObSignoffAcceptResult, ObSignoffAcceptResultResponse, ObSignoffKind } from '@/api/generated/model'
import { http } from '@/api/http'

/**
 * The demo stand-in for a client accepting a sign-off.
 *
 * ## Why this is hand-written and not a generated hook
 *
 * `POST /dev/onboarding/journeys/{journeyId}/simulate-signoff` is not in
 * `contracts/openapi.yaml` and is not going to be. It exists only under the
 * `dev-noauth` and `fixtures` Spring profiles — see
 * `DevSignoffSimulationController` — and generating it would put a "simulate
 * the client" operation into the client every screen imports, for a route that
 * 404s on any real deployment. One hand-written call site, plainly named, is
 * the smaller thing to carry.
 *
 * The *response* type is generated, though, and deliberately: the dev route
 * returns the public accept route's own `ObSignoffAcceptResult`, so this stays
 * pinned to the contract's shape rather than to a local guess that could drift
 * from it.
 */
export interface SimulateSignoffArgs {
  journeyId: number
  kind: ObSignoffKind
  /** Required for `STEP`, ignored for `GO_LIVE` — the server's own pairing. */
  stepId?: number
  signedName?: string
  note?: string
}

export async function simulateSignoff({
  journeyId,
  kind,
  stepId,
  signedName,
  note,
}: SimulateSignoffArgs): Promise<ObSignoffAcceptResult> {
  const response = await http<ObSignoffAcceptResultResponse>({
    url: `/dev/onboarding/journeys/${journeyId}/simulate-signoff`,
    method: 'POST',
    data: { kind, ...(stepId != null && { stepId }), signedName, note },
  })
  return response.data
}

/**
 * Whether to offer the simulator at all.
 *
 * Mirrors `main.tsx`'s MSW switch — on under `vite dev`, with a `VITE_` string
 * that can force it either way — because the two answer the same question. The
 * override exists in both directions for a reason: `'false'` silences it during
 * a demo recorded off the dev server, and `'true'` turns it on for a production
 * *build* served by a backend running the `fixtures` profile, which is a real
 * shape a demo box takes and the one case `import.meta.env.DEV` gets wrong.
 *
 * This is a courtesy, not the fence. The fence is `@Profile` on the server: a
 * button forced on against a real backend calls a route that is not mapped and
 * gets a 404, which is exactly what should happen.
 */
export function signoffSimulationEnabled(): boolean {
  const override = import.meta.env.VITE_SIMULATE_SIGNOFF
  if (override === 'true') return true
  if (override === 'false') return false
  return import.meta.env.DEV
}
