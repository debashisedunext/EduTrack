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
  it('shows an active service with its total TAT and active chip', async () => {
    await openCatalogue()

    const card = serviceCard('ERP Suite onboarding')
    // The product is context on the service's card, not the card's subject.
    expect(within(card).getByText('EduTrack ERP')).toBeInTheDocument()
    expect(within(card).getByText(/d total TAT/)).toBeInTheDocument()
    expect(within(card).getByText('Active version')).toBeInTheDocument()
  })

  /**
   * The card is a summary: it says *how many* steps a service has and leaves
   * what they are to the page it opens. It used to print every step as a line
   * of text, which made a grid of five services a wall nobody read.
   */
  it('counts the steps rather than listing them', async () => {
    await openCatalogue()

    const card = serviceCard('ERP Suite onboarding')
    // Template 1 is the five-step ERP journey in `db.ts`.
    expect(within(card).getByText('☰ 5 steps')).toBeInTheDocument()
    // No step lines: the mockup's `name · TATd · owner · ↳ after step n`.
    expect(within(card).queryByText(/· \d+d ·/)).not.toBeInTheDocument()
    expect(within(card).queryByText(/↳ after step \d+/)).not.toBeInTheDocument()
  })

  it('opens the service when the card is clicked, not a button inside it', async () => {
    await openCatalogue()

    const card = serviceCard('ERP Suite onboarding')
    // The heading is the link, stretched over the whole tile — so the card's
    // accessible name is the service, and there is no second "Edit" control
    // competing with it.
    const link = within(card).getByRole('link', { name: 'ERP Suite onboarding' })
    expect(link).toHaveAttribute('href', '/onboarding/journey-templates/1')
    expect(within(card).queryByRole('button', { name: /Edit/ })).not.toBeInTheDocument()
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
   * Rename, move to another product and delete moved onto the service's own
   * page when the card became a summary tile. Asserted rather than merely
   * deleted, so a "convenient" inline control does not drift back onto a tile
   * that is one big link.
   *
   * The two that stayed are both about a service's place among the others,
   * which is a fact only this screen has: ↑/↓ and "Service depends on".
   */
  it('keeps the controls that compare services, and no others', async () => {
    await openCatalogue()

    const card = serviceCard('ERP Suite onboarding')
    expect(within(card).queryByRole('button', { name: /Delete/ })).not.toBeInTheDocument()
    expect(within(card).getByLabelText('Service depends on')).toBeInTheDocument()
    expect(
      within(card).getByRole('button', { name: 'Move ERP Suite onboarding down' }),
    ).toBeInTheDocument()
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

describe('reaching the Role Master', () => {
  /*
    The catalogue used to carry its own "Manage roles ↗" button. It was
    removed: the route into S-09 belongs on OB-08, where roles are
    administered, and in the designer's step form, where the owner picker is a
    closed list and "the role I want is not here" has to be answerable without
    leaving. This page defines services, not roles.

    Asserted rather than merely deleted, so the button does not drift back in
    as an obvious-looking convenience.
  */
  it('does not offer its own route to the role master', async () => {
    await openCatalogue()

    expect(screen.queryByRole('link', { name: /Manage roles/ })).not.toBeInTheDocument()
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

/**
 * "Service depends on" stayed on the card when rename and delete moved to the
 * service's own page: the choice it offers is *the other services*, so it
 * belongs on the one screen where all of them are already on the page.
 *
 * <p>It sits above the card's stretched link on `relative z-10` — driving the
 * select must change the dependency, not open the service underneath it.
 */
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
