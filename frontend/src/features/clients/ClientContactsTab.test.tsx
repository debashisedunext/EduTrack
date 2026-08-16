import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import { getDb } from '@/mocks/db'
import { Toaster } from '@/components/ui/toaster'

import { ClientContactsTab } from './ClientContactsTab'

/**
 * B-027 · the child grid against the mock server.
 *
 * The behaviours worth a test are the ones a screenshot would not show: that a
 * removed contact is still rendered and a promotion demotes the previous
 * primary, that the confirmation says what removing the primary will cost, and
 * that a duplicate email lands on the input rather than in a banner.
 *
 * Rendered on its own rather than through `ClientFormPage`, so a failure names
 * this component. The one thing that *needs* the parent — that every button here
 * is `type="button"` and so cannot submit the client form — is asserted in
 * `ClientFormPage.test.tsx`, where there is a form to submit.
 */
function renderTab(clientId: number | null) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <ClientContactsTab clientId={clientId} />
      <Toaster />
    </QueryClientProvider>,
  )
}

/** Several round trips through MSW per mutation; the 1 s default is not enough. */
const SLOW = { timeout: 5000 }

/**
 * Named rather than "the list", because the `Toaster` rendered beside the tab is
 * also a `list` — `findByRole('list')` matches it first and every `within` on
 * the result then searches an empty `<ol>`.
 */
async function openTab(clientId: number) {
  renderTab(clientId)
  return screen.findByRole('list', { name: 'Contacts' }, SLOW)
}

describe('the contacts grid', () => {
  /**
   * The grid reads `?includeInactive=true` where every picker reads the default,
   * and the fixture has a removed contact at Acme precisely so the two can be
   * told apart. Hiding removed rows would mean an administrator watching
   * somebody vanish with no way to tell "removed" from "never existed" — and no
   * way to see that the address is still spoken for.
   */
  it('renders removed contacts, marked, alongside the live ones', async () => {
    await openTab(1)

    expect(await screen.findByText('Sara Kapoor', undefined, SLOW)).toBeInTheDocument()
    expect(screen.getByText('Ravi Menon')).toBeInTheDocument()
    expect(screen.getByText('Removed')).toBeInTheDocument()
    expect(screen.getByText(/1 removed/)).toBeInTheDocument()
  })

  /** Live rows first, removed at the bottom — the server's own ORDER BY. */
  it('sorts live contacts above removed ones', async () => {
    const list = await openTab(1)
    await screen.findByText('Sara Kapoor', undefined, SLOW)

    const names = within(list)
      .getAllByRole('listitem')
      .map((item) => item.textContent ?? '')

    expect(names[names.length - 1]).toContain('Ravi Menon')
  })

  /**
   * B-028's gate, reported and not enforced. Kestrel is the fixture's PROSPECT
   * with no contacts at all, which is the state every client is created in.
   */
  it('warns when the client has no primary contact', async () => {
    renderTab(4)

    expect(await screen.findByText(/No primary contact/, undefined, SLOW)).toBeInTheDocument()
  })

  it('says contacts come after the client when there is no client yet', () => {
    renderTab(null)

    expect(screen.getByText(/Contacts come after the client/)).toBeInTheDocument()
  })
})

