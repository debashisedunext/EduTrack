import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { AvatarStack } from './avatar-stack'

/**
 * Regression coverage for the S-20 blank-screen bug: `TicketWire` was
 * serialising `reportedBy`/`assignedTo` as bare ids rather than the
 * contract's `UserRef`, so `PersonCell` handed this component a person with
 * `name: undefined`. `initials()` called `.split(' ')` on it uncaught, and
 * with no error boundary anywhere in the app that crashed the whole React
 * tree to a blank page for every role on every ticket. `initials()` now
 * guards against a missing name instead of assuming the caller's data is
 * shaped correctly — the same defence in depth an error boundary gives at
 * the page level, but one that stops the crash before it starts.
 */
describe('AvatarStack', () => {
  // Radix's `Avatar.Fallback` renders after its own `delayMs` timer even at
  // `delayMs={0}`, so the text arrives a tick after `render` — `findByText`,
  // not `getByText`.
  it('renders a legible fallback rather than throwing when a name is missing', async () => {
    render(<AvatarStack people={[{ id: '1', name: undefined as unknown as string }]} />)
    expect(await screen.findByText('?')).toBeInTheDocument()
  })

  it('still renders real initials for a well-formed person', async () => {
    render(<AvatarStack people={[{ id: '1', name: 'Nikhil Bansal' }]} />)
    expect(await screen.findByText('NB')).toBeInTheDocument()
  })
})
