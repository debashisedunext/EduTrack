import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http } from 'msw'

import { server } from '@/mocks/server'
import { url, ok, problem } from '@/mocks/handlers/util'
import { Toaster } from '@/components/ui/toaster'

import { ModuleServiceImportDialog } from './ModuleServiceImportDialog'

/**
 * OB-07 · the Module Service import dialog, against the mock server.
 *
 * <h2>What cannot be asserted here</h2>
 *
 * <p>The mock's `preview` and commit handlers deliberately do not read the
 * uploaded file or the `productId` beside it — `onboardingJourneys.ts`'s own
 * comment gives the reason (`rest.ts`'s documented `[object FormData]` hang
 * under vitest). So these tests exercise the dialog's state machine against a
 * canned response, and cannot prove the real backend would judge any
 * particular file the same way — that is `ObModuleServiceImportServiceTest`'s
 * job, not this one's.
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
      <ModuleServiceImportDialog open onOpenChange={onOpenChange} />
      <Toaster />
    </QueryClientProvider>,
  )
  return onOpenChange
}

function productSelect() {
  return screen.getByLabelText(/product/i) as HTMLSelectElement
}

/** Picks the first real product the master returned, whatever the fixture calls it. */
async function chooseProduct() {
  const select = productSelect()
  await waitFor(() => expect(select.options.length).toBeGreaterThan(1))
  fireEvent.change(select, { target: { value: select.options[1].value } })
  return select.options[1].value
}

function chooseFile() {
  const input = document.querySelector<HTMLInputElement>('input[type="file"]')!
  const file = new File(['x'], 'module-services.xlsx', {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  })
  fireEvent.change(input, { target: { files: [file] } })
}

async function previewCannedTree() {
  await chooseProduct()
  chooseFile()
  fireEvent.click(await screen.findByRole('button', { name: /^preview$/i }))
  return screen.findByText(/2 Module Services, 2 steps, 3 tasks, 4 checklist items ready to import/i)
}

/** The nested `<ul>` the preview draws — scoped so `getAllByText` cannot reach the header. */
function tree() {
  return screen.getByTestId('ms-import-tree')
}

