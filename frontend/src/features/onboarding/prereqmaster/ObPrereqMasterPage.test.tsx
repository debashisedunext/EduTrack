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

const draft = () => active({ version: 6, isDraft: true, isActive: false, publishedAt: null })

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
  it('shows the active version read-only, with a revision as the only way in', () => {
    renderPage()

    expect(screen.getByRole('heading', { name: 'Prerequisites master' })).toBeInTheDocument()
    expect(screen.getByText('Share GST certificate')).toBeInTheDocument()
    expect(screen.getByText('Upload a scanned copy of the certificate')).toBeInTheDocument()
    expect(screen.getByText('GST format sample')).toBeInTheDocument()

    const mandatory = screen.getByRole('checkbox', { name: 'Mandatory: Share GST certificate' })
    expect(mandatory).toBeChecked()
    expect(mandatory).toBeDisabled()

    expect(screen.getByRole('button', { name: 'Delete Share GST certificate' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '+ Add to master' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Begin a revision' })).toBeEnabled()
  })

  it('begins a revision and switches the screen onto the draft it returns', async () => {
    getTemplate.mockImplementation((params?: { version?: number }) =>
      params?.version === 6 ? result(draft()) : result(active()),
    )
    renderPage()

    await userEvent.click(screen.getByRole('button', { name: 'Begin a revision' }))

    expect(await screen.findByRole('button', { name: 'Publish version 6' })).toBeEnabled()
    expect(beginMutate).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('checkbox', { name: 'Mandatory: Share GST certificate' })).toBeEnabled()
  })

  /**
   * The contract has no "get the draft" read — a draft is whatever `isDraft`
   * is true on. When begin answers 409 because one already exists, the screen
   * joins it (versions are sequential, so it can only be active + 1) rather
   * than dead-ending on an error about state it could edit.
   */
  it('adopts the existing draft when begin says one already exists', async () => {
    beginMutate.mockRejectedValue(apiError(409, 'https://edutrack.example/problems/ob-prereq-draft-exists'))
    getTemplate.mockImplementation((params?: { version?: number }) =>
      params?.version === 6 ? result(draft()) : result(active()),
    )
    renderPage()

    await userEvent.click(screen.getByRole('button', { name: 'Begin a revision' }))

    expect(await screen.findByRole('button', { name: 'Publish version 6' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
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
