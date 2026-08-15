import * as React from 'react'
import * as DialogPrimitive from '@radix-ui/react-dialog'
import { ChevronLeft, ChevronRight, Download, Minus, Plus, X } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Button } from './button'

/**
 * A full-screen image viewer with zoom and next/previous — C-026, blueprint §4B.4.
 *
 * ## Presentational, like everything else in this directory
 *
 * It takes a list of images and an index. It does not know what an attachment is,
 * does not fetch, and imports nothing from `@/api` — the same rule
 * `attachment-picker.tsx` follows and for the same reason: three other streams
 * consume this directory, and a viewer that knew about tickets would drag the
 * generated client into their bundles. `features/tickets/attachments/AttachmentGallery`
 * is the half that knows about attachments.
 *
 * Built generic because the second consumer is already visible: C-060's
 * Attachments tab is the same viewer over a filtered list, and Stream D's chat
 * image share (D-053) has no other candidate.
 *
 * ## Radix Dialog underneath, deliberately
 *
 * A lightbox is a modal, and everything that makes a modal correct — focus trap,
 * focus restoration to the thumbnail that opened it, `Esc`, inert background,
 * scroll lock, `aria-modal` — is work `@radix-ui/react-dialog` has already done
 * and this codebase already depends on. `modal.tsx` is not reused because its
 * `ModalContent` is a centred 512px card with a border and padding; a lightbox is
 * the opposite shape. The primitive is shared, the chrome is not.
 *
 * ## Zoom
 *
 * Three steps rather than continuous. §4B.4 wants zoom so somebody can read the
 * error message in a screenshot, which is a "make it bigger" problem, not a
 * photo-editing one — and a discrete control is reachable from the keyboard,
 * where a pinch gesture is not. Panning is a drag once the image is larger than
 * the frame, and the offset resets on every zoom change and every navigation, so
 * the next image never opens scrolled to a corner of the previous one.
 */

export interface LightboxImage {
  /** Full-size source. The thumbnail is not used here — this is the viewer. */
  src: string
  /** File name, shown in the header and used as the accessible name. */
  name: string
  /**
   * Alternative text.
   *
   * Defaults to the file name rather than to `''`. A lightbox is opened
   * deliberately, so the image is content by definition — the decorative
   * fallback C-066 uses for inline images would tell a screen-reader user
   * nothing about what they just opened.
   */
  alt?: string
  /** Rendered as a download link when present. */
  downloadUrl?: string | null
}

export interface ImageLightboxProps {
  images: readonly LightboxImage[]
  /** Index into `images`; `null` closes. Controlled — the caller owns it. */
  index: number | null
  onIndexChange: (index: number | null) => void
}

const ZOOM_STEPS = [1, 2, 4] as const

