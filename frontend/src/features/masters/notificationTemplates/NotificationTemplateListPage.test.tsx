import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { getDb } from '@/mocks/db'
import { Toaster } from '@/components/ui/toaster'
import { NotificationTemplateListPage } from './NotificationTemplateListPage'

/**
 * B-022 · S-15 against the mock server.
 *
 * The behaviours worth a test are the ones a screenshot would not show: that a
 * mail blueprint §4B.6 marks never-optional renders as a locked statement rather
 * than a toggle, that the in-app template for the same event does not, that a
 * misspelled merge tag is caught before the round trip, and that the event and
 * channel are not editable once the row exists.
 */
/**
 * Raised from the 5 s default, per file rather than in `vite.config.ts`.
 *
 * Every editor test here makes two round trips through MSW — the list, then the
 * detail read that carries the `ETag` — against a fixture of fifty templates,
 * and the second one lands a shade past five seconds on a cold worker. Widening
 * the global default would hide a genuine hang in somebody else's suite; this
 * says which file is slow and why.
 */
vi.setConfig({ testTimeout: 20000 })

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/masters/notification-templates']}>
        <NotificationTemplateListPage />
        <Toaster />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** Raised locally, for the reason `TaskTypeListPage.test.tsx` gives. */
const SLOW = { timeout: 5000 }

/** The section for one event, found by its humanised heading. */
async function groupFor(heading: string) {
  const title = await screen.findByRole('heading', { name: heading, level: 2 }, SLOW)
  return title.closest('section') as HTMLElement
}

/**
 * Opens the edit dialog for one channel of one event **and waits for its detail
 * read**.
 *
 * The dialog renders a skeleton until `useNotificationTemplate` resolves — a
 * second round trip, made rather than reusing the grid row because that read is
 * what carries the `ETag`. So `findByRole('dialog')` alone resolves against an
 * empty shell.
 */
async function openEditor(heading: string, channel: string) {
  const group = await groupFor(heading)
  const row = within(group).getByText(channel).closest('tr') as HTMLElement
  fireEvent.click(within(row).getByRole('button', { name: /^Edit/ }))
  const dialog = await screen.findByRole('dialog', undefined, SLOW)
  await within(dialog).findByLabelText('Body', undefined, SLOW)
  return dialog
}

describe('the template grid', () => {
  it('groups by event, with each event’s channels together', async () => {
    renderPage()

    const group = await groupFor('Ticket assigned')
    expect(within(group).getByText('Email')).toBeInTheDocument()
    expect(within(group).getByText('In-app')).toBeInTheDocument()
    expect(within(group).getByText('TICKET_ASSIGNED')).toBeInTheDocument()
  })

  it('shows recipients as positions rather than raw codes', async () => {
    renderPage()

    const group = await groupFor('Sla breached')
    expect(within(group).getAllByText("The assignee's reporting manager").length)
      .toBeGreaterThan(0)
  })

  /**
   * The status a mandatory mail renders is deliberately not the same word as an
   * ordinary one. "On" invites a click that would be refused; "Always on" says
   * what is true.
   */
  it('marks a mandatory mail as always on, and an optional one as merely on', async () => {
    renderPage()

    const assigned = await groupFor('Ticket assigned')
    const email = within(assigned).getByText('Email').closest('tr') as HTMLElement
    expect(within(email).getByText('Always on')).toBeInTheDocument()

    const digest = await groupFor('Daily digest')
    const digestEmail = within(digest).getByText('Email').closest('tr') as HTMLElement
    expect(within(digestEmail).getByText('On')).toBeInTheDocument()
  })

  /**
   * The one recipient that reaches outside the organisation, called out before
   * the dialog opens — knowing that a body is read by a customer is the
   * difference between a reword and an incident.
   */
  it('flags a client-facing template on the row', async () => {
    renderPage()

    const group = await groupFor('Comment marked client visible')
    expect(within(group).getAllByRole('button', { name: 'Edit — client-facing' }).length)
      .toBeGreaterThan(0)
  })

  it('says in words that an in-app template has no subject', async () => {
    renderPage()

    const group = await groupFor('Ticket assigned')
    const inApp = within(group).getByText('In-app').closest('tr') as HTMLElement
    expect(within(inApp).getByText(/no subject/)).toBeInTheDocument()
  })
})

describe('the mandatory-mail rule', () => {
  it('locks the toggle on an assignment mail and explains why', async () => {
    renderPage()

    const dialog = await openEditor('Ticket assigned', 'Email')
    const toggle = within(dialog).getByLabelText(/Send this notification/)

    expect(toggle).toBeDisabled()
    expect(toggle).toBeChecked()
    expect(within(dialog).getByText(/cannot be switched off/)).toBeInTheDocument()
  })

  /**
   * §7.7 gives the guarantee to mail, not to a toast that only reaches somebody
   * already logged in. Locking the in-app channel too would take away a real
   * preference to protect a channel that was never the promise.
   */
  it('leaves the in-app toggle for the same event operable', async () => {
    renderPage()

    const dialog = await openEditor('Ticket assigned', 'In-app')
    expect(within(dialog).getByLabelText(/Send this notification/)).toBeEnabled()
  })

  it('leaves an optional mail operable', async () => {
    renderPage()

    const dialog = await openEditor('Comment added', 'Email')
    expect(within(dialog).getByLabelText(/Send this notification/)).toBeEnabled()
  })

  /**
   * A state the API cannot produce, reached here the way it could be in
   * practice — a hand-run `UPDATE`, or a restore from before the rule existed.
   *
   * The locked checkbox shows `true`, so the form has to submit `true`. If it
   * submitted the stored `false` instead, every save on that row would earn the
   * 409 the lock exists to describe, on a field the user cannot reach, and the
   * template would be uneditable. It repairs itself on the first save instead.
   */
  it('repairs a mandatory mail that was switched off out of band', async () => {
    const stored = getDb().notificationTemplates
      .find((t) => t.eventCode === 'TICKET_ASSIGNED' && t.channel === 'EMAIL')!
    stored.isActive = false

    renderPage()

    const dialog = await openEditor('Ticket assigned', 'Email')
    expect(within(dialog).getByLabelText(/Send this notification/)).toBeChecked()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Save changes' }))
    await waitFor(() => {
      expect(getDb().notificationTemplates
        .find((t) => t.eventCode === 'TICKET_ASSIGNED' && t.channel === 'EMAIL')?.isActive)
        .toBe(true)
    }, SLOW)
  })
})

