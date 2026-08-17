import * as React from 'react'
import { Pencil } from 'lucide-react'

import { useChangeTicketPriority } from '@/api/generated/tickets/tickets'
import { useListPriorities } from '@/api/generated/masters/masters'
import type { Level } from '@/api/generated/model/level'
import type { Ticket } from '@/api/generated/model/ticket'
import { ApiError } from '@/api/http'

import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { Modal, ModalContent, ModalFooter, ModalHeader, ModalTitle } from '@/components/ui/modal'
import { toast } from '@/components/ui/use-toast'
import { cn } from '@/lib/utils'

import { LEVEL_VARIANT } from '../list/columns'
// Reused rather than re-drawn. §4B.1's colour chips are the same control on the
// create form and on this panel, and a second implementation is how the two
// quietly stop agreeing about which levels exist and in what order. It lives
// under `create/` because that is where it was first needed; both directories
// are this stream's, so the import is a reuse rather than a reach.
import { LevelPicker } from '../create/LevelPicker'
import { SlaPreview } from '../sla/SlaPreview'
import { usePlannedCloseDate } from '../sla/usePlannedCloseDate'
import { isLevelReasonRequired } from './levelChange'

export interface TicketLevelControlProps {
  ticket: Ticket
  /** Where this cycle's SLA clock started — see `slaClockStart`. */
  clockStart?: string
  /**
   * False for the three roles §4B.1 excludes, while `useGetMe()` is resolving,
   * and for a sealed earlier cycle. The row then renders exactly as C-019 drew
   * it, so nothing shifts when the answer arrives.
   */
  canEdit: boolean
  /** Refetch the detail payload — the level, the date and the history all moved. */
  onChanged: () => void
}

const DIALOG_ID = 'ticket-level-change'

/**
 * C-020 · the Level row of S-20's summary panel — blueprint §4B.1.
 *
 * ## Why a dialog and not an inline popover
 *
 * §4B.1 asks for "editable directly on the detail page from the summary panel —
 * one click, no full-page edit mode", and the first draft of this was a popover
 * over the chip that committed on selection. It was wrong for a reason specific
 * to this field, not to popovers: **the change has two required parts.** Picking
 * Critical is half the act; saying why is the other half on any assigned ticket,
 * and §4B.1 also insists the recomputed date is shown *before the user commits*.
 * A control that commits on selection cannot do either — it would have to either
 * write first and ask afterwards, or grow a second step, at which point it is a
 * dialog that opened sideways.
 *
 * So: one click on the chip opens it, no page mode changes, nothing else on the
 * screen is disturbed, and the two things §4B.1 requires before the write both
 * fit. The rest of the panel stays read-only, which is C-019's design.
 *
 * ## The preview measures from the cycle's start, not from now
 *
 * `clockStart` is passed in rather than defaulted, and `slaClockStart` in
 * `levelChange.ts` carries the argument. The short version: an SLA is "resolve
 * within N hours of being **reported**", so escalating a three-day-old ticket to
 * a four-hour level really does produce a date in the past. The dialog shows
 * that date. It is not a rendering bug to be clamped to now — it is the user
 * being told, before they commit, that the ticket they are about to call
 * Critical is already late.
 *
 * ## What is deliberately not here
 *
 * The three roles §4B.1 excludes get no "Request a change" button. That path is
 * a notification to the PM and belongs to Stream D; a button that silently did
 * nothing would be worse than the honest absence.
 */
