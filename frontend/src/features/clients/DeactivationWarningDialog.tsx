import { Button } from '@/components/ui/button'
import {
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
} from '@/components/ui/modal'

import type { DeactivationCandidate } from './deactivation'

/**
 * B-029 · the warning blueprint line 523 asks for, with a number in it.
 *
 * <h2>One dialog, because there are two ways to deactivate a client</h2>
 *
 * B-025 wrote this inside `ClientBulkStatusBar` when S-32's bulk bar was the
 * only path. It is not: S-33's Identity tab has a Status select that reaches
 * `INACTIVE` in two clicks and saved without a word about it. Two dialogs would
 * be two chances to describe the same consequence differently — the drift
 * `ProjectRoles`, `PasswordComplexity` and (B-028) `FieldValidators` exist to
 * prevent, and the reason this moved out of the bulk bar rather than being
 * copied into the form.
 *
 * <h2>It warns; it does not refuse</h2>
 *
 * S-07's equivalent dialog *blocks* — a resource with open tickets cannot be
 * deactivated until they are reassigned, because the tickets would be orphaned.
 * A client is the opposite case and the blueprint says so in one sentence:
 * deactivating "prompts a warning and blocks new ticket creation against it,
 * but never hides the historical tickets". Nothing is orphaned, nothing needs
 * reassigning, and the open tickets stay open and worked on. So this confirms
 * rather than refuses, and the count is there to make the decision informed
 * rather than to veto it.
 *
 * <h2>What it promises is what the rest of B-029 has to deliver</h2>
 *
 * The copy states both remaining clauses — new tickets blocked, existing ones
 * untouched and still visible. Those are not decoration: the block is
 * `ticketEligibility.ts` plus, once it exists, `POST /tickets`; "still visible"
 * is why the S-15 client filter no longer sends `?isActive=true`. A dialog that
 * promised either without the code behind it would be the worst kind of wrong,
 * because the admin acts on it and finds out weeks later.
 */

export function DeactivationWarningDialog({
  affected,
  totalSelected,
  isPending,
  confirmLabel,
  onConfirm,
  onCancel,
}: {
  affected: readonly DeactivationCandidate[] | null
  totalSelected: number
  isPending: boolean
  /**
   * Overridden by S-33, where the action is *Save* and the deactivation is one
   * field on a form of thirty. A button reading "Deactivate 1" on a page whose
   * primary action is Save would describe a narrower act than the one about to
   * happen — the admin may have edited the address in the same sitting.
   */
  confirmLabel?: string
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
          <Button type="button" variant="secondary" onClick={onCancel} disabled={isPending}>
            Cancel
          </Button>
          {/*
            `type="button"` on both, and on this one it is load-bearing rather
            than tidy. S-33 renders this dialog from inside `ClientFormPage`'s
            `<form>`; radix portals `ModalContent` to `document.body`, so it is
            a sibling and safe today — but a default `submit` here would mean
            the confirmation submits the form *and* runs `onConfirm`, which is
            a double save the moment anybody stops portalling. B-027 lost the
            same argument the other way round on the Contacts tab.
          */}
          <Button type="button" onClick={onConfirm} disabled={isPending}>
            {isPending
              ? 'Deactivating…'
              : (confirmLabel ?? `Deactivate ${totalSelected}`)}
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}
