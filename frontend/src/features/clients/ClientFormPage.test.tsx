import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import { getDb } from '@/mocks/db'
import { Toaster } from '@/components/ui/toaster'

import { ClientFormPage } from './ClientFormPage'

/**
 * B-026 · S-33 against the mock server.
 *
 * The behaviours worth a test are the ones a screenshot would not show: that a
 * tab change does not discard what was typed on another tab, that a server error
 * naming a hidden field opens the tab it is on, and that the `If-Match` the
 * `PATCH` requires comes from the read rather than from a wildcard.
 */
function renderForm(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/masters/clients/new" element={<ClientFormPage />} />
          <Route path="/masters/clients/:clientId/edit" element={<ClientFormPage />} />
          <Route path="/masters/clients" element={<div>Client grid</div>} />
        </Routes>
        <Toaster />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** Three round trips through MSW per mutation; the 1 s default is not enough. */
const SLOW = { timeout: 5000 }

const tab = (name: string) => screen.getByRole('tab', { name })

async function openCreate() {
  renderForm('/masters/clients/new')
  return screen.findByLabelText(/Client code/, undefined, SLOW)
}

async function openEdit(clientId: number) {
  renderForm(`/masters/clients/${clientId}/edit`)
  return screen.findByDisplayValue('Acme Retail Ltd', undefined, SLOW)
}

describe('the four tabs', () => {
  it('shows Identity first and offers all four', async () => {
    await openCreate()

    expect(tab('Identity')).toHaveAttribute('aria-selected', 'true')
    for (const name of ['Identity', 'Commercial', 'Contacts', 'Projects & SLA']) {
      expect(tab(name)).toBeInTheDocument()
    }
  })

  /**
   * The reason the tabs are `hidden` panels rather than unmounted ones.
   * react-hook-form unregisters an input that leaves the DOM, so unmounting a
   * tab would drop its values from a payload that sends every field — and the
   * bug would stay invisible until somebody edited a client's address and found
   * their notes cleared.
   */
  it('keeps what was typed on a tab that is not showing', async () => {
    const code = await openCreate()
    fireEvent.change(code, { target: { value: 'NEWCO' } })

    fireEvent.click(tab('Commercial'))
    fireEvent.change(screen.getByLabelText(/Billing reference/), {
      target: { value: 'PO-9' },
    })

    fireEvent.click(tab('Identity'))
    expect(screen.getByLabelText(/Client code/)).toHaveValue('NEWCO')

    fireEvent.click(tab('Commercial'))
    expect(screen.getByLabelText(/Billing reference/)).toHaveValue('PO-9')
  })

  it('moves between tabs with the arrow keys, as a tablist promises', async () => {
    await openCreate()

    fireEvent.keyDown(tab('Identity'), { key: 'ArrowRight' })
    expect(tab('Commercial')).toHaveAttribute('aria-selected', 'true')

    fireEvent.keyDown(tab('Commercial'), { key: 'ArrowLeft' })
    expect(tab('Identity')).toHaveAttribute('aria-selected', 'true')
  })
})

describe('creating', () => {
  it('upper-cases the code as it is typed and saves the client', async () => {
    const code = await openCreate()

    fireEvent.change(code, { target: { value: 'newco' } })
    expect(code).toHaveValue('NEWCO')
    fireEvent.change(screen.getByLabelText(/Client name/), { target: { value: 'Newco Ltd' } })

    fireEvent.click(screen.getByRole('button', { name: 'Create client' }))

    await waitFor(
      () => expect(getDb().clients.some((c) => c.clientCode === 'NEWCO')).toBe(true),
      SLOW,
    )
    expect(await screen.findByText('Client grid', undefined, SLOW)).toBeInTheDocument()
  })

  /**
   * A created client cannot have a contact — there is no id to hang one off
   * until the save returns — so the Contacts tab says so rather than rendering
   * an empty grid that reads as a fetch that failed.
   */
  it('explains on the Contacts tab that contacts come after the client', async () => {
    await openCreate()

    fireEvent.click(tab('Contacts'))

    expect(await screen.findByText(/Contacts come after the client/)).toBeInTheDocument()
  })

  it('refuses a duplicate client code and marks the field', async () => {
    const code = await openCreate()

    fireEvent.change(code, { target: { value: 'ACME' } })
    fireEvent.change(screen.getByLabelText(/Client name/), { target: { value: 'Acme Again' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create client' }))

    expect(await screen.findByText(/already in use/, undefined, SLOW)).toBeInTheDocument()
  })

  /**
   * The specific failure a four-tab form makes easy: a server error naming a
   * field on a tab the admin cannot see marks an invisible input, and the save
   * reads as having done nothing at all.
   */
  it('opens the tab a server error belongs to', async () => {
    const code = await openCreate()

    fireEvent.change(code, { target: { value: 'ZONECO' } })
    fireEvent.change(screen.getByLabelText(/Client name/), { target: { value: 'Zoneco' } })
    fireEvent.change(screen.getByLabelText(/Time zone/), { target: { value: 'Mars/Olympus' } })

    // Sit on a different tab, so the refusal has somewhere wrong to land.
    fireEvent.click(tab('Commercial'))
    fireEvent.click(screen.getByRole('button', { name: 'Create client' }))

    await waitFor(() => expect(tab('Identity')).toHaveAttribute('aria-selected', 'true'), SLOW)
    expect(screen.getByText(/is not a known time zone/)).toBeInTheDocument()
  })
})

describe('editing', () => {
  it('loads every tab from the client, not only the first', async () => {
    await openEdit(1)

    expect(screen.getByLabelText(/Short name/)).toHaveValue('Acme')
    expect(screen.getByLabelText(/Website \/ domain/)).toHaveValue('acme.example')

    fireEvent.click(tab('Commercial'))
    expect(screen.getByLabelText(/Billing reference/)).toHaveValue('PO-2025-0142')
    expect(screen.getByText('retail')).toBeInTheDocument()

    fireEvent.click(tab('Projects & SLA'))
    expect(await screen.findByRole('checkbox', { name: /CRM/ })).toBeChecked()
  })

  /**
   * The `PATCH` requires `If-Match` and the mock refuses a request without one
   * with 428 — so a save that succeeds is proof the tag came off the detail read
   * rather than being wildcarded away.
   */
  it('saves with the ETag it read, not with a wildcard', async () => {
    await openEdit(1)

    fireEvent.change(screen.getByLabelText(/Client name/), {
      target: { value: 'Acme Retail Limited' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Save client' }))

    await waitFor(
      () => expect(getDb().clients.find((c) => c.id === 1)?.name).toBe('Acme Retail Limited'),
      SLOW,
    )
  })

  /**
   * §4B.2's Identity group has three states, and this is the one B-025 could not
   * represent. A prospect stays selectable on the ticket form — `isActive`
   * derives as "not INACTIVE" — which is why the status has to be settable here
   * rather than through the grid's activate/deactivate.
   */
  it('can turn a client into a prospect', async () => {
    await openEdit(1)

    // Held before the listbox opens: radix marks the rest of the page
    // `aria-hidden` while a Select is open, so a query made afterwards would not
    // reach the Save button.
    const save = screen.getByRole('button', { name: 'Save client' })

    // Radix portals its listbox to `document.body`, so the option is queried
    // globally — the same shape `MyTasksPage.test.tsx` uses.
    fireEvent.click(screen.getByRole('combobox', { name: /Status/ }))
    await waitFor(() => fireEvent.click(screen.getByRole('option', { name: 'Prospect' })), SLOW)
    fireEvent.click(save)

    await waitFor(
      () => expect(getDb().clients.find((c) => c.id === 1)?.status).toBe('PROSPECT'),
      SLOW,
    )
  })

  /**
   * The domain feeds D-039's inbound-mail matching, which looks up a bare host
   * taken from a sender address. Stored as typed, the two never meet and
   * attribution silently stops working for that client.
   */
  it('strips the scheme and www from the domain on save', async () => {
    await openEdit(1)

    fireEvent.change(screen.getByLabelText(/Website \/ domain/), {
      target: { value: 'https://www.Acme.Example/support' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Save client' }))

    await waitFor(
      () => expect(getDb().clients.find((c) => c.id === 1)?.domain).toBe('acme.example'),
      SLOW,
    )
  })

  it('replaces the project mapping, including clearing it', async () => {
    await openEdit(1)
    fireEvent.click(tab('Projects & SLA'))

    const crm = await screen.findByRole('checkbox', { name: /CRM/ })
    fireEvent.click(crm)
    const web = screen.getByRole('checkbox', { name: /WEB/ })
    if ((web as HTMLInputElement).checked) fireEvent.click(web)

    fireEvent.click(screen.getByRole('button', { name: 'Save client' }))

    await waitFor(
      () => expect(getDb().clientProjects.filter((cp) => cp.clientId === 1)).toHaveLength(0),
      SLOW,
    )
  })

  /**
   * Unmapping the project that was the default clears the default rather than
   * leaving it pointing at a project the client is no longer on — which would be
   * a validation failure the admin cannot see, because the control that would
   * have shown it has just been unchecked.
   */
  it('clears the default when its project is unmapped', async () => {
    await openEdit(1)
    fireEvent.click(tab('Projects & SLA'))

    // Scoped to CRM's own row rather than taken by position — the project list
    // is ordered by the server, not by this test.
    const crmRow = (await screen.findByRole('checkbox', { name: /CRM/ })).closest(
      'li',
    ) as HTMLElement
    const crmDefault = within(crmRow).getByRole('radio', { name: 'Default' })
    expect(crmDefault).toBeChecked()

    fireEvent.click(within(crmRow).getByRole('checkbox', { name: /CRM/ }))
    expect(crmDefault).not.toBeChecked()
    expect(crmDefault).toBeDisabled()
  })
})

describe('the Contacts tab', () => {
  it('lists the contacts and marks the primary one', async () => {
    await openEdit(1)

    fireEvent.click(tab('Contacts'))

    // Scoped to the panel: the tab strip is a `<ul>` too, so an unscoped
    // `getByRole('list')` matches two.
    const panel = screen.getByRole('tabpanel', { hidden: false })
    expect(await within(panel).findByText('Sara Kapoor', undefined, SLOW)).toBeInTheDocument()
    expect(within(panel).getByText('Primary')).toBeInTheDocument()
  })

  /**
   * B-028's gate, stated rather than enforced: a client without a primary
   * contact is not selectable on a ticket, and the tab is where an admin can see
   * that before they wonder why the client is missing from the create form.
   */
  it('warns when the client has no primary contact', async () => {
    renderForm('/masters/clients/5/edit')
    await screen.findByDisplayValue('Kestrel Analytics', undefined, SLOW)

    fireEvent.click(tab('Contacts'))

    expect(
      await screen.findByText(/cannot be chosen on a ticket/, undefined, SLOW),
    ).toBeInTheDocument()
  })
})

describe('the SLA field', () => {
  /**
   * Nothing reads `clients.sla_policy_id` — C-012's ladder resolves
   * org → project → task type and never consults it. A picker here would write
   * a number nobody looks at, which is worse than no control; the tab says where
   * SLA actually comes from instead.
   */
  it('is read-only, and says why', async () => {
    await openEdit(1)
    fireEvent.click(tab('Projects & SLA'))

    expect(screen.getByText('Default SLA policy')).toBeInTheDocument()
    expect(screen.getByText(/Nothing resolves a client-level policy today/)).toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: /SLA/ })).not.toBeInTheDocument()
  })
})
