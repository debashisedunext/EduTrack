import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'

import { SegmentState } from '@/api/generated/model/segmentState'
import { RibbonDots } from './RibbonDots'
import type { CompactDot } from './compactDots'

const DOTS: CompactDot[] = [
  { stageCode: 'INTAKE', label: 'Intake', ownerRole: 'SUPPORT', state: SegmentState.COMPLETED },
  { stageCode: 'TRIAGE', label: 'Triage / Planning', ownerRole: 'PM', state: SegmentState.COMPLETED },
  { stageCode: 'DEV', label: 'Development', ownerRole: 'DEVELOPER', state: SegmentState.CURRENT },
  { stageCode: 'QA', label: 'QA / Testing', ownerRole: 'QA', state: SegmentState.PENDING },
]

function dotFor(stageCode: string): HTMLElement {
  return document.querySelector(`[data-stage="${stageCode}"]`) as HTMLElement
}

describe('B-051 · RibbonDots', () => {
  it('renders one mark per stage', () => {
    render(<RibbonDots dots={DOTS} />)
    expect(document.querySelectorAll('[data-stage]')).toHaveLength(4)
  })

  it('is one image with one accessible name, not eight', () => {
    // Eight focusable marks × 25 rows is 200 stops between a keyboard reader
    // and the bottom of the page, for a cell with nothing to activate.
    render(<RibbonDots dots={DOTS} />)
    expect(
      screen.getByRole('img', { name: 'Journey: Development (Developer), stage 3 of 4. 2 completed' }),
    ).toBeInTheDocument()
    expect(screen.getAllByRole('img')).toHaveLength(1)
  })

  it('names the stage and its owner on each dot, as §S-17 asks of a hover', () => {
    render(<RibbonDots dots={DOTS} />)
    expect(dotFor('DEV')).toHaveAttribute('title', 'Development — Developer · current stage')
    expect(dotFor('QA')).toHaveAttribute('title', 'QA / Testing — QA · not started')
  })

  it('distinguishes the four states by shape as well as colour', () => {
    // `tokens.css`'s ribbon block and CLAUDE.md both require a second channel,
    // and a 7px dot has no room for an icon or a word. If these class
    // assertions ever collapse to differing only in the colour token, the
    // ribbon has stopped being readable in greyscale.
    render(
      <RibbonDots
        dots={[
          ...DOTS,
          { stageCode: 'SIGNOFF', label: 'Sign-off', ownerRole: 'PM', state: SegmentState.REWORKED },
        ]}
      />,
    )
    expect(dotFor('INTAKE').className).toContain('bg-ribbon-done')
    expect(dotFor('DEV').className).toContain('ring-2')
    expect(dotFor('QA').className).toContain('border-ribbon-pending')
    expect(dotFor('QA').className).toContain('bg-transparent')
    // The diamond — a filled mark rotated 45°, so "sent back" survives
    // greyscale and a printed screenshot.
    expect(dotFor('SIGNOFF').className).toContain('rotate-45')
  })

  it('falls back to the pending mark for a state it cannot read', () => {
    // `SegmentState` is a wire enum; a server that grows a seventh state ships
    // it to a client built against six. The neutral mark is the only answer
    // that neither throws inside a grid nor makes a claim.
    render(
      <RibbonDots
        dots={[{ stageCode: 'X', label: 'Unknown', state: 'INVENTED' as SegmentState }]}
      />,
    )
    expect(dotFor('X').className).toContain('border-ribbon-pending')
  })

  it('reads a finished journey as finished', () => {
    render(
      <RibbonDots dots={DOTS.map((dot) => ({ ...dot, state: SegmentState.COMPLETED }))} />,
    )
    expect(screen.getByRole('img', { name: 'Journey: all 4 stages completed' })).toBeInTheDocument()
  })
})
