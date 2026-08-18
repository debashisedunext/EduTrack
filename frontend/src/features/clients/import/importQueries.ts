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

// ── B-034 · step 4 ────────────────────────────────────────────────────────────

/**
 * The problem `type` URIs step 4 branches on.
 *
 * Four, not one, because each has a different remedy — and that is the whole
 * reason the server gives them separate types rather than one `import-failed`
 * (see `ImportExceptionHandler`). Collapsing them here would throw that away and
 * leave the screen parsing English to decide which button to offer.
 *
 * `unknownField` is deliberately B-033's, shared with the preset save: to a user
 * "this import has no such column" is one condition whichever request surfaced
 * it.
 */
export const PREVIEW_PROBLEM = {
  uploadUnavailable: 'import-upload-unavailable',
  incompleteMapping: 'import-incomplete-mapping',
  unknownColumn: 'import-unknown-column',
  unknownField: 'import-unknown-field',
} as const

/**
 * Which step the user has to go back to in order to fix a refusal.
 *
 * The interesting half of the response, and the reason this is a function rather
 * than a message string: an expired upload is fixed at step 2 and a bad mapping
 * at step 3, and a screen that says "something went wrong, try again" leaves the
 * user pressing the same button.
 */
export type PreviewRemedy = 'upload' | 'mapping' | 'retry'

export interface PreviewRefusal {
  message: string
  remedy: PreviewRemedy
}

/**
 * A step-4 failure, in words the user can act on.
 *
 * Branches on `problem.type`, never on `title` or `detail` — CONVENTIONS.md §3
 * makes the type the stable half and the prose the changeable one. `detail` is
 * still what is shown where the server wrote something specific, because it
 * names the actual columns; the type decides whether to trust it and where to
 * send the user next.
 */
export function previewRefusal(error: unknown): PreviewRefusal {
  if (!(error instanceof ApiError)) {
    return {
      message: 'The preview could not be run. Check your connection and try again.',
      remedy: 'retry',
    }
  }

  const detail = error.problem.detail
  if (error.is(PREVIEW_PROBLEM.uploadUnavailable)) {
    return { message: detail ?? error.problem.title, remedy: 'upload' }
  }
  if (
    error.is(PREVIEW_PROBLEM.incompleteMapping) ||
    error.is(PREVIEW_PROBLEM.unknownColumn) ||
    error.is(PREVIEW_PROBLEM.unknownField)
  ) {
    return { message: detail ?? error.problem.title, remedy: 'mapping' }
  }
  if (error.status === 403) {
    return {
      message:
        'You do not have permission to import clients. Importing is an administrator action.',
      remedy: 'retry',
    }
  }
  return {
    message: `The preview could not be run (${error.status}${detail ? ` — ${detail}` : ''}). Nothing has been written.`,
    remedy: 'retry',
  }
}

// ── B-035 · step 5 ────────────────────────────────────────────────────────────

/**
 * The problem `type` URIs step 5 branches on.
 *
 * Four of them are step 4's, deliberately — the server refuses a commit with the
 * same types and in the same order, because an incomplete mapping is not a
 * different condition for having arrived one step later. Two are this step's
 * own, and they are two rather than one for the reason all of these are separate:
 * the remedies are opposite. `nothingToCommit` means go back to your spreadsheet;
 * `rejectedRowsPresent` means most of the file is fine and you asked for
 * all-or-nothing.
 */
export const COMMIT_PROBLEM = {
  nothingToCommit: 'import-nothing-to-commit',
  rejectedRowsPresent: 'import-rejected-rows-present',
  queueFull: 'import-commit-queue-full',
} as const

/**
 * Where a commit refusal sends the user.
 *
 * `retry` is a real answer here in a way it was not at step 4: a full commit
 * queue clears on its own, so pressing the button again in a moment is the
 * correct advice rather than a shrug.
 */
export type CommitRemedy = PreviewRemedy | 'revalidate'

export interface CommitRefusal {
  message: string
  remedy: CommitRemedy
}

/**
 * A step-5 failure, in words the user can act on.
 *
 * Delegates to `previewRefusal` for the four types the two steps share, so the
 * sentence a user reads about an expired upload is the same sentence whichever
 * button produced it. Only what is genuinely new to this step is written here.
 *
 * `revalidate` is the interesting remedy and the one worth having: the preview
 * on screen described a file the server has now re-judged, and the honest
 * instruction is to run the dry run again rather than to press Import harder.
 */
export function commitRefusal(error: unknown): CommitRefusal {
  if (!(error instanceof ApiError)) {
    return {
      message: 'The import could not be started. Check your connection and try again.',
      remedy: 'retry',
    }
  }

  const detail = error.problem.detail
  if (error.is(COMMIT_PROBLEM.nothingToCommit)) {
    return { message: detail ?? error.problem.title, remedy: 'revalidate' }
  }
  if (error.is(COMMIT_PROBLEM.rejectedRowsPresent)) {
    return { message: detail ?? error.problem.title, remedy: 'revalidate' }
  }
  if (error.is(COMMIT_PROBLEM.queueFull)) {
    return { message: detail ?? error.problem.title, remedy: 'retry' }
  }

  const shared = previewRefusal(error)
  return { message: shared.message, remedy: shared.remedy }
}

/** A run that has stopped moving. Nothing polls past one of these. */
export const TERMINAL_BATCH_STATUSES = ['COMPLETED', 'FAILED'] as const

