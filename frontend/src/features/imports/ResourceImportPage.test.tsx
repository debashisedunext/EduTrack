import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

import { ClientImportPage } from './ClientImportPage'
import { ResourceImportPage } from './ResourceImportPage'

/**
 * B-038 · <b>the second registration is the same screen, asking for the other
 * schema.</b>
 *
 * `ClientImportPage.test.tsx` covers the wizard's behaviour — the steps, the
 * dropzone, the sheet selector, the refusals — and it is deliberately not
 * repeated here. Running four hundred lines of it twice would prove the same
 * component works twice, which it does by construction now that there is only
 * one of it.
 *
 * What this file asserts is the part that is genuinely new and genuinely able to
 * be wrong: that the resource route reaches the resource registration. Every
 * failure it can catch is a wiring failure with the same symptom — a screen that
 * looks completely right and imports into the wrong master.
 */

function renderPage(page: React.ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{page}</MemoryRouter>
    </QueryClientProvider>,
  )
}

/**
 * Puts a file into the drop zone through the real `<input type="file">`, as the
 * client suite does — jsdom has no drag-and-drop, and the input is what the
 * keyboard and the picker both reach.
 */
async function drop(name: string) {
  const input = document.querySelector<HTMLInputElement>('input[type="file"]')!
  fireEvent.change(input, {
    target: { files: [new File(['x'], name, { type: 'application/octet-stream' })] },
  })
  await waitFor(() => expect(screen.queryByText('Reading the file…')).not.toBeInTheDocument())
}

describe('the resource import wizard', () => {
  it('is the resource master’s screen, not the client one', () => {
    renderPage(<ResourceImportPage />)

    expect(
      screen.getByRole('heading', { name: 'Import resources from Excel', level: 1 }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Back to resources/ })).toHaveAttribute(
      'href',
      '/masters/resources',
    )
  })

  /**
   * The same five-step rail, because it is the same component.
   *
   * Asserted rather than assumed: if the resource route ever grew its own page,
   * this is the test that would notice the steps had drifted apart — which is
   * how "the same wizard, registered twice" quietly becomes two wizards.
   */
  it('renders §4B.3’s five steps, the same ones S-34 renders', () => {
    renderPage(<ResourceImportPage />)
    const rail = within(screen.getByRole('list', { name: 'Import steps' }))

    for (const step of ['Download template', 'Upload', 'Map columns', 'Validate', 'Commit']) {
      expect(rail.getByText(step)).toBeInTheDocument()
    }
  })

  /**
   * <b>The wiring assertion that matters most.</b>
   *
   * The upload posts to `/imports/{schema}/upload`, and the whole difference
   * between this page and S-34 is that one character of path. A page that sent
   * `clients` would look entirely correct — same steps, same dropzone, same
   * preview — right up to the point where a joiner list created four hundred
   * clients.
   */
  it('uploads against the users schema, and shows what that registration returned', async () => {
    renderPage(<ResourceImportPage />)
    await drop('joiners.xlsx')

    // The mock's resource registration answers with an HR export's headings.
    // A page wired to `clients` would show Client Code and Support Plan here.
    expect(await screen.findByText('Employee Code')).toBeInTheDocument()
    expect(screen.getByText('Full Name')).toBeInTheDocument()
    expect(screen.queryByText('Client Code')).not.toBeInTheDocument()
  })

  /**
   * The reassurance every step of this wizard makes, in this registration's own
   * words.
   *
   * `importWizard.ts` argues that the nouns are worth configuring rather than
   * genericising into "record". This is that argument as an assertion: an Admin
   * about to hand over a joiner list is told no *resource* has changed.
   */
  it('speaks about resources, never about clients', async () => {
    renderPage(<ResourceImportPage />)
    await drop('joiners.xlsx')

    expect(
      await screen.findByText(/no resource has\s+changed/),
    ).toBeInTheDocument()
    expect(screen.queryByText(/no client has/)).not.toBeInTheDocument()
  })

  /**
   * B-037's history panel, filtered to this registration's runs.
   *
   * `entity` is the stored discriminator rather than the URL segment, and this
   * is what that separation buys on the screen: one panel component, two
   * histories. The failure it prevents is an Admin reversing a *client* import
   * from the resource wizard, which the panel offers a button for.
   */
  it('lists resource imports in the history, and not client ones', async () => {
    renderPage(<ResourceImportPage />)

    expect(await screen.findByText('joiners-august.xlsx')).toBeInTheDocument()
    expect(screen.queryByText('clients-august.xlsx')).not.toBeInTheDocument()
  })

  /** And the same panel on S-34 answers the other way, which is what makes the filter real. */
  it('the client wizard still lists client imports, and not resource ones', async () => {
    renderPage(<ClientImportPage />)

    expect(await screen.findByText('clients-august.xlsx')).toBeInTheDocument()
    expect(screen.queryByText('joiners-august.xlsx')).not.toBeInTheDocument()
  })
})

/**
 * The two registrations are two routes and one component.
 *
 * Written as a comparison rather than as two separate assertions because the
 * claim is about the relationship: the same step rail, the same controls, two
 * different masters. If somebody forks the page later, the halves of this test
 * stop describing one thing and it is the pair that shows it.
 */
describe('one wizard, two registrations', () => {
  it('renders the same controls for both, differing only in what they name', () => {
    const resource = renderPage(<ResourceImportPage />)
    const resourceSteps = within(
      resource.getByRole('list', { name: 'Import steps' }),
    ).getAllByRole('listitem').length
    resource.unmount()

    const client = renderPage(<ClientImportPage />)
    const clientSteps = within(client.getByRole('list', { name: 'Import steps' }))
      .getAllByRole('listitem').length

    expect(resourceSteps).toBe(clientSteps)
    expect(client.getByRole('heading', { level: 1 }).textContent)
      .toBe('Import clients from Excel')
  })
})
