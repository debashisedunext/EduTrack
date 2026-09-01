import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'

import { useDrillDownStore } from '../../drillDownStore'
import { AssigneeMisTable } from './AssigneeMisTable'

const fig = (value: number, drillDown: string | null = null) => ({ value, drillDown })

const ROW = {
  userId: 3,
  displayName: 'Ravi Kumar',
  overdueStart: fig(2, '/tickets?assigneeId=3&statusCategory=TODO&dueTo=2026-09-01'),
  dueToday: fig(1, '/tickets?assigneeId=3&statusCategory=TODO&dueFrom=2026-09-01&dueTo=2026-09-01'),
  notStarted: fig(3, '/tickets?assigneeId=3&statusCategory=TODO'),
  wip: fig(4, '/tickets?assigneeId=3&statusCategory=IN_PROGRESS'),
  updatedToday: fig(2, '/tickets?assigneeId=3&statusCategory=IN_PROGRESS&updatedFrom=2026-09-01&updatedTo=2026-09-01'),
  nearDelay: fig(1, '/tickets?assigneeId=3&statusCategory=IN_PROGRESS&isDelayed=false&dueTo=2026-09-02'),
  delayed: fig(2, '/tickets?assigneeId=3&statusCategory=IN_PROGRESS&isDelayed=true'),
  onTime: fig(1, '/tickets?assigneeId=3&statusCategory=IN_PROGRESS&isDelayed=false'),
  finishedToday: fig(0, null),
  finishedLate: fig(0, null),
}

beforeEach(() => {
  useDrillDownStore.setState({ drillDown: null, title: '', count: null })
})

describe('AssigneeMisTable', () => {
  it('renders one row per resource with every column', () => {
    render(<AssigneeMisTable rows={[ROW]} />)

    expect(screen.getByText('Ravi Kumar')).toBeInTheDocument()
    for (const label of [
      'Overdue start', 'Due today', 'Not started', 'WIP', 'Updated',
      'Near delay', 'Delayed', 'On time', 'Finished today', 'Finished late',
    ]) {
      expect(screen.getByRole('columnheader', { name: label })).toBeInTheDocument()
    }
  })

  it('opens the drill-down keyed by assignee and metric', async () => {
    render(<AssigneeMisTable rows={[ROW]} />)

    await userEvent.click(screen.getByRole('button', { name: /^Ravi Kumar, Delayed: 2\./ }))

    expect(useDrillDownStore.getState().drillDown).toBe(
      '/tickets?assigneeId=3&statusCategory=IN_PROGRESS&isDelayed=true',
    )
    expect(useDrillDownStore.getState().title).toBe('Ravi Kumar — Delayed')
    expect(useDrillDownStore.getState().count).toBe(2)
  })

  it('is not a button when the cell has no drill-down', () => {
    render(<AssigneeMisTable rows={[ROW]} />)

    expect(screen.queryByRole('button', { name: /Finished today/ })).not.toBeInTheDocument()
    expect(screen.getByRole('cell', { name: 'Ravi Kumar, Finished today: 0' })).toBeInTheDocument()
  })

  it('shows the row count', () => {
    render(<AssigneeMisTable rows={[ROW, { ...ROW, userId: 4, displayName: 'Neha Singh' }]} />)
    expect(screen.getByText('2 rows')).toBeInTheDocument()
  })
})
