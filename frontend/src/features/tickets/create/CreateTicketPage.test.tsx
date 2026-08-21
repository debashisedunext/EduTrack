import { beforeAll, beforeEach, afterEach, describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { server } from '@/mocks/server'
import { getDb } from '@/mocks/db'
import type { TicketFieldCode } from '@/api/generated/model/ticketFieldCode'
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

/**
 * The page's own cache, exposed so a test can refetch on demand.
 *
 * B-029 needs it: the one gate the dropdown cannot hold is a client that became
 * ineligible *after* it was chosen, and reproducing that means the client list
 * genuinely reloading rather than the fixture being edited underneath a cache
 * that never looks again.
 */
let queryClient: QueryClient

function renderPage() {
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
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

/** C-068 · the second rich-text field, driven the same way for the same reason. */
function typeSteps(html: string) {
  const editor = screen.getByRole('textbox', { name: /Steps to generate/ })
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
  /*
    C-071 · the CRM fixture requires MODULE and ESTIMATED_HRS on every ticket,
    and almost every test in this file raises its ticket on CRM. Left in place,
    that project rule would sit on top of whichever rule each test is actually
    measuring — §7.5's bug-type module, §4B.2's client, C-013's draft — and an
    assertion would pass or fail for a reason that has nothing to do with its
    own subject. Cleared here so each rule is measured alone; `C-071 — the
    project's own settings` puts it back, explicitly, for the tests that are
    about it.

    `resetDb()` in `src/test/setup.ts` restores the fixture after every test, so
    this is scoped to the test that runs next and nothing else.
  */
  getDb().projects.find((project) => project.id === 1)!.mandatoryFields = []
})

afterEach(() => {
  server.events.removeListener('request:start', captureRequest)
})

describe('S-19 Create Ticket', () => {
  it('renders all six blueprint §7.5 field groups', async () => {
    renderPage()
    await formReady()
    // Six since C-068. "Where it happened" sits between Core and People
    // because that is where §7.5's own field table puts it.
    for (const group of ['Identity', 'Core', 'Where it happened', 'People', 'Effort', 'Extra']) {
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

  /**
   * C-012 · §4B.1 — changing the priority recomputes and previews the planned
   * close date **before the user commits**. The screen calls the server for it
   * rather than estimating: the date depends on the working calendar and on the
   * assignee's approved leave, and a browser-side guess would disagree by a
   * weekend without saying so.
   */
  describe('the inline planned-close-date preview', () => {
    it('claims nothing until a project and a priority are both chosen', async () => {
      renderPage()
      await formReady()

      expect(screen.getByText(/pick a project and a priority/i)).toBeInTheDocument()
    })

    it('shows the date with its target in working hours and where it came from', async () => {
      renderPage()
      await formReady()
      await pickFromDropdown('projectId', /CRM — Client CRM Platform/)
      // Production Bug defaults to HIGH, and CRM has its own policy for that
      // pair — 8 working hours where the org-wide High default is 16.
      await pickFromDropdown('taskTypeId', /^Production Bug$/)

      await waitFor(() => expect(screen.getByText(/8 working hours/)).toBeInTheDocument(), { timeout: 4000 })
      expect(
        screen.getByText(/this project's policy for this task type and priority/),
      ).toBeInTheDocument()
    })

    it('recomputes when the priority changes', async () => {
      renderPage()
      await formReady()
      await pickFromDropdown('projectId', /CRM — Client CRM Platform/)
      await pickFromDropdown('taskTypeId', /^Production Bug$/)
      await waitFor(() => expect(screen.getByText(/8 working hours/)).toBeInTheDocument(), { timeout: 4000 })

      const levels = screen.getByRole('radiogroup', { name: /Level/ })
      fireEvent.click(within(levels).getByRole('radio', { name: 'CRITICAL' }))

      // CRM's Critical Production Bug policy is 2 h. A preview that did not
      // re-resolve would still show a date, which is why the hours are asserted
      // rather than merely that something is rendered.
      await waitFor(() => expect(screen.getByText(/2 working hours/)).toBeInTheDocument(), { timeout: 4000 })
      expect(screen.queryByText(/8 working hours/)).not.toBeInTheDocument()
    })
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

  /**
   * B-028 · blueprint line 948 — "at least one primary contact before the
   * client can be selected on a ticket".
   *
   * Kestrel is a PROSPECT with no contacts, mapped to CRM by `db.ts` so this
   * screen can exercise the rule at all. Both halves are asserted together
   * because they pull in opposite directions and getting either backwards is
   * invisible: it has to be **offered** (a prospect is active — the server was
   * filtering `status = 'ACTIVE'` and dropping every one of them from this
   * dropdown) and it has to be **refused** (no primary contact).
   */
  it('offers a client with no primary contact but will not let it be chosen', async () => {
    renderPage()
    await formReady()

    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)

    const options = await readDropdownOptions('clientId')
    // Listed, and saying why — a client silently absent from a dropdown is
    // indistinguishable from a dropdown that has lost its data, and the person
    // raising the ticket is usually the one who can go and fix the master.
    expect(options.some((o) => /KESTREL/.test(o) && /No primary contact/.test(o))).toBe(true)

    await pickFromDropdown('clientId', /KESTREL/)
    expect(screen.getByLabelText(/^Client$/)).not.toHaveTextContent('Kestrel')
  })

  /**
   * B-029 · blueprint line 523 — "blocks new ticket creation against it".
   *
   * A deactivated client is not merely greyed here, it is *absent*: the query
   * sends `isActive: true`, and unlike the missing-contact case that is right —
   * deactivation is a deliberate administrative act rather than an oversight
   * for the person raising the ticket to go and fix, and greying every closed
   * client forever would grow this list without bound.
   *
   * Northwind is deactivated here rather than asserting against the fixture's
   * own INACTIVE client: Oldco is mapped only to the Archived Pilot, a CLOSED
   * project, and this form's project dropdown sends `isActive: true` too — so
   * there is no project selection from which Oldco could be offered or refused,
   * and a test that "proved" its absence would be proving the project filter.
   */
  it('does not offer a deactivated client', async () => {
    renderPage()
    await formReady()

    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)
    expect((await readDropdownOptions('clientId')).some((o) => /NORTH/.test(o))).toBe(true)

    getDb().clients.find((c) => c.id === 2)!.status = 'INACTIVE'
    await queryClient.invalidateQueries()

    await waitFor(
      async () => expect((await readDropdownOptions('clientId')).some((o) => /NORTH/.test(o))).toBe(false),
      { timeout: 4000 },
    )
  })

  /**
   * The gate the dropdown cannot hold.
   *
   * `getOptionDisabled` guards a click; it does not guard a `clientId` already
   * in the form. This form outlives its own fetches — Save & Create Another
   * keeps the client across an arbitrary number of tickets — so a selection can
   * predate somebody else deactivating that client, on another screen, by an
   * hour. And there is nothing behind it: `POST /tickets` has no controller, so
   * until C-013 mounts it this refusal is the only enforcement in the system.
   */
  it('refuses at submit when the selected client stops being eligible', async () => {
    renderPage()
    await formReady()

    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)
    await pickFromDropdown('taskTypeId', /^Client Bug$/)
    // C-068 · §7.5 makes Module mandatory for bug-type task types, so a form
    // that skips it never reaches `onSubmit` and this test would pass while
    // asserting nothing about the client — the trap the comment below names.
    await pickFromDropdown('moduleId', /^Fees$/)
    await pickFromDropdown('clientId', /ACME/)
    // Filled, or zod refuses the body first and `onSubmit` never runs — which
    // would make this test pass while asserting nothing about the client.
    fillTicketBody('Card payments hang at confirmation')

    // Deactivated underneath the open form, exactly as S-32 would, and the
    // list reloaded — a window refocus, or the next Save & Create Another.
    getDb().clients.find((c) => c.id === 1)!.status = 'INACTIVE'
    await queryClient.invalidateQueries()

    // The trigger stops resolving a label once Acme leaves the list — but
    // `clientId` is still 1 in the form, which is precisely the state that used
    // to post. Waiting on this rather than on the dropdown so the popup is not
    // left open over the button the next line clicks.
    await waitFor(
      () => expect(screen.getByLabelText(/^Client$/)).not.toHaveTextContent('Acme'),
      { timeout: 4000 },
    )

    fireEvent.click(screen.getByRole('button', { name: 'Save & Assign' }))

    expect(await screen.findByText(/no longer available on this project/i)).toBeInTheDocument()
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
    await pickFromDropdown('moduleId', /^Fees$/)
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
    await pickFromDropdown('moduleId', /^Fees$/)
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
    await pickFromDropdown('moduleId', /^Fees$/)
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
    await pickFromDropdown('moduleId', /^Fees$/)
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
    // C-068 · re-picked, not inherited. `retainedForNextTicket` deliberately
    // clears the module while keeping the project, client, type and level — so
    // the second bug of a batch costs one dropdown and gets a module somebody
    // actually chose, rather than the previous ticket's carried forward into a
    // field that is never blank enough to look wrong. This line is the cost of
    // that decision, stated where it is paid.
    await pickFromDropdown('moduleId', /^Admission$/)
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

/* ── C-068 — the "Where it happened" group ─────────────────────────────── */

describe('C-068 — Where it happened', () => {
  it('marks Module required for a bug-type task type and optional for a change request', async () => {
    renderPage()
    await formReady()
    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)

    // The asterisk is `aria-hidden` and paired with an `sr-only` "(required)",
    // so this reads what a screen reader reads rather than what is drawn.
    await pickFromDropdown('taskTypeId', /^Production Bug$/)
    await waitFor(() => expect(screen.getByText('Module').textContent).toMatch(/\(required\)/))

    await pickFromDropdown('taskTypeId', /^Change Request$/)
    await waitFor(() => expect(screen.getByText('Module').textContent).not.toMatch(/\(required\)/))
  })

  it('refuses Save & Assign on a bug with no module, and accepts the same form as a draft', async () => {
    renderPage()
    await formReady()

    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)
    await pickFromDropdown('taskTypeId', /^Internal Bug$/)
    fillTicketBody('Receipt reprint drops the duplicate watermark')

    fireEvent.click(screen.getByRole('button', { name: 'Save & Assign' }))
    expect(
      await screen.findByText('Pick the module this bug is in — it is what routes it to the right team'),
    ).toBeInTheDocument()
    expect(creates).toHaveLength(0)

    // §7.5: "Save as Draft waives it either way." A draft is often parked
    // precisely because the reporter does not yet know where it happened.
    fireEvent.click(screen.getByRole('button', { name: 'Save as Draft' }))
    await waitFor(() => expect(creates).toHaveLength(1), { timeout: 4000 })
  })

  it('does not let a change request be blocked by a module it may not have', async () => {
    renderPage()
    await formReady()

    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)
    await pickFromDropdown('taskTypeId', /^Change Request$/)
    fillTicketBody('Add a duplicate watermark toggle to receipt printing')
    fireEvent.click(screen.getByRole('button', { name: 'Save & Assign' }))

    await waitFor(() => expect(creates).toHaveLength(1), { timeout: 4000 })
    expect('moduleId' in (await bodyOf(0))).toBe(false)
  })

  it('sends all four fields, with the steps sanitised on the way out', async () => {
    renderPage()
    await formReady()

    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)
    await pickFromDropdown('taskTypeId', /^Internal Bug$/)
    await pickFromDropdown('moduleId', /^Fees$/)
    fireEvent.change(screen.getByLabelText(/Screen name/), { target: { value: 'Fee Receipt Print' } })
    fireEvent.change(screen.getByLabelText(/^Feature/), {
      target: { value: 'Reprint with duplicate watermark' },
    })
    typeSteps('<p>1. Open Fees → Receipts</p><script>alert(1)</script>')
    fillTicketBody('Receipt reprint drops the duplicate watermark')
    fireEvent.click(screen.getByRole('button', { name: 'Save & Assign' }))

    await waitFor(() => expect(creates).toHaveLength(1), { timeout: 4000 })
    const body = await bodyOf(0)
    expect(body).toMatchObject({
      moduleId: 3,
      screenName: 'Fee Receipt Print',
      feature: 'Reprint with duplicate watermark',
      stepsToGenerate: '<p>1. Open Fees → Receipts</p>',
    })
  })

  it('says why the Module list is empty rather than opening onto nothing', async () => {
    // `GET /masters/modules` is B-064 and unbuilt on the real backend — it
    // answers 404 today, so this is the live state, not a hypothetical.
    server.use(http.get('*/masters/modules', () => new HttpResponse(null, { status: 404 })))
    renderPage()
    await formReady()

    expect(await screen.findByText(/module list could not be loaded/i)).toBeInTheDocument()
  })

  it('does not waive the requirement just because the master is unavailable', async () => {
    // The tempting fix and the wrong one. Waiving it would let a network
    // failure silently disable a validation rule, and every bug raised during
    // the outage would carry no module — §7.5's data poisoning, invisible.
    server.use(http.get('*/masters/modules', () => new HttpResponse(null, { status: 404 })))
    renderPage()
    await formReady()

    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)
    await pickFromDropdown('taskTypeId', /^Internal Bug$/)
    fillTicketBody('Receipt reprint drops the duplicate watermark')
    fireEvent.click(screen.getByRole('button', { name: 'Save & Assign' }))

    expect(
      await screen.findByText('Pick the module this bug is in — it is what routes it to the right team'),
    ).toBeInTheDocument()
    expect(creates).toHaveLength(0)
  })

  it('does not offer a deactivated module', async () => {
    // The endpoint returns inactive rows on purpose — a grid has to render the
    // name of a module an old ticket was raised against. A picker offering one
    // would be offering a 400: `ModuleGuard` refuses an inactive module on
    // write. Transport is the fixture's retired row.
    renderPage()
    await formReady()

    await withOpenDropdown('moduleId', () => {
      expect(screen.getByRole('option', { name: 'Fees' })).toBeInTheDocument()
      expect(screen.queryByRole('option', { name: 'Transport' })).not.toBeInTheDocument()
    })
  })
})

/* ── C-021 — client-contact dropdowns and the §4B.2 auto-fills ──────────── */

describe('C-021 — client auto-fills', () => {
  it('adds the account manager as a watcher when their client is picked, without fighting a manual removal', async () => {
    renderPage()
    await formReady()

    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)
    await pickFromDropdown('clientId', /ACME — Acme Retail Ltd/)

    // Acme's account manager in the fixture is Meera Iyer — added to the
    // watcher chips without the user opening that picker at all.
    const remove = await screen.findByRole('button', { name: 'Remove Meera Iyer from watchers' })

    // A starting point, not a rule the desk cannot override.
    fireEvent.click(remove)
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Remove Meera Iyer from watchers' })).not.toBeInTheDocument(),
    )

    // And nothing re-adds her while the client has not actually changed —
    // switching contact, or anything else on the form, must not re-run the
    // fill.
    await pickFromDropdown('clientContactId', /Sara Kapoor/)
    expect(screen.queryByRole('button', { name: 'Remove Meera Iyer from watchers' })).not.toBeInTheDocument()
  })

  it('shows the client’s time zone alongside the planned close date when it differs from the viewer’s', async () => {
    renderPage()
    await formReady()

    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)
    // Production Bug is what the existing SLA-preview tests use to get a
    // resolved date quickly.
    await pickFromDropdown('taskTypeId', /^Production Bug$/)
    await waitFor(() => expect(screen.getByText(/8 working hours/)).toBeInTheDocument(), { timeout: 4000 })

    // Northwind's fixture time zone is Europe/London — never the viewer's in
    // this suite (jsdom's default is UTC, and the dev fixture is IST either
    // way), so the extra line is always expected here.
    await pickFromDropdown('clientId', /NORTH — Northwind Logistics/)

    expect(await screen.findByText(/in the client's time zone \(Europe\/London\)/)).toBeInTheDocument()
  })

  describe('the inline "+ Add contact" dialog', () => {
    it('is not offered to a Developer, who is told a new contact needs an Admin', async () => {
      renderPage()
      await formReady()

      await pickFromDropdown('projectId', /CRM — Client CRM Platform/)
      await pickFromDropdown('clientId', /ACME — Acme Retail Ltd/)

      expect(screen.queryByRole('button', { name: '+ Add contact' })).not.toBeInTheDocument()
      expect(screen.getByText(/a new one takes an admin/i)).toBeInTheDocument()
    })

    it('lets an Admin add a contact inline and selects it without leaving the form', async () => {
      // Anita Rao — the fixture's Admin.
      getDb().currentUserId = 1
      renderPage()
      await formReady()

      await pickFromDropdown('projectId', /CRM — Client CRM Platform/)
      await pickFromDropdown('clientId', /ACME — Acme Retail Ltd/)

      // Enabled only once this client's contacts have loaded once — see the
      // component note on why the click has to wait for that.
      const addContact = await screen.findByRole('button', { name: '+ Add contact' })
      await waitFor(() => expect(addContact).toBeEnabled(), { timeout: 4000 })
      fireEvent.click(addContact)
      const dialog = await screen.findByRole('dialog', { name: 'Add contact' })

      fireEvent.change(within(dialog).getByLabelText(/^Name/), { target: { value: 'New Reporter' } })
      fireEvent.change(within(dialog).getByLabelText(/^Email/), {
        target: { value: 'new.reporter@acme.example' },
      })
      fireEvent.click(within(dialog).getByRole('button', { name: 'Add contact' }))

      // The dialog is Stream B's — it reports success only by invalidating
      // the same `useListClientContacts` query this screen already reads, so
      // proving the round trip actually happened means waiting for the real
      // MSW request rather than the dialog closing.
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument(), { timeout: 4000 })
      await waitFor(() => expect(screen.getByLabelText(/Client contact/)).toHaveTextContent('New Reporter'), {
        timeout: 4000,
      })
    })
  })

  describe('the "Show all clients" toggle', () => {
    it('is not offered to a Developer', async () => {
      renderPage()
      await formReady()
      await pickFromDropdown('projectId', /CRM — Client CRM Platform/)

      expect(screen.queryByRole('checkbox', { name: /show all clients/i })).not.toBeInTheDocument()
    })

    it('lets an Admin see clients not mapped to the project', async () => {
      // Anita Rao — the fixture's Admin.
      getDb().currentUserId = 1
      renderPage()
      await formReady()
      await pickFromDropdown('projectId', /CRM — Client CRM Platform/)

      // Bluewave is mapped to no project in the fixture, so it is absent from
      // CRM's ordinary, project-filtered list.
      expect((await readDropdownOptions('clientId')).some((o) => /BLUE/.test(o))).toBe(false)

      fireEvent.click(screen.getByRole('checkbox', { name: /show all clients/i }))

      await waitFor(
        async () => expect((await readDropdownOptions('clientId')).some((o) => /BLUE/.test(o))).toBe(true),
        { timeout: 4000 },
      )
    })
  })
})

/* ── Clipboard paste — C-024 ────────────────────────────────────────────── */

describe('CreateTicketPage — pasting a screenshot', () => {
  /** jsdom has no clipboard; testing-library copies a plain object onto the event. */
  function clipboardOf(files: File[], text: Partial<Record<string, string>> = {}) {
    return {
      types: [...Object.keys(text), ...(files.length > 0 ? ['Files'] : [])],
      items: [
        ...Object.keys(text).map(() => ({ kind: 'string', getAsFile: () => null })),
        ...files.map((file) => ({ kind: 'file', getAsFile: () => file })),
      ],
      files,
      getData: (type: string) => text[type] ?? '',
    }
  }

  /** What every browser hands over for a Snipping Tool capture. */
  const capture = () => new File(['x'], 'image.png', { type: 'image/png' })

  const attachedFiles = () => screen.findByRole('list', { name: 'Attached files' })

  it('stages a screenshot pasted with nothing focused', async () => {
    // The end of the wiring the unit tests only cover in halves: the document
    // listener, the picker's validation and deferred-mode staging, on the real
    // screen. Nothing focuses the drop zone, which is the whole reason the
    // listener is not on the control.
    renderPage()
    await formReady()

    fireEvent.paste(document.body, { clipboardData: clipboardOf([capture()]) })

    expect(within(await attachedFiles()).getByText(/^screenshot-/)).toBeInTheDocument()
  })

  it('stages three screenshots pasted one after another', async () => {
    // What a support agent actually does. The OS clipboard holds one image, so
    // several screenshots means paste, paste, paste — and every one of them
    // reaches the browser named `image.png`, faster than a one-second stamp can
    // separate them. Before the picker disambiguated the name, the second and
    // third were refused as duplicates of the first and simply never appeared.
    renderPage()
    await formReady()

    fireEvent.paste(document.body, { clipboardData: clipboardOf([capture()]) })
    fireEvent.paste(document.body, { clipboardData: clipboardOf([capture()]) })
    fireEvent.paste(document.body, { clipboardData: clipboardOf([capture()]) })

    const rows = within(await attachedFiles()).getAllByText(/^screenshot-/)
    expect(rows).toHaveLength(3)
    expect(new Set(rows.map((r) => r.textContent)).size).toBe(3)
  })

  it('stages every image of a multi-file paste', async () => {
    // A multi-select copied out of a file manager — the one route that carries
    // several images in a single event.
    renderPage()
    await formReady()

    fireEvent.paste(document.body, { clipboardData: clipboardOf([capture(), capture(), capture()]) })

    expect(within(await attachedFiles()).getAllByText(/^screenshot-/)).toHaveLength(3)
  })

  it('stages a screenshot pasted into the description exactly once', async () => {
    // Two handlers see this paste: `RichTextEditor` swallows image files itself
    // and hands them to `onPasteFiles`, and the event then bubbles to the
    // document listener. Acting in both places attaches every pasted screenshot
    // twice — the listener stands down for a contentEditable so it does not.
    renderPage()
    await formReady()

    fireEvent.paste(document.querySelector('[contenteditable="true"]')!, {
      clipboardData: clipboardOf([capture()]),
    })

    expect(within(await attachedFiles()).getAllByText(/^screenshot-/)).toHaveLength(1)
  })

  it('leaves an ordinary text paste in the title alone', async () => {
    // A file on the clipboard is not evidence the user meant to attach it —
    // copying an image from a web page brings an `<img>` tag along as
    // `text/html`. In a text field the text wins.
    renderPage()
    await formReady()

    fireEvent.paste(screen.getByLabelText(/Title \/ summary/), {
      clipboardData: clipboardOf([capture()], { 'text/html': '<img src="x">' }),
    })

    expect(screen.queryByRole('list', { name: 'Attached files' })).toBeNull()
  })
})

/* ── C-071 — the project's own settings ────────────────────────────────── */

/**
 * B-019's Settings tab had shipped and nothing obeyed it: a PM could restrict a
 * project's task types, watch the screen confirm the save, and watch tickets be
 * raised outside them. These are the four things that had to become true.
 *
 * The fixture is the arrangement: `PAY` allows exactly two task types and `CRM`
 * allows none explicitly, which is the pair the whole feature turns on — an
 * empty allow-list means *unrestricted*, and reading it the other way would
 * empty the picker on every project in the organisation.
 */
describe("C-071 — the project's own settings", () => {
  const requireOnCrm = (...fields: TicketFieldCode[]) => {
    getDb().projects.find((project) => project.id === 1)!.mandatoryFields = fields
  }

  /**
   * Wait until the settings have actually reached the form.
   *
   * **The form cannot enforce a rule it has not been told about yet**, and
   * `GET /projects/{id}/settings` is a round trip that starts when the project
   * is picked. A save fired inside that window validates against
   * `noProjectRules` and goes through — the server refuses it and
   * `CreateTicketPage` maps the 400 back onto the fields, which is the
   * arrangement this codebase draws everywhere ("a form is advice; the write
   * path is the guarantee"). Unreachable by a person, who has a title and a
   * description to type first; trivially reachable by `fireEvent`.
   *
   * The asterisk is the honest signal that the rules have landed, and it is what
   * the user would be looking at too.
   */
  const settingsApplied = (label: string) =>
    waitFor(() => expect(screen.getByText(label).textContent).toMatch(/\(required\)/))

  it('offers only the task types a restricted project accepts', async () => {
    renderPage()
    await formReady()
    await pickFromDropdown('projectId', /PAY — Payments Gateway/)

    await waitFor(async () => {
      const options = await readDropdownOptions('taskTypeId')
      // The two `project_task_types` rows, and nothing else. Filtered out rather
      // than shown greyed: unlike a client with no primary contact, this is not
      // an obstacle the person raising the ticket can go and fix.
      expect(options).toEqual(['Production Bug', 'Internal Bug'])
    })
  })

  it('offers every active task type on a project that restricts none', async () => {
    renderPage()
    await formReady()
    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)

    const options = await readDropdownOptions('taskTypeId')
    // An empty allow-list is unrestricted, not forbidden — the decision B-019
    // turns on, and the one whose reversal would stop ticket creation
    // everywhere at once. `Fax Request` is absent because it is deactivated,
    // which is a different rule and still applies.
    expect(options).toContain('Change Request')
    expect(options.length).toBeGreaterThan(2)
    expect(options).not.toContain('Fax Request')
  })

  it('clears a task type the newly selected project does not accept', async () => {
    renderPage()
    await formReady()
    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)
    await pickFromDropdown('taskTypeId', /^Change Request$/)
    expect(screen.getByLabelText(/Task type/)).toHaveTextContent('Change Request')

    await pickFromDropdown('projectId', /PAY — Payments Gateway/)

    // Left standing it would be invisible and still submitted: the picker no
    // longer lists it, so the trigger falls back to its placeholder and the form
    // looks empty while holding the old id.
    await waitFor(() =>
      expect(screen.getByLabelText(/Task type/)).toHaveTextContent('Select a task type'),
    )
  })

  it('marks a field the project requires and refuses the save without it', async () => {
    requireOnCrm('SCREEN_NAME')

    renderPage()
    await formReady()
    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)
    await pickFromDropdown('taskTypeId', /^Change Request$/)
    fillTicketBody('Add a duplicate watermark toggle')

    // The asterisk is `aria-hidden` and paired with an `sr-only` "(required)",
    // so this reads what a screen reader reads rather than what is drawn.
    await waitFor(() => expect(screen.getByText('Screen name').textContent).toMatch(/\(required\)/))

    fireEvent.click(screen.getByRole('button', { name: 'Save & Assign' }))
    expect(
      await screen.findByText('This project requires a screen name on every ticket'),
    ).toBeInTheDocument()
    expect(creates).toHaveLength(0)

    fireEvent.change(screen.getByLabelText(/Screen name/), { target: { value: 'Fee Receipt Print' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save & Assign' }))
    await waitFor(() => expect(creates).toHaveLength(1), { timeout: 4000 })
  })

  it('does not waive the project’s fields for a draft, where §7.5’s own rules are waived', async () => {
    requireOnCrm('SCREEN_NAME')

    renderPage()
    await formReady()
    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)
    await pickFromDropdown('taskTypeId', /^Change Request$/)
    fireEvent.change(screen.getByLabelText(/Title \/ summary/), { target: { value: 'Half a thought' } })
    await settingsApplied('Screen name')

    // A draft waives the description and the estimate — C-013's rule, unchanged,
    // and this save would succeed today. It does not waive the project's own
    // fields: `saveAsDraft` is accepted by the server and acted on nowhere, so a
    // draft that waived them would be a one-click opt-out producing a ticket
    // indistinguishable from any other. `ProjectSettingsGate` refuses it too.
    fireEvent.click(screen.getByRole('button', { name: 'Save as Draft' }))
    expect(
      await screen.findByText('This project requires a screen name on every ticket'),
    ).toBeInTheDocument()
    expect(creates).toHaveLength(0)
  })

  it('reports every field the project requires, not one per attempt', async () => {
    requireOnCrm('SCREEN_NAME', 'FEATURE')

    renderPage()
    await formReady()
    await pickFromDropdown('projectId', /CRM — Client CRM Platform/)
    await pickFromDropdown('taskTypeId', /^Change Request$/)
    fillTicketBody('Add a duplicate watermark toggle')
    await settingsApplied('Screen name')

    fireEvent.click(screen.getByRole('button', { name: 'Save & Assign' }))

    // Both at once. One per round trip on a form with ten optional fields is a
    // conversation rather than an error message.
    expect(
      await screen.findByText('This project requires a screen name on every ticket'),
    ).toBeInTheDocument()
    expect(
      screen.getByText('This project requires a feature on every ticket'),
    ).toBeInTheDocument()
    expect(creates).toHaveLength(0)
  })
})
