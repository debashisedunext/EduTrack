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
const formReady = () => screen.findByRole('button', { name: 'Save & Assign' })

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

/**
 * Every `POST /tickets` the page issued, cloned at request start so the body is
 * still readable — MSW hands the same `Request` to the handler, which consumes
 * it.
 */
let creates: Request[] = []
const captureRequest = ({ request }: { request: Request }) => {
  if (request.method === 'POST' && request.url.endsWith('/tickets')) creates.push(request.clone())
}
const idempotencyKeys = () => creates.map((request) => request.headers.get('Idempotency-Key'))
const bodyOf = (index: number) => creates[index].json() as Promise<Record<string, unknown>>

const descriptionEditor = () => screen.getByRole('textbox', { name: /Task description/ })

/**
 * The description is a contentEditable since C-066, so there is no `value` to
 * set — `fireEvent.change` throws "the given element does not have a value
 * setter". Writing the markup and firing `input` is what the browser does.
 */
function typeDescription(html: string) {
  const editor = descriptionEditor()
  editor.innerHTML = html
  fireEvent.input(editor)
}

/** The three fields Save & Assign insists on beyond project and task type. */
function fillTicketBody(title: string) {
  fireEvent.change(screen.getByLabelText(/Title \/ summary/), { target: { value: title } })
  typeDescription('<p>Card payments hang at the confirmation step, then fail.</p>')
  fireEvent.change(screen.getByLabelText(/Estimated effort/), { target: { value: '4.5' } })
}

beforeEach(() => {
  creates = []
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
    expect(creates).toHaveLength(0)
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
    fireEvent.click(screen.getByRole('button', { name: 'Save & Assign' }))

    expect(
      await screen.findByText('This task type is client-facing — pick the client it was raised for'),
    ).toBeInTheDocument()
    expect(creates).toHaveLength(0)
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
    fillTicketBody('Payment gateway times out on checkout')
    fireEvent.click(screen.getByRole('button', { name: 'Save & Assign' }))

    // The mock allocates from the project's own sequence, so the ID proves the
    // project prefix and the {CODE}-{YY}-{NNNNN} format round-tripped.
    const landed = await screen.findByText(/^Landed on CRM-26-\d{5,}$/)
    expect(landed).toBeInTheDocument()

    expect(creates).toHaveLength(1)
    // Without this a resubmit after a network timeout allocates a second ID,
    // and the sequence never gives the first one back.
    expect(idempotencyKeys()[0]).toMatch(/^[0-9a-f-]{36}$/i)
  })

  it('sends the description as sanitised rich text — C-066', async () => {
    // The round trip the shared editor exists for: a support agent's structure
    // survives to the wire, and what came in with it does not. Anything the
    // §3.9 allow-list rejects is stripped here, on the client, *as well as* on
    // the write path — the server's copy is the guarantee, this one is what
    // stops the user believing they saved something they did not.
    renderPage()
    await formReady()

    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)
    await pickFromDropdown('taskTypeId', /^Internal Bug$/)
    fireEvent.change(screen.getByLabelText(/Title \/ summary/), {
      target: { value: 'Payment gateway times out on checkout' },
    })
    fireEvent.change(screen.getByLabelText(/Estimated effort/), { target: { value: '4.5' } })
    typeDescription(
      '<p style="color:red">Card payments <b>hang</b>.</p>' +
        '<ol><li>Open Fees</li><li>Submit</li></ol>' +
        '<script>alert(1)</script>',
    )
    fireEvent.click(screen.getByRole('button', { name: 'Save & Assign' }))
    await screen.findByText(/^Landed on CRM-26-\d{5,}$/)

    const body = await bodyOf(0)
    // `<b>` folded to `<strong>`, the inline style dropped, the list kept, the
    // script gone tag and contents both.
    expect(body.description).toBe(
      '<p>Card payments <strong>hang</strong>.</p><ol><li>Open Fees</li><li>Submit</li></ol>',
    )
  })

  it('refuses an editor the user focused and left empty', async () => {
    // `<p><br></p>` is what the browser leaves behind, and it is 13 characters
    // of nothing — a `.min(1)` check on the raw value would let it through and
    // store a description that reads as blank.
    renderPage()
    await formReady()

    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)
    await pickFromDropdown('taskTypeId', /^Internal Bug$/)
    fireEvent.change(screen.getByLabelText(/Title \/ summary/), {
      target: { value: 'Payment gateway times out on checkout' },
    })
    fireEvent.change(screen.getByLabelText(/Estimated effort/), { target: { value: '4.5' } })
    typeDescription('<p><br></p>')
    fireEvent.click(screen.getByRole('button', { name: 'Save & Assign' }))

    expect(
      await screen.findByText('Describe the task — this is what the assignee reads first'),
    ).toBeInTheDocument()
    expect(creates).toHaveLength(0)
  })
})

