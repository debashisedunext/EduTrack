import { describe, expect, it, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import type { Me } from '@/api/generated/model'
import { initialAuthState, useAuthStore } from '@/features/auth/authStore'

import { MastersIndexPage } from './MastersIndexPage'

/**
 * B-067 · the index `/masters` renders now, replacing the placeholder's
 * hand-made link list.
 *
 * <p>The permission-filtering case is the one worth pinning: eight of the nine
 * master list routes are `isAuthenticated()`, all six roles, and only
 * notification templates is `master.write` end to end — so a Developer
 * reaching this page directly (the sidebar hides the nav entry, `RequireAuth`
 * does not hide the route) must see every card except that one, not a page
 * gated to Admin alone.
 */

const ADMIN: Me = { id: 1, displayName: 'Priya Nair', role: 'ADMIN', permissions: ['master.write'] }
const DEVELOPER: Me = { id: 4, displayName: 'Ravi Kumar', role: 'DEVELOPER', permissions: [] }

beforeEach(() => useAuthStore.setState(initialAuthState))

function renderIndexAs(user: Me | null) {
  useAuthStore.setState({ status: user ? 'authenticated' : 'anonymous', user })
  return render(
    <MemoryRouter>
      <MastersIndexPage />
    </MemoryRouter>,
  )
}

describe('MastersIndexPage', () => {
  it('links every master a signed-in role may open', () => {
    renderIndexAs(DEVELOPER)

    for (const [label, href] of [
      ['Resources', '/masters/resources'],
      ['Roles & permissions', '/masters/roles'],
      ['Projects', '/masters/projects'],
      ['Priority levels', '/masters/priorities'],
      ['Task types', '/masters/task-types'],
      ['Statuses & workflow', '/masters/statuses'],
      ['Working calendar', '/masters/calendar'],
      ['Clients', '/masters/clients'],
    ] as const) {
      expect(screen.getByRole('link', { name: new RegExp(label) })).toHaveAttribute('href', href)
    }
  })

  it('hides notification templates from a role without master.write', () => {
    renderIndexAs(DEVELOPER)

    expect(screen.queryByRole('link', { name: /Notification templates/ })).not.toBeInTheDocument()
  })

  it('shows notification templates to a role that holds master.write', () => {
    renderIndexAs(ADMIN)

    expect(screen.getByRole('link', { name: /Notification templates/ })).toHaveAttribute(
      'href',
      '/masters/notification-templates',
    )
  })

  it('does not link a master with no screen of its own', () => {
    // /masters/modules has no page — B-064 built it as a picker source, not a
    // screen — so it must not appear as a dead link here either.
    renderIndexAs(ADMIN)

    expect(screen.queryByRole('link', { name: /Modules/ })).not.toBeInTheDocument()
  })

  it('does not offer a nav entry for the workflow designer', () => {
    // B-043: reached from tab 3 of Statuses & workflow, not from a card here —
    // a second entry beside it would read as a competing master.
    renderIndexAs(ADMIN)

    expect(screen.queryByRole('link', { name: /workflow designer/i })).not.toBeInTheDocument()
  })
})
