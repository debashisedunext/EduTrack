import { useMutation, useQueryClient } from '@tanstack/react-query'

import { ApiError, BASE, getAccessToken, type Problem } from '@/api/http'
import { saveBlob, type DownloadedTemplate } from '@/features/imports/importQueries'

import { JOURNEY_TEMPLATE_KEY } from './journeyTemplateQueries'

/**
 * OB-07 · the task-import dialog's data layer.
 *
 * Preview and commit are thin wrappers over the generated
 * `usePreviewObJourneyTaskImport` / `useCommitObJourneyTaskImport` hooks — see
 * `TaskImportDialog.tsx` for why those are called directly there instead of
 * through here. What this file adds is the one thing the generated client
 * cannot: the template download, for the same reason `importQueries.ts`
 * (B-031) gives for its own `fetchImportTemplate` — `http()` parses the body
 * and drops the `Response`, so the server-supplied filename in
 * `Content-Disposition` is unreachable from the generated hook. Read off a
 * plain `fetch` instead, and handed to `saveBlob` unchanged.
 */
export async function fetchTaskImportTemplate(
  templateId: number,
  signal?: AbortSignal,
): Promise<DownloadedTemplate> {
  const token = getAccessToken()
  const response = await fetch(`${BASE}/onboarding/journey-templates/${templateId}/task-import/template`, {
    signal,
    credentials: 'include',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })

  if (!response.ok) {
    throw new ApiError(response.status, await readProblem(response), response)
  }

  return {
    blob: await response.blob(),
    filename: filenameFrom(response.headers.get('Content-Disposition')),
  }
}

/** Duplicated from `importQueries.ts` — `http.ts`'s own copy is not exported. */
async function readProblem(response: Response): Promise<Problem> {
  try {
    const body = (await response.json()) as Partial<Problem>
    if (body && typeof body === 'object' && body.title) {
      return { type: 'about:blank', status: response.status, ...body } as Problem
    }
  } catch {
    /* not JSON — a proxy's HTML error page, most likely */
  }
  return {
    type: 'about:blank',
    title: response.statusText || `HTTP ${response.status}`,
    status: response.status,
  }
}

function filenameFrom(header: string | null): string {
  const quoted = header?.match(/filename="([^"]+)"/)
  const bare = header?.match(/filename=([^;]+)/)
  const name = quoted?.[1] ?? bare?.[1]?.trim()
  return name && name.length > 0 ? name : 'module-service-tasks-template.xlsx'
}

export function useDownloadTaskImportTemplate() {
  return useMutation<DownloadedTemplate, ApiError, number>({
    mutationFn: (templateId) => fetchTaskImportTemplate(templateId),
    onSuccess: saveBlob,
  })
}

/** Invalidates the one cache entry a successful commit leaves stale — the template detail. */
export function useInvalidateAfterTaskImport() {
  const queryClient = useQueryClient()
  return (templateId: number) =>
    queryClient.invalidateQueries({ queryKey: JOURNEY_TEMPLATE_KEY(templateId) })
}
