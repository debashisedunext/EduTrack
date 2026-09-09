import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { http, HttpResponse } from 'msw'

import { BASE } from '@/api/http'
import { server } from '@/mocks/server'

import { ObPrereqMasterPage } from './ObPrereqMasterPage'

/**
 * B-124 · OB-14, against the mock server rather than against mocked hooks.
 *
 * <p>Its sibling `ObPrereqMasterPage.test.tsx` replaces the whole generated
 * module with `vi.mock`, which is right for pinning the revision workflow and
 * wrong for proving the screen loads: a page whose every request 404s passes
 * that file unchanged. This one renders the real hooks over the real handlers,
 * so the assertion is the one a person makes by opening the screen.
 *
 * <p>The states that cannot be reached through the fixtures — a fresh
 * organisation with no authored master, a 5xx, a draft that vanishes between
 * being opened and being read — are set up with per-test handler overrides
 * below, written to the <i>server's</i> contract rather than the mock's. Where
 * the two disagree (see `freshOrganisation`) the server is what ships.
 */
function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ObPrereqMasterPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const SLOW = { timeout: 5000 }

const TEMPLATE = `${BASE}/onboarding/prereq-template`

/** An RFC 9457 body shaped like the real controller's, which types nothing. */
const failure = (status: number, detail: string, title = 'Not found') =>
  HttpResponse.json(
    { type: 'about:blank', title, status, detail },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )

type Draft = {
  version: number
  isDraft: boolean
  isActive: boolean
  publishedAt: string | null
  publishedBy: null
  mandatoryCount: number
  tasks: unknown[]
}

/**
 * A brand-new organisation, exactly as `ObPrereqTemplateController` answers
 * one: the unversioned read 404s with "no version … has been authored yet",
 * and `POST /revisions` opens v1 regardless — `beginRevision` clones the
 * active version's tasks only `ifPresent`, so it does not need one.
 *
 * **This is where the MSW handler and the server disagree.** The handler in
 * `mocks/handlers/onboardingPrereqs.ts` refuses the same POST with a 404
 * (`if (!active) return notFound(...)`), so against the mock the first
 * checklist can never be started. The screen is written to the server, and
 * kept resilient to the mock — `beginFailureMessage` turns that 404 into a
 * sentence saying no draft was opened, rather than a bare "not found".
 */
function freshOrganisation() {
  let draft: Draft | null = null
  server.use(
    http.get(TEMPLATE, ({ request }) => {
      const asked = new URL(request.url).searchParams.get('version')
      // The controller falls back to the draft when nothing is active yet.
      if (draft && (asked === null || asked === String(draft.version))) {
        return HttpResponse.json({ data: draft })
      }
      return failure(404, 'no version of the prerequisites master has been authored yet')
    }),
    http.post(`${TEMPLATE}/revisions`, () => {
      draft = {
        version: 1,
        isDraft: true,
        isActive: false,
        publishedAt: null,
        publishedBy: null,
        mandatoryCount: 0,
        tasks: [],
      }
      return HttpResponse.json({ data: draft }, { status: 201 })
    }),
  )
}