export function TicketLevelControl({ ticket, clockStart, canEdit, onChanged }: TicketLevelControlProps) {
  const [open, setOpen] = React.useState(false)
  const [level, setLevel] = React.useState<Level>(ticket.level)
  const [reason, setReason] = React.useState('')
  // Set only by a rejected submit, and cleared on every edit — so the textarea
  // is never red before the user has been given a chance to fill it in.
  const [showReasonError, setShowReasonError] = React.useState(false)

  const { data: prioritiesData } = useListPriorities()

  /*
   * The master decides which levels exist and in what order — S-12 lets an
   * administrator add one without a release. The fallback is the ticket's own
   * level alone rather than a hardcoded four: a hardcoded list would be a second
   * copy of the master that silently disagrees with it the day a fifth level is
   * added, and offering only the current level while the master is loading is
   * honest about knowing nothing yet.
   */
  const levels = React.useMemo<Level[]>(() => {
    const fromMaster = (prioritiesData?.data ?? [])
      .map((p) => p.level)
      .filter((l): l is Level => l != null)
    return fromMaster.length > 0 ? fromMaster : [ticket.level]
  }, [prioritiesData, ticket.level])

  const reasonRequired = isLevelReasonRequired(ticket)
  const changed = level !== ticket.level
  const reasonMissing = reasonRequired && reason.trim().length === 0

  const preview = usePlannedCloseDate({
    projectId: ticket.project?.id ?? null,
    taskTypeId: ticket.taskTypeId ?? null,
    // Only once it differs. Previewing the level the ticket already holds would
    // fire a request whose answer is the date already on screen.
    level: open && changed ? level : null,
    assigneeId: ticket.assignee?.id ?? null,
    ...(clockStart ? { from: clockStart } : {}),
  })

  const mutation = useChangeTicketPriority()

  // Reopening after a cancel must not resume the abandoned edit — the chip the
  // dialog opens from shows the ticket's level, so that is what it must open on.
  const reset = React.useCallback(() => {
    setLevel(ticket.level)
    setReason('')
    setShowReasonError(false)
    mutation.reset()
  }, [ticket.level, mutation])

  const onOpenChange = (next: boolean) => {
    if (next) reset()
    setOpen(next)
  }

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    if (!changed) {
      setOpen(false)
      return
    }
    if (reasonMissing) {
      setShowReasonError(true)
      return
    }

    mutation.mutate(
      {
        ticketId: ticket.ticketId,
        data: { level, ...(reason.trim() ? { reason: reason.trim() } : {}) },
      },
      {
        onSuccess: () => {
          setOpen(false)
          // The level, the planned close date and the history all moved, and
          // two of the three are rendered elsewhere on this page. Refetching the
          // one aggregated call is what makes them agree; patching the level into
          // the cache would leave a stale date beside a new chip.
          onChanged()
          toast({
            title: `Level changed to ${level}`,
            description: 'The planned close date has been recalculated.',
          })
        },
      },
    )
  }

  const chip = (
    <span className="flex flex-wrap items-center gap-1.5">
      <Chip variant={LEVEL_VARIANT[ticket.level]}>{ticket.level}</Chip>
    </span>
  )

  if (!canEdit) return chip

  return (
    <>
      <button
        type="button"
        onClick={() => onOpenChange(true)}
        aria-haspopup="dialog"
        // The chip alone would announce as "MEDIUM, button", which says nothing
        // about what pressing it does — and the visible pencil is decoration a
        // screen reader never reaches.
        aria-label={`Level ${ticket.level}. Change the level`}
        className={cn(
          'group -mx-1 inline-flex items-center gap-1.5 rounded-control px-1 py-0.5 transition-colors',
          'hover:bg-subtle focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-1',
        )}
      >
        <Chip variant={LEVEL_VARIANT[ticket.level]}>{ticket.level}</Chip>
        <Pencil
          aria-hidden="true"
          className="h-3 w-3 text-content-muted opacity-0 transition-opacity group-hover:opacity-100 group-focus-visible:opacity-100"
        />
      </button>

      <Modal open={open} onOpenChange={onOpenChange}>
        <ModalContent aria-describedby={undefined}>
          <ModalHeader>
            <ModalTitle>Change level</ModalTitle>
          </ModalHeader>

          <form onSubmit={submit} className="flex flex-col gap-4">
            <div className="flex flex-col gap-1.5">
              <span id={`${DIALOG_ID}-level-label`} className="text-caption text-content-muted">
                Level
              </span>
              <LevelPicker
                id={`${DIALOG_ID}-level`}
                aria-labelledby={`${DIALOG_ID}-level-label`}
                levels={levels}
                value={level}
                onChange={(next) => setLevel(next)}
                disabled={mutation.isPending}
              />
            </div>

            {/*
              §4B.1: "recomputes the Planned Close Date and shows the new date
              inline before the user commits". Rendered only once the level
              differs — before that the answer is the date already in the panel
              two rows below, and showing it twice invites the reading that it
              has already changed.

              `isReady` as well, which is not belt-and-braces: without a project
              id `SlaPreview` renders its create-form copy, "Pick a project and a
              priority to see the planned close date" — instructions for a field
              this dialog does not have and the user cannot reach. Nothing is
              better than an instruction that cannot be followed.
            */}
            {changed && preview.isReady && <SlaPreview {...preview} />}

            <div className="flex flex-col gap-1.5">
              <label htmlFor={`${DIALOG_ID}-reason`} className="text-caption text-content-muted">
                Reason{reasonRequired && <span className="text-danger"> *</span>}
              </label>
              <textarea
                id={`${DIALOG_ID}-reason`}
                value={reason}
                onChange={(e) => {
                  setReason(e.target.value)
                  setShowReasonError(false)
                }}
                rows={3}
                maxLength={500}
                disabled={mutation.isPending}
                aria-invalid={showReasonError || undefined}
                aria-describedby={`${DIALOG_ID}-reason-hint`}
                className={cn(
                  'w-full rounded-control border bg-surface px-3 py-2 text-sm text-content',
                  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
                  showReasonError ? 'border-danger' : 'border-border',
                )}
              />
              <p
                id={`${DIALOG_ID}-reason-hint`}
                className={cn('text-caption', showReasonError ? 'text-danger-text' : 'text-content-muted')}
              >
                {showReasonError
                  ? 'This ticket is assigned, so a reason is required.'
                  : reasonRequired
                    ? 'Required — this ticket is assigned, and the level change is recorded against your name.'
                    : 'Optional while the ticket is unassigned.'}
              </p>
            </div>

            {/*
              The server's refusal, shown as it arrived. `role="alert"` because
              it appears after a submit the user is waiting on, and the two cases
              it covers need opposite responses: a 400 names a field to fix, and
              anything else is a retry.
            */}
            {mutation.isError && (
              <p role="alert" className="text-caption text-danger-text">
                {mutation.error instanceof ApiError
                  ? mutation.error.message
                  : 'The level could not be changed. Please try again.'}
              </p>
            )}

            <ModalFooter>
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={() => onOpenChange(false)}
                disabled={mutation.isPending}
              >
                Cancel
              </Button>
              <Button type="submit" size="sm" disabled={!changed || mutation.isPending}>
                {mutation.isPending ? 'Saving…' : 'Save'}
              </Button>
            </ModalFooter>
          </form>
        </ModalContent>
      </Modal>
    </>
  )
}
