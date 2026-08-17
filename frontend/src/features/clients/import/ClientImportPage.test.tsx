import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { HttpResponse, http } from 'msw'

import { server } from '@/mocks/server'

import { ClientImportPage } from './ClientImportPage'
import { filenameFrom } from './importQueries'

/**
 * B-031 · S-34 step 1, against the mock server.
 *
 * The assertions worth making here are the ones a screenshot would not show:
 * that the file is saved under the **server's** name rather than one this
 * screen invented, that the object URL is released afterwards, and that a
 * refusal reaches the user as words instead of a button that quietly does
 * nothing.
 */

/** jsdom implements neither, and `saveBlob` uses both. */
const createObjectURL = vi.fn(() => 'blob:mock-url')
const revokeObjectURL = vi.fn()

/**
 * jsdom has no navigation, so a real anchor click logs "Not implemented" and
 * tells us nothing. Spying on it is also how the download name is asserted —
 * the attribute the browser would have used is the observable behaviour.
 */
let clicked: { download: string; href: string } | null = null

beforeEach(() => {
  clicked = null
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL, revokeObjectURL }))
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
    this: HTMLAnchorElement,
  ) {
    clicked = { download: this.download, href: this.href }
  })
})

afterEach(() => {
  vi.restoreAllMocks()
  createObjectURL.mockClear()
  revokeObjectURL.mockClear()
})

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ClientImportPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('the client import wizard, step 1', () => {
  /**
   * All five steps are on screen while one of them works. Hiding the other four
   * would make the screen look finished and leave the user to discover at the
   * end of step 1 that there is no step 2 — and it would drop §4B.3's actual
   * promise, which is that nothing is written until a preview has been seen.
   */
  it('names all five steps and marks the first as current', () => {
    renderPage()

    const steps = screen.getByRole('list', { name: 'Import steps' })
    const rail = within(steps)

    // Scoped to the rail: "Download template" is also the button's label, and
    // an unscoped query would match whichever came first and pass either way.
    expect(rail.getByText('Download template')).toBeInTheDocument()
    expect(rail.getByText('Upload')).toBeInTheDocument()
    expect(rail.getByText('Map columns')).toBeInTheDocument()
    expect(rail.getByText('Validate')).toBeInTheDocument()
    expect(rail.getByText('Commit')).toBeInTheDocument()

    const current = steps.querySelectorAll('[aria-current="step"]')
    expect(current).toHaveLength(1)
    expect(current[0]).toHaveTextContent('Download template')
  })

  /**
   * The name comes off `Content-Disposition`, which is why this hook does not
   * use the generated client — `http()` drops the response, so the generated
   * one can only rebuild the name from the schema key and hope the two agree.
   */
  it('saves the workbook under the name the server gave it', async () => {
    renderPage()

    fireEvent.click(screen.getByRole('button', { name: /download template/i }))

    await waitFor(() => expect(clicked).not.toBeNull())
    expect(clicked!.download).toBe('import-template.xlsx')
    expect(createObjectURL).toHaveBeenCalledTimes(1)
  })

  /**
   * Released after the click. Without this the workbook stays resident for the
   * life of the tab, and an admin who downloads the template five times while
   * filling it in is holding five copies of it.
   */
  it('releases the object URL once the download has started', async () => {
    renderPage()

    fireEvent.click(screen.getByRole('button', { name: /download template/i }))

    await waitFor(() => expect(revokeObjectURL).toHaveBeenCalledWith('blob:mock-url'))
  })

  /**
   * A refusal has to become words. The button is the only thing on this screen
   * that talks to the server, so a failure that left it looking idle would be
   * indistinguishable from a browser that blocked the download.
   */
  it('reports a refusal instead of failing silently', async () => {
    server.use(
      http.get('*/imports/:schema/template', () =>
        HttpResponse.json(
          { type: 'https://edutrack/errors/forbidden', title: 'Forbidden', status: 403 },
          { status: 403, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )

    renderPage()
    fireEvent.click(screen.getByRole('button', { name: /download template/i }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('could not be downloaded')
    expect(alert).toHaveTextContent('403')
    expect(clicked).toBeNull()
  })

  /**
   * Disabled and labelled rather than absent: somebody about to spend a morning
   * on four hundred rows should learn now that the upload step is not there yet.
   */
  it('offers no upload yet, and says so', () => {
    renderPage()

    expect(screen.getByRole('button', { name: /continue to upload/i })).toBeDisabled()
    expect(screen.getByText(/uploading is not available yet/i)).toBeInTheDocument()
  })
})

describe('the filename', () => {
  it('is read out of Content-Disposition', () => {
    expect(filenameFrom('attachment; filename="clients-import-template.xlsx"', 'clients'))
      .toBe('clients-import-template.xlsx')
  })

  it('reads an unquoted name too, because not every proxy quotes it', () => {
    expect(filenameFrom('attachment; filename=users-import-template.xlsx', 'users'))
      .toBe('users-import-template.xlsx')
  })

  /**
   * The fallback is the shape the server builds, not something generic: a proxy
   * that strips the header should cost a correct name, not leave the user with
   * `download.xlsx` and no idea which schema it belongs to.
   */
  it('falls back to the schema name when the header is missing', () => {
    expect(filenameFrom(null, 'clients')).toBe('clients-import-template.xlsx')
  })
})
