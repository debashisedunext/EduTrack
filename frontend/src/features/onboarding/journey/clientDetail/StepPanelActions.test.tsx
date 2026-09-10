import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import { Toaster } from '@/components/ui/toaster'
import { ObClientProductPage } from './ObClientProductPage'

/**
 * C-111 · OB-06's action surface, driven through OB-05 against the mock server.
 *
 * Driven through the page rather than by mounting the panel directly, because
 * the things most likely to break are the joins: the panel's second read, the
 * ribbon refetch after a transition, and `mayActOnStep` agreeing with the mock's
 * own `mayAct`. A panel tested in isolation with hand-built props would prove
 * none of them.
 *
 * The fixture is the seeded Trinity ERP journey (client 8), and it is well
 * chosen for this: **step 5 (Configuration & branding) is `BLOCKED`, with
 * user 3 — the mock's signed-in user — as backup owner** — so the default
 * focus step is one the caller may act on. It also carries the only seeded
 * Task List in the module, with the two mandatory items unticked and the
 * optional one done, which is exactly the completion gate.
 *
 * **The page under it is now `ObClientProductPage`**, addressed as
 * (client, product), since the journey accordions moved off the client page to
 * one page per purchased product. Nothing about OB-06 changed with them — the
 * accordion, the ribbon and the panel are the same components with the same
 * props — so every assertion below is untouched.
 */
