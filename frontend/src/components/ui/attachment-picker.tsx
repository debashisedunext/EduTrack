import * as React from 'react'
import { AlertTriangle, Check, FileText, Image as ImageIcon, Loader2, Paperclip, ShieldAlert, Upload, X } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Button } from './button'
import {
  ATTACHMENT_ACCEPT,
  ATTACHMENT_DEFAULT_LIMITS,
  type AttachmentLimits,
  type AttachmentRejection,
  filesFromDataTransfer,
  formatFileSize,
  isPreviewableImage,
  selectAttachments,
} from './attachments'

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
 * Clipboard paste is **C-024** and is deliberately not here. `onAdd` is the seam
 * it will use — `RichTextEditor.onPasteFiles` already routes image pastes out as
 * `File[]`, which is the exact shape this takes.
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
   * Signed URL, or an object URL for a file not yet uploaded.
   *
   * Never rendered while `status` is `scanning` — §4B.4 is explicit that a file
   * is not visible until the scan passes, and a thumbnail is visibility.
   */
  thumbnailUrl?: string | null
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
  disabled?: boolean
  className?: string
  id?: string
  'aria-labelledby'?: string
  'aria-describedby'?: string
  'aria-label'?: string
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

export function AttachmentPicker({
  items,
  onAdd,
  onRemove,
  onReject,
  limits = ATTACHMENT_DEFAULT_LIMITS,
  compact = false,
  disabled = false,
  className,
  id,
  'aria-labelledby': ariaLabelledBy,
  'aria-describedby': ariaDescribedBy,
  'aria-label': ariaLabel,
}: AttachmentPickerProps) {
  const inputRef = React.useRef<HTMLInputElement>(null)
  const [dragging, setDragging] = React.useState(false)
  const [rejections, setRejections] = React.useState<{ file: File; rejection: AttachmentRejection }[]>([])

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
    (files: readonly File[]) => {
      if (disabled || files.length === 0) return
      const result = selectAttachments(files, {
        existingBytes: usedBytes,
        existingCount: usedCount,
        existingNames: items.filter((i) => i.status !== 'failed').map((i) => i.name.toLowerCase()),
        limits,
      })
      setRejections(result.rejected)
      if (result.rejected.length > 0) onReject?.(result.rejected)
      if (result.accepted.length > 0) onAdd(result.accepted)
    },
    [disabled, items, limits, onAdd, onReject, usedBytes, usedCount],
  )

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
            Drag files here, or{' '}
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

      {items.length > 0 && (
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
              */}
              {item.status === 'ready' && item.thumbnailUrl && isPreviewableImage(item.contentType) ? (
                <img src={item.thumbnailUrl} alt="" className="h-7 w-7 shrink-0 rounded object-cover" />
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
                <span className="block truncate text-sm text-content" title={item.name}>
                  {item.name}
                </span>
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
        One live region for both the running total and any rejection. Two would
        race each other on a mixed drop and a screen reader would read whichever
        landed last, which is usually the total — the least useful of the two.

        The capacity line is about what can still be added, so it is suppressed
        when nothing can be: on a sealed cycle the detail page renders this
        disabled, and "1 of 20 files · 50 MB" there describes a budget the reader
        cannot spend. Rejections still speak, since a rejection is always news.
      */}
      {(rejections.length > 0 || showCapacity) && (
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
}
