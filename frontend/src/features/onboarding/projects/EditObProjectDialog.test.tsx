import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { HttpResponse, http } from 'msw'

import type { ObProjectDetail } from '@/api/generated/model/obProjectDetail'
import type { UserRef } from '@/api/generated/model/userRef'
import { server } from '@/mocks/server'
import { problem, validationFailed } from '@/mocks/handlers/util'

import { EditObProjectDialog } from './EditObProjectDialog'

/**
 * The Edit project dialog against the mock server.
 *
 * <p>The assertion that earns this file is the `If-Match` one. The dialog is
 * fed the page's own `ETag` precisely so the header names what the reader saw;
 * a dialog that fetched a fresh tag at submit time would pass every other test
 * here and silently overwrite a colleague's edit.
 */

const PEOPLE: UserRef[] = [
  { id: 12, displayName: 'Aditya Rawat' },
  { id: 26, displayName: 'Kavya Sharma' },
  { id: 41, displayName: 'Vikram Mehta' },
]

const PROJECT = {
  id: 7,
  name: 'DAV Proj',
  client: { id: 3, name: 'DAV School', clientCode: 'DAV-101', city: null },
  product: { id: 1, code: 'EDUNEXT_ERP', name: 'EDUNEXT-ERP' },
  startDate: '2026-09-15',
  salesPerson: { id: 12, displayName: 'Aditya Rawat' },
  implementor: { id: 26, displayName: 'Kavya Sharma' },
  implementorManager: { id: 31, displayName: 'Rahul Menon' },
  status: 'RUNNING',
  gateStatus: 'OPEN',
  stagesComplete: 0,
  stagesTotal: 7,
  journeyCount: 2,
  totalTatDays: 8,
  stages: [],
  moduleServices: [],
  createdAt: '2026-09-15T00:00:00Z',
} as unknown as ObProjectDetail

const ETAG = '"7-abc123"'

/** Every PATCH the dialog sent — its `If-Match` and its body. */
function stubPatch(respond: () => Response = () => HttpResponse.json({ data: PROJECT })) {
  const sent: { ifMatch: string | null; body: unknown }[] = []
  server.use(
    http.patch('*/onboarding/projects/:id', async ({ request }) => {
      sent.push({ ifMatch: request.headers.get('If-Match'), body: await request.json() })
      return respond()
    }),
  )
  return sent
}

function renderDialog(over: Partial<React.ComponentProps<typeof EditObProjectDialog>> = {}) {
  const onClose = vi.fn()
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <EditObProjectDialog
        project={PROJECT}
        etag={ETAG}
        open
        onClose={onClose}
        people={PEOPLE}
        {...over}
      />
    </QueryClientProvider>,
  )
  return { onClose }
}

describe('Edit project', () => {
  it('opens on what the project says today', async () => {
    renderDialog()

    expect(await screen.findByLabelText(/project name/i)).toHaveValue('DAV Proj')
    expect(screen.getByLabelText(/start date/i)).toHaveValue('2026-09-15')
    expect(screen.getByLabelText(/status/i)).toHaveValue('RUNNING')
    expect(screen.getByText('Aditya Rawat')).toBeInTheDocument()
    expect(screen.getByText('Kavya Sharma')).toBeInTheDocument()
  })

  /** The one that matters: the page's tag, verbatim, and the whole representation. */
  it('sends the page’s ETag as If-Match with the whole representation', async () => {
    const user = userEvent.setup()
    const sent = stubPatch()
    const { onClose } = renderDialog()

    const name = await screen.findByLabelText(/project name/i)
    await user.clear(name)
    await user.type(name, 'DAV School ERP')
    await user.click(screen.getByRole('button', { name: /save project/i }))

    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(sent).toHaveLength(1)
    expect(sent[0].ifMatch).toBe(ETAG)
    expect(sent[0].body).toEqual({
      name: 'DAV School ERP',
      startDate: '2026-09-15',
      salesPersonId: 12,
      implementorUserId: 26,
      implementorManagerUserId: 31,
      status: 'RUNNING',
      statusReason: null,
    })
  })

  /** Unassigning is a real thing to do, and it is sent as null, not left out. */
  it('clears a person and sends the gap as null', async () => {
    const user = userEvent.setup()
    const sent = stubPatch()
    renderDialog()

    await user.click(await screen.findByRole('button', { name: /clear implementor/i }))
    await user.click(screen.getByRole('button', { name: /save project/i }))

    await waitFor(() => expect(sent).toHaveLength(1))
    expect(sent[0].body).toHaveProperty('implementorUserId', null)
  })

  it('asks for a reason before it will put a project on hold', async () => {
    const user = userEvent.setup()
    const sent = stubPatch()
    renderDialog()

    await user.selectOptions(await screen.findByLabelText(/status/i), 'ON_HOLD')
    await user.click(screen.getByRole('button', { name: /save project/i }))

    expect(await screen.findByText(/say why the project is on hold/i)).toBeInTheDocument()
    expect(sent).toHaveLength(0)
  })

  /**
   * A 412 is not an error to retry. The project changed since it was read —
   * the tag covers the roll-up, so even a step somebody completed moves it —
   * and the dialog stays open rather than pretending the save went through.
   */
  it('stays open on a precondition failure rather than closing as if saved', async () => {
    const user = userEvent.setup()
    stubPatch(() => problem(412, 'precondition-failed', 'This project changed since you read it.'))
    const { onClose } = renderDialog()

    await user.click(await screen.findByRole('button', { name: /save project/i }))

    await waitFor(() => expect(screen.getByRole('button', { name: /save project/i })).toBeEnabled())
    expect(onClose).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('puts a field-keyed refusal on the field it names', async () => {
    const user = userEvent.setup()
    stubPatch(() => validationFailed({ name: ['That name is already taken.'] }))
    const { onClose } = renderDialog()

    await user.click(await screen.findByRole('button', { name: /save project/i }))

    expect(await screen.findByText('That name is already taken.')).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
  })

  /** Completed is earned; the form neither offers it nor echoes it back. */
  it('offers no status on a completed project', async () => {
    renderDialog({ project: { ...PROJECT, status: 'COMPLETED' } as ObProjectDetail })

    expect(await screen.findByText(/status is not editable/i)).toBeInTheDocument()
    expect(screen.queryByLabelText(/^status/i)).not.toBeInTheDocument()
  })

  /**
   * The field that named a step owner as implementor manager on every project
   * in the fixture database, and produced a manager the review routes then
   * refused. The dropdown asks the platform who may hold the job now.
   */
  it('offers only onboarding managers and admins as the implementor manager', async () => {
    const asked: string[] = []
    server.use(
      http.get('*/users', ({ request }) => {
        const q = new URL(request.url).searchParams
        asked.push(...q.getAll('obModuleRole'))
        return HttpResponse.json({
          data: [{ id: 99, displayName: 'Ananya Rao', isActive: true }],
        })
      }),
    )
    const user = userEvent.setup()
    renderDialog()

    await user.click(await screen.findByLabelText(/implementor manager/i))

    // Comma-joined, not repeated — `explode: false`, which is what `http.ts`
    // sends and what the mock and Spring both read.
    await waitFor(() => expect(asked).toContain('OB_MANAGER,OB_ADMIN'))
    expect(await screen.findByText('Ananya Rao')).toBeInTheDocument()
  })
})
