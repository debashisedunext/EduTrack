import * as React from 'react'
import { useForm, Controller } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { AlertTriangle } from 'lucide-react'

import { newIdempotencyKey, ApiError } from '@/api/http'
import { useListUsers } from '@/api/generated/users/users'
import type { Ticket } from '@/api/generated/model/ticket'
import type { User } from '@/api/generated/model/user'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Modal, ModalContent, ModalFooter, ModalHeader, ModalTitle } from '@/components/ui/modal'
import { SearchableDropdown } from '@/components/ui/searchable-dropdown'
import { toast } from '@/components/ui/use-toast'

import { FormField } from '../create/FormField'
import { emptyReopenForm, reopenSchema, toReopenRequest, type ReopenFormValues } from './reopenForm'
import { useReopenMutation } from './useReopenMutation'

const DIALOG_ID = 'ticket-reopen'

export interface ReopenDialogProps {
  ticket: Ticket
  /** Refetch the detail payload — status, cycle, ribbon and history all moved. */
  onReopened: () => void
}

/**
 * S-22 Reopen Dialog — C-039.
 *
 * A dialog rather than the wireframe's inline checkbox for the reason
 * `TicketLevelControl` already gives for its own change-of-mind: the act has
 * more than one required part (a mandatory reason, and the warning that a
 * whole cycle is about to seal), and a control that commits on a single click
 * cannot show either before the write happens.
 *
 * `POST /tickets/{ticketId}/reopen` is only offered when the server's own
 * `availableActions` names it — `TicketDetailHeader` gates this component the
 * same way it gates every other header action, so nothing here re-derives who
 * may reopen a ticket.
 */
export function ReopenDialog({ ticket, onReopened }: ReopenDialogProps) {
  const [open, setOpen] = React.useState(false)

  return (
    <>
      <Button type="button" size="sm" variant="secondary" onClick={() => setOpen(true)}>
        Reopen
      </Button>
      <Modal open={open} onOpenChange={setOpen}>
        <ModalContent aria-describedby={undefined}>
          {/* Mounted only while open, so a cancelled attempt never resumes on reopen. */}
          {open && <ReopenForm ticket={ticket} onDone={() => setOpen(false)} onReopened={onReopened} />}
        </ModalContent>
      </Modal>
    </>
  )
}

function ReopenForm({
  ticket,
  onDone,
  onReopened,
}: {
  ticket: Ticket
  onDone: () => void
  onReopened: () => void
}) {
  const initial = React.useMemo(() => emptyReopenForm(ticket), [ticket])
  const {
    control,
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ReopenFormValues>({
    resolver: zodResolver(reopenSchema),
    defaultValues: initial,
    mode: 'onBlur',
  })

  // Only the project's members can be picked, same rule the create form's
  // assignee field follows (§7.5) — waits for a project rather than showing
  // everyone and filtering after the fact.
  const projectId = ticket.project?.id
  const { data: membersData } = useListUsers(
    { projectId: projectId ?? undefined, isActive: true, limit: 200 },
    { query: { enabled: projectId != null } },
  )
  const members = React.useMemo(() => membersData?.data ?? [], [membersData])

  // Minted once per open dialog, not inside the mutation — a retried request
  // after a timeout must be recognised as the same reopen, not a second cycle.
  const idempotencyKey = React.useRef(newIdempotencyKey())
  const reopen = useReopenMutation()

  const nextCycleNo = (ticket.cycleNo ?? 1) + 1

  function submit(values: ReopenFormValues) {
    reopen.mutate(
      {
        ticketId: ticket.ticketId,
        data: toReopenRequest(values),
        idempotencyKey: idempotencyKey.current,
      },
      {
        onSuccess: () => {
          toast({
            title: `${ticket.ticketId} reopened`,
            description: `Cycle ${ticket.cycleNo ?? 1} is sealed. Cycle ${nextCycleNo} has begun.`,
          })
          onReopened()
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
        <ModalTitle>Reopen {ticket.ticketId}</ModalTitle>
      </ModalHeader>

      <p className="flex items-start gap-2 rounded-control border border-warning bg-surface p-3 text-sm text-warning-text">
        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
        Cycle {ticket.cycleNo ?? 1} and its ribbon will be sealed and preserved. A new cycle with a fresh ribbon will
        begin.
      </p>

      <FormField id={`${DIALOG_ID}-reason`} label="Reason" required error={errors.reason?.message}>
        {(aria) => (
          <textarea
            {...aria}
            {...register('reason')}
            rows={3}
            maxLength={1000}
            disabled={reopen.isPending}
            placeholder="Why is this ticket reopening?"
            className="w-full rounded-control border border-border bg-surface px-3 py-2 text-sm text-content placeholder:text-content-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary aria-[invalid=true]:border-danger"
          />
        )}
      </FormField>

      <FormField
        id={`${DIALOG_ID}-restart-stage`}
        label="Restart stage"
        error={errors.restartStageCode?.message}
        hint="Where the new cycle's ribbon opens. Defaults to Triage."
      >
        {(aria) => <Input {...aria} {...register('restartStageCode')} disabled={reopen.isPending} />}
      </FormField>

      <FormField
        id={`${DIALOG_ID}-assignee`}
        label="New assignee"
        hint="Defaults to whoever held the closing cycle."
      >
        {(aria) => (
          <Controller
            control={control}
            name="assigneeId"
            render={({ field }) => (
              <SearchableDropdown<User>
                {...aria}
                options={members}
                value={members.find((u) => u.id === field.value) ?? null}
                onChange={(user) => field.onChange(user.id)}
                getKey={(user) => String(user.id)}
                getLabel={(user) => user.displayName}
                getSearchable={(user) => [user.email ?? '', user.role ?? '']}
                placeholder={projectId == null ? 'No project on this ticket' : 'Search project members…'}
                emptyText="No active members on this project"
                disabled={reopen.isPending || projectId == null}
              />
            )}
          />
        )}
      </FormField>

      <div className="flex gap-3">
        <FormField
          id={`${DIALOG_ID}-pcd`}
          label="New planned close date"
          className="flex-1"
          hint="Optional — recomputed from the SLA ladder if left blank."
        >
          {(aria) => <Input {...aria} {...register('plannedCloseDate')} type="date" disabled={reopen.isPending} />}
        </FormField>
        <FormField
          id={`${DIALOG_ID}-estimate`}
          label="Revised estimate (hrs)"
          className="flex-1"
          error={errors.estimatedHrs?.message}
          hint="Optional — leaves the current estimate unchanged."
        >
          {(aria) => (
            <Input
              {...aria}
              {...register('estimatedHrs')}
              inputMode="decimal"
              placeholder="8"
              disabled={reopen.isPending}
            />
          )}
        </FormField>
      </div>

      {/*
        The server's refusal, shown as it arrived — same reason
        `TicketLevelControl` shows one here rather than only in a toast: a
        400 names a field this form can still let the user fix without losing
        what they typed.
      */}
      {reopen.isError && (
        <p role="alert" className="text-caption text-danger-text">
          {reopen.error instanceof ApiError
            ? (reopen.error.problem.detail ?? reopen.error.message)
            : 'The ticket could not be reopened. Please try again.'}
        </p>
      )}

      <ModalFooter>
        <Button type="button" variant="secondary" size="sm" onClick={onDone} disabled={reopen.isPending}>
          Cancel
        </Button>
        <Button type="submit" size="sm" disabled={reopen.isPending}>
          {reopen.isPending ? 'Reopening…' : 'Reopen'}
        </Button>
      </ModalFooter>
    </form>
  )
}
