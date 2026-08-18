import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import type { ImportPreviewResponseData, ImportRowVerdict } from '@/api/generated/model'

import { CLIENT_IMPORT } from './importWizard'
import { ValidationStep } from './ValidationStep'
import { PREVIEW_PAGE_SIZE } from './validationPreview'

/**
 * B-034 · S-34 step 4, rendered.
 *
 * `validationPreview.test.ts` owns the arithmetic. What is left here is what the
 * user actually reads, and the three things this table can get wrong in ways
 * that matter:
 *
 * - a message that says the wrong kind of thing for its verdict — above all an
 *   update whose changed fields are missing, which must not read as "nothing
 *   changes";
 * - a natural key that renders as an empty cell rather than as `(blank)`, at the
 *   moment the user is scanning for why a row was rejected;
 * - the promise. "Nothing has been written" is unverifiable from the screen, so
 *   it has to be on it.
 */

function preview(overrides: Partial<ImportPreviewResponseData> = {}): ImportPreviewResponseData {
  return {
    willCreate: 1,
    willUpdate: 1,
    duplicates: 1,
    rejected: 1,
    rows: [
      verdictRow(2, 'WILL_CREATE', null, { clientCode: 'NEWCO', name: 'Newco Ltd' }),
      verdictRow(3, 'WILL_UPDATE', 'Name, Phone', { clientCode: 'NORTHWIND' }),
      verdictRow(4, 'DUPLICATE_IN_FILE', 'Row 2 wins', { clientCode: 'NEWCO' }),
      verdictRow(5, 'REJECTED', 'Client Code required', { name: 'No Code Here' }),
    ],
    ...overrides,
  }
}

function verdictRow(
  rowNumber: number,
  verdict: ImportRowVerdict['verdict'],
  reason: string | null,
  values: Record<string, string>,
): ImportRowVerdict {
  return { rowNumber, verdict, reason, values }
}

function renderStep(
  data: ImportPreviewResponseData = preview(),
  props: { onBack?: () => void; onCommit?: () => void; committing?: boolean } = {},
) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <ValidationStep
        schema="clients"
        nouns={CLIENT_IMPORT.nouns}
        preview={data}
        fileName="clients.xlsx"
        onBack={props.onBack ?? (() => {})}
        onCommit={props.onCommit}
        committing={props.committing}
      />
    </QueryClientProvider>,
  )
}

/** The row for a given source row number, whatever order the table is in. */
function tableRow(rowNumber: number) {
  return screen.getByRole('rowheader', { name: String(rowNumber) }).closest('tr')!
}

describe('the dry-run preview', () => {
  it('states that nothing has been written, because the user cannot check', async () => {
    renderStep()

    expect(await screen.findAllByText(/nothing has been written/i)).not.toHaveLength(0)
  })

  it('names the file the preview is of', () => {
    renderStep()

    expect(screen.getByText('clients.xlsx')).toBeInTheDocument()
  })

  /** §4B.3's summary line, as four tiles. */
  it('shows a count for every outcome, zeroes included', () => {
    renderStep(preview({ willCreate: 412, willUpdate: 38, rejected: 0, duplicates: 2 }))

    const summary = screen.getByRole('list', { name: /preview summary/i })
    expect(within(summary).getByText('412')).toBeInTheDocument()
    expect(within(summary).getByText('38')).toBeInTheDocument()
    expect(within(summary).getByText('0')).toBeInTheDocument()
  })

  /**
   * The number step 5's offer is about, and the one no tile shows. Reaching for
   * `willCreate` is the natural mistake and it omits every update.
   */
  it('says how many rows a commit would actually write', () => {
    renderStep(preview({ willCreate: 412, willUpdate: 38, rejected: 6, duplicates: 2 }))

    expect(screen.getByRole('status')).toHaveTextContent(/450 rows/)
    expect(screen.getByRole('button', { name: /import 450 rows/i })).toBeInTheDocument()
  })

  it('says plainly when nothing can be imported', () => {
    renderStep(preview({ willCreate: 0, willUpdate: 0, rejected: 6, duplicates: 2, rows: [] }))

    expect(screen.getByRole('status')).toHaveTextContent(/no row in this file can be imported/i)
    expect(screen.getByRole('button', { name: /import 0 rows/i })).toBeDisabled()
  })
})

describe('the table', () => {
  /** Blueprint §4B.3's four columns, and the key one named by the schema. */
  it('has a column for the field rows are matched on, named as the template names it', async () => {
    renderStep()

    // From `GET /imports/clients/fields` via MSW — `clientCode` → "Client Code".
    // The generous timeout is because this one assertion waits on a round trip
    // through the mock server; the rest of the file renders synchronously, and
    // the default 1s is close enough to the observed time to flake under load.
    expect(await screen.findByRole('columnheader', { name: 'Client Code' }, { timeout: 5000 }))
      .toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Row' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Status' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Message' })).toBeInTheDocument()
  })

  it('quotes the row numbers of the sheet, so the user can go and look', () => {
    renderStep(preview({ rows: [verdictRow(44, 'WILL_CREATE', null, { clientCode: 'A' })] }))

    expect(screen.getByRole('rowheader', { name: '44' })).toBeInTheDocument()
  })

  /**
   * The change list is the reason "38 will update" is reviewable at all. A file
   * correcting six phone numbers and a file overwriting every address produce
   * the same verdict and different messages.
   */
  it('names the fields an update would change', async () => {
    renderStep()

    expect(within(tableRow(3)).getByText('Name, Phone')).toBeInTheDocument()
  })

  /**
   * `null` on an update means the server could not say, which is **not** "nothing
   * changes" — rendering it as an em dash would turn a missing answer into a
   * reassuring one.
   */
  it('does not let an update with no change list read as no change', async () => {
    renderStep(preview({
      rows: [verdictRow(3, 'WILL_UPDATE', null, { clientCode: 'ACME' })],
    }))

    const row = tableRow(3)
    expect(within(row).getByText(/changed fields not available/i)).toBeInTheDocument()
    expect(within(row).queryByText('—')).not.toBeInTheDocument()
  })

  /** A clean create has nothing to say, and the blueprint's mock-up uses an em dash. */
  it('shows an em dash for a create', () => {
    renderStep()

    expect(within(tableRow(2)).getByText('—')).toBeInTheDocument()
  })

  /** Blueprint §4B.3's row 5. An empty cell would read as a rendering fault. */
  it('shows (blank) where the row has no code, which is why it was rejected', () => {
    renderStep()

    const row = tableRow(5)
    expect(within(row).getByText('(blank)')).toBeInTheDocument()
    expect(within(row).getByText('Client Code required')).toBeInTheDocument()
  })

  /**
   * A duplicate is not a rejection, and the difference has to survive into the
   * words. Reading only the colour is not available to everybody.
   */
  it('labels a duplicate as a duplicate, naming the row that won', () => {
    renderStep()

    const row = tableRow(4)
    expect(within(row).getByText(/duplicate in file/i)).toBeInTheDocument()
    expect(within(row).getByText('Row 2 wins')).toBeInTheDocument()
  })
})

