import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

import type { Me } from '@/api/generated/model'
import { initialAuthState, useAuthStore } from '@/features/auth/authStore'
import { ApiError } from '@/api/http'

/** `ApiError` wants a whole problem document and a `Response`; a test needs neither. */
const apiError = (status: number) =>
  new ApiError(status, { title: `HTTP ${status}` } as never, undefined as never)

import { ObModuleAccessPage } from './ObModuleAccessPage'

/**
 * A-117 · OB-08.
 *
 * <p>The assertions worth having are the ones about the *shape of the write*,
 * not about ticks appearing. A grid over an event log can look completely
 * correct while issuing the wrong calls underneath — a role change that grants
 * without revoking leaves two live grants and the unique index decides which
 * one the guard believes, and a revoke-then-grant fired in parallel makes that
 * a race. Those are what these tests pin.
 */

const listUsers = vi.fn()
const listGrants = vi.fn()
const grantMutate = vi.fn()
const revokeMutate = vi.fn()

vi.mock('@/api/generated/users/users', () => ({
  useListUsers: () => listUsers(),
}))

vi.mock('@/api/generated/onboarding-masters/onboarding-masters', () => ({
  useListObModuleAccess: () => listGrants(),
  useGrantObModuleAccess: () => ({ mutateAsync: grantMutate }),
  useRevokeObModuleAccess: () => ({ mutateAsync: revokeMutate }),
  getListObModuleAccessQueryKey: () => ['/onboarding/module-access'],
}))

const PRIYA = { id: 1, displayName: 'Priya Nair' }
const KAVYA = { id: 7, displayName: 'Kavya Sharma' }

function grant(over: Partial<Record<string, unknown>> = {}) {
  return {
    id: 100,
    user: KAVYA,
    module: 'ONBOARDING',
    moduleRole: 'OB_VIEWER',
    grantedBy: PRIYA,
    grantedAt: '2026-09-01T10:00:00Z',
    isLive: true,
    tokenLagSeconds: 900,
    ...over,
  }
}

function signedInAs(id: number) {
  useAuthStore.setState({
    ...initialAuthState,
    status: 'authenticated',
    user: { id, displayName: 'Priya Nair' } as unknown as Me,
  })
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ObModuleAccessPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  useAuthStore.setState(initialAuthState)
  signedInAs(PRIYA.id)
  grantMutate.mockResolvedValue({})
  revokeMutate.mockResolvedValue({})
  listUsers.mockReturnValue({ data: { data: [PRIYA, KAVYA] }, isPending: false, isError: false })
  listGrants.mockReturnValue({ data: { data: [grant()] }, isPending: false, isError: false })
})

