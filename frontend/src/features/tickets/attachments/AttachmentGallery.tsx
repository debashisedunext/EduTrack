import * as React from 'react'
import { AlertTriangle, FileText, Loader2, ShieldAlert, X } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import type { AttachmentItem } from '@/components/ui/attachment-picker'
import { attachmentPreviewSource, formatFileSize, isPreviewableImage } from '@/components/ui/attachments'
import { ImageLightbox, type LightboxImage } from '@/components/ui/image-lightbox'

/**
 * S-20's attachment strip — C-026, blueprint §4B.4.
 *
 * The wireframe is `📎 [thumb][thumb] error-log.txt   +2`, and it is doing three
 * things at once: pictures shown as pictures, everything else named, and a count
 * for the rest. Clicking a picture opens it full-screen.
 *
 * ## Images are tiles, documents are chips
 *
 * Not one uniform grid, because a 64px square is a good look at a screenshot and
 * a terrible way to read `qa-signoff-report-final-v2.pdf`. The wireframe already
 * makes the distinction; this follows it.
 *
 * ## It replaces the picker's list rather than sitting above it
 *
 * `AttachmentPicker` renders its own flat list of rows, which is right for the
 * create form and the quick update panel. On the detail page it would mean the
 * same six files drawn twice, once as tiles and once as rows — so the picker is
 * given `showItems={false}` there and still receives the full `items`, because
 * its validation depends on them. Two presentations of one array, and one place
 * that decides which.
 *
 * ## Why it lives here and not in `components/ui/`
 *
 * Its input is `AttachmentItem`, which is shared, so this *could* have gone in
 * the component library. It does not, for the same reason `SavedViewsMenu` did
 * not: the arrangement is specific to §4B.4 — the image/document split, the
 * scan-state placeholders, the overflow rule — where `ImageLightbox` underneath
 * it is genuinely general, and *that* is in `components/ui/` with a story.
 * C-060's Attachments tab will reuse this component; Stream B's import wizard
 * never would.
 */

/** Enough for two rows of tiles without the strip taking the page over. */
const COLLAPSED_LIMIT = 8

export function AttachmentGallery({
  items,
  onRemove,
  className,
}: {
  items: readonly AttachmentItem[]
  /** Omit to render no remove control at all — C-028 owns the 15-minute rule. */
  onRemove?: (id: string) => void
  className?: string
}) {
  const [expanded, setExpanded] = React.useState(false)
  const [lightboxIndex, setLightboxIndex] = React.useState<number | null>(null)

  /**
   * Every openable image on the ticket, in order.
   *
   * Built over the *whole* list and not over the visible slice, so the viewer
   * pages through pictures hidden behind "+N more" as well. A viewer that stopped
   * at the fold would silently show fewer images to someone who opened it before
   * expanding than to someone who expanded first, which is the kind of difference
   * nobody reports and everybody notices.
   */
  const lightboxImages = React.useMemo<LightboxImage[]>(
    () =>
      items
        .filter((item) => item.status === 'ready' && item.downloadUrl && isPreviewableImage(item.contentType))
        .map((item) => ({ name: item.name, src: item.downloadUrl as string, downloadUrl: item.downloadUrl })),
    [items],
  )

  const openLightboxAt = React.useCallback(
    (item: AttachmentItem) => {
      const index = lightboxImages.findIndex((image) => image.src === item.downloadUrl)
      if (index >= 0) setLightboxIndex(index)
    },
    [lightboxImages],
  )

  if (items.length === 0) return null

  const visible = expanded ? items : items.slice(0, COLLAPSED_LIMIT)
  const hidden = items.length - visible.length

  return (
    <div className={cn('flex flex-col gap-2', className)}>
      <ul className="flex flex-wrap items-start gap-2" aria-label="Attached files">
        {visible.map((item) => (
          // `group` sits here so the remove control can reveal on hover of the
          // whole tile rather than only of itself, which would be a 20px target.
          <li key={item.id} className="group relative">
            {isPreviewableImage(item.contentType) ? (
              <ImageTile item={item} onOpen={() => openLightboxAt(item)} />
            ) : (
              <FileChip item={item} />
            )}

            {onRemove && (
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={() => onRemove(item.id)}
                aria-label={`Remove ${item.name}`}
                /*
                  Revealed on hover *and* on focus, and never removed from the
                  DOM: a control that exists only on hover is unreachable from a
                  keyboard and invisible on a touch screen, so it is forced
                  visible where the device has no hover at all.
                */
                className={cn(
                  'absolute -right-1.5 -top-1.5 h-5 w-5 rounded-chip border border-border bg-surface p-0',
                  'text-content-muted opacity-0 shadow-rest transition-opacity hover:text-danger-text',
                  'group-hover:opacity-100 focus-visible:opacity-100 [@media(hover:none)]:opacity-100',
                )}
              >
                <X className="h-3 w-3" aria-hidden />
              </Button>
            )}
          </li>
        ))}
      </ul>

      {hidden > 0 && (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => setExpanded(true)}
          className="h-auto self-start p-0 text-caption text-content-muted"
        >
          +{hidden} more
        </Button>
      )}

      {expanded && items.length > COLLAPSED_LIMIT && (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => setExpanded(false)}
          className="h-auto self-start p-0 text-caption text-content-muted"
        >
          Show fewer
        </Button>
      )}

      <ImageLightbox images={lightboxImages} index={lightboxIndex} onIndexChange={setLightboxIndex} />
    </div>
  )
}

