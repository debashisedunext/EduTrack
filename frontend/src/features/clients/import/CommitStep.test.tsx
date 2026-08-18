import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { HttpResponse, http } from 'msw'

import { server } from '@/mocks/server'

import { CommitStep } from './CommitStep'

/**
 * B-035 · S-34 step 5, rendered.
 *
 * `commitProgress.test.ts` owns the arithmetic. What is left here is the half a
 * pure function cannot show, and it is the half this kind of screen gets wrong:
 *
 * - a run that has finished must **stop polling**, or a tab left open all
 *   afternoon asks a question every two seconds whose answer cannot change;
 * - `COMPLETED` and `FAILED` must say different *kinds* of thing — one reports
 *   an outcome, the other reports that there is no outcome;
 * - the progress bar must be a `progressbar` with a value on it, because a
 *   coloured `<div>` announces nothing at all.
 */

const BATCH = {
  batchId: 77,
  entity: 'CLIENT',
  fileName: 'clients.xlsx',
  status: 'RUNNING',
  processed: 40,
  total: 100,
  created: 30,
  updated: 8,
  rejected: 2,
  errorReportUrl: null,
}

/**
 * jsdom implements neither, and `saveBlob` uses both — the same two stubs
 * `ClientImportPage.test.tsx` sets up for the template download, and set up the
 * same way. `Object.assign(URL, ...)` rather than a fresh object: replacing
 * `URL` wholesale takes its constructor with it, and MSW builds one per request.
 */
const createObjectURL = vi.fn(() => 'blob:report')
const revokeObjectURL = vi.fn()

/** jsdom has no navigation, so the anchor's attributes are the observable behaviour. */
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

function answerWith(batch: Record<string, unknown>, onPoll?: () => void) {
  server.use(
    http.get('*/import-batches/:batchId', () => {
      onPoll?.()
      return HttpResponse.json({ data: batch })
    }),
  )
}

