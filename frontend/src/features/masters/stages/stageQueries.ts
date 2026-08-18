import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import http, { ApiError, BASE, getAccessToken, newIdempotencyKey } from '@/api/http'
import { getListWorkflowTemplatesQueryKey } from '@/api/generated/masters/masters'
import type { Stage } from '@/api/generated/model/stage'
import type { StagePatchRequest } from '@/api/generated/model/stagePatchRequest'
import type { StageWriteRequest } from '@/api/generated/model/stageWriteRequest'
import type { WorkflowTemplate } from '@/api/generated/model/workflowTemplate'

/**
 * B-040 · S-13 tab 2's data layer.
 *
 * The same two things the generated client structurally cannot express, for the
 * reasons `statusQueries.ts` and four masters before it give: orval omits header
 * parameters, so `Idempotency-Key` is hand-set, and `http()` drops the response
 * object, so an `ETag` has to come off a plain `fetch`.
 *
 * **The stage list is the second collection read in the product to carry a tag**,
 * after B-039's transition matrix and for the same reason: the reorder replaces
 * the whole set and there is no per-row verb to precondition on. So `useStages`
 * is a raw `fetch` where an ordinary list would be a plain `http()`.
 */

export const STAGES_KEY = (templateId: number) =>
  ['/masters/workflow-templates', templateId, 'stages'] as const

export const STAGE_KEY = (templateId: number, stageId: number) =>
  ['/masters/workflow-templates', templateId, 'stages', stageId] as const

export interface StagesWithEtag {
  stages: Stage[]
  /** Sent back as `If-Match` on the reorder. Null if the server did not supply one. */
  etag: string | null
}

export interface StageWithEtag {
  stage: Stage
  etag: string | null
}

/**
 * Every template, for the tab's selector.
 *
 * Inactive ones included — the server returns them and the screen labels them,
 * because a template is what every historical ticket points at rather than a
 * value offered in a filter. See `StageService.templates`.
 */
export function useWorkflowTemplates() {
  return useQuery<WorkflowTemplate[], ApiError>({
    queryKey: getListWorkflowTemplatesQueryKey(),
    queryFn: async ({ signal }) => {
      const body = await http<{ data: WorkflowTemplate[] }>({
        url: '/masters/workflow-templates',
        method: 'GET',
        signal,
      })
      return body.data
    },
  })
}

/**
 * One template's ribbon **and** its tag.
 *
 * The tag is cached with the data deliberately, as every other master's is:
 * fetching it at submit time would read a value the user never saw, which
 * defeats the guard. What is being detected is that the set changed between the
 * order they dragged and the order they sent.
 */
export function useStages(templateId: number | null) {
  return useQuery<StagesWithEtag, ApiError>({
    queryKey: STAGES_KEY(templateId ?? -1),
    enabled: templateId != null,
    queryFn: async ({ signal }) => {
      const response = await authedFetch(
        `/masters/workflow-templates/${templateId}/stages`,
        signal,
      )
      const body = (await response.json()) as { data: Stage[] }
      return { stages: body.data, etag: response.headers.get('ETag') }
    },
  })
}

/**
 * One stage and its tag, for the edit dialog's `If-Match`.
 *
 * The list read cannot supply it: its tag covers the whole ribbon and moves when
 * any row changes, so a `PATCH` preconditioned on it would be refused because
 * somebody edited a different stage. Two tags, two scopes, and each write takes
 * the one that matches what it replaces.
 */
export function useStage(templateId: number | null, stageId: number | null) {
  return useQuery<StageWithEtag, ApiError>({
    queryKey: STAGE_KEY(templateId ?? -1, stageId ?? -1),
    enabled: templateId != null && stageId != null,
    queryFn: async ({ signal }) => {
      const response = await authedFetch(
        `/masters/workflow-templates/${templateId}/stages/${stageId}`,
        signal,
      )
      const body = (await response.json()) as { data: Stage }
      return { stage: body.data, etag: response.headers.get('ETag') }
    },
  })
}

/**
 * A write on one stage moves the whole ribbon's tag, so both keys go.
 *
 * Not defensive: the list tag is built from every row's content, precisely so
 * that a reorder cannot discard an edit it never saw. Leaving the list cached
 * after an edit would leave the screen holding a tag the server has already
 * moved past, and the next drag would be refused with a 412 the user has no way
 * to explain.
 *
 * The template list goes too — `stageCount` is on it, and a create changes it.
 */
function invalidate(
  queryClient: ReturnType<typeof useQueryClient>,
  templateId: number,
  stageId?: number,
) {
  void queryClient.invalidateQueries({ queryKey: STAGES_KEY(templateId) })
  void queryClient.invalidateQueries({ queryKey: getListWorkflowTemplatesQueryKey() })
  if (stageId != null) {
    void queryClient.invalidateQueries({ queryKey: STAGE_KEY(templateId, stageId) })
  }
}

export function useCreateStage() {
  const queryClient = useQueryClient()

  return useMutation<Stage, ApiError, { templateId: number; data: StageWriteRequest }>({
    mutationFn: async ({ templateId, data }) => {
      const body = await http<{ data: Stage }>({
        url: `/masters/workflow-templates/${templateId}/stages`,
        method: 'POST',
        headers: { 'Idempotency-Key': newIdempotencyKey() },
        data,
      })
      return body.data
    },
    onSuccess: (_stage, { templateId }) => invalidate(queryClient, templateId),
  })
}

/**
 * The only write that edits — and there is no delete anywhere on this screen.
 *
 * §7.4: stages in use are deprecated, never deleted, because deleting one breaks
 * every historical ribbon that referenced its code. The flag that makes
 * deprecation possible is B-042, so until it lands this screen offers no removal
 * at all rather than a delete B-042 would have to take away.
 */
export function useUpdateStage() {
  const queryClient = useQueryClient()

  return useMutation<
    Stage,
    ApiError,
    { templateId: number; stageId: number; data: StagePatchRequest; etag: string | null }
  >({
    mutationFn: async ({ templateId, stageId, data, etag }) => {
      const body = await http<{ data: Stage }>({
        url: `/masters/workflow-templates/${templateId}/stages/${stageId}`,
        method: 'PATCH',
        // `*` only when the server never gave us a tag. Sending it routinely
        // would disable the guard for every client, which is the failure the
        // whole mechanism exists to prevent.
        headers: { 'If-Match': etag ?? '*' },
        data,
      })
      return body.data
    },
    onSuccess: (_stage, { templateId, stageId }) =>
      invalidate(queryClient, templateId, stageId),
  })
}

/**
 * The drag, saved.
 *
 * `PUT` with the complete ordered list, and `If-Match` from `useStages` — the
 * write this whole tag exists for. Two Admins each dragging one row would
 * otherwise both save their own screen state, and the second would silently put
 * the first's order back.
 */
export function useReorderStages() {
  const queryClient = useQueryClient()

  return useMutation<
    Stage[],
    ApiError,
    { templateId: number; stageIds: number[]; etag: string | null }
  >({
    mutationFn: async ({ templateId, stageIds, etag }) => {
      const body = await http<{ data: Stage[] }>({
        url: `/masters/workflow-templates/${templateId}/stages/order`,
        method: 'PUT',
        headers: { 'If-Match': etag ?? '*' },
        data: { stageIds },
      })
      return body.data
    },
    onSuccess: (_stages, { templateId }) => invalidate(queryClient, templateId),
  })
}

/**
 * The `ETag` the generated client drops.
 *
 * One helper for both tag-carrying reads rather than two copies, because they
 * differ only in the path — and the day orval emits a response-aware mutator
 * this is the single function that goes.
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