/**
 * A picture.
 *
 * Becomes a button when there is something to open. An image still being
 * scanned, or one whose upload failed, renders as a static placeholder — §4B.4
 * is explicit that a file is not visible until the scan passes, and there is
 * nothing behind a click on it either way.
 */
function ImageTile({ item, onOpen }: { item: AttachmentItem; onOpen: () => void }) {
  // `attachmentPreviewSource` is what handles the null-thumbnail cases — a WebP,
  // an image already smaller than the reduction box, one the server could not
  // decode — by falling back to the full file rather than to an icon.
  const preview = item.status === 'ready' ? attachmentPreviewSource(item) : undefined
  const label = `${item.name}, ${formatFileSize(item.sizeBytes)}${statusSuffix(item)}`

  if (!preview) {
    return (
      <span
        className={cn(
          'flex h-16 w-16 items-center justify-center rounded-control border border-border bg-subtle',
          item.status === 'failed' && 'border-danger/40',
        )}
        title={label}
      >
        <StatusIcon item={item} />
        <span className="sr-only">{label}</span>
      </span>
    )
  }

  return (
    <button
      type="button"
      onClick={onOpen}
      title={label}
      aria-label={`Open ${label}`}
      className="block h-16 w-16 overflow-hidden rounded-control border border-border bg-surface transition-shadow hover:shadow-modal focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
    >
      <img
        src={preview}
        // Empty alt with the name on the button instead: the file name is the
        // only description available, and putting it in both places makes a
        // screen reader announce every tile twice.
        alt=""
        // Native lazy loading — a ticket may carry twenty of these and the tabs
        // below the strip matter more. No observer and no polyfill needed.
        loading="lazy"
        className="h-full w-full object-cover"
      />
    </button>
  )
}

/** Everything that is not a picture: a name, a size, and what state it is in. */
function FileChip({ item }: { item: AttachmentItem }) {
  const label = `${item.name}, ${formatFileSize(item.sizeBytes)}${statusSuffix(item)}`

  const inner = (
    <>
      <StatusIcon item={item} />
      <span className="min-w-0 flex-1">
        <span className="block max-w-[14rem] truncate text-sm text-content">{item.name}</span>
        <span className="block text-caption text-content-muted">
          {formatFileSize(item.sizeBytes)}
          {item.status === 'failed' && item.error ? ` · ${item.error}` : ''}
        </span>
      </span>
    </>
  )

  const shell = cn(
    'flex h-16 items-center gap-2 rounded-control border border-border bg-surface px-2.5',
    item.status === 'failed' && 'border-danger/40',
  )

  if (item.status !== 'ready' || !item.downloadUrl) {
    return (
      <span className={shell} title={label}>
        {inner}
        <span className="sr-only">{label}</span>
      </span>
    )
  }

  return (
    <a
      href={item.downloadUrl}
      // The signed URL already carries `Content-Disposition: attachment`, so this
      // is belt to that braces. It carries no filename: supplying one would
      // override the server's, and the server's is the one that has been through
      // RFC 6266 encoding and had CR/LF stripped out of it.
      download
      title={label}
      aria-label={`Download ${label}`}
      className={cn(
        shell,
        'transition-shadow hover:shadow-modal focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
      )}
    >
      {inner}
    </a>
  )
}

function StatusIcon({ item }: { item: AttachmentItem }) {
  if (item.status === 'uploading') {
    return <Loader2 className="h-4 w-4 shrink-0 animate-spin text-content-muted" aria-hidden />
  }
  if (item.status === 'scanning') {
    return <ShieldAlert className="h-4 w-4 shrink-0 text-warning-text" aria-hidden />
  }
  if (item.status === 'failed') {
    return <AlertTriangle className="h-4 w-4 shrink-0 text-danger-text" aria-hidden />
  }
  return <FileText className="h-4 w-4 shrink-0 text-content-muted" aria-hidden />
}

/**
 * What the accessible name says about state.
 *
 * Only the states that are visible in the tile's own icon, so the two agree.
 * "Ready" is deliberately unsaid — announcing it on every tile of a twenty-file
 * strip is noise, and it is what a reader assumes by default.
 */
function statusSuffix(item: AttachmentItem): string {
  switch (item.status) {
    case 'uploading':
      return ', uploading'
    case 'scanning':
      return ', scanning for viruses'
    case 'failed':
      return `, failed: ${item.error ?? 'unknown error'}`
    default:
      return ''
  }
}
