import { useMutation, useQueryClient } from '@tanstack/react-query'

import { ApiError, BASE, getAccessToken, type Problem } from '@/api/http'
import { saveBlob, type DownloadedTemplate } from '@/features/imports/importQueries'

/**
 * OB-07 · the Module Service import dialog's data layer.
 *
 * Preview and commit are the generated `usePreviewObModuleServiceImport` /
 * `useCommitObModuleServiceImport` hooks, called directly in
 * `ModuleServiceImportDialog.tsx` — see its own note on why. What this file
 * adds is the two things the generated client cannot do:
 *
 * 1. **The template download.** For the same reason `importQueries.ts` (B-031)
 *    gives for its `fetchImportTemplate`: `http()` parses the body and drops
 *    the `Response`, so the server-supplied filename in `Content-Disposition`
 *    is unreachable from the generated hook. Read off a plain `fetch` instead
 *    and hand the blob to `saveBlob` unchanged.
 * 2. **The invalidation a commit needs**, which is wider than one template —
 *    see {@link useInvalidateAfterModuleServiceImport}.
 */

/**
 * The four-column workbook. Takes no id: the file is the same for every
 * product, and the product is chosen at upload time rather than baked into
 * the download.
 */
export async function fetchModuleServiceImportTemplate(
  signal?: AbortSignal,
): Promise<DownloadedTemplate> {
  const token = getAccessToken()
  const response = await fetch(`${BASE}/onboarding/module-service-import/template`, {
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
  return name && name.length > 0 ? name : 'module-service-import-template.xlsx'
}

export function useDownloadModuleServiceImportTemplate() {
  return useMutation<DownloadedTemplate, ApiError, void>({
    mutationFn: () => fetchModuleServiceImportTemplate(),
    onSuccess: saveBlob,
  })
}

/**
 * Everything a successful commit leaves stale.
 *
 * <p>Wider than the task import this replaced, which invalidated the one
 * template it wrote to. One file now creates and rewrites **several** Module
 * Services at once, and the dialog does not know their ids — the result
 * carries counts, not rows. So the whole `/onboarding/journey-templates`
 * prefix goes, which covers both the catalogue's list read and every cached
 * detail under it. A commit is rare and deliberate; refetching a few extra
 * entries costs less than a catalogue that shows the state from before the
 * import.
 */
export function useInvalidateAfterModuleServiceImport() {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: ['/onboarding/journey-templates'] })
}
