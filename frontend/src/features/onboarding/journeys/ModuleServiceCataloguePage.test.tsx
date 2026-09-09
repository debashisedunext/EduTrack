import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

import { getDb } from '@/mocks/db'
import { Toaster } from '@/components/ui/toaster'

import { ModuleServiceCataloguePage } from './ModuleServiceCataloguePage'

/**
 * C-123 · the Module Service catalogue against the mock server, laid out to
 * the prototype's `vTemplates()`: create card, "Show services for" filter,
 * then the card grid.
 *
 * <p>Fixture note — `db.ts`'s `OB_PRODUCTS`/`OB_JOURNEY_TEMPLATES`: EduTrack
 * ERP (product 1, template 1) is active, sequence 1. LMS (product 3, template
 * 3) is a *retired* product with its own active, published template —
 * sequence 2, `dependsOnTemplateId: 1` — added by this task specifically so
 * the ↑/↓ control and the depends-on picker have a second real row to act
 * on. Biometric (product 2) has a draft template only, and is the
 * "no active Module Service yet" case.
 */
function renderCatalogue() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/onboarding/journey-templates']}>
        <ModuleServiceCataloguePage />
      </MemoryRouter>
      <Toaster />
    </QueryClientProvider>,
  )
}

vi.setConfig({ testTimeout: 20000 })
const SLOW = { timeout: 5000 }

async function openCatalogue() {
  renderCatalogue()
  await screen.findByRole('heading', { name: 'Module Service' }, SLOW)
  // Wait for the card grid itself, not just the header — the ERP card's own
  // heading, which only renders once the product list has arrived.
  await screen.findByRole('heading', { name: 'EduTrack ERP' }, SLOW)
}

const productCard = (name: string) =>
  screen.getAllByRole('listitem').find((li) => within(li).queryByRole('heading', { name }))!

describe('the catalogue lists every product', () => {
  it('shows an active service with its total TAT, active chip and edit link', async () => {
    await openCatalogue()

    const card = productCard('EduTrack ERP')
    expect(within(card).getByText('ERP')).toBeInTheDocument()
    expect(within(card).getByText(/d total TAT/)).toBeInTheDocument()
    expect(within(card).getByText('Active for product')).toBeInTheDocument()
    // The version chip and the edit label both come off the template detail.
    await within(card).findByRole('link', { name: /✎ Edit \(publishes v\d+\)/ }, SLOW)
  })

  it('lists the services with TAT, owner and dependency markers', async () => {
    await openCatalogue()

    const card = productCard('EduTrack ERP')
    // The active ERP template's first step, drawn as the mockup's step line.
    await within(card).findByText(/∥ parallel|↳ after step \d+/, undefined, SLOW)
  })

  it('shows a product with no active service as a dead end, not a broken card', async () => {
    await openCatalogue()

    const card = productCard('Biometric Attendance')
    expect(within(card).getByText('No active Module Service yet')).toBeInTheDocument()
    expect(within(card).queryByLabelText('Service depends on')).not.toBeInTheDocument()
  })

  it('the "Show services for" filter narrows the grid to one card', async () => {
    await openCatalogue()

    fireEvent.change(screen.getByLabelText('Show services for'), { target: { value: '1' } })

    // Card headings, not bare text — the create and filter selects still
    // offer every product as an <option>, which is not a card.
    await waitFor(() => {
      expect(screen.queryByRole('heading', { name: 'Biometric Attendance' })).not.toBeInTheDocument()
    })
    expect(screen.getByRole('heading', { name: 'EduTrack ERP' })).toBeInTheDocument()
  })
})

describe('creating a module service', () => {
  it('creates a draft for the chosen product and navigates to its designer', async () => {
    await openCatalogue()

    fireEvent.change(screen.getByLabelText('Create a new module service'), {
      target: { value: 'RFID Card Rollout' },
    })
    // Product 4 (HRMS) is the one fixture product with no template row yet —
    // the mock answers 409 for any product already holding one, draft included.
    fireEvent.change(screen.getByLabelText('For product'), { target: { value: '4' } })
    fireEvent.click(screen.getByRole('button', { name: '+ Create module service' }))

    await waitFor(() => {
      const created = getDb().obJourneyTemplates.find((t) => t.name === 'RFID Card Rollout')
      expect(created).toBeDefined()
      expect(created!.productId).toBe(4)
    }, SLOW)
  })

  it('refuses to submit without a name and a product', async () => {
    await openCatalogue()
    expect(screen.getByRole('button', { name: '+ Create module service' })).toBeDisabled()
  })
})

describe('reordering the catalogue', () => {
  it('moves a service down and persists the new sequence', async () => {
    await openCatalogue()

    const erpCard = productCard('EduTrack ERP')
    fireEvent.click(within(erpCard).getByRole('button', { name: 'Move EduTrack ERP down' }))

    await waitFor(() => {
      const templates = getDb().obJourneyTemplates
      expect(templates.find((t) => t.id === 1)!.sequence).toBe(1)
      expect(templates.find((t) => t.id === 3)!.sequence).toBe(0)
    }, SLOW)
  })

  it('the topmost service cannot move up, the bottommost cannot move down', async () => {
    await openCatalogue()

    const erpCard = productCard('EduTrack ERP')
    const lmsCard = productCard('Learning Management')
    expect(within(erpCard).getByRole('button', { name: 'Move EduTrack ERP up' })).toBeDisabled()
    expect(within(lmsCard).getByRole('button', { name: 'Move Learning Management down' })).toBeDisabled()
  })

  it('reorder still acts on the whole catalogue order while a filter narrows the view', async () => {
    // The mockup's `msMove` reorders the full `TEMPLATES` sequence whatever
    // the filter shows; the ↑/↓ buttons therefore stay, and their disabled
    // state reflects the card's place in the *unfiltered* order.
    await openCatalogue()

    fireEvent.change(screen.getByLabelText('Show services for'), { target: { value: '1' } })
    await waitFor(() => {
      expect(screen.queryByRole('heading', { name: 'Biometric Attendance' })).not.toBeInTheDocument()
    })

    expect(screen.getByRole('button', { name: 'Move EduTrack ERP up' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Move EduTrack ERP down' })).toBeEnabled()
  })
})

describe('the depends-on picker', () => {
  it('shows the current dependency, pre-selected', async () => {
    await openCatalogue()

    const lmsCard = productCard('Learning Management')
    const select = within(lmsCard).getByLabelText('Service depends on') as HTMLSelectElement
    await waitFor(() => expect(select).not.toBeDisabled(), SLOW)
    expect(select.value).toBe('1')
  })

  it('does not offer the service depending on it, or itself', async () => {
    await openCatalogue()

    const erpCard = productCard('EduTrack ERP')
    const select = within(erpCard).getByLabelText('Service depends on') as HTMLSelectElement
    await waitFor(() => expect(select).not.toBeDisabled(), SLOW)

    const optionValues = Array.from(select.options).map((o) => o.value)
    expect(optionValues).not.toContain('1') // itself
    expect(optionValues).not.toContain('3') // LMS already depends on ERP
  })

  it('clearing the dependency sends null and persists it', async () => {
    await openCatalogue()

    const lmsCard = productCard('Learning Management')
    const select = within(lmsCard).getByLabelText('Service depends on') as HTMLSelectElement
    await waitFor(() => expect(select).not.toBeDisabled(), SLOW)

    fireEvent.change(select, { target: { value: '' } })

    await waitFor(() => {
      expect(getDb().obJourneyTemplates.find((t) => t.id === 3)!.dependsOnTemplateId).toBeNull()
    }, SLOW)
  })
})