function renderStep(onStartAnother = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <CommitStep batchId={77} fileName="clients.xlsx" onStartAnother={onStartAnother} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('the commit progress', () => {
  it('renders a real progressbar with the run’s position on it', async () => {
    answerWith(BATCH)
    renderStep()

    const bar = await screen.findByRole('progressbar')
    // A coloured div announces nothing. The value is what a screen reader reads,
    // and 40 of 100 must not be reported as 38 — the rejected rows are part of
    // the file being worked through.
    expect(bar).toHaveAttribute('aria-valuenow', '40')
  })

  it('shows the counts from the server, not from the preview', async () => {
    answerWith(BATCH)
    renderStep()

    const results = await screen.findByRole('list', { name: /import results/i })
    expect(results).toHaveTextContent('30')
    expect(results).toHaveTextContent('8')
    expect(results).toHaveTextContent('2')
  })

  it('says the run continues without the page, because it does', async () => {
    answerWith(BATCH)
    renderStep()

    expect(await screen.findByText(/you can leave this page/i)).toBeInTheDocument()
  })

  /**
   * The distinction this screen must not blur. A run that rejected half a file
   * completed; a run that died did not, and telling the user "imported 0 rows"
   * would describe the second as if it were the first.
   */
  it('reports a failure as a stop, not as an import of nothing', async () => {
    answerWith({ ...BATCH, status: 'FAILED', processed: 12, created: 10, updated: 0 })
    renderStep()

    expect(await screen.findByText(/stopped before it finished/i)).toBeInTheDocument()
    // And says the safe thing about what to do next, which is only true because
    // the commit upserts on the natural key.
    expect(screen.getByText(/never duplicated/i)).toBeInTheDocument()
  })

  it('reports a completed run as what it wrote', async () => {
    answerWith({ ...BATCH, status: 'COMPLETED', processed: 100, created: 90, updated: 8 })
    renderStep()

    expect(await screen.findByText(/imported 98 rows from clients\.xlsx/i)).toBeInTheDocument()
  })

  /**
   * B-036 · a run whose report could not be stored. Visible and disabled rather
   * than hidden, the shape every step of this wizard has been left in — hiding
   * it would make the screen look finished and leave the skipped rows
   * unaccounted for.
   *
   * `errorReportUrl` is null in exactly three cases and this is the only one a
   * finished run can be in: the object store was unreachable at the end of the
   * run. The sentence says so rather than promising a feature.
   */
  it('offers the error report, disabled, when the run produced none', async () => {
    answerWith({ ...BATCH, status: 'COMPLETED', processed: 100, created: 90, updated: 8 })
    renderStep()

    const download = await screen.findByRole('button', { name: /error report/i })
    expect(download).toBeDisabled()
    expect(download).toHaveAttribute('title', expect.stringContaining('No error report'))
    expect(screen.getByText(/could not be produced for this run/i)).toBeInTheDocument()
  })

  /**
   * <b>The one B-036 exists for.</b> The batch says there is a report; pressing
   * the button fetches it and hands it to the browser.
   *
   * The name is asserted because it is the whole reason this download does not
   * go through the generated hook: the server names the file per run, and a
   * client that reconstructed the name would be a second place that has to agree
   * about a string.
   */
  it('downloads the error report under the name the server gave it', async () => {
    answerWith({
      ...BATCH,
      status: 'COMPLETED',
      processed: 100,
      created: 90,
      updated: 8,
      errorReportUrl: '/import-batches/77/error-report',
    })
    server.use(
      http.get('*/import-batches/:batchId/error-report', () =>
        new HttpResponse(new Blob(['PK']), {
          headers: {
            'Content-Type':
              'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
            'Content-Disposition': 'attachment; filename="clients-import-errors-77.xlsx"',
          },
        }),
      ),
    )
    renderStep()

    const download = await screen.findByRole('button', { name: /error report/i })
    expect(download).toBeEnabled()
    await userEvent.click(download)

    await waitFor(() => expect(clicked?.download).toBe('clients-import-errors-77.xlsx'))
    expect(createObjectURL).toHaveBeenCalled()
  })

  /**
   * A 404 on the download is the one a bookmark hits, and the user is looking at
   * a count of skipped rows they were just told they could recover. Silence here
   * reads as a broken button.
   */
  it('says what happened when the report has gone', async () => {
    answerWith({
      ...BATCH,
      status: 'COMPLETED',
      processed: 100,
      created: 90,
      updated: 8,
      errorReportUrl: '/import-batches/77/error-report',
    })
    server.use(
      http.get('*/import-batches/:batchId/error-report', () =>
        HttpResponse.json(
          {
            type: 'https://edutrack/errors/not-found',
            title: 'Import error report not available',
            detail: 'Import batch 77 has no error report to download (status COMPLETED).',
            status: 404,
          },
          { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )
    renderStep()

    await userEvent.click(await screen.findByRole('button', { name: /error report/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/no error report to download/i)
  })

  it('does not offer an error report for a clean run', async () => {
    answerWith({ ...BATCH, status: 'COMPLETED', processed: 100, total: 100, created: 100, updated: 0, rejected: 0 })
    renderStep()

    await screen.findByText(/imported 100 rows/i)
    expect(screen.queryByRole('button', { name: /error report/i })).not.toBeInTheDocument()
  })

  /**
   * The stop condition itself is `batchPollInterval`, unit-tested in
   * `commitProgress.test.ts` — a component test for it has to either wait two
   * real seconds or fake the clock underneath MSW, and both are flaky in a way
   * a pure function is not. What is asserted here is that this screen is wired
   * to it at all: a finished run makes no second request.
   */
  it('does not poll again once the run is terminal', async () => {
    let polls = 0
    answerWith({ ...BATCH, status: 'COMPLETED', processed: 100, created: 90, updated: 8 }, () => {
      polls += 1
    })
    renderStep()

    await screen.findByText(/imported 98 rows/i)
    await waitFor(() => expect(polls).toBe(1))
    expect(polls).toBe(1)
  })

  it('offers a way onward once the run is over', async () => {
    answerWith({ ...BATCH, status: 'COMPLETED', processed: 100, created: 90, updated: 8 })
    renderStep()

    expect(await screen.findByRole('link', { name: /see the clients/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /import another file/i })).toBeInTheDocument()
  })

  /**
   * The import is running whatever this screen can read. Saying "it failed"
   * because a poll failed would be wrong in the most alarming direction.
   */
  it('does not claim the import failed when only the poll did', async () => {
    server.use(
      http.get('*/import-batches/:batchId', () =>
        HttpResponse.json({ title: 'Not found' }, { status: 404 }),
      ),
    )
    renderStep()

    expect(await screen.findByRole('alert')).toHaveTextContent(/it is still running/i)
  })
})
