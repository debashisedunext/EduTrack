import * as React from 'react'
import { Controller, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { ArrowRight } from 'lucide-react'

import { newIdempotencyKey, ApiError } from '@/api/http'
import { useListProjectMembers } from '@/api/generated/projects/projects'
import { useListWorkflowTemplates } from '@/api/generated/masters/masters'
import type { Ticket } from '@/api/generated/model/ticket'
import type { ProjectMember } from '@/api/generated/model/projectMember'
import type { Ribbon } from '@/api/generated/model/ribbon'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Modal, ModalContent, ModalFooter, ModalHeader, ModalTitle } from '@/components/ui/modal'
import { SearchableDropdown } from '@/components/ui/searchable-dropdown'
import { AttachmentPicker } from '@/components/ui/attachment-picker'
import { toast } from '@/components/ui/use-toast'

import { useTicketAttachments } from '../attachments/useTicketAttachments'
import { FormField } from '../create/FormField'
import {
  emptyHandoffForm,
  handoffSchema,
  handoffStageOptions,
  stageOwnerRole,
  toHandoffRequest,
  type HandoffFormValues,
  type HandoffStageOption,
} from './handoffForm'
import { useHandoffMutation } from './useHandoffMutation'

const DIALOG_ID = 'ticket-handoff'

export interface HandoffDialogProps {
  ticket: Ticket
  /**
   * `detail.ribbon` from `GET /tickets/{ticketId}/full`, when the caller has
   * it. Used only to pre-fill the next stage and to resolve a stage's owner
   * role for the assignee filter — see `handoffForm.ts`'s `nextStageCode` and
   * `stageOwnerRole` for what each falls back to when this is `undefined`,
   * which it always is against the real backend today: C-051/C-053 have not
   * landed, so `TicketDetailService` does not populate `ribbon` yet, though
   * the mock already does.
   */
  ribbon?: Ribbon
  /** Refetch the detail payload — stage, assignee, status and the ribbon all moved. */
  onHandedOff: () => void
}

/**
 * S-29 Handoff Dialog — C-044, blueprint §4A.
 *
 * **Live, and reached from the ribbon's current segment.** This comment used
 * to say the component was standalone with "no trigger site of its own yet",
 * pending C-051/C-052; that stopped being true once `TicketDetailPage`
 * started rendering it beside the ribbon, and BUG-003 — a real defect found
 * by a user opening this dialog from that segment — is what caught the
 * comment still claiming otherwise.
 *
 * Composed the same way as `ReopenDialog`/`CloseDialog`: the exported
 * component owns its own trigger button and open state, so the caller renders
 * `<HandoffDialog .../>` and nothing about the modal's lifecycle. Should the
 * labelled button later give way to a segment click, the form below depends
 * on nothing more than `open`/`onOpenChange`, so replacing the trigger does
 * not mean rebuilding the dialog.
 */
export function HandoffDialog({ ticket, ribbon, onHandedOff }: HandoffDialogProps) {
  const [open, setOpen] = React.useState(false)

  return (
    <>
      <Button type="button" size="sm" onClick={() => setOpen(true)}>
        <ArrowRight className="h-4 w-4" aria-hidden />
        Hand off
      </Button>
      <Modal open={open} onOpenChange={setOpen}>
        <ModalContent aria-describedby={undefined}>
          {/* Mounted only while open, so a cancelled attempt never resumes on reopen. */}
          {open && <HandoffForm ticket={ticket} ribbon={ribbon} onDone={() => setOpen(false)} onHandedOff={onHandedOff} />}
        </ModalContent>
      </Modal>
    </>
  )
}

