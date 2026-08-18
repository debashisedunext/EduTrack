import { describe, expect, it } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { server } from '@/mocks/server'

import { AuditLogPage } from './AuditLogPage'
import { actionLabel, isRefusal, moduleLabel } from './auditVocabulary'

/**
 * A-071 · S-16 against the mock server.
 *
 * <p>The assertions worth making on this screen are about what it refuses to
 * claim. An audit viewer is read as a record, so the failures that matter are
 * the ones where it says something slightly untrue and looks completely
 * normal: a timestamp shifted into the reader's timezone, a system action
 * attributed to a person, a deleted user's row rendered as if nobody did it, or
 * a "no entries" that is really a permission refusal.
 */

const ENTRIES = [
  {
    id: 3,
    actor: { id: 7, displayName: 'Ravi Kumar', role: 'ADMIN' },
    action: 'PERMISSIONS_UPDATED',
    entityType: 'masters',
    entityId: '4',
    ipAddress: '203.0.113.9',
    userAgent: 'Mozilla/5.0',
    detail: { old: 'ticket.create', new: 'ticket.create,ticket.assign' },
    createdAt: '2026-08-18T09:15:00.123456Z',
  },
  {
    id: 2,
    actor: null,
    action: 'CHAIN_VERIFIED',
    entityType: 'tickets',
    entityId: 'CRM-26-00347',
    ipAddress: null,
    userAgent: null,
    detail: null,
    createdAt: '2026-08-18T03:00:00Z',
  },
  {
    id: 1,
    actor: { id: 12, displayName: 'Deleted user #12', role: null },
    action: 'LOGIN_FAILED',
    entityType: 'users',
    entityId: null,
    ipAddress: '198.51.100.4',
    userAgent: 'curl/8',
    detail: { new: 'jsmith' },
    createdAt: '2026-08-17T22:41:09Z',
  },
]

function renderPage(initialEntry = '/audit-logs') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <AuditLogPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function respondWith(entries: unknown[], meta: unknown = { nextCursor: null, hasMore: false }) {
  server.use(
    http.get('*/audit-logs', () => HttpResponse.json({ data: entries, meta })),
    http.get('*/users', () => HttpResponse.json({ data: [] })),
  )
}

