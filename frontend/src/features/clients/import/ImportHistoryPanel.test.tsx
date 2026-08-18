import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { HttpResponse, http } from 'msw'

import { server } from '@/mocks/server'

import { ImportHistoryPanel } from './ImportHistoryPanel'

/**
 * B-037 · the history panel, rendered.
 *
 * `importHistory.test.ts` owns every sentence. What is left here is the half a
 * pure function cannot show, and it is the half a destructive screen gets wrong:
 *
 * - the Reverse button must be **disabled from the server's flag**, not from a
 *   rule this screen re-derives;
 * - pressing it must not delete anything until the dialog is confirmed;
 * - the dialog must state the limit — updated rows are not restored — **before**
 *   the confirm, not on the result;
 * - a retained client must be named afterwards, because the dialog deliberately
 *   promised no count beforehand.
 */

const REVERSIBLE = {
  batchId: 412,
  entity: 'CLIENT',
  fileName: 'clients-august.xlsx',
  status: 'COMPLETED',
  processed: 31,
  total: 31,
  created: 24,
  updated: 4,
  rejected: 3,
  errorReportUrl: '/import-batches/412/error-report',
  startedAt: '2026-08-17T11:48:00.000Z',
  importedBy: 1,
  importedByName: 'Anita Desai',
  reversedAt: null,
  reversedRows: 0,
  retainedRows: 0,
  reversible: true,
}

const ALREADY_REVERSED = {
  ...REVERSIBLE,
  batchId: 411,
  fileName: 'clients-q1.xlsx',
  errorReportUrl: null,
  rejected: 0,
  reversedAt: '2026-08-11T07:02:00.000Z',
  reversedRows: 40,
  retainedRows: 2,
  reversible: false,
}

function listing(batches: Record<string, unknown>[]) {
  server.use(
    http.get('*/import-batches', () =>
      HttpResponse.json({ data: { entity: 'CLIENT', batches, limit: 50 } }),
    ),
  )
}

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <ImportHistoryPanel />
    </QueryClientProvider>,
  )
}

async function rowFor(fileName: string) {
  const cell = await screen.findByText(fileName)
  const row = cell.closest('tr')
  if (!row) throw new Error(`no row for ${fileName}`)
  return within(row)
}

describe('the import history', () => {
  it('lists a run with the provenance that makes it identifiable', async () => {
    listing([REVERSIBLE])
    renderPanel()

    const row = await rowFor('clients-august.xlsx')
    // The batch id, so the run can be named to anyone else.
    expect(row.getByText('#412')).toBeInTheDocument()
    expect(row.getByText('Anita Desai')).toBeInTheDocument()
  })

  it('says how many runs it is showing rather than implying it shows all of them', async () => {
    listing(Array.from({ length: 50 }, (_, i) => ({ ...REVERSIBLE, batchId: 500 + i })))
    renderPanel()

    expect(await screen.findByText(/showing the 50 most recent/i)).toBeInTheDocument()
  })

  it('tells a first-time user that nothing has been imported yet', async () => {
    listing([])
    renderPanel()

    expect(await screen.findByText(/no client import has been run yet/i)).toBeInTheDocument()
  })
})

