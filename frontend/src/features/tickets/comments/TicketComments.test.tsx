import { beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import { getDb } from '@/mocks/db'
import { TicketDetailPage } from '../detail/TicketDetailPage'

/**
 * C-029 · the box and the thread on S-20, end to end through the mock server.
 *
 * The unit-level behaviour of the composer is `CommentBox.test.tsx`. What this
 * file is for is the wiring the unit tests cannot see: that the box is outside
 * the tab strip, that the thread is inside it, and that posting makes the
 * comment appear without a reload — which is the whole of what §4B.5 asks the
 * detail page for.
 */

const TICKET = 'CRM-26-00347'

beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  const element = Element.prototype as unknown as Record<string, unknown>
  element.hasPointerCapture ??= () => false
  element.setPointerCapture ??= () => {}
  element.releasePointerCapture ??= () => {}
  element.scrollIntoView ??= () => {}
})

/**
 * The §14 walkthrough ticket is Meera's and the mock signs in Ravi, a Developer
 * scoped to `assigned_to = me`. Signing in as the Admin is how the fixture
 * becomes visible — the same move `TicketDetailPage.test.tsx` makes and for the
 * same reason.
 */
beforeEach(() => {
  getDb().currentUserId = 1
})

function renderPage(entry = `/tickets/${TICKET}`) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path="/tickets/:ticketId" element={<TicketDetailPage />} />
          <Route path="/tickets" element={<p>Ticket list</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const waitForTicket = () =>
  screen.findByRole('heading', { name: /Checkout fails with 500/ }, { timeout: 4000 })

const commentEditor = () => screen.getByRole('textbox', { name: 'Comment' })

function write(html: string) {
  const box = commentEditor()
  box.innerHTML = html
  fireEvent.input(box)
}

const openComments = () => fireEvent.click(screen.getByRole('tab', { name: 'Comments' }))

/**
 * Scoped to the thread's own list.
 *
 * A bare `findAllByRole('listitem')` also matches the breadcrumb, and its first
 * item is the "Tickets" link rather than the oldest comment — which is a test
 * that fails for a reason with nothing to do with comments, or worse, passes.
 */
const thread = () => screen.findByRole('list', { name: 'Comments' })
const commentsIn = async () => within(await thread()).getAllByRole('listitem')

describe('the comment box on S-20', () => {
  /**
   * §7: "the comment box itself is always visible above the tabs so posting
   * never costs a click". The Journey tab is the default, so finding the editor
   * without touching the tab strip is the assertion.
   */
  it('is visible without opening the Comments tab', async () => {
    renderPage()
    await waitForTicket()

    expect(screen.getByRole('tab', { name: 'Journey' })).toHaveAttribute('aria-selected', 'true')
    expect(commentEditor()).toBeInTheDocument()
  })

  it('stays visible while the Comments tab is open', async () => {
    renderPage()
    await waitForTicket()
    openComments()

    expect(commentEditor()).toBeInTheDocument()
  })

  it('posts a comment and shows it in the thread without a reload', async () => {
    renderPage()
    await waitForTicket()
    openComments()

    await screen.findByText(/Reproduced on prod with two Acme accounts/)

    write('<p>Retested on staging, all three flows pass.</p>')
    fireEvent.click(screen.getByRole('button', { name: 'Post' }))

    expect(await screen.findByText(/Retested on staging, all three flows pass/)).toBeInTheDocument()
    // The draft is gone, which is how the box says the server took it.
    await waitFor(() => expect(commentEditor()).toHaveTextContent(''))
  })

  /**
   * The mock refuses a blank body with the contract's 400. The box's own guard
   * should stop it ever being sent — this pins that the guard is the reason,
   * rather than the round trip happening to succeed.
   */
  it('never sends a body the server would refuse as blank', async () => {
    renderPage()
    await waitForTicket()

    write('<p><br></p>')

    expect(screen.getByRole('button', { name: 'Post' })).toBeDisabled()
  })

  describe('a sealed cycle', () => {
    it('takes no new comments but still reads the old ones', async () => {
      renderPage(`/tickets/${TICKET}?cycle=1&tab=comments`)
      await waitForTicket()

      expect(screen.queryByRole('textbox', { name: 'Comment' })).not.toBeInTheDocument()
      // The box's own reason, not the page's sealed-cycle banner — both say
      // "sealed", and a loose matcher here would pass on the banner alone even
      // if the box explained nothing.
      expect(
        screen.getByText(/new ones belong to the current cycle/i),
      ).toBeInTheDocument()
      expect(await screen.findByText(/Reproduced on prod with two Acme accounts/)).toBeInTheDocument()
    })
  })
})

describe('the Comments tab', () => {
  it('renders the thread oldest first', async () => {
    renderPage()
    await waitForTicket()
    openComments()

    const bodies = (await commentsIn()).map((item) => item.textContent ?? '')

    const reproduced = bodies.findIndex((t) => t.includes('Reproduced on prod'))
    const tokenRefresh = bodies.findIndex((t) => t.includes('Token refresh was missing'))

    expect(reproduced).toBeGreaterThanOrEqual(0)
    // A conversation reads top to bottom — the first comment is what gives the
    // rest their context. Every other list in the product is newest-first.
    expect(reproduced).toBeLessThan(tokenRefresh)
  })

  it('names the author and their role', async () => {
    renderPage()
    await waitForTicket()
    openComments()

    const first = (await commentsIn())[0]
    expect(within(first).getByText('Priya Nair')).toBeInTheDocument()
    expect(within(first).getByText('SUPPORT')).toBeInTheDocument()
  })

  /**
   * Until C-031 draws the colour, the fact still has to be on screen: a comment
   * going to the client that looks exactly like one that is not is the mistake
   * §4B.5 exists to prevent.
   */
  it('marks a client-visible comment', async () => {
    renderPage()
    await waitForTicket()
    openComments()

    const items = await commentsIn()
    const signoff = items.find((item) => item.textContent?.includes('Fix is live'))

    expect(signoff).toBeDefined()
    expect(within(signoff!).getByText('Client visible')).toBeInTheDocument()
    // And the internal ones are not marked.
    const reproduced = items.find((item) => item.textContent?.includes('Reproduced on prod'))
    expect(within(reproduced!).queryByText('Client visible')).not.toBeInTheDocument()
  })
})
