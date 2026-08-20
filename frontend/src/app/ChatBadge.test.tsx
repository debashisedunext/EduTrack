import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Me } from '@/api/generated/model'
import { initialAuthState, useAuthStore } from '@/features/auth/authStore'

import { ChatBadge } from './ChatBadge'

/**
 * The header chat panel — what replaced the chat toast.
 *
 * <p>The behaviour under test is mostly a *negative* one, and it is the whole
 * point of the change: chat is on screen only when somebody has opened it.
 * That is easy to regress into by accident — a stray default-open, a panel
 * that reopens onto the last conversation — and none of it would fail a test
 * that only checked the messages render.
 *
 * <p>Rendered directly rather than through `App`: the unit is this component,
 * and the shell would drag in the notification stream, whose own suite covers
 * the other half of this change.
 *
 * <p><b>Every "nothing is showing" assertion waits for the thread list to have
 * arrived first.</b> Asserting absence against a component that has not
 * finished loading passes whatever it does, which would make three of these
 * six tests decorative — and the three that matter most, since they are the
 * ones standing between the user and the popups coming back.
 */

vi.setConfig({ testTimeout: 20000 })

/** Six popover tests in one file contend for the CPU; 1000 ms is not enough under a full run. */
const SLOW = { timeout: 5000 }

/**
 * jsdom implements no Pointer Capture API, and Radix's popover reads it on
 * pointerdown. Without this, clicking the trigger throws inside an event
 * listener — which does not fail an assertion but does exit vitest non-zero.
 */
beforeAll(() => {
  const element = Element.prototype as unknown as Record<string, unknown>
  element.hasPointerCapture ??= () => false
  element.setPointerCapture ??= () => {}
  element.releasePointerCapture ??= () => {}
})

/** Ravi Kumar, who the mock session signs in as. */
const ME: Me = { id: 4, displayName: 'Ravi Kumar', role: 'DEVELOPER' }

/** The store is a module singleton; a user left behind would decide the next test. */
beforeEach(() => {
  useAuthStore.setState({ ...initialAuthState, status: 'authenticated', user: ME })
})

function renderBadge() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ChatBadge />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function trigger() {
  return screen.getByRole('button', { name: /^chat/i })
}

/**
 * The thread list has arrived.
 *
 * Two of the seeded ticket-thread messages are unread for the mock user, so
 * the badge reaching that number is the signal that there was something to
 * render — and therefore that a following "not in the document" means the
 * panel chose not to show it rather than that it had nothing yet.
 */
function threadsLoaded() {
  return waitFor(() => expect(trigger()).toHaveAccessibleName('Chat (2 unread)'), SLOW)
}

/** The seeded ticket thread, by the title the mock database gives it. */
const THREAD = 'CRM-26-00347'
const A_SEEDED_MESSAGE = /root cause found/i

describe('before it is opened', () => {
  it('shows a badge, and no conversation with it', async () => {
    renderBadge()

    await threadsLoaded()

    // The thing that used to arrive uninvited.
    expect(screen.queryByText(THREAD)).not.toBeInTheDocument()
    expect(screen.queryByText(A_SEEDED_MESSAGE)).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  })
})

describe('opening it', () => {
  it('lists the conversations, like the bell lists notifications', async () => {
    const user = userEvent.setup()
    renderBadge()
    await threadsLoaded()

    await user.click(trigger())

    const panel = await screen.findByRole('dialog', { name: 'Chat' }, SLOW)
    expect(within(panel).getByText(THREAD)).toBeVisible()
    expect(within(panel).getByText(/ticket/i)).toBeVisible()
    // A list of threads — no message is on screen yet.
    expect(within(panel).queryByText(A_SEEDED_MESSAGE)).not.toBeInTheDocument()
  })

  it('shows the messages once a conversation is chosen', async () => {
    const user = userEvent.setup()
    renderBadge()
    await threadsLoaded()
    await user.click(trigger())

    await user.click(await screen.findByText(THREAD, undefined, SLOW))

    expect(await screen.findByText(A_SEEDED_MESSAGE, undefined, SLOW)).toBeVisible()
    expect(screen.getByRole('button', { name: 'Send message' })).toBeVisible()
  })

  it('sends a reply without leaving the header', async () => {
    const user = userEvent.setup()
    renderBadge()
    await threadsLoaded()
    await user.click(trigger())
    await user.click(await screen.findByText(THREAD, undefined, SLOW))
    await screen.findByText(A_SEEDED_MESSAGE, undefined, SLOW)

    // Short on purpose: `user.type` presses one key at a time, and each press
    // is a render of a controlled input. A sentence here cost three seconds of
    // CPU that neighbouring suites were timing out against.
    await user.type(await screen.findByRole('textbox', undefined, SLOW), 'On it')
    await user.click(screen.getByRole('button', { name: 'Send message' }))

    expect(await screen.findByText('On it', undefined, SLOW)).toBeVisible()
  })
})

describe('closing it', () => {
  /**
   * The regression this change exists to prevent, in its subtlest form.
   *
   * <p>Leaving the selection behind is what every chat client does and it is
   * *nearly* right — but it means the next click on the badge opens onto a
   * conversation nobody asked for, which is the complaint that started this,
   * one popup smaller.
   */
  it('reopens on the list, never back into the last conversation', async () => {
    const user = userEvent.setup()
    renderBadge()
    await threadsLoaded()
    await user.click(trigger())
    await user.click(await screen.findByText(THREAD, undefined, SLOW))
    await screen.findByText(A_SEEDED_MESSAGE, undefined, SLOW)

    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByText(A_SEEDED_MESSAGE)).not.toBeInTheDocument(), SLOW)
    await user.click(trigger())

    // Back on the list — the thread is named, its conversation is not shown.
    expect(await screen.findByText(THREAD, undefined, SLOW)).toBeVisible()
    expect(screen.queryByText(A_SEEDED_MESSAGE)).not.toBeInTheDocument()
  })
})
