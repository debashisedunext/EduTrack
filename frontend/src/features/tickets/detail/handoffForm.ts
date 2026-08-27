import { z } from 'zod'

import {
  handoffTicketBodyEffortHoursMin,
  handoffTicketBodyNoteMax,
} from '@/api/generated/zod/ribbon/ribbon.zod'
import type { HandoffRequest } from '@/api/generated/model/handoffRequest'
import type { Ribbon } from '@/api/generated/model/ribbon'
import type { RoleCode } from '@/api/generated/model/roleCode'
import type { WorkflowTemplate } from '@/api/generated/model/workflowTemplate'

/**
 * S-29 Handoff Dialog — C-044 — form state, validation and the mapping onto
 * the wire.
 *
 * `toStageCode` and `toUserId` are the contract's only required fields, but
 * `effortHours` is business-mandatory too — G-1's default is blocking, and
 * {@code HandoffService} on the server answers 400 for a missing one. This
 * form enforces the same rule client-side so the dialog names the field
 * before the round trip, on `ticketForm.ts`'s own `requiredId` precedent for
 * "the contract calls it optional, the product does not".
 */
export interface HandoffFormValues {
  toStageCode: string
  toUserId: number | null
  note: string
  /** String, not number — `ticketForm.ts`'s `estimatedHrs` field follows the
   * same shape, so "4", "4.5" and "0" are all typed exactly as a user would
   * type them, and an empty box is distinguishable from a real zero. */
  effortHours: string
}

export function emptyHandoffForm(ribbon: Ribbon | undefined): HandoffFormValues {
  return {
    toStageCode: nextStageCode(ribbon) ?? '',
    toUserId: null,
    note: '',
    effortHours: '',
  }
}

/**
 * The stage immediately after the ribbon's `CURRENT` segment, left to right —
 * the same "next stage after the one being left" `TransitionService.nextStageAfter`
 * resolves server-side, read here from data the page already has.
 *
 * Deliberately **not** `GET /masters/workflow-templates`' deduplicated,
 * cross-template stage list — `TicketListPage`'s own `stageOptions` comment
 * explains why that shape exists (the wire `Ticket` carries no
 * `workflowTemplateId` for a client to resolve one template from) and it
 * answers a different question: "which stage codes exist anywhere," not
 * "which stage does *this* ticket move to next." The ribbon already answers
 * the second question correctly, for this ticket, when it is available.
 *
 * `undefined` — never a guess — when there is no ribbon (C-051/C-053 have
 * not landed on the real backend yet, so `detail.ribbon` is `undefined`
 * against it today) or no `CURRENT` segment. The dialog falls back to asking
 * the user to type the stage rather than silently picking one.
 */
export function nextStageCode(ribbon: Ribbon | undefined): string | undefined {
  const segments = ribbon?.segments
  if (!segments || segments.length === 0) return undefined
  const current = segments.find((s) => s.state === 'CURRENT')
  if (!current || current.sequence == null) return undefined
  const after = segments
    .filter((s): s is typeof s & { sequence: number; stageCode: string } => s.sequence != null && s.sequence > current.sequence! && s.stageCode != null)
    .sort((a, b) => a.sequence - b.sequence)
  return after[0]?.stageCode
}

/**
 * The receiving role for a stage code — "assign-to filtered to the receiving
 * role's project members" needs this to know which role to filter by.
 *
 * Tries the ribbon first: `RibbonSegment.ownerRole` is already this ticket's
 * own template's answer, resolved server-side, and correct even when several
 * templates define the same `stageCode` with different owners. Falls back to
 * deduplicating across every template — `TicketListPage`'s `stageOptions`
 * precedent — only for a stage typed outside the ribbon's own segments (no
 * ribbon at all, since `TicketDetailService` does not populate one yet
 * against the real backend, or a stage a user typed that this cycle has not
 * reached). `undefined` when neither source has an answer, which the caller
 * treats as "show every project member" rather than an empty, unexplained list.
 */
export function stageOwnerRole(
  stageCode: string,
  ribbon: Ribbon | undefined,
  templates: WorkflowTemplate[] | undefined,
): RoleCode | undefined {
  const code = stageCode.trim()
  if (!code) return undefined

  const fromRibbon = ribbon?.segments?.find((s) => s.stageCode === code)?.ownerRole
  if (fromRibbon) return fromRibbon

  for (const template of templates ?? []) {
    const stage = template.stages?.find((s) => s.stageCode === code)
    if (stage?.ownerRole) return stage.ownerRole
  }
  return undefined
}

