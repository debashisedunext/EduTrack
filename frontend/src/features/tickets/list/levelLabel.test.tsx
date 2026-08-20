import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'

import { Chip } from '@/components/ui/chip'

import { LEVEL_VARIANT, levelLabel } from './columns'

/**
 * D-066 · `Level` is a code from the S-12 master, not a four-value enum, so
 * every table keyed on it is partial. These are the two degradations that
 * partiality produces, pinned so neither becomes a blank on screen the first
 * time an Admin uses the button §9 promises them.
 */
describe('a level the seeded vocabulary has never seen', () => {
  it('still renders a chip, carrying its own code', () => {
    // `LEVEL_VARIANT` answers undefined and `Chip`'s cva default takes over.
    // That is the whole mechanism — asserted here rather than assumed, because
    // it is one `defaultVariants` line away from being a blank chip.
    render(<Chip variant={LEVEL_VARIANT['BLOCKER']}>BLOCKER</Chip>)

    const chip = screen.getByText('BLOCKER')
    expect(chip).toBeInTheDocument()
    expect(chip.className).not.toBe('')
  })

  it('gets a readable label rather than an empty filter row', () => {
    // The one case the neutral chip does not cover: a dropdown option with no
    // text is unreadable and cannot be named.
    expect(levelLabel('BLOCKER')).toBe('Blocker')
    expect(levelLabel('VERY_HIGH')).toBe('Very high')
  })

  it('leaves the four seeded levels exactly as they were', () => {
    expect(levelLabel('LOW')).toBe('Low')
    expect(levelLabel('CRITICAL')).toBe('Critical')
    expect(LEVEL_VARIANT['CRITICAL']).toBe('critical')
  })
})
