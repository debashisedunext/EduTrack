import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import http, { ApiError, BASE, getAccessToken, newIdempotencyKey } from '@/api/http'
import type { ObJourneyTemplate } from '@/api/generated/model/obJourneyTemplate'
import type { ObJourneyTemplateCreateRequest } from '@/api/generated/model/obJourneyTemplateCreateRequest'
import type { ObJourneyTemplateDetail } from '@/api/generated/model/obJourneyTemplateDetail'
import type { ObJourneyTemplateStep } from '@/api/generated/model/obJourneyTemplateStep'
import type { ObJourneyTemplateStepDoc } from '@/api/generated/model/obJourneyTemplateStepDoc'
import type { ObJourneyTemplateStepDocWriteRequest } from '@/api/generated/model/obJourneyTemplateStepDocWriteRequest'
import type { ObJourneyTemplateStepItemResponse } from '@/api/generated/model/obJourneyTemplateStepItemResponse'
import type { ObJourneyTemplateStepItemWriteRequest } from '@/api/generated/model/obJourneyTemplateStepItemWriteRequest'
import type { ObJourneyTemplateStepUpdateRequest } from '@/api/generated/model/obJourneyTemplateStepUpdateRequest'
import type { ObJourneyTemplateTaskWriteRequest } from '@/api/generated/model/obJourneyTemplateTaskWriteRequest'

/**
 * C-102 · OB-07 template designer's data layer.
 *
 * The same two gaps `stageQueries.ts` (B-040) papers over, for the identical
 * reason: orval omits header parameters, so `Idempotency-Key` on every create
 * and `If-Match` on the reorder are hand-set, and `http()` drops the response
 * object, so the `ETag` `PUT .../steps/order` requires has to come off a
 * plain `fetch`. `useJourneyTemplate` is this screen's `useStages` —
 * `GET /onboarding/journey-templates/{templateId}` is the *only* source of
 * that tag (the controller's own javadoc says so), and it is cached
 * alongside the detail for the reason `stageQueries.ts` gives: reading it
 * again at submit time would check a value the user never saw.
 *
 * **Every write here invalidates the one query key.** Unlike the masters
 * designer's stage list and per-stage reads, this screen has a single detail
 * read — steps, items and docs all nest inside it — so there is only one
 * cache entry a write could leave stale.
 */

export const JOURNEY_TEMPLATE_KEY = (templateId: number) =>
  ['/onboarding/journey-templates', templateId] as const

export interface JourneyTemplateWithEtag {
  detail: ObJourneyTemplateDetail
  /** Sent back as `If-Match` on the reorder. Null if the server did not supply one. */
  etag: string | null
}

/**
 * The template's full detail — steps, items and docs nested — **and** its tag.
 *
 * The tag is cached with the data deliberately: fetching it again when the
 * reorder is confirmed would read a value the user never saw, which defeats
 * the guard the precondition exists to provide. What is being detected is
 * that the template changed between the order staged on screen and the order
 * about to be sent.
 */
export function useJourneyTemplate(templateId: number | null) {
  return useQuery<JourneyTemplateWithEtag, ApiError>({
    queryKey: JOURNEY_TEMPLATE_KEY(templateId ?? -1),
    enabled: templateId != null,
    queryFn: async ({ signal }) => {
      const response = await authedFetch(`/onboarding/journey-templates/${templateId}`, signal)
      const body = (await response.json()) as { data: ObJourneyTemplateDetail }
      return { detail: body.data, etag: response.headers.get('ETag') }
    },
  })
}

function invalidate(queryClient: ReturnType<typeof useQueryClient>, templateId: number) {
  void queryClient.invalidateQueries({ queryKey: JOURNEY_TEMPLATE_KEY(templateId) })
  // Creating, revising or publishing a version changes what the Module
  // Service catalogue lists — `is_active` moves between rows and a new
  // service appears — and that page reads `useListObJourneyTemplates()`, a
  // different query. Without this it keeps showing the version it loaded
  // with until a manual reload.
  void queryClient.invalidateQueries({ queryKey: ['/onboarding/journey-templates'] })
}

/**
 * "+ Create journey template" — a product's first draft. Not reachable from
 * this designer's own header (there is no existing template to land the
 * screen on until one exists), but part of this data layer's contract
 * surface all the same, for whatever screen ends up offering the button —
 * OB-04's product picker is the likeliest candidate.
 */
export function useCreateJourneyTemplate() {
  const queryClient = useQueryClient()

  return useMutation<ObJourneyTemplate, ApiError, { data: ObJourneyTemplateCreateRequest }>({
    mutationFn: async ({ data }) => {
      const body = await http<{ data: ObJourneyTemplate }>({
        url: '/onboarding/journey-templates',
        method: 'POST',
        headers: { 'Idempotency-Key': newIdempotencyKey() },
        data,
      })
      return body.data
    },
    onSuccess: (template) => invalidate(queryClient, template.id),
  })
}

