import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ObDashboardDrillPanel } from './ObDashboardDrillPanel'

/**
 * B-127 · the panel in isolation, against a mocked
 * `useListObDashboardCardItems` — `DrillDownPanel.test.tsx`'s own shape one
 * module over. What is under test is the panel's own rendering and the
 * client-row handoff; the query itself is `ObDashboardCardItemsIT`'s job.
 */

const navigate = vi.fn()
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return { ...actual, useNavigate: () => navigate }
})

const useListObDashboardCardItems = vi.fn()
vi.mock('@/api/generated/onboarding/onboarding', () => ({
  useListObDashboardCardItems: (...args: unknown[]) => useListObDashboardCardItems(...args),
}))

const ROW = {
  itemType: 'SERVICE',
  itemId: 501,
  obClientId: 42,
  obClientName: 'Horizon Retail',
  journeyId: 9,
  product: { id: 1, code: 'ERP', name: 'ERP' },
  title: 'Data migration',
  owner: { id: 7, displayName: 'Meera Nair' },
  status: 'IN_PROGRESS',
  dueAt: '2026-09-10T12:00:00.000Z',
  isOverdue: false,
}

function served(data: unknown, meta: Record<string, unknown> = {}) {
  useListObDashboardCardItems.mockReturnValue({
    data: data === undefined ? undefined : { data, meta: { hasMore: false, computedAt: null, ...meta } },
    isPending: false,
    isError: false,
  })
}

function renderPanel(props: Partial<React.ComponentProps<typeof ObDashboardDrillPanel>> = {}) {
  return render(
    <MemoryRouter>
      <ObDashboardDrillPanel
        cardKey="this-weeks-deadlines"
        onClose={vi.fn()}
        {...props}
      />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  served([ROW])
})

describe('ObDashboardDrillPanel', () => {
  it('stays closed and asks nothing of the server when no card is open', () => {
    served(undefined)
    renderPanel({ cardKey: null })

    expect(useListObDashboardCardItems).toHaveBeenCalledWith(
      expect.any(String),
      expect.anything(),
      expect.objectContaining({ query: expect.objectContaining({ enabled: false }) }),
    )
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('titles the panel from the card, when no override is given', () => {
    renderPanel({ cardKey: 'this-weeks-deadlines' })

    expect(screen.getByRole('heading', { name: "This week's deadlines" })).toBeInTheDocument()
  })

  /** B-128's own reuse: a workload-grid cell wants its own title, not the card's. */
  it('accepts a title override, for a caller that is not a card tile', () => {
    renderPanel({ cardKey: 'this-weeks-deadlines', title: "Meera's clients" })

    expect(screen.getByRole('heading', { name: "Meera's clients" })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: "This week's deadlines" })).not.toBeInTheDocument()
  })

  it('renders a row with its client, product, item, owner, due date and status', () => {
    renderPanel()

    expect(screen.getByRole('button', { name: 'Horizon Retail' })).toBeInTheDocument()
    expect(screen.getByText('ERP')).toBeInTheDocument()
    expect(screen.getByText('Data migration')).toBeInTheDocument()
    expect(screen.getByText('Meera Nair')).toBeInTheDocument()
    expect(screen.getByText('IN_PROGRESS')).toBeInTheDocument()
    expect(screen.queryByText('Overdue')).not.toBeInTheDocument()
  })

  it('flags an overdue row without waiting for the caller to notice the date', () => {
    served([{ ...ROW, isOverdue: true }])
    renderPanel()

    expect(screen.getByText('Overdue')).toBeInTheDocument()
  })

  it('a prerequisite row shows no product and no owner, rather than a misleading dash for a verifier', () => {
    served([{ ...ROW, itemType: 'PREREQUISITE', journeyId: null, product: null, owner: null }])
    renderPanel()

    // Two em dashes: product and owner, both genuinely absent on a prerequisite.
    expect(screen.getAllByText('—')).toHaveLength(2)
  })

  it('a row opens the client and closes the panel, never one without the other', async () => {
    const onClose = vi.fn()
    renderPanel({ onClose })

    await userEvent.click(screen.getByRole('button', { name: 'Horizon Retail' }))

    expect(navigate).toHaveBeenCalledWith('/onboarding/clients/42')
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('says how many more there are, rather than silently truncating', () => {
    served([ROW], { hasMore: true })
    renderPanel()

    expect(screen.getByText(/Showing the first 50/)).toBeInTheDocument()
  })

  it('an empty list explains the staleness gap rather than reading as broken', () => {
    served([])
    renderPanel()

    expect(screen.getByText('Nothing matches this card right now')).toBeInTheDocument()
  })

  it('a failed request does not claim the card itself is wrong', () => {
    useListObDashboardCardItems.mockReturnValue({ data: undefined, isPending: false, isError: true })
    renderPanel()

    expect(screen.getByText('This list could not be loaded')).toBeInTheDocument()
  })

  it('shows a skeleton, not a blank panel, while the first request is in flight', () => {
    useListObDashboardCardItems.mockReturnValue({ data: undefined, isPending: true, isError: false })
    renderPanel()

    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: "This week's deadlines" })).toBeInTheDocument()
  })
})
