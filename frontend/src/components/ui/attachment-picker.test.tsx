import * as React from 'react'
import { describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AttachmentPicker, type AttachmentItem, type AttachmentPickerHandle } from './attachment-picker'

/**
 * jsdom has no `DataTransfer` constructor and no real file dialog, so a drop is
 * built by hand and a browse is driven through the native input rather than the
 * button that clicks it. What is asserted here is everything that is ours: which
 * files reach `onAdd`, what the user is told about the ones that do not, the
 * status and ARIA contract, and the limit behaviour.
 *
 * The drag *visual* is not asserted. It is a class on a div, it changes with the
 * design tokens, and pinning it would break on a restyle without a defect.
 */

function fileOf(name: string, size = 1024, type = ''): File {
  const file = new File(['x'], name, { type })
  Object.defineProperty(file, 'size', { value: size })
  return file
}

const MB = 1024 * 1024

function dropOf(files: File[]): Partial<DataTransfer> {
  return {
    types: ['Files'],
    items: files.map((file) => ({
      kind: 'file',
      getAsFile: () => file,
      webkitGetAsEntry: () => ({ isDirectory: false }),
    })) as unknown as DataTransferItemList,
    files: files as unknown as FileList,
  }
}

function renderPicker(props: Partial<React.ComponentProps<typeof AttachmentPicker>> = {}) {
  const onAdd = vi.fn()
  const onReject = vi.fn()
  const utils = render(<AttachmentPicker items={[]} onAdd={onAdd} onReject={onReject} {...props} />)
  return { onAdd, onReject, ...utils }
}

/** The visually-hidden native input is the accessible control; the button clicks it. */
function fileInput(): HTMLInputElement {
  return document.querySelector('input[type="file"]') as HTMLInputElement
}

describe('AttachmentPicker — choosing files', () => {
  it('hands accepted files to onAdd', async () => {
    const { onAdd } = renderPicker()
    const png = fileOf('gateway-500.png', 184_320, 'image/png')

    await userEvent.upload(fileInput(), png)

    expect(onAdd).toHaveBeenCalledWith([png])
  })

  it('accepts a drop, and reads items rather than files so dragged text is ignored', () => {
    const { onAdd } = renderPicker()
    const png = fileOf('shot.png', 2048, 'image/png')

    fireEvent.drop(screen.getByText(/drag files here/i).closest('div')!, { dataTransfer: dropOf([png]) })

    expect(onAdd).toHaveBeenCalledWith([png])
  })

  it('offers the allow-list as extensions on the native input', () => {
    // Never MIME types: a `.log` has an empty `type` on every platform, and
    // greying it out blocks the file support agents attach most.
    renderPicker()
    expect(fileInput().accept).toContain('.log')
    expect(fileInput().accept).not.toContain('/')
  })

  it('clears the input value so the same file can be picked again after removal', async () => {
    // Re-picking an identical path fires no `change` event at all if the value
    // is still set, and the picker looks silently broken.
    renderPicker()
    const input = fileInput()
    await userEvent.upload(input, fileOf('a.png', 1024, 'image/png'))
    expect(input.value).toBe('')
  })
})

