import { FileText, Paperclip, ShieldAlert, ShieldQuestion } from 'lucide-react'

import type { ChatAttachment } from '@/api/generated/model'
import { cn } from '@/lib/utils'

/**
 * D-053 · the files carried by one message — blueprint §7.6.
 *
 * ## Three states, and only one of them is a link
 *
 * `downloadUrl` is minted server-side **only** for a `CLEAN` row, so this
 * component never decides whether a file is safe — it renders what the server
 * was willing to hand over. A `PENDING` row is shown as itself rather than
 * hidden: hiding it makes a slow virus scan indistinguishable from a failed
 * upload and leaves the sender re-attaching the same file, which is C-025's own
 * reasoning for returning pending rows on a ticket.
 *
 * An `INFECTED` row is shown too, and that is deliberate. The bytes are already
 * deleted from storage; what survives is the record that somebody shared
 * something that did not pass, which §7.6 wants kept — chat is evidence.
 *
 * ## An image is an image because the server sniffed it
 *
 * `isImage` comes off the **sniffed** content type. Rendering an `<img>` from a
 * file extension is how a renamed executable gets a request made to it, and the
 * decision deliberately does not live on this side of the wire.
 *
 * ## Every image has real alt text
 *
 * The file name, not "image" — a screenshot called
 * `checkout-500-saved-card.png` tells a screen reader user what was shared, and
 * that is the only description anybody ever supplies in a chat client.
 */
export function ChatAttachments({ attachments }: { attachments: ChatAttachment[] | undefined }) {
  if (!attachments || attachments.length === 0) return null

  return (
    <ul className="mt-1 flex flex-col gap-2">
      {attachments.map((file) => (
        <li key={file.id}>
          <ChatAttachmentItem file={file} />
        </li>
      ))}
    </ul>
  )
}

function ChatAttachmentItem({ file }: { file: ChatAttachment }) {
  const name = file.fileName ?? 'Attachment'

  if (file.scanStatus === 'INFECTED') {
    return (
      <span className="inline-flex items-center gap-2 rounded-md border border-border bg-subtle px-2 py-1 text-xs text-content-muted">
        <ShieldAlert aria-hidden className="size-3.5" />
        {name} — blocked by the virus scan
      </span>
    )
  }

  if (!file.downloadUrl) {
    // PENDING. No link, because there is nothing to link to yet.
    return (
      <span className="inline-flex items-center gap-2 rounded-md border border-border bg-subtle px-2 py-1 text-xs text-content-muted">
        <ShieldQuestion aria-hidden className="size-3.5" />
        {name} — checking this file
      </span>
    )
  }

  if (file.isImage) {
    return (
      <a href={file.downloadUrl} target="_blank" rel="noreferrer" className="inline-block">
        <img
          src={file.downloadUrl}
          alt={name}
          className="max-h-64 max-w-full rounded-md border border-border object-contain"
        />
      </a>
    )
  }

  return (
    <a
      href={file.downloadUrl}
      target="_blank"
      rel="noreferrer"
      className={cn(
        'inline-flex items-center gap-2 rounded-md border border-border px-2 py-1',
        'text-xs text-content hover:bg-subtle',
      )}
    >
      <FileText aria-hidden className="size-3.5" />
      {name}
      <span className="text-content-muted">{humanSize(file.sizeBytes)}</span>
    </a>
  )
}

/** Attached to the composer's own pending list, so it lives beside its one sibling use. */
export function AttachmentChip({ name, onRemove }: { name: string; onRemove: () => void }) {
  return (
    <span className="inline-flex items-center gap-1 rounded-md border border-border bg-subtle px-2 py-0.5 text-xs text-content">
      <Paperclip aria-hidden className="size-3" />
      {name}
      <button
        type="button"
        onClick={onRemove}
        // Named, not a bare ×: a row of identical "remove" buttons is
        // unusable by anybody navigating by label.
        aria-label={`Remove ${name}`}
        className="ml-0.5 rounded px-1 text-content-muted hover:text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
      >
        ×
      </button>
    </span>
  )
}

function humanSize(bytes: number | undefined): string {
  if (bytes == null) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
