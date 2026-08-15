import * as React from 'react'
import { useForm, Controller } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Zap } from 'lucide-react'

import { newIdempotencyKey, ApiError } from '@/api/http'
import type { Ticket } from '@/api/generated/model/ticket'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { toast } from '@/components/ui/use-toast'
import {
  SlideOver,
  SlideOverContent,
  SlideOverHeader,
  SlideOverTitle,
  SlideOverBody,
  SlideOverFooter,
  SlideOverTrigger,
} from '@/components/ui/slide-over'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { cn } from '@/lib/utils'

import { AttachmentPicker } from '@/components/ui/attachment-picker'

import { FormField } from '../create/FormField'
import { useAttachmentLimits } from '../attachments/attachmentLimits'
import { useTicketAttachments } from '../attachments/useTicketAttachments'
import { STATUS_LABEL } from '../list/columns'
import { titleCase } from '../stageDisplay'
import { useQuickUpdateMutation } from './useQuickUpdateMutation'
import { emptyQuickUpdateForm, quickUpdateSchema, toQuickUpdateRequest, type QuickUpdateFormValues } from './quickUpdateForm'

const STATUS_OPTIONS = Object.keys(STATUS_LABEL) as (keyof typeof STATUS_LABEL)[]

/**
 * S-21 Quick Update — the ⚡ trigger and its slide-over, bundled as one
 * component so any row (My Tasks, the ticket list, eventually the detail
 * page) drops in one component rather than wiring open state itself.
 */
export function QuickUpdateTrigger({
  ticket,
  triggerClassName,
  compact,
}: {
  ticket: Ticket
  triggerClassName?: string
  /** Icon-only, for tight spaces like a Kanban card. Same action, same panel — just no label. */
  compact?: boolean
}) {
  const [open, setOpen] = React.useState(false)

  return (
    <SlideOver open={open} onOpenChange={setOpen}>
      <SlideOverTrigger asChild>
        <Button
          size="sm"
          onClick={(e) => e.stopPropagation()}
          className={cn('shrink-0', triggerClassName)}
          aria-label={compact ? `Quick update ${ticket.ticketId}` : undefined}
        >
          <Zap className="h-3.5 w-3.5" />
          {!compact && 'Quick update'}
        </Button>
      </SlideOverTrigger>
      <SlideOverContent onClick={(e) => e.stopPropagation()}>
        {open && <QuickUpdateForm ticket={ticket} onDone={() => setOpen(false)} />}
      </SlideOverContent>
    </SlideOver>
  )
}

