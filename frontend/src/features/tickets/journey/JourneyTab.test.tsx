import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'

import type { JourneyRow } from '@/api/generated/model/journeyRow'
import { JourneyTab } from './JourneyTab'

/**
 * C-055 · the grid itself. Plain data in, no server — the one query is
 * `useJourneyTab`'s, and its own reason for existing is covered there.
 */

function row(over: Partial<JourneyRow> = {}): JourneyRow {
  return {
    iterationNo: 1,
    cycleNo: 1,
    stageCode: 'DEVELOPMENT',
    resource: { id: 8, displayName: 'Ravi Kumar' },
    role: 'DEVELOPER',
    enteredAt: '2026-08-01T09:00:00Z',
    exitedAt: '2026-08-03T10:00:00Z',
    durationMins: 2940,
    effortHrs: 9,
    ...over,
  }
}

describe('JourneyTab', () => {
  it('renders one row per hop with §4A.4s eight columns', () => {
    render(<JourneyTab rows={[row()]} isLoading={false} loadError={null} />)

    for (const header of ['It', 'Stage', 'Resource', 'Role', 'In', 'Out', 'Duration', 'Effort']) {
      expect(screen.getByRole('columnheader', { name: header })).toBeInTheDocument()
    }

    const cells = within(screen.getAllByRole('row')[1]).getAllByRole('cell')
    expect(cells.map((c) => c.textContent)).toEqual([
      '1', 'Development', 'Ravi Kumar', 'DEVELOPER', '1 Aug', '3 Aug', '2d 1h', '9.0 h',
    ])
  })

  it('keeps two iterations of the same stage as adjacent rows', () => {
    // The bounce is the thing the grid exists to make visible, so nothing here
    // may group or collapse DEV → QA → DEV back into one Development row.
    render(
      <JourneyTab
        rows={[
          row({ iterationNo: 1, stageCode: 'DEVELOPMENT' }),
          row({ iterationNo: 1, stageCode: 'QA' }),
          row({ iterationNo: 2, stageCode: 'DEVELOPMENT' }),
        ]}
        isLoading={false}
        loadError={null}
      />,
    )
    const stages = screen.getAllByRole('row').slice(1).map((r) => within(r).getAllByRole('cell')[1].textContent)
    expect(stages).toEqual(['Development', 'QA', 'Development'])
  })

  it('shows the open hop as in progress rather than a zero duration', () => {
    render(<JourneyTab rows={[row({ exitedAt: null, durationMins: null })]} isLoading={false} loadError={null} />)
    const cells = within(screen.getAllByRole('row')[1]).getAllByRole('cell')
    expect(cells[5]).toHaveTextContent('in progress')
    expect(cells[6]).toHaveTextContent('—')
  })

  it('names an unassigned hop rather than leaving the cell blank', () => {
    // §4A.2 lets a ticket fall to a project-level queue when the receiving role
    // has nobody free, so this is a real state, not missing data.
    render(<JourneyTab rows={[row({ resource: undefined })]} isLoading={false} loadError={null} />)
    expect(screen.getByText('Unassigned')).toBeInTheDocument()
  })

  it('does not render the idle column or the roll-up — those are C-056 and C-057', () => {
    render(<JourneyTab rows={[row()]} isLoading={false} loadError={null} />)
    expect(screen.queryByRole('columnheader', { name: /idle/i })).not.toBeInTheDocument()
    expect(screen.queryByText(/roll-up by resource/i)).not.toBeInTheDocument()
  })

  it('reports loading, failure and emptiness distinctly', () => {
    const { rerender } = render(<JourneyTab rows={[]} isLoading loadError={null} />)
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(document.querySelector('[aria-busy="true"]')).toBeInTheDocument()

    rerender(<JourneyTab rows={[]} isLoading={false} loadError="The journey could not be loaded." />)
    expect(screen.getByRole('alert')).toHaveTextContent('The journey could not be loaded.')

    rerender(<JourneyTab rows={[]} isLoading={false} loadError={null} />)
    expect(screen.getByText('No journey yet')).toBeInTheDocument()
  })
})