export function ImageLightbox({ images, index, onIndexChange }: ImageLightboxProps) {
  const open = index !== null && index >= 0 && index < images.length
  const image = open ? images[index] : undefined

  const [zoomStep, setZoomStep] = React.useState(0)
  const [offset, setOffset] = React.useState({ x: 0, y: 0 })
  const drag = React.useRef<{ x: number; y: number; originX: number; originY: number } | null>(null)

  const zoom = ZOOM_STEPS[zoomStep]

  // Reset on every navigation. Carrying zoom across images sounds convenient and
  // is disorienting in practice: the next picture opens at 4× and off-centre,
  // showing a corner of something the user has not seen whole yet.
  React.useEffect(() => {
    setZoomStep(0)
    setOffset({ x: 0, y: 0 })
  }, [index])

  const go = React.useCallback(
    (delta: number) => {
      if (index === null || images.length === 0) return
      // Wraps, because the alternative is a disabled arrow at each end and the
      // list here is a handful of files, not a paginated feed.
      onIndexChange((index + delta + images.length) % images.length)
    },
    [index, images.length, onIndexChange],
  )

  const setZoom = React.useCallback((next: number) => {
    setZoomStep(Math.max(0, Math.min(ZOOM_STEPS.length - 1, next)))
    setOffset({ x: 0, y: 0 })
  }, [])

  /**
   * Keys Radix does not already own.
   *
   * `Esc` is Radix's. Arrows and the zoom keys are handled on the content
   * element rather than on `document`, so they are scoped to the open dialog and
   * cannot fight the ticket list underneath — which binds arrows of its own.
   */
  const onKeyDown = (event: React.KeyboardEvent) => {
    switch (event.key) {
      case 'ArrowRight':
        event.preventDefault()
        go(1)
        break
      case 'ArrowLeft':
        event.preventDefault()
        go(-1)
        break
      case '+':
      case '=':
        event.preventDefault()
        setZoom(zoomStep + 1)
        break
      case '-':
        event.preventDefault()
        setZoom(zoomStep - 1)
        break
      case '0':
        event.preventDefault()
        setZoom(0)
        break
      default:
        break
    }
  }

  const onPointerDown = (event: React.PointerEvent<HTMLImageElement>) => {
    if (zoom === 1) return
    drag.current = { x: event.clientX, y: event.clientY, originX: offset.x, originY: offset.y }
    // Pointer capture, so a drag that leaves the image — which is most of them,
    // since the point of panning is to reach what is off-screen — keeps sending
    // moves here instead of stopping dead at the edge.
    event.currentTarget.setPointerCapture(event.pointerId)
  }

  const onPointerMove = (event: React.PointerEvent) => {
    const from = drag.current
    if (!from) return
    setOffset({ x: from.originX + (event.clientX - from.x), y: from.originY + (event.clientY - from.y) })
  }

  const endDrag = () => {
    drag.current = null
  }

  return (
    <DialogPrimitive.Root open={open} onOpenChange={(next) => !next && onIndexChange(null)}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/80 data-[state=open]:animate-in data-[state=open]:fade-in-0" />
        <DialogPrimitive.Content
          onKeyDown={onKeyDown}
          className="fixed inset-0 z-50 flex flex-col focus:outline-none data-[state=open]:animate-in data-[state=open]:fade-in-0"
          aria-label={image ? `${image.name}, image viewer` : 'Image viewer'}
        >
          {/*
            The title is required by Radix for an accessible name and is visually
            hidden rather than absent: rendering the file name twice — once in
            the header, once for the dialog — would have a screen reader read it
            twice on open.
          */}
          <DialogPrimitive.Title className="sr-only">
            {image ? `${image.name} (${(index ?? 0) + 1} of ${images.length})` : 'Image viewer'}
          </DialogPrimitive.Title>

          <header className="flex items-center gap-2 bg-black/40 px-4 py-2 text-white">
            <span className="min-w-0 flex-1 truncate text-sm" title={image?.name}>
              {image?.name}
            </span>

            {images.length > 1 && (
              <span className="shrink-0 text-caption text-white/70" aria-hidden>
                {(index ?? 0) + 1} / {images.length}
              </span>
            )}

            <div className="flex shrink-0 items-center gap-1">
              <LightboxButton onClick={() => setZoom(zoomStep - 1)} disabled={zoomStep === 0} label="Zoom out">
                <Minus className="h-4 w-4" aria-hidden />
              </LightboxButton>
              {/* Not aria-hidden: it is the only feedback that a zoom press did anything. */}
              <span className="w-12 text-center text-caption tabular-nums" role="status" aria-live="polite">
                {zoom}×
              </span>
              <LightboxButton
                onClick={() => setZoom(zoomStep + 1)}
                disabled={zoomStep === ZOOM_STEPS.length - 1}
                label="Zoom in"
              >
                <Plus className="h-4 w-4" aria-hidden />
              </LightboxButton>

              {image?.downloadUrl && (
                <a
                  href={image.downloadUrl}
                  // The signed URL already carries Content-Disposition: attachment,
                  // so `download` is belt to that braces — and it must not carry a
                  // filename, which would silently override the server's and put a
                  // name the client chose on the user's disk.
                  download
                  className="inline-flex h-8 w-8 items-center justify-center rounded-control text-white/80 transition-colors hover:bg-white/10 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white"
                >
                  <Download className="h-4 w-4" aria-hidden />
                  <span className="sr-only">Download {image.name}</span>
                </a>
              )}

              <DialogPrimitive.Close asChild>
                <LightboxButton label="Close">
                  <X className="h-4 w-4" aria-hidden />
                </LightboxButton>
              </DialogPrimitive.Close>
            </div>
          </header>

          <div className="relative flex min-h-0 flex-1 items-center justify-center overflow-hidden">
            {images.length > 1 && (
              <LightboxButton
                onClick={() => go(-1)}
                label="Previous image"
                className="absolute left-2 top-1/2 z-10 h-10 w-10 -translate-y-1/2 bg-black/40"
              >
                <ChevronLeft className="h-6 w-6" aria-hidden />
              </LightboxButton>
            )}

            {image && (
              <img
                key={image.src}
                src={image.src}
                alt={image.alt ?? image.name}
                onPointerDown={onPointerDown}
                onPointerMove={onPointerMove}
                onPointerUp={endDrag}
                onPointerCancel={endDrag}
                // Dragging a browser's native image ghost around on top of a pan
                // gesture makes the pan feel broken on the first attempt.
                draggable={false}
                style={{ transform: `translate(${offset.x}px, ${offset.y}px) scale(${zoom})` }}
                className={cn(
                  'max-h-full max-w-full select-none object-contain transition-transform',
                  zoom > 1 ? 'cursor-grab active:cursor-grabbing' : 'cursor-default',
                )}
              />
            )}

            {images.length > 1 && (
              <LightboxButton
                onClick={() => go(1)}
                label="Next image"
                className="absolute right-2 top-1/2 z-10 h-10 w-10 -translate-y-1/2 bg-black/40"
              >
                <ChevronRight className="h-6 w-6" aria-hidden />
              </LightboxButton>
            )}
          </div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}

/**
 * The chrome buttons.
 *
 * Local rather than `Button` with a variant: every control here sits on a
 * photograph, so it needs a light-on-dark treatment that exists nowhere else in
 * the product. Adding a `variant="on-image"` to the shared button would put a
 * style three other streams can reach into their API for one screen's benefit.
 */
const LightboxButton = React.forwardRef<
  HTMLButtonElement,
  React.ComponentPropsWithoutRef<typeof Button> & { label: string }
>(function LightboxButton({ label, className, ...props }, ref) {
  return (
    <button
      ref={ref}
      type="button"
      aria-label={label}
      className={cn(
        'inline-flex h-8 w-8 items-center justify-center rounded-control text-white/80 transition-colors',
        'hover:bg-white/10 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white',
        'disabled:pointer-events-none disabled:opacity-40',
        className,
      )}
      {...props}
    />
  )
})
