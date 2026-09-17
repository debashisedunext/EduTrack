import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { HttpResponse, http } from 'msw'

import { server } from '@/mocks/server'

import { ObProjectListPage } from './ObProjectListPage'

/**
 * Ten rows a page, and the walk through them.
 *
 * The list endpoint is stubbed rather than driven off the fixture DB: the
 * assertions here are about *which* ten rows are on screen and what was asked
 * for, and a fixture whose size moves with an unrelated seed change would make
 * "page three holds five rows" a fact about the fixture rather than about the
 * pager.
 *
 * The stub's cursor is an offset, which the real server's deliberately is not —
 * it pages by keyset. That difference does not reach this screen: the grid
 * treats `nextCursor` as opaque and only ever hands it back, which is the
 * property being tested.
 */
const ROWS = Array.from({ length: 25 }, (_, index) => {
  const n = index + 1
  return {
    id: n,
    name: `Project ${String(n).padStart(2, '0')}`,
    client: { id: 1, clientCode: 'ACME', name: 'Acme Trust' },
    product: { id: 1, name: 'ERP' },
    startDate: '2026-01-05',
    salesPerson: null,
    implementor: null,
    status: 'RUNNING',
    gateStatus: 'OPEN',
    currentStage: 'Configuration',
    stagesComplete: 1,
    stagesTotal: 5,
    journeyCount: 1,
    delayedByDays: null,
    tentativeCompletion: '2026-03-01',
    totalTatDays: 40,
  }
})

/** Every query string the grid asked the list endpoint for, in order. */
function stubList(rows = ROWS) {
  const asked: string[] = []

  server.use(
    http.get('*/onboarding/projects', ({ request }) => {
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

function renderGrid() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/onboarding/projects']}>
        <ObProjectListPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** The project names on screen, in order — the header row has none. */
async function projectNames() {
  const table = await screen.findByRole('table')
  return within(table)
    .getAllByRole('link')
    .map((link) => link.textContent)
    .filter((text): text is string => /^Project \d\d$/.test(text ?? ''))
}

describe('the Projects grid pages ten at a time', () => {
  it('asks for ten and renders the first ten', async () => {
    const asked = stubList()
    renderGrid()

    expect(await projectNames()).toEqual(
      ROWS.slice(0, 10).map((row) => row.name),
    )
    expect(asked[0]).toContain('limit=10')
    expect(asked[0]).not.toContain('cursor=')
    expect(await screen.findByText('Rows 1–10')).toBeInTheDocument()
  })

  it('walks forward and back through the pages', async () => {
    const user = userEvent.setup()
    stubList()
    renderGrid()

    await screen.findByText('Rows 1–10')
    await user.click(screen.getByRole('button', { name: /next/i }))

    expect(await screen.findByText('Rows 11–20')).toBeInTheDocument()
    expect(await projectNames()).toEqual(ROWS.slice(10, 20).map((row) => row.name))

    await user.click(screen.getByRole('button', { name: /previous/i }))

    expect(await screen.findByText('Rows 1–10')).toBeInTheDocument()
    expect(await projectNames()).toEqual(ROWS.slice(0, 10).map((row) => row.name))
  })

  it('offers no way back from the first page and no way on from the last', async () => {
    const user = userEvent.setup()
    stubList()
    renderGrid()

    await screen.findByText('Rows 1–10')
    expect(screen.getByRole('button', { name: /previous/i })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: /next/i }))
    await screen.findByText('Rows 11–20')
    await user.click(screen.getByRole('button', { name: /next/i }))

    // Five rows on the last page, and `hasMore` false — so Next is spent.
    expect(await screen.findByText('Rows 21–25')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /next/i })).toBeDisabled()
    expect(screen.getByRole('button', { name: /previous/i })).toBeEnabled()
  })

  /**
   * The failure this guards is silent. A cursor is a position in one ordered
   * result set; resumed under a different filter it returns rows from an
   * arbitrary place rather than an error, and the grid would show them as an
   * answer.
   */
  it('returns to the first page when a filter changes', async () => {
    const user = userEvent.setup()
    const asked = stubList()
    renderGrid()

    await screen.findByText('Rows 1–10')
    await user.click(screen.getByRole('button', { name: /next/i }))
    await screen.findByText('Rows 11–20')

    await user.type(screen.getByLabelText(/search/i), 'a')

    expect(await screen.findByText('Rows 1–10')).toBeInTheDocument()
    expect(asked[asked.length - 1]).toContain('q=a')
    expect(asked[asked.length - 1]).not.toContain('cursor=')
  })
})
