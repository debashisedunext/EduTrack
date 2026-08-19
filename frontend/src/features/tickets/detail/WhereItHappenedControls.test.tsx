import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'

import { server } from '@/mocks/server'
import { Toaster } from '@/components/ui/toaster'
import type { Module } from '@/api/generated/model/module'

import { ModuleControl, StepsToGenerateSection, TextControl } from './WhereItHappenedControls'

/**
 * C-069 · §7.5's four fields, inline-editable on S-20.
 *
 * `PATCH /tickets/{id}` is intercepted here rather than asserted through
 * `frontend/src/mocks/`, so the assertions about the **request body** are this
 * file's own — which is what the interesting half of this feature is. What the
 * body says decides whether a field is cleared, left alone or overwritten, and
 * `ticket_history` cannot take back a row written for a change that was not one.
 */

const TICKET_CODE = 'CRM-26-00347'

const MODULES: Module[] = [
  { id: 1, code: 'STUDENT', name: 'Student', isActive: true },
  { id: 3, code: 'FEES', name: 'Fees', isActive: true },
  { id: 9, code: 'TRANSPORT', name: 'Transport', isActive: false },
]

/** Radix's Select needs APIs jsdom lacks. */
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

let patches: Record<string, unknown>[] = []
let failNext = false

beforeEach(() => {
  patches = []
  failNext = false
  server.use(
    http.patch(`*/tickets/${TICKET_CODE}`, async ({ request }) => {
      const body = (await request.json()) as Record<string, unknown>
      if (failNext) {
        return HttpResponse.json(
          { error: { type: 'about:blank', title: 'Validation failed', detail: 'That module is not active.' } },
          { status: 400 },
        )
      }
      patches.push(body)
      return HttpResponse.json({ data: { ticketId: TICKET_CODE, ...body } })
    }),
  )
})

function renderWith(node: React.ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      {node}
      <Toaster />
    </QueryClientProvider>,
  )
  return { user: userEvent.setup() }
}

describe('ModuleControl', () => {
  it('shows the name of a retired module and lets the ticket be moved off it', async () => {
    const onChanged = vi.fn()
    const { user } = renderWith(
      <ModuleControl ticketId={TICKET_CODE} moduleId={9} modules={MODULES} onChanged={onChanged} />,
    )

    expect(screen.getByText('Transport')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Edit module' }))
    await user.click(screen.getByRole('combobox', { name: 'Module' }))
    // Its own retired row is offered, because the editor has to open showing
    // what the ticket actually says.
    expect(screen.getByRole('option', { name: /Transport/ })).toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: 'Fees' }))
    await user.click(screen.getByRole('button', { name: /^Save/ }))

    await waitFor(() => expect(patches).toEqual([{ moduleId: 3 }]))
    expect(onChanged).toHaveBeenCalled()
  })

  it('does not offer a retired module to a ticket that is not on one', async () => {
    const { user } = renderWith(
      <ModuleControl ticketId={TICKET_CODE} moduleId={3} modules={MODULES} onChanged={vi.fn()} />,
    )
    await user.click(screen.getByRole('button', { name: 'Edit module' }))
    await user.click(screen.getByRole('combobox', { name: 'Module' }))

    expect(screen.getByRole('option', { name: 'Fees' })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /Transport/ })).not.toBeInTheDocument()
  })

  it('sends nothing when the same module is re-picked', async () => {
    const onChanged = vi.fn()
    const { user } = renderWith(
      <ModuleControl ticketId={TICKET_CODE} moduleId={3} modules={MODULES} onChanged={onChanged} />,
    )
    await user.click(screen.getByRole('button', { name: 'Edit module' }))
    await user.click(screen.getByRole('button', { name: /^Save/ }))

    // `ticket_history` is append-only. A row recording "Fees → Fees" cannot be
    // deleted once written, so not sending is the cheaper half of the rule the
    // server also enforces.
    expect(patches).toEqual([])
    expect(onChanged).not.toHaveBeenCalled()
  })
})

