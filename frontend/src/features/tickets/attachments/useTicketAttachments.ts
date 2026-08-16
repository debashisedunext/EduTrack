import * as React from 'react'
import { ApiError } from '@/api/http'
import { deleteAttachment, listAttachments } from '@/api/generated/attachments/attachments'
import type { Attachment } from '@/api/generated/model'
import type { AttachmentItem, AttachmentItemStatus } from '@/components/ui/attachment-picker'
import { isPreviewableImage } from '@/components/ui/attachments'
import { uploadTicketAttachment } from './uploadTicketAttachment'

/**
 * Upload lifecycle for every C-023 surface.
 *
 * The picker in `components/ui/` is presentational and knows nothing about the
 * API. This is the half that does — and it exists once because the alternative
 * is five screens each inventing their own upload state machine, which is the
 * same argument C-066 made for building one editor.
 *
 * ## Two modes, one hook
 *
 * The surfaces split cleanly in two, and the split is not cosmetic:
 *
 * **Immediate** (`ticketId` given) — ticket detail and quick update. The ticket
 * exists, so a file goes up the moment it is chosen and the server ID is
 * available immediately for `QuickUpdateRequest.attachmentIds`.
 *
 * **Deferred** (`ticketId` is `null`) — the create form. There is no ticket to
 * attach to yet, and **`TicketCreateRequest` has no `attachmentIds` field**, so
 * there is nothing to send even if the files were somewhere. Files stage
 * locally and `flush(newTicketId)` uploads them once the 201 names the ticket.
 * A failure there leaves a created ticket with missing files rather than a lost
 * ticket, which is why `flush` reports per-file outcomes instead of throwing.
 *
 * ## Why it takes `existing` rather than fetching
 *
 * Both live surfaces already load their attachments — detail through the single
 * aggregated `GET /tickets/{id}/full` (C-019 fetches once on purpose; a second
 * request for the same rows would be exactly the waterfall it avoided), quick
 * update through whatever its caller holds. Passing them in keeps this hook from
 * adding a fetch to a screen that already made one, and lets each surface own
 * its own cache invalidation through `onUploaded`.
 */

export interface UseTicketAttachmentsOptions {
  /** `null` puts the hook in deferred mode — see `flush`. */
  ticketId: string | null
  /** Attachments the surface already loaded. Merged ahead of in-flight ones. */
  existing?: readonly Attachment[]
  /**
   * Called after an upload or delete settles, so the surface can invalidate the
   * query it already owns. Called a second time ~2s later while the scan is
   * still `PENDING`, which is what turns the row from "Scanning" to "Ready"
   * without this hook opening a polling loop of its own.
   */
  onUploaded?: () => void
  /** §4B.4's per-attachment flag. Comments default internal; so does this. */
  isClientVisible?: boolean
}

/** A file this hook is still responsible for — staged, in flight, or failed. */
interface LocalEntry {
  id: string
  file: File
  status: Extract<AttachmentItemStatus, 'uploading' | 'failed'> | 'staged'
  error?: string
  objectUrl?: string
}

function scanStatusToItemStatus(scanStatus: Attachment['scanStatus']): AttachmentItemStatus {
  if (scanStatus === 'CLEAN') return 'ready'
  if (scanStatus === 'INFECTED') return 'failed'
  return 'scanning'
}

function serverToItem(attachment: Attachment): AttachmentItem {
  return {
    id: String(attachment.id),
    name: attachment.fileName ?? 'Untitled',
    sizeBytes: attachment.sizeBytes ?? 0,
    contentType: attachment.contentType,
    status: scanStatusToItemStatus(attachment.scanStatus),
    error: attachment.scanStatus === 'INFECTED' ? 'Failed the virus scan' : undefined,
    thumbnailUrl: attachment.thumbnailUrl,
    // C-026. Both are null until the scan passes — the server decides that, not
    // this mapper — and `thumbnailUrl` is additionally null whenever no
    // reduction was worth storing, which is why the gallery falls back to this
    // one rather than treating its absence as "not an image".
    downloadUrl: attachment.downloadUrl,
  }
}

/**
 * `createObjectURL` guarded because jsdom does not implement it.
 *
 * A staged file has no server thumbnail — there is no server row — so the create
 * form's preview can only come from the local blob. Losing it in tests is fine;
 * throwing in tests is not.
 */
