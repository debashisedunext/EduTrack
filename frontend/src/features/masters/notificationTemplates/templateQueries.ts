import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import http, { ApiError, BASE, getAccessToken, newIdempotencyKey } from '@/api/http'
import {
  getListNotificationTemplatesQueryKey,
  getGetNotificationTemplateVocabularyQueryKey,
} from '@/api/generated/masters/masters'
import type { NotificationTemplate } from '@/api/generated/model/notificationTemplate'
import type { NotificationTemplatePatchRequest } from '@/api/generated/model/notificationTemplatePatchRequest'
import type { NotificationTemplateVocabulary } from '@/api/generated/model/notificationTemplateVocabulary'
import type { NotificationTemplateWriteRequest } from '@/api/generated/model/notificationTemplateWriteRequest'

/**
 * B-022 · S-15's data layer.
 *
 * The same two things the generated client structurally cannot express, for the
 * reasons `roleQueries.ts`, `calendarQueries.ts`, `taskTypeQueries.ts` and
 * `priorityQueries.ts` all give: orval omits header parameters, so
 * `Idempotency-Key` is hand-set, and `http()` drops the response object, so the
 * `ETag` the `PATCH` needs as `If-Match` has to come off a plain `fetch`. Delete
 * the hand-written parts the day orval emits header params and a
 * response-aware mutator.
 */

export const TEMPLATE_KEY = (templateId: number) =>
  ['/masters/notification-templates', templateId] as const

export interface TemplateWithEtag {
  template: NotificationTemplate
  /** Sent back as `If-Match`. Null if the server did not supply one. */
  etag: string | null
}

/**
 * Every template, switched-off ones included.
 *
 * One key, unlike the priority master's two. That screen had to keep its grid's
 * list separate from the create form's, because a shared cache entry would have
 * put a retired level into a picker two Stream C screens build unfiltered. This
 * route has no second consumer to protect.
 */
export function useNotificationTemplates() {
  return useQuery<NotificationTemplate[], ApiError>({
    queryKey: getListNotificationTemplatesQueryKey(),
    queryFn: async ({ signal }) => {
      const body = await http<{ data: NotificationTemplate[] }>({
        url: '/masters/notification-templates',
        method: 'GET',
        signal,
      })
      return body.data
    },
  })
}

/**
 * The events, channels, recipients and merge tags a template is composed from.
 *
 * `staleTime: Infinity` because these are enum values: they change when the
 * server is redeployed and at no other time, so re-fetching them on every focus
 * would be a request that can only ever return the same answer. The list above
 * deliberately does *not* get the same treatment — a template is edited by
 * people, and two admins on one screen is exactly the case `If-Match` exists
 * for.
 */
export function useTemplateVocabulary() {
  return useQuery<NotificationTemplateVocabulary, ApiError>({
    queryKey: getGetNotificationTemplateVocabularyQueryKey(),
    staleTime: Infinity,
    queryFn: async ({ signal }) => {
      const body = await http<{ data: NotificationTemplateVocabulary }>({
        url: '/masters/notification-templates/vocabulary',
        method: 'GET',
        signal,
      })
      return body.data
    },
  })
}

/**
 * Reads one template **and** its `ETag`.
 *
 * The tag is cached with the data deliberately, as the working week's, the
 * role's, the task type's and the level's are: fetching it separately at submit
 * time would read a value the user never saw, which defeats the guard. The point
 * is to detect that the row changed between the read they edited and the write
 * they sent — and on this screen the thing that changed is a body somebody else
 * spent five minutes rewording.
 */
export function useNotificationTemplate(templateId: number | null) {
  return useQuery<TemplateWithEtag, ApiError>({
    queryKey: TEMPLATE_KEY(templateId ?? -1),
    enabled: templateId != null,
    queryFn: async ({ signal }) => {
      const token = getAccessToken()
      const response = await fetch(`${BASE}/masters/notification-templates/${templateId}`, {
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
      const body = (await response.json()) as { data: NotificationTemplate }
      return { template: body.data, etag: response.headers.get('ETag') }
    },
  })
}

/**
 * A save here reaches no further than this screen, which is worth stating
 * because every other master in this stream's save reaches somewhere else.
 *
 * Retiring a level changes a column on every project's SLA matrix; retiring a
 * task type changes what a project offers. Rewording a template changes what a
 * mail says — and nothing in this application reads a template. The renderer
 * that will is Stream D's worker, in another process, which holds no React
 * Query cache to invalidate. So the list and the edited row, and nothing else.
 */
function invalidate(queryClient: ReturnType<typeof useQueryClient>, templateId?: number) {
  void queryClient.invalidateQueries({ queryKey: getListNotificationTemplatesQueryKey() })
  if (templateId != null) {
    void queryClient.invalidateQueries({ queryKey: TEMPLATE_KEY(templateId) })
  }
}

export function useCreateNotificationTemplate() {
  const queryClient = useQueryClient()

  return useMutation<NotificationTemplate, ApiError, NotificationTemplateWriteRequest>({
    mutationFn: async (data) => {
      const body = await http<{ data: NotificationTemplate }>({
        url: '/masters/notification-templates',
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
 * The one write that edits, and the one that switches off — there is no delete.
 *
 * Deleting a template would not orphan a reference the way deleting a level
 * would; it would remove the *wording* for an event that goes on firing, and the
 * failure would appear as a mail that never arrives. And on the events blueprint
 * §4B.6 marks never-optional, even switching off is refused — `isMandatory` on
 * the row is what lets this screen say so before the click rather than after the
 * 409.
 */
export function useUpdateNotificationTemplate() {
  const queryClient = useQueryClient()

  return useMutation<
    NotificationTemplate,
    ApiError,
    { templateId: number; data: NotificationTemplatePatchRequest; etag: string | null }
  >({
    mutationFn: async ({ templateId, data, etag }) => {
      const body = await http<{ data: NotificationTemplate }>({
        url: `/masters/notification-templates/${templateId}`,
        method: 'PATCH',
        // `*` only when the server never gave us a tag. Sending it routinely
        // would disable the guard for every client, which is the failure the
        // whole mechanism exists to prevent.
        headers: { 'If-Match': etag ?? '*' },
        data,
      })
      return body.data
    },
    onSuccess: (_template, { templateId }) => invalidate(queryClient, templateId),
  })
}