function QuickUpdateForm({ ticket, onDone }: { ticket: Ticket; onDone: () => void }) {
  const initial = React.useMemo(() => emptyQuickUpdateForm(ticket), [ticket])
  const {
    control,
    register,
    handleSubmit,
    watch,
    formState: { errors },
  } = useForm<QuickUpdateFormValues>({
    resolver: zodResolver(quickUpdateSchema),
    defaultValues: initial,
    mode: 'onBlur',
  })

  const pctComplete = watch('pctComplete')
  const revisedEta = watch('revisedEta')

  // Minted once per open panel, not inside the mutation — a retried request
  // after a timeout must be recognised as the same request, not a second
  // effort entry. See `useQuickUpdateMutation`'s own doc comment.
  const idempotencyKey = React.useRef(newIdempotencyKey())
  const quickUpdate = useQuickUpdateMutation()

  // Immediate mode — the ticket exists, so a file goes up the moment it is
  // picked and its ID is on hand by the time Update is pressed. No `existing`:
  // this panel holds a `Ticket`, and attachments only ride on the aggregated
  // `/full` payload the detail page fetches. §4B.4's flag defaults internal.
  const attachments = useTicketAttachments({ ticketId: ticket.ticketId })
  // C-027 · §4B.4's caps as configured, not as hard-coded. Shares one cached
  // fetch with the detail page behind this slide-over.
  const attachmentLimits = useAttachmentLimits()

  async function onSubmit(values: QuickUpdateFormValues) {
    try {
      await quickUpdate.mutateAsync({
        ticketId: ticket.ticketId,
        data: toQuickUpdateRequest(values, initial, attachments.attachmentIds),
        idempotencyKey: idempotencyKey.current,
      })
      toast({ variant: 'success', title: `${ticket.ticketId} updated` })
      onDone()
    } catch (error) {
      toast({
        variant: 'danger',
        title: 'The update did not go through',
        description: error instanceof ApiError ? error.problem.detail ?? error.message : 'Try again in a moment.',
      })
    }
  }

  return (
    <form onSubmit={(e) => { e.preventDefault(); void handleSubmit(onSubmit)() }} className="flex h-full flex-col">
      <SlideOverHeader>
        <span className="text-caption font-medium uppercase tracking-wide text-content-muted">Quick update</span>
        <SlideOverTitle>{ticket.ticketId}</SlideOverTitle>
        <p className="truncate text-sm text-content-muted" title={ticket.title}>
          {ticket.title}
        </p>
      </SlideOverHeader>

      <SlideOverBody className="space-y-4">
        <FormField id="qu-status" label="Status">
          {(aria) => (
            <Controller
              control={control}
              name="status"
              render={({ field }) => (
                <Select value={field.value} onValueChange={field.onChange}>
                  <SelectTrigger {...aria}>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {STATUS_OPTIONS.map((code) => (
                      <SelectItem key={code} value={code}>
                        {STATUS_LABEL[code]}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              )}
            />
          )}
        </FormField>

        <div className="flex gap-3">
          <FormField id="qu-effort-hours" label="Log effort (hrs)" error={errors.effortHours?.message} className="flex-1">
            {(aria) => <Input {...aria} {...register('effortHours')} inputMode="decimal" placeholder="2.5" />}
          </FormField>
          <FormField id="qu-effort-date" label="On date" className="flex-1">
            {(aria) => <Input {...aria} {...register('effortDate')} type="date" />}
          </FormField>
        </div>
        {ticket.currentStageCode && (
          <p className="rounded-control bg-primary-soft px-3 py-2.5 text-caption text-primary">
            Effort is stamped automatically to <b>{titleCase(ticket.currentStageCode)}</b>
            {ticket.iterationNo ? ` · iteration ${ticket.iterationNo}` : ''} — the stage this ticket is in right now.
          </p>
        )}

        <FormField id="qu-work-note" label="Work note" error={errors.workNote?.message}>
          {(aria) => (
            <textarea
              {...aria}
              {...register('workNote')}
              rows={3}
              placeholder="What did you do?"
              className="w-full rounded-control border border-border bg-surface px-3 py-2 text-sm text-content placeholder:text-content-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-1"
            />
          )}
        </FormField>

        {/*
          Compact, because the slide-over is narrow and a 96px drop zone would
          push % Complete and the ETA pair below the fold. Drag-and-drop still
          works over the whole control — it just stops advertising itself.
        */}
        <FormField id="qu-attachments" label="Attachments">
          {(aria) => (
            <AttachmentPicker
              {...aria}
              compact
              items={attachments.items}
              onAdd={attachments.add}
              onRemove={attachments.remove}
              limits={attachmentLimits}
              disabled={quickUpdate.isPending}
            />
          )}
        </FormField>

        <FormField id="qu-pct-complete" label={`% Complete — ${pctComplete}%`}>
          {(aria) => (
            <Controller
              control={control}
              name="pctComplete"
              render={({ field }) => (
                <input
                  {...aria}
                  type="range"
                  min={0}
                  max={100}
                  value={field.value}
                  onChange={(e) => field.onChange(Number(e.target.value))}
                  className="w-full accent-primary"
                  aria-valuetext={`${field.value}%`}
                />
              )}
            />
          )}
        </FormField>

        <div className="flex gap-3">
          <FormField
            id="qu-revised-eta"
            label="Revised ETA"
            className="flex-1"
          >
            {(aria) => <Input {...aria} {...register('revisedEta')} type="date" />}
          </FormField>
          <FormField
            id="qu-revised-eta-reason"
            label="Reason"
            required={!!revisedEta}
            error={errors.revisedEtaReason?.message}
            className="flex-1"
          >
            {(aria) => (
              <Input {...aria} {...register('revisedEtaReason')} placeholder="Why is the date moving?" disabled={!revisedEta} />
            )}
          </FormField>
        </div>

        <p className="text-caption text-content-muted">
          Not editable here: ticket ID, reported by, assigned by, date reported, cycle history, the ribbon, prior
          effort logs, level and project. Those are append-only or a PM/Admin action elsewhere.
        </p>
      </SlideOverBody>

      <SlideOverFooter>
        <Button type="button" variant="ghost" onClick={onDone} disabled={quickUpdate.isPending}>
          Cancel
        </Button>
        {/*
          Blocked while a file is in flight. Submitting mid-upload would send an
          `attachmentIds` missing the very file the user attached — the update
          would succeed and the attachment would silently not be on it.
        */}
        <Button type="submit" disabled={quickUpdate.isPending || attachments.isUploading}>
          {quickUpdate.isPending ? 'Updating…' : attachments.isUploading ? 'Uploading…' : 'Update ✓'}
        </Button>
      </SlideOverFooter>
    </form>
  )
}
