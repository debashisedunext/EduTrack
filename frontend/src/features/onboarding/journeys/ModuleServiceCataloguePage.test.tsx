import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

import { getDb } from '@/mocks/db'
import { Toaster } from '@/components/ui/toaster'

import { ModuleServiceCataloguePage } from './ModuleServiceCataloguePage'

/**
 * C-123 · the Module Service catalogue against the mock server.
 *
 * <p>Fixture note — `db.ts`'s `OB_PRODUCTS`/`OB_JOURNEY_TEMPLATES`: ERP
 * (product 1, template 1) is active, sequence 1. LMS (product 3, template 3)
 * is a *retired* product with its own active, published template —
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
  await screen.findByRole('heading', { name: 'Module Service' })
  // Wait for the product list itself, not just the header. A link, not
  // plain text: LMS's own "Service depends on" picker also offers "ERP
  // Suite" as an <option>, so a bare findByText matches both.
  await screen.findByRole('link', { name: 'ERP Suite' }, SLOW)
}

const productCard = (name: string) =>
  screen.getAllByRole('listitem').find((li) => within(li).queryByText(name))!

describe('the catalogue lists every product', () => {
  it('shows an active service with its total TAT and journey count', async () => {
    await openCatalogue()

    const card = productCard('ERP Suite')
    expect(within(card).getByText('ERP')).toBeInTheDocument()
    expect(within(card).getByText(/working days? total/)).toBeInTheDocument()
  })

  it('shows a product with no active service as a dead end, not a broken card', async () => {
    await openCatalogue()

    const card = productCard('Biometric Attendance')
    expect(within(card).getByText('No active Module Service yet')).toBeInTheDocument()
    expect(within(card).queryByLabelText('Service depends on')).not.toBeInTheDocument()
  })

  it('the product filter narrows the list to one card', async () => {
    await openCatalogue()

    fireEvent.click(screen.getByRole('button', { name: /Product/ }))
    const listbox = await screen.findByRole('listbox')
    fireEvent.click(within(listbox).getByRole('option', { name: 'ERP Suite' }))

    await waitFor(() => {
      expect(screen.queryByText('Biometric Attendance')).not.toBeInTheDocument()
    })
    expect(screen.getByRole('link', { name: 'ERP Suite' })).toBeInTheDocument()
  })
})

describe('reordering the catalogue', () => {
  it('moves a service down and persists the new sequence', async () => {
    await openCatalogue()

    const erpCard = productCard('ERP Suite')
    fireEvent.click(within(erpCard).getByRole('button', { name: 'Move ERP Suite down' }))

    await waitFor(() => {
      const templates = getDb().obJourneyTemplates
      expect(templates.find((t) => t.id === 1)!.sequence).toBe(1)
      expect(templates.find((t) => t.id === 3)!.sequence).toBe(0)
    }, SLOW)
  })

  it('the topmost service cannot move up, the bottommost cannot move down', async () => {
    await openCatalogue()

    const erpCard = productCard('ERP Suite')
    const lmsCard = productCard('Learning Management')
    expect(within(erpCard).getByRole('button', { name: 'Move ERP Suite up' })).toBeDisabled()
    expect(within(lmsCard).getByRole('button', { name: 'Move Learning Management down' })).toBeDisabled()
  })

  it('reorder controls are hidden once a product filter narrows the list', async () => {
    await openCatalogue()

    fireEvent.click(screen.getByRole('button', { name: /Product/ }))
    const listbox = await screen.findByRole('listbox')
    fireEvent.click(within(listbox).getByRole('option', { name: 'ERP Suite' }))

    await waitFor(() => {
      expect(screen.queryByRole('button', { name: 'Move ERP Suite up' })).not.toBeInTheDocument()
    })
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

    const erpCard = productCard('ERP Suite')
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
