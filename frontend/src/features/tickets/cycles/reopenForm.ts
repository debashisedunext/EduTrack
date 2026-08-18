import { z } from 'zod'

import {
  reopenTicketBodyReasonMax,
  reopenTicketBodyReasonMin,
  reopenTicketBodyRestartStageCodeDefault,
} from '@/api/generated/zod/tickets/tickets.zod'
import type { ReopenRequest } from '@/api/generated/model/reopenRequest'
import type { Ticket } from '@/api/generated/model/ticket'

/**
 * S-22 Reopen Dialog — form state, validation and the mapping onto the wire.
 *
 * Only `reason` is required, on the contract and on `ReopenService` — every
 * other field falls back to something the ticket already knows (the closing
 * cycle's assignee, a planned close date recomputed from the SLA ladder, the
 * template's `TRIAGE` default). The blueprint's own S-22 wireframe marks the
 * planned close date with an asterisk, but `ReopenService`'s javadoc explains
 * why the server does not enforce that: refusing a reopen over a field the
 * caller never sent would fail the action on a value the dialog was going to
 * supply anyway. This form follows the server's rule rather than the
 * wireframe's punctuation.
 */
export interface ReopenFormValues {
  reason: string
  restartStageCode: string
  assigneeId: number | null
  plannedCloseDate: string
  estimatedHrs: string
}

export function emptyReopenForm(ticket: Ticket): ReopenFormValues {
  return {
    reason: '',
    restartStageCode: reopenTicketBodyRestartStageCodeDefault,
    // "New assignee (defaults to previous)" — previous is whoever holds the
    // ticket right now, since nothing has reassigned it since the cycle being
    // reopened closed.
    assigneeId: ticket.assignee?.id ?? null,
    plannedCloseDate: '',
    estimatedHrs: '',
  }
}

const HOURS = /^\d+(\.\d{1,2})?$/

export const reopenSchema = z.object({
  reason: z
    .string()
    .trim()
    .min(reopenTicketBodyReasonMin, `Say why this ticket is reopening — at least ${reopenTicketBodyReasonMin} characters`)
    .max(reopenTicketBodyReasonMax, `Keep the reason under ${reopenTicketBodyReasonMax} characters`),
  restartStageCode: z
    .string()
    .trim()
    .min(1, 'Pick a stage for the new cycle to open in')
    .max(20, 'Stage codes are 20 characters or fewer'),
  assigneeId: z.number().nullable(),
  plannedCloseDate: z.string(),
  estimatedHrs: z
    .string()
    .trim()
    .refine((s) => s === '' || HOURS.test(s), 'Enter hours as a decimal — 8 or 7.5')
    .refine((s) => s === '' || Number(s) > 0, 'The revised estimate must be greater than 0'),
})

/**
 * Form state → `POST /tickets/{id}/reopen` body.
 *
 * Send-if-touched for every optional field, same rule `toQuickUpdateRequest`
 * follows: an assignee left at its pre-filled default is still sent (the
 * field always carries a real selection, never a placeholder), while a blank
 * planned close date or estimate must not overwrite the server's own default
 * with nothing.
 */
export function toReopenRequest(values: ReopenFormValues): ReopenRequest {
  const restartStageCode = values.restartStageCode.trim()
  const estimatedHrs = values.estimatedHrs.trim()

  return {
    reason: values.reason.trim(),
    ...(restartStageCode ? { restartStageCode } : {}),
    ...(values.assigneeId != null ? { assigneeId: values.assigneeId } : {}),
    ...(values.plannedCloseDate ? { plannedCloseDate: new Date(values.plannedCloseDate).toISOString() } : {}),
    ...(estimatedHrs ? { estimatedHrs: Number(estimatedHrs) } : {}),
  }
}
