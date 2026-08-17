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

/**
 * @param label which editor. The composer is "Comment"; C-033's inline editor is
 *              "Edit comment", and both are on screen at once while a card is
 *              open — an unqualified `getByRole('textbox')` throws on exactly
 *              the tests that matter.
 */
function write(html: string, label: 'Comment' | 'Edit comment' = 'Comment') {
  const box = screen.getByRole('textbox', { name: label })
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

/**
 * The card in the thread whose text contains `needle`, once the post has
 * actually round-tripped.
 *
 * ⚠ **`findByText` is the wrong tool for this and looks like the right one.**
 * The composer is a contentEditable holding the very paragraph being posted, so
 * `screen.findByText(/Retested on staging/)` resolves *immediately* against the
 * draft — before the request is sent, and identically if the server refuses it.
 * A test written that way passes on a build where posting is completely broken,
 * which is what it was doing here until C-031 tried to assert something about
 * the posted card and got the editor's own `<p>` back.
 *
 * Scoping to the thread's `<li>` is what makes the assertion mean "the server
 * took it and the thread re-read it", and the `waitFor` is what waits for the
 * invalidated query rather than the keystroke.
 */
async function postedCard(needle: string): Promise<HTMLElement> {
  return waitFor(() => {
    const found = within(screen.getByRole('list', { name: 'Comments' }))
      .getAllByRole('listitem')
      .find((item) => item.textContent?.includes(needle))
    expect(found).toBeDefined()
    return found!
  })
}

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

    // In the thread's own list, not merely somewhere on the page — see
    // `postedCard`. The version of this that read `findByText` was matching the
    // draft still sitting in the composer.
    expect(await postedCard('Retested on staging, all three flows pass')).toBeInTheDocument()
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
   * A comment going to the client that looks exactly like one that is not is
   * the mistake §4B.5 exists to prevent — so the label, and since C-031 the two
   * backgrounds §4B.5 specifies as well.
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

    // C-031 · §4B.5's pair, read in the blueprint's own order: "Internal note
    // (grey background)… Client visible (white)". Before this task both were
    // white, which meant every comment looked like the safe kind.
    expect(signoff!.className).toContain('bg-surface')
    expect(reproduced!.className).toContain('bg-subtle')
  })
})

/**
 * C-031 · the toggle, through the mock server rather than a stubbed `onPost`.
 *
 * `CommentBox.test.tsx` proves the composer passes the flag to its callback.
 * What that cannot see is the rest of the path: that `TicketDetailPage` hands
 * the composer a client at all, that `useTicketComments` puts `isClientVisible`
 * in the request body, that the mock stores it and that the thread reads it
 * back marked. Four seams, each of which has been the one that was wrong in a
 * feature that unit-tested clean — C-028's delete button worked against the
 * mock and did nothing in production for three tasks.
 */
describe('C-031 · posting a client-visible comment', () => {
  const clientVisibleRadio = () => screen.getByRole('radio', { name: /Client visible/ })

  it('offers the toggle on a ticket that has a client, defaulted to internal', async () => {
    renderPage()
    await waitForTicket()

    expect(screen.getByRole('radiogroup', { name: 'Visibility' })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /Internal note/ })).toBeChecked()
    // The client's real name, from the ticket — not the word "client".
    expect(clientVisibleRadio()).toHaveAccessibleName(/Acme Retail Ltd/)
  })

  it('posts it client-visible and the thread shows it marked', async () => {
    renderPage()
    await waitForTicket()
    openComments()
    await screen.findByText(/Reproduced on prod with two Acme accounts/)

    fireEvent.click(clientVisibleRadio())
    write('<p>Root cause fixed, live at 18:40.</p>')
    fireEvent.click(screen.getByRole('button', { name: 'Post' }))

    const posted = await postedCard('Root cause fixed, live at 18:40')

    expect(within(posted).getByText('Client visible')).toBeInTheDocument()
    expect(posted.className).toContain('border-warning')
    expect(posted.className).toContain('bg-surface')
  })

  /**
   * The default is only worth anything if it is also where the composer returns
   * to. This is the second comment — the one that leaks in the failure §17
   * describes, because the first was a deliberate client-facing summary and
   * nothing on screen changed in between.
   */
  it('leaves the next comment internal, and posts it internal', async () => {
    renderPage()
    await waitForTicket()
    openComments()
    await screen.findByText(/Reproduced on prod with two Acme accounts/)

    fireEvent.click(clientVisibleRadio())
    write('<p>Root cause fixed, live at 18:40.</p>')
    fireEvent.click(screen.getByRole('button', { name: 'Post' }))
    await postedCard('Root cause fixed, live at 18:40')

    await waitFor(() => expect(screen.getByRole('radio', { name: /Internal note/ })).toBeChecked())

    write('<p>Stack trace was in the retry handler, not the gateway.</p>')
    fireEvent.click(screen.getByRole('button', { name: 'Post' }))

    const posted = await postedCard('Stack trace was in the retry handler')
    expect(within(posted).queryByText('Client visible')).not.toBeInTheDocument()
    expect(posted.className).toContain('bg-subtle')
  })
})

