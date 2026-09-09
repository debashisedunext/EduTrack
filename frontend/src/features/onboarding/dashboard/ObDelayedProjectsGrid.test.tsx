import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ObDelayedProjectsGrid } from './ObDelayedProjectsGrid'

/**
 * B-128 · the grid in isolation, against a mocked `useListObDelayedProjects`
 * — {@code ObDashboardDrillPanel.test.tsx}'s own shape. What is under test is
 * this component's own rendering and the client-row handoff; the query
 * itself is `ObDelayedProjectsIT`'s job.
 */

const navigate = vi.fn()
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return { ...actual, useNavigate: () => navigate }
})

const useListObDelayedProjects = vi.fn()
vi.mock('@/api/generated/onboarding/onboarding', () => ({
  useListObDelayedProjects: (...args: unknown[]) => useListObDelayedProjects(...args),
}))

const ROW = {
  journeyId: 9,
  obClientId: 42,
  obClientName: 'Horizon Retail',
  startedAt: '2026-03-01T09:00:00.000Z',
  productsBought: [
    { id: 1, code: 'ERP', name: 'ERP' },
    { id: 2, code: 'BIO', name: 'Biometric' },
  ],
  product: { id: 1, code: 'ERP', name: 'ERP' },
  currentStep: { id: 40, sequence: 2, name: 'Data migration', status: 'IN_PROGRESS' },
  responsible: { id: 7, displayName: 'Meera Nair' },
  expectedCompletionAt: '2026-08-28T18:30:00.000Z',
  delayedByDays: 3,
}

function served(data: unknown, meta: Record<string, unknown> = {}) {
  useListObDelayedProjects.mockReturnValue({
    data: data === undefined ? undefined : { data, meta: { hasMore: false, ...meta } },
    isPending: false,
    isError: false,
  })
}

function renderGrid() {
  return render(
    <MemoryRouter>
      <ObDelayedProjectsGrid />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  served([ROW])
})

describe('ObDelayedProjectsGrid', () => {
  it('renders one row per delayed journey, with the working-days-late figure', () => {
    renderGrid()

    expect(screen.getByRole('cell', { name: /3 working days/ })).toBeInTheDocument()
    expect(screen.getByRole('row', { name: /Horizon Retail/ })).toBeInTheDocument()
  })

  it('names the client, the module and the responsible implementor as their own columns', () => {
    renderGrid()

    const row = screen.getByRole('row', { name: /Horizon Retail/ })
    expect(row).toHaveTextContent('ERP')
    expect(row).toHaveTextContent('Data migration')
    expect(row).toHaveTextContent('Meera Nair')
    expect(row).toHaveTextContent('ERP, BIO')
  })

  it('renders a dash for a null currentStep rather than guessing one', () => {
    served([{ ...ROW, currentStep: null }])
    renderGrid()

    const row = screen.getByRole('row', { name: /Horizon Retail/ })
    expect(row).toHaveTextContent('—')
  })

  it("opening a client's row navigates to the client, closing nothing else on this page", async () => {
    renderGrid()

    await userEvent.click(screen.getByRole('row', { name: /Horizon Retail/ }))

    expect(navigate).toHaveBeenCalledWith('/onboarding/clients/42')
  })

  it('shows an empty state when nothing is delayed, distinct from a loading or error state', () => {
    served([])
    renderGrid()

    expect(screen.getByText('Nothing is currently delayed')).toBeInTheDocument()
  })

  it('shows a loading skeleton rather than an empty grid while the request is in flight', () => {
    useListObDelayedProjects.mockReturnValue({ data: undefined, isPending: true, isError: false })
    renderGrid()

    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.queryByText('Nothing is currently delayed')).not.toBeInTheDocument()
  })

  it('shows an error state distinct from the empty one', () => {
    useListObDelayedProjects.mockReturnValue({ data: undefined, isPending: false, isError: true })
    renderGrid()

    expect(screen.getByText('The delayed projects grid could not be loaded')).toBeInTheDocument()
  })

  it('names the grid for a screen-reader user via its own heading', () => {
    renderGrid()

    expect(screen.getByRole('heading', { name: 'Delayed projects' })).toBeInTheDocument()
    expect(screen.getByRole('table')).toBeInTheDocument()
  })
})
