import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

import { ApiError, type Problem } from '@/api/http'

import { ObPrereqMasterPage } from './ObPrereqMasterPage'

/**
 * B-124 · OB-14.
 *
 * <p>What is worth pinning here is the revision workflow, not the rendering:
 * the master is versioned rather than edited, so the assertions that matter
 * are that nothing mutates without a draft, that a mandatory flip is a *full*
 * task update (the PATCH body carries every field, which is what the server's
 * `If-Match` protects), and that publishing tells the admin the snapshot rule
 * rather than pretending the change reaches existing clients.
 */

const apiError = (status: number, type = 'about:blank', detail?: string) =>
  new ApiError(status, { type, title: `HTTP ${status}`, status, detail } as Problem, undefined as never)

const getTemplate = vi.fn()
const beginMutate = vi.fn()
const publishMutate = vi.fn()
const addMutate = vi.fn()
const updateMutate = vi.fn()
const removeMutate = vi.fn()

vi.mock('@/api/generated/onboarding-masters/onboarding-masters', () => ({
  useGetObPrereqTemplate: (params?: { version?: number }) => getTemplate(params),
  useBeginObPrereqTemplateRevision: () => ({ mutateAsync: beginMutate }),
  usePublishObPrereqTemplate: () => ({ mutateAsync: publishMutate }),
  useAddObPrereqTemplateTask: () => ({ mutateAsync: addMutate }),
  useUpdateObPrereqTemplateTask: () => ({ mutateAsync: updateMutate }),
  useRemoveObPrereqTemplateTask: () => ({ mutateAsync: removeMutate }),
  getGetObPrereqTemplateQueryKey: () => ['/onboarding/prereq-template'],
}))

const toastSpy = vi.fn()
vi.mock('@/components/ui/use-toast', () => ({
  toast: (options: unknown) => toastSpy(options),
}))

const GST = {
  id: 11,
  sequence: 1,
  title: 'Share GST certificate',
  description: 'Upload a scanned copy of the certificate',
  tatDays: 3,
  isMandatory: true,
  isActive: true,
  docs: [
    {
      id: 9,
      templateTaskId: 11,
      label: 'GST format sample',
      attachmentId: 3,
      fileName: 'gst-format-sample.pdf',
      sizeBytes: 96_000,
    },
  ],
}

const SPOC = {
  id: 12,
  sequence: 2,
  title: 'Nominate a SPOC',
  description: null,
  tatDays: 2,
  isMandatory: false,
  isActive: true,
  docs: [],
}

const active = (over: Partial<Record<string, unknown>> = {}) => ({
  version: 5,
  isDraft: false,
  isActive: true,
  publishedAt: '2026-08-01T00:00:00Z',
  publishedBy: { id: 1, displayName: 'Priya Nair' },
  mandatoryCount: 1,
  tasks: [GST, SPOC],
  ...over,
})

const draft = (over: Partial<Record<string, unknown>> = {}) =>
  active({ version: 6, isDraft: true, isActive: false, publishedAt: null, ...over })

const result = (template: unknown) => ({
  data: { data: template },
  isPending: false,
  isError: false,
})

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

beforeEach(() => {
  vi.clearAllMocks()
  getTemplate.mockReturnValue(result(active()))
  beginMutate.mockResolvedValue({ data: draft() })
  publishMutate.mockResolvedValue({ data: active({ version: 6 }) })
  addMutate.mockResolvedValue({})
  updateMutate.mockResolvedValue({})
  removeMutate.mockResolvedValue({})
})

