import type { ComponentProps } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import type { Attachment } from '@/api/generated/model'
import { AttachmentsTab } from './AttachmentsTab'

/**
 * C-060 · the Attachments tab, blueprint §7: "gallery of every file on the
 * ticket … filterable by client-visible, grouped by cycle and stage."
 *
 * Driven entirely by props, the same choice `AttachmentGallery.test.tsx` and
 * `HistoryTab`'s own tests make: the tile/chip/lightbox rendering already has
 * its own suite, so what is worth pinning here is the grouping this component
 * adds on top — cycle, then stage, most recent cycle open — and the
 * client-visible filter, without a mock server in the way.
 */

let nextId = 1

function attachment(overrides: Partial<Attachment> = {}): Attachment {
  return {
    id: nextId++,
    fileName: 'notes.txt',
    contentType: 'text/plain',
    sizeBytes: 2048,
    scanStatus: 'CLEAN',
    downloadUrl: 'https://minio.example/full?sig=a',
    thumbnailUrl: null,
    isClientVisible: false,
    isDeleted: false,
    uploadedBy: { id: 3, displayName: 'Ravi Kumar' },
    stageCode: 'DEVELOPMENT',
    cycleNo: 1,
    createdAt: '2026-08-03T09:12:00Z',
    ...overrides,
  } as Attachment
}

function renderTab(attachments: Attachment[], overrides: Partial<ComponentProps<typeof AttachmentsTab>> = {}) {
  const onClientVisibleOnlyChange = vi.fn()
  const utils = render(
    <AttachmentsTab
      attachments={attachments}
      isLoading={false}
      loadError={null}
      clientVisibleOnly={false}
      onClientVisibleOnlyChange={onClientVisibleOnlyChange}
      {...overrides}
    />,
  )
  return { ...utils, onClientVisibleOnlyChange }
}

const cycleButton = (cycleNo: number) => screen.getByRole('button', { name: new RegExp(`Cycle ${cycleNo}\\b`) })

describe('AttachmentsTab — grouped by cycle', () => {
  it('opens only the most recent cycle by default', () => {
    renderTab([
      attachment({ cycleNo: 1, fileName: 'old.txt' }),
      attachment({ cycleNo: 2, fileName: 'new.txt' }),
    ])

    expect(cycleButton(2)).toHaveAttribute('aria-expanded', 'true')
    expect(cycleButton(1)).toHaveAttribute('aria-expanded', 'false')
  })

  it('expands an earlier cycle on request', () => {
    renderTab([attachment({ cycleNo: 1 }), attachment({ cycleNo: 2 })])

    fireEvent.click(cycleButton(1))
    expect(cycleButton(1)).toHaveAttribute('aria-expanded', 'true')
  })

  it('buckets a row with no cycle stamped under "Before cycle 1" rather than dropping it', () => {
    renderTab([attachment({ cycleNo: undefined })])
    expect(screen.getByRole('button', { name: /Before cycle 1/ })).toBeInTheDocument()
  })
})

describe('AttachmentsTab — grouped by stage within a cycle', () => {
  it('renders a separate section per stage, titled from the stage code', () => {
    renderTab([
      attachment({ cycleNo: 1, stageCode: 'INTAKE', fileName: 'report.png', contentType: 'image/png' }),
      attachment({ cycleNo: 1, stageCode: 'DEVELOPMENT', fileName: 'fix.diff' }),
    ])

    expect(screen.getByText('Intake')).toBeInTheDocument()
    expect(screen.getByText('Development')).toBeInTheDocument()
  })

  it('labels a row with no stage stamped rather than hiding it', () => {
    renderTab([attachment({ stageCode: null })])
    expect(screen.getByText('No stage recorded')).toBeInTheDocument()
  })
})

describe('AttachmentsTab — client-visible filter', () => {
  it('reports the toggle back to its caller', () => {
    const { onClientVisibleOnlyChange } = renderTab([attachment()])
    fireEvent.click(screen.getByRole('checkbox', { name: 'Client-visible only' }))
    expect(onClientVisibleOnlyChange).toHaveBeenCalledWith(true)
  })

  it('narrows the gallery to client-visible rows once the caller flips it on', () => {
    renderTab(
      [
        attachment({ fileName: 'internal-debug.log', isClientVisible: false }),
        attachment({ fileName: 'client-facing.pdf', isClientVisible: true }),
      ],
      { clientVisibleOnly: true },
    )

    expect(screen.queryByText('internal-debug.log')).not.toBeInTheDocument()
    expect(screen.getByText('client-facing.pdf')).toBeInTheDocument()
  })

  it('shows a filtered-empty message distinct from a genuinely empty ticket', () => {
    renderTab([attachment({ isClientVisible: false })], { clientVisibleOnly: true })
    expect(screen.getByText('No client-visible files')).toBeInTheDocument()
  })
})

describe('AttachmentsTab — uploader, per §7\'s "documents as rows with size and uploader"', () => {
  it('names who attached a document', () => {
    renderTab([attachment({ fileName: 'qa-signoff.pdf', uploadedBy: { id: 4, displayName: 'Anil Shah' } })])

    expect(screen.getByRole('link', { name: /Download qa-signoff\.pdf/ })).toHaveTextContent('Anil Shah')
  })
})

describe('AttachmentsTab — tombstones stay grouped with their stage', () => {
  it('renders a removed file under the cycle and stage it was removed from', () => {
    renderTab([
      attachment({
        fileName: 'wrong-screenshot.png',
        contentType: 'image/png',
        isDeleted: true,
        deletedBy: { id: 2, displayName: 'Meera Iyer' },
        deletedAt: '2026-08-05T10:00:00Z',
        stageCode: 'QA',
        cycleNo: 1,
      }),
    ])

    // The ticket's only cycle, so it is the "latest" one and starts open —
    // nothing to expand.
    const removed = within(screen.getByLabelText('Removed files'))
    expect(removed.getByText('wrong-screenshot.png')).toBeInTheDocument()
    expect(removed.getByText(/removed by Meera Iyer/)).toBeInTheDocument()
  })
})

describe('AttachmentsTab — loading, error and empty states', () => {
  it('shows a skeleton while loading, not the empty state', () => {
    renderTab([], { isLoading: true })
    expect(screen.queryByText('No attachments yet')).not.toBeInTheDocument()
  })

  it('surfaces the load error', () => {
    renderTab([], { loadError: 'This ticket’s attachments could not be loaded.' })
    expect(screen.getByRole('alert')).toHaveTextContent('could not be loaded')
  })

  it('says plainly when nothing has been attached', () => {
    renderTab([])
    expect(screen.getByText('No attachments yet')).toBeInTheDocument()
  })
})
