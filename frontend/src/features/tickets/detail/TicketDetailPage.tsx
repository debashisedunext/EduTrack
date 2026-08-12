import * as React from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'

import { useGetTicketDetail } from '@/api/generated/tickets/tickets'
import { useListTaskTypes } from '@/api/generated/masters/masters'
import { useListClientContacts } from '@/api/generated/clients/clients'
import { ApiError } from '@/api/http'

import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { RichTextView } from '@/components/ui/rich-text-view'
import { ensureRichText } from '@/components/ui/rich-text'

import { PendingSection } from './PendingSection'
import { TicketAttachmentsSection } from './TicketAttachmentsSection'
import { TicketDetailHeader } from './TicketDetailHeader'
import { TicketDetailTabs, type DetailTab } from './TicketDetailTabs'
import { TicketSummaryPanel } from './TicketSummaryPanel'

const DEFAULT_TAB = 'journey'

/** Every tab in S-20, each pointing at the task that fills it. */
const TAB_OWNERS: { id: string; label: string; owner: string; note?: string }[] = [
  { id: 'journey', label: 'Journey', owner: 'C-055', note: 'The stage-by-stage roll-up — every hop, its duration, and the active-versus-idle split.' },
  { id: 'history', label: 'History', owner: 'C-059', note: 'Cycle-grouped field changes and handoffs. Append-only: no edit or delete affordance exists for any role.' },
  { id: 'comments', label: 'Comments', owner: 'C-029', note: 'The comment stream, with the always-visible box above it.' },
  { id: 'attachments', label: 'Attachments', owner: 'C-060', note: 'The gallery, grouped by cycle and stage.' },
  { id: 'effort', label: 'Effort', owner: 'C-061', note: 'Every effort line for the selected cycle, with per-cycle and grand totals.' },
  { id: 'chat', label: 'Chat', owner: 'D-047', note: 'The threaded ticket conversation.' },
]

/**
 * S-20 Ticket Detail — C-019, the shell and the summary panel.
 *
 * **One aggregated call.** `GET /tickets/{id}/full` returns the ticket,
 * cycles, ribbon, history, effort, comments, attachments and watchers
 * together. The two masters lookups beside it resolve `taskTypeId` and
 * `clientContactId`, which the payload carries as bare IDs; everything else on
 * this page comes from the one request, because the waterfall is what makes a
 * detail page feel slow, not the payload size.
 *
 * **Cycle and tab live in the URL.** Same rule C-014 set for the ticket list:
 * a colleague pasting "cycle 1's effort on CRM-26-00347" into chat has to land
 * on cycle 1's effort. `?cycle=` also re-fetches — an earlier cycle is a
 * different, read-only journey, not a client-side filter over this one.
 *
 * **404 is the only "no access" answer.** Out-of-scope IDs come back as 404 by
 * design so nothing leaks the existence of a ticket the caller cannot see, and
 * this page must not undo that by saying "you do not have permission".
 */