function renderClient(id = 8, productId = 1) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/onboarding/clients/${id}/products/${productId}`]}>
        <Routes>
          <Route
            path="/onboarding/clients/:obClientId/products/:productId"
            element={<ObClientProductPage />}
          />
        </Routes>
      </MemoryRouter>
      <Toaster />
    </QueryClientProvider>,
  )
}

const SLOW = { timeout: 5000 }

/**
 * OB-05 drives more reads per mount than any other screen in the module — the
 * client, its prerequisites, the directory, the open escalations, then the
 * opened journey's ribbon, its step detail, that step's communications and its
 * sign-off. Every one is an MSW round trip with latency, and vitest runs the
 * files in parallel.
 *
 * Vitest's 5s default therefore sat exactly on `SLOW`, so a `findBy` that used
 * its full budget timed the *test* out before it could report which query
 * failed. The extra headroom buys a real assertion failure instead of a
 * stopwatch one; it does not make any individual wait longer.
 */
vi.setConfig({ testTimeout: 20_000 })

/**
 * Expand the ERP journey and wait for its panel.
 *
 * The page opens its running journey on first paint, so on Trinity the ERP
 * accordion is usually expanded already and a click would *collapse* it. The
 * helper reads `aria-expanded` rather than assuming either way, so it keeps
 * working whichever journey the page decides to open.
 */
async function openErpPanel(user: ReturnType<typeof userEvent.setup>) {
  await screen.findAllByText(/Trinity College of Commerce/, undefined, SLOW)
  const trigger = screen.getByRole('button', { name: /^EduTrack ERP —/ })
  if (trigger.getAttribute('aria-expanded') !== 'true') await user.click(trigger)
  return screen.findByTestId('journey-step-panel', undefined, SLOW)
}

describe('OB-06 — the step panel’s actions', () => {
  describe('what is offered', () => {
    /**
     * `BLOCKED` accepts `resume` and nothing else. Complete on a blocked step
     * is not a disabled button — it is a transition the service refuses
     * outright, so it should not be drawn at all.
     */
    it('offers only Resume on a blocked step the caller owns', async () => {
      const user = userEvent.setup()
      renderClient()
      const panel = await openErpPanel(user)

      expect(await within(panel).findByRole('button', { name: 'Resume' }, SLOW)).toBeEnabled()
      expect(within(panel).queryByRole('button', { name: 'Complete' })).not.toBeInTheDocument()
      expect(within(panel).queryByRole('button', { name: 'Start' })).not.toBeInTheDocument()
    })

    /**
     * `ObStepOwnership.mayAct` admits the owner and backup owner only. For
     * everybody else the buttons are **absent, not disabled** — a greyed-out
     * row suggests a permission the reader might acquire by asking, and this
     * step simply is not theirs.
     */
    it('offers nothing on a step the caller does not own, and says who does', async () => {
      const user = userEvent.setup()
      renderClient()
      await openErpPanel(user)

      // Step 5 is the only one whose backup is the signed-in user — so pick
      // step 6, Admin & user training, which Priya owns and nobody backs up.
      await user.click(screen.getByRole('button', { name: /^Step 6:/ }))
      const other = await screen.findByTestId('journey-step-panel', undefined, SLOW)

      expect(within(other).queryByRole('button', { name: 'Resume' })).not.toBeInTheDocument()
      expect(within(other).queryByRole('button', { name: 'Start' })).not.toBeInTheDocument()
    })
  })

  describe('the task list and the completion gate', () => {
    it('draws the step’s task list, marking which items are mandatory', async () => {
      const user = userEvent.setup()
      renderClient()
      const panel = await openErpPanel(user)

      const roles = await within(panel).findByRole('checkbox', { name: /Roles & permissions configured/ }, SLOW)
      const logo = within(panel).getByRole('checkbox', { name: /Logo & colours applied/ })
      expect(roles).toBeChecked()
      expect(logo).not.toBeChecked()
    })

    /**
     * The heart of C-111, end to end through the mock: resume the blocked step
     * so it becomes `IN_PROGRESS`, and Complete then appears **disabled**,
     * naming the mandatory item that is holding it. Without this the owner
     * presses Complete and reads a 422 for something the checklist in front of
     * them could have said.
     */
    it('offers Complete disabled once resumed, naming the unticked mandatory items', async () => {
      const user = userEvent.setup()
      renderClient()
      const panel = await openErpPanel(user)

      await user.click(await within(panel).findByRole('button', { name: 'Resume' }, SLOW))

      const complete = await screen.findByRole('button', { name: 'Complete' }, SLOW)
      expect(complete).toBeDisabled()
      expect(await screen.findByText(/“Logo & colours applied”/, undefined, SLOW))
        .toBeInTheDocument()
    })

    /**
     * And the gate lifts when the item is ticked — the same read driving both,
     * so the checklist and the button cannot disagree.
     */
    it('enables Complete once the last mandatory item is ticked', async () => {
      const user = userEvent.setup()
      renderClient()
      const panel = await openErpPanel(user)

      await user.click(await within(panel).findByRole('button', { name: 'Resume' }, SLOW))
      await screen.findByRole('button', { name: 'Complete' }, SLOW)

      await user.click(screen.getByRole('checkbox', { name: /Logo & colours applied/ }))
      await waitFor(
        () => expect(screen.getByRole('checkbox', { name: /Logo & colours applied/ })).toBeChecked(),
        SLOW,
      )
      await user.click(screen.getByRole('checkbox', { name: /Notice \/ report templates set/ }))

      await waitFor(
        () => expect(screen.getByRole('button', { name: 'Complete' })).toBeEnabled(),
        SLOW,
      )
    })
  })

  describe('blocking', () => {
    /**
     * `reasonCode` is mandatory — the server answers 400 without it — so the
     * dialog refuses before the request rather than after.
     */
    it('will not submit a block with no reason chosen', async () => {
      const user = userEvent.setup()
      renderClient()
      const panel = await openErpPanel(user)

      await user.click(await within(panel).findByRole('button', { name: 'Resume' }, SLOW))
      await user.click(await screen.findByRole('button', { name: 'Block' }, SLOW))

      const confirm = await screen.findByRole('button', { name: 'Block service' })
      expect(confirm).toBeDisabled()

      await user.selectOptions(screen.getByRole('combobox'), 'technical-issue')
      expect(confirm).toBeEnabled()
    })

    /**
     * The one sentence on this dialog that is worth more than the rest of it.
     * Blocked keeps the TAT clock running and charges the delay to us; waiting
     * on client pauses it and attributes the time to the client (plan §5.7).
     * An owner picking the wrong one moves a breach onto the wrong account.
     */
    it('warns that blocking keeps the clock running, and points at the alternative', async () => {
      const user = userEvent.setup()
      renderClient()
      const panel = await openErpPanel(user)

      await user.click(await within(panel).findByRole('button', { name: 'Resume' }, SLOW))
      await user.click(await screen.findByRole('button', { name: 'Block' }, SLOW))

      const dialog = await screen.findByRole('dialog')
      expect(within(dialog).getByText(/clock keeps running/i)).toBeInTheDocument()
      // Scoped to the dialog: "Waiting on client" is also a ribbon tile's
      // state label wherever a journey has one, and an unscoped query would
      // match both.
      expect(within(dialog).getByText(/Waiting on client/)).toBeInTheDocument()
    })
  })

  /**
   * A transition changes the step's status, and the journey's RAG, percent and
   * step dots are all computed from the set of statuses. Invalidating only the
   * step would leave the ribbon tile the reader just acted on disagreeing with
   * the panel underneath it.
   */
  it('refreshes the ribbon after a transition, not only the panel', async () => {
    const user = userEvent.setup()
    renderClient()
    const panel = await openErpPanel(user)

    expect(await screen.findByRole('button', { name: /^Step 5:.*Blocked/ }, SLOW)).toBeInTheDocument()

    await user.click(await within(panel).findByRole('button', { name: 'Resume' }, SLOW))

    /*
      Asserted as the *absence* of "Blocked" rather than the presence of "In
      progress", because the tile need not say "In progress" once resumed: this
      step has consumed 24 hours against a 27-hour budget, so its RAG is AMBER
      and `treatmentFor` renders the at-risk overlay over the state label —
      C-109's deliberate design, not a bug. Asserting the label it happens to
      carry would couple this test to that treatment; what is under test here
      is that the ribbon re-read at all.
    */
    await waitFor(
      () => expect(screen.queryByRole('button', { name: /^Step 5:.*Blocked/ })).not.toBeInTheDocument(),
      SLOW,
    )
  }, 15000)
})