describe('the editor', () => {
  it('will not let the event or channel be changed', async () => {
    renderPage()

    const dialog = await openEditor('Comment added', 'Email')
    expect(within(dialog).getByLabelText('Event')).toBeDisabled()
    expect(within(dialog).getByLabelText('Channel')).toBeDisabled()
    expect(within(dialog).getByText(/Permanent\./)).toBeInTheDocument()
  })

  it('catches a misspelled merge tag before the round trip', async () => {
    renderPage()

    const dialog = await openEditor('Comment added', 'Email')
    fireEvent.change(within(dialog).getByLabelText('Body'), {
      target: { value: '<p>{{ticketId}} was updated</p>' },
    })

    // Asserted through `role="alert"` rather than by text: the textarea holds
    // the same string, so a plain text query matches the mistake as well as the
    // warning about it.
    expect(await within(dialog).findByRole('alert', undefined, SLOW))
      .toHaveTextContent('{{ticketId}}')

    fireEvent.click(within(dialog).getByRole('button', { name: 'Save changes' }))
    // Two now: the live warning under the tag palette, and the field error the
    // submit put on the body. Both are wanted — one catches it while typing, the
    // other is what a keyboard user lands on after the refused save.
    await waitFor(() => {
      expect(within(dialog).getAllByText(/is not a merge tag/)).toHaveLength(2)
    })
    // Nothing was written — the refusal is local, before the request.
    expect(getDb().notificationTemplates
      .find((t) => t.eventCode === 'COMMENT_ADDED' && t.channel === 'EMAIL')?.bodyTemplate)
      .not.toContain('ticketId')
  })

  it('inserts a merge tag at the caret rather than at the end', async () => {
    renderPage()

    const dialog = await openEditor('Comment added', 'Email')
    const body = within(dialog).getByLabelText('Body') as HTMLTextAreaElement
    fireEvent.change(body, { target: { value: '<p></p>' } })
    body.setSelectionRange(3, 3)

    fireEvent.click(within(dialog).getByRole('button', { name: '{{ticket_id}}' }))

    await waitFor(() => {
      expect((within(dialog).getByLabelText('Body') as HTMLTextAreaElement).value)
        .toBe('<p>{{ticket_id}}</p>')
    })
  })

  it('saves a reworded body through a PATCH', async () => {
    renderPage()

    const dialog = await openEditor('Comment added', 'Email')
    fireEvent.change(within(dialog).getByLabelText('Body'), {
      target: { value: '<p>{{actor}} said something about {{ticket_id}}</p>' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save changes' }))

    await waitFor(() => {
      expect(getDb().notificationTemplates
        .find((t) => t.eventCode === 'COMMENT_ADDED' && t.channel === 'EMAIL')?.bodyTemplate)
        .toBe('<p>{{actor}} said something about {{ticket_id}}</p>')
    }, SLOW)
  })

  it('refuses a save that names nobody', async () => {
    renderPage()

    const dialog = await openEditor('Comment added', 'Email')
    for (const label of ['Assignee', 'Watchers']) {
      const checkbox = within(dialog).getByLabelText(label)
      if ((checkbox as HTMLInputElement).checked) {
        fireEvent.click(checkbox)
      }
    }
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save changes' }))

    expect(await within(dialog).findByText(/at least one recipient/, undefined, SLOW))
      .toBeInTheDocument()
  })
})

describe('the mock fixture', () => {
  /**
   * Asserted here rather than only server-side, because the fixture is what
   * every test above renders against: a seeded template using a tag the
   * catalogue does not carry would make the editor show a refusal on a row
   * nobody had touched.
   */
  it('seeds a template for every event, using only known merge tags', () => {
    const templates = getDb().notificationTemplates
    expect(templates.length).toBeGreaterThan(40)

    const known = /\{\{\s*(ticket_id|assignee|stage|client|planned_close|ticket_title|ticket_url|project|level|status|actor|recipient|comment|iteration|cycle|overdue_by|sla_due|org)\s*\}\}/g
    for (const template of templates) {
      const text = `${template.subjectTemplate ?? ''}\n${template.bodyTemplate}`
      expect(text.replace(known, '')).not.toMatch(/\{\{/)
    }
  })

  /** A-007's column comment said POPUP|BELL|EMAIL and is superseded. */
  it('uses the channel vocabulary that actually runs', () => {
    expect([...new Set(getDb().notificationTemplates.map((t) => t.channel))].sort())
      .toEqual(['EMAIL', 'IN_APP'])
  })
})