describe('adding a contact', () => {
  it('adds it and shows it in the grid', async () => {
    await openTab(2)
    fireEvent.click(await screen.findByRole('button', { name: /Add contact/ }, SLOW))

    fireEvent.change(await screen.findByLabelText(/^Name/, undefined, SLOW), {
      target: { value: 'Priya Nair' },
    })
    fireEvent.change(screen.getByLabelText(/^Email/), {
      target: { value: 'priya@northwind.example' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Add contact' }))

    expect(await screen.findByText('Priya Nair', undefined, SLOW)).toBeInTheDocument()
  })

  /**
   * The one queried rule, and it has to land on the input rather than in a
   * banner — an admin fixing an address should be looking at the box they have
   * to change. The message names who holds it, because "already in use" on a
   * client with nine contacts is a search rather than a fix.
   */
  it('marks the email input when the address is already used at this client', async () => {
    await openTab(1)
    fireEvent.click(await screen.findByRole('button', { name: /Add contact/ }, SLOW))

    fireEvent.change(await screen.findByLabelText(/^Name/, undefined, SLOW), {
      target: { value: 'Somebody Else' },
    })
    fireEvent.change(screen.getByLabelText(/^Email/), {
      // Cased differently on purpose: the server matches through
      // `utf8mb4_0900_ai_ci` and the mock mirrors it.
      target: { value: 'SARA@ACME.EXAMPLE' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Add contact' }))

    expect(await screen.findByText(/Sara Kapoor already uses/, undefined, SLOW))
      .toBeInTheDocument()
  })
})

describe('the primary flag', () => {
  /**
   * Single-writer, and the schema cannot assert it: MySQL has no partial unique
   * index, so this rule exists only in the service — and in the mock, which is
   * what this asserts agrees with it.
   */
  it('promoting one demotes the previous primary', async () => {
    await openTab(1)
    await screen.findByText('Dev Patel', undefined, SLOW)

    fireEvent.click(screen.getByRole('button', { name: 'Make primary' }))

    await waitFor(() => {
      const contacts = getDb().contacts.filter((c) => c.clientId === 1 && c.isPrimary)
      expect(contacts).toHaveLength(1)
      expect(contacts[0].name).toBe('Dev Patel')
    }, SLOW)
  })

  /** Nothing to promote when they already hold it. */
  it('offers no promotion on the contact that is already primary', async () => {
    const list = await openTab(3)
    await screen.findByText('Erin Walsh', undefined, SLOW)

    expect(within(list).queryByRole('button', { name: 'Make primary' })).toBeNull()
  })
})

describe('removing a contact', () => {
  /**
   * Removal deactivates — `tickets.client_contact_id` is a foreign key with no
   * cascade — so the row survives and the confirmation says so. An administrator
   * who believes they are destroying the record of who reported a ticket will
   * not press the button, and the record is exactly what §4B.2 protects.
   */
  it('says historical tickets keep the name, then deactivates rather than deletes', async () => {
    await openTab(2)
    await screen.findByText('Tom Fletcher', undefined, SLOW)

    fireEvent.click(screen.getByRole('button', { name: 'Remove Tom Fletcher' }))
    expect(await screen.findByText(/keep their name/, undefined, SLOW)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Remove contact' }))

    await waitFor(() => {
      const contact = getDb().contacts.find((c) => c.name === 'Tom Fletcher')
      expect(contact).toBeDefined()
      expect(contact?.isActive).toBe(false)
    }, SLOW)
  })

  /**
   * Removing the last primary is **allowed** — the person may simply have left,
   * and a contact who cannot be removed until somebody else is promoted reads as
   * a broken button. What the screen owes is the consequence, stated before the
   * click rather than discovered on the ticket form.
   */
  it('warns that the client stops being selectable when the primary is removed', async () => {
    await openTab(3)
    await screen.findByText('Erin Walsh', undefined, SLOW)

    fireEvent.click(screen.getByRole('button', { name: 'Remove Erin Walsh' }))

    expect(await screen.findByText(/will not be selectable on a ticket/, undefined, SLOW))
      .toBeInTheDocument()
  })

  /** A removed contact is editable — but there is no un-remove, and it says so. */
  it('offers no way to restore a removed contact', async () => {
    await openTab(1)
    await screen.findByText('Ravi Menon', undefined, SLOW)

    expect(screen.queryByRole('button', { name: 'Remove Ravi Menon' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Edit Ravi Menon' })).toBeInTheDocument()
    expect(screen.getByText(/was removed and cannot be restored/)).toBeInTheDocument()
  })
})

describe('editing a contact', () => {
  /**
   * The editor is reused for every row and for the add path, so it has to reset
   * on every open. Seeding it once would show the previous contact's values —
   * and, on the add path after an edit, would silently submit them as a new
   * contact.
   */
  it('seeds the editor from the row that was clicked', async () => {
    await openTab(1)
    await screen.findByText('Dev Patel', undefined, SLOW)

    fireEvent.click(screen.getByRole('button', { name: 'Edit Dev Patel' }))
    expect(await screen.findByDisplayValue('Dev Patel', undefined, SLOW)).toBeInTheDocument()
    expect(screen.getByDisplayValue('Helpdesk Lead')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    fireEvent.click(screen.getByRole('button', { name: /Add contact/ }))
    await waitFor(() => {
      expect(screen.getByLabelText(/^Name/)).toHaveValue('')
    }, SLOW)
  })

  it('saves the change', async () => {
    await openTab(1)
    await screen.findByText('Dev Patel', undefined, SLOW)

    fireEvent.click(screen.getByRole('button', { name: 'Edit Dev Patel' }))
    fireEvent.change(await screen.findByLabelText(/Designation/, undefined, SLOW), {
      target: { value: 'Service Desk Manager' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Save contact' }))

    await waitFor(() => {
      expect(getDb().contacts.find((c) => c.name === 'Dev Patel')?.designation)
        .toBe('Service Desk Manager')
    }, SLOW)
  })

  /**
   * The body is the whole representation, so an edit that omitted an untouched
   * field would clear it. `toContactWriteRequest` sends every one — asserted
   * here from the browser end as well as in `contactForm.test.ts`, because this
   * is where the round trip actually happens.
   */
  it('does not clear the fields it did not touch', async () => {
    await openTab(1)
    await screen.findByText('Sara Kapoor', undefined, SLOW)

    fireEvent.click(screen.getByRole('button', { name: 'Edit Sara Kapoor' }))
    fireEvent.change(await screen.findByLabelText(/^Name/, undefined, SLOW), {
      target: { value: 'Sara Kapoor-Rao' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Save contact' }))

    await waitFor(() => {
      const contact = getDb().contacts.find((c) => c.id === 1)
      expect(contact?.name).toBe('Sara Kapoor-Rao')
      expect(contact?.designation).toBe('IT Director')
      expect(contact?.phone).toBe('+91 98200 11111')
      expect(contact?.portalAccess).toBe(true)
    }, SLOW)
  })
})
