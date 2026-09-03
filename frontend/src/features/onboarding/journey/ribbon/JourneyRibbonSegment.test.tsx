import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import type { JourneyStep } from './types'
import { JourneyRibbonSegment } from './JourneyRibbonSegment'

function step(over: Partial<JourneyStep> = {}): JourneyStep {
  return {
    id: 's3',
    seqNo: 3,
    name: 'Data migration',
    status: 'CURRENT',
    owner: { displayName: 'Priya Nair' },
    ownerRole: 'STEP_OWNER',
    tatDays: 8,
    tatPercent: 40,
    dependsOnSeqNo: 2,
    ...over,
  }
}

describe('JourneyRibbonSegment', () => {
  it('renders the seq, name and owner', () => {
    render(<JourneyRibbonSegment step={step()} />)
    expect(screen.getByText('3. Data migration')).toBeInTheDocument()
    expect(screen.getByText('Priya Nair')).toBeInTheDocument()
  })

  it('shows the dependency badge', () => {
    render(<JourneyRibbonSegment step={step({ dependsOnSeqNo: 2 })} />)
    expect(screen.getByTitle('Depends on step 2')).toHaveTextContent('↳ 2')
  })

  it('shows the parallel badge for a dependency-free step', () => {
    render(<JourneyRibbonSegment step={step({ dependsOnSeqNo: null })} />)
    expect(screen.getByTitle('No dependency — runs parallel')).toHaveTextContent('∥')
  })

  it('shows SD/FD and the closed marker for a DONE step, not a TAT budget', () => {
    render(
      <JourneyRibbonSegment
        step={step({
          status: 'DONE',
          tatPercent: null,
          startedOn: '2026-08-01',
          finishedOn: '2026-08-09',
          closed: 'early',
        })}
      />,
    )
    expect(screen.getByText(/SD 1 Aug/)).toBeInTheDocument()
    expect(screen.getByText(/FD 9 Aug/)).toBeInTheDocument()
    expect(screen.getByText(/early/)).toBeInTheDocument()
  })

  it('shows the TAT budget for a step still running', () => {
    render(<JourneyRibbonSegment step={step({ status: 'PENDING', tatPercent: null, tatDays: 5 })} />)
    expect(screen.getByText(/5d TAT/)).toBeInTheDocument()
  })

  it('renders a read-only group with no onSelect, and a button once one is given', () => {
    const { rerender } = render(<JourneyRibbonSegment step={step()} />)
    expect(screen.getByRole('group')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Data migration/ })).not.toBeInTheDocument()

    const onSelect = vi.fn()
    rerender(<JourneyRibbonSegment step={step()} onSelect={onSelect} />)
    expect(screen.getByRole('button')).toBeInTheDocument()
  })

  it('calls onSelect with the step on click', async () => {
    const onSelect = vi.fn()
    const user = userEvent.setup()
    const theStep = step()
    render(<JourneyRibbonSegment step={theStep} onSelect={onSelect} />)

    await user.click(screen.getByRole('button'))
    expect(onSelect).toHaveBeenCalledWith(theStep)
  })

  it('marks the current step for assistive tech', () => {
    render(<JourneyRibbonSegment step={step({ status: 'CURRENT' })} />)
    expect(screen.getByRole('group')).toHaveAttribute('aria-current', 'step')
  })

  it('omits the trailing connector on the last tile', () => {
    render(<JourneyRibbonSegment step={step()} isLast />)
    expect(screen.queryByTestId('journey-ribbon-connector')).not.toBeInTheDocument()
  })

  it('draws the animated emoji title matching the step state', () => {
    render(<JourneyRibbonSegment step={step({ status: 'BLOCKED' })} />)
    expect(screen.getByTitle('Blocked / waiting')).toBeInTheDocument()
  })

  it('draws no emoji for a PENDING step', () => {
    render(<JourneyRibbonSegment step={step({ status: 'PENDING', tatPercent: null })} />)
    expect(screen.queryByTitle('On time')).not.toBeInTheDocument()
  })
})
