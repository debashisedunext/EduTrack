import * as React from 'react'
import { Navigate, useParams } from 'react-router-dom'

import { useGetObClient } from '@/api/generated/onboarding/onboarding'
import { Skeleton } from '@/components/ui/skeleton'

import { groupJourneysByProduct } from './productGroups'

/**
 * `/onboarding/clients/:obClientId` — a forwarder, not a page.
 *
 * <h2>The client page is gone</h2>
 *
 * OB-05's client half was removed: everything on it that could be acted on —
 * the prerequisites gate, the portal-login panel, the client info card and the
 * stitched communications panel — now sits on {@link ObClientProductPage},
 * beside the ribbons those things actually gate. A client-level shell above
 * them was a second header and a second scroll on the way to the same
 * controls.
 *
 * <h2>Why the URL survives the page</h2>
 *
 * It is not ours to retire. `ObMailLinks.STAFF_CLIENT` has been putting
 * `/onboarding/clients/{id}` into onboarding mail since B-108, and the mail
 * already sent cannot be rewritten — deleting the route would turn every one of
 * those into a dead link. The dashboard's RAG board, stuck panel and delayed
 * grid point here too, each holding a client id and no product.
 *
 * So the route resolves what those callers could not: it reads the client,
 * takes the first product they bought and replaces itself with that product's
 * page. `replace`, so a Back out of the product does not bounce off this route
 * and forward again.
 *
 * <h2>When there is no product</h2>
 *
 * A client can be recorded before anything is bought, and a boarded client
 * whose journeys are not instantiated yet groups to nothing. Neither is an
 * error — there is simply no product page to land on — so both go to the list,
 * which is where a reader can pick a different client.
 */
export function ObClientRedirect() {
  const params = useParams<{ obClientId: string }>()
  const obClientId = Number(params.obClientId)

  const client = useGetObClient(obClientId, { query: { enabled: Number.isFinite(obClientId) } })
  const journeys = React.useMemo(
    () => client.data?.data?.journeys ?? [],
    [client.data?.data?.journeys],
  )
  const products = React.useMemo(() => groupJourneysByProduct(journeys), [journeys])

  // A 404 here is the scope guard doing its job as much as it is a stale id,
  // and the two are indistinguishable by design (CLAUDE.md's row-scoping rule).
  // The list says "no such client" far better than a blank forwarder can.
  if (client.isError || !Number.isFinite(obClientId)) {
    return <Navigate to="/onboarding/clients" replace />
  }

  if (client.isPending) {
    return (
      <div className="mx-auto w-full max-w-[1280px] p-6">
        <div className="flex flex-col gap-4" role="status" aria-label="Opening client">
          <Skeleton className="h-7 w-64" />
          <Skeleton className="h-14 w-full" />
        </div>
      </div>
    )
  }

  const first = products[0]
  if (!first) return <Navigate to="/onboarding/clients" replace />

  return <Navigate to={`/onboarding/clients/${obClientId}/products/${first.product.id}`} replace />
}
