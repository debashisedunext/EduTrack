import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AttachmentItem } from '@/components/ui/attachment-picker'
import { AttachmentGallery } from './AttachmentGallery'

/**
 * C-026 · S-20's attachment strip.
 *
 * The assertions worth having here are all about **which source ends up in the
 * `<img>`**, because that is where this component earns its keep: a null
 * `thumbnailUrl` has four ordinary causes and none of them means "not an image",
 * and a version that treated it as one would show a file icon for a perfectly
 * good screenshot without anything failing.
 */

function item(overrides: Partial<AttachmentItem> = {}): AttachmentItem {
  return {
    id: '1',
    name: 'screenshot.png',
    sizeBytes: 12_345,
    contentType: 'image/png',
    status: 'ready',
    thumbnailUrl: 'https://minio.example/thumb.png?sig=a',
    downloadUrl: 'https://minio.example/full.png?sig=b',
    ...overrides,
  }
}

/**
 * The strip's own images.
 *
 * Not `getAllByRole('img')`, and the reason is worth writing down because it
 * cost a round trip: a tile's `<img>` carries `alt=""`, which makes it
 * presentational and removes it from the accessibility tree entirely — so a role
 * query finds nothing and, worse, a `queryByRole('img')` assertion for *absence*
 * would pass whether or not the image were there. That empty alt is deliberate
 * (the file name is on the enclosing button, and repeating it makes a screen
 * reader announce every tile twice), so the test reaches for the DOM instead.
 *
 * Scoped to the list so it never picks up the lightbox's image, which does have
 * alternative text and is a different assertion.
 */
function tiles(): HTMLImageElement[] {
  const list = screen.queryByRole('list', { name: 'Attached files' })
  return list ? Array.from(list.querySelectorAll('img')) : []
}

describe('AttachmentGallery — which source is rendered', () => {
  it('prefers the reduction when the server stored one', () => {
    render(<AttachmentGallery items={[item()]} />)
    expect(tiles()[0]).toHaveAttribute('src', 'https://minio.example/thumb.png?sig=a')
  })

  it('falls back to the full image when there is no reduction', () => {
    // The four ordinary causes: a WebP the JVM cannot decode, an image already
    // smaller than the thumbnail box, one that failed to decode, and thumbnails
    // switched off. Showing a file icon for any of them would be wrong.
    render(<AttachmentGallery items={[item({ thumbnailUrl: null })]} />)
    expect(tiles()[0]).toHaveAttribute('src', 'https://minio.example/full.png?sig=b')
  })

  it('renders a webp from its full file, since the server never reduces one', () => {
    render(<AttachmentGallery items={[item({ contentType: 'image/webp', thumbnailUrl: null })]} />)
    expect(tiles()[0]).toHaveAttribute('src', 'https://minio.example/full.png?sig=b')
  })

  it('shows no image at all while the scan is still running', () => {
    // §4B.4: a file is not visible until the scan passes, and a thumbnail is
    // visibility. The server sends no URLs for a PENDING row, but this must not
    // depend on that — a client that would have rendered one if handed one is a
    // client that renders one the day something else changes.
    render(<AttachmentGallery items={[item({ status: 'scanning' })]} />)

    expect(tiles()).toHaveLength(0)
    expect(screen.getByText(/scanning for viruses/)).toBeInTheDocument()
  })

  it('shows no image for a failed upload', () => {
    render(<AttachmentGallery items={[item({ status: 'failed', error: 'Failed the virus scan' })]} />)

    expect(tiles()).toHaveLength(0)
    expect(screen.getByText(/Failed the virus scan/)).toBeInTheDocument()
  })

  it('renders nothing when there is nothing attached', () => {
    const { container } = render(<AttachmentGallery items={[]} />)
    expect(container).toBeEmptyDOMElement()
  })
})

describe('AttachmentGallery — images and documents are drawn differently', () => {
  it('gives a document a named download link rather than a tile', () => {
    // A 64px square is a good look at a screenshot and a terrible way to read
    // `qa-signoff-report-final-v2.pdf`. The wireframe already splits them.
    render(
      <AttachmentGallery
        items={[item({ id: '2', name: 'error-log.txt', contentType: 'text/plain', thumbnailUrl: null })]}
      />,
    )

    expect(tiles()).toHaveLength(0)
    expect(screen.getByRole('link', { name: /Download error-log\.txt/ })).toHaveAttribute(
      'href',
      'https://minio.example/full.png?sig=b',
    )
  })

  it('does not link a document whose scan has not passed', () => {
    render(
      <AttachmentGallery
        items={[item({ name: 'error-log.txt', contentType: 'text/plain', status: 'scanning', downloadUrl: null })]}
      />,
    )
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })
})

