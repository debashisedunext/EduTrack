import { AlertTriangle, Check, X } from 'lucide-react'

import { Button } from '@/components/ui/button'
import {
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
} from '@/components/ui/modal'
import type { BulkUserStatusResponseData } from '@/api/generated/model/bulkUserStatusResponseData'
import type { BulkUserStatusOutcome } from '@/api/generated/model/bulkUserStatusOutcome'

export interface BulkStatusBarProps {
  selectedCount: number
  isPending: boolean
  onApply: (isActive: boolean) => void
  onClear: () => void
}

/**
 * B-010 · the selection bar over the S-07 grid.
 *
 * Deliberately two explicit buttons rather than one "Toggle status". A mixed
 * selection has no toggle — half of it would go each way — and a bulk action
 * whose effect depends on rows the user cannot all see at once is exactly the
 * kind that gets clicked by accident.
 */
export function BulkStatusBar({ selectedCount, isPending, onApply, onClear }: BulkStatusBarProps) {
  if (selectedCount === 0) return null

  return (
    <div
      role="region"
      aria-label="Bulk actions"
      className="flex flex-wrap items-center gap-3 rounded-control border border-primary bg-primary-soft px-4 py-2"
    >
      <span className="text-sm font-medium text-primary">
        {selectedCount} selected
      </span>
      <Button size="sm" variant="secondary" disabled={isPending} onClick={() => onApply(true)}>
        <Check className="h-4 w-4" />
        Activate
      </Button>
      <Button size="sm" variant="secondary" disabled={isPending} onClick={() => onApply(false)}>
        <X className="h-4 w-4" />
        Deactivate
      </Button>
      <button
        type="button"
        onClick={onClear}
        className="ml-auto text-sm text-primary underline-offset-2 hover:underline"
      >
        Clear selection
      </button>
    </div>
  )
}

export interface BulkStatusResultDialogProps {
  result: BulkUserStatusResponseData | null
  isActivating: boolean
  onClose: () => void
}

const OUTCOME_ORDER: BulkUserStatusOutcome['outcome'][] = [
  'BLOCKED_OPEN_TICKETS',
  'NOT_FOUND',
  'CHANGED',
  'UNCHANGED',
]

/**
 * What actually happened, per resource.
 *
 * <p>Shown as a dialog rather than a toast whenever anything was blocked or
 * missing. A toast is right for "38 deactivated" and wrong for "38
 * deactivated, 2 refused" — the second is a list somebody has to act on, and a
 * notification that disappears after five seconds is not a list.
 *
 * <p><b>Blocked resources are the point of this dialog.</b> They are named,
 * their open-ticket count is shown, and the reassignment route is offered.
 * Deactivating them anyway would orphan live work, which is how tickets stop
 * being anybody's problem.
 */
export function BulkStatusResultDialog({
  result,
  isActivating,
  onClose,
}: BulkStatusResultDialogProps) {
  if (!result) return null

  const ordered = [...result.results].sort(
    (a, b) => OUTCOME_ORDER.indexOf(a.outcome) - OUTCOME_ORDER.indexOf(b.outcome),
  )
  const verb = isActivating ? 'activated' : 'deactivated'

  return (
    <Modal
      open
      onOpenChange={(next) => {
        if (!next) onClose()
      }}
    >
      <ModalContent>
        <ModalHeader>
          <ModalTitle>
            {result.changed} {verb}
          </ModalTitle>
          <ModalDescription>{summarise(result)}</ModalDescription>
        </ModalHeader>

        <ul className="max-h-72 space-y-1 overflow-y-auto text-sm">
          {ordered.map((outcome) => (
            <li key={outcome.userId} className="flex items-start gap-2 py-1">
              <OutcomeIcon outcome={outcome.outcome} />
              <div className="flex flex-col">
                <span className="text-content">{outcome.displayName ?? `#${outcome.userId}`}</span>
                <span className="text-caption text-content-muted">
                  {describe(outcome, isActivating)}
                </span>
              </div>
            </li>
          ))}
        </ul>

        {result.blocked > 0 && (
          <p className="mt-3 rounded-control bg-subtle px-3 py-2 text-caption text-content-muted">
            Reassign their open tickets first — the bulk reassignment wizard is not built yet
            (B-014). Until it lands, reassign from each ticket, then deactivate again.
          </p>
        )}

        <ModalFooter>
          <Button size="sm" onClick={onClose}>
            Done
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}

function summarise(result: BulkUserStatusResponseData): string {
  const parts: string[] = []
  if (result.unchanged > 0) parts.push(`${result.unchanged} already in that state`)
  if (result.blocked > 0) parts.push(`${result.blocked} blocked by open tickets`)
  if (result.notFound > 0) parts.push(`${result.notFound} no longer exist`)
  return parts.length > 0 ? parts.join(' · ') : 'Every selected resource was updated.'
}

function describe(outcome: BulkUserStatusOutcome, isActivating: boolean): string {
  switch (outcome.outcome) {
    case 'CHANGED':
      return isActivating ? 'Activated' : 'Deactivated'
    case 'UNCHANGED':
      return isActivating ? 'Already active' : 'Already inactive'
    case 'BLOCKED_OPEN_TICKETS':
      return `Left active — ${outcome.openTicketCount ?? 0} open ticket${
        outcome.openTicketCount === 1 ? '' : 's'
      } to reassign first`
    case 'NOT_FOUND':
      return 'No longer exists'
    default:
      return ''
  }
}

function OutcomeIcon({ outcome }: { outcome: BulkUserStatusOutcome['outcome'] }) {
  if (outcome === 'BLOCKED_OPEN_TICKETS' || outcome === 'NOT_FOUND') {
    return <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warning-text" aria-hidden />
  }
  return <Check className="mt-0.5 h-4 w-4 shrink-0 text-success-text" aria-hidden />
}
