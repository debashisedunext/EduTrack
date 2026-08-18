import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'

import { setAccessToken } from '@/api/http'
import { ReportExportButtons } from './ReportExportButtons'

/**
 * A-064 · the export buttons.
 *
 * <p>The assertion that matters is the one about the request, not the one about
 * the markup: the access token lives in memory, so a plain `<a download>` would
 * send no `Authorization` header and the browser would save a 401 body as a
 * file called `date-wise-2026-08-17.xlsx`. That failure looks like a corrupt
 * spreadsheet rather than a permissions error, which is why it is worth a test
 * rather than a comment.
 */
describe('the report export buttons', () => {
  const createObjectURL = vi.fn(() => 'blob:mock')
  const revokeObjectURL = vi.fn()

  beforeEach(() => {
    setAccessToken('test-token')
    // jsdom implements neither, and the component is not interesting without them.
    Object.defineProperty(URL, 'createObjectURL', { value: createObjectURL, writable: true })
    Object.defineProperty(URL, 'revokeObjectURL', { value: revokeObjectURL, writable: true })
    // A real click would ask jsdom to navigate to blob:mock, which it cannot.
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  })

  afterEach(() => {
    vi.restoreAllMocks()
    setAccessToken(null)
  })

  function renderButtons(params = new URLSearchParams('from=2026-08-01&projectId=3')) {
    return render(<ReportExportButtons reportKey="date-wise" params={params} />)
  }

  it('offers the three formats §7.8 names', () => {
    renderButtons()

    expect(screen.getByRole('button', { name: /Excel/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /CSV/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /PDF/ })).toBeInTheDocument()
  })

  it('sends the bearer token, or the download is a 401 body saved as a spreadsheet', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(new Blob(['a,b']), {
        status: 200,
        headers: { 'Content-Disposition': 'attachment; filename="date-wise-2026-08-17.csv"' },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    renderButtons()
    fireEvent.click(screen.getByRole('button', { name: /CSV/ }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalled())

    const [url, init] = fetchMock.mock.calls[0]
    expect(init.headers.Authorization).toBe('Bearer test-token')
    expect(String(url)).toContain('/reports/date-wise')
    expect(String(url)).toContain('export=csv')
  })

  it('carries the current filters, so the file matches what is on screen', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(new Blob(['x']), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    renderButtons()
    fireEvent.click(screen.getByRole('button', { name: /Excel/ }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    const url = String(fetchMock.mock.calls[0][0])

    // An export of unfiltered data under a filtered heading is the same class
    // of lie the scope note exists to prevent.
    expect(url).toContain('from=2026-08-01')
    expect(url).toContain('projectId=3')
  })

  it('releases the object URL, so a dozen exports are not a dozen files held in memory', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(new Blob(['x']), { status: 200 })))

    renderButtons()
    fireEvent.click(screen.getByRole('button', { name: /PDF/ }))

    await waitFor(() => expect(revokeObjectURL).toHaveBeenCalledWith('blob:mock'))
  })

  it('shows an error instead of saving a failure response as a file', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('nope', { status: 500 })))

    renderButtons()
    fireEvent.click(screen.getByRole('button', { name: /CSV/ }))

    // The whole point: a 500 body written to disk as .csv reads as a corrupt
    // export rather than as the server error it is.
    expect(await screen.findByRole('alert')).toHaveTextContent(/export failed \(500\)/i)
    expect(createObjectURL).not.toHaveBeenCalled()
  })
})
