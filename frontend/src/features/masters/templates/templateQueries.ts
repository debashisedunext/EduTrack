import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import http, { ApiError, BASE, getAccessToken, newIdempotencyKey } from '@/api/http'
import { getListWorkflowTemplatesQueryKey } from '@/api/generated/masters/masters'
import type { TemplateMapping } from '@/api/generated/model/templateMapping'
import type { TemplateMappingEntry } from '@/api/generated/model/templateMappingEntry'
import type { TemplateResolution } from '@/api/generated/model/templateResolution'
import type { WorkflowTemplateDetail } from '@/api/generated/model/workflowTemplateDetail'
import type { WorkflowTemplatePatchRequest } from '@/api/generated/model/workflowTemplatePatchRequest'
import type { WorkflowTemplateWriteRequest } from '@/api/generated/model/workflowTemplateWriteRequest'

/**
 * B-041 · S-13 tab 3's data layer.
 *
 * The same two things the generated client structurally cannot express, for the
 * reasons `stageQueries.ts` and five masters before it give: orval omits header
 * parameters, so `Idempotency-Key` is hand-set, and `http()` drops the response
 * object, so an `ETag` has to come off a plain `fetch`.
 *
 * **Two tags here rather than one, and they cover different things.** The
 * template's own tag moves when its name, its flags or any of its three counts
 * change; the mapping set's tag moves when a rule is added or removed. Each
 * write takes the one that matches what it replaces — which is the split
 * `stageQueries.ts` drew between `useStage` and `useStages`, and it matters more
 * here because a template's tag is moved by tab 2 editing stages the tab-3 user
 * never looked at.
 */

export const TEMPLATE_KEY = (templateId: number) =>
  ['/masters/workflow-templates', templateId] as const

export const MAPPINGS_KEY = (templateId: number) =>
  ['/masters/workflow-templates', templateId, 'mappings'] as const

export const RESOLUTION_KEY = (projectId: number | null, taskTypeId: number | null) =>
  ['/masters/workflow-templates', 'resolution', projectId, taskTypeId] as const

export interface TemplateWithEtag {
  template: WorkflowTemplateDetail
  etag: string | null
}

export interface MappingsWithEtag {
  mappings: TemplateMapping[]
  /** Sent back as `If-Match` on the replace. Null if the server did not supply one. */
  etag: string | null
}

/**
 * One template and its tag.
 *
 * The tag is cached with the data deliberately, as every other master's is:
 * fetching it at submit time would read a value the user never saw, which
 * defeats the guard entirely. What is being detected is that the row changed
 * between the moment they read it and the moment they saved.
 */
export function useTemplate(templateId: number | null) {
  return useQuery<TemplateWithEtag, ApiError>({
    queryKey: TEMPLATE_KEY(templateId ?? -1),
    enabled: templateId != null,
    queryFn: async ({ signal }) => {
      const response = await authedFetch(`/masters/workflow-templates/${templateId}`, signal)
      const body = (await response.json()) as { data: WorkflowTemplateDetail }
      return { template: body.data, etag: response.headers.get('ETag') }
    },
  })
}

/**
 * One template's routing rules and their tag.
 *
 * The template's own tag cannot stand in for this one. It moves when a stage is
 * added on tab 2, and the rules did not change — so a replace preconditioned on
 * it would be refused for an edit that has nothing to do with routing.
 */
export function useTemplateMappings(templateId: number | null) {
  return useQuery<MappingsWithEtag, ApiError>(mappingsQueryOptions(templateId))
}

/**
 * The same query as an options object, so a caller that needs several
 * templates' rules at once can hand them all to `useQueries` — B-051's Journey
 * column on S-17 does, because a grid mixing projects needs the whole routing
 * table rather than one template's slice of it.
 *
 * Split out rather than copied: the tab and the grid must read the same cache
 * entry, or an Admin who edits a rule on S-13 would see the ticket list keep
 * the old routing until a reload. `invalidate` below already clears this key.
 */
export function mappingsQueryOptions(templateId: number | null) {
  return {
    queryKey: MAPPINGS_KEY(templateId ?? -1),
    enabled: templateId != null,
    queryFn: async ({ signal }: { signal?: AbortSignal }) => {
      const response = await authedFetch(
        `/masters/workflow-templates/${templateId}/mappings`,
        signal,
      )
      const body = (await response.json()) as { data: TemplateMapping[] }
      return { mappings: body.data, etag: response.headers.get('ETag') } satisfies MappingsWithEtag
    },
  }
}

/**
 * What one project × task type resolves to, and which rung answered.
 *
 * A plain `http()` — no tag, because there is no row here to precondition a
 * write on. The screen uses it as a checker: pick a pair, see where a ticket
 * raised on it would go, and see whether that is a rule somebody wrote or the
 * default nobody chose.
 *
 * Both arguments may be null, and null is a question rather than a missing
 * value: "what does this task type resolve to on a project with no rule of its
 * own?" They are therefore always enabled — there is no combination that is not
 * a legitimate query.
 */
