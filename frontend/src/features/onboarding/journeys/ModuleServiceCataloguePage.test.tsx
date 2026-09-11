import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

import { getDb } from '@/mocks/db'
import { Toaster } from '@/components/ui/toaster'

import { ModuleServiceCataloguePage } from './ModuleServiceCataloguePage'

/**
 * C-123 · the Module Service catalogue against the mock server: create card,
 * "Show services for" filter, then the table — one row per service.
 *
 * <p>Fixture note — `db.ts`'s `OB_PRODUCTS`/`OB_JOURNEY_TEMPLATES`: EduTrack
 * ERP (product 1) sells **two live services** — "ERP Suite onboarding"
 * (template 1, sequence 1) and "Enterprise (data migration)" (template 4,
 * sequence 2, held behind template 1). LMS (product 3, template 3, sequence
 * 3) is a *retired* product with its own active, published template —
 * retiring a product does not unpublish what it had — so the depends-on
 * picker has real rows to act on. Biometric (product 2) has
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
  // Wait for the table itself, not just the header — the ERP service's own
  // row link, which renders once both the product and template lists have
  // arrived. The row's subject is the service, not the product.
  await screen.findByRole('link', { name: 'ERP Suite onboarding' }, SLOW)
}

/** One service's row, found by the link that is its name. */
const serviceRow = (name: string) =>
  screen.getAllByRole('row').find((row) => within(row).queryByRole('link', { name }))!

