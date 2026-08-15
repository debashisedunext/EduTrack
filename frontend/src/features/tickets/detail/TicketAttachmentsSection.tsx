import type { Attachment } from '@/api/generated/model'
import { AttachmentPicker } from '@/components/ui/attachment-picker'
import { AttachmentGallery } from '../attachments/AttachmentGallery'
import { useAttachmentLimits } from '../attachments/attachmentLimits'
import { useTicketAttachments } from '../attachments/useTicketAttachments'

/**
 * S-20's attachment strip — C-023 and C-026, blueprint §4B.4.
 *
 * Sits directly under the description because that is where §7's S-20 wireframe
 * puts it (`📎 [thumb][thumb] error-log.txt   +2`), immediately above the tabs.
 *
 * **This is not the Attachments tab.** That is C-060 — the full gallery, grouped
 * by cycle and stage, filterable by client-visible — and it stays a
 * `PendingSection` until then. This is the upload surface and the at-a-glance
 * strip, which is all §4B.4 asks the detail page for.
 *
 * ## One list, drawn once
 *
 * C-026 gave this page thumbnails, and the picker already drew a row per file.
 * Rendering both would show every attachment twice, so `AttachmentGallery` draws
 * them and the picker is told `showItems={false}` — while still being handed the
 * full `items`, because its per-ticket size and count limits are computed from
 * them. The picker keeps the drop zone, the clipboard paste and the validation;
 * the gallery keeps the pixels.
 *
 * A separate component rather than JSX inside `TicketDetailPage` because the
 * page early-returns for its loading and 404 states before it renders anything,
 * and `useTicketAttachments` cannot be called after a conditional return.
 */
export function TicketAttachmentsSection({
  ticketId,
  attachments,
  onChanged,
  readOnly,
}: {
  ticketId: string
  /** From the aggregated `/full` payload — this page does not fetch again. */
  attachments: Attachment[] | undefined
  /** Refetch `/full`, so an upload or delete shows up as the server sees it. */
  onChanged: () => void
  /**
   * An earlier cycle is sealed. Its files stay readable — that is the whole
   * point of preserving a cycle — but nothing may be added to or removed from a
   * journey that has already closed.
   */
  readOnly?: boolean
}) {
  const { items, add, remove, isUploading } = useTicketAttachments({
    ticketId,
    existing: attachments,
    onUploaded: onChanged,
  })
  // C-027. Server-authored, so this strip refuses exactly what the server would
  // refuse — the picker's gate runs before any request, so a hard-coded cap here
  // would override a raised setting rather than merely disagree with it.
  const limits = useAttachmentLimits()

  return (
    <section
      aria-labelledby="ticket-attachments-heading"
      className="rounded-card border border-border bg-surface p-4 shadow-rest"
    >
      <div className="mb-2 flex items-baseline justify-between gap-2">
        <h2 id="ticket-attachments-heading" className="text-h3 text-content">
          Attachments
        </h2>
        {isUploading && (
          <span className="text-caption text-content-muted" role="status">
            Uploading…
          </span>
        )}
      </div>

      {readOnly ? (
        items.length > 0 ? (
          // A sealed cycle's files stay readable — that is the whole point of
          // preserving a cycle — so the gallery renders and the lightbox opens.
          // Only `onRemove` is withheld: nothing may be added to or taken out of
          // a journey that has already closed.
          <AttachmentGallery items={items} />
        ) : (
          <p className="text-caption text-content-muted">Nothing was attached in this cycle.</p>
        )
      ) : (
        <div className="flex flex-col gap-3">
          <AttachmentGallery items={items} onRemove={remove} />
          <AttachmentPicker
            items={items}
            onAdd={add}
            limits={limits}
            showItems={false}
            aria-label="Attachments"
          />
        </div>
      )}
    </section>
  )
}
