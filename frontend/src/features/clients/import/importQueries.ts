import { useMutation } from '@tanstack/react-query'

import type { ImportUploadResponse, ImportUploadResponseData } from '@/api/generated/model'
import http, { ApiError, BASE, getAccessToken, type Problem } from '@/api/http'

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

// ── B-032 · step 2 ────────────────────────────────────────────────────────────

/**
 * §4B.3's caps, restated on the client — and restated is the right word.
 *
 * The server enforces these; nothing here is a security control, and a check the
 * browser makes is one an attacker skips. What it buys is the ordinary case:
 * telling somebody their 40 MB export is too big **without uploading 40 MB
 * first**, over whatever connection they happen to be on. A refusal that arrives
 * after two minutes of progress bar is a worse refusal than the same words
 * arriving instantly.
 *
 * The row limit is deliberately absent — rows are not knowable without parsing
 * the file, and guessing from the byte count would refuse a large-but-legal file
 * or wave through a small illegal one. That one is the server's alone.
 */
export const MAX_UPLOAD_BYTES = 5 * 1024 * 1024

/**
 * What the file picker offers and the drop zone accepts.
 *
 * `.xls` is **not** here, and the omission is the visible half of a decision the
 * backend documents at length: Excel 97–2003 is a binary container with no XML
 * to stream, so reading it means the whole-workbook reader §4B.3's step 2 exists
 * to avoid. Leaving it in the picker would let a user choose a file that is then
 * refused by the server — the refusal names the fix, but not offering it in the
 * first place is better than explaining it afterwards.
 */
export const ACCEPTED_EXTENSIONS = ['.xlsx', '.csv'] as const

/** The problem `type` URIs step 2 branches on. Never match on `title` or `detail`. */
export const IMPORT_PROBLEM = {
  tooLarge: 'import-too-large',
  unsupported: 'import-unsupported-file',
  unreadable: 'import-unreadable-file',
} as const

export type StagedUploadSummary = ImportUploadResponseData

export interface UploadRequest {
  file: File
  /** Which sheet to read. Omitted on the first upload — the server takes the first. */
  sheet?: string
  /** The `uploadId` this supersedes, so switching sheets does not leak staging slots. */
  replaces?: string
}

/**
 * `POST /imports/{schema}/upload`, by hand rather than through the generated
 * `useUploadImportFile`.
 *
 * ⚠ **This exists because of a defect, not a preference — and it is the same
 * defect `features/tickets/attachments/uploadTicketAttachment.ts` documents.**
 * Orval emits
 *
 *     headers: { 'Content-Type': 'multipart/form-data' }
 *
 * on the generated call, and `api/http.ts` spreads caller headers *after* the
 * branch that deliberately omits a content type for a `FormData` body, so the
 * generated header wins. A multipart body is unparseable without the `boundary`
 * parameter, and the browser adds a boundary only when it is left to set the
 * header itself. A real Spring `@RequestPart` answers 400 or 500 for every one
 * of them.
 *
 * The fix belongs in `api/http.ts` — Stream D's, one line, drop an incoming
 * `Content-Type` when the body is `FormData` — or in the generator, and
 * `api/generated/` is never hand-edited. So this sends the same request without
 * the header, stays correct when that fix lands, and can be deleted in favour of
 * the generated hook on the day it does. Two features now carry the same
 * workaround, which is the argument for fixing it once.
 *
 * Everything else comes from the generated code — the response type and the URL
 * shape — so a contract change still reaches this.
 */
export function uploadImportFile(
  schema: ImportSchemaKey,
  { file, sheet, replaces }: UploadRequest,
  signal?: AbortSignal,
): Promise<ImportUploadResponse> {
  const form = new FormData()
  form.append('file', file)

  return http<ImportUploadResponse>({
    url: `/imports/${schema}/upload`,
    method: 'POST',
    // `http()` drops undefined values, so a first upload sends no query string
    // rather than `?sheet=undefined`.
    params: { sheet, replaces },
    data: form,
    signal,
  })
}

/** The step-2 mutation. Errors surface as `ApiError`, branched on in the page. */
export function useUploadImportFile(schema: ImportSchemaKey) {
  return useMutation<ImportUploadResponse, ApiError, UploadRequest>({
    mutationFn: (request) => uploadImportFile(schema, request),
  })
}

/**
 * The pre-flight refusals, as a pure function.
 *
 * Pure so it can be tested without a DOM and reused by both the drop zone and
 * the file input — two entry points that must agree, and that would otherwise
 * each grow their own slightly different opinion of what a `.xlsx` is.
 *
 * @returns the reason to refuse, or `null` to send it
 */
export function rejectionReason(file: File): string | null {
  const name = file.name.toLowerCase()

  if (name.endsWith('.xls')) {
    // Named separately from "not one of ours" because the fix is specific and
    // the user is holding a file their own spreadsheet opens without complaint.
    return 'Excel 97–2003 workbooks (.xls) are not accepted. Open the file, choose Save As, and pick Excel Workbook (.xlsx).'
  }
  if (!ACCEPTED_EXTENSIONS.some((extension) => name.endsWith(extension))) {
    return `${file.name} is not a spreadsheet this import reads. Upload a .xlsx or .csv file.`
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    return `${file.name} is ${formatBytes(file.size)}, and the limit is 5 MB. Split it and import the parts.`
  }
  if (file.size === 0) {
    return `${file.name} is empty.`
  }
  return null
}

export function formatBytes(bytes: number): string {
  return bytes >= 1024 * 1024
    ? `${(bytes / 1024 / 1024).toFixed(1)} MB`
    : `${Math.max(1, Math.round(bytes / 1024))} KB`
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
