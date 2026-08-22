import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import type { RibbonSegment as RibbonSegmentData } from '@/api/generated/model/ribbonSegment'
import { SegmentState } from '@/api/generated/model/segmentState'
import { CollapsedGroupTile } from './CollapsedGroupTile'

function seg(over: Partial<RibbonSegmentData> = {}): RibbonSegmentData {
  return {
    stageCode: 'BUILD',
    displayName: 'Build',
    state: SegmentState.COMPLETED,
    sequence: 4,
    iterationNo: 1,
    loopBackCount: 0,
    ...over,
  }
}

describe('CollapsedGroupTile', () => {
  it('is always a real button, unlike a read-only RibbonSegment', () => {
    render(<CollapsedGroupTile segments={[seg()]} expanded={false} onToggle={vi.fn()} />)
    expect(screen.getByRole('button')).toBeInTheDocument()
  })

  it('names the count and every hidden stage while collapsed', () => {
    render(
      <CollapsedGroupTile
        segments={[seg(), seg({ stageCode: 'QA', displayName: 'QA', sequence: 5 })]}
        expanded={false}
        onToggle={vi.fn()}
      />,
    )
    const button = screen.getByRole('button')
    expect(button).toHaveAccessibleName('2 completed stages collapsed: Build, QA')
    expect(button).toHaveAttribute('aria-expanded', 'false')
  })

  it('offers to collapse once expanded', () => {
    render(<CollapsedGroupTile segments={[seg()]} expanded onToggle={vi.fn()} />)
    const button = screen.getByRole('button')
    expect(button).toHaveAccessibleName('Collapse 1 completed stage: Build')
    expect(button).toHaveAttribute('aria-expanded', 'true')
  })

  it('fires onToggle on click', async () => {
    const user = userEvent.setup()
    const onToggle = vi.fn()
    render(<CollapsedGroupTile segments={[seg()]} expanded={false} onToggle={onToggle} />)

    await user.click(screen.getByRole('button'))
    expect(onToggle).toHaveBeenCalledOnce()
  })

  it('draws its trailing connector except on the last row', () => {
    const { rerender } = render(<CollapsedGroupTile segments={[seg()]} expanded={false} onToggle={vi.fn()} />)
    expect(screen.getByTestId('ribbon-connector')).toBeInTheDocument()

    rerender(<CollapsedGroupTile segments={[seg()]} expanded={false} onToggle={vi.fn()} isLast />)
    expect(screen.queryByTestId('ribbon-connector')).not.toBeInTheDocument()
  })

  it('forwards tabIndex and onFocus, the roving-focus contract every ribbon tile takes', () => {
    const onFocus = vi.fn()
    render(<CollapsedGroupTile segments={[seg()]} expanded={false} onToggle={vi.fn()} tabIndex={0} onFocus={onFocus} />)

    const button = screen.getByRole('button')
    expect(button).toHaveAttribute('tabindex', '0')
    button.focus()
    expect(onFocus).toHaveBeenCalledOnce()
  })
})
