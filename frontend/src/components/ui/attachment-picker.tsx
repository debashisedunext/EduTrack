import * as React from 'react'
import { AlertTriangle, Check, FileText, Image as ImageIcon, Loader2, Paperclip, ShieldAlert, Upload, X } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Button } from './button'
import {
  ATTACHMENT_ACCEPT,
  ATTACHMENT_DEFAULT_LIMITS,
  type AttachmentLimits,
  type AttachmentRejection,
  attachmentPreviewSource,
  filesFromDataTransfer,
  formatFileSize,
  isPreviewableImage,
  renameClipboardFiles,
  selectAttachments,
} from './attachments'
import { ImageLightbox, type LightboxImage } from './image-lightbox'
import { useAttachmentPaste } from './use-attachment-paste'

/**
 * The shared attachment picker — C-023, blueprint §4B.4.
 *
 * §4B.4 lists six places a file can be attached: the create form, ticket
 * detail, the comment box, the handoff dialog, quick update and inbound email.
 * Five of those are screens in this codebase and two of them (C-029's comment
 * box, C-052's handoff) do not exist yet — so this is built once, here, the same
 * way C-066 built the rich-text editor before its second consumer existed.
 *
 * ## Presentational on purpose
 *
 * This component does **no fetching**. It holds no TanStack Query hook, imports
 * nothing from `@/api`, and does not know what a ticket ID is. Every other
 * component in `components/ui/` obeys that rule and three other streams consume
 * this directory; a picker that knew how to upload would drag the generated
 * client into Stream A's and Stream B's bundles the moment either used it.
 *
 * Upload lifecycle lives in `features/tickets/attachments/useTicketAttachments`,
 * which owns the two genuinely different cases: detail and quick update have a
 * ticket ID and upload immediately, while the create form has no ticket yet and
 * must stage files until the 201 comes back.
 *
 * ## What it *does* own
 *
 * Validation, because it is the only party that knows what is already in the
 * list. Running totals are checked across a whole drop (`selectAttachments`), so
 * twelve individually-legal 5 MB files cannot walk past a 50 MB ticket cap
 * together. Rejections render in a live region rather than being thrown, since a
 * user who drags a folder expects the six valid files to land and to be told
 * about the seventh.
 *
 * ## Clipboard paste — C-024
 *
 * On by default, because §4B.4 is right that paste is what decides whether
 * support agents attach anything at all: a Snipping Tool capture exists only on
 * the clipboard, with no file to drag and nothing to browse to.
 *
 * The listener is on `document` and only one picker on the page claims it —
 * `useAttachmentPaste` carries that argument, including why an ordinary text
 * paste and a rich-text editor are both left alone. A paste runs through exactly
 * the same `accept` as a drop, so no route reaches `onAdd` having skipped the
 * caps, and it is **announced**, because unlike a drop or a browse a paste has
 * no visible action to have produced it.
 *
 * A surface that owns a rich-text editor wires `RichTextEditor.onPasteFiles` to
 * this component's `addFiles` handle: the editor swallows image pastes itself,
 * and the handle is how they reach the same validation.
 */

export type AttachmentItemStatus =
  /** In flight. */
  | 'uploading'
  /** Uploaded; the AV scan has not returned. Not downloadable yet — §4B.4. */
  | 'scanning'
  /** Scan passed. */
  | 'ready'
  /** Upload or scan failed; `error` says which. */
  | 'failed'

export interface AttachmentItem {
  /** Stable across the item's life. The server ID once there is one, a local key before. */
  id: string
  name: string
  sizeBytes: number
  contentType?: string | null
  status: AttachmentItemStatus
  /** Shown under the row when `status` is `failed`. */
  error?: string
  /**
   * Signed URL for the small reduction, or an object URL for a file not yet
   * uploaded.
   *
   * Never rendered while `status` is `scanning` — §4B.4 is explicit that a file
   * is not visible until the scan passes, and a thumbnail is visibility.
   *
   * **Null does not mean "not an image."** The server stores a reduction only
   * where one is worth storing — see `attachmentPreviewSource`, which is what a
   * caller should use rather than reading this field directly.
   */
  thumbnailUrl?: string | null
  /**
   * Signed URL for the file itself — C-026.
   *
   * Two jobs: it is what the lightbox opens, and it is the preview fallback for
   * an image the server chose not to reduce. Null until the scan passes, on the
   * same terms as `thumbnailUrl`, because it is the same rule.
   */
  downloadUrl?: string | null
}