describe('filtering and paging', () => {
  it('narrows the table to one outcome', () => {
    renderStep()

    fireEvent.click(screen.getByRole('button', { name: /^rejected 1$/i }))

    expect(screen.getByRole('rowheader', { name: '5' })).toBeInTheDocument()
    expect(screen.queryByRole('rowheader', { name: '2' })).not.toBeInTheDocument()
  })

  /**
   * Disabled rather than hidden: a clean file should still say "0 rejected" out
   * loud, and controls that come and go make two runs hard to compare.
   */
  it('disables a filter with nothing behind it instead of hiding it', () => {
    renderStep(preview({ duplicates: 0, rows: preview().rows.slice(0, 2) }))

    expect(screen.getByRole('button', { name: /duplicate in file 0/i })).toBeDisabled()
  })

  it('pages rather than rendering five thousand rows', () => {
    const rows = Array.from({ length: 120 }, (_, index) =>
      verdictRow(index + 2, 'WILL_CREATE', null, { clientCode: `C${index}` }))
    renderStep(preview({ willCreate: 120, willUpdate: 0, rejected: 0, duplicates: 0, rows }))

    expect(screen.getAllByRole('rowheader')).toHaveLength(PREVIEW_PAGE_SIZE)
    // And says so, rather than looking like the file was truncated.
    expect(screen.getByText(/showing 50 of 120 rows/i)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /show 50 more/i }))

    expect(screen.getAllByRole('rowheader')).toHaveLength(PREVIEW_PAGE_SIZE * 2)
  })

  /**
   * Changing the filter goes back to the first page. Keeping the offset means
   * clicking Rejected on a 400-row file and landing on an empty table.
   */
  it('returns to the first page when the filter changes', () => {
    const rows = [
      ...Array.from({ length: 60 }, (_, index) =>
        verdictRow(index + 2, 'WILL_CREATE', null, { clientCode: `C${index}` })),
      verdictRow(100, 'REJECTED', 'Client Code required', {}),
    ]
    renderStep(preview({ willCreate: 60, willUpdate: 0, rejected: 1, duplicates: 0, rows }))

    fireEvent.click(screen.getByRole('button', { name: /show 11 more/i }))
    fireEvent.click(screen.getByRole('button', { name: /^rejected 1$/i }))
    fireEvent.click(screen.getByRole('button', { name: /^all 61$/i }))

    expect(screen.getAllByRole('rowheader')).toHaveLength(PREVIEW_PAGE_SIZE)
  })
})

describe('the way out', () => {
  it('goes back to mapping', () => {
    const onBack = vi.fn()
    renderStep(preview(), { onBack })

    fireEvent.click(screen.getByRole('button', { name: /back to mapping/i }))

    expect(onBack).toHaveBeenCalled()
  })

  /**
   * A caller with no commit still gets a button that says what is missing rather
   * than one that silently does nothing. B-035 gives `ClientImportPage` a real
   * `onCommit`, but the prop stays optional and this is the shape it leaves.
   */
  it('leaves committing disabled and says why when there is no commit to run', () => {
    renderStep()

    const commit = screen.getByRole('button', { name: /import 2 rows/i })
    expect(commit).toBeDisabled()
    expect(commit).toHaveAttribute('title', expect.stringContaining('next step'))
  })

  it('commits when it can', () => {
    const onCommit = vi.fn()
    renderStep(preview(), { onCommit })

    fireEvent.click(screen.getByRole('button', { name: /import 2 rows/i }))

    expect(onCommit).toHaveBeenCalled()
  })

  /**
   * B-035 · the one irreversible button on this screen, guarded against a second
   * press.
   *
   * The server refuses the duplicate on its own — the commit consumes its
   * staging entry — but the user would then see a refusal for something they
   * were entirely right to expect to work. Both buttons go: Back would leave a
   * commit in flight against a mapping the screen has stopped showing.
   */
  it('disables both buttons while a commit is in flight', () => {
    renderStep(preview(), { onCommit: vi.fn(), committing: true })

    expect(screen.getByRole('button', { name: /starting/i })).toBeDisabled()
    expect(screen.getByRole('button', { name: /back to mapping/i })).toBeDisabled()
  })

  it('says the import runs in the background, because the user may close the tab', () => {
    renderStep(preview(), { onCommit: vi.fn() })

    expect(screen.getByText(/runs in the background/i)).toBeInTheDocument()
  })
})