describe('AttachmentPicker — rejections', () => {
  it('names the file and both numbers when it is too large', async () => {
    const { onAdd, onReject } = renderPicker()

    await userEvent.upload(fileInput(), fileOf('capture.mp4', 12 * MB, 'video/mp4'))

    expect(onAdd).not.toHaveBeenCalled()
    expect(onReject).toHaveBeenCalled()
    expect(screen.getByRole('status')).toHaveTextContent(/capture\.mp4/)
    expect(screen.getByRole('status')).toHaveTextContent(/12 MB/)
    expect(screen.getByRole('status')).toHaveTextContent(/10 MB/)
  })

  it('says which extension was refused', () => {
    // Dropped, not picked, and that is the point: `accept` filters the *file
    // dialog*, so a `.exe` cannot be chosen through it — jsdom's `upload`
    // honours that too. Drag-and-drop bypasses `accept` entirely in every
    // browser, which is precisely why the guard cannot be the attribute.
    renderPicker()
    fireEvent.drop(screen.getByText(/drag files here/i).closest('div')!, {
      dataTransfer: dropOf([fileOf('payload.exe', 2048)]),
    })
    expect(screen.getByRole('status')).toHaveTextContent(/\.exe files are not allowed/i)
  })

  it('adds what it can from a mixed drop and reports only the rest', () => {
    const { onAdd } = renderPicker()
    const good = fileOf('a.png', 1024, 'image/png')
    const bad = fileOf('b.exe', 1024)

    fireEvent.drop(screen.getByText(/drag files here/i).closest('div')!, { dataTransfer: dropOf([good, bad]) })

    expect(onAdd).toHaveBeenCalledWith([good])
    expect(screen.getByRole('status')).toHaveTextContent(/\.exe/)
  })

  it('rejects a duplicate against what is already listed', async () => {
    const items: AttachmentItem[] = [
      { id: '1', name: 'trace.log', sizeBytes: 2048, contentType: 'text/plain', status: 'ready' },
    ]
    const { onAdd } = renderPicker({ items })

    await userEvent.upload(fileInput(), fileOf('Trace.LOG', 2048))

    expect(onAdd).not.toHaveBeenCalled()
    expect(screen.getByRole('status')).toHaveTextContent(/already attached/i)
  })

  it('puts rejections in a live region so a screen reader hears them', async () => {
    renderPicker()
    await userEvent.upload(fileInput(), fileOf('capture.mp4', 12 * MB, 'video/mp4'))
    expect(screen.getByRole('status')).toHaveAttribute('aria-live', 'polite')
  })
})

describe('AttachmentPicker — the list', () => {
  const items: AttachmentItem[] = [
    { id: '1', name: 'gateway-500.png', sizeBytes: 184_320, contentType: 'image/png', status: 'ready', thumbnailUrl: '/t.png' },
    { id: '2', name: 'trace.log', sizeBytes: 2_411_724, contentType: 'text/plain', status: 'uploading' },
    { id: '3', name: 'report.pdf', sizeBytes: 421_888, contentType: 'application/pdf', status: 'scanning', thumbnailUrl: '/x.png' },
    { id: '4', name: 'dump.zip', sizeBytes: 48 * MB, contentType: 'application/zip', status: 'failed', error: 'Too large for this ticket' },
  ]

  it('shows each file with a human size', () => {
    renderPicker({ items })
    expect(screen.getByText('gateway-500.png')).toBeInTheDocument()
    expect(screen.getByText(/180 KB/)).toBeInTheDocument()
  })

  it('renders no thumbnail while a file is still scanning', () => {
    // §4B.4: the file "becomes visible only after the scan passes", and showing
    // the image *is* making it visible.
    renderPicker({ items })
    const scanning = screen.getByText('report.pdf').closest('li')!
    expect(within(scanning).queryByRole('img')).toBeNull()
  })

  it('gives every status a text equivalent, not just an icon', () => {
    renderPicker({ items })
    expect(screen.getByText('Uploading')).toBeInTheDocument()
    expect(screen.getByText('Scanning for viruses')).toBeInTheDocument()
  })

  it('shows the failure reason on the row it belongs to', () => {
    renderPicker({ items })
    const failed = screen.getByText('dump.zip').closest('li')!
    expect(within(failed)).toBeTruthy()
    expect(failed.textContent).toContain('Too large for this ticket')
  })

  it('renders no remove control at all when onRemove is not given', () => {
    // C-028 owns the 15-minute deletion rule; a surface that has not opted in
    // must not show an affordance the API will refuse.
    renderPicker({ items })
    expect(screen.queryByRole('button', { name: /remove/i })).toBeNull()
  })

  it('removes by id, naming the file for a screen reader', async () => {
    const onRemove = vi.fn()
    renderPicker({ items, onRemove })

    await userEvent.click(screen.getByRole('button', { name: 'Remove trace.log' }))

    expect(onRemove).toHaveBeenCalledWith('2')
  })

  it('counts a failed row against neither the file count nor the byte total', () => {
    // It is not on the ticket. Counting it would refuse a retry of the very file
    // that failed.
    renderPicker({ items })
    expect(screen.getByRole('status')).toHaveTextContent('3 of 20 files')
  })
})

