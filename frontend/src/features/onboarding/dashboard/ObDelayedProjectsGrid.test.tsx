import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { ObProjectBoardRow } from '@/api/generated/model'

import { ObDelayedProjectsGrid } from './ObDelayedProjectsGrid'

/**
 * B-128 · the grid in isolation.
 *
 * <p>It takes the board's rows as a prop now rather than fetching a list of
 * its own, so there is no query to mock: the cases below hand it projects and
 * assert which ones it draws. "Behind schedule" is the project's own
 * completion date having passed — the `DELAYED` and `AT_RISK` buckets — and
 * the first case is the one that pins it, because a grid that simply drew
 * every row it was given would pass every other case here.
 */

const navigate = vi.fn()
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return { ...actual, useNavigate: () => navigate }
})

function row(over: Partial<ObProjectBoardRow> & { id: number }): ObProjectBoardRow {
  return {
    name: `Project ${over.id}`,
    client: { id: 100 + over.id, name: `Client ${over.id}`, clientCode: null, city: null },
    product: { id: 1, code: 'ERP', name: 'ERP' },
    startDate: '2026-03-01',
    gateStatus: 'OPEN',
    bucket: 'ON_TIME',
    tasksTotal: 4,
    tasksDone: 1,
    openEscalations: 0,
    ...over,
  } as ObProjectBoardRow
}

const DELAYED = row({
  id: 9,
  name: 'Horizon Retail — ERP',
  client: { id: 42, name: 'Horizon Retail', clientCode: null, city: null },
  bucket: 'DELAYED',
  currentStage: 'Data migration',
  implementor: { id: 7, displayName: 'Meera Nair' },
  tentativeCompletion: '2026-08-28',
  daysPastCompletion: 3,
} as Partial<ObProjectBoardRow> & { id: number })

function renderGrid(rows: ObProjectBoardRow[] = [DELAYED], over: Partial<{ isPending: boolean; isError: boolean }> = {}) {
  return render(
    <MemoryRouter>
      <ObDelayedProjectsGrid
        rows={rows}
        isPending={over.isPending ?? false}
        isError={over.isError ?? false}
      />
    </MemoryRouter>,
  )
}

beforeEach(() => vi.clearAllMocks())

describe('ObDelayedProjectsGrid', () => {
  /*
    The definition, and the case the rest of the file rests on. On time and
    ahead are not behind anything; NOT_SCHEDULED has no date to have passed,
    so it is not behind either — it is unplanned, which is a different
    problem and not this grid's.
  */
  it('draws the projects past their own completion date, and only those', () => {
    renderGrid([
      DELAYED,
      row({ id: 2, name: 'On time project', bucket: 'ON_TIME' }),
      row({ id: 3, name: 'Ahead project', bucket: 'AHEAD' }),
      row({ id: 4, name: 'At risk project', bucket: 'AT_RISK', daysPastCompletion: 12 }),
      row({ id: 5, name: 'Unplanned project', bucket: 'NOT_SCHEDULED' }),
    ])

    expect(screen.getByRole('row', { name: /Horizon Retail — ERP/ })).toBeInTheDocument()
    expect(screen.getByRole('row', { name: /At risk project/ })).toBeInTheDocument()
    expect(screen.queryByRole('row', { name: /On time project/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('row', { name: /Ahead project/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('row', { name: /Unplanned project/ })).not.toBeInTheDocument()
  })

  /** Worked from the top, so the furthest behind leads. */
  it('orders the grid furthest behind first', () => {
    renderGrid([
      row({ id: 1, name: 'Two days', bucket: 'DELAYED', daysPastCompletion: 2 }),
      row({ id: 2, name: 'Nine days', bucket: 'AT_RISK', daysPastCompletion: 9 }),
    ])

    const names = screen.getAllByRole('row').slice(1).map((r) => r.textContent ?? '')
    expect(names[0]).toContain('Nine days')
    expect(names[1]).toContain('Two days')
  })

  /*
    The grid is headed "Delayed projects" and counted in them, so the project
    names the row and the client qualifies it — it named the client alone
    while the rows were journeys.
  */
  it('names the project first, with the client, module, stage and implementor beside it', () => {
    renderGrid()

    const row_ = screen.getByRole('row', { name: /Horizon Retail — ERP/ })
    expect(row_).toHaveTextContent('Horizon Retail — ERP')
    expect(row_).toHaveTextContent('Horizon Retail')
    expect(row_).toHaveTextContent('ERP')
    expect(row_).toHaveTextContent('Data migration')
    expect(row_).toHaveTextContent('Meera Nair')
    expect(row_).toHaveTextContent('3 days')
  })

  it('renders a dash for a missing current stage rather than guessing one', () => {
    renderGrid([{ ...DELAYED, currentStage: undefined } as ObProjectBoardRow])

    expect(screen.getByRole('row', { name: /Horizon Retail — ERP/ })).toHaveTextContent('—')
  })

  /** The project, not the client: this grid is a list of projects to work. */
  it('opens the project a row is for', async () => {
    renderGrid()

    await userEvent.click(screen.getByRole('row', { name: /Horizon Retail — ERP/ }))

    expect(navigate).toHaveBeenCalledWith('/onboarding/projects/9')
  })

  it('shows an empty state when nothing is behind, distinct from loading or error', () => {
    renderGrid([row({ id: 1, bucket: 'ON_TIME' })])

    expect(screen.getByText('Nothing is behind schedule')).toBeInTheDocument()
  })

  it('shows a loading skeleton rather than an empty grid while the board is in flight', () => {
    renderGrid([], { isPending: true })

    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.queryByText('Nothing is behind schedule')).not.toBeInTheDocument()
  })

  it('shows an error state distinct from the empty one', () => {
    renderGrid([], { isError: true })

    expect(screen.getByText('The delayed projects grid could not be loaded')).toBeInTheDocument()
  })

  it('names the grid for a screen-reader user via its own heading', () => {
    renderGrid()

    expect(screen.getByRole('heading', { name: 'Delayed projects' })).toBeInTheDocument()
    expect(screen.getByRole('table')).toBeInTheDocument()
  })
})
