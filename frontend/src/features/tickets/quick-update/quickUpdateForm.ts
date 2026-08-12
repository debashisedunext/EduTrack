import { z } from 'zod'
import { format } from 'date-fns'
import {
  quickUpdateTicketBodyEffortHoursMax,
  quickUpdateTicketBodyEffortHoursMin,
  quickUpdateTicketBodyPctCompleteMax,
  quickUpdateTicketBodyPctCompleteMin,
  quickUpdateTicketBodyRevisedEtaReasonMax,
  quickUpdateTicketBodyWorkNoteMax,
} from '@/api/generated/zod/tickets/tickets.zod'
import { StatusCode } from '@/api/generated/model/statusCode'
import type { QuickUpdateRequest } from '@/api/generated/model/quickUpdateRequest'
import type { Ticket } from '@/api/generated/model/ticket'

/**
 * S-21 Quick Update — form state, validation and the mapping onto the wire.
 *
 * Deliberately narrow, same as the contract's own doc comment on
 * `QuickUpdateRequest`: no ticket ID, reported by, assigned by, date
 * reported, cycle history, ribbon, prior effort logs, project, or level
 * (unless PM — C-037's PM exception has no field to hang it on yet, so it
 * waits for that).
 *
 * 📎 Attach **is** wired now — C-023 landed the shared picker and this panel is
 * one of §4B.4's listed surfaces. It is not a form *value*, though: files upload
 * the moment they are chosen (the ticket already exists here, unlike the create
 * form), so what reaches the wire is the resulting server IDs, passed into
 * `toQuickUpdateRequest` rather than validated by the schema. Nothing to
 * validate — the picker refuses anything outside §4B.4 before it ever uploads.
 */
export interface QuickUpdateFormValues {
  status: StatusCode
  effortHours: string
  effortDate: string
  workNote: string
  pctComplete: number
  revisedEta: string
  revisedEtaReason: string
}

export function emptyQuickUpdateForm(ticket: Ticket): QuickUpdateFormValues {
  return {
    status: ticket.status,
    effortHours: '',
    effortDate: format(new Date(), 'yyyy-MM-dd'),
    workNote: '',
    pctComplete: ticket.pctComplete ?? 0,
    revisedEta: '',
    revisedEtaReason: '',
  }
}

const HOURS = /^\d+(\.\d{1,2})?$/

export const quickUpdateSchema = z
  .object({
    status: z.nativeEnum(StatusCode),
    effortHours: z
      .string()
      .trim()
      .refine((s) => s === '' || HOURS.test(s), 'Enter hours as a decimal — 2, 2.5 or 0.25')
      .refine(
        (s) => s === '' || (Number(s) > quickUpdateTicketBodyEffortHoursMin && Number(s) <= quickUpdateTicketBodyEffortHoursMax),
        `Effort must be between 0 and ${quickUpdateTicketBodyEffortHoursMax} hours`,
      ),
    effortDate: z.string(),
    workNote: z.string().max(quickUpdateTicketBodyWorkNoteMax, `Keep the work note under ${quickUpdateTicketBodyWorkNoteMax} characters`),
    pctComplete: z.number().min(quickUpdateTicketBodyPctCompleteMin).max(quickUpdateTicketBodyPctCompleteMax),
    revisedEta: z.string(),
    revisedEtaReason: z.string().max(quickUpdateTicketBodyRevisedEtaReasonMax),
  })
  .superRefine((values, ctx) => {
    // Blueprint §7's S-21 wireframe marks the reason mandatory the moment a
    // revised ETA is entered — "Revised ETA [ 14 Aug 2026 ] (reason*)".
    if (values.revisedEta && !values.revisedEtaReason.trim()) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['revisedEtaReason'],
        message: 'Say why the date is moving — required once you change it',
      })
    }
  })

/**
 * Form state → `POST /tickets/{id}/quick-update` body.
 *
 * Every field is send-if-touched: an unfilled effort field must not log a
 * zero-hour entry, an unmoved % complete slider must not overwrite the
 * ticket's actual progress with whatever the slider happened to render at
 * (its default *is* the current value, so sending it unconditionally would
 * be harmless today — but only by accident of how the default was chosen).
 * Status is the one exception, sent every time: the field is always a real
 * selection, never blank, because the form seeds it from the ticket's
 * current status rather than a placeholder.
 */
export function toQuickUpdateRequest(
  values: QuickUpdateFormValues,
  initial: QuickUpdateFormValues,
  /**
   * IDs of files already uploaded through the picker. Optional and omitted when
   * empty, following the same send-if-touched rule as every field above — an
   * empty array is a claim ("no attachments"), absence is silence.
   */
  attachmentIds: readonly number[] = [],
): QuickUpdateRequest {
  const workNote = values.workNote.trim()
  const effortHours = values.effortHours.trim()

  return {
    status: values.status,
    ...(effortHours ? { effortHours: Number(effortHours), effortDate: values.effortDate } : {}),
    ...(workNote ? { workNote } : {}),
    ...(values.pctComplete !== initial.pctComplete ? { pctComplete: values.pctComplete } : {}),
    ...(values.revisedEta
      ? { revisedEta: new Date(values.revisedEta).toISOString(), revisedEtaReason: values.revisedEtaReason.trim() }
      : {}),
    ...(attachmentIds.length > 0 ? { attachmentIds: [...attachmentIds] } : {}),
  }
}