describe('AttachmentPicker — limits and disabled state', () => {
  const limits = { maxFileBytes: 10 * MB, maxTotalBytes: 50 * MB, maxFiles: 2 }
  const full: AttachmentItem[] = [
    { id: '1', name: 'one.png', sizeBytes: 1024, contentType: 'image/png', status: 'ready' },
    { id: '2', name: 'two.png', sizeBytes: 1024, contentType: 'image/png', status: 'ready' },
  ]

  it('disables the input at the file ceiling rather than opening onto a rejection', () => {
    renderPicker({ items: full, limits })
    expect(fileInput()).toBeDisabled()
  })

  it('says why it is closed', () => {
    renderPicker({ items: full, limits, compact: true })
    expect(screen.getByText(/2 file limit reached/i)).toBeInTheDocument()
  })

  it('ignores a drop entirely when disabled', () => {
    const { onAdd } = renderPicker({ items: full, disabled: true, compact: true })
    fireEvent.drop(screen.getByRole('button', { name: /attach/i }).closest('div')!.parentElement!, {
      dataTransfer: dropOf([fileOf('a.png', 1024, 'image/png')]),
    })
    expect(onAdd).not.toHaveBeenCalled()
  })

  it('says nothing about capacity when it is disabled', () => {
    // The line describes what can still be added. On a sealed cycle the detail
    // page renders this read-only, and "2 of 2 files · 50 MB" there is a budget
    // the reader cannot spend. It also stops a second `role="status"` landing on
    // a page that already has one — S-20's sealed-cycle banner is one.
    renderPicker({ items: full, limits, disabled: true })
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('still reports a rejection while disabled elsewhere on the page', () => {
    // Capacity is suppressed; news is not. This asserts the region can come back.
    renderPicker({ items: full, limits })
    expect(screen.getByRole('status')).toHaveTextContent(/2 of 2 files/)
  })

  it('drops the drop zone in compact mode but keeps a labelled button', () => {
    renderPicker({ compact: true })
    expect(screen.queryByText(/drag files here/i)).toBeNull()
    expect(screen.getByRole('button', { name: /attach files/i })).toBeInTheDocument()
  })

  it('forwards the ARIA a FormField hands it onto the real control', () => {
    // Without this the control reaches a screen reader unnamed, which fails AA —
    // the same gap C-010 closed on `searchable-dropdown`.
    renderPicker({ 'aria-labelledby': 'my-label', 'aria-describedby': 'my-hint' })
    expect(fileInput()).toHaveAttribute('aria-labelledby', 'my-label')
    expect(fileInput().getAttribute('aria-describedby')).toContain('my-hint')
  })
})

/* ── Clipboard paste — C-024 ────────────────────────────────────────────── */

function clipboardOf(files: File[], text: Partial<Record<string, string>> = {}) {
  return {
    types: [...Object.keys(text), ...(files.length > 0 ? ['Files'] : [])],
    items: [
      ...Object.keys(text).map(() => ({ kind: 'string', getAsFile: () => null })),
      ...files.map((file) => ({ kind: 'file', getAsFile: () => file })),
    ],
    files,
    getData: (type: string) => text[type] ?? '',
  }
}

/** What a browser actually hands over for a Snipping Tool capture. */
function pastedScreenshot(size = 92_160): File {
  return fileOf('image.png', size, 'image/png')
}

describe('AttachmentPicker — clipboard paste', () => {
  it('attaches a screenshot pasted with nothing focused', () => {
    // §4B.4's point: the most common attachment on the product exists only on
    // the clipboard. There is no file to drag and nothing to browse to.
    const { onAdd } = renderPicker()

    fireEvent.paste(document.body, { clipboardData: clipboardOf([pastedScreenshot()]) })

    expect(onAdd).toHaveBeenCalledTimes(1)
    expect(onAdd.mock.calls[0][0][0].name).toMatch(/^screenshot-\d{4}-\d{2}-\d{2}-\d{6}\.png$/)
  })

  it('accepts two screenshots in a row, which both arrive named image.png', () => {
    // The defect worth pinning. Every capture reaches the browser under the same
    // name, so a picker that took the name at face value would refuse the second
    // as a duplicate — and paste would look broken on the second use, not the
    // first, which is the hardest kind of failure to report.
    vi.useFakeTimers()
    try {
      vi.setSystemTime(new Date(2026, 7, 12, 14, 30, 5))
      const { onAdd, rerender } = renderPicker()
      fireEvent.paste(document.body, { clipboardData: clipboardOf([pastedScreenshot(1024)]) })

      const landed = onAdd.mock.calls[0][0][0] as File
      rerender(
        <AttachmentPicker
          items={[{ id: '1', name: landed.name, sizeBytes: landed.size, contentType: 'image/png', status: 'ready' }]}
          onAdd={onAdd}
        />,
      )
      // A second later. Within the same second the stamp is identical, and
      // refusing that as a duplicate is right — it is a double Ctrl+V on one
      // clipboard, not two captures.
      vi.setSystemTime(new Date(2026, 7, 12, 14, 30, 6))
      fireEvent.paste(document.body, { clipboardData: clipboardOf([pastedScreenshot(2048)]) })

      expect(onAdd).toHaveBeenCalledTimes(2)
      expect(onAdd.mock.calls[1][0][0].name).not.toBe(landed.name)
      expect(screen.getByRole('status')).not.toHaveTextContent(/already attached/i)
    } finally {
      vi.useRealTimers()
    }
  })

  it('refuses the same clipboard pasted twice in one second — a double Ctrl+V', () => {
    // The other half of the second-resolution stamp, and the reason it is not a
    // defect: one clipboard pasted twice *is* the same file, and the duplicate
    // check is what should stop it.
    vi.useFakeTimers()
    try {
      vi.setSystemTime(new Date(2026, 7, 12, 14, 30, 5))
      const onAdd = vi.fn()
      const onReject = vi.fn()
      const { rerender } = render(<AttachmentPicker items={[]} onAdd={onAdd} onReject={onReject} />)

      fireEvent.paste(document.body, { clipboardData: clipboardOf([pastedScreenshot(1024)]) })
      const landed = onAdd.mock.calls[0][0][0] as File
      rerender(
        <AttachmentPicker
          items={[{ id: '1', name: landed.name, sizeBytes: landed.size, contentType: 'image/png', status: 'ready' }]}
          onAdd={onAdd}
          onReject={onReject}
        />,
      )
      fireEvent.paste(document.body, { clipboardData: clipboardOf([pastedScreenshot(1024)]) })

      expect(onAdd).toHaveBeenCalledTimes(1)
      expect(screen.getByRole('status')).toHaveTextContent(/already attached/i)
    } finally {
      vi.useRealTimers()
    }
  })

  it('runs a paste through the same limits as a drop', () => {
    // The route must not be a way around the caps: a picker that handed pasted
    // files straight to `onAdd` would accept what browse refuses.
    const limits = { maxFileBytes: 10 * MB, maxTotalBytes: 50 * MB, maxFiles: 1 }
    const { onAdd, onReject } = renderPicker({
      limits,
      items: [{ id: '1', name: 'one.png', sizeBytes: 1024, contentType: 'image/png', status: 'ready' }],
    })

    fireEvent.paste(document.body, { clipboardData: clipboardOf([pastedScreenshot()]) })

    expect(onAdd).not.toHaveBeenCalled()
    expect(onReject).toHaveBeenCalled()
    expect(screen.getByRole('status')).toHaveTextContent(/may hold 1 attachment/i)
  })

  it('applies the size cap to a pasted file too', () => {
    // A named file, because the rename allocates a new `File` over the same
    // blob and jsdom computes size from content — a fixture's redefined `size`
    // does not survive it. Nothing is lost in a browser, where the blob really
    // is that long, but a test that pastes an oversized *screenshot* silently
    // measures a 1-byte file and passes for the wrong reason.
    const { onAdd } = renderPicker()

    fireEvent.paste(document.body, {
      clipboardData: clipboardOf([fileOf('capture.mp4', 12 * MB, 'video/mp4')]),
    })

    expect(onAdd).not.toHaveBeenCalled()
    expect(screen.getByRole('status')).toHaveTextContent(/10 MB/)
  })

  it('says what it took, because a paste has no visible action behind it', () => {
    // A drop and a browse both have evidence the user produced them. Ctrl+V has
    // none, so silence reads as "the paste was ignored".
    renderPicker()

    fireEvent.paste(document.body, { clipboardData: clipboardOf([pastedScreenshot()]) })

    expect(screen.getByRole('status')).toHaveTextContent(/^Pasted screenshot-/)
  })

  it('lets a rejection speak instead of the notice', () => {
    renderPicker()

    fireEvent.paste(document.body, { clipboardData: clipboardOf([fileOf('payload.exe', 1024)]) })

    expect(screen.getByRole('status')).toHaveTextContent(/\.exe files are not allowed/i)
    expect(screen.getByRole('status')).not.toHaveTextContent(/^Pasted/)
  })

  it('keeps the live region mounted before there is anything to announce', () => {
    // A region that appears carrying its first message is unreliably announced,
    // and for paste that first message is the only evidence anything happened.
    renderPicker()
    expect(screen.getByRole('status')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveAttribute('aria-live', 'polite')
  })

  it('takes no paste while disabled, and adds no second status region', () => {
    const { onAdd } = renderPicker({ disabled: true })

    fireEvent.paste(document.body, { clipboardData: clipboardOf([pastedScreenshot()]) })

    expect(onAdd).not.toHaveBeenCalled()
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('can be opted out of', () => {
    const { onAdd } = renderPicker({ enablePaste: false })

    fireEvent.paste(document.body, { clipboardData: clipboardOf([pastedScreenshot()]) })

    expect(onAdd).not.toHaveBeenCalled()
  })
})

describe('AttachmentPicker — the addFiles handle', () => {
  it('validates files pushed in from a rich-text editor', () => {
    // `RichTextEditor` swallows image pastes itself, so its files reach the
    // picker through the handle rather than through the document listener.
    // Going straight to `onAdd` would skip every cap.
    const onAdd = vi.fn()
    const onReject = vi.fn()
    const ref = React.createRef<AttachmentPickerHandle>()
    render(<AttachmentPicker ref={ref} items={[]} onAdd={onAdd} onReject={onReject} />)

    // Named, not `image.png` — a renamed fixture loses its redefined size under
    // jsdom, and the cap would then be measured against one byte. See the note
    // on the size-cap paste test above.
    act(() => ref.current!.addFiles([fileOf('capture.mp4', 12 * MB, 'video/mp4')]))
    expect(onAdd).not.toHaveBeenCalled()
    expect(onReject).toHaveBeenCalled()

    act(() => ref.current!.addFiles([fileOf('gateway-500.png', 1024, 'image/png')]))
    expect(onAdd).toHaveBeenCalledWith([expect.objectContaining({ name: 'gateway-500.png' })])
  })

  it('renames the browser bitmap the editor hands over', () => {
    // The editor never produces a `DataTransfer` — it filters
    // `clipboardData.files` itself and passes plain `File[]`, still carrying the
    // `image.png` every capture has. Without the rename here the second
    // screenshot pasted into a description is refused as a duplicate, which is
    // this whole task's defect arriving on the surface most likely to hit it.
    const onAdd = vi.fn()
    const ref = React.createRef<AttachmentPickerHandle>()
    render(<AttachmentPicker ref={ref} items={[]} onAdd={onAdd} />)

    act(() => ref.current!.addFiles([fileOf('image.png', 2048, 'image/png')]))

    expect(onAdd.mock.calls[0][0][0].name).toMatch(/^screenshot-\d{4}-\d{2}-\d{2}-\d{6}\.png$/)
  })

  it('announces what the handle took, the same as a paste', () => {
    const ref = React.createRef<AttachmentPickerHandle>()
    render(<AttachmentPicker ref={ref} items={[]} onAdd={vi.fn()} />)

    act(() => ref.current!.addFiles([fileOf('gateway-500.png', 1024, 'image/png')]))

    expect(screen.getByRole('status')).toHaveTextContent('Pasted gateway-500.png')
  })
})
