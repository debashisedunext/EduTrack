import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { http, HttpResponse } from 'msw'

import { server } from '@/mocks/server'
import { getDb } from '@/mocks/db'

import { NewObClientWizardPage } from './NewObClientWizardPage'

/**
 * B-109 · OB-04 against the mock server, on the mockup's flow: "Client
 * basics" → "SPOC contacts" → "Commercials" → "Requirements & journeys",
 * with a Continue button that answers an invalid step with a banner rather
 * than sitting disabled.
 *
 * Mounted through `Routes`, on `ObClientListPage.test.tsx`'s precedent, but
 * for a different reason here: the assertion that matters most —
 * `handleSubmit` navigating to the created client — can only be seen by
 * giving the wizard somewhere real to navigate *to*.
 */
function renderWizard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/onboarding/clients/new']}>
        <Routes>
          <Route path="/onboarding/clients/new" element={<NewObClientWizardPage />} />
          <Route path="/onboarding/clients/:obClientId" element={<p>client detail</p>} />
          <Route path="/onboarding/clients" element={<p>client list</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

// Four real MSW round trips per test at worst, under a parallel full-suite
// run — `ObClientListPage.test.tsx`'s SLOW alone is not enough headroom for a
// flow this long. `JourneyTemplateDesignerPage.test.tsx`'s precedent.
vi.setConfig({ testTimeout: 20000 })

const SLOW = { timeout: 8000 }

/** PAN is required on step 1 now (the mockup's own guard), so each test brings its own. */
async function fillStep1(name: string, pan: string) {
  fireEvent.change(await screen.findByLabelText(/Client name/), { target: { value: name } })
  fireEvent.change(screen.getByLabelText(/^PAN/), { target: { value: pan } })
  fireEvent.change(screen.getByLabelText(/Date of onboarding/), { target: { value: '2026-09-09' } })
}

function goNext() {
  fireEvent.click(screen.getByRole('button', { name: 'Continue →' }))
}

async function fillStep2() {
  await waitFor(() => expect(screen.getByRole('heading', { name: 'SPOC contacts' })).toBeTruthy())
  fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'A Person' } })
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'a@example.com' } })
}

/** Commercials — one application row, which is also step 4's checked product. */
async function addAnApplication() {
  await waitFor(() => expect(screen.getByRole('heading', { name: 'Commercials' })).toBeTruthy())
  // Disabled until the product catalogue has loaded — wait it out, or the
  // click lands on a button that cannot add anything yet.
  const add = await screen.findByRole('button', { name: '+ Add application' }, SLOW)
  await waitFor(() => expect(add).not.toBeDisabled(), SLOW)
  fireEvent.click(add)
  await screen.findByLabelText('Application', undefined, SLOW)
}

async function fillStep4() {
  await waitFor(() =>
    expect(screen.getByRole('heading', { name: 'Requirements & journeys' })).toBeTruthy())
  fireEvent.change(screen.getByLabelText(/Client requirements/), {
    target: { value: 'Migrate data and train the office staff.' },
  })
}

function clickCreate() {
  fireEvent.click(screen.getByRole('button', { name: /Create client — journeys instantiate/ }))
}