describe('the catalogue lists every service', () => {
  it('names the service, captions it with its product, and prints its TAT', async () => {
    await openCatalogue()

    const row = serviceRow('ERP Suite onboarding')
    // The product is a caption under the name rather than a column of its
    // own — without it the "All products" view cannot say whose service a row
    // is, and with it the table is a column narrower.
    expect(within(row).getByText('EduTrack ERP')).toBeInTheDocument()
    // Template 1's five steps: 3 + 4 + 8 + 5 + 4.
    expect(within(row).getByText('⏱ 24d')).toBeInTheDocument()
    // Version and state left with the cards. A row says what a service *is*,
    // and the designer page says which version you are looking at.
    expect(within(row).queryByText('Active version')).not.toBeInTheDocument()
    expect(within(row).queryByText(/^v\d+$/)).not.toBeInTheDocument()
  })

  /**
   * The two derived columns. Neither is on `ObJourneyTemplateSummary`: owners
   * and checklist items nest inside the steps of the *detail* read, which this
   * page already fetches per row for the picker's ETag.
   */
  it('names the default implementor from the first step, falling back to its role', async () => {
    await openCatalogue()

    const row = serviceRow('ERP Suite onboarding')
    // Template 1's first step pins no person, only the role that covers it —
    // so the column says the role rather than inventing a name.
    expect(await within(row).findByText('Role · PM', undefined, SLOW)).toBeInTheDocument()
  })

  it('totals the checklist items across a service’s steps', async () => {
    await openCatalogue()

    // Template 2's first step carries the fixture's two items; template 1
    // carries none, and zero is a fact rather than a gap.
    const biometric = serviceRow('Biometric Attendance onboarding')
    expect(await within(biometric).findByText('☑ 2', undefined, SLOW)).toBeInTheDocument()
    expect(within(serviceRow('ERP Suite onboarding')).getByText('☑ 0')).toBeInTheDocument()
  })

  /**
   * A row is a summary: it says *how many* steps a service has and leaves what
   * they are to the page it opens. It used to print every step as a line of
   * text, which made a grid of five services a wall nobody read.
   */
  it('counts the steps rather than listing them', async () => {
    await openCatalogue()

    const row = serviceRow('ERP Suite onboarding')
    // Template 1 is the five-step ERP journey in `db.ts`.
    expect(within(row).getByText('☰ 5')).toBeInTheDocument()
    // No step lines: the mockup's `name · TATd · owner · ↳ after step n`.
    expect(within(row).queryByText(/· \d+d ·/)).not.toBeInTheDocument()
    expect(within(row).queryByText(/↳ after step \d+/)).not.toBeInTheDocument()
  })

  it('opens the service from the name, not from a button in the row', async () => {
    await openCatalogue()

    const row = serviceRow('ERP Suite onboarding')
    // The name is a real anchor — middle-click and "copy link" work — and the
    // row navigates on click as well. There is no second "Edit" control
    // competing with either.
    const link = within(row).getByRole('link', { name: 'ERP Suite onboarding' })
    expect(link).toHaveAttribute('href', '/onboarding/journey-templates/1')
    expect(within(row).queryByRole('button', { name: /Edit/ })).not.toBeInTheDocument()
  })

  /**
   * A draft is a service too, and it gets a row. What it does not get is the
   * depends-on picker or a position in the order — `sequence` runs over the
   * active services, which is what instantiation follows.
   */
  it('gives an unpublished service no position and no depends-on picker', async () => {
    await openCatalogue()

    const row = serviceRow('Biometric Attendance onboarding')
    // Said, not dashed: the cell is not missing a number, there is no number
    // for it to hold.
    expect(within(row).getByText('Not in order')).toBeInTheDocument()
    expect(within(row).queryByLabelText('Service depends on')).not.toBeInTheDocument()
    expect(within(row).getByText('∥ parallel')).toBeInTheDocument()
  })

  /**
   * Rename, move to another product and delete moved onto the service's own
   * page when the card became a summary tile. Asserted rather than merely
   * deleted, so a "convenient" inline control does not drift back onto a tile
   * that is one big link.
   *
   * One stayed, and for the same reason: "Service depends on" offers *the
   * other services*, so it belongs on the one screen where all of them are
   * already on the page. The ↑/↓ pair left with the table — re-sequencing is
   * now a field on the service's own admin form.
   */
  it('keeps the control that compares services, and no others', async () => {
    await openCatalogue()

    const row = serviceRow('ERP Suite onboarding')
    expect(within(row).queryByRole('button', { name: /Delete/ })).not.toBeInTheDocument()
    expect(
      within(row).getByRole('button', { name: /Services ERP Suite onboarding depends on/ }),
    ).toBeInTheDocument()
    expect(
      within(row).queryByRole('button', { name: /^Move ERP Suite onboarding/ }),
    ).not.toBeInTheDocument()
  })

  /**
   * The whole point of the change: one product, several named services. The
   * fixture's product 1 gets a second service here so the grid has to draw
   * two cards for it rather than one.
   */
  it('draws a row per service when one product sells several', async () => {
    getDb().obJourneyTemplates.push({
      id: 99, productId: 1, name: 'ERP Enterprise onboarding', version: 1, isActive: false,
      sequence: 9, dependsOnTemplateIds: [], publishedBy: null, publishedAt: null,
    })
    await openCatalogue()

    expect(await screen.findByRole('link', { name: 'ERP Suite onboarding' }, SLOW)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'ERP Enterprise onboarding' })).toBeInTheDocument()
  })

  it('draws both of a product\'s live services as active, not one', async () => {
    // The two are not alternatives — a client who buys EduTrack ERP is
    // boarded through both, one ribbon each. Publishing the second used to
    // retire the first, which showed here as a single active row.
    await openCatalogue()

    // With state off the row, "active" is what the row *can do*: only an
    // active service holds a position and an editable dependency.
    const standard = serviceRow('ERP Suite onboarding')
    const enterprise = serviceRow('Enterprise (data migration)')
    expect(
      within(standard).getByRole('button', { name: /Services ERP Suite onboarding depends on/ }),
    ).toBeInTheDocument()
    expect(within(standard).queryByText('Not in order')).not.toBeInTheDocument()
    expect(within(enterprise).queryByText('Not in order')).not.toBeInTheDocument()

    const trigger = within(enterprise).getByRole('button', {
      name: /Services Enterprise \(data migration\) depends on/,
    })
    await waitFor(() => expect(trigger).not.toBeDisabled(), SLOW)
    // Held behind template 1 — the dependency the card used to print as a chip.
    expect(trigger).toHaveTextContent('ERP Suite onboarding')
  })

  it('the "Show services for" filter narrows the table to one product', async () => {
    await openCatalogue()

    fireEvent.change(screen.getByLabelText('Show services for'), { target: { value: '1' } })

    // Row links, not bare text — the create and filter selects still offer
    // every product as an <option>, which is not a row.
    await waitFor(() => {
      expect(
        screen.queryByRole('link', { name: 'Biometric Attendance onboarding' }),
      ).not.toBeInTheDocument()
    })
    expect(screen.getByRole('link', { name: 'ERP Suite onboarding' })).toBeInTheDocument()
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
  /*
    The ↑/↓ pair left this screen with the card grid. Re-sequencing is a fact
    about one service, and it is now a field on that service's own admin form
    beside the rename and the product move — which is also the only place a
    draft's position can be set at all.

    Asserted rather than merely deleted, so a "convenient" inline control does
    not drift back onto a row the whole of which is a link.
  */
  it('no longer carries its own reorder buttons', async () => {
    await openCatalogue()

    expect(screen.queryByRole('button', { name: /^Move .* (up|down)$/ })).not.toBeInTheDocument()
  })
})

