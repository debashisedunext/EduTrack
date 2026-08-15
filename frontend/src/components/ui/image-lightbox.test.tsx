import * as React from 'react'
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ImageLightbox, type LightboxImage } from './image-lightbox'

/**
 * C-026 · the full-screen viewer.
 *
 * ## What jsdom cannot check, and where it is checked instead
 *
 * Three of this component's behaviours have no meaning here and are covered by
 * the Storybook stories, which run in a real browser: the pan gesture (jsdom has
 * no pointer capture and no layout, so a drag moves nothing observable), the
 * zoom itself (a CSS transform jsdom neither applies nor measures), and focus
 * restoration to the trigger on close. The story file says so at the top.
 *
 * What *is* asserted here is everything that is logic rather than rendering:
 * which image is shown, how navigation wraps, that zoom state resets, and the
 * accessibility contract — because those are the parts that break silently in a
 * refactor.
 */

const IMAGES: LightboxImage[] = [
  { name: 'one.png', src: 'blob:one', downloadUrl: 'blob:one' },
  { name: 'two.png', src: 'blob:two' },
  { name: 'three.png', src: 'blob:three', downloadUrl: 'blob:three' },
]

/** Controlled, exactly as a caller has to write it. */
function Harness({ images = IMAGES, startAt = 0 }: { images?: LightboxImage[]; startAt?: number | null }) {
  const [index, setIndex] = React.useState<number | null>(startAt)
  return <ImageLightbox images={images} index={index} onIndexChange={setIndex} />
}

const shownImage = () => screen.getByRole('img') as HTMLImageElement

describe('ImageLightbox — what is open', () => {
  it('renders nothing at all when the index is null', () => {
    render(<Harness startAt={null} />)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('opens on the image the index names', () => {
    render(<Harness startAt={1} />)
    expect(shownImage()).toHaveAttribute('src', 'blob:two')
  })

  it('ignores an index past the end rather than rendering a blank viewer', () => {
    // A caller whose list shrank under it — a delete, a refetch — must get a
    // closed dialog, not an open one showing nothing.
    render(<Harness startAt={99} />)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('closes through onIndexChange rather than holding its own open state', () => {
    const onIndexChange = vi.fn()
    render(<ImageLightbox images={IMAGES} index={0} onIndexChange={onIndexChange} />)

    screen.getByRole('button', { name: 'Close' }).click()

    expect(onIndexChange).toHaveBeenCalledWith(null)
  })
})

describe('ImageLightbox — navigation', () => {
  it('moves forwards and backwards', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Next image' }))
    expect(shownImage()).toHaveAttribute('src', 'blob:two')

    await user.click(screen.getByRole('button', { name: 'Previous image' }))
    expect(shownImage()).toHaveAttribute('src', 'blob:one')
  })

  it('wraps at both ends', async () => {
    // Wrap rather than disabled arrows: the list here is a handful of files on
    // one ticket, not a paginated feed, and a dead control at each end invites
    // clicking.
    const user = userEvent.setup()
    render(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Previous image' }))
    expect(shownImage()).toHaveAttribute('src', 'blob:three')

    await user.click(screen.getByRole('button', { name: 'Next image' }))
    expect(shownImage()).toHaveAttribute('src', 'blob:one')
  })

  it('moves with the arrow keys', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.keyboard('{ArrowRight}')
    expect(shownImage()).toHaveAttribute('src', 'blob:two')

    await user.keyboard('{ArrowLeft}')
    expect(shownImage()).toHaveAttribute('src', 'blob:one')
  })

  it('renders no arrows and no counter for a single image', () => {
    // The common case on a ticket: one pasted screenshot.
    render(<Harness images={[IMAGES[0]]} />)

    expect(screen.queryByRole('button', { name: 'Next image' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Previous image' })).not.toBeInTheDocument()
    expect(screen.queryByText('1 / 1')).not.toBeInTheDocument()
  })
})

describe('ImageLightbox — zoom', () => {
  it('steps up and down and stops at each end', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    const zoomIn = screen.getByRole('button', { name: 'Zoom in' })
    const zoomOut = screen.getByRole('button', { name: 'Zoom out' })

    expect(zoomOut).toBeDisabled()
    await user.click(zoomIn)
    expect(screen.getByRole('status')).toHaveTextContent('2×')

    await user.click(zoomIn)
    expect(screen.getByRole('status')).toHaveTextContent('4×')
    expect(zoomIn).toBeDisabled()

    await user.click(zoomOut)
    expect(screen.getByRole('status')).toHaveTextContent('2×')
  })

  it('zooms from the keyboard, including the reset', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.keyboard('{+}')
    expect(screen.getByRole('status')).toHaveTextContent('2×')

    await user.keyboard('0')
    expect(screen.getByRole('status')).toHaveTextContent('1×')
  })

  it('resets to 1× when the image changes', async () => {
    // Carrying zoom across images sounds convenient and is disorienting: the
    // next picture opens at 4× and off-centre, showing a corner of something
    // nobody has seen whole yet.
    const user = userEvent.setup()
    render(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Zoom in' }))
    expect(screen.getByRole('status')).toHaveTextContent('2×')

    await user.click(screen.getByRole('button', { name: 'Next image' }))
    expect(screen.getByRole('status')).toHaveTextContent('1×')
  })
})

describe('ImageLightbox — accessibility', () => {
  it('names the dialog with the file and its position', () => {
    render(<Harness startAt={1} />)
    expect(screen.getByText('two.png (2 of 3)')).toBeInTheDocument()
  })

  it('gives the image the file name as alternative text by default', () => {
    // A lightbox is opened deliberately, so the image is content by definition —
    // the decorative empty alt used for inline images would tell a screen-reader
    // user nothing about what they just opened.
    render(<Harness />)
    expect(shownImage()).toHaveAttribute('alt', 'one.png')
  })

  it('prefers explicit alternative text where a caller supplies it', () => {
    render(<Harness images={[{ name: 'one.png', src: 'blob:one', alt: 'The fees screen showing error 500' }]} />)
    expect(shownImage()).toHaveAttribute('alt', 'The fees screen showing error 500')
  })

  it('announces the zoom level in a live region', async () => {
    // The only feedback a zoom press produces — the transform itself is
    // invisible to a screen reader.
    const user = userEvent.setup()
    render(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Zoom in' }))
    expect(screen.getByRole('status')).toHaveAttribute('aria-live', 'polite')
  })
})

describe('ImageLightbox — download', () => {
  it('offers a download when the caller has a URL for one', () => {
    render(<Harness />)
    expect(screen.getByRole('link', { name: 'Download one.png' })).toHaveAttribute('href', 'blob:one')
  })

  it('renders no download control at all when there is no URL', () => {
    // Absent rather than disabled: there is nothing the user can do to enable
    // it — the scan has not passed — and a dead control invites clicking.
    render(<Harness startAt={1} />)
    expect(screen.queryByRole('link', { name: /Download/ })).not.toBeInTheDocument()
  })

  it('sets no filename on the download attribute', () => {
    // Supplying one would override the server's, and the server's is the one
    // that has been through RFC 6266 encoding with CR/LF stripped out of it.
    render(<Harness />)
    expect(screen.getByRole('link', { name: 'Download one.png' })).toHaveAttribute('download', '')
  })
})
