import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import type { JourneyStep } from './types'
import { JourneyRibbonStrip } from './JourneyRibbonStrip'

function step(over: Partial<JourneyStep> = {}): JourneyStep {
  return {
    id: over.id ?? 's1',
    seqNo: 1,
    name: 'Kickoff call',
    status: 'DONE',
    tatDays: 1,
    tatPercent: null,
    closed: null,
    ...over,
  }
}

const STEPS: JourneyStep[] = [
  step({ id: 's1', seqNo: 1, name: 'Kickoff call', status: 'DONE' }),
  step({ id: 's2', seqNo: 2, name: 'Requirements', status: 'DONE' }),
  step({ id: 's3', seqNo: 3, name: 'Data migration', status: 'CURRENT', tatPercent: 40, tatDays: 8 }),
  step({ id: 's4', seqNo: 4, name: 'Training', status: 'PENDING', tatPercent: null, tatDays: 4, dependsOnSeqNo: 3 }),
]

describe('JourneyRibbonStrip', () => {
  it('renders one listitem per step, in order', () => {
    render(<JourneyRibbonStrip steps={STEPS} />)
    expect(screen.getAllByRole('listitem')).toHaveLength(4)
    expect(screen.getByText('3. Data migration')).toBeInTheDocument()
  })

  it('shows an empty state for a journey with no steps', () => {
    render(<JourneyRibbonStrip steps={[]} />)
    expect(screen.getByText('No journey template')).toBeInTheDocument()
    expect(screen.queryByRole('list')).not.toBeInTheDocument()
  })

  it('starts the tab stop on the current step, not step 1', () => {
    render(<JourneyRibbonStrip steps={STEPS} />)
    const groups = screen.getAllByRole('group')
    // Only s3 (CURRENT) is announced as the current step.
    const current = groups.find((el) => el.getAttribute('aria-current') === 'step')
    expect(current).toHaveTextContent('Data migration')
    expect(current).toHaveAttribute('tabindex', '0')
  })

  it('moves the tab stop with the arrow keys and wraps at the ends', async () => {
    const user = userEvent.setup()
    render(<JourneyRibbonStrip steps={STEPS} />)

    // Tabbing in (rather than a raw `.focus()` call) lands on the current
    // step, Data migration — and keeps focus changes inside userEvent's own
    // `act()` wrapping, same as `components/ribbon/RibbonStrip.test.tsx`.
    await user.tab()
    const groups = screen.getAllByRole('group')
    expect(document.activeElement).toBe(groups[2])

    await user.keyboard('{ArrowRight}')
    expect(document.activeElement).toBe(groups[3])

    await user.keyboard('{ArrowRight}')
    expect(document.activeElement).toBe(groups[0])
  })

  it('fires onSelectStep with the clicked step when selection is wired', async () => {
    const onSelectStep = vi.fn()
    const user = userEvent.setup()
    render(<JourneyRibbonStrip steps={STEPS} onSelectStep={onSelectStep} />)

    await user.click(screen.getByRole('button', { name: /Requirements/ }))
    expect(onSelectStep).toHaveBeenCalledWith(STEPS[1])
  })
})