function previewUrl(file: File): string | undefined {
  if (!isPreviewableImage(file.type)) return undefined
  try {
    return URL.createObjectURL(file)
  } catch {
    return undefined
  }
}

function revoke(url: string | undefined): void {
  if (!url) return
  try {
    URL.revokeObjectURL(url)
  } catch {
    /* jsdom, or already revoked */
  }
}

export interface UseTicketAttachmentsResult {
  /** Server rows plus anything still local, ready for `<AttachmentPicker items>`. */
  items: AttachmentItem[]
  /**
   * C-028 · removed files the ticket still records — §4B.4's "file removed by X
   * on date".
   *
   * Kept apart from `items` rather than given a fifth `AttachmentItemStatus`,
   * which was the first shape tried. `AttachmentItem` is `components/ui/`, which
   * all four streams consume, and widening its status union would make every
   * exhaustive `switch` on it in three other streams' code a change they did not
   * ask for. A tombstone is also not a *file* in any sense the picker cares
   * about — it cannot be previewed, downloaded, removed or counted against
   * §4B.4's caps — so putting it in the same array would mean teaching every
   * consumer to filter it out again.
   *
   * The server sends only the removals worth showing; a file its uploader took
   * back within fifteen minutes never arrives here at all.
   */
  tombstones: Attachment[]
  /**
   * C-028 · why the last removal was refused, or null.
   *
   * Carries the server's `detail` verbatim rather than a message composed here.
   * §4B.4's rule is enforced server-side and the refusal depends on the row, the
   * caller and the clock — restating it in the client would be a second copy of
   * a rule that has already gone out of step once in this feature.
   */
  removeError: string | null
  /**
   * Uploaded server IDs, for `QuickUpdateRequest.attachmentIds`,
   * `CommentWriteRequest.attachmentIds` and `HandoffRequest.attachmentIds`.
   */
  attachmentIds: number[]
  add: (files: File[]) => void
  remove: (id: string) => void
  /** Deferred mode only: upload everything staged against a now-existing ticket. */
  flush: (ticketId: string) => Promise<FlushResult>
  /** Drops staged files and their object URLs — Save & Create Another needs this. */
  reset: () => void
  isUploading: boolean
  /** Staged but not yet uploaded. Zero in immediate mode. */
  pendingCount: number
}

export interface FlushResult {
  uploaded: number
  /** File names that did not make it, in the order they were attempted. */
  failed: string[]
}

