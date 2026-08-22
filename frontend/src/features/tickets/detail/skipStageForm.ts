import { z } from 'zod'

import {
  skipStageBodyReasonMax,
  skipStageBodyReasonMin,
  skipStageBodyToStageCodeMax,
} from '@/api/generated/zod/ribbon/ribbon.zod'
import type { SkipStageBody } from '@/api/generated/model/skipStageBody'

/**
 * C-047 · Skip Stage Dialog — form state, validation and the mapping onto
 * the wire. `reason` is the contract's only required field; `toStageCode`
 * is optional and left blank by default, exactly as `SkipService.skip`
 * itself treats a blank one — the server defaults it to the template's next
 * stage, on `TransitionService.nextStageAfter`'s own precedent, so this form
 * does not try to guess it client-side the way `handoffForm.ts` does (a
 * handoff has no server-side default; a skip does).
 */
export interface SkipStageFormValues {
  reason: string
  toStageCode: string
}

export function emptySkipStageForm(): SkipStageFormValues {
  return { reason: '', toStageCode: '' }
}

export const skipStageSchema = z.object({
  reason: z
    .string()
    .trim()
    .min(skipStageBodyReasonMin, `Explain why this stage is being skipped — at least ${skipStageBodyReasonMin} characters`)
    .max(skipStageBodyReasonMax, `Keep the reason under ${skipStageBodyReasonMax} characters`),
  toStageCode: z
    .string()
    .trim()
    .max(skipStageBodyToStageCodeMax, `Stage codes are ${skipStageBodyToStageCodeMax} characters or fewer`),
})

/** Form state → `POST /tickets/{id}/skip-stage` body. A blank `toStageCode`
 * is omitted rather than sent as `""` — the server's own default (the
 * template's next stage) applies only when the field is absent. */
export function toSkipStageRequest(values: SkipStageFormValues): SkipStageBody {
  const toStageCode = values.toStageCode.trim()
  return {
    reason: values.reason.trim(),
    ...(toStageCode ? { toStageCode } : {}),
  }
}
