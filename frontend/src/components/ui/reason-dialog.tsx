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
 * C-126 · a generic "confirm with a mandatory reason" dialog, for the two
 * write actions this task adds — the portal's Escalate control and OB-05's
 * resolve-and-acknowledge — and for any future action whose whole point is
 * an irreversible-ish decision with text somebody reads back later.
 *
 * Field-for-field the same shape as
 * `features/onboarding/journey/clientDetail/PrereqReasonDialog` (C-110),
 * which stayed feature-local to that screen's prereq wording. This is the
 * same idea promoted to the shared library — `components/ui` is Stream C's
 * to keep additive (CLAUDE.md), so a second and third caller with the same
 * shape get one component rather than a third bespoke modal.
 *
 * The confirm button stays disabled until there is text — not a validation
 * message after submission. The requirement is not a rule the caller broke,
 * it is the point of the dialog.
 */
export interface ReasonDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description: string
  fieldLabel: string
  confirmLabel: string
  confirmVariant?: 'primary' | 'danger'
  isPending?: boolean
  onConfirm: (reason: string) => void
}

export function ReasonDialog({
  open,
  onOpenChange,
  title,
  description,
  fieldLabel,
  confirmLabel,
  confirmVariant = 'primary',
  isPending = false,
  onConfirm,
}: ReasonDialogProps) {
  const [reason, setReason] = React.useState('')
  const fieldId = React.useId()

  // Cleared on every open rather than on close: a dialog dismissed with
  // Escape and reopened should not silently re-offer text the user walked
  // away from.
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
          <Button
            variant={confirmVariant === 'danger' ? 'danger' : 'primary'}
            onClick={() => onConfirm(reason.trim())}
            disabled={!canSubmit}
          >
            {confirmLabel}
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}