describe('OB-08 · roles and module access', () => {
  it('shows each active user with the access they actually hold', () => {
    renderPage()

    expect(screen.getByRole('checkbox', { name: 'Onboarding access for Kavya Sharma' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Ticketing access for Kavya Sharma' })).not.toBeChecked()
    expect(screen.getByRole('combobox', { name: 'Onboarding role for Kavya Sharma' })).toHaveValue(
      'OB_VIEWER',
    )
  })

  it('grants with a starting role when access is ticked on', async () => {
    listGrants.mockReturnValue({ data: { data: [] }, isPending: false, isError: false })
    renderPage()

    await userEvent.click(screen.getByRole('checkbox', { name: 'Onboarding access for Kavya Sharma' }))

    await waitFor(() =>
      expect(grantMutate).toHaveBeenCalledWith({
        data: { userId: KAVYA.id, module: 'ONBOARDING', moduleRole: 'OB_VIEWER' },
      }),
    )
    expect(revokeMutate).not.toHaveBeenCalled()
  })

  it('revokes rather than deleting when access is ticked off', async () => {
    renderPage()

    await userEvent.click(screen.getByRole('checkbox', { name: 'Onboarding access for Kavya Sharma' }))

    await waitFor(() => expect(revokeMutate).toHaveBeenCalledWith({ grantId: 100 }))
    expect(grantMutate).not.toHaveBeenCalled()
  })

  /**
   * The one that matters. A grant is not edited — `uq_user_module_access_live`
   * permits one live row per (user, module), so a role change must withdraw
   * before it issues, and doing both without ordering them makes which lands
   * first decide the outcome.
   */
  it('changes a role by revoking the old grant before issuing the new one', async () => {
    const order: string[] = []
    revokeMutate.mockImplementation(async () => {
      order.push('revoke')
    })
    grantMutate.mockImplementation(async () => {
      order.push('grant')
    })
    renderPage()

    await userEvent.selectOptions(
      screen.getByRole('combobox', { name: 'Onboarding role for Kavya Sharma' }),
      'OB_ADMIN',
    )

    await waitFor(() => expect(order).toEqual(['revoke', 'grant']))
    expect(grantMutate).toHaveBeenCalledWith({
      data: { userId: KAVYA.id, module: 'ONBOARDING', moduleRole: 'OB_ADMIN' },
    })
  })

  /**
   * Not a server rule — the server is right to allow an admin to revoke their
   * own grant in general. It is wrong *from this screen*, which the next render
   * would remove along with any way back.
   */
  it('will not let you remove your own onboarding access', () => {
    listGrants.mockReturnValue({
      data: { data: [grant({ id: 200, user: PRIYA, moduleRole: 'OB_ADMIN' })] },
      isPending: false,
      isError: false,
    })
    renderPage()

    expect(screen.getByRole('checkbox', { name: 'Onboarding access for Priya Nair' })).toBeDisabled()
    expect(screen.getByRole('combobox', { name: 'Onboarding role for Priya Nair' })).toBeDisabled()
  })

  it('reports the last-admin refusal in the words of the rule', async () => {
    revokeMutate.mockRejectedValue(apiError(422))
    renderPage()

    await userEvent.click(screen.getByRole('checkbox', { name: 'Onboarding access for Kavya Sharma' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/at least one OB Admin/i)
  })

  it('says who to ask when the caller is not an OB Admin', () => {
    listGrants.mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      error: apiError(403),
    })
    renderPage()

    expect(screen.getByRole('alert')).toHaveTextContent(/OB Admin only/i)
  })

  it('reports the token lag from the server rather than a number of its own', () => {
    listGrants.mockReturnValue({
      data: { data: [grant({ tokenLagSeconds: 300 })] },
      isPending: false,
      isError: false,
    })
    renderPage()

    expect(screen.getByText(/up to 300 seconds/i)).toBeInTheDocument()
  })

  it('leaves the grid alone while a row is in flight', async () => {
    let release: (() => void) | undefined
    revokeMutate.mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          release = resolve
        }),
    )
    renderPage()

    const box = screen.getByRole('checkbox', { name: 'Onboarding access for Kavya Sharma' })
    await userEvent.click(box)

    await waitFor(() => expect(box).toBeDisabled())

    // Settled before the test ends, or the resolution lands outside `act`.
    release?.()
    await waitFor(() => expect(box).not.toBeDisabled())
  })

  it('names every onboarding role the server accepts', () => {
    renderPage()

    const select = screen.getByRole('combobox', { name: 'Onboarding role for Kavya Sharma' })
    for (const label of ['OB Admin', 'Onboarding Manager', 'Sales', 'Step Owner', 'Viewer (Management)']) {
      expect(within(select).getByRole('option', { name: label })).toBeInTheDocument()
    }
  })

  /**
   * The way across to where a role is actually defined.
   *
   * <p>This screen grants an existing role and cannot create one; the
   * ticketing role master can. This is now the only header-level route to it —
   * the Module Service catalogue's own button was removed in favour of it.
   */
  it('links to the role master, where roles are actually defined', () => {
    renderPage()

    expect(screen.getByRole('link', { name: /manage roles/i })).toHaveAttribute(
      'href',
      '/masters/roles',
    )
  })

  /**
   * `/masters/roles` is in the ticketing module. Navigating in place swaps the
   * sidebar, and `ModuleSwitcher` renders nothing for somebody holding only
   * ONBOARDING — so in place this is a one-way trip out of the module.
   */
  it('opens it in a new tab rather than leaving the module', () => {
    renderPage()

    const link = screen.getByRole('link', { name: /manage roles/i })
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', expect.stringContaining('noopener'))
  })
})