describe('S-19 actions — C-013', () => {
  it('offers the four actions blueprint §7.5 lists', async () => {
    renderPage()
    await formReady()
    for (const name of ['Save & Assign', 'Save as Draft', 'Save & Create Another', 'Cancel']) {
      expect(screen.getByRole('button', { name })).toBeInTheDocument()
    }
  })

  it('says whether the ticket is about to be saved unassigned', async () => {
    renderPage()
    await formReady()

    // §7.5 does not mark Assigned To required and C-015 ships an Unassigned
    // saved view, so this is a supported outcome — but "Save & Assign" reads
    // like a promise that someone will get it, so it is worth stating.
    expect(screen.getByText(/Saves unassigned/)).toBeInTheDocument()

    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)
    await pickFromDropdown('assigneeId', /Ravi Kumar/)
    expect(await screen.findByText(/Will be assigned to/)).toHaveTextContent('Ravi Kumar')
  })

  it('saves a draft without the description and effort the primary action demands', async () => {
    renderPage()
    await formReady()

    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)
    await pickFromDropdown('taskTypeId', /^Internal Bug$/)
    fireEvent.change(screen.getByLabelText(/Title \/ summary/), {
      target: { value: 'Half-written — finish tomorrow' },
    })

    // The same values under Save & Assign must still be refused. If the draft
    // relaxation leaked into the primary path, every ticket would be creatable
    // with no description and no estimate.
    fireEvent.click(screen.getByRole('button', { name: 'Save & Assign' }))
    expect(
      await screen.findByText('Describe the task — this is what the assignee reads first'),
    ).toBeInTheDocument()
    expect(creates).toHaveLength(0)

    fireEvent.click(screen.getByRole('button', { name: 'Save as Draft' }))
    await screen.findByText(/^Landed on CRM-26-\d{5,}$/)

    const body = await bodyOf(0)
    expect(body.saveAsDraft).toBe(true)
    // Blank is omitted, never sent as '' or 0. `estimatedHrs: 0` is a genuine
    // zero-hour estimate, which is a different claim from "not estimated yet".
    expect(body).not.toHaveProperty('description')
    expect(body).not.toHaveProperty('estimatedHrs')
  })

  it('applies the draft rules per attempt rather than latching them on', async () => {
    renderPage()
    await formReady()

    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)
    await pickFromDropdown('taskTypeId', /^Client Bug$/)

    // §4B.2's client rule is waived — chasing down which client a half-written
    // ticket belongs to is a common reason to park it. The title is not: the
    // contract requires it, so this attempt still fails on that alone.
    fireEvent.click(screen.getByRole('button', { name: 'Save as Draft' }))
    expect(await screen.findByText(/Give the ticket a title of at least/)).toBeInTheDocument()
    expect(
      screen.queryByText('This task type is client-facing — pick the client it was raised for'),
    ).not.toBeInTheDocument()
    expect(creates).toHaveLength(0)

    fireEvent.change(screen.getByLabelText(/Title \/ summary/), { target: { value: 'Needs a client' } })

    // And the form must not stay lenient afterwards — the resolver reads the
    // action per attempt, not once at mount.
    fireEvent.click(screen.getByRole('button', { name: 'Save & Assign' }))
    expect(
      await screen.findByText('This task type is client-facing — pick the client it was raised for'),
    ).toBeInTheDocument()
    expect(creates).toHaveLength(0)
  })

  it('keeps the batch context and clears the ticket after Save & Create Another', async () => {
    renderPage()
    await formReady()

    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)
    await pickFromDropdown('taskTypeId', /^Internal Bug$/)
    await pickFromDropdown('assigneeId', /Ravi Kumar/)
    fillTicketBody('First of a batch')

    // Radix restores focus to a dropdown's trigger a frame after the popup
    // closes. A real user's next act is clicking a button, which happens long
    // after that; here the save follows immediately, so wait the restoration
    // out rather than race the focus assertion below against it.
    await waitFor(() => expect(document.getElementById('assigneeId')).toHaveFocus())

    fireEvent.click(screen.getByRole('button', { name: 'Save & Create Another' }))
    // 4000, not the 1000 ms default — this waits on a real MSW round trip.
    await waitFor(() => expect(creates).toHaveLength(1), { timeout: 4000 })

    // It stays on the form — navigating away is what the other two do.
    expect(screen.queryByText(/^Landed on /)).not.toBeInTheDocument()

    // Confirmed in the action bar rather than with a toast. The shared toast
    // viewport is `fixed bottom-0 right-0 z-[100]`, directly over this
    // screen's primary action — verified in a real browser, where the toast
    // swallowed the click on Save & Assign. jsdom has no layout, so this
    // assertion pins the behaviour that avoids it, not the overlap itself.
    expect(await screen.findByText(/CRM-26-\d{5,} created — 1 in this batch/)).toBeInTheDocument()

    const title = screen.getByLabelText(/Title \/ summary/)
    await waitFor(() => expect(title).toHaveValue(''), { timeout: 4000 })
    // A contentEditable has no `value`; an emptied one is the placeholder
    // coming back, which is also what the user actually sees.
    expect(descriptionEditor().innerHTML).toBe('')
    expect(
      screen.getByText('What happened, what was expected, and how to reproduce it.'),
    ).toBeInTheDocument()
    expect(screen.getByLabelText(/Estimated effort/)).toHaveValue('')

    // What a batch shares survives, or the action saves nobody any work.
    expect(screen.getByLabelText(/^Project/)).toHaveTextContent('CRM')
    expect(screen.getByLabelText(/Task type/)).toHaveTextContent('Internal Bug')
    expect(screen.getByLabelText(/Assigned to/)).toHaveTextContent('Ravi Kumar')

    // Otherwise the user is left at the bottom of a form that looks unchanged
    // apart from a toast, with no cue that it is now blank.
    expect(title).toHaveFocus()
  })

  it('mints a fresh idempotency key for the second ticket of a batch', async () => {
    renderPage()
    await formReady()

    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)
    await pickFromDropdown('taskTypeId', /^Internal Bug$/)
    fillTicketBody('First of a batch')
    fireEvent.click(screen.getByRole('button', { name: 'Save & Create Another' }))
    // Both waits are on a real round trip through MSW, which carries a
    // deliberate 120 ms latency, and this is the only test in the file that
    // makes two of them in sequence. `waitFor` defaults to 1000 ms; alone that
    // is ample, but competing with twenty-two other suites for the CPU it
    // intermittently is not, and the failure reads as "the second ticket was
    // never created" rather than "the clock ran out". Every other
    // network-waiting assertion in this codebase already passes 4000.
    await waitFor(() => expect(screen.getByLabelText(/Title \/ summary/)).toHaveValue(''), {
      timeout: 4000,
    })

    fillTicketBody('Second of a batch')
    fireEvent.click(screen.getByRole('button', { name: 'Save & Assign' }))
    await waitFor(() => expect(creates).toHaveLength(2), { timeout: 4000 })

    const [first, second] = idempotencyKeys()
    expect(first).toMatch(/^[0-9a-f-]{36}$/i)
    // The key identifies one logical ticket, not one attempt. Carrying it into
    // the second save replays the first response for 24 hours: the user is
    // shown the first ID again and the second ticket is never created. The
    // mock does not implement replay, so only this assertion catches it.
    expect(second).not.toBe(first)
  })
})