export function useTicketAttachments({
  ticketId,
  existing,
  onUploaded,
  isClientVisible = false,
}: UseTicketAttachmentsOptions): UseTicketAttachmentsResult {
  const [local, setLocal] = React.useState<LocalEntry[]>([])
  const [deleted, setDeleted] = React.useState<ReadonlySet<string>>(() => new Set())
  /**
   * Rows this hook uploaded, kept even after the local entry is dropped.
   *
   * Quick update passes no `existing` — it holds a `Ticket`, and attachments
   * only ride on the aggregated `/full` payload — so without this a successful
   * upload would disappear from the picker the instant it succeeded, which reads
   * exactly like the upload failed. Merged with `existing` and deduplicated by
   * ID, so the detail page does not show each file twice once its refetch lands.
   */
  const [uploaded, setUploaded] = React.useState<Attachment[]>([])
  /** C-028 · why the last remove did not take. Null once another one is tried. */
  const [removeError, setRemoveError] = React.useState<string | null>(null)

  const localRef = React.useRef(local)
  localRef.current = local

  // Kept in a ref so `add` and `flush` do not need them in their dependency
  // arrays — a new `onUploaded` identity on every parent render would otherwise
  // rebuild `add`, and `AttachmentPicker` would see a new `onAdd` every render.
  const optionsRef = React.useRef({ onUploaded, isClientVisible })
  optionsRef.current = { onUploaded, isClientVisible }

  const nextId = React.useRef(0)

  // Revoke every object URL on unmount. Without this a create form that stages
  // six screenshots and navigates away leaks all six blobs for the tab's life.
  React.useEffect(() => {
    return () => {
      for (const entry of localRef.current) revoke(entry.objectUrl)
    }
  }, [])

  /**
   * Re-read this ticket's attachments and fold the fresh rows over our own.
   *
   * Only reached when something is still `PENDING`. Quick update has no query to
   * invalidate — it holds a `Ticket`, and attachments ride on the aggregated
   * `/full` payload the detail page fetches — so `onUploaded` alone would leave
   * its rows saying "Scanning" forever.
   */
  const refreshUploaded = React.useCallback(async (target: string) => {
    try {
      const list = await listAttachments(target)
      const byId = new Map((list.data ?? []).map((a) => [String(a.id), a]))
      setUploaded((prev) => prev.map((a) => byId.get(String(a.id)) ?? a))
    } catch {
      // Leave the row as it stands. A failed refresh is not a failed upload, and
      // saying so would be worse than a stale "Scanning".
    }
  }, [])

  /** Fire `onUploaded`, then once more if the scan was still pending. */
  const notify = React.useCallback(
    (target: string, stillScanning: boolean) => {
      optionsRef.current.onUploaded?.()
      if (!stillScanning) return
      // Not a polling loop — one follow-up. If the scan outlasts it the row stays
      // "Scanning", which is honest; §4B.4 has no push channel for scan
      // completion and inventing one here would be Stream D's WebSocket work.
      setTimeout(() => {
        optionsRef.current.onUploaded?.()
        void refreshUploaded(target)
      }, 2000)
    },
    [refreshUploaded],
  )

  const uploadOne = React.useCallback(
    async (target: string, entry: LocalEntry): Promise<boolean> => {
      setLocal((prev) => prev.map((e) => (e.id === entry.id ? { ...e, status: 'uploading', error: undefined } : e)))
      try {
        const response = await uploadTicketAttachment(target, {
          file: entry.file,
          isClientVisible: optionsRef.current.isClientVisible,
        })
        // The server row now owns this file; drop the local copy so the list
        // does not show it twice once `onUploaded` refreshes the surface.
        if (response.data) setUploaded((prev) => [...prev, response.data])
        setLocal((prev) => {
          const match = prev.find((e) => e.id === entry.id)
          revoke(match?.objectUrl)
          return prev.filter((e) => e.id !== entry.id)
        })
        notify(target, response.data?.scanStatus === 'PENDING')
        return true
      } catch (error) {
        setLocal((prev) =>
          prev.map((e) => (e.id === entry.id ? { ...e, status: 'failed', error: uploadErrorMessage(error) } : e)),
        )
        return false
      }
    },
    [notify],
  )

  const add = React.useCallback(
    (files: File[]) => {
      const entries: LocalEntry[] = files.map((file) => ({
        id: `local-${nextId.current++}`,
        file,
        status: ticketId ? 'uploading' : 'staged',
        objectUrl: previewUrl(file),
      }))
      setLocal((prev) => [...prev, ...entries])
      if (!ticketId) return
      // Sequential, not `Promise.all`. Twenty parallel multipart requests is a
      // burst the per-ticket size check on the server sees out of order, and the
      // 50 MB cap can only be enforced correctly against a settled total.
      void (async () => {
        for (const entry of entries) await uploadOne(ticketId, entry)
      })()
    },
    [ticketId, uploadOne],
  )

  const remove = React.useCallback(
    (id: string) => {
      const entry = localRef.current.find((e) => e.id === id)
      if (entry) {
        revoke(entry.objectUrl)
        setLocal((prev) => prev.filter((e) => e.id !== id))
        return
      }
      // A server row. Hide it optimistically — the surface's own refetch is what
      // makes it permanent, and `deleted` keeps it from flashing back in between.
      setDeleted((prev) => new Set(prev).add(id))
      setRemoveError(null)
      void deleteAttachment(ticketId ?? '', Number(id))
        .then(() => optionsRef.current.onUploaded?.())
        .catch((error: unknown) => {
          // C-028 made this branch reachable for a reason a user can act on.
          // Before it, a delete only failed on a network fault and silently
          // restoring the row was the whole of the right behaviour. Now §4B.4's
          // rule can refuse it — a Developer clicking × on a colleague's file
          // gets a 403 — and a row that reappears with no explanation reads as a
          // broken button, which is the one outcome worth avoiding: the user
          // will click it again.
          setDeleted((prev) => {
            const next = new Set(prev)
            next.delete(id)
            return next
          })
          setRemoveError(removeErrorMessage(error))
        })
    },
    [ticketId],
  )

  const flush = React.useCallback(
    async (target: string): Promise<FlushResult> => {
      const staged = localRef.current.filter((e) => e.status === 'staged' || e.status === 'failed')
      let uploaded = 0
      const failed: string[] = []
      for (const entry of staged) {
        if (await uploadOne(target, entry)) uploaded += 1
        else failed.push(entry.file.name)
      }
      return { uploaded, failed }
    },
    [uploadOne],
  )

  const reset = React.useCallback(() => {
    for (const entry of localRef.current) revoke(entry.objectUrl)
    setLocal([])
    setUploaded([])
    setDeleted(new Set())
  }, [])

  /**
   * `existing` folded over our own uploads, keyed by ID.
   *
   * The surface's copy wins on collision: once its query refetches, that row is
   * newer than the 201 we cached — it carries the settled scan status and the
   * signed URLs. Insertion order keeps our uploads last, which is where the user
   * just put them.
   */
  const all = React.useMemo<Attachment[]>(() => {
    const byId = new Map<string, Attachment>()
    for (const a of uploaded) byId.set(String(a.id), a)
    for (const a of existing ?? []) byId.set(String(a.id), a)
    return [...byId.values()]
  }, [existing, uploaded])

  const merged = React.useMemo<Attachment[]>(
    () => all.filter((a) => !a.isDeleted && !deleted.has(String(a.id))),
    [all, deleted],
  )

  /**
   * C-028 · the removals the server chose to keep visible.
   *
   * `deleted` is not consulted, and that is deliberate. It holds ids this hook
   * has optimistically hidden, and a removal that turns out to warrant a
   * tombstone should appear as one the moment the refetch lands rather than stay
   * hidden because we hid it a second earlier — the optimistic set is about not
   * flashing a row back in, not about what the ticket records.
   */
  const tombstones = React.useMemo<Attachment[]>(() => all.filter((a) => a.isDeleted), [all])

  const items = React.useMemo<AttachmentItem[]>(() => {
    const server = merged.map(serverToItem)
    const pending = local.map<AttachmentItem>((entry) => ({
      id: entry.id,
      name: entry.file.name,
      sizeBytes: entry.file.size,
      contentType: entry.file.type,
      // A staged file is shown as ready: nothing is happening to it, and a
      // permanent spinner on a create form reads as a stuck upload.
      status: entry.status === 'staged' ? 'ready' : entry.status,
      error: entry.error,
      thumbnailUrl: entry.objectUrl,
      // The same blob URL serves as the full-size source: there is no server row
      // and therefore no reduction, and the local file is the only copy that
      // exists. It is what makes a staged screenshot openable in the lightbox on
      // the create form, before the ticket it belongs to has been created.
      downloadUrl: entry.objectUrl,
    }))
    return [...server, ...pending]
  }, [merged, local])

  const attachmentIds = React.useMemo(
    () => merged.filter((a) => typeof a.id === 'number').map((a) => a.id as number),
    [merged],
  )

  return {
    items,
    tombstones,
    removeError,
    add,
    attachmentIds,
    remove,
    flush,
    reset,
    isUploading: local.some((e) => e.status === 'uploading'),
    pendingCount: local.filter((e) => e.status === 'staged' || e.status === 'failed').length,
  }
}

/**
 * §4B.4's two server-side rejections have specific meanings and both are worth
 * saying plainly — a 413 means "make room", a 415 means "this will never work".
 * Anything else falls back to the problem title, never to `error.message`.
 */
/**
 * C-028 · why a removal was refused.
 *
 * The 403's `detail` is the whole message and is written for the person who
 * clicked — it names the window and who can act instead. Anything else falls
 * back to a plain sentence rather than to `error.message`, which on a network
 * fault is a stack-shaped string.
 */
function removeErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return error.problem.detail ?? error.problem.title ?? 'That file could not be removed'
  }
  return 'That file could not be removed'
}

function uploadErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 413) return error.problem.detail ?? 'Too large for this ticket'
    if (error.status === 415) return error.problem.detail ?? 'That file type is not allowed'
    return error.problem.detail ?? error.problem.title ?? 'Upload failed'
  }
  return 'Upload failed'
}
