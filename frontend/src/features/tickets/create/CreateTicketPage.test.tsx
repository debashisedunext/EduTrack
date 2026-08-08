import { beforeAll, beforeEach, afterEach, describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom'
import { server } from '@/mocks/server'
import { useCurrentProjectStore } from '@/app/currentProjectStore'
import { CreateTicketPage } from './CreateTicketPage'

/**
 * Radix's popup primitives measure and capture pointers, neither of which jsdom
 * implements. Polyfilled here rather than in `src/test/setup.ts` so the shim
 * stays next to the only suite that needs it.
 */
beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  const element = Element.prototype as unknown as Record<string, unknown>
  element.hasPointerCapture ??= () => false
  element.setPointerCapture ??= () => {}
  element.releasePointerCapture ??= () => {}
  element.scrollIntoView ??= () => {}
})

function TicketDetailStub() {
  const { ticketId } = useParams()
  return <p>Landed on {ticketId}</p>
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/tickets/new']}>
        <Routes>
          <Route path="/tickets/new" element={<CreateTicketPage />} />
          <Route path="/tickets/:ticketId" element={<TicketDetailStub />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** The form skeletons until the project and task-type masters land. */
const formReady = () => screen.findByRole('button', { name: 'Create ticket' })

/**
 * Open a Radix popup and wait for the option to actually be there.
 *
 * Two things make a single click unreliable under jsdom, neither of which a
 * person hits. Closing a popover restores focus to its trigger a frame later,
 * which the next popover — already open by then — reads as focus moving
 * outside itself and dismisses on. And a dropdown whose contents depend on the
 * project renders empty until that query resolves. Reopening until the option
 * appears covers both without sleeping for a guessed number of milliseconds.
 */
async function withOpenDropdown<T>(fieldId: string, use: () => T): Promise<T> {
  const trigger = document.getElementById(fieldId)!
  // Everything happens inside one `waitFor` tick. Splitting "open it" from "use
  // it" loses the race: the popup can be dismissed again between the two.
  return waitFor(
    () => {
      if (trigger.getAttribute('data-state') !== 'open') fireEvent.click(trigger)
      return use()
    },
    { timeout: 4000 },
  )
}

async function pickFromDropdown(fieldId: string, optionText: RegExp) {
  await withOpenDropdown(fieldId, () => fireEvent.click(screen.getByRole('option', { name: optionText })))
}

/** The option labels a dropdown is offering right now. */
function readDropdownOptions(fieldId: string) {
  return withOpenDropdown(fieldId, () => {
    const names = screen.getAllByRole('option').map((option) => option.textContent ?? '')
    expect(names.length).toBeGreaterThan(0)
    return names
  })
}

let requestHeaders: Headers[] = []
const captureRequest = ({ request }: { request: Request }) => {
  if (request.method === 'POST' && request.url.endsWith('/tickets')) requestHeaders.push(request.headers)
}

beforeEach(() => {
  requestHeaders = []
  server.events.on('request:start', captureRequest)
  // The mock session is Ravi Kumar, a Developer, and the project switcher has
  // no selection until the user makes one.
  useCurrentProjectStore.setState({ project: null })
})

afterEach(() => {
  server.events.removeListener('request:start', captureRequest)
})

describe('S-19 Create Ticket', () => {
  it('renders all five blueprint §7.5 field groups', async () => {
    renderPage()
    await formReady()
    for (const group of ['Identity', 'Core', 'People', 'Effort', 'Extra']) {
      expect(screen.getByRole('group', { name: group })).toBeInTheDocument()
    }
  })

  it('shows the ticket ID as server-issued and never as an input', async () => {
    renderPage()
    await formReady()
    expect(screen.queryByLabelText(/^Ticket ID/)).not.toBeInTheDocument()
    expect(screen.getByText(/Generated on save/)).toBeInTheDocument()
  })

  it('refuses an empty submit and names every missing field', async () => {
    renderPage()
    fireEvent.click(await formReady())

    const alerts = await screen.findAllByRole('alert')
    expect(alerts.length).toBeGreaterThanOrEqual(6)
    expect(screen.getByText('Select the project this ticket belongs to')).toBeInTheDocument()
    expect(screen.getByText('Select a priority level')).toBeInTheDocument()
    expect(requestHeaders).toHaveLength(0)
  })

  it('offers no planned-close-date override to a Developer', async () => {
    renderPage()
    await formReady()
    // Supplying one requires PM or Admin. Enforced server-side; the input is
    // simply not offered, so the field reads as computed rather than skipped.
    expect(screen.queryByLabelText(/Planned close date/)).not.toBeInTheDocument()
    expect(screen.getByText('Computed from the SLA policy on save')).toBeInTheDocument()
  })

  it('pre-fills the level from the task type default and lets the user override it', async () => {
    renderPage()
    await formReady()

    // Production Bug defaults to HIGH in the task-type master.
    await pickFromDropdown('taskTypeId', /^Production Bug$/)

    const levels = screen.getByRole('radiogroup', { name: /Level/ })
    await waitFor(() => expect(within(levels).getByRole('radio', { name: 'HIGH' })).toBeChecked())

    fireEvent.click(within(levels).getByRole('radio', { name: 'CRITICAL' }))
    expect(within(levels).getByRole('radio', { name: 'CRITICAL' })).toBeChecked()

    // Changing the task type again must not overwrite a level the user chose.
    await pickFromDropdown('taskTypeId', /^Change Request$/)
    expect(within(levels).getByRole('radio', { name: 'CRITICAL' })).toBeChecked()
  })

  it('demands a client for a client-facing task type', async () => {
    renderPage()
    await formReady()

    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)
    await pickFromDropdown('taskTypeId', /^Client Bug$/)
    fireEvent.click(screen.getByRole('button', { name: 'Create ticket' }))

    expect(
      await screen.findByText('This task type is client-facing — pick the client it was raised for'),
    ).toBeInTheDocument()
    expect(requestHeaders).toHaveLength(0)
  })

  it('keeps client and contact dependent, and clears the contact when the client changes', async () => {
    renderPage()
    await formReady()

    expect(screen.getByLabelText(/^Client$/)).toBeDisabled()
    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)
    expect(screen.getByLabelText(/^Client$/)).toBeEnabled()

    expect(screen.getByLabelText(/Client contact/)).toBeDisabled()
    await pickFromDropdown('clientId', /ACME — Acme Retail Ltd/)
    await pickFromDropdown('clientContactId', /Sara Kapoor/)
    expect(screen.getByLabelText(/Client contact/)).toHaveTextContent('Sara Kapoor')

    await pickFromDropdown('clientId', /NORTH — Northwind Logistics/)
    await waitFor(() =>
      expect(screen.getByLabelText(/Client contact/)).not.toHaveTextContent('Sara Kapoor'),
    )
  })

  it('shows each assignee’s open load so the assigner can see who is free', async () => {
    renderPage()
    await formReady()
    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)

    expect(await readDropdownOptions('assigneeId')).toContainEqual(
      expect.stringMatching(/Ravi Kumar · \d+ open/),
    )
  })

  it('adds and removes watchers', async () => {
    renderPage()
    await formReady()
    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)

    await pickFromDropdown('watcherIds', /^Meera Iyer$/)
    const remove = await screen.findByRole('button', { name: 'Remove Meera Iyer from watchers' })
    fireEvent.click(remove)
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Remove Meera Iyer from watchers' })).not.toBeInTheDocument(),
    )
  })

  it('creates the ticket, sends an idempotency key and lands on the new ID', async () => {
    renderPage()
    await formReady()

    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)
    await pickFromDropdown('taskTypeId', /^Internal Bug$/)
    fireEvent.change(screen.getByLabelText(/Title \/ summary/), {
      target: { value: 'Payment gateway times out on checkout' },
    })
    fireEvent.change(screen.getByLabelText(/Task description/), {
      target: { value: 'Card payments hang at the confirmation step, then fail.' },
    })
    fireEvent.change(screen.getByLabelText(/Estimated effort/), { target: { value: '4.5' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create ticket' }))

    // The mock allocates from the project's own sequence, so the ID proves the
    // project prefix and the {CODE}-{YY}-{NNNNN} format round-tripped.
    const landed = await screen.findByText(/^Landed on CRM-26-\d{5,}$/)
    expect(landed).toBeInTheDocument()

    expect(requestHeaders).toHaveLength(1)
    // Without this a resubmit after a network timeout allocates a second ID,
    // and the sequence never gives the first one back.
    expect(requestHeaders[0].get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/i)
  })
})