describe('ModuleServiceImportDialog', () => {
  /*
    The product gate. `ob_journey_templates.product_id` is NOT NULL and the
    file carries no product column, so until one is picked there is nothing a
    download or an upload could mean.
  */
  it('keeps download, upload and Confirm disabled until a product is chosen', async () => {
    renderDialog()

    expect(screen.getByRole('button', { name: /download template/i })).toBeDisabled()
    expect(document.querySelector<HTMLInputElement>('input[type="file"]')!).toBeDisabled()
    expect(screen.getByRole('button', { name: /confirm import/i })).toBeDisabled()

    await chooseProduct()

    expect(screen.getByRole('button', { name: /download template/i })).toBeEnabled()
    expect(document.querySelector<HTMLInputElement>('input[type="file"]')!).toBeEnabled()
    // Still disabled — a product alone is not a validated file.
    expect(screen.getByRole('button', { name: /confirm import/i })).toBeDisabled()
  })

  it('downloads the template under the name the server gave it', async () => {
    renderDialog()
    await chooseProduct()

    fireEvent.click(screen.getByRole('button', { name: /download template/i }))

    await waitFor(() => expect(clicked).not.toBeNull())
    expect(clicked!.download).toBe('module-service-import-template.xlsx')
  })

  it('offers Preview only once a file is chosen', async () => {
    renderDialog()
    await chooseProduct()

    expect(screen.queryByRole('button', { name: /^preview$/i })).not.toBeInTheDocument()

    chooseFile()
    expect(await screen.findByRole('button', { name: /^preview$/i })).toBeInTheDocument()
  })

  it('draws the tree per service, saying which will be created and which replaced', async () => {
    renderDialog()
    await previewCannedTree()

    expect(screen.getByText('Admission Management')).toBeInTheDocument()
    expect(screen.getByText(/new draft/i)).toBeInTheDocument()
    expect(screen.getByText('Fee Management')).toBeInTheDocument()
    expect(screen.getByText(/replaces existing draft/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /confirm import/i })).toBeEnabled()
  })

  /*
    The point of the tree, and what the counts it replaced could not say. The
    file is flat and repeats Module Service / Step / Task down the rows that
    share them; "2 tasks" reads the same whether the author meant one task with
    three checklist entries or three tasks, which is the one mistake this
    screen exists to catch. Asserted level by level so a regression that
    flattens any of the four fails here.
  */
  it('nests all four levels — service, step, task, checklist', async () => {
    renderDialog()
    await previewCannedTree()

    const step = within(tree()).getByText('Data Migration')
    expect(step).toBeInTheDocument()

    // The step's own list item holds both its tasks, and each task holds its
    // checklist — walking down from the step proves the nesting, not just that
    // the strings are all somewhere on screen.
    const stepItem = step.closest('li')!
    const studentDataPort = within(stepItem).getByText('Student Data Port').closest('li')!

    expect(within(studentDataPort).getByText('Validate source file')).toBeInTheDocument()
    expect(within(studentDataPort).getByText('Reconcile record counts')).toBeInTheDocument()
  })

  /*
    A task whose rows all left the Checklist column blank is the shape most
    real files take — the sheets these are transcribed from carry a Pointers
    column and nothing under it. The server gives such a task one checklist
    entry named after the task itself, so there is something to tick on the
    client journey; the preview has to show that, or it is showing a tree the
    commit will not write.
  */
  it('shows the task name as its own checklist entry when the file gave it none', async () => {
    renderDialog()
    await previewCannedTree()

    const task = within(tree()).getAllByText('Enquiry Data Port')
    // Twice: once as the task, once as the single checklist entry beneath it.
    expect(task).toHaveLength(2)
    expect(within(task[0].closest('li')!).getAllByText('Enquiry Data Port')).toHaveLength(2)
  })

  /*
    Changing the product re-resolves every row against a different catalogue,
    so a preview taken against the old one is not a preview of what Confirm
    would now do.
  */
  it('discards the preview when the product changes', async () => {
    renderDialog()
    await previewCannedTree()

    const select = productSelect()
    fireEvent.change(select, { target: { value: select.options[2].value } })

    expect(screen.queryByText(/ready to import/i)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /confirm import/i })).toBeDisabled()
  })

  it('shows row errors and keeps Confirm disabled when the file does not validate', async () => {
    server.use(
      http.post(url('/onboarding/module-service-import/preview'), () =>
        ok({
          valid: false,
          errors: [{ sheet: 'Import', rowNumber: 3, message: 'Task is required on every row.' }],
          services: [],
        }),
      ),
    )
    renderDialog()
    await chooseProduct()
    chooseFile()

    fireEvent.click(await screen.findByRole('button', { name: /^preview$/i }))

    expect(await screen.findByText(/1 row needs fixing/i)).toBeInTheDocument()
    expect(screen.getByText(/Row 3: Task is required on every row\./)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /confirm import/i })).toBeDisabled()
  })

  it('confirms the import, reports the counts and closes the dialog', async () => {
    const onOpenChange = renderDialog()
    await previewCannedTree()

    fireEvent.click(screen.getByRole('button', { name: /confirm import/i }))

    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
    expect(await screen.findByText(/module services imported/i)).toBeInTheDocument()
    expect(screen.getByText(/1 created, 1 replaced/i)).toBeInTheDocument()
  })

  it('re-reads a 422 on confirm as row errors, exactly like a failed preview', async () => {
    renderDialog()
    await previewCannedTree()

    server.use(
      http.post(url('/onboarding/module-service-import'), () =>
        problem(422, 'module-import-invalid', 'Module Service import file is not valid', {
          errors: [
            {
              sheet: 'Import',
              rowNumber: 2,
              message: "Module Service 'Fee Management' is already published (v3).",
            },
          ],
        }),
      ),
    )

    fireEvent.click(screen.getByRole('button', { name: /confirm import/i }))

    expect(
      await screen.findByText(/Row 2: Module Service 'Fee Management' is already published \(v3\)\./),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /confirm import/i })).toBeDisabled()
  })
})
