import { useState } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import { Tabs, type TabItem } from './tabs'

const TABS: TabItem[] = [
  { id: 'a', label: 'Tab A', content: <p>Content A</p> },
  { id: 'b', label: 'Tab B', content: <p>Content B</p> },
  { id: 'c', label: 'Tab C', content: <p>Content C</p> },
]

function ControlledTabs() {
  const [activeId, setActiveId] = useState('a')
  return <Tabs tabs={TABS} activeId={activeId} onSelect={setActiveId} ariaLabel="Test tabs" />
}

describe('Tabs', () => {
  it('marks only the active tab as selected and shows its panel', () => {
    render(<ControlledTabs />)

    expect(screen.getByRole('tab', { name: 'Tab A' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: 'Tab B' })).toHaveAttribute('aria-selected', 'false')
    expect(screen.getByRole('tabpanel')).toHaveTextContent('Content A')
  })

  it('only the selected tab is in the tab order — roving tabindex', () => {
    render(<ControlledTabs />)

    expect(screen.getByRole('tab', { name: 'Tab A' })).toHaveAttribute('tabindex', '0')
    expect(screen.getByRole('tab', { name: 'Tab B' })).toHaveAttribute('tabindex', '-1')
    expect(screen.getByRole('tab', { name: 'Tab C' })).toHaveAttribute('tabindex', '-1')
  })

  it('selects a tab on click', async () => {
    const user = userEvent.setup()
    render(<ControlledTabs />)

    await user.click(screen.getByRole('tab', { name: 'Tab B' }))

    expect(screen.getByRole('tab', { name: 'Tab B' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tabpanel')).toHaveTextContent('Content B')
  })

  it('ArrowRight moves to the next tab and wraps at the end', async () => {
    const user = userEvent.setup()
    render(<ControlledTabs />)

    screen.getByRole('tab', { name: 'Tab A' }).focus()
    await user.keyboard('{ArrowRight}')
    expect(screen.getByRole('tab', { name: 'Tab B' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: 'Tab B' })).toHaveFocus()

    await user.keyboard('{ArrowRight}{ArrowRight}')
    expect(screen.getByRole('tab', { name: 'Tab A' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: 'Tab A' })).toHaveFocus()
  })

  it('ArrowLeft moves to the previous tab and wraps at the start', async () => {
    const user = userEvent.setup()
    render(<ControlledTabs />)

    screen.getByRole('tab', { name: 'Tab A' }).focus()
    await user.keyboard('{ArrowLeft}')

    expect(screen.getByRole('tab', { name: 'Tab C' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: 'Tab C' })).toHaveFocus()
  })

  it('Home and End jump to the first and last tab', async () => {
    const user = userEvent.setup()
    render(<ControlledTabs />)

    screen.getByRole('tab', { name: 'Tab A' }).focus()
    await user.keyboard('{End}')
    expect(screen.getByRole('tab', { name: 'Tab C' })).toHaveAttribute('aria-selected', 'true')

    await user.keyboard('{Home}')
    expect(screen.getByRole('tab', { name: 'Tab A' })).toHaveAttribute('aria-selected', 'true')
  })

  it('gives each rendered instance its own ids, so two on one page never collide', () => {
    function TwoTabs() {
      const [left, setLeft] = useState('a')
      const [right, setRight] = useState('a')
      return (
        <>
          <Tabs tabs={TABS} activeId={left} onSelect={setLeft} ariaLabel="Left" />
          <Tabs tabs={TABS} activeId={right} onSelect={setRight} ariaLabel="Right" />
        </>
      )
    }
    render(<TwoTabs />)

    const [leftTabA, rightTabA] = screen.getAllByRole('tab', { name: 'Tab A' })
    expect(leftTabA.id).not.toBe(rightTabA.id)
  })
})
