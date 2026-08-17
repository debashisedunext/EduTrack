import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { HttpResponse, http } from 'msw'

import { server } from '@/mocks/server'

import { ClientImportPage } from './ClientImportPage'
import { filenameFrom, formatBytes, rejectionReason } from './importQueries'

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

function file(name: string, content: string): File {
  return new File([content], name, { type: 'application/octet-stream' })
}

/**
 * Puts a file into the drop zone through the real `<input type="file">`.
 *
 * jsdom has no drag-and-drop, and the input is the control the zone is built
 * around anyway — it is what the keyboard and the picker both reach, so driving
 * it is driving the path most users take rather than a test-only shortcut.
 */
async function drop(chosen: File) {
  const input = document.querySelector<HTMLInputElement>('input[type="file"]')!
  fireEvent.change(input, { target: { files: [chosen] } })
  await waitFor(() => expect(screen.queryByText(/reading the file/i)).not.toBeInTheDocument())
}

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

})

/**
 * B-032 · S-34 step 2.
 *
 * The interesting cases are the refusals and the sheet selector. A happy-path
 * upload is one assertion; the value is in what happens when the user drops the
 * wrong thing, which is most of what a bulk-import screen actually handles.
 */
describe('the client import wizard, step 2', () => {
  it('uploads a chosen file and describes what was found in it', async () => {
    renderPage()

    await drop(file('clients.xlsx', 'binary-ish'))

    expect(await screen.findByText('clients.xlsx')).toBeInTheDocument()
    expect(screen.getByText(/128 rows/)).toBeInTheDocument()
    expect(screen.getByText('Client Code')).toBeInTheDocument()
  })

  /**
   * Blueprint §4B.3's promise, and the reason the wizard is five steps: a user
   * who has just handed over four hundred rows must be told, on this screen,
   * that nothing has changed yet.
   *
   * B-033 turned the Continue button on — it was disabled with a "not available
   * yet" note while step 3 did not exist — so what is asserted now is that it is
   * *enabled* and the promise is still on screen beside it.
   */
  it('says plainly that uploading has written nothing', async () => {
    renderPage()

    await drop(file('clients.xlsx', 'binary-ish'))

    expect(await screen.findByText(/no client has changed/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /continue to mapping/i })).toBeEnabled()
  })

  /**
   * §4B.3: "first sheet by default, sheet selector if the workbook has several".
   * Both halves — the default, and that choosing another actually re-reads
   * rather than only re-styling a button.
   */
  it('offers the workbook’s sheets and re-reads the file when one is chosen', async () => {
    renderPage()
    await drop(file('clients.xlsx', 'binary-ish'))

    const archive = await screen.findByRole('button', { name: 'Archive' })
    expect(screen.getByRole('button', { name: 'Clients' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )

    fireEvent.click(archive)

    // 12 is the Archive sheet's row count in the mock — proof the server was
    // asked again with ?sheet=, not that a class was toggled.
    expect(await screen.findByText(/12 rows/)).toBeInTheDocument()
  })

  /** A CSV has one sheet, so a selector would be a control with nothing to do. */
  it('shows no sheet selector for a single-sheet file', async () => {
    server.use(
      http.post('*/imports/:schema/upload', () =>
        HttpResponse.json({
          data: {
            uploadId: 'u-1', fileName: 'clients.csv', sheets: ['clients'], sheet: 'clients',
            rowCount: 4, headers: ['Client Code'], suggestedMapping: { clientCode: 'Client Code' },
          },
        }),
      ),
    )
    renderPage()

    await drop(file('clients.csv', 'Client Code\nACME\n'))

    expect(await screen.findByText('clients.csv')).toBeInTheDocument()
    expect(screen.queryByText(/which sheet holds the clients/i)).not.toBeInTheDocument()
  })

  /**
   * Refused here rather than after the upload. The point is not security — the
   * server refuses it too — it is that a user on a slow connection learns their
   * 40 MB export is too big without waiting for 40 MB of it to go.
   */
  it('refuses an oversized file before sending it', async () => {
    renderPage()

    await drop(file('huge.xlsx', 'x'.repeat(6 * 1024 * 1024)))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/the limit is 5 MB/i)
    expect(screen.queryByText(/columns found/i)).not.toBeInTheDocument()
  })

  /**
   * The stated deviation from §4B.3, which lists `.xls`. What matters on screen
   * is that the refusal names the fix — the user is holding a file their own
   * spreadsheet opens without complaint.
   */
  it('refuses a legacy .xls and says how to convert it', async () => {
    renderPage()

    await drop(file('clients.xls', 'old binary'))

    expect(await screen.findByRole('alert')).toHaveTextContent(/Save As/)
  })

  /**
   * A refusal the server made reads the same way to the user as one this screen
   * made — to them it is the same event, and the detail is the half that names
   * the actual number.
   */
  it('shows the server’s reason when the file is read and rejected', async () => {
    server.use(
      http.post('*/imports/:schema/upload', () =>
        HttpResponse.json(
          {
            type: 'https://edutrack/errors/import-too-large',
            title: 'Import file is too large',
            status: 413,
            detail: 'The sheet has more than 5,000 rows, which is the limit.',
          },
          { status: 413, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )
    renderPage()

    await drop(file('clients.xlsx', 'binary-ish'))

    expect(await screen.findByRole('alert')).toHaveTextContent('more than 5,000 rows')
  })

  it('lets the user start again with a different file', async () => {
    renderPage()
    await drop(file('clients.xlsx', 'binary-ish'))
    await screen.findByText('clients.xlsx')

    fireEvent.click(screen.getByRole('button', { name: /choose a different file/i }))

    expect(screen.queryByText('clients.xlsx')).not.toBeInTheDocument()
    expect(screen.getByText(/drop your spreadsheet here/i)).toBeInTheDocument()
  })
})

/**
 * B-033 · S-34 step 3.
 *
 * What is worth asserting here is the rule §4B.3 states and the two failures that
 * rule is easy to satisfy without: that Next is blocked by an unmapped *required*
 * column specifically, and that a preset applied to a file whose columns have
 * been renamed does not enable it anyway.
 *
 * The mapping table itself — twenty rows of `<select>` — is driven through the
 * real controls rather than around them, because `columnMapping.test.ts` already
 * covers the rules as functions and what is left to prove here is the wiring.
 */
describe('the client import wizard, step 3', () => {
  /** Gets a staged file and opens the mapping step. */
  async function toMapping() {
    renderPage()
    await drop(file('clients.xlsx', 'binary-ish'))
    fireEvent.click(await screen.findByRole('button', { name: /continue to mapping/i }))
    return await screen.findByRole('combobox', { name: /^client code/i })
  }

  /**
   * The auto-match is the starting point, not a suggestion the user has to accept
   * — a file built from the template arrives fully mapped and they only confirm.
   * The mock's `suggestedMapping` covers both required fields, so Next is open.
   */
  it('opens on the server’s auto-match, with a row per column the import accepts', async () => {
    const clientCode = await toMapping()

    expect(clientCode).toHaveValue('Client Code')
    expect(screen.getByRole('combobox', { name: /^name/i })).toHaveValue('Name')
    // Optional, unmatched in the mock — left out rather than guessed at.
    expect(screen.getByRole('combobox', { name: /^city/i })).toHaveValue('')
    expect(screen.getByRole('button', { name: /continue to validation/i })).toBeInTheDocument()
  })

  /**
   * The field list comes off `GET /imports/{schema}/fields`, which is the whole
   * reason that route exists — the headings shown are the template's own, so the
   * user is matching the words they saw in the file they downloaded.
   */
  it('names the natural key as the column rows are matched on', async () => {
    await toMapping()

    expect(screen.getByText(/rows are matched on this column/i)).toBeInTheDocument()
  })

  /**
   * §4B.3: "Unmapped required columns block the Next button." Asserted by
   * *clearing* one rather than by starting from empty, because that is how it
   * actually happens — the auto-match filled it in and the user changed it.
   */
  it('blocks Next when a required column is unmapped, and names it', async () => {
    const clientCode = await toMapping()

    fireEvent.change(clientCode, { target: { value: '' } })

    expect(screen.getByRole('button', { name: /continue to validation/i })).toBeDisabled()
    expect(await screen.findByText(/one required column is not mapped yet/i))
      .toHaveTextContent('Client Code')
  })

  /** An optional column left out is the ordinary case and must not block anything. */
  it('does not block Next for an unmapped optional column', async () => {
    await toMapping()

    fireEvent.change(screen.getByRole('combobox', { name: /^support plan/i }), {
      target: { value: '' },
    })

    expect(screen.queryByText(/required columns? .* not mapped/i)).not.toBeInTheDocument()
  })

  /**
   * The columns the import will not read. Not an error — but the mock's file
   * carries `Account Manager`, which `ClientImportSchema` deliberately has no
   * column for, and somebody who filled it in is entitled to know before the
   * commit rather than after it.
   */
  it('says which of the file’s columns will not be imported', async () => {
    await toMapping()

    expect(await screen.findByText(/will not be imported/i))
      .toHaveTextContent('Account Manager')
  })

  it('goes back to upload without unwinding the upload', async () => {
    await toMapping()

    fireEvent.click(screen.getByRole('button', { name: /back to upload/i }))

    expect(screen.queryByRole('combobox', { name: /^client code/i })).not.toBeInTheDocument()
    // Still staged: the file is not re-sent, and the row count is still on screen.
    expect(screen.getByText(/128 rows/)).toBeInTheDocument()
  })

  /**
   * Step 4 is B-034. Disabled and labelled rather than absent, for the reason
   * B-032 left step 2's Continue in the same state: a button that silently does
   * nothing is worse than one that says what is missing.
   */
  it('leaves validation disabled and says why, rather than doing nothing', async () => {
    await toMapping()

    const next = screen.getByRole('button', { name: /continue to validation/i })
    expect(next).toBeDisabled()
    expect(next).toHaveAttribute('title', expect.stringContaining('next step'))
    expect(screen.getByText(/the mapping is complete/i)).toBeInTheDocument()
  })
})

describe('step 3’s mapping presets', () => {
  async function toMapping() {
    renderPage()
    await drop(file('clients.xlsx', 'binary-ish'))
    fireEvent.click(await screen.findByRole('button', { name: /continue to mapping/i }))
    await screen.findByRole('combobox', { name: /^client code/i })
  }

  /**
   * §4B.3's preset, saved and then reused — the round trip, because that is the
   * whole feature. The mock stores it in `db.mappingPresets`, so a preset that
   * did not really persist would not come back in the list.
   */
  it('saves the current mapping and offers it back', async () => {
    await toMapping()

    fireEvent.change(screen.getByLabelText(/save this mapping as/i), {
      target: { value: 'CRM export' },
    })
    fireEvent.click(screen.getByRole('button', { name: /^save preset$/i }))

    const saved = await screen.findByRole('list', { name: /saved mappings/i })
    // Exact, not a substring: the delete button beside it is labelled "Delete the
    // CRM export preset" and would match too.
    expect(within(saved).getByRole('button', { name: 'CRM export' })).toBeInTheDocument()
  })

  /**
   * Saving under a name that exists replaces it, and the button says so *before*
   * it is pressed. The server upserts on `(schema, name)` — that is what makes
   * correcting a preset possible — and the destructive reading of Save should be
   * the one on screen when it is the one that applies.
   */
  it('offers Replace rather than Save once the name is taken', async () => {
    await toMapping()

    fireEvent.change(screen.getByLabelText(/save this mapping as/i), {
      target: { value: 'CRM export' },
    })
    fireEvent.click(screen.getByRole('button', { name: /^save preset$/i }))
    await screen.findByRole('list', { name: /saved mappings/i })

    expect(await screen.findByRole('button', { name: /replace .*CRM export/i }))
      .toBeInTheDocument()
  })

  /**
   * The case `applyPreset` exists for, end to end.
   *
   * The preset is saved against this file and then applied to one whose `Name`
   * column has been renamed. The entry is dropped, the screen says which column
   * it could not find, and — the half that matters — **Next stays blocked**,
   * because `name` is required and is now genuinely unmapped. Carrying the stale
   * entry over would have left the mapping claiming a heading that is in no
   * dropdown on screen.
   */
  it('drops a preset entry whose column this file does not have, and keeps Next blocked', async () => {
    await toMapping()

    fireEvent.change(screen.getByLabelText(/save this mapping as/i), {
      target: { value: 'CRM export' },
    })
    fireEvent.click(screen.getByRole('button', { name: /^save preset$/i }))
    await screen.findByRole('list', { name: /saved mappings/i })

    // A different file: `Name` is now `Account Name`, so the preset's entry for
    // it cannot be placed. Re-uploading is how the user gets here.
    server.use(
      http.post('*/imports/:schema/upload', () =>
        HttpResponse.json({
          data: {
            uploadId: 'u-2', fileName: 'renamed.xlsx', sheets: ['Clients'], sheet: 'Clients',
            rowCount: 9, headers: ['Client Code', 'Account Name'], suggestedMapping: {},
          },
        }),
      ),
    )
    fireEvent.click(screen.getByRole('button', { name: /back to upload/i }))
    fireEvent.click(screen.getByRole('button', { name: /choose a different file/i }))
    await drop(file('renamed.xlsx', 'binary-ish'))
    fireEvent.click(await screen.findByRole('button', { name: /continue to mapping/i }))
    await screen.findByRole('combobox', { name: /^client code/i })

    fireEvent.click(screen.getByRole('button', { name: 'CRM export' }))

    expect(await screen.findByText(/this file has no column called/i))
      .toHaveTextContent('Name')
    expect(screen.getByRole('combobox', { name: /^client code/i })).toHaveValue('Client Code')
    expect(screen.getByRole('button', { name: /continue to validation/i })).toBeDisabled()
  })

  /**
   * `import-unknown-field`, branched on by `type` rather than by wording —
   * CONVENTIONS.md §3. It means the mapping names a field the import no longer
   * has, which is not something the user can fix by retyping the name, so the
   * message has to say something else.
   */
  it('explains a refusal that names a field the import no longer has', async () => {
    server.use(
      http.post('*/imports/:schema/mapping-presets', () =>
        HttpResponse.json(
          {
            type: 'https://edutrack/errors/import-unknown-field',
            title: 'Unknown import field',
            status: 422,
            detail: "The 'clients' import declares no field called faxNumber.",
          },
          { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )
    await toMapping()

    fireEvent.change(screen.getByLabelText(/save this mapping as/i), {
      target: { value: 'Old preset' },
    })
    fireEvent.click(screen.getByRole('button', { name: /^save preset$/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent('faxNumber')
  })

  it('deletes a preset it no longer wants', async () => {
    await toMapping()

    fireEvent.change(screen.getByLabelText(/save this mapping as/i), {
      target: { value: 'CRM export' },
    })
    fireEvent.click(screen.getByRole('button', { name: /^save preset$/i }))
    await screen.findByRole('list', { name: /saved mappings/i })

    fireEvent.click(screen.getByRole('button', { name: /delete the CRM export preset/i }))

    await waitFor(() =>
      expect(screen.queryByRole('list', { name: /saved mappings/i })).not.toBeInTheDocument(),
    )
  })
})

/**
 * The pre-flight check, on its own. Pure, so both entry points into the drop
 * zone — the drag and the picker — cannot drift into two different opinions of
 * what a spreadsheet is.
 */
describe('the pre-upload check', () => {
  it('accepts the two formats the import reads', () => {
    expect(rejectionReason(file('clients.xlsx', 'x'))).toBeNull()
    expect(rejectionReason(file('clients.CSV', 'x'))).toBeNull()
  })

  it('names the conversion for a legacy .xls rather than only refusing it', () => {
    expect(rejectionReason(file('clients.xls', 'x'))).toContain('Save As')
  })

  it('refuses anything else by name', () => {
    expect(rejectionReason(file('notes.pdf', 'x'))).toContain('notes.pdf')
  })

  /** An empty file parses to nothing and would be staged as a working upload. */
  it('refuses an empty file', () => {
    expect(rejectionReason(file('clients.csv', ''))).toContain('is empty')
  })

  it('reports sizes in the unit the user thinks in', () => {
    expect(formatBytes(500)).toBe('1 KB')
    expect(formatBytes(2 * 1024 * 1024)).toBe('2.0 MB')
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