/**
 * Clone the active version into a new editable draft. `409` if `templateId`
 * is not the product's currently active version — the header's own "Begin
 * revision" button is hidden unless it is, so this refusal should only ever
 * be reachable by a stale screen racing another Admin's publish.
 */
export function useBeginJourneyTemplateRevision() {
  const queryClient = useQueryClient()

  return useMutation<ObJourneyTemplate, ApiError, { templateId: number }>({
    mutationFn: async ({ templateId }) => {
      const body = await http<{ data: ObJourneyTemplate }>({
        url: `/onboarding/journey-templates/${templateId}/revisions`,
        method: 'POST',
        headers: { 'Idempotency-Key': newIdempotencyKey() },
      })
      return body.data
    },
    onSuccess: (draft) => invalidate(queryClient, draft.id),
  })
}

/**
 * The draft becomes the product's active version. `422` with no steps,
 * `409` if this version has already been published — both are surfaced to
 * the caller as the server's own `problem.detail` rather than re-derived.
 */
export function usePublishJourneyTemplate() {
  const queryClient = useQueryClient()

  return useMutation<ObJourneyTemplate, ApiError, { templateId: number }>({
    mutationFn: async ({ templateId }) => {
      const body = await http<{ data: ObJourneyTemplate }>({
        url: `/onboarding/journey-templates/${templateId}/publish`,
        method: 'POST',
        headers: { 'Idempotency-Key': newIdempotencyKey() },
      })
      return body.data
    },
    onSuccess: (_template, { templateId }) => invalidate(queryClient, templateId),
  })
}

/**
 * Add a task to a stage of a draft template. Writes immediately, on this
 * designer's own architectural rule — see `JourneyTemplateDesignerPage.tsx`'s
 * header: only the ordering is staged locally, because it is the one route
 * that replaces the whole set under a single precondition.
 *
 * `templateId` is carried alongside the stage id purely to invalidate the
 * right detail query — the route itself needs only the stage, since a task is
 * created inside one.
 */
export function useAddJourneyTemplateTask() {
  const queryClient = useQueryClient()

  return useMutation<
    ObJourneyTemplateStep,
    ApiError,
    { templateId: number; stageId: number; data: ObJourneyTemplateTaskWriteRequest }
  >({
    mutationFn: async ({ stageId, data }) => {
      const body = await http<{ data: ObJourneyTemplateStep }>({
        url: `/onboarding/journey-template-stages/${stageId}/tasks`,
        method: 'POST',
        headers: { 'Idempotency-Key': newIdempotencyKey() },
        data,
      })
      return body.data
    },
    onSuccess: (_task, { templateId }) => invalidate(queryClient, templateId),
  })
}

/**
 * Edit a step of a draft — TAT, owner, sign-off, description, dependency.
 *
 * Hand-written for this file's standing reason, and one more specific to this
 * route: `If-Match` is **required** rather than merely accepted, and the tag
 * is the *template's*. A step has no read of its own to draw one from, and the
 * template's tag covers every step on it — which is the tag that notices what
 * this edit can silently destroy, somebody else's dependency change. Never
 * defaulted to `*` on a null tag, for the same reason the reorder is not: the
 * route answers `428` for a missing precondition, and `*` would turn that
 * clear signal into a confusing one.
 *
 * `clearDependsOn` exists because null and omitted are the same thing over
 * JSON, so "this step waits for nothing" has to be something a caller can say
 * rather than something they fail to say.
 */
export function useUpdateJourneyTemplateStep() {
  const queryClient = useQueryClient()

  return useMutation<
    ObJourneyTemplateStep,
    ApiError,
    {
      templateId: number
      stepId: number
      data: ObJourneyTemplateStepUpdateRequest
      etag: string | null
    }
  >({
    mutationFn: async ({ stepId, data, etag }) => {
      const body = await http<{ data: ObJourneyTemplateStep }>({
        url: `/onboarding/journey-template-steps/${stepId}`,
        method: 'PATCH',
        headers: { 'If-Match': etag ?? '*' },
        data,
      })
      return body.data
    },
    onSuccess: (_step, { templateId }) => invalidate(queryClient, templateId),
  })
}

/**
 * The OB-07 ↑/↓ control, applied to one stage's tasks in one call. Tasks in
 * every other stage keep the positions they had. `If-Match` from
 * `useJourneyTemplate` — the write this whole tag exists for. Two Admins
 * each reordering the same draft would otherwise both save their own screen
 * state, and the second would silently put the first's order back.
 *
 * `If-Match` is **required** here, never defaulted to `*` the way
 * `stageQueries.ts`'s other writes fall back on a missing tag — the reorder
 * route itself refuses a request with none (`428`), so sending `*` on a
 * null `etag` would only turn a clear signal into a confusing one.
 */
