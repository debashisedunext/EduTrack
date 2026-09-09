import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { http, HttpResponse } from 'msw'

import { server } from '@/mocks/server'
import { getDb } from '@/mocks/db'

import { NewObClientWizardPage } from './NewObClientWizardPage'

/**
 * B-109 · OB-04 against the mock server.
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

async function fillStep1(name: string) {
  fireEvent.change(await screen.findByLabelText(/Client name/), { target: { value: name } })
  fireEvent.change(screen.getByLabelText(/Onboarding date/), { target: { value: '2026-09-09' } })
}

function goNext() {
  fireEvent.click(screen.getByRole('button', { name: 'Next' }))
}

async function fillStep2() {
  await waitFor(() => expect(screen.getByRole('heading', { name: 'Contacts' })).toBeTruthy())
  fireEvent.change(screen.getByLabelText('Contact name'), { target: { value: 'A Person' } })
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'a@example.com' } })
}

/** The first product whose template checkbox is not disabled. */
async function selectAProduct() {
  await waitFor(() => expect(screen.getByRole('heading', { name: 'Products' })).toBeTruthy())
  const boxes = (await screen.findAllByRole('checkbox', undefined, SLOW)) as HTMLInputElement[]
  const selectable = boxes.find((b) => !b.disabled)
  expect(selectable).toBeTruthy()
  fireEvent.click(selectable!)
}

describe('NewObClientWizardPage', () => {
  it('keeps Next disabled until the required fields on the current step are filled', async () => {
    renderWizard()
    await screen.findByLabelText(/Client name/, undefined, SLOW)

    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()

    await fillStep1('Wizard Smoke Academy')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Next' })).not.toBeDisabled())
  })

  it('boards a client end to end and lands on its detail page', async () => {
    renderWizard()
    await fillStep1('Wizard Happy Path Academy')
    goNext()
    await fillStep2()
    goNext()
    await selectAProduct()
    goNext()

    await screen.findByRole('heading', { name: 'Review & create' })
    fireEvent.click(screen.getByRole('button', { name: 'Create client' }))

    await screen.findByText('client detail', undefined, SLOW)

    const created = getDb().obClients.find((c) => c.name === 'Wizard Happy Path Academy')
    expect(created).toBeTruthy()
    expect(created!.journeys).toHaveLength(1)
    // B-109 · the backend gap this branch closes — a client boarded with
    // journeys and no checklist is exactly the half-state the create is
    // supposed to refuse to leave behind.
    expect(getDb().obClientPrereqs.some((h) => h.obClientId === created!.id)).toBe(true)
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
    await fillStep1('Wizard Portal Login Academy')
    goNext()
    await fillStep2()
    goNext()
    await selectAProduct()
    goNext()

    await screen.findByRole('heading', { name: 'Review & create' })
    fireEvent.click(screen.getByLabelText('Create client portal login now'))
    fireEvent.click(screen.getByRole('button', { name: 'Create client' }))

    await screen.findByText(/Portal logins are not available from this screen yet/, undefined, SLOW)
    expect(screen.getByLabelText('Create client portal login now')).not.toBeChecked()
  })

  it('offers to create anyway when only a similar name stops the create, and does not lose the rest of the form', async () => {
    renderWizard()
    await fillStep1('Acme Private Limited')
    goNext()
    await fillStep2()
    goNext()
    await selectAProduct()
    goNext()

    await screen.findByRole('heading', { name: 'Review & create' })
    fireEvent.click(screen.getByRole('button', { name: 'Create client' }))

    await screen.findByText(/very similar name already exists/, undefined, SLOW)
    fireEvent.click(screen.getByRole('button', { name: /create anyway/i }))

    await screen.findByText('client detail', undefined, SLOW)
    expect(getDb().obClients.filter((c) => c.name === 'Acme Private Limited')).toHaveLength(2)
  })

  it('refuses to board anyone while nothing is published on OB-14, and writes nothing', async () => {
    const db = getDb()
    for (const version of db.obPrereqVersions) version.isActive = false

    renderWizard()
    await fillStep1('Wizard No Checklist Academy')
    goNext()
    await fillStep2()
    goNext()
    await selectAProduct()
    goNext()

    await screen.findByRole('heading', { name: 'Review & create' })
    fireEvent.click(screen.getByRole('button', { name: 'Create client' }))

    await screen.findByText(/no prerequisites checklist is published yet/i, undefined, SLOW)
    expect(screen.queryByText('client detail')).toBeNull()
    expect(getDb().obClients.some((c) => c.name === 'Wizard No Checklist Academy')).toBe(false)
  })
})
