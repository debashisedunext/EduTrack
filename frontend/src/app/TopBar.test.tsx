import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'

import { TopBar } from './TopBar'

/**
 * Which of the top bar's controls belong to the ticketing module, and which
 * belong to the application.
 *
 * The search field and the project switcher are ticketing's: the first
 * navigates to `/tickets`, the second picks a ticketing project that no
 * `/onboarding` screen reads. The module switcher, bell, chat and avatar are
 * the application's and stay everywhere.
 *
 * ## The children are stubbed, deliberately
 *
 * Every one of them fetches — projects, `/me`, notifications, chat — and this
 * file is about **composition**: which children the bar renders on which route.
 * Rendering the real ones would make each case depend on four unrelated
 * queries, and an assertion like "no element labelled project" would pass
 * whenever the fetch merely had not resolved, which is a test that cannot
 * fail. A stub with a testid is present or it is not.
 */
vi.mock('./ProjectSwitcher', () => ({
  ProjectSwitcher: () => <div data-testid="project-switcher" />,
}))
vi.mock('@/features/launcher/ModuleSwitcher', () => ({
  ModuleSwitcher: () => <div data-testid="module-switcher" />,
}))
vi.mock('./NotificationBell', () => ({ NotificationBell: () => <div data-testid="bell" /> }))
vi.mock('./ChatBadge', () => ({ ChatBadge: () => <div data-testid="chat" /> }))
vi.mock('./AvatarMenu', () => ({ AvatarMenu: () => <div data-testid="avatar" /> }))

/** Renders where the bar navigated to, so a submit can be asserted on. */
function Location() {
  const location = useLocation()
  return <span data-testid="location">{location.pathname + location.search}</span>
}

function renderAt(pathname: string) {
  return render(
    <MemoryRouter initialEntries={[pathname]}>
      <TopBar />
      <Location />
    </MemoryRouter>,
  )
}

describe('TopBar', () => {
  it('offers ticket search and the project switcher on a ticketing route', () => {
    renderAt('/tickets')

    expect(screen.getByPlaceholderText(/search ticket id/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /open command palette/i })).toBeInTheDocument()
    expect(screen.getByTestId('project-switcher')).toBeInTheDocument()
  })

  it('searches clients by name in the onboarding module, in the same place', () => {
    renderAt('/onboarding/clients')

    // Retargeted rather than removed: the position is where anybody looks for
    // a global search, and typing a school's name into a ticket search would
    // have navigated out of the module.
    expect(screen.getByPlaceholderText(/search client by name/i)).toBeInTheDocument()
    expect(screen.queryByPlaceholderText(/search ticket id/i)).not.toBeInTheDocument()

    // C-006's palette jumps to tickets, so its hint goes with the ticket
    // search rather than sitting beside a client one.
    expect(screen.queryByRole('button', { name: /open command palette/i })).not.toBeInTheDocument()
  })

  it('sends an onboarding search to the client list filter', async () => {
    const user = userEvent.setup()
    renderAt('/onboarding/dashboard')

    await user.type(screen.getByLabelText(/search clients by name/i), 'kiet{Enter}')

    // `?q=` is useObClientFilters' own parameter, so the list arrives already
    // filtered with the term still in its field.
    expect(screen.getByTestId('location')).toHaveTextContent('/onboarding/clients')
    expect(screen.getByTestId('location')).toHaveTextContent('q=kiet')
  })

  it('still sends a ticketing search to the ticket list', async () => {
    const user = userEvent.setup()
    renderAt('/tickets')

    await user.type(screen.getByLabelText(/search tickets/i), 'printer{Enter}')

    expect(screen.getByTestId('location')).toHaveTextContent('/tickets')
    expect(screen.getByTestId('location')).toHaveTextContent('q=printer')
  })

  it('hides the ticketing project switcher in the onboarding module', () => {
    renderAt('/onboarding/dashboard')

    // Onboarding is scoped by client and module role, never by project, so a
    // project selection here changes nothing on any screen the reader can see.
    expect(screen.queryByTestId('project-switcher')).not.toBeInTheDocument()
  })

  it('keeps the application-level controls in both modules', () => {
    renderAt('/onboarding/clients/47')

    // The module switcher especially: it is the way back out of onboarding,
    // so hiding it with the rest would strand the reader.
    expect(screen.getByTestId('module-switcher')).toBeInTheDocument()
    expect(screen.getByTestId('bell')).toBeInTheDocument()
    expect(screen.getByTestId('chat')).toBeInTheDocument()
    expect(screen.getByTestId('avatar')).toBeInTheDocument()
  })

  it('hides them on every onboarding route, not just the index', () => {
    renderAt('/onboarding/clients/47/journeys')

    expect(screen.queryByPlaceholderText(/search ticket id/i)).not.toBeInTheDocument()
    expect(screen.queryByTestId('project-switcher')).not.toBeInTheDocument()
  })
})
