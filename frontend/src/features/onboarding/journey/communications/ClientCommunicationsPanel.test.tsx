import { describe, expect, it } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import type { ObJourneyStrip } from '@/api/generated/model/obJourneyStrip'
import { ClientCommunicationsPanel } from './ClientCommunicationsPanel'

/**
 * C-112 · the stitched view against the mock server.
 *
 * Northwind (client 1) is the fixture, because it is the only seeded client
 * with communications on **two** journeys — which is the whole point of a
 * stitched view and the one thing a per-service timeline cannot show.
 */
const JOURNEYS = [
  { id: 1, product: { id: 1, code: 'ERP', name: 'ERP Suite' }, gateStatus: 'OPEN' },
  { id: 2, product: { id: 2, code: 'BIO', name: 'Biometric Attendance' }, gateStatus: 'OPEN' },
] as unknown as ObJourneyStrip[]

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <ClientCommunicationsPanel obClientId={1} journeys={JOURNEYS} />
    </QueryClientProvider>,
  )
}

/** MSW adds latency and the suite is heavily parallel — `ObClientDetailPage.test.tsx`'s convention. */
const SLOW = { timeout: 5000 }

/**
 * A test that changes a filter makes **two** round trips through MSW, and
 * vitest's own 5s default is the same number `SLOW` is — so the test times out
 * before the second `findBy` ever gets its full window. Raised here rather
 * than globally: the single-read tests are correct at the default, and a
 * suite-wide bump would hide a genuinely slow one.
 */
const MULTI_STEP = 20_000

describe('ClientCommunicationsPanel', () => {
  it('stitches entries from every service of the client into one timeline', async () => {
    renderPanel()

    // Journey 1's own thread…
    await screen.findByText(/Kickoff call with Meena and Sanjay/, undefined, SLOW)
    // …and journey 2's, which no per-service timeline would ever show beside it.
    expect(screen.getByText(/biometric devices can ship/)).toBeInTheDocument()
  })

  /*
   * A feed you check, not a narrative you read forwards. The entry that
   * matters is the last one, so it is first.
   */
  it('puts the most recent conversation first', async () => {
    renderPanel()
    await screen.findByText(/Kickoff call with Meena and Sanjay/, undefined, SLOW)

    const entries = screen.getAllByTestId('communication-entry')
    expect(entries[0]).toHaveTextContent(/biometric devices can ship/)
    expect(entries[entries.length - 1]).toHaveTextContent(/Kickoff call with Meena and Sanjay/)
  })

  /*
   * Which service an entry came from is the only thing distinguishing two
   * adjacent rows here — it is why this list is not the step panel with a
   * wider read.
   */
  it('names the service every entry came from', async () => {
    renderPanel()
    await screen.findByText(/Kickoff call with Meena and Sanjay/, undefined, SLOW)

    expect(screen.getByText('ERP Suite · 1. Kickoff & Requirement Sign-off')).toBeInTheDocument()
    expect(screen.getByText('Biometric Attendance · 1. Device Rollout')).toBeInTheDocument()
  })

  it('renders all three author shapes, including the ones no staff user wrote', async () => {
    renderPanel()
    await screen.findByText(/Kickoff call with Meena and Sanjay/, undefined, SLOW)

    expect(screen.getByText('Portal comment · Sanjay Bose (client)')).toBeInTheDocument()
    expect(screen.getByText('System · System')).toBeInTheDocument()
    expect(screen.getAllByText(/· Ravi Kumar$/).length).toBeGreaterThan(0)
  })

  /*
   * §11 and CP-03. Both states are words, never a tint alone, because an
   * internal note repeated on a call is the one mistake here that cannot be
   * taken back.
   */
  it('says of every entry, in words, whether the client can read it', async () => {
    renderPanel()
    await screen.findByText(/Kickoff call with Meena and Sanjay/, undefined, SLOW)

    expect(screen.getAllByText('Client can see this').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Internal only').length).toBeGreaterThan(0)
  })

  it(
    'narrows to one service, and back',
    async () => {
      const user = userEvent.setup()
      renderPanel()
      await screen.findByText(/biometric devices can ship/, undefined, SLOW)

      await user.selectOptions(screen.getByLabelText('Service'), '2')

      // The filter is a new query key, so the panel goes back through its
      // pending state — wait for the surviving entry rather than asserting
      // into the skeleton.
      await screen.findByText(/biometric devices can ship/, undefined, SLOW)
      await waitFor(
        () => expect(screen.queryByText(/Kickoff call with Meena and Sanjay/)).not.toBeInTheDocument(),
        SLOW,
      )

      await user.selectOptions(screen.getByLabelText('Service'), '')
      await screen.findByText(/Kickoff call with Meena and Sanjay/, undefined, SLOW)
    },
    MULTI_STEP,
  )

  /*
   * The question before a call: did we say that to them, or only to each
   * other. The internal note about their Tally export is exactly the entry
   * this filter exists to keep out of that answer.
   */
  it(
    'hides internal entries when the reader asks what the client can see',
    async () => {
      const user = userEvent.setup()
      renderPanel()
      await screen.findByText(/opening balances/, undefined, SLOW)

      await user.click(screen.getByLabelText('Client-visible only'))

      // The client-visible kickoff call survives the filter, so waiting for it
      // is what proves the second read finished rather than that it is still
      // pending — a "not in the document" assertion is true of a skeleton too.
      await screen.findByText(/Kickoff call with Meena and Sanjay/, undefined, SLOW)
      expect(screen.queryByText(/opening balances/)).not.toBeInTheDocument()
      expect(screen.queryByText('Internal only')).not.toBeInTheDocument()
    },
    MULTI_STEP,
  )

  /*
   * Acme has journeys and no communications at all. The sentence has to say
   * "nothing has been recorded", not "nothing matches these filters" — the
   * two are different facts and only one of them is the reader's to fix.
   */
  it(
    'distinguishes an empty record from an over-narrow filter',
    async () => {
      const user = userEvent.setup()
      const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
      render(
        <QueryClientProvider client={queryClient}>
          <ClientCommunicationsPanel
            obClientId={2}
            journeys={
              [
                { id: 3, product: { id: 1, code: 'ERP', name: 'ERP Suite' }, gateStatus: 'LOCKED' },
              ] as unknown as ObJourneyStrip[]
            }
          />
        </QueryClientProvider>,
      )

      await screen.findByText('Nothing has been recorded against this client yet.', undefined, SLOW)

      await user.click(screen.getByLabelText('Client-visible only'))

      await screen.findByText('Nothing matches these filters.', undefined, SLOW)
    },
    MULTI_STEP,
  )

  it('is a labelled region, not an unnamed stack of rows', async () => {
    renderPanel()
    await screen.findByText(/Kickoff call with Meena and Sanjay/, undefined, SLOW)

    const region = screen.getByTestId('client-communications')
    expect(within(region).getByRole('heading', { name: 'Communications' })).toBeInTheDocument()
  })
})