export interface AttachmentPickerProps {
  items: readonly AttachmentItem[]
  /** Receives only what passed validation. Rejections are rendered here. */
  onAdd: (files: File[]) => void
  /** Omit to render no remove control at all — C-028 owns the 15-minute rule. */
  onRemove?: (id: string) => void
  /** Also fires for files this component rejected, for a caller that wants to toast. */
  onReject?: (rejections: { file: File; rejection: AttachmentRejection }[]) => void
  limits?: AttachmentLimits
  /**
   * Drops the drop zone for a single inline button.
   *
   * For surfaces with no room for a 96px target — §4B.5's comment box renders an
   * inline `[📎]`, and the quick update slide-over is 420px wide. Drag-and-drop
   * still works: the whole control stays a drop target, it just stops
   * advertising itself as one.
   */
  compact?: boolean
  /**
   * Render the attached-files list, or leave it to the caller — C-026.
   *
   * `items` must still be passed either way, and that is the point of the prop
   * existing at all: validation depends on it. The running totals that stop
   * twelve individually-legal 5 MB files from walking past a 50 MB ticket cap are
   * computed from `items`, so a caller who wanted its own presentation and
   * passed `items={[]}` would silently disable every per-ticket limit. This says
   * "you draw them" rather than "there are none".
   *
   * S-20's detail page is the reason: §4B.4 wants one strip of thumbnails there,
   * not a strip and a list of the same files underneath it. `AttachmentGallery`
   * draws that strip from the identical `AttachmentItem[]`.
   */
  showItems?: boolean
  /**
   * Clicking an image opens it full-screen — C-026.
   *
   * On by default, because the surface that needs it most is the one with no
   * other way to check: **the create form**. A file staged there has not been
   * uploaded — `TicketCreateRequest` carries no `attachmentIds`, so nothing
   * leaves the browser until the 201 — and a 28px preview is not enough to tell
   * two screenshots of the same screen apart. Pasting the wrong capture and
   * finding out after the ticket exists is the failure this closes.
   *
   * It works for staged files precisely because `useTicketAttachments` gives a
   * local blob its object URL as both `thumbnailUrl` and `downloadUrl`: the
   * viewer opens the local copy, which is the only copy there is.
   *
   * Ignored when `showItems` is false — there are no rows to click.
   */
  enablePreview?: boolean
  disabled?: boolean
  /**
   * Clipboard paste — C-024. On unless a surface has a reason to opt out.
   *
   * Only one picker on the page claims a paste at a time, so a second one
   * mounting over the first (quick update over ticket detail) does not need this
   * turned off. Pass `false` where a paste would be genuinely ambiguous.
   */
  enablePaste?: boolean
  className?: string
  id?: string
  'aria-labelledby'?: string
  'aria-describedby'?: string
  'aria-label'?: string
}

export interface AttachmentPickerHandle {
  /**
   * Push files in from somewhere else on the screen and validate them here.
   *
   * The seam for `RichTextEditor.onPasteFiles`: the editor intercepts image
   * pastes itself (C-066) and its files must land in the same running totals as
   * every other route, not in `onAdd` unchecked. C-029's comment box takes the
   * identical pair.
   */
  addFiles: (files: File[]) => void
}

function statusIcon(item: AttachmentItem) {
  switch (item.status) {
    case 'uploading':
      return <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin text-content-muted" />
    case 'scanning':
      return <ShieldAlert className="h-3.5 w-3.5 shrink-0 text-warning-text" />
    case 'failed':
      return <AlertTriangle className="h-3.5 w-3.5 shrink-0 text-danger-text" />
    default:
      return <Check className="h-3.5 w-3.5 shrink-0 text-success-text" />
  }
}

