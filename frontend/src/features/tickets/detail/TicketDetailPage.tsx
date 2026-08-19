import * as React from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'

import { useGetTicketDetail } from '@/api/generated/tickets/tickets'
import { useListTaskTypes } from '@/api/generated/masters/masters'
import { useListClientContacts } from '@/api/generated/clients/clients'
import { useGetMe } from '@/api/generated/auth/auth'
import { ApiError } from '@/api/http'

import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { RichTextView } from '@/components/ui/rich-text-view'
import { ensureRichText } from '@/components/ui/rich-text'

import { AttachmentsTab } from '../attachments/AttachmentsTab'
import { useAttachmentsTab } from '../attachments/useAttachmentsTab'
import { CommentBox } from '../comments/CommentBox'
import { CommentThread } from '../comments/CommentThread'
import { useTicketComments } from '../comments/useTicketComments'
import { HistoryTab } from '../history/HistoryTab'
import { useTicketHistory } from '../history/useTicketHistory'
import { EffortTab } from '../effort/EffortTab'
import { JourneyTab } from '../journey/JourneyTab'
import { useJourneyTab } from '../journey/useJourneyTab'
import { useEffortTab } from '../effort/useEffortTab'

import { canChangeLevel } from './levelChange'
import { PendingSection } from './PendingSection'
import { TicketAttachmentsSection } from './TicketAttachmentsSection'
import { TicketDetailHeader } from './TicketDetailHeader'
import { TicketDetailTabs, type DetailTab } from './TicketDetailTabs'
import { TicketSummaryPanel } from './TicketSummaryPanel'
import { totalEffortHrs } from './ticketSummary'

const DEFAULT_TAB = 'journey'