export function useReorderJourneyTemplateTasks() {
  const queryClient = useQueryClient()

  return useMutation<
    void,
    ApiError,
    { templateId: number; stageId: number; taskIds: number[]; etag: string | null }
  >({
    mutationFn: async ({ stageId, taskIds, etag }) => {
      await http<void>({
        url: `/onboarding/journey-template-stages/${stageId}/tasks/order`,
        method: 'PUT',
        headers: { 'If-Match': etag ?? '*' },
        data: { taskIds },
      })
    },
    onSuccess: (_void, { templateId }) => invalidate(queryClient, templateId),
  })
}

/**
 * Remove a step. `409`, naming the dependent step ids on the problem body's
 * `dependentStepIds`, if another step in the same template still depends on
 * this one — the designer reads that field to name the blocking steps
 * rather than showing a bare conflict.
 */
export function useRemoveJourneyTemplateStep() {
  const queryClient = useQueryClient()

  return useMutation<void, ApiError, { templateId: number; stepId: number }>({
    mutationFn: async ({ stepId }) => {
      await http<void>({
        url: `/onboarding/journey-template-steps/${stepId}`,
        method: 'DELETE',
      })
    },
    onSuccess: (_void, { templateId }) => invalidate(queryClient, templateId),
  })
}

/**
 * B-131 · resolves to the whole envelope, not just `data`.
 *
 * `backfilledJourneyCount` is the half the caller cannot work out for itself:
 * adding an item to a service that is live also puts it on the journeys
 * already running from it, and the count is the only evidence of that reaching
 * the screen. Every other mutation here unwraps to `data` — this one is the
 * exception, and it is the response shape that makes it one.
 */
export function useAddJourneyTemplateStepItem() {
  const queryClient = useQueryClient()

  return useMutation<
    ObJourneyTemplateStepItemResponse,
    ApiError,
    { templateId: number; stepId: number; data: ObJourneyTemplateStepItemWriteRequest }
  >({
    mutationFn: async ({ stepId, data }) =>
      http<ObJourneyTemplateStepItemResponse>({
        url: `/onboarding/journey-template-steps/${stepId}/items`,
        method: 'POST',
        headers: { 'Idempotency-Key': newIdempotencyKey() },
        data,
      }),
    /*
      Invalidates the template, as its siblings do. It deliberately does NOT
      reach into the journey caches the back-fill also wrote to: this screen is
      the catalogue, a project's own board refetches on its own mount, and an
      admin adding an item here is not looking at a client's journey in the
      same breath. Widening the invalidation to every journey query would
      refetch a page nobody has open.
    */
    onSuccess: (_response, { templateId }) => invalidate(queryClient, templateId),
  })
}

export function useRemoveJourneyTemplateStepItem() {
  const queryClient = useQueryClient()

  return useMutation<void, ApiError, { templateId: number; itemId: number }>({
    mutationFn: async ({ itemId }) => {
      await http<void>({
        url: `/onboarding/journey-template-step-items/${itemId}`,
        method: 'DELETE',
      })
    },
    onSuccess: (_void, { templateId }) => invalidate(queryClient, templateId),
  })
}

export function useAddJourneyTemplateStepDoc() {
  const queryClient = useQueryClient()

  return useMutation<
    ObJourneyTemplateStepDoc,
    ApiError,
    { templateId: number; stepId: number; data: ObJourneyTemplateStepDocWriteRequest }
  >({
    mutationFn: async ({ stepId, data }) => {
      const body = await http<{ data: ObJourneyTemplateStepDoc }>({
        url: `/onboarding/journey-template-steps/${stepId}/docs`,
        method: 'POST',
        headers: { 'Idempotency-Key': newIdempotencyKey() },
        data,
      })
      return body.data
    },
    onSuccess: (_doc, { templateId }) => invalidate(queryClient, templateId),
  })
}

export function useRemoveJourneyTemplateStepDoc() {
  const queryClient = useQueryClient()

  return useMutation<void, ApiError, { templateId: number; docId: number }>({
    mutationFn: async ({ docId }) => {
      await http<void>({
        url: `/onboarding/journey-template-step-docs/${docId}`,
        method: 'DELETE',
      })
    },
    onSuccess: (_void, { templateId }) => invalidate(queryClient, templateId),
  })
}

/**
 * The `ETag` the generated client drops. Identical to `stageQueries.ts`'s
 * own helper — duplicated rather than imported, because sharing it would
 * mean this feature depending on `features/masters`, which is the other
 * direction cross-feature imports are meant to go in this codebase.
 */
async function authedFetch(path: string, signal: AbortSignal | undefined) {
  const token = getAccessToken()
  const response = await fetch(`${BASE}${path}`, {
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
  return response
}
