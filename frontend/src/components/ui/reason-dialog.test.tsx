import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ReasonDialog } from './reason-dialog'

/**
 * C-126 · the confirm button is the whole point of this component — it must
 * refuse to fire with no text, and must fire with the trimmed text once
 * there is some. Both the portal's Escalate control and OB-05's resolve
 * dialog rely on this to keep a mandatory-comment rule from being bypassable
 * by clicking through with an empty field.
 */
describe('ReasonDialog', () => {
  it('disables the confirm button until there is text, and enables it once there is', async () => {
    const user = userEvent.setup()
    render(
      <ReasonDialog
        open
        onOpenChange={() => {}}
        title="Escalate to staff"
        description="Notifies the onboarding manager and the service owner."
        fieldLabel="What's the problem?"
        confirmLabel="Escalate"
        onConfirm={() => {}}
      />,
    )

    const confirm = screen.getByRole('button', { name: 'Escalate' })
    expect(confirm).toBeDisabled()

    await user.type(screen.getByLabelText("What's the problem?"), 'It has been down for an hour')
    expect(confirm).toBeEnabled()
  })

  it('a field of only whitespace still counts as empty', async () => {
    const user = userEvent.setup()
    render(
      <ReasonDialog
        open
        onOpenChange={() => {}}
        title="Escalate to staff"
        description="Notifies the onboarding manager and the service owner."
        fieldLabel="What's the problem?"
        confirmLabel="Escalate"
        onConfirm={() => {}}
      />,
    )

    await user.type(screen.getByLabelText("What's the problem?"), '   ')
    expect(screen.getByRole('button', { name: 'Escalate' })).toBeDisabled()
  })

  it('confirms with the trimmed text', async () => {
    const user = userEvent.setup()
    const onConfirm = vi.fn()
    render(
      <ReasonDialog
        open
        onOpenChange={() => {}}
        title="Resolve escalation"
        description="This note goes back to the client."
        fieldLabel="Resolution note"
        confirmLabel="Resolve"
        onConfirm={onConfirm}
      />,
    )

    await user.type(screen.getByLabelText('Resolution note'), '  Fixed the sync job  ')
    await user.click(screen.getByRole('button', { name: 'Resolve' }))

    expect(onConfirm).toHaveBeenCalledWith('Fixed the sync job')
  })

  it('clears whatever was typed the next time it opens', () => {
    const { rerender } = render(
      <ReasonDialog
        open={false}
        onOpenChange={() => {}}
        title="Escalate to staff"
        description="d"
        fieldLabel="Comment"
        confirmLabel="Escalate"
        onConfirm={() => {}}
      />,
    )

    rerender(
      <ReasonDialog
        open
        onOpenChange={() => {}}
        title="Escalate to staff"
        description="d"
        fieldLabel="Comment"
        confirmLabel="Escalate"
        onConfirm={() => {}}
      />,
    )

    expect(screen.getByLabelText('Comment')).toHaveValue('')
  })
})
