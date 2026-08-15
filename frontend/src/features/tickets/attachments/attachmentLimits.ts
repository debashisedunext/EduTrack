import { useGetAttachmentLimits } from '@/api/generated/attachments/attachments'
import type { AttachmentLimits as AttachmentLimitsDto } from '@/api/generated/model'
import { ATTACHMENT_DEFAULT_LIMITS, type AttachmentLimits } from '@/components/ui/attachments'

/**
 * C-027 · the caps the server is actually enforcing, blueprint §4B.4.
 *
 * §4B.4 words the limits row as "10 MB per file **by default**, 50 MB per
 * ticket, 20 files per ticket — all configurable in system settings". C-023 put
 * those three numbers in `components/ui/attachments.ts` as constants, with a
 * note saying they become the fallback the day a settings source exists. This
 * is that day.
 *
 * ## Why the client has to read them rather than know them
 *
 * The picker refuses a file **before any request is made** — that is the whole
 * point of the client-side gate, and it is what stops a user watching a 40 MB
 * upload run only to be told at the end that it was never going to be accepted.
 * A hard-coded 10 MB therefore does not merely disagree with a raised server
 * cap; it *overrides* it. An administrator who lifts the per-file limit to 25 MB
 * would watch the setting save, reload the ticket page and still be refused at
 * 10 MB, with no request in the network tab and nothing in any log. The setting
 * would look broken, and the place it was broken would be a different codebase
 * from the one they were looking at.
 *
 * So there is one authority, the server, and both sides read it: the picker
 * validates against this, and `AttachmentService.enforceLimits` validates
 * against the same `effective()`. A file the picker accepts is one the server
 * accepts.
 *
 * ## Why a failure falls back instead of blocking
 *
 * If the request fails, or has not landed yet, this returns §4B.4's published
 * defaults. Blocking the picker on a settings fetch would make a slow or failing
 * request look like a broken attachment control on five screens, and the
 * fallback is not a guess — it is the blueprint's own specification and what an
 * unconfigured deployment enforces anyway. The cost of being wrong is one
 * rejected upload that the server would have taken (or a 413 the user is shown
 * plainly), which is strictly better than no upload control at all.
 */

/**
 * Long, and on purpose.
 *
 * These change when an administrator edits them, which is approximately never,
 * and every ticket page, create form and quick update panel wants them. One
 * fetch per session is the intent; React Query dedupes the concurrent callers,
 * so five surfaces mounting at once still make one request.
 */
const LIMITS_STALE_MS = 60 * 60 * 1000

/**
 * The wire shape, narrowed to what the picker takes — and defended.
 *
 * Every field is optional in the generated type (orval widens anything the
 * response could omit), and a zero or a negative would be worse than a missing
 * value: `maxFiles: 0` reads as "no attachment may ever be added" and would
 * disable every upload surface in the product from a bad payload, silently and
 * with a perfectly ordinary-looking 200. Each field falls back independently, so
 * one bad number costs one limit rather than all three.
 *
 * `ceilingBytes` is deliberately not consulted. It is the server's own multipart
 * bound and the server has already clamped `maxFileBytes` under it; a client
 * that applied it a second time would be re-deriving a rule it does not own.
 */
export function toAttachmentLimits(dto: AttachmentLimitsDto | undefined): AttachmentLimits {
  const positive = (value: number | undefined, fallback: number) =>
    typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : fallback

  return {
    maxFileBytes: positive(dto?.maxFileBytes, ATTACHMENT_DEFAULT_LIMITS.maxFileBytes),
    maxTotalBytes: positive(dto?.maxTicketBytes, ATTACHMENT_DEFAULT_LIMITS.maxTotalBytes),
    maxFiles: positive(dto?.maxFiles, ATTACHMENT_DEFAULT_LIMITS.maxFiles),
  }
}

/**
 * Hand straight to `<AttachmentPicker limits>`. Never undefined, never throws.
 *
 * Returns the defaults while loading and after a failure, so a caller has
 * nothing to branch on — which is the point. A surface that had to handle a
 * loading state here would render its picker disabled for a moment on every
 * mount, and a disabled picker is indistinguishable from one that is broken.
 */
export function useAttachmentLimits(): AttachmentLimits {
  const { data } = useGetAttachmentLimits({
    query: {
      staleTime: LIMITS_STALE_MS,
      gcTime: LIMITS_STALE_MS,
      // One attempt. A failure costs the customisation and not the feature —
      // the defaults are already correct for an unconfigured deployment — and
      // three retries would delay the real numbers on a flaky connection
      // without changing what the user can do in the meantime.
      retry: false,
    },
  })

  return toAttachmentLimits(data?.data)
}
