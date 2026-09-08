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

import { BLOCK_REASONS } from './blockReasons'

/**
 * C-111 · blocking a step, with the mandatory reason plan addition 5 calls
 * "blocked-with-reason".
 *
 * ## The reason is a code, not a sentence, and that is this screen's decision
 *
 * `ObBlockJourneyStepRequest.reasonCode` is `type: string, maxLength: 40` —
 * the contract accepts anything. Its stated purpose is to power **"where is it
 * stuck"**, which is an aggregate across every blocked step in the module, and
 * free text cannot be aggregated: twenty owners describing the same obstacle
 * produce twenty categories and a report nobody can read.
 *
 * So the reasons are a select rather than a text box, with the free-text
 * detail moved to `note` where it belongs. The list itself is in
 * `blockReasons.ts` — including the flag that **it is not ratified anywhere**
 * and belongs with the onboarding masters once somebody owns that vocabulary.
 *
 * ## Blocked is not the same as waiting on the client, and the difference is money
 *
 * This is the most consequential choice on the panel, so the dialog says it
 * out loud rather than assuming the owner remembers. Plan §5.7:
 *
 * - **Blocked** — an internal obstacle. The TAT clock **keeps running**, and
 *   the delay is charged to us.
 * - **Waiting on client** — the clock **pauses** and the time is attributed to
 *   the client.
 *
 * `ObStepClockState`'s own contract puts it plainly: "`PAUSED` means and only
 * means `WAITING_ON_CLIENT`: an internal `BLOCKED` step is still `RUNNING`."
 * An owner who picks the wrong one is not making a cosmetic mistake — they are
 * moving a TAT breach onto the wrong party's account, and every reason code
 * below is therefore an *internal* one.
 */
export interface BlockStepDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  isPending?: boolean
  onConfirm: (reasonCode: string, note: string | null) => void
}

export function BlockStepDialog({ open, onOpenChange, isPending = false, onConfirm }: BlockStepDialogProps) {
  const [reasonCode, setReasonCode] = React.useState('')
  const [note, setNote] = React.useState('')
  const selectId = React.useId()
  const noteId = React.useId()

  React.useEffect(() => {
    if (open) {
      setReasonCode('')
      setNote('')
    }
  }, [open])

  const selected = BLOCK_REASONS.find((r) => r.code === reasonCode)

  return (
    <Modal open={open} onOpenChange={onOpenChange}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>Block this service</ModalTitle>
          <ModalDescription>
            The TAT clock keeps running while a service is blocked — an internal hold is our delay,
            not the client’s. If you are waiting on the client, close this and choose{' '}
            <strong>Waiting on client</strong> instead, which pauses the clock.
          </ModalDescription>
        </ModalHeader>

        <label htmlFor={selectId} className="text-sm font-medium text-content">
          What is blocking it
        </label>
        <select
          id={selectId}
          value={reasonCode}
          onChange={(event) => setReasonCode(event.target.value)}
          className="mt-1 w-full rounded-control border border-border bg-surface px-3 py-2 text-sm text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        >
          <option value="">Choose a reason…</option>
          {BLOCK_REASONS.map((reason) => (
            <option key={reason.code} value={reason.code}>
              {reason.label}
            </option>
          ))}
        </select>
        {selected && <p className="mt-1 text-caption text-content-muted">{selected.hint}</p>}

        <label htmlFor={noteId} className="mt-4 block text-sm font-medium text-content">
          Detail <span className="font-normal text-content-muted">(optional)</span>
        </label>
        <textarea
          id={noteId}
          value={note}
          onChange={(event) => setNote(event.target.value)}
          rows={3}
          maxLength={500}
          className="mt-1 w-full rounded-control border border-border bg-surface px-3 py-2 text-sm text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        />

        <ModalFooter>
          <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={isPending}>
            Cancel
          </Button>
          <Button
            variant="danger"
            disabled={!reasonCode || isPending}
            onClick={() => onConfirm(reasonCode, note.trim() || null)}
          >
            Block service
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}
