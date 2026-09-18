import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { HttpResponse, http } from 'msw'

import { server } from '@/mocks/server'

import { ObClientMasterPage } from './ObClientMasterPage'

/**
 * Ten rows a page on the Clients master, and the walk through them.
 *
 * The list endpoint is stubbed for the reason the Projects grid's own paging
 * test gives: these assertions are about which ten rows are on screen, and a
 * fixture whose size moves with an unrelated seed change would make them facts
 * about the fixture instead.
 */
const ROWS = Array.from({ length: 25 }, (_, index) => {
  const n = index + 1
  return {
    id: n,
    clientCode: `C-${String(n).padStart(3, '0')}`,
    name: `Client ${String(n).padStart(2, '0')}`,
    address: '12 Example Road',
    city: 'Indore',
    journeyCount: 0,
  }
})

/** Every query string the master asked the list endpoint for, in order. */
function stubList(rows = ROWS) {
  const asked: string[] = []

  server.use(
    http.get('*/onboarding/clients', ({ request }) => {
      const url = new URL(request.url)
      asked.push(url.search)

      const limit = Number(url.searchParams.get('limit')) || 50
      const from = Number(url.searchParams.get('cursor') ?? '0')
      const page = rows.slice(from, from + limit)
      const end = from + page.length
      const hasMore = end < rows.length

      return HttpResponse.json({
        data: page,
        meta: { nextCursor: hasMore ? String(end) : null, hasMore },
      })
    }),
  )

  return asked
}

function renderMaster() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/onboarding/clients']}>
        <ObClientMasterPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** The client codes on screen, in order — the header row has none. */
async function clientCodes() {
  const table = await screen.findByRole('table')
  return within(table)
    .getAllByRole('cell')
    .map((cell) => cell.textContent ?? '')
    .filter((text) => /^C-\d\d\d$/.test(text))
}

describe('the Clients master pages ten at a time', () => {
  it('asks for ten and renders the first ten', async () => {
    const asked = stubList()
    renderMaster()

    expect(await clientCodes()).toEqual(ROWS.slice(0, 10).map((row) => row.clientCode))
    expect(asked[0]).toContain('limit=10')
    expect(asked[0]).not.toContain('cursor=')
    expect(await screen.findByText('Rows 1–10')).toBeInTheDocument()
  })

  it('walks forward and back through the pages', async () => {
    const user = userEvent.setup()
    stubList()
    renderMaster()

    await screen.findByText('Rows 1–10')
    await user.click(screen.getByRole('button', { name: /next/i }))

    expect(await screen.findByText('Rows 11–20')).toBeInTheDocument()
    expect(await clientCodes()).toEqual(ROWS.slice(10, 20).map((row) => row.clientCode))

    await user.click(screen.getByRole('button', { name: /previous/i }))

    expect(await screen.findByText('Rows 1–10')).toBeInTheDocument()
    expect(await clientCodes()).toEqual(ROWS.slice(0, 10).map((row) => row.clientCode))
  })

  it('offers no way back from the first page and no way on from the last', async () => {
    const user = userEvent.setup()
    stubList()
    renderMaster()

    await screen.findByText('Rows 1–10')
    expect(screen.getByRole('button', { name: /previous/i })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: /next/i }))
    await screen.findByText('Rows 11–20')
    await user.click(screen.getByRole('button', { name: /next/i }))

    expect(await screen.findByText('Rows 21–25')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /next/i })).toBeDisabled()
    expect(screen.getByRole('button', { name: /previous/i })).toBeEnabled()
  })

  it('returns to the first page when the search changes', async () => {
    const user = userEvent.setup()
    const asked = stubList()
    renderMaster()

    await screen.findByText('Rows 1–10')
    await user.click(screen.getByRole('button', { name: /next/i }))
    await screen.findByText('Rows 11–20')

    await user.type(screen.getByLabelText(/search clients/i), 'a')

    expect(await screen.findByText('Rows 1–10')).toBeInTheDocument()
    expect(asked[asked.length - 1]).toContain('q=a')
    expect(asked[asked.length - 1]).not.toContain('cursor=')
  })
})