describe('TextControl', () => {
  it('clears an emptied field with null rather than an empty string', async () => {
    const { user } = renderWith(
      <TextControl
        ticketId={TICKET_CODE}
        field="screenName"
        label="Screen name"
        value="Fee Receipt Print"
        maxLength={120}
        onChanged={vi.fn()}
      />,
    )

    await user.click(screen.getByRole('button', { name: 'Edit screen name' }))
    await user.clear(screen.getByRole('textbox', { name: 'Screen name' }))
    await user.click(screen.getByRole('button', { name: /^Save/ }))

    await waitFor(() => expect(patches).toEqual([{ screenName: null }]))
  })

  it('saves on Enter and abandons on Escape', async () => {
    const { user } = renderWith(
      <TextControl
        ticketId={TICKET_CODE}
        field="feature"
        label="Feature"
        value="Reprint"
        maxLength={120}
        onChanged={vi.fn()}
      />,
    )

    await user.click(screen.getByRole('button', { name: 'Edit feature' }))
    await user.type(screen.getByRole('textbox', { name: 'Feature' }), ' with watermark{Escape}')
    // Abandoned — the value on screen is the ticket's, not the draft's.
    await waitFor(() => expect(screen.getByText('Reprint')).toBeInTheDocument())
    expect(patches).toEqual([])

    await user.click(screen.getByRole('button', { name: 'Edit feature' }))
    await user.type(screen.getByRole('textbox', { name: 'Feature' }), ' with watermark{Enter}')
    await waitFor(() => expect(patches).toEqual([{ feature: 'Reprint with watermark' }]))
  })

  it('sends nothing when the field is re-typed identically, whitespace aside', async () => {
    const { user } = renderWith(
      <TextControl
        ticketId={TICKET_CODE}
        field="screenName"
        label="Screen name"
        value="Fee Receipt Print"
        maxLength={120}
        onChanged={vi.fn()}
      />,
    )

    await user.click(screen.getByRole('button', { name: 'Edit screen name' }))
    await user.type(screen.getByRole('textbox', { name: 'Screen name' }), '  {Enter}')

    // Trailing spaces are not a change, and `ticket_history` cannot take back
    // the row that would say they were.
    expect(patches).toEqual([])
  })

  it('tells the user when the save was refused, and stays open so the work is not lost', async () => {
    failNext = true
    const { user } = renderWith(
      <TextControl
        ticketId={TICKET_CODE}
        field="screenName"
        label="Screen name"
        value="Fee Receipt Print"
        maxLength={120}
        onChanged={vi.fn()}
      />,
    )

    await user.click(screen.getByRole('button', { name: 'Edit screen name' }))
    await user.type(screen.getByRole('textbox', { name: 'Screen name' }), ' v2{Enter}')

    expect(await screen.findByText('Screen name was not saved')).toBeInTheDocument()
    // Still editing. Closing on failure would discard what the user typed and
    // show the old value back, which reads as "saved" to anyone not watching
    // the toast.
    expect(screen.getByRole('textbox', { name: 'Screen name' })).toHaveValue('Fee Receipt Print v2')
  })
})

describe('StepsToGenerateSection', () => {
  it('invites the steps rather than showing an empty block', () => {
    renderWith(<StepsToGenerateSection ticketId={TICKET_CODE} steps={null} onChanged={vi.fn()} />)
    expect(screen.getByText(/reproduce this without asking/i)).toBeInTheDocument()
  })

  it('sanitises the steps on the way out', async () => {
    const { user } = renderWith(
      <StepsToGenerateSection ticketId={TICKET_CODE} steps="<p>Open Fees</p>" onChanged={vi.fn()} />,
    )

    await user.click(screen.getByRole('button', { name: /^Edit/ }))
    const editor = screen.getByRole('textbox', { name: 'Steps to generate' })
    editor.innerHTML = '<p>1. Open Fees</p><script>alert(1)</script>'
    editor.dispatchEvent(new Event('input', { bubbles: true }))
    await user.click(screen.getByRole('button', { name: /^Save/ }))

    // The server's copy of this is the guarantee; this one is what stops the
    // markup ever leaving the browser.
    await waitFor(() => expect(patches).toEqual([{ stepsToGenerate: '<p>1. Open Fees</p>' }]))
  })
})