describe('NewObClientWizardPage', () => {
  it('answers an invalid Continue with the mockup’s error banner, naming the first problem', async () => {
    renderWizard()
    await screen.findByLabelText(/Client name/, undefined, SLOW)

    goNext()
    expect(await screen.findByRole('alert')).toHaveTextContent('Client name is required.')

    // Name alone is not enough — the mockup requires a well-formed PAN too.
    fireEvent.change(screen.getByLabelText(/Client name/), {
      target: { value: 'Wizard Smoke Academy' },
    })
    goNext()
    expect(await screen.findByRole('alert')).toHaveTextContent('PAN must look like ABCDE1234F.')

    await fillStep1('Wizard Smoke Academy', 'AAAAA1111A')
    goNext()
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: 'SPOC contacts' })).toBeTruthy())
  })

  it('boards a client end to end and lands on its detail page', async () => {
    renderWizard()
    await fillStep1('Wizard Happy Path Academy', 'BBBBB2222B')
    goNext()
    await fillStep2()
    goNext()
    await addAnApplication()
    goNext()
    await fillStep4()
    clickCreate()

    await screen.findByText('client detail', undefined, SLOW)

    const created = getDb().obClients.find((c) => c.name === 'Wizard Happy Path Academy')
    expect(created).toBeTruthy()
    expect(created!.journeys).toHaveLength(1)
    // B-109 · the backend gap this branch closes — a client boarded with
    // journeys and no checklist is exactly the half-state the create is
    // supposed to refuse to leave behind.
    expect(getDb().obClientPrereqs.some((h) => h.obClientId === created!.id)).toBe(true)
  })

  it('refuses to finish without requirements — the mockup marks them required', async () => {
    renderWizard()
    await fillStep1('Wizard Requirements Academy', 'EEEEE5555E')
    goNext()
    await fillStep2()
    goNext()
    await addAnApplication()
    goNext()

    await waitFor(() =>
      expect(screen.getByRole('heading', { name: 'Requirements & journeys' })).toBeTruthy())
    clickCreate()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Capture at least one line of client requirements.',
    )
    expect(getDb().obClients.some((c) => c.name === 'Wizard Requirements Academy')).toBe(false)
  })

  /**
   * `PortalLoginUnavailableException`'s own reason: the checkbox is real UI
   * over a refusal that is still real until B-126. The mock's default
   * `POST /onboarding/clients` handler does not refuse this (it is itself
   * ahead of the backend, deliberately — see `onboarding.ts`'s own note), so
   * this test overrides it for the one request that must see the refusal.
   */
  it('uninstalls a portal-login request the server still refuses, rather than leaving it silently doomed to repeat', async () => {
    server.use(
      http.post('*/onboarding/clients', () =>
        HttpResponse.json(
          {
            type: 'https://edutrack/errors/ob-client-portal-login-unavailable',
            title: 'Client portal logins are not available yet',
            status: 409,
            detail: 'B-126 is not built yet.',
            errors: { createPortalLogin: ['B-126 is not built yet.'] },
          },
          { status: 409 },
        )),
    )

    renderWizard()
    await fillStep1('Wizard Portal Login Academy', 'CCCCC3333C')
    goNext()
    await fillStep2()
    goNext()
    await addAnApplication()
    goNext()
    await fillStep4()

    fireEvent.click(screen.getByLabelText('Create client portal login now'))
    clickCreate()

    await screen.findByText(/Portal logins are not available from this screen yet/, undefined, SLOW)
    expect(screen.getByLabelText('Create client portal login now')).not.toBeChecked()
  })

  it('offers to create anyway when only a similar name stops the create, and does not lose the rest of the form', async () => {
    renderWizard()
    await fillStep1('Sunrise EdTech Pvt Ltd', 'DDDDD4444D')
    goNext()
    await fillStep2()
    goNext()
    await addAnApplication()
    goNext()
    await fillStep4()
    clickCreate()

    await screen.findByText(/very similar name already exists/, undefined, SLOW)
    fireEvent.click(screen.getByRole('button', { name: /create anyway/i }))

    await screen.findByText('client detail', undefined, SLOW)
    expect(getDb().obClients.filter((c) => c.name === 'Sunrise EdTech Pvt Ltd')).toHaveLength(2)
  })

  it('refuses to board anyone while nothing is published on OB-14, and writes nothing', async () => {
    const db = getDb()
    for (const version of db.obPrereqVersions) version.isActive = false

    renderWizard()
    await fillStep1('Wizard No Checklist Academy', 'FFFFF6666F')
    goNext()
    await fillStep2()
    goNext()
    await addAnApplication()
    goNext()
    await fillStep4()
    clickCreate()

    await screen.findByText(/no prerequisites checklist is published yet/i, undefined, SLOW)
    expect(screen.queryByText('client detail')).toBeNull()
    expect(getDb().obClients.some((c) => c.name === 'Wizard No Checklist Academy')).toBe(false)
  })
})
