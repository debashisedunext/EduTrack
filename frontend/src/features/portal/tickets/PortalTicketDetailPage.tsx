import { Link, useParams } from 'react-router-dom'

import {
  useGetPortalTicket,
  useListPortalTicketAttachments,
  useListPortalTicketComments,
} from '@/api/generated/portal/portal'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'

/**
 * CP-07 · one of the client's own tickets, read-only.
 *
 * Two things this page deliberately has none of, both structural rather than
 * a checklist item this component has to remember:
 *
 * - **No comment composer.** `PortalTicketDtos.PortalComment`'s own note:
 *   "the portal thread is read-only in phase 1" — `POST
 *   /portal/tickets/{id}/comments` does not exist, so there is nothing this
 *   page could call even if it grew a form.
 * - **No status-change or raise-ticket affordance.** `PortalTicketController`
 *   registers four `GET`s and nothing else (plan §1: "the portal's ticketing
 *   side is view-only").
 *
 * Comments and attachments are already filtered server-side —
 * `PortalTicketReadRepository` selects only `is_internal = 0` comments and
 * `is_client_visible = 1`, scan-clean attachments — so this page renders
 * every row it is handed without a second filter of its own.
 */
export function PortalTicketDetailPage() {
  const params = useParams<{ ticketId: string }>()
  const ticketId = params.ticketId ?? ''

  const { data, isPending, isError } = useGetPortalTicket(ticketId, {
    query: { enabled: ticketId.length > 0 },
  })

  if (!ticketId) {
    return <EmptyState title="Ticket not found" />
  }

  if (isPending) {
    return (
      <div className="flex flex-col gap-4">
        <Skeleton className="h-32 w-full rounded-card" />
        <Skeleton className="h-48 w-full rounded-card" />
      </div>
    )
  }

  if (isError || !data) {
    return <EmptyState title="Could not load this ticket" description="It may not belong to your account." />
  }

  const ticket = data.data

  return (
    <div className="flex flex-col gap-6">
      <div>
        <Link to="/portal/tickets" className="text-sm text-primary hover:underline">
          ← Back to my tickets
        </Link>
      </div>

      <section className="rounded-card border border-border bg-surface p-5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="text-h2 text-content">{ticket.title}</h1>
            <p className="mt-1 text-sm text-content-muted">
              {ticket.ticketId} · {ticket.projectName}
              {ticket.taskTypeName ? ` · ${ticket.taskTypeName}` : ''}
            </p>
          </div>
          <StatusChip status={ticket.status} />
        </div>

        {ticket.description ? (
          <p className="mt-4 whitespace-pre-wrap text-sm text-content">{ticket.description}</p>
        ) : null}

        <dl className="mt-4 grid grid-cols-1 gap-3 text-sm sm:grid-cols-3">
          <div>
            <dt className="text-caption text-content-muted">Reported</dt>
            <dd className="text-content">{new Date(ticket.dateReported).toLocaleDateString()}</dd>
          </div>
          <div>
            <dt className="text-caption text-content-muted">Expected by</dt>
            <dd className="text-content">
              {ticket.plannedCloseDate ? new Date(ticket.plannedCloseDate).toLocaleDateString() : '—'}
            </dd>
          </div>
          <div>
            <dt className="text-caption text-content-muted">Closed</dt>
            <dd className="text-content">
              {ticket.actualCloseDate ? new Date(ticket.actualCloseDate).toLocaleDateString() : '—'}
            </dd>
          </div>
        </dl>
      </section>

      <AttachmentsCard ticketId={ticketId} />
      <CommentsCard ticketId={ticketId} />
    </div>
  )
}

function StatusChip({ status }: { status: string }) {
  switch (status) {
    case 'CLOSED':
    case 'RESOLVED':
      return <Chip variant="success">{status === 'CLOSED' ? 'Closed' : 'Resolved'}</Chip>
    case 'ON_HOLD':
    case 'AWAITING_INFO':
      return <Chip variant="warning">{status === 'ON_HOLD' ? 'On hold' : 'Awaiting info'}</Chip>
    case 'REWORK':
    case 'REOPENED':
      return <Chip variant="danger">{status === 'REWORK' ? 'Rework' : 'Reopened'}</Chip>
    case 'IN_PROGRESS':
      return <Chip variant="info">In progress</Chip>
    default:
      return <Chip variant="neutral">New</Chip>
  }
}

function CommentsCard({ ticketId }: { ticketId: string }) {
  const { data, isPending } = useListPortalTicketComments(ticketId, undefined, {
    query: { enabled: ticketId.length > 0 },
  })

  return (
    <section className="rounded-card border border-border bg-surface p-5">
      <h2 className="text-h3 text-content">Comments</h2>
      <p className="mt-1 text-caption text-content-muted">
        Only messages your account has been included on appear here.
      </p>

      {isPending ? (
        <Skeleton className="mt-3 h-20 w-full" />
      ) : (
        <ul className="mt-3 flex flex-col gap-3">
          {(data?.data ?? []).map((comment) => (
            <li key={comment.id} className="rounded-card bg-subtle px-3 py-2 text-sm">
              <p className="text-caption font-medium text-content-muted">
                {comment.authorName ?? 'Support team'}
              </p>
              <p className="whitespace-pre-wrap text-content">{comment.body}</p>
              <p className="mt-1 text-caption text-content-muted">
                {new Date(comment.createdAt).toLocaleString()}
              </p>
            </li>
          ))}
          {(data?.data ?? []).length === 0 ? (
            <p className="text-sm text-content-muted">No comments yet.</p>
          ) : null}
        </ul>
      )}
    </section>
  )
}

function AttachmentsCard({ ticketId }: { ticketId: string }) {
  const { data, isPending } = useListPortalTicketAttachments(ticketId, undefined, {
    query: { enabled: ticketId.length > 0 },
  })

  return (
    <section className="rounded-card border border-border bg-surface p-5">
      <h2 className="text-h3 text-content">Attachments</h2>

      {isPending ? (
        <Skeleton className="mt-3 h-16 w-full" />
      ) : (data?.data ?? []).length === 0 ? (
        <p className="mt-3 text-sm text-content-muted">Nothing attached yet.</p>
      ) : (
        <ul className="mt-3 flex flex-col gap-1">
          {(data?.data ?? []).map((file) => (
            <li key={file.id} className="text-sm">
              {file.downloadUrl ? (
                <a href={file.downloadUrl} className="text-primary hover:underline" target="_blank" rel="noreferrer">
                  {file.fileName}
                </a>
              ) : (
                <span className="text-content-muted">{file.fileName}</span>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

export default PortalTicketDetailPage
