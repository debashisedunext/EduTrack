import * as React from 'react'

import { Button } from '@/components/ui/button'
import {
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
} from '@/components/ui/modal'

/**
 * C-110 · the one dialog behind both prerequisite actions that refuse to
 * happen silently.
 *
 * Returning a submission and waiving a task look like different operations and
 * are the same interaction: an irreversible-ish decision whose *reason* is the
 * thing somebody reads back months later. The contract makes both mandatory —
 * `ObPrereqReturnRequest.comment` is `minLength: 1` and so is
 * `ObPrereqSkipRequest.reason` — and says why in each case: a return with no
 * reason "is a round trip that teaches them nothing", and a skip reason is
 * "the only field on this row that a later dispute is likely to turn on".
 *
 * So the submit button is disabled until there is text. Not a validation
 * message after the fact: the requirement is not a rule the user broke, it is
 * the point of the dialog, and a control that cannot yet do anything should
 * say so before it is pressed rather than after.
 */
export interface PrereqReasonDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description: string
  fieldLabel: string
  confirmLabel: string
  isPending?: boolean
  onConfirm: (reason: string) => void
}

export function PrereqReasonDialog({
  open,
  onOpenChange,
  title,
  description,
  fieldLabel,
  confirmLabel,
  isPending = false,
  onConfirm,
}: PrereqReasonDialogProps) {
  const [reason, setReason] = React.useState('')
  const fieldId = React.useId()

  // Cleared on every open rather than on close: a dialog dismissed with Escape
  // and reopened should not silently re-offer text the user walked away from.
  React.useEffect(() => {
    if (open) setReason('')
  }, [open])

  const canSubmit = reason.trim().length > 0 && !isPending

  return (
    <Modal open={open} onOpenChange={onOpenChange}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>{title}</ModalTitle>
          <ModalDescription>{description}</ModalDescription>
        </ModalHeader>

        <label htmlFor={fieldId} className="text-sm font-medium text-content">
          {fieldLabel}
        </label>
        <textarea
          id={fieldId}
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          rows={4}
          maxLength={2000}
          className="mt-1 w-full rounded-control border border-border bg-surface px-3 py-2 text-sm text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        />

        <ModalFooter>
          <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={isPending}>
            Cancel
          </Button>
          <Button onClick={() => onConfirm(reason.trim())} disabled={!canSubmit}>
            {confirmLabel}
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}