/**
 * "Depends on" stayed when rename and delete moved to the service's own page:
 * the choice it offers is *the other services*, so it belongs on the one
 * screen where all of them are already on the page. That argument got stronger
 * when it became multi-select — picking three of nine is more of a comparison
 * than picking one.
 *
 * <p>The row around it is itself a link — driving the picker must change the
 * dependencies, not open the service underneath it.
 */
describe('the depends-on picker', () => {
  /** The row's picker trigger, once its template detail has loaded. */
  async function picker(serviceName: string) {
    const row = serviceRow(serviceName)
    const trigger = within(row).getByRole('button', { name: /Services .* depends on/ })
    await waitFor(() => expect(trigger).not.toBeDisabled(), SLOW)
    return trigger
  }

  it('names the current dependencies on the closed trigger', async () => {
    // Set up here rather than in the fixture: see the note on LMS in db.ts.
    getDb().obJourneyTemplates.find((t) => t.id === 3)!.dependsOnTemplateIds = [1, 4]
    await openCatalogue()

    // LMS waits for both ERP services — the trigger names the first and
    // counts the rest, because a table cell cannot hold three service names.
    const trigger = await picker('LMS onboarding')
    expect(trigger).toHaveAccessibleName(
      'Services LMS onboarding depends on — ERP Suite onboarding, Enterprise (data migration)',
    )
    expect(trigger).toHaveTextContent('+1')
  })

  it('says a service with no dependencies runs parallel', async () => {
    await openCatalogue()

    expect(await picker('ERP Suite onboarding')).toHaveTextContent('Runs parallel')
  })

  it('does not offer a service that depends on it, or itself', async () => {
    await openCatalogue()

    fireEvent.click(await picker('ERP Suite onboarding'))

    const offered = (await screen.findAllByRole('option', undefined, SLOW)).map(
      (o) => o.textContent,
    )
    expect(offered).not.toContain('ERP Suite onboarding') // itself
    expect(offered).not.toContain('LMS onboarding') // already depends on ERP
  })

  it('ticking a second service adds to the set rather than replacing it', async () => {
    await openCatalogue()

    // LMS already waits for ERP Suite (template 1); Enterprise (4) is the
    // other candidate and neither reaches LMS, so both may be held at once.
    // A single-select would come out of this holding only the second.
    fireEvent.click(await picker('LMS onboarding'))
    fireEvent.click(await screen.findByRole('option', { name: 'Enterprise (data migration)' }, SLOW))

    await waitFor(() => {
      expect(getDb().obJourneyTemplates.find((t) => t.id === 3)!.dependsOnTemplateIds)
        .toEqual([1, 4])
    }, SLOW)
  })

  it('unticking the last one clears it back to parallel, and persists that', async () => {
    await openCatalogue()

    fireEvent.click(await picker('Enterprise (data migration)'))
    fireEvent.click(await screen.findByRole('option', { name: 'ERP Suite onboarding' }, SLOW))

    await waitFor(() => {
      expect(getDb().obJourneyTemplates.find((t) => t.id === 4)!.dependsOnTemplateIds).toEqual([])
    }, SLOW)
  })

  it('opening the picker does not navigate to the service underneath it', async () => {
    await openCatalogue()

    fireEvent.click(await picker('Enterprise (data migration)'))

    // Still the catalogue: the designer page would have replaced this heading.
    expect(screen.getByRole('heading', { name: 'Module Service' })).toBeInTheDocument()
  })
})