describe('OB-14 · prerequisites master', () => {
  it('edits in place, with none of the revision chrome the design does not draw', () => {
    renderPage()

    expect(screen.getByRole('heading', { name: 'Prerequisites master' })).toBeInTheDocument()
    expect(screen.getByText('Share GST certificate')).toBeInTheDocument()
    expect(screen.getByText('Upload a scanned copy of the certificate')).toBeInTheDocument()
    expect(screen.getByText('GST format sample')).toBeInTheDocument()

    const mandatory = screen.getByRole('checkbox', { name: 'Mandatory: Share GST certificate' })
    expect(mandatory).toBeChecked()
    expect(mandatory).toBeEnabled()

    expect(screen.getByRole('button', { name: 'Delete Share GST certificate' })).toBeEnabled()
    expect(screen.getByLabelText('Title *')).toBeEnabled()
    // Add stays disabled until there is a title — that is the form's own rule,
    // not the read-only one this screen used to have.
    expect(screen.getByRole('button', { name: '+ Add to master' })).toBeDisabled()

    // The resting screen is the mockup's: no version chip, no revision button,
    // and nothing unpublished to announce.
    expect(screen.queryByRole('button', { name: 'Begin a revision' })).not.toBeInTheDocument()
    expect(screen.queryByText('Unpublished changes')).not.toBeInTheDocument()
  })

  /**
   * The trap in editing a published version in place: `beginRevision` clones
   * every task into new rows, so the id the row was drawn with belongs to the
   * published version and writing to it is refused. `sequence` is what carries
   * across, and this asserts the write lands on the clone.
   */
  it('opens a draft on the first edit and writes to the cloned task, not the published one', async () => {
    const cloned = () =>
      draft({ tasks: [{ ...GST, id: 101 }, { ...SPOC, id: 102 }] })
    beginMutate.mockResolvedValue({ data: cloned() })
    getTemplate.mockImplementation((params?: { version?: number }) =>
      params?.version === 6 ? result(cloned()) : result(active()),
    )
    renderPage()

    await userEvent.click(screen.getByRole('checkbox', { name: 'Mandatory: Share GST certificate' }))

    expect(beginMutate).toHaveBeenCalledTimes(1)
    expect(updateMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        templateTaskId: 101,
        data: expect.objectContaining({ isMandatory: false }),
      }),
    )
    expect(await screen.findByText('Unpublished changes')).toBeInTheDocument()
  })

  /**
   * The contract has no "get the draft" read — a draft is whatever `isDraft`
   * is true on. When begin answers 409 because one already exists, the screen
   * joins it (versions are sequential, so it can only be active + 1) rather
   * than dead-ending on an error about state it could edit.
   */
  it('joins the draft somebody else had open, and says the edit did not apply', async () => {
    beginMutate.mockRejectedValue(apiError(409, 'https://edutrack.example/problems/ob-prereq-draft-exists'))
    getTemplate.mockImplementation((params?: { version?: number }) =>
      params?.version === 6 ? result(draft()) : result(active()),
    )
    renderPage()

    await userEvent.click(screen.getByRole('button', { name: 'Delete Share GST certificate' }))

    expect(await screen.findByText('Unpublished changes')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Publish version 6' })).toBeInTheDocument()
    // The delete is deliberately not retried against ids the 409 never carried.
    expect(removeMutate).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveTextContent(/make it again/i)
  })

  /**
   * The same conflict as the server actually sends it.
   * `ObPrereqTemplateExceptionHandler` types all three of its 409s as the
   * generic `errors/conflict`, so a screen matching only on the mock's
   * `ob-prereq-draft-exists` would adopt the draft in tests and dead-end in
   * production. `beginRevision` raises no other 409, so the status is enough.
   */
  it('adopts the existing draft on the conflict type the server really sends', async () => {
    beginMutate.mockRejectedValue(
      apiError(409, 'https://edutrack/errors/conflict', 'A draft already exists — publish or discard it first.'),
    )
    getTemplate.mockImplementation((params?: { version?: number }) =>
      params?.version === 6 ? result(draft()) : result(active()),
    )
    renderPage()

    await userEvent.click(screen.getByRole('button', { name: 'Delete Share GST certificate' }))

    expect(await screen.findByText('Unpublished changes')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Publish version 6' })).toBeInTheDocument()
    expect(removeMutate).not.toHaveBeenCalled()
  })

  it('adds a task to the draft with exactly what was typed', async () => {
    getTemplate.mockReturnValue(result(draft()))
    renderPage()

    const add = screen.getByRole('button', { name: '+ Add to master' })
    expect(add).toBeDisabled() // no title yet — the one field the server refuses blank

    await userEvent.type(screen.getByLabelText('Title *'), 'Provide student data extract')
    await userEvent.type(screen.getByLabelText('Description for the client'), 'CSV per the sample')
    const tat = screen.getByLabelText('TAT (working days)')
    await userEvent.clear(tat)
    await userEvent.type(tat, '4')

    await userEvent.click(add)

    await waitFor(() =>
      expect(addMutate).toHaveBeenCalledWith({
        data: {
          title: 'Provide student data extract',
          description: 'CSV per the sample',
          tatDays: 4,
          isMandatory: true,
        },
      }),
    )
  })

  /**
   * The one shaped by the contract's own reasoning: OB-14 has a real PATCH
   * where OB-07 has delete-and-re-add, so a mandatory flip must carry the
   * whole task — a body missing `tatDays` would be a different write, and the
   * server's `If-Match` can only protect fields that are actually sent.
   */
  it('flips mandatory through a full task update', async () => {
    getTemplate.mockReturnValue(result(draft()))
    renderPage()

    await userEvent.click(screen.getByRole('checkbox', { name: 'Mandatory: Nominate a SPOC' }))

    await waitFor(() =>
      expect(updateMutate).toHaveBeenCalledWith({
        templateTaskId: 12,
        data: {
          title: 'Nominate a SPOC',
          description: undefined,
          tatDays: 2,
          isMandatory: true,
          isActive: true,
        },
      }),
    )
  })

  it('removes a task from the draft', async () => {
    getTemplate.mockReturnValue(result(draft()))
    renderPage()

    await userEvent.click(screen.getByRole('button', { name: 'Delete Nominate a SPOC' }))

    await waitFor(() => expect(removeMutate).toHaveBeenCalledWith({ templateTaskId: 12 }))
  })

  it('publishes with the snapshot rule said out loud', async () => {
    getTemplate.mockReturnValue(result(draft()))
    renderPage()

    await userEvent.click(screen.getByRole('button', { name: 'Publish version 6' }))

    await waitFor(() => expect(publishMutate).toHaveBeenCalledTimes(1))
    await waitFor(() =>
      expect(toastSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          title: 'Version 6 published',
          description: expect.stringMatching(/keep the snapshot/i),
        }),
      ),
    )
  })

  it('reports a publish refusal in the words of the rule', async () => {
    getTemplate.mockReturnValue(result(draft()))
    publishMutate.mockRejectedValue(
      apiError(
        422,
        'https://edutrack.example/problems/ob-prereq-no-mandatory',
        'A published checklist with no mandatory task would clear its own gate at boarding.',
      ),
    )
    renderPage()

    await userEvent.click(screen.getByRole('button', { name: 'Publish version 6' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/clear its own gate/i)
    expect(toastSpy).not.toHaveBeenCalled()
  })

  /**
   * A 404 on the *unversioned* read is the fresh-organisation state the
   * service documents as legitimate — the first visit is somebody arriving to
   * author a checklist, not a failure. Pinned here as well as in the MSW
   * suite because this is the file that decides what the markup is.
   */
  it('offers to author the first checklist when no version exists yet', async () => {
    getTemplate.mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      error: apiError(404, 'about:blank', 'no version of the prerequisites master has been authored yet'),
    })
    renderPage()

    expect(screen.getByRole('heading', { name: 'Prerequisites master' })).toBeInTheDocument()
    expect(screen.getByText(/No prerequisites checklist has been authored yet/i)).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()

    // `beginRevision` needs no active version to clone — the server opens v1
    // against an empty master, so the empty state's action is the real one.
    await userEvent.click(screen.getByRole('button', { name: 'Author the first checklist' }))
    await waitFor(() => expect(beginMutate).toHaveBeenCalledTimes(1))
  })

  it('keeps every other failure an error', () => {
    getTemplate.mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      error: apiError(500),
    })
    renderPage()

    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Author the first checklist' })).not.toBeInTheDocument()
  })

  it('says who to ask when the caller is not an OB Admin', () => {
    getTemplate.mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      error: apiError(403),
    })
    renderPage()

    expect(screen.getByRole('alert')).toHaveTextContent(/OB Admin only/i)
  })
})