describe('AuditLogPage', () => {
  it('lists entries, most recent first', async () => {
    respondWith(ENTRIES)
    renderPage()

    const rows = await screen.findAllByRole('row')
    // Header plus three entries.
    expect(rows).toHaveLength(4)
    expect(within(rows[1]).getByText('Permissions updated')).toBeInTheDocument()
  })

  /**
   * The one that would be caught last and cost the most. Every other screen in
   * the product renders in the user's timezone; this one must not, because an
   * audit extract is read beside server logs and mail headers that are all UTC.
   * The suite runs in whatever zone the machine is in, so asserting the literal
   * string is the assertion — a `Date`-formatted cell would drift with the
   * runner.
   */
  it('shows times as UTC, unshifted by the browser timezone', async () => {
    respondWith(ENTRIES)
    renderPage()

    expect(await screen.findByText('2026-08-18 09:15:00')).toBeInTheDocument()
    expect(screen.getByText('2026-08-17 22:41:09')).toBeInTheDocument()
  })

  /**
   * A null actor is a scanner, not a person. Rendering it as a blank cell, or
   * worse as the previous row's actor, is how an automated action gets
   * attributed to whoever happens to be nearby in the list.
   */
  it('names an actorless entry as System', async () => {
    respondWith(ENTRIES)
    renderPage()

    const rows = await screen.findAllByRole('row')
    expect(within(rows[2]).getByText('System')).toBeInTheDocument()
  })

  /**
   * An audit row outlives its actor — that is what the server's `LEFT JOIN` is
   * for. If a removed account rendered as System, "somebody did this and then
   * left" would be indistinguishable from "nobody did this".
   */
  it('keeps a row whose user has been deleted, and says so', async () => {
    respondWith(ENTRIES)
    renderPage()

    expect(await screen.findByText('Deleted user #12')).toBeInTheDocument()
  })

  it('renders a ticket code as the record, not a blank', async () => {
    respondWith(ENTRIES)
    renderPage()

    expect(await screen.findByText('CRM-26-00347')).toBeInTheDocument()
  })

  /**
   * Absent detail is most rows — the interceptor records that a request
   * happened, not what changed underneath it. A dash reads as deliberately
   * blank; an empty cell reads as a value that failed to load.
   */
  it('renders a missing detail as a dash rather than an empty cell', async () => {
    respondWith([ENTRIES[1]])
    renderPage()

    const rows = await screen.findAllByRole('row')
    expect(within(rows[1]).getAllByText('—').length).toBeGreaterThan(0)
  })

  /**
   * The distinction the whole screen depends on. A 403 rendered as "no entries
   * match" tells an Admin their audit log is empty — which is the single most
   * reassuring and most wrong thing this page could say.
   */
  it('reports a refusal as a refusal, never as an empty log', async () => {
    server.use(
      http.get('*/audit-logs', () =>
        HttpResponse.json({ error: { status: 403 } }, { status: 403 }),
      ),
      http.get('*/users', () => HttpResponse.json({ data: [] })),
    )
    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent(/Admins only/i)
    expect(screen.queryByText(/No entries match/i)).not.toBeInTheDocument()
  })

  it('says so plainly when nothing matches', async () => {
    respondWith([])
    renderPage()

    expect(await screen.findByText(/No entries match/i)).toBeInTheDocument()
  })

  /**
   * Load more extends the list rather than replacing it, and the count is a
   * claim about the rows on screen — never about the table, which is not
   * counted.
   */
  it('accumulates rows on Load more', async () => {
    let call = 0
    server.use(
      http.get('*/audit-logs', () => {
        call += 1
        return call === 1
          ? HttpResponse.json({
              data: [ENTRIES[0]],
              meta: { nextCursor: 'next', hasMore: true },
            })
          : HttpResponse.json({ data: [ENTRIES[2]], meta: { nextCursor: null, hasMore: false } })
      }),
      http.get('*/users', () => HttpResponse.json({ data: [] })),
    )
    renderPage()

    expect(await screen.findByText(/Showing 1 entry/)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /Load more/i }))

    await waitFor(() => expect(screen.getByText(/Showing 2 entries/)).toBeInTheDocument())
    expect(screen.getByText('Permissions updated')).toBeInTheDocument()
    expect(screen.getByText('Login failed')).toBeInTheDocument()
  })

  /**
   * The filters go to the server, and the URL is what holds them — so a link
   * somebody pastes into a ticket opens the same query.
   */
  it('sends the filters from the URL', async () => {
    let seen: URL | undefined
    server.use(
      http.get('*/audit-logs', ({ request }) => {
        seen = new URL(request.url)
        return HttpResponse.json({ data: [], meta: { nextCursor: null, hasMore: false } })
      }),
      http.get('*/users', () => HttpResponse.json({ data: [] })),
    )
    renderPage('/audit-logs?actorId=7&action=LOGIN_FAILED&entityType=users&from=2026-08-01')

    await waitFor(() => expect(seen).toBeDefined())
    expect(seen?.searchParams.get('actorId')).toBe('7')
    expect(seen?.searchParams.get('action')).toBe('LOGIN_FAILED')
    expect(seen?.searchParams.get('entityType')).toBe('users')
    expect(seen?.searchParams.get('from')).toBe('2026-08-01')
  })

  /**
   * Said once at the top, and the reason it is asserted: this sentence is the
   * screen's contract with its reader. A future edit that adds a row action
   * should have to delete it deliberately.
   */
  it('states that the log cannot be edited', async () => {
    respondWith(ENTRIES)
    renderPage()

    expect(await screen.findByText(/append-only/i)).toBeInTheDocument()
  })
})

describe('the vocabulary', () => {
  it('reads a derived term as English', () => {
    expect(actionLabel('PERMISSIONS_UPDATED')).toBe('Permissions updated')
    expect(actionLabel('LOGIN_SUCCESS')).toBe('Login success')
  })

  /**
   * The property that makes a client-side vocabulary safe: a term nobody wrote
   * down still renders, tidied rather than hidden. Terms are derived from the
   * route table, so this is the normal case for anything added after today.
   */
  it('renders a term it has never seen', () => {
    expect(actionLabel('WIDGETS_CREATED')).toBe('Widgets created')
    expect(moduleLabel('widgets')).toBe('Widgets')
  })

  it('gives a known module its caption', () => {
    expect(moduleLabel('import_batches')).toBe('Imports')
    expect(moduleLabel('users')).toBe('Sign-in & accounts')
  })

  it('marks refusals and nothing else', () => {
    expect(isRefusal('ACCESS_DENIED')).toBe(true)
    expect(isRefusal('LOGIN_FAILED')).toBe(true)
    expect(isRefusal('LOGIN_2FA_FAILED')).toBe(true)
    expect(isRefusal('LOGIN_SUCCESS')).toBe(false)
    expect(isRefusal('TICKETS_CREATED')).toBe(false)
    expect(isRefusal(undefined)).toBe(false)
  })
})
