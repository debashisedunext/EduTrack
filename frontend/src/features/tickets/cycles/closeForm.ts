import { z } from 'zod'

import type { CloseRequest } from '@/api/generated/model/closeRequest'

/**
 * C-040 · S-23's close dialog — form values, validation and the mapping onto
 * {@link CloseRequest}.
 *
 * No generated Zod schema exists for `CloseRequest` — orval's Zod plugin
 * never emitted one, the same gap C-039's `reopenForm.ts` found for
 * `ReopenRequest` — so this is hand-authored against the contract's own
 * bounds (`resolutionSummary` 3–4000, `rootCauseCategory` ≤100,
 * `finalEffortHours` positive), which is also exactly what
 * `CloseDtos.CloseRequest`'s Bean Validation enforces server-side. Kept in
 * one place so the two cannot drift silently past each other.
 */
export const closeFormSchema = z.object({
  resolutionSummary: z
    .string()
    .trim()
    .min(3, 'Say at least a few words about how this was resolved.')
    .max(4000),
  rootCauseCategory: z.string().trim().max(100).optional(),
  /** ISO datetime-local string (`YYYY-MM-DDTHH:mm`), or blank for "now". */
  actualCloseDate: z.string().optional(),
  /**
   * A string in the form, not a number — an `<input type="number">` reports
   * blank as `''` and a bare `z.number()` would reject that before the
   * "omitted" branch ever ran. Parsed to a number only in {@link toCloseRequest}.
   */
  finalEffortHours: z.string().optional(),
  requestClientVerification: z.boolean(),
})

export type CloseFormValues = z.infer<typeof closeFormSchema>

export const CLOSE_FORM_DEFAULTS: CloseFormValues = {
  resolutionSummary: '',
  rootCauseCategory: '',
  actualCloseDate: '',
  finalEffortHours: '',
  requestClientVerification: false,
}

/**
 * Maps validated form values onto the wire shape.
 *
 * Blank optionals are **omitted**, never sent as `''` or `0` — the same rule
 * `CreateTicketPage`'s mapper follows and for the identical reason:
 * `finalEffortHours: 0` is a genuine claim of zero confirmed hours, a
 * different fact from "not confirmed". `actualCloseDate` omitted is the
 * contract's own "defaults to now", so an empty picker is not a client-side
 * guess at the current instant — the server's clock is the one that matters
 * for a fact this permanent.
 */
export function toCloseRequest(values: CloseFormValues): CloseRequest {
  const rootCauseCategory = values.rootCauseCategory?.trim()
  const finalEffortHours = values.finalEffortHours?.trim()

  return {
    resolutionSummary: values.resolutionSummary.trim(),
    ...(rootCauseCategory ? { rootCauseCategory } : {}),
    ...(values.actualCloseDate
      ? { actualCloseDate: new Date(values.actualCloseDate).toISOString() }
      : {}),
    ...(finalEffortHours ? { finalEffortHours: Number(finalEffortHours) } : {}),
    ...(values.requestClientVerification ? { requestClientVerification: true } : {}),
  }
}
