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
 * B-025 · S-32's bulk activate/deactivate controls.
 *
 * Kept out of `ClientListPage` for the reason `resources/BulkStatusBar.tsx` is:
 * the grid is long enough already, and the confirmation is the part worth
 * reading on its own.
 */

/** A client the admin is about to deactivate that still holds open tickets. */
export interface DeactivationCandidate {
  id: number
  name: string
  clientCode: string
  openTicketCount: number
}

export function ClientBulkStatusBar({
  selectedCount,
  isPending,
  onApply,
  onClear,
}: {
  selectedCount: number
  isPending: boolean
  onApply: (isActive: boolean) => void
  onClear: () => void
}) {
  if (selectedCount === 0) return null

  return (
    <div
      role="region"
      aria-label="Bulk actions"
      className="flex flex-wrap items-center gap-3 rounded-control bg-subtle px-3 py-2"
    >
      <p className="text-sm text-content" role="status">
        {selectedCount} selected
      </p>
      <div className="ml-auto flex items-center gap-2">
        <Button size="sm" variant="secondary" disabled={isPending} onClick={() => onApply(true)}>
          Activate
        </Button>
        <Button size="sm" variant="secondary" disabled={isPending} onClick={() => onApply(false)}>
          Deactivate
        </Button>
        <Button size="sm" variant="ghost" disabled={isPending} onClick={onClear}>
          Clear
        </Button>
      </div>
    </div>
  )
}

/**
 * The warning blueprint §4B.2 asks for, with a number in it.
 *
 * <h2>It warns; it does not refuse</h2>
 *
 * S-07's equivalent dialog *blocks* — a resource with open tickets cannot be
 * deactivated until they are reassigned, because the tickets would be orphaned.
 * A client is the opposite case and the blueprint says so in a sentence:
 * deactivating "prompts a warning and blocks new ticket creation against it, but
 * never hides the historical tickets". Nothing is orphaned, nothing needs
 * reassigning, and the open tickets stay open and worked on. So this confirms
 * rather than refuses, and the count is there to make the decision informed
 * rather than to veto it.
 *
 * Enforcing the "blocks new tickets" half is B-029's, on the ticket create path
 * — it cannot live here, because this screen is not what a ticket is raised
 * through.
 *
 * Only shown for deactivation, and only when the selection actually holds open
 * tickets. Activation cannot strand anything, and a confirmation on every bulk
 * action is a confirmation nobody reads.
 */
export function DeactivationWarningDialog({
  affected,
  totalSelected,
  isPending,
  onConfirm,
  onCancel,
}: {
  affected: readonly DeactivationCandidate[] | null
  totalSelected: number
  isPending: boolean
  onConfirm: () => void
  onCancel: () => void
}) {
  const open = affected != null && affected.length > 0
  const openTickets = (affected ?? []).reduce((sum, c) => sum + c.openTicketCount, 0)

  return (
    <Modal open={open} onOpenChange={(next) => !next && onCancel()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>Deactivate {totalSelected === 1 ? 'this client' : 'these clients'}?</ModalTitle>
          <ModalDescription>
            {affected?.length === 1 ? 'One client has' : `${affected?.length ?? 0} clients have`}{' '}
            open tickets — {openTickets} in total. Deactivating blocks{' '}
            <strong>new</strong> tickets against them. Existing tickets stay open, stay assigned and
            stay visible in every report.
          </ModalDescription>
        </ModalHeader>

        <ul className="max-h-56 overflow-y-auto rounded-control border border-subtle">
          {(affected ?? []).map((client) => (
            <li
              key={client.id}
              className="flex items-center justify-between gap-3 border-b border-subtle px-3 py-2 last:border-b-0"
            >
              <span className="flex flex-col">
                <span className="text-sm text-content">{client.name}</span>
                <span className="font-mono text-caption text-content-muted">
                  {client.clientCode}
                </span>
              </span>
              <span className="text-caption text-content-muted">
                {client.openTicketCount} open
              </span>
            </li>
          ))}
        </ul>

        <ModalFooter>
          <Button variant="secondary" onClick={onCancel} disabled={isPending}>
            Cancel
          </Button>
          <Button onClick={onConfirm} disabled={isPending}>
            {isPending ? 'Deactivating…' : `Deactivate ${totalSelected}`}
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}
