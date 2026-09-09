import { useMutation, useQueryClient } from '@tanstack/react-query'
import http, { ApiError } from '@/api/http'
import type { ObJourneyTemplate } from '@/api/generated/model/obJourneyTemplate'

/**
 * C-123 · the Module Service catalogue page's two writes.
 *
 * Hand-written rather than the generated `useReorderObJourneyTemplateCatalogue`
 * / `useUpdateObJourneyTemplateDependsOn` hooks, on `journeyTemplateQueries.ts`'s
 * own precedent for this exact screen family: orval drops header parameters,
 * so `If-Match` on the depends-on picker has to be hand-set the same way the
 * designer's own step reorder already does it.
 */

const PRODUCTS_KEY = ['/onboarding/products'] as const

function invalidateProducts(queryClient: ReturnType<typeof useQueryClient>) {
  void queryClient.invalidateQueries({ queryKey: PRODUCTS_KEY })
}

/**
 * The catalogue's ↑/↓ control. No `If-Match` — the route's own contract note
 * is that a full-list replace spanning every active template has no single
 * resource for a precondition to protect.
 */
export function useReorderModuleServiceCatalogue() {
  const queryClient = useQueryClient()

  return useMutation<void, ApiError, { templateIds: number[] }>({
    mutationFn: async ({ templateIds }) => {
      await http<void>({
        url: '/onboarding/journey-templates/order',
        method: 'PUT',
        data: { templateIds },
      })
    },
    onSuccess: () => invalidateProducts(queryClient),
  })
}

/**
 * The "Service depends on" picker. `If-Match` is required by the route —
 * `journeyTemplateQueries.ts`'s `useReorderJourneyTemplateSteps` makes the
 * identical call for the identical reason, never defaulted to `*` on a null
 * tag: the route itself refuses a missing precondition with `428`.
 */
export function useUpdateModuleServiceDependsOn() {
  const queryClient = useQueryClient()

  return useMutation<
    ObJourneyTemplate,
    ApiError,
    { templateId: number; dependsOnTemplateId: number | null; etag: string | null }
  >({
    mutationFn: async ({ templateId, dependsOnTemplateId, etag }) => {
      const body = await http<{ data: ObJourneyTemplate }>({
        url: `/onboarding/journey-templates/${templateId}/depends-on`,
        method: 'PUT',
        headers: { 'If-Match': etag ?? '*' },
        data: { dependsOnTemplateId },
      })
      return body.data
    },
    onSuccess: () => invalidateProducts(queryClient),
  })
}
