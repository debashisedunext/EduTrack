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
    idleMins: 2640,
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

    for (const header of ['It', 'Stage', 'Resource', 'Role', 'In', 'Out', 'Duration', 'Effort', 'Idle']) {
      expect(screen.getByRole('columnheader', { name: header })).toBeInTheDocument()
    }

    const cells = within(screen.getAllByRole('row')[1]).getAllByRole('cell')
    expect(cells.slice(0, 8).map((c) => c.textContent)).toEqual([
      '1', 'Development', 'Ravi Kumar', 'DEVELOPER', '1 Aug', '3 Aug', '2d 1h', '9.0 h',
    ])
    // Idle carries a screen-reader note when waiting dominates, which this
    // fixture's 9 hours across two days does — so match the figure, not the cell.
    expect(cells[8]).toHaveTextContent('1d 20h')
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
    const body = screen.getAllByRole('rowgroup')[1] // thead, tbody, tfoot
    const stages = within(body).getAllByRole('row').map((r) => within(r).getAllByRole('cell')[1].textContent)
    expect(stages).toEqual(['Development', 'QA', 'Development'])
  })

  it('shows the open hop as in progress rather than a zero duration', () => {
    render(<JourneyTab rows={[row({ exitedAt: null, durationMins: null, idleMins: null })]} isLoading={false} loadError={null} />)
    const cells = within(screen.getAllByRole('row')[1]).getAllByRole('cell')
    expect(cells[5]).toHaveTextContent('in progress')
    expect(cells[6]).toHaveTextContent('—')
    // Idle is unmeasured too, not zero — nothing has been waited yet that anyone counted.
    expect(cells[8]).toHaveTextContent('—')
  })

  it('names an unassigned hop rather than leaving the cell blank', () => {
    // §4A.2 lets a ticket fall to a project-level queue when the receiving role
    // has nobody free, so this is a real state, not missing data.
    render(<JourneyTab rows={[row({ resource: undefined })]} isLoading={false} loadError={null} />)
    expect(screen.getByText('Unassigned')).toBeInTheDocument()
  })

  it('marks a queue-bound hop for a sighted reader and names the reason for a screen reader', () => {
    // C-056 · the insight §4A.4 says justifies the whole feature. Colour alone
    // would leave a screen-reader user with an unremarkable number.
    render(<JourneyTab rows={[row({ durationMins: 2880, effortHrs: 2, idleMins: 2760 })]} isLoading={false} loadError={null} />)
    expect(screen.getByText(/^— most of this stage was spent waiting, not working$/)).toBeInTheDocument()
  })

  it('leaves a mostly-worked hop unmarked', () => {
    render(<JourneyTab rows={[row({ durationMins: 480, effortHrs: 7, idleMins: 60 })]} isLoading={false} loadError={null} />)
    // The table caption legitimately mentions waiting, so this must match the
    // per-row note rather than the word anywhere on the page.
    expect(screen.queryByText(/^— most of this stage was spent waiting, not working$/)).not.toBeInTheDocument()
  })

  it('rolls up per resource, counting distinct stages and iterations', () => {
    // C-057 · Ravi held DEV twice across a rework. §4A.4 calls that
    // "1 stage, 2 iterations", not two stages.
    render(
      <JourneyTab
        rows={[row({ stageCode: 'DEV', iterationNo: 1 }), row({ stageCode: 'DEV', iterationNo: 2 })]}
        cycleTotalHrs={18}
        allCyclesTotalHrs={38}
        isLoading={false}
        loadError={null}
      />,
    )
    expect(screen.getByText('Roll-up by resource')).toBeInTheDocument()
    expect(screen.getByText('1 stage, 2 iterations')).toBeInTheDocument()
  })

  it('labels the cycle total from the rows the server returned', () => {
    render(
      <JourneyTab rows={[row({ cycleNo: 2 })]} cycleTotalHrs={18} allCyclesTotalHrs={38} isLoading={false} loadError={null} />,
    )
    expect(screen.getByText('Total (cycle 2)')).toBeInTheDocument()
    expect(screen.getByText('18.0 h')).toBeInTheDocument()
    expect(screen.getByText('38.0 h')).toBeInTheDocument()
  })

  it('shows no elapsed figure for all cycles, because only one cycle is loaded', () => {
    // Summing this cycle's elapsed and labelling it "all cycles" would be a
    // plainly wrong number in the most authoritative-looking row on the page.
    render(
      <JourneyTab rows={[row()]} cycleTotalHrs={9} allCyclesTotalHrs={38} isLoading={false} loadError={null} />,
    )
    const footer = screen.getAllByRole('rowgroup')[2]
    const allCycles = within(footer).getByText('Total (all cycles)').closest('tr')!
    const cells = within(allCycles).getAllByRole('cell')
    expect(cells[1]).toHaveTextContent('—')
    expect(cells[2]).toHaveTextContent('38.0 h')
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