export function isTerminal(status: string | undefined): boolean {
  return status !== undefined && (TERMINAL_BATCH_STATUSES as readonly string[]).includes(status)
}

/**
 * How often the progress bar asks.
 *
 * Two seconds, which is what the contract's own note on `getImportBatch`
 * assumes. It is affordable because the route carries an `ETag` and the runner
 * flushes its counters every fifty rows, so most of these polls transfer a `304`
 * and no body at all.
 */
export const BATCH_POLL_MS = 2000

/**
 * How long to wait before asking again, or `false` to stop.
 *
 * A function rather than a ternary inside the hook so the one decision that
 * would otherwise ship broken is testable without timers: a run that has
 * finished must stop being polled, and a component test for that has to either
 * wait two real seconds or fake the clock underneath MSW. Both are flaky in a
 * way this is not.
 *
 * An unrecognised status keeps polling. A newer deploy writing a state this
 * build has not heard of must not leave a screen sitting for ever on a run that
 * was going to finish — the same tolerance `ImportBatchStatus.of` applies
 * server-side, in the same direction.
 */
export function batchPollInterval(status: string | undefined): number | false {
  return isTerminal(status) ? false : BATCH_POLL_MS
}

/**
 * What fraction of the run is done, as a percentage.
 *
 * Reads `processed` rather than `created + updated`: the rejected rows were
 * counted before the job started and are part of the file the user is watching
 * go through. Leaving them out would leave a bar on a file with six bad rows
 * permanently short of the end, which reads as a job that stalled.
 *
 * Clamped, because a bar past 100% is a rendering fault the user has no way to
 * interpret — and `total` is 0 on a batch that has not started, where dividing
 * would produce `NaN` and an empty bar with no width at all.
 */
export function progressPercent(processed: number, total: number): number {
  if (total <= 0) {
    return 0
  }
  return Math.min(100, Math.max(0, Math.round((processed / total) * 100)))
}

// ── B-036 · step 5's error report ─────────────────────────────────────────────

/**
 * Fetches the error report and hands back the bytes and the name.
 *
 * ## Why not the generated `useDownloadImportErrorReport`
 *
 * The same two reasons `fetchImportTemplate` gives one screen up, and this is
 * the second feature to hit them — which is the argument for fixing `http()`
 * rather than writing a third:
 *
 * 1. Orval generates a `useQuery`, and a download is an **event**, not cached
 *    state. Mounting the step-5 screen would fetch the workbook, and regaining
 *    window focus would fetch it again.
 * 2. `http()` parses a body and drops the `Response`, so the generated call
 *    cannot return the `Content-Disposition` name — and the server names this
 *    file per batch (`clients-import-errors-412.xlsx`) precisely so two reports
 *    in a Downloads folder say which import each came from. Reconstructing it
 *    here would be a second place that has to agree about a string.
 *
 * ## It takes the URL off the batch rather than composing one
 *
 * `errorReportUrl` is null exactly when there is nothing to download, so a
 * caller that has one has already been told the report exists. Composing
 * `/import-batches/${batchId}/error-report` here would work and would move that
 * decision into the client — where a screen could ask for a report of a run that
 * has none, and would have to render the 404 that came back.
 */
export async function fetchImportErrorReport(
  errorReportUrl: string,
  signal?: AbortSignal,
): Promise<DownloadedTemplate> {
  const token = getAccessToken()
  const response = await fetch(`${BASE}${errorReportUrl}`, {
    signal,
    credentials: 'include',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })

  if (!response.ok) {
    throw new ApiError(response.status, await readProblem(response), response)
  }

  return {
    blob: await response.blob(),
    filename: errorReportFilenameFrom(response.headers.get('Content-Disposition')),
  }
}

/**
 * The name out of `Content-Disposition`, or a plain fallback.
 *
 * Deliberately **not** `filenameFrom`, whose fallback is the template's name:
 * a proxy that strips the header would otherwise leave a user with
 * `clients-import-template.xlsx` in their Downloads folder — the name of a
 * different file, which is worse than a generic one because it is confidently
 * wrong. Without the header the batch id is not knowable from here either, so
 * the fallback says only what this is.
 */
export function errorReportFilenameFrom(header: string | null): string {
  const quoted = header?.match(/filename="([^"]+)"/)
  const bare = header?.match(/filename=([^;]+)/)
  const name = quoted?.[1] ?? bare?.[1]?.trim()
  return name && name.length > 0 ? name : 'import-errors.xlsx'
}

/** The button's mutation: fetch, then save. Errors surface as `ApiError`. */
export function useDownloadImportErrorReport() {
  return useMutation<DownloadedTemplate, ApiError, string>({
    mutationFn: (errorReportUrl) => fetchImportErrorReport(errorReportUrl),
    onSuccess: saveBlob,
  })
}

/**
 * What to say when the download fails, in words the user can act on.
 *
 * A 404 is the interesting one and the only one worth its own sentence: the
 * report was there when the batch was read and is not there now, which in
 * practice means the run was polled at the wrong moment or the object expired.
 * "Try again" is wrong advice for it, so it does not say that.
 */
export function errorReportRefusal(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return 'The error report could not be downloaded. Check your connection and try again.'
  }
  if (error.status === 404) {
    return (
      error.problem.detail ??
      'The error report for this import is no longer available. The rows it listed were not imported — re-upload the file to see them again.'
    )
  }
  if (error.status === 403) {
    return 'You do not have permission to download this import’s error report.'
  }
  return `The error report could not be downloaded (${error.status}).`
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
