import * as React from 'react'
import { Controller, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { AlertTriangle } from 'lucide-react'

import { useCloseTicket } from '@/api/generated/tickets/tickets'
import type { Ticket } from '@/api/generated/model/ticket'
import { ApiError } from '@/api/http'

import { Button } from '@/components/ui/button'
import { Modal, ModalContent, ModalFooter, ModalHeader, ModalTitle } from '@/components/ui/modal'
import { toast } from '@/components/ui/use-toast'
import { cn } from '@/lib/utils'

import { CLOSE_FORM_DEFAULTS, closeFormSchema, toCloseRequest, type CloseFormValues } from './closeForm'

export interface CloseDialogProps {
  ticket: Ticket
  /** Refetch the detail payload — status, the sealed cycle and the History tab all moved. */
  onClosed: () => void
}

const DIALOG_ID = 'ticket-close'

/**
 * C-040 · S-23's close dialog, blueprint §4A.2 — `TicketLevelControl`'s and
 * C-039's `ReopenDialog`'s sibling, one route over.
 *
 * `useCloseTicket` (orval-generated) is called directly rather than through a
 * hand-written mutation hook. `POST /tickets/{ticketId}/close` carries no
 * `Idempotency-Key` parameter in the contract — unlike reopen, closing a
 * ticket twice is not "create it twice", `TicketNotResolvedException`
 * already refuses the second attempt as *not resolved* — so none of the
 * header-dropping problems `useReopenMutation` and `useQuickUpdateMutation`
 * exist here to work around.
 *
 * No generated Zod schema exists for `CloseRequest` either; `closeForm.ts`
 * hand-authors one against the contract's own bounds, same gap and same fix
 * C-039 found for `ReopenRequest`.
 */
export function CloseDialog({ ticket, onClosed }: CloseDialogProps) {
  const [open, setOpen] = React.useState(false)

  const {
    register,
    control,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<CloseFormValues>({
    resolver: zodResolver(closeFormSchema),
    defaultValues: CLOSE_FORM_DEFAULTS,
  })

  const mutation = useCloseTicket()

  // Reopening the dialog after a cancel must not resume the abandoned draft.
  const onOpenChange = (next: boolean) => {
    if (next) {
      reset(CLOSE_FORM_DEFAULTS)
      mutation.reset()
    }
    setOpen(next)
  }

  const submit = handleSubmit((values) => {
    mutation.mutate(
      { ticketId: ticket.ticketId, data: toCloseRequest(values) },
      {
        onSuccess: () => {
          setOpen(false)
          // Status, the sealed cycle and the History tab all moved — the same
          // refetch-not-patch rule ReopenDialog's own onReopened follows.
          onClosed()
          toast({
            title: 'Ticket closed',
            description: `Cycle ${ticket.cycleNo ?? 1} is now sealed and read-only.`,
          })
        },
      },
    )
  })

  return (
    <>
      <Button type="button" size="sm" variant="secondary" onClick={() => onOpenChange(true)}>
        Close
      </Button>

      <Modal open={open} onOpenChange={onOpenChange}>
        <ModalContent aria-describedby={`${DIALOG_ID}-warning`}>
          <ModalHeader>
            <ModalTitle>Close ticket</ModalTitle>
          </ModalHeader>

          <form onSubmit={submit} className="flex flex-col gap-4">
            <div
              id={`${DIALOG_ID}-warning`}
              role="note"
              className="flex gap-2 rounded-control border border-warning/40 bg-level-high-soft px-3 py-2.5 text-caption text-warning-text"
            >
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
              <span>
                Closing seals cycle {ticket.cycleNo ?? 1} — its ribbon becomes read-only. The ticket can
                still be reopened later if the client reports the same issue again.
              </span>
            </div>

            <div className="flex flex-col gap-1.5">
              <label htmlFor={`${DIALOG_ID}-summary`} className="text-caption text-content-muted">
                Resolution summary<span className="text-danger"> *</span>
              </label>
              <textarea
                id={`${DIALOG_ID}-summary`}
                {...register('resolutionSummary')}
                rows={4}
                maxLength={4000}
                disabled={mutation.isPending}
                aria-invalid={!!errors.resolutionSummary || undefined}
                aria-describedby={`${DIALOG_ID}-summary-hint`}
                className={cn(
                  'w-full rounded-control border bg-surface px-3 py-2 text-sm text-content',
                  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
                  errors.resolutionSummary ? 'border-danger' : 'border-border',
                )}
              />
              <p
                id={`${DIALOG_ID}-summary-hint`}
                className={cn('text-caption', errors.resolutionSummary ? 'text-danger-text' : 'text-content-muted')}
              >
                {errors.resolutionSummary?.message ?? 'How was this resolved? Recorded on the History tab.'}
              </p>
            </div>

            <div className="flex flex-col gap-1.5">
              <label htmlFor={`${DIALOG_ID}-root-cause`} className="text-caption text-content-muted">
                Root cause category
              </label>
              <input
                id={`${DIALOG_ID}-root-cause`}
                {...register('rootCauseCategory')}
                maxLength={100}
                disabled={mutation.isPending}
                placeholder="e.g. Configuration drift"
                className="w-full rounded-control border border-border bg-surface px-3 py-2 text-sm text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
              />
            </div>

            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <div className="flex flex-col gap-1.5">
                <label htmlFor={`${DIALOG_ID}-close-date`} className="text-caption text-content-muted">
                  Actual close date
                </label>
                <input
                  id={`${DIALOG_ID}-close-date`}
                  type="datetime-local"
                  {...register('actualCloseDate')}
                  disabled={mutation.isPending}
                  className="w-full rounded-control border border-border bg-surface px-3 py-2 text-sm text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                />
                <p className="text-caption text-content-muted">Blank defaults to now.</p>
              </div>

              <div className="flex flex-col gap-1.5">
                <label htmlFor={`${DIALOG_ID}-effort`} className="text-caption text-content-muted">
                  Final effort (hours)
                </label>
                <input
                  id={`${DIALOG_ID}-effort`}
                  type="number"
                  step="0.25"
                  min="0"
                  {...register('finalEffortHours')}
                  disabled={mutation.isPending}
                  placeholder="e.g. 18.5"
                  aria-invalid={!!errors.finalEffortHours || undefined}
                  className="w-full rounded-control border border-border bg-surface px-3 py-2 text-sm text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                />
                <p className="text-caption text-content-muted">
                  Confirms hours already logged — does not add a new entry.
                </p>
              </div>
            </div>

            <label className="flex items-center gap-2 text-sm text-content">
              <Controller
                control={control}
                name="requestClientVerification"
                render={({ field }) => (
                  <input
                    type="checkbox"
                    checked={field.value}
                    onChange={(e) => field.onChange(e.target.checked)}
                    disabled={mutation.isPending}
                    className="h-4 w-4 rounded border-border text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-1"
                  />
                )}
              />
              Ask the client to verify this resolution
            </label>

            {/*
              The server's refusal, shown as it arrived — `TicketLevelControl`'s
              own reasoning: the two cases this covers need opposite responses,
              a 422 names the ticket's real status and anything else is a retry.
            */}
            {mutation.isError && (
              <p role="alert" className="text-caption text-danger-text">
                {mutation.error instanceof ApiError
                  ? mutation.error.message
                  : 'The ticket could not be closed. Please try again.'}
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
              <Button type="submit" size="sm" disabled={mutation.isPending}>
                {mutation.isPending ? 'Closing…' : 'Close ticket'}
              </Button>
            </ModalFooter>
          </form>
        </ModalContent>
      </Modal>
    </>
  )
}