/**
 * C-033 · §4B.5's immutability row, through the mock server.
 *
 * The rules themselves are `commentPermissions.test.ts` (pure, and the only
 * place the window boundary can be asserted without waiting) and
 * `CommentServiceTest` (the side that enforces them). What this file adds is the
 * wiring neither can see: that the affordances reach the right cards, that an
 * edit round-trips through the server rather than only through local state, and
 * that a removal leaves something on screen rather than a gap.
 *
 * The signed-in user is **Anita, the Admin** (`currentUserId = 1`, set in this
 * file's `beforeEach`), and the seeded comment is **Priya's**. That pairing is
 * what makes the asymmetry testable end to end in one fixture: an Admin may
 * remove Priya's comment and may not edit it.
 */
describe('C-033 · the edit window and the tombstone', () => {
  const priyasCard = async () =>
    within(await screen.findByRole('list', { name: 'Comments' }))
      .getAllByRole('listitem')
      .find((item) => item.textContent?.includes('Reproduced on prod with two Acme accounts'))!

  /**
   * Waits for the inline editor to close, which happens **only on success** —
   * `CommentCard.save` keeps it open with the draft intact on a refusal.
   *
   * This exists because `postedCard` is not enough here, and the reason is
   * C-031's trap wearing a new hat. That helper looks for a `<li>` whose text
   * contains the needle, and while the editor is open the editor is *inside*
   * that `<li>` — so it matches the draft the user has typed and resolves before
   * the request has been anywhere near the server. Both of these tests passed
   * their `postedCard` call and then failed on the assertion after it, which is
   * the failure reading as "the edit did not save" when the edit had not been
   * given a chance to.
   */
  const editorClosed = () =>
    waitFor(() =>
      expect(screen.queryByRole('textbox', { name: 'Edit comment' })).not.toBeInTheDocument(),
    )

  /**
   * §4B.5: "no role, including Admin, can silently rewrite a comment". The Admin
   * is the strongest case available and the button must not be there.
   */
  it('offers no Edit on somebody else’s comment, even to an Admin', async () => {
    renderPage()
    await waitForTicket()
    openComments()

    const card = await priyasCard()
    expect(within(card).queryByRole('button', { name: /^Edit/ })).not.toBeInTheDocument()
    // …while Remove *is* offered, which is the asymmetry rather than a
    // permissions bug. Asserted here so the two cannot drift apart unnoticed.
    expect(within(card).getByRole('button', { name: /Remove/ })).toBeInTheDocument()
  })

  it('lets the author edit their own comment, and marks it edited', async () => {
    renderPage()
    await waitForTicket()
    openComments()
    await screen.findByText(/Reproduced on prod with two Acme accounts/)

    write('<p>Deployed the fix at 18:40.</p>')
    fireEvent.click(screen.getByRole('button', { name: 'Post' }))
    const mine = await postedCard('Deployed the fix at 18:40')

    fireEvent.click(within(mine).getByRole('button', { name: /^Edit/ }))
    write('<p>Deployed the fix at 18:55, after a rollback.</p>', 'Edit comment')
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await editorClosed()

    const edited = await postedCard('Deployed the fix at 18:55, after a rollback')
    expect(within(edited).getByText(/edited/)).toBeInTheDocument()
    // The old wording is gone from the card, not merely hidden behind the new
    // one — the thread renders `body`, and `originalBody` only on request.
    expect(within(edited).queryByText(/Deployed the fix at 18:40/)).not.toBeInTheDocument()
  })

  /**
   * §4B.5's "with the original preserved". Behind a disclosure rather than
   * always drawn — but it has to actually be reachable, which is the half a
   * server-side test cannot show.
   */
  it('keeps the original behind a disclosure', async () => {
    renderPage()
    await waitForTicket()
    openComments()
    await screen.findByText(/Reproduced on prod with two Acme accounts/)

    write('<p>First wording, with a typo.</p>')
    fireEvent.click(screen.getByRole('button', { name: 'Post' }))
    const mine = await postedCard('First wording, with a typo')

    fireEvent.click(within(mine).getByRole('button', { name: /^Edit/ }))
    write('<p>Second wording, corrected.</p>', 'Edit comment')
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await editorClosed()

    const edited = await postedCard('Second wording, corrected')
    expect(within(edited).queryByText(/First wording/)).not.toBeInTheDocument()

    fireEvent.click(within(edited).getByRole('button', { name: /Show what was first posted/ }))
    await waitFor(() =>
      expect(within(edited).getByText(/First wording, with a typo/)).toBeInTheDocument(),
    )
  })

  /**
   * The card becomes a tombstone rather than disappearing — that is the whole
   * of §4B.5's "deletion leaves a tombstone", and a test asserting only that the
   * text is gone would pass on an implementation that hard-deleted the row.
   */
  it('leaves a tombstone naming who removed it', async () => {
    renderPage()
    await waitForTicket()
    openComments()

    const card = await priyasCard()
    fireEvent.click(within(card).getByRole('button', { name: /Remove/ }))

    await waitFor(() =>
      expect(screen.queryByText(/Reproduced on prod with two Acme accounts/)).not.toBeInTheDocument(),
    )
    // Anita is the signed-in Admin. The name is the point: a bare "comment
    // removed" is the record §4B.5 asked for minus the fact that matters.
    expect(await screen.findByText(/Comment removed by Anita/)).toBeInTheDocument()
  })
})
