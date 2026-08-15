import * as React from 'react'
import type { Meta, StoryObj } from '@storybook/react-vite'
import { Button } from './button'
import { ImageLightbox, type LightboxImage } from './image-lightbox'

/**
 * The full-screen image viewer — C-026, blueprint §4B.4.
 *
 * Presentational and controlled: it takes a list and an index, and the caller
 * owns both. It knows nothing about attachments, which is what lets C-060's
 * Attachments tab and Stream D's chat image share reuse it unchanged.
 *
 * **Storybook is the contract for this component, and it needs a real browser to
 * mean anything.** Three of the behaviours below cannot be exercised in jsdom at
 * all — pointer-capture panning, focus restoration to the trigger on close, and
 * the CSS transform that does the zooming — so the stories are where they are
 * verified, the same argument C-024's `ClipboardPaste` story makes.
 */
const meta: Meta<typeof ImageLightbox> = {
  title: 'UI/ImageLightbox',
  component: ImageLightbox,
  tags: ['autodocs'],
  parameters: {
    docs: {
      description: {
        component:
          'Zoom in three steps, next/previous with wrap, drag to pan while zoomed. `Esc`, arrows, `+`/`-` and `0` are all bound; focus is trapped while open and returns to whatever opened it.',
      },
    },
  },
}
export default meta

type Story = StoryObj<typeof ImageLightbox>

/**
 * Inline SVG data URIs rather than files or a network image.
 *
 * A story that reached for a remote placeholder service would fail in every
 * offline build and in CI, and committing three PNGs to prove a viewer renders
 * an `<img>` is weight the repository does not need. These are deliberately
 * different sizes and aspect ratios, because letterboxing and the never-upscale
 * rule are the two things most easily got wrong.
 */
function swatch(label: string, width: number, height: number, background: string): string {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">
    <rect width="${width}" height="${height}" fill="${background}"/>
    <text x="50%" y="50%" fill="#ffffff" font-family="sans-serif" font-size="${Math.round(Math.min(width, height) / 6)}"
          text-anchor="middle" dominant-baseline="middle">${label}</text>
  </svg>`
  return `data:image/svg+xml;utf8,${encodeURIComponent(svg)}`
}

const IMAGES: LightboxImage[] = [
  { name: 'fees-screen-error.png', src: swatch('1 · wide', 1200, 600, '#1F6FEB'), downloadUrl: '#' },
  { name: 'stack-trace.png', src: swatch('2 · tall', 600, 1200, '#8250DF') },
  { name: 'network-tab.png', src: swatch('3 · square', 800, 800, '#1A7F37'), downloadUrl: '#' },
]

/** The caller owns the index — this is what every consumer has to write. */
function Stateful({ images, startAt = 0 }: { images: LightboxImage[]; startAt?: number }) {
  const [index, setIndex] = React.useState<number | null>(null)
  return (
    <div className="flex flex-col items-start gap-3">
      <Button type="button" onClick={() => setIndex(startAt)}>
        Open viewer
      </Button>
      <p className="text-caption text-content-muted">
        Arrows navigate · <kbd>+</kbd>/<kbd>-</kbd> zoom · <kbd>0</kbd> resets · <kbd>Esc</kbd> closes
      </p>
      <ImageLightbox images={images} index={index} onIndexChange={setIndex} />
    </div>
  )
}

/** Several images: both arrows, the counter, and wrap-around at each end. */
export const Gallery: Story = {
  render: () => <Stateful images={IMAGES} />,
}

/**
 * One image — no arrows and no counter.
 *
 * The common case on a ticket, where a support agent has pasted exactly one
 * screenshot. Arrows that navigate nowhere read as broken.
 */
export const SingleImage: Story = {
  render: () => <Stateful images={[IMAGES[0]]} />,
}

/**
 * Opened on the last image, to check the wrap forwards rather than dead-ending.
 */
export const OpenedOnTheLast: Story = {
  render: () => <Stateful images={IMAGES} startAt={IMAGES.length - 1} />,
}

/**
 * No download control.
 *
 * `downloadUrl` is absent whenever the server has not signed one — which for an
 * attachment means the scan has not passed. The button is not rendered rather
 * than rendered disabled: there is nothing the user can do to enable it, and a
 * dead control invites clicking.
 */
export const WithoutDownload: Story = {
  render: () => <Stateful images={[{ name: 'pending-scan.png', src: IMAGES[1].src }]} />,
}
