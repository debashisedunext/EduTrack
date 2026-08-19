import { AlertTriangle, Loader2 } from 'lucide-react'

import type { ImportBatch } from '@/api/generated/model'
import { Button } from '@/components/ui/button'
import {
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
} from '@/components/ui/modal'

import { reversalWarning } from './importHistory'
import { type ImportNouns } from './importWizard'

/**
 * B-037 · the confirmation for the one action in this product that deletes
 * rows from the client master.
 *
 * Blueprint §4B.3's closing rule and §17's mitigation for *"Client Excel import
 * silently corrupts the master"*: an import can be reversed as a set. Everywhere
 * else a client is deactivated (B-029) and history is preserved; here it goes.
 * That asymmetry is the reason this dialog exists at all rather than the button
 * simply firing.
 *
 * ## It states what it will *not* do, and that is the point
 *
 * `DeactivationWarningDialog` beside this one warns about a consequence; this
 * one has to warn about a **limit**. A reversal deletes what the run created and
 * cannot restore what it updated — there is no before image anywhere. An Admin
 * who imported 412 rows and presses a button labelled Reverse will reasonably
 * expect all 412 to go back to how they were, and that expectation is wrong.
 *
 * Saying so afterwards, on the result screen, would be too late: they would have
 * pressed it believing something else. So `reversalWarning` produces both
 * sentences and both are shown before the button, not after it.
 *
 * ## It does not promise a count of what will survive
 *
 * A client that has since been named on a ticket is kept, and the dialog could
 * have fetched that number to say "3 will be kept". It deliberately does not:
 * the count is a query against live tickets, it can change between the dialog
 * opening and the button being pressed, and a promise that turns out to be wrong
 * on a destructive action is worse than no promise. The result names every
 * retained client afterwards, with the reason, which is the honest place for it.
 */
export function ReverseImportDialog({
  batch,
  nouns,
  isPending,
  onConfirm,
  onCancel,
}: {
  /** The run to reverse, or `null` when the dialog is closed. */
  batch: ImportBatch | null
  /** B-038 · what this registration calls the thing being deleted. */
  nouns: ImportNouns
  isPending: boolean
  onConfirm: () => void
  onCancel: () => void
}) {
  const open = batch != null
  const warning = batch ? reversalWarning(batch, nouns) : null

  return (
    <Modal open={open} onOpenChange={(next) => !next && !isPending && onCancel()}>
      <ModalContent className="max-w-lg">
        <ModalHeader>
          <ModalTitle className="flex items-center gap-2">
            <AlertTriangle className="h-5 w-5 text-danger-text" aria-hidden="true" />
            Reverse this import?
          </ModalTitle>
          <ModalDescription>
            {batch?.fileName
              ? `Import #${batch.batchId} — ${batch.fileName}`
              : `Import #${batch?.batchId}`}
          </ModalDescription>
        </ModalHeader>

        <div className="space-y-3 text-sm text-content">
          <p>{warning?.deletes}</p>

          {warning?.keeps && (
            /*
              The limit, in its own box rather than as a third sentence in the
              paragraph above. It is the one thing on this dialog a user is
              likely to be surprised by afterwards, and a surprise about a
              destructive action belongs where it cannot be skimmed past.
            */
            <p className="rounded-control border border-warning bg-surface p-3 text-warning-text">
              {warning.keeps}
            </p>
          )}

          <p className="text-content-muted">
            A {nouns.one} that work has been recorded against since the import is
            kept rather than deleted, and named in the result. This cannot be
            undone.
          </p>
        </div>

        <ModalFooter>
          <Button type="button" variant="secondary" onClick={onCancel} disabled={isPending}>
            Cancel
          </Button>
          <Button type="button" variant="danger" onClick={onConfirm} disabled={isPending}>
            {isPending && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
            {isPending ? 'Reversing…' : 'Reverse import'}
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}