export function TicketDetailPage() {
  const { ticketId = '' } = useParams()
  const [searchParams, setSearchParams] = useSearchParams()

  const rawCycle = Number(searchParams.get('cycle'))
  const cycle = Number.isInteger(rawCycle) && rawCycle > 0 ? rawCycle : undefined

  const requestedTab = searchParams.get('tab') ?? DEFAULT_TAB
  const activeTab = TAB_OWNERS.some((t) => t.id === requestedTab) ? requestedTab : DEFAULT_TAB

  const selectTab = React.useCallback(
    (id: string) => {
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev)
          if (id === DEFAULT_TAB) next.delete('tab')
          else next.set('tab', id)
          return next
        },
        // Replace rather than push: arrowing across six tabs should not bury
        // the ticket list six entries deep in the back button.
        { replace: true },
      )
    },
    [setSearchParams],
  )

  const { data, isPending, isError, error, refetch } = useGetTicketDetail(ticketId, cycle ? { cycle } : undefined, {
    query: { enabled: ticketId.length > 0, retry: false },
  })

  const detail = data?.data
  const ticket = detail?.ticket

  const { data: taskTypesData } = useListTaskTypes()
  const taskTypeName = React.useMemo(() => {
    if (ticket?.taskTypeId == null) return undefined
    return (taskTypesData?.data ?? []).find((t) => t.id === ticket.taskTypeId)?.name
  }, [taskTypesData, ticket?.taskTypeId])

  const clientId = ticket?.client?.id
  const contactId = ticket?.clientContactId
  const { data: contactsData } = useListClientContacts(clientId ?? 0, {
    query: { enabled: clientId != null && contactId != null },
  })
  const contactName = React.useMemo(
    () => (contactId == null ? undefined : (contactsData?.data ?? []).find((c) => c.id === contactId)?.name),
    [contactsData, contactId],
  )

  const tabs: DetailTab[] = React.useMemo(
    () =>
      TAB_OWNERS.map(({ id, label, owner, note }) => ({
        id,
        label,
        content: <PendingSection title={`${label} tab`} owner={owner} note={note} />,
      })),
    [],
  )

  if (isPending) {
    return (
      <div className="flex flex-col gap-4 p-6" aria-busy="true">
        <Skeleton className="h-6 w-48" />
        <Skeleton className="h-9 w-2/3" />
        <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_20rem]">
          <Skeleton className="h-64 w-full" />
          <Skeleton className="h-64 w-full" />
        </div>
      </div>
    )
  }

  if (isError || !ticket) {
    const notFound = error instanceof ApiError && error.status === 404
    return (
      <div className="p-8">
        <EmptyState
          title={notFound ? 'Ticket not found' : 'Could not load this ticket'}
          description={
            notFound
              ? `No ticket ${ticketId} is available to you.`
              : error instanceof Error
                ? error.message
                : 'Something went wrong.'
          }
          action={
            notFound ? (
              <Button size="sm" variant="secondary" asChild>
                <Link to="/tickets">Back to tickets</Link>
              </Button>
            ) : (
              <Button size="sm" onClick={() => refetch()}>
                Retry
              </Button>
            )
          }
        />
      </div>
    )
  }

  const selectedCycleNo = cycle ?? ticket.cycleNo ?? 1
  const isEarlierCycle = selectedCycleNo < (ticket.cycleNo ?? 1)

  return (
    <div className="flex h-full flex-col overflow-y-auto">
      <TicketDetailHeader ticket={ticket} availableActions={detail?.availableActions} />

      <div className="grid flex-1 gap-4 p-6 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <div className="flex min-w-0 flex-col gap-4">
          {isEarlierCycle && (
            <p role="status" className="rounded-control bg-subtle px-3 py-2 text-caption text-content-muted">
              Showing cycle {selectedCycleNo}, which is sealed and read-only. Its journey, effort and history are
              preserved exactly as they were when it closed.{' '}
              <Link to={`/tickets/${ticket.ticketId}`} className="text-primary hover:underline">
                Back to the current cycle
              </Link>
              .
            </p>
          )}

          <section
            aria-label="Workflow ribbon"
            className="rounded-card border border-border bg-surface p-2 shadow-rest"
          >
            <PendingSection
              title="Workflow ribbon"
              owner="C-051"
              note="Eight segments, their owners, time in stage and effort, with the cycle selector above them."
            />
          </section>

          <section
            aria-labelledby="ticket-description-heading"
            className="rounded-card border border-border bg-surface p-4 shadow-rest"
          >
            <h2 id="ticket-description-heading" className="mb-2 text-h3 text-content">
              Description
            </h2>
            {/*
              Rendered through the §3.9 sanitiser, not printed as text — the
              description is rich text since C-066. `ensureRichText` is the
              migration shim: every row already in the database is plain text
              until C-067 backfills, and handing that straight to the view
              would collapse its line breaks. It goes when the backfill lands.
            */}
            <RichTextView
              html={ensureRichText(ticket.description ?? '')}
              emptyText="No description was given."
            />
          </section>

          {/*
            Directly under the description, where S-20's wireframe puts it. Fed
            from the `/full` payload this page already fetched — C-019's single
            aggregated call stands, and `refetch` is what makes an upload or a
            delete visible rather than a second query of our own.
          */}
          <TicketAttachmentsSection
            ticketId={ticket.ticketId}
            attachments={detail?.attachments}
            onChanged={() => void refetch()}
            readOnly={isEarlierCycle}
          />

          <TicketDetailTabs tabs={tabs} activeId={activeTab} onSelect={selectTab} />
        </div>

        <TicketSummaryPanel
          ticket={ticket}
          cycles={detail?.cycles}
          history={detail?.history}
          watchers={detail?.watchers}
          selectedCycleNo={selectedCycleNo}
          taskTypeName={taskTypeName}
          contactName={contactName}
        />
      </div>
    </div>
  )
}
