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

/**
 * The list the catalogue page actually renders its cards from —
 * `useListObJourneyTemplates()`, whose generated key is the bare path.
 *
 * Invalidating `PRODUCTS_KEY` alone was why the ↑/↓ buttons looked broken:
 * the PUT succeeded and `sequence` moved in the database, but the only query
 * holding the rows the cards are sorted by was never refetched, so the two
 * services stayed exactly where they were until a manual reload. A write that
 * reorders templates has to invalidate the templates.
 */
const TEMPLATES_KEY = ['/onboarding/journey-templates'] as const

function invalidateCatalogue(queryClient: ReturnType<typeof useQueryClient>) {
  void queryClient.invalidateQueries({ queryKey: TEMPLATES_KEY })
  // `sequence`, `activeTemplateId` and `dependsOnTemplateIds` are on the
  // product read too (ObProductDtos.Product), so OB-07's cards and the OB-04
  // picker would otherwise disagree about the order until one of them refetched.
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
    onSuccess: () => invalidateCatalogue(queryClient),
  })
}

/**
 * The "Depends on" picker, which is multi-select. `If-Match` is required by
 * the route — `journeyTemplateQueries.ts`'s `useReorderJourneyTemplateSteps`
 * makes the identical call for the identical reason, never defaulted to `*`
 * on a null tag: the route itself refuses a missing precondition with `428`.
 *
 * `dependsOnTemplateIds` is the whole desired set on every call, not a delta
 * — the route's own contract, and the reason two ticks in a row cannot
 * interleave into a set neither of them asked for. An empty array clears
 * every dependency.
 */
export function useUpdateModuleServiceDependsOn() {
  const queryClient = useQueryClient()

  return useMutation<
    ObJourneyTemplate,
    ApiError,
    { templateId: number; dependsOnTemplateIds: number[]; etag: string | null }
  >({
    mutationFn: async ({ templateId, dependsOnTemplateIds, etag }) => {
      const body = await http<{ data: ObJourneyTemplate }>({
        url: `/onboarding/journey-templates/${templateId}/depends-on`,
        method: 'PUT',
        headers: { 'If-Match': etag ?? '*' },
        data: { dependsOnTemplateIds },
      })
      return body.data
    },
    onSuccess: () => invalidateCatalogue(queryClient),
  })
}

/**
 * C-124 · the card's "Edit details" — rename a Module Service, or move it to
 * another product.
 *
 * Hand-written for the same reason as the two above: orval drops header
 * parameters, and this route requires `If-Match` rather than merely accepting
 * it — `428` without one.
 *
 * The `PATCH` renames **every version** of the service, and `invalidateCatalogue`
 * already reaches all of them: `JOURNEY_TEMPLATE_KEY(id)` is
 * `['/onboarding/journey-templates', id]`, so `TEMPLATES_KEY` is a *prefix* of
 * every per-template detail entry and React Query's prefix matching drops the
 * whole family. Worth saying out loud, because the alternative reading — that
 * only the list is refreshed — would leave the designer page rendering the old
 * name for whichever version happened to be open.
 */
export function useUpdateModuleService() {
  const queryClient = useQueryClient()

  return useMutation<
    ObJourneyTemplate,
    ApiError,
    { templateId: number; name: string; productId: number | null; etag: string | null }
  >({
    mutationFn: async ({ templateId, name, productId, etag }) => {
      const body = await http<{ data: ObJourneyTemplate }>({
        url: `/onboarding/journey-templates/${templateId}`,
        method: 'PATCH',
        headers: { 'If-Match': etag ?? '*' },
        data: { name, productId },
      })
      return body.data
    },
    onSuccess: () => invalidateCatalogue(queryClient),
  })
}

/**
 * C-124 · the card's Delete — the service and every version of it.
 *
 * No `If-Match`, and that is the route's own contract rather than an omission:
 * a precondition protects a lost update and there is nothing here to lose,
 * while the race that does matter — a client boarding between the read and the
 * delete — is settled inside the server's transaction and could never have
 * moved an `ETag` derived from the template's content.
 *
 * Written by hand anyway, so both of this screen's destructive writes read the
 * same way and share one invalidation.
 */
export function useDeleteModuleService() {
  const queryClient = useQueryClient()

  return useMutation<void, ApiError, { templateId: number }>({
    mutationFn: async ({ templateId }) => {
      await http<void>({
        url: `/onboarding/journey-templates/${templateId}`,
        method: 'DELETE',
      })
    },
    onSuccess: () => invalidateCatalogue(queryClient),
  })
}
