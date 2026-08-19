import * as React from 'react'

import { useListAttachments } from '@/api/generated/attachments/attachments'
import { ApiError } from '@/api/http'
import type { Attachment } from '@/api/generated/model'

export interface UseAttachmentsTabOptions {
  ticketId: string
  /**
   * Defaults to `true`. `TicketDetailPage` passes `activeTab === 'attachments'` —
   * matching `useTicketHistory`'s own rule: nothing outside this tab needs every
   * cycle's files, so fetching them before the tab is opened would be a request
   * for a gallery most visitors never scroll to.
   */
  enabled?: boolean
}

export interface UseAttachmentsTabResult {
  /**
   * Every attachment on the ticket, live and tombstoned, across every cycle —
   * the strip's `detail.attachments` is scoped to one cycle by A-052's `?cycle=`,
   * and grouping by cycle is the entire point of this tab.
   */
  rows: Attachment[]
  isLoading: boolean
  loadError: string | null
  clientVisibleOnly: boolean
  setClientVisibleOnly: (value: boolean) => void
}

/**
 * C-060 · the Attachments tab's one query, plus the client-visible filter
 * blueprint §7 lists: "gallery of every file on the ticket … filterable by
 * client-visible, grouped by cycle and stage".
 *
 * ## Why this fetches rather than reading the `/full` payload
 *
 * `detail.attachments` exists on `GET /tickets/{id}/full` already, and
 * `TicketAttachmentsSection` reads it for the strip — but that projection is
 * cycle-scoped to whichever cycle the page is viewing, the same limitation
 * `useTicketHistory` and `useTicketComments` each found and fetched around.
 * `listAttachments` takes no `cycle` here on purpose, for every row at once.
 *
 * ## The filter is client-side, not a second request
 *
 * Unlike `useTicketHistory`'s `include=comments` — which asks the server for
 * rows it would otherwise leave out — `clientVisibleOnly` narrows a set this
 * hook already has in full; `AttachmentService.list` supports it server-side
 * for the strip's own use, but re-fetching the same ticket's attachments to
 * flip a boolean over data already in hand would be the waterfall C-019's
 * aggregated call exists to avoid, on a per-ticket list §4B.4 already caps at
 * 20 files.
 */
export function useAttachmentsTab({ ticketId, enabled = true }: UseAttachmentsTabOptions): UseAttachmentsTabResult {
  const [clientVisibleOnly, setClientVisibleOnly] = React.useState(false)

  const { data, isPending, isError, error } = useListAttachments(
    ticketId,
    undefined,
    { query: { enabled: enabled && ticketId.length > 0, retry: false } },
  )

  return {
    rows: data?.data ?? [],
    isLoading: isPending,
    loadError: isError ? messageFor(error, 'This ticket’s attachments could not be loaded.') : null,
    clientVisibleOnly,
    setClientVisibleOnly,
  }
}

function messageFor(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    return error.problem.detail ?? error.problem.title ?? fallback
  }
  return fallback
}