export function useTemplateResolution(projectId: number | null, taskTypeId: number | null) {
  return useQuery<TemplateResolution, ApiError>({
    queryKey: RESOLUTION_KEY(projectId, taskTypeId),
    queryFn: async ({ signal }) => {
      const params = new URLSearchParams()
      if (projectId != null) params.set('projectId', String(projectId))
      if (taskTypeId != null) params.set('taskTypeId', String(taskTypeId))
      const query = params.toString()
      const body = await http<{ data: TemplateResolution }>({
        url: `/masters/workflow-templates/resolution${query ? `?${query}` : ''}`,
        method: 'GET',
        signal,
      })
      return body.data
    },
  })
}

/**
 * Everything a template write touches.
 *
 * The list goes because `listWorkflowTemplates` carries `name`, `isDefault` and
 * `stageCount`, all of which a write here can move — and because **a write to
 * one template can change another**: promoting a default clears the flag from
 * whichever template held it, so invalidating only the row that was edited would
 * leave the old default rendering as default until a reload.
 *
 * Every resolution goes too, and that is the broad one. A rule change or a moved
 * default re-answers pairs the screen has already asked about, and there is no
 * way to tell from the mutation which ones — the ladder means a single rule can
 * change the answer for every pair that was falling through it.
 */
function invalidate(
  queryClient: ReturnType<typeof useQueryClient>,
  templateId?: number,
) {
  void queryClient.invalidateQueries({ queryKey: getListWorkflowTemplatesQueryKey() })
  void queryClient.invalidateQueries({
    queryKey: ['/masters/workflow-templates', 'resolution'],
  })
  if (templateId != null) {
    void queryClient.invalidateQueries({ queryKey: TEMPLATE_KEY(templateId) })
    void queryClient.invalidateQueries({ queryKey: MAPPINGS_KEY(templateId) })
  }
  // Every template's detail, not only the one edited — see above. Cheap, since
  // the tab holds one at a time.
  void queryClient.invalidateQueries({
    predicate: (q) => q.queryKey[0] === '/masters/workflow-templates',
  })
}

export function useCreateTemplate() {
  const queryClient = useQueryClient()

  return useMutation<WorkflowTemplateDetail, ApiError, WorkflowTemplateWriteRequest>({
    mutationFn: async (data) => {
      const body = await http<{ data: WorkflowTemplateDetail }>({
        url: '/masters/workflow-templates',
        method: 'POST',
        headers: { 'Idempotency-Key': newIdempotencyKey() },
        data,
      })
      return body.data
    },
    onSuccess: (template) => invalidate(queryClient, template.id),
  })
}

export function useUpdateTemplate() {
  const queryClient = useQueryClient()

  return useMutation<
    WorkflowTemplateDetail,
    ApiError,
    { templateId: number; data: WorkflowTemplatePatchRequest; etag: string | null }
  >({
    mutationFn: async ({ templateId, data, etag }) => {
      const body = await http<{ data: WorkflowTemplateDetail }>({
        url: `/masters/workflow-templates/${templateId}`,
        method: 'PATCH',
        // `*` only when the server never gave us a tag. Sending it routinely
        // would disable the guard for every client, which is the failure the
        // whole mechanism exists to prevent.
        headers: { 'If-Match': etag ?? '*' },
        data,
      })
      return body.data
    },
    onSuccess: (_t, { templateId }) => invalidate(queryClient, templateId),
  })
}

/**
 * The delete, and it is refused far more often than it succeeds.
 *
 * `If-Match` is **required** by the server here rather than optional, so `etag`
 * is not defaulted to `*`: the whole guard is that two counts are zero, both are
 * inside the tag, and a client that shrugged and sent `*` would be asking the
 * server to skip the one check that makes the operation safe.
 */
export function useDeleteTemplate() {
  const queryClient = useQueryClient()

  return useMutation<void, ApiError, { templateId: number; etag: string | null }>({
    mutationFn: async ({ templateId, etag }) => {
      await http<void>({
        url: `/masters/workflow-templates/${templateId}`,
        method: 'DELETE',
        headers: etag ? { 'If-Match': etag } : {},
      })
    },
    onSuccess: () => invalidate(queryClient),
  })
}

export function useReplaceTemplateMappings() {
  const queryClient = useQueryClient()

  return useMutation<
    TemplateMapping[],
    ApiError,
    { templateId: number; mappings: TemplateMappingEntry[]; etag: string | null }
  >({
    mutationFn: async ({ templateId, mappings, etag }) => {
      const body = await http<{ data: TemplateMapping[] }>({
        url: `/masters/workflow-templates/${templateId}/mappings`,
        method: 'PUT',
        headers: { 'If-Match': etag ?? '*' },
        data: { mappings },
      })
      return body.data
    },
    onSuccess: (_rows, { templateId }) => invalidate(queryClient, templateId),
  })
}

/**
 * A `fetch` rather than `http()`, because the `ETag` is the point.
 *
 * Copied from `stageQueries.ts` rather than shared, on the precedent five
 * masters have now set: the alternative is an `api/` helper four streams edit,
 * and fifteen lines duplicated is cheaper than a shared file with four owners.
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