describe('the Reverse button', () => {
  /**
   * `reversible` is the server's answer — see `ImportBatch.reversible` in the
   * contract. A screen re-deriving it holds a second copy of the service's
   * refusals, and the drift shows up as a button offering an operation the
   * server refuses.
   */
  it('is disabled for a batch the server says is not reversible, and says why', async () => {
    listing([ALREADY_REVERSED])
    renderPanel()

    const row = await rowFor('clients-q1.xlsx')
    const button = row.getByRole('button', { name: /reverse/i })

    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('title', expect.stringMatching(/already been reversed/i))
  })

  it('shows what a previous reversal did, beside the status it did not overwrite', async () => {
    listing([ALREADY_REVERSED])
    renderPanel()

    const row = await rowFor('clients-q1.xlsx')
    // Both facts, because they are two facts: how the run ended, and what
    // happened to it afterwards.
    expect(row.getByText('Completed')).toBeInTheDocument()
    expect(row.getByText(/40 deleted, 2 kept/)).toBeInTheDocument()
  })

  /** Nothing is destroyed by a click. The dialog is the gate. */
  it('does not reverse anything until the dialog is confirmed', async () => {
    const reversed = vi.fn()
    listing([REVERSIBLE])
    server.use(
      http.post('*/import-batches/:batchId/reverse', () => {
        reversed()
        return HttpResponse.json({ data: { batch: REVERSIBLE, deleted: [], retained: [], updatedRowsNotReverted: 0 } })
      }),
    )
    renderPanel()

    const row = await rowFor('clients-august.xlsx')
    await userEvent.click(row.getByRole('button', { name: /reverse/i }))

    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    expect(reversed).not.toHaveBeenCalled()
  })

  /**
   * **The warning that has to come before the button, not after it.** Somebody
   * pressing Reverse expects all 31 rows to go back to how they were; four of
   * them will not, and there is no before image to restore them from.
   */
  it('warns that updated clients are not restored, in the dialog', async () => {
    listing([REVERSIBLE])
    renderPanel()

    const row = await rowFor('clients-august.xlsx')
    await userEvent.click(row.getByRole('button', { name: /reverse/i }))

    const dialog = within(await screen.findByRole('dialog'))
    expect(dialog.getByText(/deletes the 24 clients this import created/i)).toBeInTheDocument()
    expect(dialog.getByText(/4 clients it updated are not restored/i)).toBeInTheDocument()
  })

  it('closes the dialog without reversing when it is cancelled', async () => {
    const reversed = vi.fn()
    listing([REVERSIBLE])
    server.use(
      http.post('*/import-batches/:batchId/reverse', () => {
        reversed()
        return HttpResponse.json({ data: { batch: REVERSIBLE, deleted: [], retained: [], updatedRowsNotReverted: 0 } })
      }),
    )
    renderPanel()

    const row = await rowFor('clients-august.xlsx')
    await userEvent.click(row.getByRole('button', { name: /reverse/i }))
    await userEvent.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: /cancel/i }),
    )

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(reversed).not.toHaveBeenCalled()
  })
})

describe('what a reversal reports back', () => {
  /**
   * The dialog deliberately promises no count of survivors beforehand — it would
   * be a query against live tickets that can change before the button is pressed.
   * So this is the first and only place the user learns which clients survived,
   * and a count without names would leave them diffing a spreadsheet against the
   * client master.
   */
  it('names every retained client, with the reason', async () => {
    listing([REVERSIBLE])
    server.use(
      http.post('*/import-batches/:batchId/reverse', () =>
        HttpResponse.json({
          data: {
            batch: { ...REVERSIBLE, reversedAt: '2026-08-18T09:02:00Z', reversedRows: 23, retainedRows: 1, reversible: false },
            deleted: ['ACME'],
            retained: [
              {
                naturalKey: 'ZENITH',
                reason: 'Kept — 3 tickets have been raised against this client since the import.',
              },
            ],
            updatedRowsNotReverted: 4,
          },
        }),
      ),
    )
    renderPanel()

    const row = await rowFor('clients-august.xlsx')
    await userEvent.click(row.getByRole('button', { name: /reverse/i }))
    await userEvent.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: /reverse import/i }),
    )

    const result = await screen.findByRole('status')
    expect(within(result).getByText(/deleted the 23 clients/i)).toBeInTheDocument()
    expect(within(result).getByText('ZENITH')).toBeInTheDocument()
    expect(within(result).getByText(/3 tickets have been raised/i)).toBeInTheDocument()
    // And the rows the reversal was never about.
    expect(within(result).getByText(/4 clients that this import updated/i)).toBeInTheDocument()
  })

  /**
   * Three refusal types, three remedies. A batch reversed in another tab must not
   * offer a retry that will refuse for ever.
   */
  it('offers a refresh, not a retry, when the batch was already reversed elsewhere', async () => {
    listing([REVERSIBLE])
    server.use(
      http.post('*/import-batches/:batchId/reverse', () =>
        HttpResponse.json(
          {
            type: 'https://edutrack/errors/import-batch-already-reversed',
            title: 'This import has already been reversed',
            status: 422,
            detail: 'Import #412 has already been reversed. A run can only be reversed once.',
          },
          { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )
    renderPanel()

    const row = await rowFor('clients-august.xlsx')
    await userEvent.click(row.getByRole('button', { name: /reverse/i }))
    await userEvent.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: /reverse import/i }),
    )

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/already been reversed/i)
    expect(alert).toHaveTextContent(/refresh this list/i)
    expect(alert).not.toHaveTextContent(/try again/i)
  })
})