/**
 * What to draw in the row's 28px square, or nothing — C-026.
 *
 * `ready` is checked here rather than at each call site, so the scan rule is
 * asked once: §4B.4's "the file becomes visible only after the scan passes"
 * covers a 28px preview exactly as it covers a full-size one.
 */
function previewOf(item: AttachmentItem): string | undefined {
  return item.status === 'ready' ? attachmentPreviewSource(item) : undefined
}

/**
 * Whether this row opens in the viewer — C-026.
 *
 * An image, scanned, with a full-size source to open. A document is not
 * openable *here* and is handed to the browser as a link instead; the viewer is
 * for images, and putting a PDF in it would render a broken `<img>`.
 */
function openable(item: AttachmentItem, enablePreview: boolean): boolean {
  return enablePreview && previewOf(item) !== undefined && item.downloadUrl != null
}

/** Screen-reader text for the status icon, which is otherwise a decorative glyph. */
function statusLabel(item: AttachmentItem): string {
  switch (item.status) {
    case 'uploading':
      return 'Uploading'
    case 'scanning':
      return 'Scanning for viruses'
    case 'failed':
      return item.error ?? 'Failed'
    default:
      return 'Ready'
  }
}

export const AttachmentPicker = React.forwardRef<AttachmentPickerHandle, AttachmentPickerProps>(function AttachmentPicker(
  {
    items,
    onAdd,
    onRemove,
    onReject,
    limits = ATTACHMENT_DEFAULT_LIMITS,
    compact = false,
    showItems = true,
    enablePreview = true,
    disabled = false,
    enablePaste = true,
    className,
    id,
    'aria-labelledby': ariaLabelledBy,
    'aria-describedby': ariaDescribedBy,
    'aria-label': ariaLabel,
  },
  ref,
) {
  const inputRef = React.useRef<HTMLInputElement>(null)
  const [dragging, setDragging] = React.useState(false)
  const [rejections, setRejections] = React.useState<{ file: File; rejection: AttachmentRejection }[]>([])
  /**
   * What a paste just did, for the live region.
   *
   * Drop and browse both have a visible action behind them — the user let go of
   * a file, or came back from a dialog. A paste has nothing: the screen simply
   * has to say that something happened, or `Ctrl+V` reads as ignored.
   */
  const [pasteNotice, setPasteNotice] = React.useState<string | null>(null)

  /** C-026 · which image the viewer is on, or `null` for closed. */
  const [lightboxIndex, setLightboxIndex] = React.useState<number | null>(null)

  /**
   * The images the viewer can page through.
   *
   * `ready` only — a file still uploading or still being scanned has nothing to
   * show, and §4B.4 is explicit that it must not. Built over `items` rather than
   * over what is rendered, but the picker renders all of them, so the two agree.
   */
  const lightboxImages = React.useMemo<LightboxImage[]>(
    () =>
      items
        .filter((item) => item.status === 'ready' && item.downloadUrl && isPreviewableImage(item.contentType))
        .map((item) => ({ name: item.name, src: item.downloadUrl as string, downloadUrl: item.downloadUrl })),
    [items],
  )

  const openPreview = React.useCallback(
    (item: AttachmentItem) => {
      const index = lightboxImages.findIndex((image) => image.src === item.downloadUrl)
      if (index >= 0) setLightboxIndex(index)
    },
    [lightboxImages],
  )

  // A drag over a child element fires `dragleave` on the parent. Counting
  // enter/leave pairs is the only way to keep the highlight from flickering off
  // the moment the pointer crosses a file row inside the zone.
  const dragDepth = React.useRef(0)

  const generatedId = React.useId()
  const controlId = id ?? `${generatedId}-attachments`
  const statusId = `${controlId}-status`

  // A failed row counts against neither total — it is not on the ticket, and
  // counting it would refuse a retry of the very file that failed.
  const usedBytes = items.reduce((sum, item) => (item.status === 'failed' ? sum : sum + item.sizeBytes), 0)
  const usedCount = items.filter((item) => item.status !== 'failed').length
  const full = usedCount >= limits.maxFiles
  const showCapacity = items.length > 0 && !disabled

  const accept = React.useCallback(
    (files: readonly File[], source: 'picker' | 'paste' = 'picker') => {
      if (disabled || files.length === 0) return

      const attached = items.filter((i) => i.status !== 'failed').map((i) => i.name)
      // Naming happens here and not in the paste listener because it needs
      // `attached` — the clipboard's own `image.png` has to be disambiguated
      // against what is already on the ticket, or the second screenshot a user
      // pastes is refused as a duplicate of the first. Only a clipboard route
      // is renamed; a drop or a browse keeps its names, where a duplicate is
      // real feedback rather than an artefact of the clock.
      const incoming = source === 'paste' ? renameClipboardFiles(files, { taken: attached }) : files

      const result = selectAttachments(incoming, {
        existingBytes: usedBytes,
        existingCount: usedCount,
        existingNames: attached.map((name) => name.toLowerCase()),
        limits,
      })
      setRejections(result.rejected)
      // Only a paste announces what it took. A rejection speaks for itself on
      // every route, and it is the one thing a paste must not drown out — so the
      // notice is dropped the moment there is something to refuse.
      setPasteNotice(
        source === 'paste' && result.accepted.length > 0 && result.rejected.length === 0
          ? result.accepted.length === 1
            ? `Pasted ${result.accepted[0].name}`
            : `Pasted ${result.accepted.length} files`
          : null,
      )
      if (result.rejected.length > 0) onReject?.(result.rejected)
      if (result.accepted.length > 0) onAdd(result.accepted)
    },
    [disabled, items, limits, onAdd, onReject, usedBytes, usedCount],
  )

  // The seam for a rich-text editor's own paste handler — see
  // `AttachmentPickerHandle`. It goes in as a paste, which is what it is: the
  // editor never produces a `DataTransfer`, it hands out plain `File[]` still
  // carrying the `image.png` every capture has, and those need the same naming
  // as any other clipboard route.
  React.useImperativeHandle(ref, () => ({ addFiles: (files: File[]) => accept(files, 'paste') }), [accept])

  useAttachmentPaste({
    enabled: enablePaste && !disabled,
    onFiles: React.useCallback((files: File[]) => accept(files, 'paste'), [accept]),
  })

  const onDrop = (event: React.DragEvent) => {
    event.preventDefault()
    dragDepth.current = 0
    setDragging(false)
    if (disabled) return
    accept(filesFromDataTransfer(event.dataTransfer))
  }

  const onDragEnter = (event: React.DragEvent) => {
    // Only react to an actual file drag — dragging selected text across the page
    // should not light the zone up as if it would accept it.
    if (!Array.from(event.dataTransfer?.types ?? []).includes('Files')) return
    dragDepth.current += 1
    setDragging(true)
  }

  const onDragLeave = () => {
    dragDepth.current = Math.max(0, dragDepth.current - 1)
    if (dragDepth.current === 0) setDragging(false)
  }

  const browse = () => inputRef.current?.click()

  return (
    <div
      className={cn('flex flex-col gap-2', className)}
      onDrop={onDrop}
      onDragOver={(event) => event.preventDefault()}
      onDragEnter={onDragEnter}
      onDragLeave={onDragLeave}
    >
      {/*
        The native input is the accessible control and is never hidden with
        `display:none`, which removes it from the tab order and from some
        screen readers' forms mode. It is visually hidden and driven by the
        button, which is what carries the label.
      */}
      <input
        ref={inputRef}
        id={controlId}
        type="file"
        multiple
        accept={ATTACHMENT_ACCEPT}
        disabled={disabled || full}
        className="sr-only"
        aria-labelledby={ariaLabelledBy}
        aria-describedby={[ariaDescribedBy, statusId].filter(Boolean).join(' ') || undefined}
        onChange={(event) => {
          accept(Array.from(event.target.files ?? []))
          // Reset, or re-picking the same file after removing it fires no
          // `change` event at all and the picker looks broken.
          event.target.value = ''
        }}
      />

      {compact ? (
        <div className="flex items-center gap-2">
          <Button
            type="button"
            variant="secondary"
            size="sm"
            disabled={disabled || full}
            onClick={browse}
            aria-label={ariaLabel ?? 'Attach files'}
            className={cn(dragging && 'border-primary bg-primary/5')}
          >
            <Paperclip className="h-4 w-4" aria-hidden />
            Attach
          </Button>
          <span className="text-caption text-content-muted">
            {full ? `${limits.maxFiles} file limit reached` : `Up to ${formatFileSize(limits.maxFileBytes)} each`}
          </span>
        </div>
      ) : (
        <div
          className={cn(
            'flex flex-col items-center justify-center gap-1 rounded-control border border-dashed px-4 py-6 text-center transition-colors',
            dragging ? 'border-primary bg-primary/5' : 'border-border bg-subtle',
            (disabled || full) && 'opacity-50',
          )}
        >
          <Upload className={cn('h-5 w-5', dragging ? 'text-primary' : 'text-content-muted')} aria-hidden />
          <p className="text-sm text-content">
            Drag files here, paste a screenshot, or{' '}
            <Button
              type="button"
              variant="ghost"
              size="sm"
              disabled={disabled || full}
              onClick={browse}
              aria-label={ariaLabel ?? 'Choose files to attach'}
              className="h-auto p-0 align-baseline text-sm font-medium text-primary underline underline-offset-2 hover:bg-transparent"
            >
              browse
            </Button>
          </p>
          <p className="text-caption text-content-muted">
            {formatFileSize(limits.maxFileBytes)} per file · {limits.maxFiles} per ticket · images, documents, zip, mp4
          </p>
        </div>
      )}

      {showItems && items.length > 0 && (
        <ul className="flex flex-col gap-1" aria-label="Attached files">
          {items.map((item) => (
            <li
              key={item.id}
              className={cn(
                'flex items-center gap-2 rounded-control border border-border bg-surface px-2.5 py-1.5',
                item.status === 'failed' && 'border-danger/40',
              )}
            >
              {/*
                A thumbnail is only rendered once the scan has passed. §4B.4:
                the file "becomes visible only after the scan passes", and
                showing the image is precisely making it visible.

                `attachmentPreviewSource` rather than `thumbnailUrl` directly —
                a null reduction does not mean "not an image", and on the create
                form there is never a reduction at all, because the file has not
                been uploaded yet and the object URL is the only copy.
              */}
              {previewOf(item) ? (
                /*
                  C-026 · a redundant click target, not the accessible one.

                  28px is a fine thing to click with a mouse and a poor thing to
                  find with a keyboard, so the name below carries the real
                  control and this is `tabIndex={-1}` + `aria-hidden`. Two
                  focusable controls per row would double the tab stops of a
                  twenty-file list to say the same thing twice.
                */
                <span
                  role={openable(item, enablePreview) ? 'presentation' : undefined}
                  onClick={openable(item, enablePreview) ? () => openPreview(item) : undefined}
                  aria-hidden={openable(item, enablePreview) || undefined}
                  className={cn('shrink-0', openable(item, enablePreview) && 'cursor-pointer')}
                >
                  <img src={previewOf(item)} alt="" className="h-7 w-7 rounded object-cover" />
                </span>
              ) : (
                <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded bg-subtle">
                  {isPreviewableImage(item.contentType) ? (
                    <ImageIcon className="h-3.5 w-3.5 text-content-muted" aria-hidden />
                  ) : (
                    <FileText className="h-3.5 w-3.5 text-content-muted" aria-hidden />
                  )}
                </span>
              )}

              <span className="min-w-0 flex-1">
                {/*
                  C-026 · the name is the control, because it is the thing a user
                  reads and therefore the thing they click. It is underlined on
                  hover and focusable, which the bare 28px thumbnail was not —
                  that thumbnail had no pointer cursor and no hover state, so
                  nothing on the row said it could be opened at all.

                  Two shapes, because the two actions genuinely differ: an image
                  opens in the viewer, and anything else is handed to the browser.
                  A document is an <a>, so middle-click and "open in new tab"
                  behave the way a link is expected to.
                */}
                {openable(item, enablePreview) ? (
                  <button
                    type="button"
                    onClick={() => openPreview(item)}
                    className="block max-w-full truncate rounded text-left text-sm text-content underline-offset-2 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                    title={`Open ${item.name}`}
                  >
                    {item.name}
                  </button>
                ) : item.status === 'ready' && item.downloadUrl ? (
                  <a
                    href={item.downloadUrl}
                    // A new tab, not a navigation: the create form holds unsaved
                    // work and a PDF opening in place would discard the ticket
                    // the user is halfway through writing.
                    target="_blank"
                    rel="noreferrer"
                    className="block max-w-full truncate rounded text-left text-sm text-content underline-offset-2 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                    title={`Open ${item.name}`}
                  >
                    {item.name}
                  </a>
                ) : (
                  <span className="block truncate text-sm text-content" title={item.name}>
                    {item.name}
                  </span>
                )}
                <span className="block text-caption text-content-muted">
                  {formatFileSize(item.sizeBytes)}
                  {item.status === 'failed' && item.error ? ` · ${item.error}` : ''}
                </span>
              </span>

              {statusIcon(item)}
              <span className="sr-only">{statusLabel(item)}</span>

              {onRemove && (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  disabled={disabled}
                  onClick={() => onRemove(item.id)}
                  aria-label={`Remove ${item.name}`}
                  className="h-7 w-7 shrink-0 p-0 text-content-muted hover:text-danger-text"
                >
                  <X className="h-4 w-4" aria-hidden />
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}

      {/*
        C-026 · mounted only where a row could have opened it. On the detail page
        the picker runs with `showItems={false}` and `AttachmentGallery` owns
        both the tiles and the viewer — two lightboxes on one screen would fight
        over focus the moment either opened.
      */}
      {showItems && enablePreview && (
        <ImageLightbox images={lightboxImages} index={lightboxIndex} onIndexChange={setLightboxIndex} />
      )}

      {/*
        One live region for both the running total and any rejection. Two would
        race each other on a mixed drop and a screen reader would read whichever
        landed last, which is usually the total — the least useful of the two.

        The capacity line is about what can still be added, so it is suppressed
        when nothing can be: on a sealed cycle the detail page renders this
        disabled, and "1 of 20 files · 50 MB" there describes a budget the reader
        cannot spend. Rejections still speak, since a rejection is always news.

        The region itself is rendered whenever the control is live, even with
        nothing in it. A live region that *appears* carrying its first message is
        unreliably announced — several screen readers only watch regions that
        were already in the tree — and the first message here is usually a paste,
        which has no other evidence that it worked. It stays absent while
        disabled, so a sealed cycle does not land a second `role="status"` beside
        the banner already telling the reader the cycle is read-only.
      */}
      {!disabled && (
        <div id={statusId} role="status" aria-live="polite" className="flex flex-col gap-0.5">
          {rejections.length > 0 && (
            <ul className="flex flex-col gap-0.5">
              {rejections.map(({ file, rejection }, index) => (
                <li key={`${file.name}-${index}`} className="text-caption text-danger-text">
                  {rejection.code === 'file-too-large' || rejection.code === 'ticket-size-exceeded'
                    ? `${file.name} — ${rejection.message}`
                    : rejection.message}
                </li>
              ))}
            </ul>
          )}
          {pasteNotice && <p className="text-caption text-content-muted">{pasteNotice}</p>}
          {showCapacity && (
            <p className="text-caption text-content-muted">
              {usedCount} of {limits.maxFiles} files · {formatFileSize(usedBytes)} of{' '}
              {formatFileSize(limits.maxTotalBytes)}
            </p>
          )}
        </div>
      )}
    </div>
  )
})
