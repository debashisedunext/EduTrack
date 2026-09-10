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
 * ERP (product 1) sells **two live services** — "ERP Suite onboarding"
 * (template 1, sequence 1) and "Enterprise (data migration)" (template 4,
 * sequence 2, held behind template 1). LMS (product 3, template 3, sequence
 * 3) is a *retired* product with its own active, published template —
 * retiring a product does not unpublish what it had — so the ↑/↓ control and
 * the depends-on picker have real rows to act on. Biometric (product 2) has
 * a draft template only, and is the "no active Module Service yet" case.
 *
 * <p>The catalogue order is therefore templates 1, 4, 3.
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
  // Wait for the card grid itself, not just the header — the ERP service's
  // own heading, which renders once both the product and template lists have
  // arrived. The card's subject is the service, not the product.
  await screen.findByRole('heading', { name: 'ERP Suite onboarding' }, SLOW)
}

const serviceCard = (name: string) =>
  screen.getAllByRole('listitem').find((li) => within(li).queryByRole('heading', { name }))!

describe('the catalogue lists every service', () => {
  it('shows an active service with its total TAT, active chip and edit link', async () => {
    await openCatalogue()

    const card = serviceCard('ERP Suite onboarding')
    // The product is context on the service's card, not the card's subject.
    expect(within(card).getByText('EduTrack ERP')).toBeInTheDocument()
    expect(within(card).getByText(/d total TAT/)).toBeInTheDocument()
    expect(within(card).getByText('Active version')).toBeInTheDocument()
    // The version chip and the edit label both come off the template detail.
    await within(card).findByRole('link', { name: /✎ Edit \(publishes v\d+\)/ }, SLOW)
  })

  it('lists the services with TAT, owner and dependency markers', async () => {
    await openCatalogue()

    const card = serviceCard('ERP Suite onboarding')
    // The active ERP template's first step, drawn as the mockup's step line.
    await within(card).findByText(/∥ parallel|↳ after step \d+/, undefined, SLOW)
  })

  /**
   * A draft is a service too, and it gets a card. What it does not get is the
   * depends-on picker or a position in the order — `sequence` runs over the
   * active services, which is what instantiation follows.
   */
  it('draws an unpublished service as a draft, without the depends-on picker', async () => {
    await openCatalogue()

    const card = serviceCard('Biometric Attendance onboarding')
    expect(within(card).getByText('Draft — not published')).toBeInTheDocument()
    expect(within(card).queryByLabelText('Service depends on')).not.toBeInTheDocument()
    expect(within(card).queryByText('Active version')).not.toBeInTheDocument()
  })

  /**
   * The whole point of the change: one product, several named services. The
   * fixture's product 1 gets a second service here so the grid has to draw
   * two cards for it rather than one.
   */
  it('draws a card per service when one product sells several', async () => {
    getDb().obJourneyTemplates.push({
      id: 99, productId: 1, name: 'ERP Enterprise onboarding', version: 1, isActive: false,
      sequence: 9, dependsOnTemplateId: null, publishedBy: null, publishedAt: null,
    })
    await openCatalogue()

    expect(await screen.findByRole('heading', { name: 'ERP Suite onboarding' }, SLOW)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'ERP Enterprise onboarding' })).toBeInTheDocument()
  })

  it('draws both of a product\'s live services as active, not one', async () => {
    // The two are not alternatives — a client who buys EduTrack ERP is
    // boarded through both, one ribbon each. Publishing the second used to
    // retire the first, which showed here as a single active card.
    await openCatalogue()

    const standard = serviceCard('ERP Suite onboarding')
    const enterprise = serviceCard('Enterprise (data migration)')
    expect(within(standard).getByText('Active version')).toBeInTheDocument()
    expect(within(enterprise).getByText('Active version')).toBeInTheDocument()
    expect(within(enterprise).getByText('⛓ after ERP Suite onboarding')).toBeInTheDocument()
  })

  it('the "Show services for" filter narrows the grid to one card', async () => {
    await openCatalogue()

    fireEvent.change(screen.getByLabelText('Show services for'), { target: { value: '1' } })

    // Card headings, not bare text — the create and filter selects still
    // offer every product as an <option>, which is not a card.
    await waitFor(() => {
      expect(
        screen.queryByRole('heading', { name: 'Biometric Attendance onboarding' }),
      ).not.toBeInTheDocument()
    })
    expect(screen.getByRole('heading', { name: 'ERP Suite onboarding' })).toBeInTheDocument()
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

    const erpCard = serviceCard('ERP Suite onboarding')
    fireEvent.click(within(erpCard).getByRole('button', { name: 'Move ERP Suite onboarding down' }))

    // Order was [1, 4, 3]; moving the first down makes it [4, 1, 3],
    // persisted as sequence 0..N-1.
    await waitFor(() => {
      const templates = getDb().obJourneyTemplates
      expect(templates.find((t) => t.id === 4)!.sequence).toBe(0)
      expect(templates.find((t) => t.id === 1)!.sequence).toBe(1)
      expect(templates.find((t) => t.id === 3)!.sequence).toBe(2)
    }, SLOW)
  })

  it('the topmost service cannot move up, the bottommost cannot move down', async () => {
    await openCatalogue()

    const erpCard = serviceCard('ERP Suite onboarding')
    const lmsCard = serviceCard('LMS onboarding')
    expect(within(erpCard).getByRole('button', { name: 'Move ERP Suite onboarding up' })).toBeDisabled()
    expect(within(lmsCard).getByRole('button', { name: 'Move LMS onboarding down' })).toBeDisabled()
  })

  it('reorder still acts on the whole catalogue order while a filter narrows the view', async () => {
    // The mockup's `msMove` reorders the full `TEMPLATES` sequence whatever
    // the filter shows; the ↑/↓ buttons therefore stay, and their disabled
    // state reflects the card's place in the *unfiltered* order.
    await openCatalogue()

    fireEvent.change(screen.getByLabelText('Show services for'), { target: { value: '1' } })
    await waitFor(() => {
      expect(
        screen.queryByRole('heading', { name: 'Biometric Attendance onboarding' }),
      ).not.toBeInTheDocument()
    })

    expect(screen.getByRole('button', { name: 'Move ERP Suite onboarding up' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Move ERP Suite onboarding down' })).toBeEnabled()
  })
})

describe('the depends-on picker', () => {
  it('shows the current dependency, pre-selected', async () => {
    await openCatalogue()

    const lmsCard = serviceCard('LMS onboarding')
    const select = within(lmsCard).getByLabelText('Service depends on') as HTMLSelectElement
    await waitFor(() => expect(select).not.toBeDisabled(), SLOW)
    expect(select.value).toBe('1')
  })

  it('does not offer the service depending on it, or itself', async () => {
    await openCatalogue()

    const erpCard = serviceCard('ERP Suite onboarding')
    const select = within(erpCard).getByLabelText('Service depends on') as HTMLSelectElement
    await waitFor(() => expect(select).not.toBeDisabled(), SLOW)

    const optionValues = Array.from(select.options).map((o) => o.value)
    expect(optionValues).not.toContain('1') // itself
    expect(optionValues).not.toContain('3') // LMS already depends on ERP
  })

  it('clearing the dependency sends null and persists it', async () => {
    await openCatalogue()

    const lmsCard = serviceCard('LMS onboarding')
    const select = within(lmsCard).getByLabelText('Service depends on') as HTMLSelectElement
    await waitFor(() => expect(select).not.toBeDisabled(), SLOW)

    fireEvent.change(select, { target: { value: '' } })

    await waitFor(() => {
      expect(getDb().obJourneyTemplates.find((t) => t.id === 3)!.dependsOnTemplateId).toBeNull()
    }, SLOW)
  })
})