describe('ObPrereqMasterPage · against the mock server', () => {
  it('loads the active version and lists its tasks', async () => {
    renderPage()

    expect(await screen.findByText('Signed service agreement', undefined, SLOW)).toBeInTheDocument()
    expect(screen.getByText('Primary contact confirmation')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  /**
   * Read-only is the snapshot guarantee doing its job — a client keeps the
   * checklist it was boarded against — so the screen says so where the
   * disabled controls are, rather than leaving them looking broken.
   */
  it('says why the published version is read-only instead of just disabling it', async () => {
    renderPage()

    expect(await screen.findByText(/is published, so it is read-only/i, undefined, SLOW))
      .toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Begin a revision' })).toBeEnabled()
    expect(screen.getByRole('checkbox', { name: 'Mandatory: Signed service agreement' })).toBeDisabled()
  })

  /**
   * The defect this file exists for. A 404 on the unversioned read is a fresh
   * organisation, which the service documents as "a legitimate state, not an
   * error" — OB-14's first visit is somebody arriving to author one.
   */
  it('renders the authoring empty state — not an error — when no version has been authored', async () => {
    freshOrganisation()
    renderPage()

    expect(
      await screen.findByText(/No prerequisites checklist has been authored yet/i, undefined, SLOW),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Author the first checklist' })).toBeEnabled()
    // The heading survives every state — an error page with no title was half
    // the reason this read as broken.
    expect(screen.getByRole('heading', { name: 'Prerequisites master' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('opens the first draft from the empty state and lets it be edited', async () => {
    freshOrganisation()
    renderPage()

    await userEvent.click(
      await screen.findByRole('button', { name: 'Author the first checklist' }, SLOW),
    )

    expect(await screen.findByRole('button', { name: 'Publish version 1' }, SLOW)).toBeInTheDocument()
    expect(screen.getByLabelText('Title *')).toBeEnabled()
    expect(screen.getByText(/Add the first task below/i)).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  /**
   * The mock's own answer to that POST, kept as a test so the divergence is
   * visible rather than folklore: the screen must not dead-end or claim a
   * draft it does not have.
   */
  it('says no draft was opened when the API refuses to start the first one', async () => {
    server.use(
      http.get(TEMPLATE, () =>
        failure(404, 'no version of the prerequisites master has been authored yet'),
      ),
      http.post(`${TEMPLATE}/revisions`, () => failure(404, 'Prerequisite template not found')),
    )
    renderPage()

    await userEvent.click(
      await screen.findByRole('button', { name: 'Author the first checklist' }, SLOW),
    )

    expect(await screen.findByRole('alert')).toHaveTextContent(/No draft was opened/i)
    // Still the empty state, still offering the action — nothing was changed.
    expect(screen.getByRole('button', { name: 'Author the first checklist' })).toBeInTheDocument()
  })

  it('still renders a server failure as an error', async () => {
    server.use(
      http.get(TEMPLATE, () => failure(500, 'Something went wrong', 'Internal Server Error')),
    )
    renderPage()

    expect(await screen.findByRole('alert', undefined, SLOW)).toBeInTheDocument()
    expect(screen.queryByText(/has been authored yet/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Author the first checklist' })).not.toBeInTheDocument()
  })

  it('names the role when the caller may not read the master', async () => {
    server.use(http.get(TEMPLATE, () => failure(403, 'OB Admin only', 'Forbidden')))
    renderPage()

    expect(await screen.findByRole('alert', undefined, SLOW)).toHaveTextContent(/OB Admin only/i)
    expect(screen.queryByRole('button', { name: 'Author the first checklist' })).not.toBeInTheDocument()
  })

  /**
   * A 404 for a version asked for *by number* is a fault, not a fresh
   * organisation — the draft was published or discarded under this session.
   * The screen falls back to the active version and says so; what it must not
   * do is offer to author a first checklist that plainly already exists.
   */
  it('does not mistake a vanished draft for an unauthored master', async () => {
    const active = {
      version: 8,
      isDraft: false,
      isActive: true,
      publishedAt: '2026-07-01T09:00:00.000Z',
      publishedBy: { id: 1, displayName: 'Priya Nair' },
      mandatoryCount: 1,
      tasks: [
        {
          id: 81,
          sequence: 1,
          title: 'Signed service agreement',
          description: null,
          tatDays: 3,
          isMandatory: true,
          isActive: true,
          docs: [],
        },
      ],
    }
    server.use(
      http.get(TEMPLATE, ({ request }) => {
        const asked = new URL(request.url).searchParams.get('version')
        return asked
          ? failure(404, `version ${asked} of the prerequisites master does not exist`)
          : HttpResponse.json({ data: active })
      }),
      http.post(`${TEMPLATE}/revisions`, () =>
        HttpResponse.json({ data: { ...active, version: 9, isDraft: true, isActive: false } }, { status: 201 }),
      ),
    )
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Begin a revision' }, SLOW))

    expect(await screen.findByRole('alert')).toHaveTextContent(/draft could not be loaded/i)
    expect(screen.getByText('Signed service agreement')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Author the first checklist' })).not.toBeInTheDocument()
  })
})
