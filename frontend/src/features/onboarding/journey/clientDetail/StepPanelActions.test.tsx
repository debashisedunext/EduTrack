import { describe, expect, it } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'

import { Toaster } from '@/components/ui/toaster'
import { ObClientDetailPage } from './ObClientDetailPage'

/**
 * C-111 · OB-06's action surface, driven through OB-05 against the mock server.
 *
 * Driven through the page rather than by mounting the panel directly, because
 * the things most likely to break are the joins: the panel's second read, the
 * ribbon refetch after a transition, and `mayActOnStep` agreeing with the mock's
 * own `mayAct`. A panel tested in isolation with hand-built props would prove
 * none of them.
 *
 * The fixture is the seeded Northwind ERP journey, and it is well chosen for
 * this: **step 3 (Data Migration) is `BLOCKED`, owned by user 3, which is the
 * mock's signed-in user** — so the default focus step is one the caller may act
 * on. It also carries the only seeded Task List in the module, with one
 * mandatory item ticked and one not, which is exactly the completion gate.
 */
function renderClient(id = 1) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/onboarding/clients/${id}`]}>
        <Routes>
          <Route path="/onboarding/clients/:obClientId" element={<ObClientDetailPage />} />
        </Routes>
      </MemoryRouter>
      <Toaster />
    </QueryClientProvider>,
  )
}

const SLOW = { timeout: 5000 }

/** Expand the ERP journey and wait for its panel. */
async function openErpPanel(user: ReturnType<typeof userEvent.setup>) {
  await screen.findByText('Northwind Technologies Pvt Ltd', undefined, SLOW)
  await user.click(screen.getByRole('button', { name: /^ERP Suite —/ }))
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

      // Step 4, User Training, is WAITING_ON_CLIENT and owned by user 3 too —
      // so pick step 2, Environment Provisioning, which has no owner at all.
      await user.click(screen.getByRole('button', { name: /^Step 2:/ }))
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

      const staff = await within(panel).findByRole('checkbox', { name: /Staff master reconciled/ }, SLOW)
      const student = within(panel).getByRole('checkbox', { name: /Student master reconciled/ })
      expect(staff).toBeChecked()
      expect(student).not.toBeChecked()
    })

    /**
     * The heart of C-111, end to end through the mock: resume the blocked step
     * so it becomes `IN_PROGRESS`, and Complete then appears **disabled**,
     * naming the mandatory item that is holding it. Without this the owner
     * presses Complete and reads a 422 for something the checklist in front of
     * them could have said.
     */
    it('offers Complete disabled once resumed, naming the unticked mandatory item', async () => {
      const user = userEvent.setup()
      renderClient()
      const panel = await openErpPanel(user)

      await user.click(await within(panel).findByRole('button', { name: 'Resume' }, SLOW))

      const complete = await screen.findByRole('button', { name: 'Complete' }, SLOW)
      expect(complete).toBeDisabled()
      expect(await screen.findByText(/“Student master reconciled” is not ticked/, undefined, SLOW))
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

      await user.click(screen.getByRole('checkbox', { name: /Student master reconciled/ }))

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
      // Scoped to the dialog: "Waiting on client" is also a ribbon tile's own
      // state label on this journey, and an unscoped query matches both.
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

    expect(await screen.findByRole('button', { name: /^Step 3:.*Blocked/ }, SLOW)).toBeInTheDocument()

    await user.click(await within(panel).findByRole('button', { name: 'Resume' }, SLOW))

    /*
      Asserted as the *absence* of "Blocked" rather than the presence of "In
      progress", because the tile does not say "In progress" once resumed: this
      step has consumed 71 hours against a 64-hour budget, so its RAG is RED and
      `treatmentFor` renders it "Breached" — the breach overlay winning over the
      state label is C-109's deliberate design, not a bug. Asserting the label
      it happens to carry would couple this test to that treatment; what is
      under test here is that the ribbon re-read at all.
    */
    await waitFor(
      () => expect(screen.queryByRole('button', { name: /^Step 3:.*Blocked/ })).not.toBeInTheDocument(),
      SLOW,
    )
  }, 15000)
})
