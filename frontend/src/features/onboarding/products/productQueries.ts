import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import http, { ApiError, BASE, getAccessToken, newIdempotencyKey } from '@/api/http'
import {
  getListObProductsQueryKey,
  listObProducts,
} from '@/api/generated/onboarding-masters/onboarding-masters'
import type { ObProduct } from '@/api/generated/model/obProduct'
import type { ObProductListResponse } from '@/api/generated/model/obProductListResponse'
import type { ObProductWriteRequest } from '@/api/generated/model/obProductWriteRequest'

/**
 * OB-07's product half — the data layer for the Products master.
 *
 * The same two things the generated client structurally cannot express, for
 * the reasons `implementationStageQueries.ts` and its neighbours give: orval
 * omits header parameters, so `Idempotency-Key` on the create and `If-Match`
 * on the edit are hand-set, and `http()` drops the response object, so the
 * `ETag` the `PATCH` needs has to come off a plain `fetch`. Delete the
 * hand-written parts the day orval emits header params and a response-aware
 * mutator.
 */

/**
 * Every query under `/onboarding/products` — the generated list key's bare
 * prefix, so one invalidation reaches the master's own list, the OB-04
 * picker's `{ isActive: true }` entry and every per-product detail.
 */
const PRODUCTS_PREFIX = ['/onboarding/products'] as const

export const PRODUCT_KEY = (productId: number) => ['/onboarding/products', productId] as const

export interface ProductWithEtag {
  product: ObProduct
  /** Sent back as `If-Match`. Null if the server did not supply one. */
  etag: string | null
}

/**
 * The whole catalogue, retired products included — which is what the master
 * draws. The bare key is deliberate: the module-service create form and the
 * OB-04 picker ask for their own entries, so opening this screen can never
 * leak a retired product into a dropdown.
 *
 * <h2>The cached value is the envelope, and that is not a detail</h2>
 *
 * `getListObProductsQueryKey()` is the *generated* key, and the generated
 * `useListObProducts()` is on it too — the Module Service catalogue, the
 * journey designer and the report filter bar all call it. One key must hold
 * one shape, so this query caches `ObProductListResponse` exactly as the
 * generated hook does and unwraps to the array through `select`, which runs on
 * the way out of the cache and never writes to it.
 *
 * <p>Storing the bare array here instead is what made both screens look
 * broken. Whichever page was opened first won the cache entry, and the second
 * read it back in the other's shape: arriving at the catalogue from this
 * master, `query.data.data` was `undefined`; arriving here from the catalogue,
 * `products` was the envelope and `products.map` was not a function. A reload
 * emptied the cache and whichever page you landed on fetched its own shape and
 * worked, which is why this read as "the data only comes after a refresh"
 * rather than as two screens disagreeing about one cache entry.
 */
export function useProducts() {
  return useQuery<ObProductListResponse, ApiError, ObProduct[]>({
    queryKey: getListObProductsQueryKey(),
    queryFn: ({ signal }) => listObProducts(undefined, signal),
    select: (body) => body.data,
  })
}

/**
 * Reads one product **and** its `ETag`.
 *
 * The tag is cached with the data, as every other master's is: fetching it at
 * submit time would read a value the user never saw, which defeats the guard.
 * The tag covers `journeyCount` and `hasActiveTemplate`, so a client boarding
 * or a template publishing while the edit dialog is open costs a reload — and
 * that is right, because those two facts are what a retire decision is made
 * against.
 */
export function useProduct(productId: number | null) {
  return useQuery<ProductWithEtag, ApiError>({
    queryKey: PRODUCT_KEY(productId ?? -1),
    enabled: productId != null,
    queryFn: async ({ signal }) => {
      const token = getAccessToken()
      const response = await fetch(`${BASE}/onboarding/products/${productId}`, {
        signal,
        credentials: 'include',
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      })
      if (!response.ok) {
        throw new ApiError(
          response.status,
          { type: 'about:blank', title: response.statusText, status: response.status },
          response,
        )
      }
      const body = (await response.json()) as { data: ObProduct }
      return { product: body.data, etag: response.headers.get('ETag') }
    },
  })
}

function invalidate(queryClient: ReturnType<typeof useQueryClient>) {
  void queryClient.invalidateQueries({ queryKey: PRODUCTS_PREFIX })
}

export function useCreateProduct() {
  const queryClient = useQueryClient()

  return useMutation<ObProduct, ApiError, ObProductWriteRequest>({
    mutationFn: async (data) => {
      const body = await http<{ data: ObProduct }>({
        url: '/onboarding/products',
        method: 'POST',
        headers: { 'Idempotency-Key': newIdempotencyKey() },
        data,
      })
      return body.data
    },
    onSuccess: () => invalidate(queryClient),
  })
}

/**
 * Rename or retire — and there is no delete.
 *
 * `isActive: false` is how a product goes away: it drops out of the OB-04
 * picker and the module-service form, and every journey already instantiated
 * from it keeps running. The code is sent back unchanged; the server refuses
 * a different one, because `ob_client_applications` and `ob_journey_templates`
 * point at the row by id while every mail and report names it by code.
 */
export function useUpdateProduct() {
  const queryClient = useQueryClient()

  return useMutation<
    ObProduct,
    ApiError,
    { productId: number; data: ObProductWriteRequest; etag: string | null }
  >({
    mutationFn: async ({ productId, data, etag }) => {
      const body = await http<{ data: ObProduct }>({
        url: `/onboarding/products/${productId}`,
        method: 'PATCH',
        // `*` only when the server never gave us a tag. Sending it routinely
        // would disable the guard for every client.
        headers: { 'If-Match': etag ?? '*' },
        data,
      })
      return body.data
    },
    onSuccess: () => invalidate(queryClient),
  })
}