/** One row of the **Next stage** dropdown. */
export interface HandoffStageOption {
  stageCode: string
  /** The template's own label where there is one, else the code itself. */
  displayName: string
  ownerRole?: RoleCode
  sequence: number
}

/**
 * The stages offered by the **Next stage** dropdown — every stage on this
 * ticket's own workflow except the one it is already standing in, sorted by
 * sequence.
 *
 * Ribbon first, exactly as `nextStageCode`/`stageOwnerRole` do: `ribbon.segments`
 * is *this ticket's own* template resolved server-side. Falls back to
 * deduplicating across every template — `TicketListPage`'s own `stageOptions`
 * precedent, including its `CLOSED` exclusion, since closing a ticket is
 * `CloseDialog`'s own dedicated flow, not a stage a handoff hands to — only
 * when there is no ribbon, so every non-deprecated, non-`CLOSED` stage is
 * offered rather than none.
 */
export function handoffStageOptions(
  ribbon: Ribbon | undefined,
  templates: WorkflowTemplate[] | undefined,
): HandoffStageOption[] {
  const segments = ribbon?.segments
  if (segments && segments.length > 0) {
    return segments
      .filter(
        (s): s is typeof s & { sequence: number; stageCode: string } =>
          s.state !== 'CURRENT' && s.sequence != null && s.stageCode != null,
      )
      .map((s) => ({
        stageCode: s.stageCode,
        displayName: s.displayName ?? s.stageCode,
        ownerRole: s.ownerRole,
        sequence: s.sequence,
      }))
      .sort((a, b) => a.sequence - b.sequence)
  }

  const byCode = new Map<string, HandoffStageOption>()
  for (const template of templates ?? []) {
    for (const stage of template.stages ?? []) {
      if (stage.stageCode === 'CLOSED' || stage.isDeprecated) continue
      if (!stage.stageCode || byCode.has(stage.stageCode)) continue
      byCode.set(stage.stageCode, {
        stageCode: stage.stageCode,
        displayName: stage.displayName ?? stage.stageCode,
        ownerRole: stage.ownerRole,
        sequence: stage.sequence ?? 0,
      })
    }
  }
  // Codes shared across templates can disagree on sequence, so the code breaks
  // the tie and the list is at least stable between renders.
  return [...byCode.values()].sort(
    (a, b) => a.sequence - b.sequence || a.stageCode.localeCompare(b.stageCode),
  )
}

const requiredId = (message: string) =>
  z
    .number()
    .int()
    .positive()
    .nullable()
    .refine((v) => v !== null, { message })

// Hours as the user types them: "4", "4.5", "0" — `ticketForm.ts`'s own HOURS
// regexp, restated here rather than imported, since a shared regexp across two
// feature-local form files is exactly the coupling `EffortLogUserRefs`'s
// javadoc argues against for a shared type.
const HOURS = /^\d+(\.\d{1,2})?$/

export const handoffSchema = z.object({
  toStageCode: z
    .string()
    .trim()
    .min(1, 'Pick the stage this ticket is moving to')
    .max(20, 'Stage codes are 20 characters or fewer'),
  toUserId: requiredId('Pick who is receiving this ticket'),
  note: z
    .string()
    .max(handoffTicketBodyNoteMax, `Keep the note under ${handoffTicketBodyNoteMax} characters`),
  effortHours: z
    .string()
    .trim()
    .min(1, 'Confirm the hours you spent in this stage — enter 0 if none')
    .refine((s) => HOURS.test(s), 'Enter hours as a decimal — 8 or 7.5')
    .refine((s) => Number(s) >= handoffTicketBodyEffortHoursMin, 'Hours cannot be negative'),
})

/**
 * Form state → `POST /tickets/{id}/handoff` body.
 *
 * `attachmentIds` is threaded in separately rather than held on
 * `HandoffFormValues`, because it is not this form's state — `useTicketAttachments`
 * owns it, on `useTicketAttachments`'s own javadoc naming `HandoffRequest.attachmentIds`
 * as one of its three consumers.
 */
export function toHandoffRequest(values: HandoffFormValues, attachmentIds: number[]): HandoffRequest {
  const note = values.note.trim()
  return {
    toStageCode: values.toStageCode.trim(),
    toUserId: values.toUserId as number,
    ...(note ? { note } : {}),
    effortHours: Number(values.effortHours),
    ...(attachmentIds.length ? { attachmentIds } : {}),
  }
}
