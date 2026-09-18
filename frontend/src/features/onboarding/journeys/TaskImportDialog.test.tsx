import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http } from 'msw'

import { server } from '@/mocks/server'
import { url, ok, problem } from '@/mocks/handlers/util'
import { Toaster } from '@/components/ui/toaster'

import { TaskImportDialog } from './TaskImportDialog'

/**
 * OB-07 · the task-import dialog, against the mock server.
 *
 * Fixture note — template **2** (Biometric Attendance) is the draft
 * `JourneyTemplateDesignerPage.test.tsx` already documents; this dialog only
 * ever renders on a draft, so every test here targets it.
 *
 * <h2>What cannot be asserted here</h2>
 *
 * <p>The mock's `preview`/`task-import` handlers deliberately do not read the
 * uploaded file — `onboardingJourneys.ts`'s own comment gives the reason
 * (`rest.ts`'s documented `[object FormData]` hang under vitest). So these
 * tests exercise the dialog's state machine against a canned response, and
 * cannot prove the real backend would judge any particular file the same way
 * — that is `ObJourneyTaskImportServiceTest`'s job, not this one's.
 */

/** Radix's dialog needs APIs jsdom lacks. */
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

/** jsdom implements neither, and the template download uses both. */
const createObjectURL = vi.fn(() => 'blob:mock-url')
const revokeObjectURL = vi.fn()
let clicked: { download: string } | null = null

beforeEach(() => {
  clicked = null
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL, revokeObjectURL }))
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
    this: HTMLAnchorElement,
  ) {
    clicked = { download: this.download }
  })
})

afterEach(() => {
  vi.restoreAllMocks()
  createObjectURL.mockClear()
  revokeObjectURL.mockClear()
})

function renderDialog(onOpenChange = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <TaskImportDialog templateId={2} open onOpenChange={onOpenChange} />
      <Toaster />
    </QueryClientProvider>,
  )
  return onOpenChange
}

function chooseFile() {
  const input = document.querySelector<HTMLInputElement>('input[type="file"]')!
  const file = new File(['x'], 'tasks.xlsx', {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  })
  fireEvent.change(input, { target: { files: [file] } })
}

describe('TaskImportDialog', () => {
  it('downloads the template under the name the server gave it', async () => {
    renderDialog()

    fireEvent.click(screen.getByRole('button', { name: /download template/i }))

    await waitFor(() => expect(clicked).not.toBeNull())
    expect(clicked!.download).toBe('module-service-tasks-template.xlsx')
  })

  it('offers Preview only once a file is chosen, and disables Confirm until it validates', async () => {
    renderDialog()

    expect(screen.queryByRole('button', { name: /^preview$/i })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /confirm import/i })).toBeDisabled()

    chooseFile()
    expect(await screen.findByRole('button', { name: /^preview$/i })).toBeInTheDocument()
  })

  it('previews the canned tree and enables Confirm once it comes back valid', async () => {
    renderDialog()
    chooseFile()

    fireEvent.click(await screen.findByRole('button', { name: /^preview$/i }))

    expect(await screen.findByText(/1 task ready to import/i)).toBeInTheDocument()
    expect(screen.getByText(/Imported task/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /confirm import/i })).toBeEnabled()
  })

  it('shows row errors and keeps Confirm disabled when the file does not validate', async () => {
    server.use(
      http.post(url('/onboarding/journey-templates/:templateId/task-import/preview'), () =>
        ok({
          valid: false,
          errors: [{ sheet: 'Tasks', rowNumber: 3, message: 'Task Name is required.' }],
          tasks: [],
        }),
      ),
    )
    renderDialog()
    chooseFile()

    fireEvent.click(await screen.findByRole('button', { name: /^preview$/i }))

    expect(await screen.findByText(/1 row needs fixing/i)).toBeInTheDocument()
    expect(screen.getByText(/Tasks — row 3: Task Name is required\./)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /confirm import/i })).toBeDisabled()
  })

  it('confirms the import, invalidates the template and closes the dialog', async () => {
    const onOpenChange = renderDialog()
    chooseFile()
    fireEvent.click(await screen.findByRole('button', { name: /^preview$/i }))
    await screen.findByText(/1 task ready to import/i)

    fireEvent.click(screen.getByRole('button', { name: /confirm import/i }))

    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
    expect(await screen.findByText(/task tree replaced/i)).toBeInTheDocument()
  })

  it('re-reads a 422 on confirm as row errors, exactly like a failed preview', async () => {
    renderDialog()
    chooseFile()
    fireEvent.click(await screen.findByRole('button', { name: /^preview$/i }))
    await screen.findByText(/1 task ready to import/i)

    server.use(
      http.post(url('/onboarding/journey-templates/:templateId/task-import'), () =>
        problem(422, 'task-import-invalid', 'Task import file is not valid', {
          errors: [{ sheet: 'Tasks', rowNumber: 2, message: 'Duplicate task name.' }],
        }),
      ),
    )

    fireEvent.click(screen.getByRole('button', { name: /confirm import/i }))

    expect(await screen.findByText(/Tasks — row 2: Duplicate task name\./)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /confirm import/i })).toBeDisabled()
  })
})