describe('AttachmentGallery — the lightbox', () => {
  const three: AttachmentItem[] = [
    item({ id: '1', name: 'one.png', downloadUrl: 'blob:one' }),
    item({ id: '2', name: 'two.png', downloadUrl: 'blob:two' }),
    item({ id: '3', name: 'three.png', downloadUrl: 'blob:three' }),
  ]

  it('opens on the tile that was clicked', async () => {
    const user = userEvent.setup()
    render(<AttachmentGallery items={three} />)

    await user.click(screen.getByRole('button', { name: /Open two\.png/ }))

    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText('two.png (2 of 3)')).toBeInTheDocument()
  })

  it('shows the full image, not the reduction', async () => {
    // The reduction is 320px on its longest side. Opening a viewer onto it would
    // make "zoom" mean "look at a blurry copy" — the one thing zoom is for is
    // reading text the strip is too small to show.
    const user = userEvent.setup()
    render(<AttachmentGallery items={[item({ downloadUrl: 'blob:full' })]} />)

    await user.click(screen.getByRole('button', { name: /Open screenshot\.png/ }))

    const inDialog = screen.getByRole('dialog').querySelector('img') as HTMLImageElement
    expect(inDialog).toHaveAttribute('src', 'blob:full')
  })

  it('pages through images hidden behind the fold', async () => {
    // Built over the whole list rather than the visible slice. A viewer that
    // stopped at the fold would show fewer images to someone who opened it
    // before expanding than to someone who expanded first.
    const user = userEvent.setup()
    const ten = Array.from({ length: 10 }, (_, i) =>
      item({ id: String(i), name: `img-${i}.png`, downloadUrl: `blob:${i}` }),
    )
    render(<AttachmentGallery items={ten} />)

    await user.click(screen.getByRole('button', { name: /Open img-0\.png/ }))

    expect(screen.getByText('img-0.png (1 of 10)')).toBeInTheDocument()
  })

  it('does not open for a document', () => {
    render(<AttachmentGallery items={[item({ name: 'notes.pdf', contentType: 'application/pdf' })]} />)

    expect(screen.queryByRole('button', { name: /Open notes\.pdf/ })).not.toBeInTheDocument()
  })
})

describe('AttachmentGallery — overflow', () => {
  const many = Array.from({ length: 11 }, (_, i) =>
    item({ id: String(i), name: `img-${i}.png`, downloadUrl: `blob:${i}` }),
  )

  it('collapses past eight and says how many are hidden', () => {
    render(<AttachmentGallery items={many} />)

    expect(tiles()).toHaveLength(8)
    expect(screen.getByRole('button', { name: '+3 more' })).toBeInTheDocument()
  })

  it('expands and collapses again', async () => {
    const user = userEvent.setup()
    render(<AttachmentGallery items={many} />)

    await user.click(screen.getByRole('button', { name: '+3 more' }))
    expect(tiles()).toHaveLength(11)

    await user.click(screen.getByRole('button', { name: 'Show fewer' }))
    expect(tiles()).toHaveLength(8)
  })

  it('offers no overflow control for a list that fits', () => {
    render(<AttachmentGallery items={many.slice(0, 8)} />)
    expect(screen.queryByRole('button', { name: /more/ })).not.toBeInTheDocument()
  })
})

describe('AttachmentGallery — removal', () => {
  it('reports the id it was given', async () => {
    const user = userEvent.setup()
    const onRemove = vi.fn()
    render(<AttachmentGallery items={[item({ id: '42' })]} onRemove={onRemove} />)

    await user.click(screen.getByRole('button', { name: 'Remove screenshot.png' }))

    expect(onRemove).toHaveBeenCalledWith('42')
  })

  it('renders no remove control when the caller offers none', () => {
    // A sealed cycle. Its files stay readable — that is the point of preserving
    // a cycle — but nothing may be taken out of a closed journey.
    render(<AttachmentGallery items={[item()]} />)
    expect(screen.queryByRole('button', { name: /Remove/ })).not.toBeInTheDocument()
  })

  it('keeps the remove control in the DOM rather than hiding it from the keyboard', () => {
    // Revealed on hover, but present and focusable — a hover-only control is
    // unreachable from a keyboard and invisible on a touch screen.
    render(<AttachmentGallery items={[item()]} onRemove={vi.fn()} />)
    expect(screen.getByRole('button', { name: 'Remove screenshot.png' })).toBeVisible()
  })
})
