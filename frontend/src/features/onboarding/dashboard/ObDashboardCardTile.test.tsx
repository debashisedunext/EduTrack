import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'

import type { ObDashboardCard } from '@/api/generated/model'

import { ObDashboardCardTile } from './ObDashboardCardTile'

const card = (over: Partial<ObDashboardCard> = {}): ObDashboardCard => ({
  key: 'overdue-clients',
  count: 4,
  ...over,
})

describe('ObDashboardCardTile', () => {
  it('shows the label and the figure', () => {
    render(<ObDashboardCardTile card={card()} />)

    expect(screen.getByText('Overdue clients')).toBeInTheDocument()
    expect(screen.getByText('4')).toBeInTheDocument()
  })

  /**
   * A dead control is worse than none: it teaches the user the board is broken.
   * B-127 passes the handler; until then the tile is a region.
   */
  it('is not a button until something can be opened', () => {
    render(<ObDashboardCardTile card={card()} />)

    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.getByRole('group')).toBeInTheDocument()
  })

  it('becomes a button once a handler is given, and passes the card back', () => {
    const onOpen = vi.fn()
    const subject = card()
    render(<ObDashboardCardTile card={subject} onOpen={onOpen} />)

    fireEvent.click(screen.getByRole('button'))

    expect(onOpen).toHaveBeenCalledWith(subject)
  })

  /**
   * An unavailable card has nothing to open, so it stays a region even when the
   * board is otherwise interactive — pressing it could only produce an empty
   * panel.
   */
  it('stays inert when the card carries no number, handler or not', () => {
    render(
      <ObDashboardCardTile
        card={card({ count: 0, unavailableReason: 'No scope dimension yet.' })}
        onOpen={vi.fn()}
      />,
    )

    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  /**
   * The sentence replaces the number rather than sitting under a zero. A zero
   * renders as "nothing is overdue", which is a factual claim and a false one.
   */
  it('shows the reason instead of a zero when the scope cannot be answered', () => {
    render(
      <ObDashboardCardTile
        card={card({ count: 0, unavailableReason: 'The board counts journeys containing your services.' })}
      />,
    )

    expect(screen.getByText('The board counts journeys containing your services.')).toBeInTheDocument()
    expect(screen.queryByText('0')).not.toBeInTheDocument()
  })

  /**
   * The defect this whole flag exists for, at the surface a user sees it: the
   * figure is a ceiling, and the tile says so on screen, on hover and in the
   * accessible name.
   */
  it('marks an upper bound and explains it', () => {
    render(<ObDashboardCardTile card={card({ countIsUpperBound: true })} />)

    expect(screen.getByText('≈')).toBeInTheDocument()
    expect(screen.getByRole('group')).toHaveAccessibleName(/at most 4/)
    expect(screen.getByRole('group').getAttribute('title')).toMatch(/once per product/)
  })

  it('leaves an exact figure unmarked', () => {
    render(<ObDashboardCardTile card={card()} />)

    expect(screen.queryByText('≈')).not.toBeInTheDocument()
    expect(screen.getByRole('group').getAttribute('title')).toBeNull()
  })

  it('draws a delta with its direction', () => {
    render(<ObDashboardCardTile card={card({ deltaFromYesterday: -2 })} />)

    expect(screen.getByText(/▼\s*2/)).toBeInTheDocument()
  })

  it('draws no delta at all on the first day a deployment has data', () => {
    render(<ObDashboardCardTile card={card({ deltaFromYesterday: null })} />)

    expect(screen.queryByText(/[▲▼→]/)).not.toBeInTheDocument()
  })
})
