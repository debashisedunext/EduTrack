import * as React from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { SkipForward } from 'lucide-react'

import { ApiError } from '@/api/http'
import type { Ticket } from '@/api/generated/model/ticket'

import { Button } from '@/components/ui/button'
import { Modal, ModalContent, ModalFooter, ModalHeader, ModalTitle } from '@/components/ui/modal'
import { toast } from '@/components/ui/use-toast'

import { FormField } from '../create/FormField'
import { emptySkipStageForm, skipStageSchema, toSkipStageRequest, type SkipStageFormValues } from './skipStageForm'
import { useSkipStageMutation } from './useSkipStageMutation'

const DIALOG_ID = 'ticket-skip-stage'

export interface SkipStageDialogProps {
  ticket: Ticket
  /** Refetch the detail payload — stage, status and the ribbon all moved. */
  onSkipped: () => void
}

/**
 * C-047 · §4A.6's Skip Stage dialog — blueprint §2's "Skip a stage (with
 * reason)" row, ticked for Admin and PM alone.
 *
 * A **standalone, importable component with no trigger site of its own
 * beyond the ribbon's current segment** — `HandoffDialog`'s own precedent
 * for a detail-page control gated on the golden rule's sibling capability
 * question, `TicketDetailService.availableActions` answering `skip-stage`
 * rather than this component re-deriving who may see it. `RibbonStrip`
 * renders it only when that string is present, so a Developer never mounts
 * this dialog in the first place — not merely hides its trigger.
 *
 * Deliberately thinner than `HandoffDialog`: no assignee, no effort
 * confirmation, no attachments — none of the three apply to a skip
 * (`SkipService`'s own javadoc: "no assignee and no handoff note are sent —
 * a skip reassigns nobody explicitly"). `toStageCode` is optional here
 * because, unlike a handoff, the server has a real default for it
 * (`TransitionService.nextStageAfter`) — the field exists only for the rarer
 * case of skipping past more than one stage in one move.
 */
export function SkipStageDialog({ ticket, onSkipped }: SkipStageDialogProps) {
  const [open, setOpen] = React.useState(false)

  return (
    <>
      <Button type="button" size="sm" variant="secondary" onClick={() => setOpen(true)}>
        <SkipForward className="h-4 w-4" aria-hidden />
        Skip stage
      </Button>
      <Modal open={open} onOpenChange={setOpen}>
        <ModalContent aria-describedby={undefined}>
          {/* Mounted only while open, so a cancelled attempt never resumes on reopen. */}
          {open && <SkipStageForm ticket={ticket} onDone={() => setOpen(false)} onSkipped={onSkipped} />}
        </ModalContent>
      </Modal>
    </>
  )
}

function SkipStageForm({
  ticket,
  onDone,
  onSkipped,
}: {
  ticket: Ticket
  onDone: () => void
  onSkipped: () => void
}) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<SkipStageFormValues>({
    resolver: zodResolver(skipStageSchema),
    defaultValues: emptySkipStageForm(),
    mode: 'onBlur',
  })

  const skipStage = useSkipStageMutation()

  function submit(values: SkipStageFormValues) {
    skipStage.mutate(
      { ticketId: ticket.ticketId, data: toSkipStageRequest(values) },
      {
        onSuccess: () => {
          toast({
            title: `${ticket.ticketId} — stage skipped`,
            description: 'The ribbon has moved on.',
          })
          onSkipped()
          onDone()
        },
      },
    )
  }

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        void handleSubmit(submit)()
      }}
      className="flex flex-col gap-4"
    >
      <ModalHeader>
        <ModalTitle>Skip a stage on {ticket.ticketId}</ModalTitle>
      </ModalHeader>

      <FormField
        id={`${DIALOG_ID}-reason`}
        label="Reason"
        required
        error={errors.reason?.message}
        hint="Shown on the ribbon segment's hover, alongside your name."
      >
        {(aria) => (
          <textarea
            {...aria}
            {...register('reason')}
            rows={3}
            disabled={skipStage.isPending}
            placeholder="Why is this stage being skipped?"
            className="w-full rounded-control border border-border bg-surface px-3 py-2 text-sm text-content placeholder:text-content-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary aria-[invalid=true]:border-danger"
          />
        )}
      </FormField>

      <FormField
        id={`${DIALOG_ID}-to-stage`}
        label="Land on stage"
        error={errors.toStageCode?.message}
        hint="Optional — defaults to the next stage in the workflow."
      >
        {(aria) => <input {...aria} {...register('toStageCode')} disabled={skipStage.isPending}
          className="w-full rounded-control border border-border bg-surface px-3 py-2 text-sm text-content placeholder:text-content-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary aria-[invalid=true]:border-danger"
        />}
      </FormField>

      {/* The server's refusal, shown as it arrived — `HandoffDialog`'s own
          precedent: a non-optional stage (422) or an unresolvable
          destination (400) both name something the user can act on without
          losing what they typed. */}
      {skipStage.isError && (
        <p role="alert" className="text-caption text-danger-text">
          {skipStage.error instanceof ApiError
            ? (skipStage.error.problem.detail ?? skipStage.error.message)
            : 'This stage could not be skipped. Please try again.'}
        </p>
      )}

      <ModalFooter>
        <Button type="button" variant="secondary" size="sm" onClick={onDone} disabled={skipStage.isPending}>
          Cancel
        </Button>
        <Button type="submit" size="sm" disabled={skipStage.isPending}>
          {skipStage.isPending ? 'Skipping…' : 'Skip stage'}
        </Button>
      </ModalFooter>
    </form>
  )
}
