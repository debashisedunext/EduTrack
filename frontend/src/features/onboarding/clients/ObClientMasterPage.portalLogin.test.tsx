import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

import { ObClientMasterPage } from './ObClientMasterPage'

/**
 * The portal login the add dialog can issue, against the mock server.
 *
 * The company-only half of this screen is covered by the master page's own
 * tests; everything here is about the tick box, the two fields it reveals and
 * the credentials that come back.
 */
function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ObClientMasterPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

async function openAddDialog(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: /add client/i }))
  return screen.findByRole('dialog')
}

describe('the add dialog’s portal login', () => {
  it('asks for no contact until the box is ticked', async () => {
    const user = userEvent.setup()
    renderPage()
    await openAddDialog(user)

    expect(screen.queryByLabelText(/contact name/i)).not.toBeInTheDocument()

    await user.click(screen.getByLabelText(/create client portal login/i))

    expect(await screen.findByLabelText(/contact name/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/contact email/i)).toBeInTheDocument()
  })

  /**
   * Both at once. A form with two things missing that reports them one at a
   * time is two round trips to learn what one would have said.
   */
  it('refuses a ticked box with no contact, naming both fields', async () => {
    const user = userEvent.setup()
    renderPage()
    await openAddDialog(user)

    await user.type(screen.getByLabelText(/client name/i), 'Little Flower School')
    await user.type(screen.getByLabelText(/client code/i), 'LFS-001')
    await user.click(screen.getByLabelText(/create client portal login/i))
    await user.click(screen.getByRole('button', { name: /save client/i }))

    expect(await screen.findByText(/issued to a person/i)).toBeInTheDocument()
    expect(screen.getByText(/one-time link is mailed/i)).toBeInTheDocument()
    // Nothing was sent, so the dialog is still open on the operator's input.
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('issues the login under the client code and shows the credentials once', async () => {
    const user = userEvent.setup()
    renderPage()
    await openAddDialog(user)

    await user.type(screen.getByLabelText(/client name/i), 'Little Flower School')
    await user.type(screen.getByLabelText(/client code/i), 'LFS-001')
    await user.click(screen.getByLabelText(/create client portal login/i))
    await user.type(await screen.findByLabelText(/contact name/i), 'Arjun Singh')
    await user.type(screen.getByLabelText(/contact email/i), 'arjun@lfs.example')
    await user.click(screen.getByRole('button', { name: /save client/i }))

    // The username is the client code, unchanged — hyphen included.
    expect(await screen.findByTestId('portal-username')).toHaveTextContent('LFS-001')
    expect(screen.getByTestId('portal-password')).toHaveTextContent('Demo-Passw0rd!')
  })

  it('leaves no credential on screen when the box is not ticked', async () => {
    const user = userEvent.setup()
    renderPage()
    await openAddDialog(user)

    await user.type(screen.getByLabelText(/client name/i), 'Quiet Company')
    await user.type(screen.getByLabelText(/client code/i), 'QC-001')
    await user.click(screen.getByRole('button', { name: /save client/i }))

    // The row lands and no credential dialog stands in the way — the whole
    // point of the flag being absent.
    expect(await screen.findByRole('button', { name: /edit quiet company/i })).toBeInTheDocument()
    expect(screen.queryByTestId('portal-password')).not.toBeInTheDocument()
    expect(screen.queryByTestId('portal-username')).not.toBeInTheDocument()
  })
})