/** Every tab in S-20, each pointing at the task that fills it. */
const TAB_OWNERS: { id: string; label: string; owner: string; note?: string }[] = [
  // `note` is dead for this one now too — `JourneyTab` never reads it — kept for
  // the same one-line-description reason C-059's and C-061's identical comments
  // give. The active/idle half of the note is still accurate as a description of
  // where the tab is going: C-056 adds the idle column, C-057 the roll-up.
  { id: 'journey', label: 'Journey', owner: 'C-055', note: 'The stage-by-stage roll-up — every hop, its duration, and the active-versus-idle split.' },
  // C-059 fills this one too, same reason C-029's comments entry does below:
  // kept here so the strip's order, labels and ownership stay in one list,
  // and `content` decides whether a tab renders a `PendingSection` or the
  // real thing. `note` is now dead for both — HistoryTab and CommentThread
  // never read it — but removing it would lose the one-line description a
  // reader gets from this table alone, without opening either component.
  { id: 'history', label: 'History', owner: 'C-059', note: 'Cycle-grouped field changes and handoffs. Append-only: no edit or delete affordance exists for any role.' },
  { id: 'comments', label: 'Comments', owner: 'C-029', note: 'The comment stream, with the always-visible box above it.' },
  { id: 'attachments', label: 'Attachments', owner: 'C-060', note: 'The gallery, grouped by cycle and stage.' },
  // `note` is dead for this one too — `EffortTab` never reads it — kept for
  // the same one-line-description reason C-059's identical comment gives.
  { id: 'effort', label: 'Effort', owner: 'C-061', note: 'Every effort line, grouped by cycle, with per-cycle and grand totals.' },
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

  /*
   * C-033 · who is reading, which decides which comment cards offer Edit and
   * Remove. Both rules are enforced server-side (422 and 403); this only stops
   * the page offering a button that will be refused.
   *
   * `useGetMe` rather than a prop: the page already renders under `RequireAuth`
   * and the query is cached across the app, so this is a cache read rather than
   * a request — `TicketListPage` and `SavedViewsMenu` read the same one. Both
   * fields are undefined for a moment on a cold cache, and `commentPermissions`
   * treats a null id as "show nothing", which is the safe direction: an
   * affordance that appears a beat late costs a re-render, one that appears
   * wrongly is a refusal the user has to interpret.
   */
  const { data: meData } = useGetMe()
  const viewer = React.useMemo(
    () => ({ id: meData?.data.id ?? null, role: meData?.data.role }),
    [meData?.data.id, meData?.data.role],
  )

  const { data: taskTypesData } = useListTaskTypes()
  const taskTypeName = React.useMemo(() => {
    if (ticket?.taskTypeId == null) return undefined
    return (taskTypesData?.data ?? []).find((t) => t.id === ticket.taskTypeId)?.name
  }, [taskTypesData, ticket?.taskTypeId])

  const clientId = ticket?.client?.id
  const contactId = ticket?.clientContactId
  // B-027 · `includeInactive: true`, and it is not symmetrical with the create
  // form's picker. This resolves a name for a contact a *historical* ticket was
  // raised by, and B-027 removes a contact by deactivating the row rather than
  // deleting it — so without this the "Reported by" line goes blank the moment
  // that person leaves the client, which reads as missing data rather than as a
  // departure. Stream B's edit, one argument wide, flagged for Stream C.
  const { data: contactsData } = useListClientContacts(
    clientId ?? 0,
    { includeInactive: true },
    { query: { enabled: clientId != null && contactId != null } },
  )
  const contactName = React.useMemo(
    () => (contactId == null ? undefined : (contactsData?.data ?? []).find((c) => c.id === contactId)?.name),
    [contactsData, contactId],
  )

  /*
    C-029. Called here rather than inside a section component, because both
    halves of §4B.5 need it and they render in different places: the box is
    always visible above the tabs, and the stream is inside the Comments tab.
    One hook, one query, two consumers — the alternative is two components each
    calling `useListComments` and react-query dedupes them into one request,
    which works by coincidence rather than by design.

    Unlike the attachment strip this does not read from the `/full` payload;
    `useTicketComments` explains why at length, and the short version is that a
    thread has no cap and the contract pages it.
  */
  const comments = useTicketComments({ ticketId, cycle })

  // C-059 · gated on the tab being open — see `useTicketHistory`'s own note
  // on why this, unlike the comment box, has nothing else on the page that
  // needs it fetched early.
  const history = useTicketHistory({ ticketId, cycle, enabled: activeTab === 'history' })
  /*
    C-060 · fetched only once the tab is open, same rule `useTicketHistory`
    follows for the History tab: nothing outside this tab needs every cycle's
    files, and `detail.attachments` above is cycle-scoped for the strip's own
    at-a-glance purpose.
  */
  const attachmentsTab = useAttachmentsTab({ ticketId, enabled: ticketId.length > 0 && activeTab === 'attachments' })
  // C-061 · gated on the tab being open — `useEffortTab`'s own note on why,
  // and the identical rule `useTicketHistory`/`useAttachmentsTab` follow.
  const effortTab = useEffortTab({ ticketId, cycle, enabled: ticketId.length > 0 && activeTab === 'effort' })
  // C-055 · same gate as the three above. Unlike them this one forwards `cycle`
  // as the *scope* rather than a narrowing filter — §4A.4's grid is one cycle's
  // journey, because `iterationNo` restarts at 1 on a reopen.
  const journeyTab = useJourneyTab({ ticketId, cycle, enabled: ticketId.length > 0 && activeTab === 'journey' })
  // `ticket` is still possibly undefined here — the pending/404 guards below
  // haven't run yet at this point in the render — so this mirrors `taskTypeName`
  // and `contactName` above rather than `TicketSummaryPanel`'s narrowed `ticket`.
  const grandTotalHrs = ticket ? totalEffortHrs(ticket, detail?.cycles) : 0

  const tabs: DetailTab[] = React.useMemo(
    () =>
      TAB_OWNERS.map(({ id, label, owner, note }) => ({
        id,
        label,
        content:
          id === 'comments' ? (
            <CommentThread
              comments={comments.comments}
              isLoading={comments.isLoading}
              loadError={comments.loadError}
              viewer={viewer}
              onEdit={comments.edit}
              onDelete={comments.remove}
              isEditing={comments.isEditing}
              editError={comments.editError}
              isRemoving={comments.isRemoving}
              removeError={comments.removeError}
            />
          ) : id === 'history' ? (
            <HistoryTab
              entries={history.entries}
              isLoading={history.isLoading}
              loadError={history.loadError}
              includeComments={history.includeComments}
              onIncludeCommentsChange={history.setIncludeComments}
              hasMore={history.hasMore}
              isLoadingMore={history.isLoadingMore}
              onLoadMore={history.loadMore}
            />
          ) : id === 'attachments' ? (
            <AttachmentsTab
              attachments={attachmentsTab.rows}
              isLoading={attachmentsTab.isLoading}
              loadError={attachmentsTab.loadError}
              clientVisibleOnly={attachmentsTab.clientVisibleOnly}
              onClientVisibleOnlyChange={attachmentsTab.setClientVisibleOnly}
            />
          ) : id === 'effort' ? (
            <EffortTab
              entries={effortTab.entries}
              cycles={detail?.cycles}
              grandTotalHrs={grandTotalHrs}
              isLoading={effortTab.isLoading}
              loadError={effortTab.loadError}
              hasMore={effortTab.hasMore}
              isLoadingMore={effortTab.isLoadingMore}
              onLoadMore={effortTab.loadMore}
            />
          ) : id === 'journey' ? (
            <JourneyTab rows={journeyTab.rows} isLoading={journeyTab.isLoading} loadError={journeyTab.loadError} />
          ) : (
            <PendingSection title={`${label} tab`} owner={owner} note={note} />
          ),
      })),
    [
      journeyTab.rows,
      journeyTab.isLoading,
      journeyTab.loadError,
      comments.comments,
      comments.isLoading,
      comments.loadError,
      comments.edit,
      comments.remove,
      comments.isEditing,
      comments.editError,
      comments.isRemoving,
      comments.removeError,
      history.entries,
      history.isLoading,
      history.loadError,
      history.includeComments,
      history.setIncludeComments,
      history.hasMore,
      history.isLoadingMore,
      history.loadMore,
      viewer,
      attachmentsTab.rows,
      attachmentsTab.isLoading,
      attachmentsTab.loadError,
      attachmentsTab.clientVisibleOnly,
      attachmentsTab.setClientVisibleOnly,
      effortTab.entries,
      effortTab.isLoading,
      effortTab.loadError,
      effortTab.hasMore,
      effortTab.isLoadingMore,
      effortTab.loadMore,
      detail?.cycles,
      grandTotalHrs,
    ],
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
      <TicketDetailHeader
        ticket={ticket}
        availableActions={detail?.availableActions}
        onReopened={() => void refetch()}
        onClosed={() => void refetch()}
      />

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

          {/*
            C-029 · §4B.5's box, above the tabs rather than inside the Comments
            tab. §7 gives the reason in as many words: "the comment box itself is
            always visible above the tabs so posting never costs a click."

            C-031 · `clientName` is what decides whether the visibility toggle is
            drawn. Passing the name and not a boolean is deliberate: the warning
            has to name the client, and a `hasClient` flag would leave the box
            saying "the client will see this" — a sentence people read past. Not
            `contactName`: a client-visible comment goes to the client's portal
            and mail thread, which is the organisation, not the one contact who
            happened to raise the ticket.
          */}
          <CommentBox
            onPost={comments.post}
            isPosting={comments.isPosting}
            postError={comments.postError}
            // C-030 · the population the `@` type-ahead offers. Read from the
            // ticket this page already has rather than fetched: the server
            // resolves mentions against the same project on the write path, so
            // anything else here would offer names it then refuses.
            projectId={ticket.project?.id}
            disabled={isEarlierCycle}
            disabledReason={`Cycle ${selectedCycleNo} is sealed. Its comments stay readable, but new ones belong to the current cycle.`}
            clientName={ticket.client?.name}
          />

          <TicketDetailTabs tabs={tabs} activeId={activeTab} onSelect={selectTab} />
        </div>

        <TicketSummaryPanel
          ticket={ticket}
          cycles={detail?.cycles}
          history={detail?.history}
          watchers={detail?.watchers}
          linkedTickets={detail?.linkedTickets}
          onLinksChanged={() => void refetch()}
          selectedCycleNo={selectedCycleNo}
          taskTypeName={taskTypeName}
          contactName={contactName}
          /*
            C-020 · §4B.1's three roles, and never on a sealed cycle. The second
            half is the same rule the comment box and the attachment strip
            already follow: an earlier cycle is read-only, and its level belongs
            to a journey that ended. The write would in fact land on the current
            cycle — the server has no notion of "change the level as it was in
            cycle 1" — so offering it here would be offering the wrong act under
            the right label.

            Server-side either way: `PriorityChangeService` refuses a caller
            without the capability with 403. This only stops the panel offering a
            control that will be refused.
          */
          canChangeLevel={canChangeLevel(viewer.role) && !isEarlierCycle}
          onLevelChanged={() => void refetch()}
        />
      </div>
    </div>
  )
}
