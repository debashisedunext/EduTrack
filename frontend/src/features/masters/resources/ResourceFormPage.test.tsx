import { afterEach, beforeAll, describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import { server } from '@/mocks/server'
import { getDb } from '@/mocks/db'
import { ResourceFormPage } from './ResourceFormPage'

/** Radix's popover, select and dialog primitives need APIs jsdom does not implement. */
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

interface Recorded {
  method: string
  pathname: string
  ifMatch: string | null
  idempotencyKey: string | null
}

let requests: Recorded[] = []
beforeAll(() =>
  server.events.on('request:start', ({ request }) => {
    const url = new URL(request.url)
    if (!url.pathname.includes('/users')) return
    requests.push({
      method: request.method,
      pathname: url.pathname,
      ifMatch: request.headers.get('If-Match'),
      idempotencyKey: request.headers.get('Idempotency-Key'),
    })
  }),
)

afterEach(() => {
  requests = []
})

function renderForm(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/masters/resources" element={<div>Resource list</div>} />
          <Route path="/masters/resources/new" element={<ResourceFormPage />} />
          <Route path="/masters/resources/:userId/edit" element={<ResourceFormPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const writes = () => requests.filter((r) => r.method === 'POST' || r.method === 'PATCH')

describe('S-08 create', () => {
  it('renders all five sections', async () => {
    renderForm('/masters/resources/new')

    expect(await screen.findByText('New resource')).toBeInTheDocument()
    for (const section of ['Personal', 'Access', 'Org', 'Work', 'Projects']) {
      expect(screen.getByText(section)).toBeInTheDocument()
    }
  })

  /**
   * The password is never a request field — an admin who could set somebody
   * else's password would make `mustChangePassword` mean nothing.
   */
  it('shows that the password is generated rather than offering a field for it', async () => {
    renderForm('/masters/resources/new')

    expect(await screen.findByText('Generated on save, shown once')).toBeInTheDocument()
    expect(screen.queryByLabelText(/^password/i)).not.toBeInTheDocument()
  })

  it('refuses to submit until the five required fields are filled', async () => {
    renderForm('/masters/resources/new')

    fireEvent.click(await screen.findByRole('button', { name: 'Create resource' }))

    await waitFor(() => expect(screen.getByText('Employee code is required')).toBeInTheDocument())
    expect(screen.getByText('Full name is required')).toBeInTheDocument()
    expect(writes()).toHaveLength(0)
  })

  it('creates a resource and shows the temporary password exactly once', async () => {
    renderForm('/masters/resources/new')

    await fillRequiredFields()
    fireEvent.click(screen.getByRole('button', { name: 'Create resource' }))

    const password = await screen.findByTestId('temporary-password')
    expect(password).toHaveTextContent('Mock7#TempPass9x')
    expect(screen.getByText(/cannot be looked up again/i)).toBeInTheDocument()

    const created = getDb().users.find((u) => u.username === 'new.person')
    expect(created).toBeDefined()
    expect(created?.mustChangePassword).toBe(true)
  })

  /**
   * `http.ts` is explicit: a key created inside the mutation changes on every
   * retry and defends against nothing.
   */
  it('sends an Idempotency-Key on the create', async () => {
    renderForm('/masters/resources/new')

    await fillRequiredFields()
    fireEvent.click(screen.getByRole('button', { name: 'Create resource' }))

    await screen.findByTestId('temporary-password')
    const post = writes().find((r) => r.method === 'POST')
    expect(post?.idempotencyKey).toBeTruthy()
  })

  it('puts a duplicate-username 409 on the username field rather than in a banner', async () => {
    renderForm('/masters/resources/new')

    // `ravi` is in the fixture directory.
    await fillRequiredFields({ username: 'ravi' })
    fireEvent.click(screen.getByRole('button', { name: 'Create resource' }))

    expect(await screen.findByText('That username is already taken')).toBeInTheDocument()
    expect(screen.queryByTestId('temporary-password')).not.toBeInTheDocument()
  })
})

describe('S-08 edit', () => {
  it('seeds every section from the loaded resource', async () => {
    renderForm('/masters/resources/3/edit')

    expect(await screen.findByDisplayValue('Ravi Kumar')).toBeInTheDocument()
    expect(screen.getByDisplayValue('EMP-003')).toBeInTheDocument()
    expect(screen.getByDisplayValue('Engineering')).toBeInTheDocument()
    expect(screen.getByDisplayValue('Pune')).toBeInTheDocument()
    // Skills come back as chips, not as input values.
    expect(screen.getByRole('button', { name: 'Remove Java' })).toBeInTheDocument()
  })

  /**
   * The whole reason `useResource` is hand-written rather than the generated
   * hook: without the tag the PATCH answers 428 and nothing can be saved.
   */
  it('sends the ETag it read back as If-Match', async () => {
    renderForm('/masters/resources/3/edit')

    await screen.findByDisplayValue('Ravi Kumar')
    fireEvent.change(screen.getByLabelText(/^Designation/), { target: { value: 'Tech Lead' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(screen.getByText('Resource list')).toBeInTheDocument())

    const patch = writes().find((r) => r.method === 'PATCH')
    expect(patch?.ifMatch).toBeTruthy()
    // Not the blanket wildcard — that would disable the guard for every client,
    // which is the failure the mechanism exists to prevent.
    expect(patch?.ifMatch).not.toBe('*')
    expect(getDb().users.find((u) => u.id === 3)?.designation).toBe('Tech Lead')
  })

  it('shows a 404 rather than an empty form for a resource that does not exist', async () => {
    renderForm('/masters/resources/9999/edit')

    expect(await screen.findByText('No such resource')).toBeInTheDocument()
  })

  /**
   * The form must not be a way round the guard the status route enforces — it
   * is the more discoverable of the paths that can deactivate somebody.
   */
  it('refuses to deactivate a resource holding open tickets, and says how many', async () => {
    renderForm('/masters/resources/3/edit')

    await screen.findByDisplayValue('Ravi Kumar')
    fireEvent.click(screen.getByLabelText('Status'))
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/open tickets/i)
    expect(getDb().users.find((u) => u.id === 3)?.isActive).toBe(true)
  })

  /**
   * B-014 · and it does not stop there.
   *
   * This is the most discoverable of the three ways to deactivate somebody — an
   * admin who wants a leaver gone opens their record long before they think of
   * the grid's selection bar — so it is the path that most needs a next step
   * rather than a paragraph explaining why the save failed.
   */
  it('offers the reassignment wizard from the refusal, preselected', async () => {
    renderForm('/masters/resources/3/edit')

    await screen.findByDisplayValue('Ravi Kumar')
    fireEvent.click(screen.getByLabelText('Status'))
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))

    const alert = await screen.findByRole('alert')
    const href = within(alert).getByRole('link').getAttribute('href')!
    const url = new URL(href, 'http://localhost')

    expect(url.pathname).toBe('/tickets/bulk-reassign')
    expect(url.searchParams.get('fromUserId')).toBe('3')
    expect(url.searchParams.get('returnTo')).toBe('/masters/resources?deactivate=3')
  })

  it('drops the wizard link once the refusal is a different one', async () => {
    renderForm('/masters/resources/3/edit')

    await screen.findByDisplayValue('Ravi Kumar')
    // A duplicate username: still a 409, nothing to do with tickets. A stale
    // "reassign their tickets" link under it would send the admin to a wizard
    // that has nothing to fix.
    fireEvent.click(screen.getByLabelText('Status'))
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()

    fireEvent.click(screen.getByLabelText('Status'))
    fireEvent.change(screen.getByLabelText(/^Username/), { target: { value: 'meera' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() =>
      expect(within(screen.getByRole('alert')).queryByRole('link')).not.toBeInTheDocument(),
    )
  })

  /**
   * B-012. Anita (1) manages Meera (2) manages Ravi (3), so making Ravi
   * Meera's manager closes a loop the form is perfectly able to express — the
   * picker offers everybody but the resource being edited, and the subtree
   * below them is not something the browser knows.
   *
   * The assertion is that it lands *on the field*: a cycle three levels deep is
   * exactly the mistake an admin cannot see from the screen they are on, so a
   * banner above a five-section form is the wrong place to explain it.
   */
  it('puts a reporting-cycle 409 on the manager picker, not in a banner', async () => {
    renderForm('/masters/resources/2/edit')

    await screen.findByDisplayValue('Meera Iyer')
    fireEvent.click(screen.getByRole('button', { name: 'Reporting manager' }))
    fireEvent.click(await screen.findByRole('option', { name: /Ravi Kumar/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))

    expect(await screen.findByText(/reporting cycle/i)).toBeInTheDocument()
    expect(screen.queryByText('Resource list')).not.toBeInTheDocument()
    expect(getDb().users.find((u) => u.id === 2)?.reportingManagerId).toBe(1)
  })

  it('distinguishes somebody still on their temporary password from somebody who has logged in', async () => {
    renderForm('/masters/resources/3/edit')
    expect(await screen.findByText('Has set their own password')).toBeInTheDocument()

    // Karan (id 5) has never logged in.
    renderForm('/masters/resources/5/edit')
    expect(await screen.findByText('Still on the temporary password')).toBeInTheDocument()
  })
})

describe('the weekly-off override', () => {
  /**
   * `null` means "inherit the org working week" and `[]` means "no weekly off
   * at all". They are different answers and the form has to express both.
   */
  it('hides the day picker while the org week is inherited, and shows it when it is not', async () => {
    renderForm('/masters/resources/3/edit')

    await screen.findByDisplayValue('Ravi Kumar')
    const inherit = screen.getByLabelText(/organisation’s working week/i)
    expect(inherit).toBeChecked()
    expect(screen.queryByRole('group', { name: /non-working days/i })).not.toBeInTheDocument()

    fireEvent.click(inherit)
    expect(screen.getByRole('group', { name: /non-working days/i })).toBeInTheDocument()
  })

  it('saves an override and can put it back to inherited', async () => {
    renderForm('/masters/resources/3/edit')

    await screen.findByDisplayValue('Ravi Kumar')
    fireEvent.click(screen.getByLabelText(/organisation’s working week/i))
    fireEvent.click(screen.getByRole('button', { name: 'Saturday' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(screen.getByText('Resource list')).toBeInTheDocument())
    expect(getDb().users.find((u) => u.id === 3)?.weeklyOff).toEqual([6])
  })
})

describe('skills', () => {
  it('commits a tag on Enter without submitting the form', async () => {
    renderForm('/masters/resources/new')

    const input = await screen.findByLabelText(/Skills/)
    fireEvent.change(input, { target: { value: 'Kotlin' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    expect(screen.getByRole('button', { name: 'Remove Kotlin' })).toBeInTheDocument()
    // Enter inside a form submits it. A tag input that saves the whole resource
    // when you finish typing a skill is worse than one that ignores Enter.
    expect(writes()).toHaveLength(0)
  })

  /**
   * Without a blur-commit the tag the user can still see in the box is not in
   * the request, and the record saves one skill short with no clue why.
   */
  it('commits a tag left in the box when the field loses focus', async () => {
    renderForm('/masters/resources/new')

    const input = await screen.findByLabelText(/Skills/)
    fireEvent.change(input, { target: { value: 'Terraform' } })
    fireEvent.blur(input)

    expect(screen.getByRole('button', { name: 'Remove Terraform' })).toBeInTheDocument()
  })

  it('collapses a duplicate typed in a different case', async () => {
    renderForm('/masters/resources/3/edit')

    const input = await screen.findByLabelText(/Skills/)
    fireEvent.change(input, { target: { value: 'java' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    expect(screen.getByRole('button', { name: 'Remove Java' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Remove java' })).not.toBeInTheDocument()
  })
})

describe('project assignments', () => {
  it('does not offer a project the resource is already on', async () => {
    renderForm('/masters/resources/3/edit')

    await screen.findByDisplayValue('Ravi Kumar')
    fireEvent.click(screen.getByRole('button', { name: /Add project/ }))

    // `SearchableDropdown` renders a trigger whose accessible name comes from
    // the field's label, not from its placeholder; the search box lives in the
    // popover it opens.
    fireEvent.click(screen.getByRole('button', { name: 'Project assignments' }))

    const options = await screen.findAllByRole('option')
    const labels = options.map((o) => o.textContent ?? '')

    // Ravi is already on CRM (1) and PAY (2), so only WEB is offerable — two
    // rows for one project is a request the server de-duplicates anyway, and
    // letting the form express it means the second choice silently wins.
    expect(labels.some((l) => l.includes('WEB'))).toBe(true)
    expect(labels.some((l) => l.includes('CRM'))).toBe(false)
    expect(labels.some((l) => l.includes('PAY'))).toBe(false)
  })

  it('removes a membership and saves the shorter set', async () => {
    renderForm('/masters/resources/3/edit')

    await screen.findByDisplayValue('Ravi Kumar')
    fireEvent.click(screen.getByRole('button', { name: /Remove from PAY/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(screen.getByText('Resource list')).toBeInTheDocument())
    expect(getDb().users.find((u) => u.id === 3)?.projectIds).toEqual([1])
  })
})

// ── helpers ─────────────────────────────────────────────────────────────────

async function fillRequiredFields(overrides: { username?: string } = {}) {
  fireEvent.change(await screen.findByLabelText(/^Employee code/), { target: { value: 'EMP-100' } })
  fireEvent.change(screen.getByLabelText(/^Full name/), { target: { value: 'New Person' } })
  fireEvent.change(screen.getByLabelText(/^Email/), {
    target: { value: 'new.person@edunext.example' },
  })
  fireEvent.change(screen.getByLabelText(/^Username/), {
    target: { value: overrides.username ?? 'new.person' },
  })

  // The role Select is a Radix listbox, not a native <select>. Its trigger is
  // the labelable element the FormField points at.
  fireEvent.keyDown(screen.getByLabelText(/^Role/), { key: 'Enter' })
  fireEvent.click(await screen.findByRole('option', { name: 'Developer' }))
}