function HandoffForm({
  ticket,
  ribbon,
  onDone,
  onHandedOff,
}: {
  ticket: Ticket
  ribbon: Ribbon | undefined
  onDone: () => void
  onHandedOff: () => void
}) {
  const initial = React.useMemo(() => emptyHandoffForm(ribbon), [ribbon])
  const {
    control,
    register,
    handleSubmit,
    watch,
    formState: { errors },
  } = useForm<HandoffFormValues>({
    resolver: zodResolver(handoffSchema),
    defaultValues: initial,
    mode: 'onBlur',
  })

  const toStageCode = watch('toStageCode')

  // C-014's precedent for a client with no per-ticket template to read: the
  // wire `Ticket` carries no `workflowTemplateId`, so a stage typed outside
  // the ribbon's own segments (a legacy ticket, or one C-051 has not rendered
  // for) is resolved by deduplicating across every template instead of
  // guessed at. `nextStageCode`/`stageOwnerRole` in `handoffForm.ts` try the
  // ribbon first and only reach this on a miss.
  const { data: templatesData } = useListWorkflowTemplates()

  const ownerRole = React.useMemo(
    () => stageOwnerRole(toStageCode, ribbon, templatesData?.data),
    [toStageCode, ribbon, templatesData],
  )

  // BUG-003 · the same two sources `stageOwnerRole` reads, asked the other
  // question: not "who owns this stage" but "which stages are there to pick".
  const stageOptions = React.useMemo(
    () => handoffStageOptions(ribbon, templatesData?.data),
    [ribbon, templatesData],
  )

  // A ticket cannot be handed to the stage it is already standing in. Listed
  // and greyed rather than dropped, on `SearchableDropdown`'s own B-028 rule
  // — a stage that silently vanishes from a ribbon-ordered list reads as lost
  // data, whereas one greyed with a reason says what is going on.
  const currentStageCode =
    ribbon?.segments?.find((segment) => segment.state === 'CURRENT')?.stageCode ??
    ribbon?.currentStageCode ??
    ticket.currentStageCode ??
    undefined

  // "Assign-to filtered to the receiving role's project members" — §4A. The
  // roster read (`GET /projects/{projectId}/members`) carries `openTicketCount`,
  // which `useListUsers` does not, and showing it is the entire point of this
  // control (contract: "so work is not handed to whoever is already
  // drowning") — ReopenDialog's assignee field uses `useListUsers` because it
  // has no load to show; this one cannot reuse that call for the same reason
  // it cannot reuse EffortLogUserRefs across features.
  const projectId = ticket.project?.id
  const { data: membersData } = useListProjectMembers(projectId ?? 0, { query: { enabled: projectId != null } })
  const candidates = React.useMemo(() => {
    const members = membersData?.data ?? []
    if (!ownerRole) return members
    return members.filter((m) => (m.projectRole ?? m.role) === ownerRole)
  }, [membersData, ownerRole])

  const attachments = useTicketAttachments({ ticketId: ticket.ticketId })

  const idempotencyKey = React.useRef(newIdempotencyKey())
  const handoff = useHandoffMutation()

  function submit(values: HandoffFormValues) {
    handoff.mutate(
      {
        ticketId: ticket.ticketId,
        data: toHandoffRequest(values, attachments.attachmentIds),
        idempotencyKey: idempotencyKey.current,
      },
      {
        onSuccess: () => {
          toast({
            title: `${ticket.ticketId} handed off`,
            description: `Moved to ${values.toStageCode}.`,
          })
          onHandedOff()
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
        <ModalTitle>Hand off {ticket.ticketId}</ModalTitle>
      </ModalHeader>

      <FormField
        id={`${DIALOG_ID}-to-stage`}
        label="Next stage"
        required
        error={errors.toStageCode?.message}
        hint={
          ribbon
            ? 'Pre-filled from the ribbon — change it to send the ticket somewhere else.'
            : 'Every stage defined across the workflow templates, since this ticket has no ribbon yet.'
        }
      >
        {(aria) => (
          <Controller
            control={control}
            name="toStageCode"
            render={({ field }) => (
              <SearchableDropdown<HandoffStageOption>
                {...aria}
                options={stageOptions}
                value={stageOptions.find((s) => s.stageCode === field.value) ?? null}
                onChange={(stage) => field.onChange(stage.stageCode)}
                getKey={(stage) => stage.stageCode}
                // Code alongside the label, not instead of it: the code is what
                // reaches `ticket_stage_transitions` and what every other
                // screen shows, so hiding it would make the two hard to line up.
                getLabel={(stage) =>
                  stage.displayName === stage.stageCode
                    ? stage.stageCode
                    : `${stage.displayName} · ${stage.stageCode}`
                }
                getSearchable={(stage) => [stage.stageCode, stage.ownerRole ?? '']}
                getOptionDisabled={(stage) =>
                  stage.stageCode === currentStageCode ? 'The ticket is already here' : null
                }
                placeholder="Pick the stage this ticket is moving to…"
                emptyText="No stages available — the workflow templates have not loaded"
                disabled={handoff.isPending}
              />
            )}
          />
        )}
      </FormField>

      <FormField
        id={`${DIALOG_ID}-assignee`}
        label="Assign to"
        required
        error={errors.toUserId?.message}
        hint={ownerRole ? `Filtered to ${ownerRole} on this project.` : 'Showing every member of this project.'}
      >
        {(aria) => (
          <Controller
            control={control}
            name="toUserId"
            render={({ field }) => (
              <SearchableDropdown<ProjectMember>
                {...aria}
                options={candidates}
                value={candidates.find((m) => m.userId === field.value) ?? null}
                onChange={(member) => field.onChange(member.userId)}
                getKey={(member) => String(member.userId)}
                // The open-load figure inline in the label — "so work is not
                // handed to whoever is already drowning" is the field's own
                // reason to exist, and `SearchableDropdown` has no per-row
                // render slot to put it in separately (additive-only; adding
                // one is a shared-component change for whichever task next
                // needs it, not this one).
                getLabel={(member) =>
                  `${member.displayName} · ${member.openTicketCount ?? 0} open`
                }
                getSearchable={(member) => [member.email ?? '', member.role ?? '']}
                placeholder={projectId == null ? 'No project on this ticket' : 'Search project members…'}
                emptyText={ownerRole ? `No ${ownerRole} on this project` : 'No active members on this project'}
                disabled={handoff.isPending || projectId == null}
              />
            )}
          />
        )}
      </FormField>

      <FormField
        id={`${DIALOG_ID}-effort`}
        label="Effort in this stage (hrs)"
        required
        error={errors.effortHours?.message}
        hint="Confirm the hours spent before handing off. Enter 0 if none were logged here."
      >
        {(aria) => (
          <Input
            {...aria}
            {...register('effortHours')}
            inputMode="decimal"
            placeholder="4"
            disabled={handoff.isPending}
          />
        )}
      </FormField>

      <FormField id={`${DIALOG_ID}-note`} label="Handoff note" hint="Optional — visible to whoever receives this ticket.">
        {(aria) => (
          <textarea
            {...aria}
            {...register('note')}
            rows={3}
            maxLength={4000}
            disabled={handoff.isPending}
            placeholder="Anything the next owner should know…"
            className="w-full rounded-control border border-border bg-surface px-3 py-2 text-sm text-content placeholder:text-content-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary aria-[invalid=true]:border-danger"
          />
        )}
      </FormField>

      <div>
        <span className="mb-1 block text-caption font-medium text-content-muted">Attachments</span>
        <AttachmentPicker
          items={attachments.items}
          onAdd={attachments.add}
          onRemove={attachments.remove}
          compact
        />
      </div>

      {/*
        The server's refusal, shown as it arrived — same reason `ReopenDialog`
        shows one here: the golden rule (422) and an unknown stage (400) both
        name something this form can let the user act on without losing what
        they typed.
      */}
      {handoff.isError && (
        <p role="alert" className="text-caption text-danger-text">
          {handoff.error instanceof ApiError
            ? (handoff.error.problem.detail ?? handoff.error.message)
            : 'This ticket could not be handed off. Please try again.'}
        </p>
      )}

      <ModalFooter>
        <Button type="button" variant="secondary" size="sm" onClick={onDone} disabled={handoff.isPending}>
          Cancel
        </Button>
        <Button type="submit" size="sm" disabled={handoff.isPending}>
          {handoff.isPending ? 'Handing off…' : 'Hand off'}
        </Button>
      </ModalFooter>
    </form>
  )
}
