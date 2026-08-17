import { useMutation } from '@tanstack/react-query'

import { ApiError, BASE, getAccessToken, type Problem } from '@/api/http'

/**
 * B-031 · S-34 step 1's data layer.
 *
 * ## Why this is not `useDownloadImportTemplate` from the generated client
 *
 * Orval generates one, and it works — it returns a `Blob`. What it cannot return
 * is the **file name**, because `http()` parses a body and drops the `Response`.
 * The server names this file (`Content-Disposition`), and a download that ignores
 * that name has to reconstruct it here from the schema key: two places that must
 * agree about a string, with nothing to make them.
 *
 * So this reads the header off a plain `fetch`, exactly as `useClient` in the
 * parent folder reads `ETag` off one, for the same structural reason and with the
 * same note attached: **delete this the day `http()` exposes response headers.**
 *
 * The generated hook is also a `useQuery`, which is the wrong shape entirely — a
 * download is an event, not cached state. Fetching a workbook on mount and again
 * on every window focus is not what the button means.
 */

/** The schemas the contract registers. `users` arrives with B-038. */
export type ImportSchemaKey = 'clients' | 'users'

export interface DownloadedTemplate {
  blob: Blob
  /** What the browser saves it as — the server's name, not a guess. */
  filename: string
}

/**
 * Fetches the template and hands back the bytes and the name.
 *
 * <p>Deliberately does not save the file itself: a hook that reaches for
 * `document` is a hook that cannot be tested without one, and the saving half is
 * three lines of DOM that belong next to the click.
 */
export async function fetchImportTemplate(
  schema: ImportSchemaKey,
  signal?: AbortSignal,
): Promise<DownloadedTemplate> {
  const token = getAccessToken()
  const response = await fetch(`${BASE}/imports/${schema}/template`, {
    signal,
    credentials: 'include',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })

  if (!response.ok) {
    throw new ApiError(response.status, await readProblem(response), response)
  }

  return {
    blob: await response.blob(),
    filename: filenameFrom(response.headers.get('Content-Disposition'), schema),
  }
}

/**
 * `attachment; filename="clients-import-template.xlsx"` → the name inside it.
 *
 * Falls back to the same shape the server builds rather than to something
 * generic: a proxy that strips the header should cost the user a correct name,
 * not leave them with `download.xlsx` in their Downloads folder.
 */
export function filenameFrom(header: string | null, schema: ImportSchemaKey): string {
  const quoted = header?.match(/filename="([^"]+)"/)
  const bare = header?.match(/filename=([^;]+)/)
  const name = quoted?.[1] ?? bare?.[1]?.trim()
  return name && name.length > 0 ? name : `${schema}-import-template.xlsx`
}

/**
 * Hands the blob to the browser as a download.
 *
 * The object URL is revoked afterwards — without it the workbook stays resident
 * for the life of the tab, and an admin who downloads the template five times
 * while filling it in has five copies of it in memory.
 */
export function saveBlob({ blob, filename }: DownloadedTemplate): void {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}

/** The button's mutation: fetch, then save. Errors surface as `ApiError`. */
export function useDownloadImportTemplate() {
  return useMutation<DownloadedTemplate, ApiError, ImportSchemaKey>({
    mutationFn: (schema) => fetchImportTemplate(schema),
    onSuccess: saveBlob,
  })
}

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
