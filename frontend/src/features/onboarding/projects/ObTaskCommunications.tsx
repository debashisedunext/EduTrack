import * as React from 'react'
import { useQueryClient } from '@tanstack/react-query'

import type { ObStepCommunication } from '@/api/generated/model/obStepCommunication'
import type { ObStepCommunicationCreateRequestChannel } from '@/api/generated/model/obStepCommunicationCreateRequestChannel'
import {
  getListObStepCommunicationsQueryKey,
  useCreateObStepCommunication,
  useListObStepCommunications,
} from '@/api/generated/onboarding-journeys/onboarding-journeys'
import { cn } from '@/lib/utils'

/**
 * A task's communication timeline, and the one line that adds to it.
 *
 * <h2>Append-only, and it says so</h2>
 *
 * <p>There is no `PATCH` or `DELETE` on this path and there will not be
 * (CONVENTIONS §8): the thread is half the record of what a client was asked
 * for and what they said back, and a deletable entry is a conversation either
 * side can rewrite after a dispute starts. A correction is a new entry.
 *
 * <h2>Eight kinds in, five kinds out</h2>
 *
 * <p>`entry_type` is one column carrying all eight values rather than a channel
 * beside a kind, so a reader filtering the timeline chooses between all of them
 * with one control. Only `CALL, EMAIL, MEETING, WHATSAPP, OTHER` can be
 * composed — that route is a person recording a conversation. `COMMENT` is a
 * client's portal comment, `ESCALATION` is C-126's mirror, and `SYSTEM` is the
 * module's own automatic entries; all three are written by other subsystems and
 * are read-only here.
 *
 * <h2>Client-visible is off by default, and that is not a UI convenience</h2>
 *
 * <p>True publishes the entry to the client's portal thread and there is no way
 * to unpublish something a client has already read. The checkbox therefore
 * starts unticked every time, including after a send.
 *
 * <h2>Direction is shown, not chosen</h2>
 *
 * <p>The design has an Inbound/Outbound picker. `ObStepCommunication` has no
 * direction field — it has `authorType` (`STAFF | CLIENT | SYSTEM`) — so each
 * row's direction is read from who wrote it, and the composer offers no picker
 * rather than one that silently discards what it is set to. Deriving it is
 * right for reading and wrong for writing: a staff member logging an *inbound*
 * call is the common case and cannot be expressed until the field exists.
 */
export interface ObTaskCommunicationsProps {
  stepId: number
  /** Anybody on staff may record one — it is a log entry, not a task mutation. */
  canPost: boolean
}

const WRITABLE: readonly ObStepCommunicationCreateRequestChannel[] = [
  'CALL', 'EMAIL', 'MEETING', 'WHATSAPP', 'OTHER',
]

const BADGE: Record<string, string> = {
  CALL: 'bg-level-medium-soft text-info-text',
  EMAIL: 'bg-level-medium-soft text-info-text',
  MEETING: 'bg-level-medium-soft text-info-text',
  WHATSAPP: 'bg-level-low-soft text-success-text',
  ESCALATION: 'bg-danger-soft text-danger-text',
  COMMENT: 'bg-level-high-soft text-warning-text',
  SYSTEM: 'bg-subtle text-content-muted',
  OTHER: 'bg-subtle text-content-muted',
}

const RING: Record<string, string> = {
  ESCALATION: 'border-danger',
  COMMENT: 'border-warning',
  SYSTEM: 'border-ribbon-pending',
}

/** Who wrote it, read as a direction — see the note above. */
function direction(entry: ObStepCommunication): string {
  if (entry.authorType === 'CLIENT') return 'inbound'
  if (!entry.isClientVisible) return 'internal'
  return 'outbound'
}

export function ObTaskCommunications({ stepId, canPost }: ObTaskCommunicationsProps) {
  const queryClient = useQueryClient()
  const [channel, setChannel] = React.useState<ObStepCommunicationCreateRequestChannel>('CALL')
  const [summary, setSummary] = React.useState('')
  const [clientVisible, setClientVisible] = React.useState(false)

  const list = useListObStepCommunications(stepId)
  const entries = list.data?.data ?? []

  const create = useCreateObStepCommunication({
    mutation: {
      onSuccess: () => {
        setSummary('')
        // Back to private after every send: see the note on why this is not a
        // convenience.
        setClientVisible(false)
        void queryClient.invalidateQueries({
          queryKey: getListObStepCommunicationsQueryKey(stepId),
        })
      },
    },
  })

  const send = () => {
    const text = summary.trim()
    if (!text) return
    create.mutate({
      stepId,
      data: {
        channel,
        // When it happened, which is not when it was typed — the field exists
        // because people record a Friday call on Monday. Nothing on this row
        // lets them backdate it yet, so it is now.
        occurredAt: new Date().toISOString(),
        summary: text,
        isClientVisible: clientVisible,
      },
    })
  }

  return (
    /* `bg-surface` explicitly: this sits inside a task panel that is tinted
       indigo when the task is yours, and a transparent box would take that
       tint. The timeline is a record to read, so it gets a white ground of its
       own wherever it is mounted. */
    <section className="overflow-hidden rounded-control border border-border bg-surface">
      <header className="flex flex-wrap items-baseline gap-x-2 border-b border-border bg-subtle px-3 py-1.5">
        <h5 className="m-0 text-caption font-semibold text-content">Communication on this task</h5>
        <span className="text-caption tabular-nums text-content-muted">
          {list.isPending ? '…' : `${entries.length} ${entries.length === 1 ? 'entry' : 'entries'}`}
        </span>
      </header>

      {canPost && (
        <div className="flex flex-wrap items-center gap-1.5 border-b border-border px-3 py-2">
          <select
            value={channel}
            aria-label="Channel"
            disabled={create.isPending}
            onChange={(e) =>
              setChannel(e.target.value as ObStepCommunicationCreateRequestChannel)
            }
            className="flex-none rounded-[6px] border border-border bg-surface px-2 py-1 text-caption text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          >
            {WRITABLE.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
          <input
            type="text"
            value={summary}
            aria-label="What was communicated?"
            placeholder="What was communicated?"
            disabled={create.isPending}
            onChange={(e) => setSummary(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault()
                send()
              }
            }}
            className="min-w-0 flex-1 rounded-[6px] border border-border bg-surface px-2.5 py-1 text-caption text-content placeholder:text-content-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          />
          <label
            className="flex shrink-0 items-center gap-1.5 text-caption text-content-muted"
            title="Publishes this to the client's portal thread. There is no way to unpublish something a client has already read."
          >
            <input
              type="checkbox"
              checked={clientVisible}
              disabled={create.isPending}
              onChange={(e) => setClientVisible(e.target.checked)}
              className="size-3.5 accent-primary"
            />
            Visible to client
          </label>
          <button
            type="button"
            disabled={create.isPending || !summary.trim()}
            onClick={send}
            className="shrink-0 rounded-control bg-primary px-3.5 py-1.5 text-caption font-semibold text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-content disabled:cursor-not-allowed disabled:bg-subtle disabled:text-content-muted"
          >
            Log
          </button>
        </div>
      )}

      <p className="m-0 px-3 pt-2 text-[10.5px] text-content-muted">
        Append-only — corrections are new entries, never edits.
      </p>

      {list.isPending ? (
        <p className="px-3 py-3 text-caption text-content-muted" role="status">
          Loading the timeline…
        </p>
      ) : entries.length === 0 ? (
        <p className="px-3 py-3 text-caption italic text-content-muted">
          Nothing recorded against this task yet.
        </p>
      ) : (
        <ol className="m-0 max-h-80 list-none overflow-y-auto p-3 pt-2">
          {entries.map((entry, index) => (
            <li key={entry.id} className="relative py-1.5 pl-5">
              {index < entries.length - 1 && (
                <span
                  aria-hidden="true"
                  className="absolute bottom-[-2px] left-[5px] top-[18px] w-[1.5px] bg-border"
                />
              )}
              <span
                aria-hidden="true"
                className={cn(
                  'absolute left-0 top-[7px] size-[11px] rounded-chip border-2 bg-surface',
                  RING[entry.channel] ?? 'border-primary',
                )}
              />
              <span className="flex flex-wrap items-baseline gap-x-1.5">
                <span
                  className={cn(
                    'rounded-chip px-1.5 text-[9.5px] font-bold tracking-wide',
                    BADGE[entry.channel] ?? BADGE.OTHER,
                  )}
                >
                  {entry.channel}
                </span>
                <span className="text-caption font-semibold text-content">
                  {entry.authorName}
                  {entry.authorType === 'CLIENT' ? ' (Client)' : ''}
                </span>
                <span className="text-[10.5px] tabular-nums text-content-muted">
                  {formatWhen(entry.occurredAt)} · {direction(entry)}
                </span>
              </span>
              <p className="m-0 mt-0.5 text-caption text-content">{entry.summary}</p>
            </li>
          ))}
        </ol>
      )}

      {create.isError && (
        <p className="px-3 pb-2 text-caption text-danger-text" role="alert">
          That entry was not recorded. Check the summary and try again.
        </p>
      )}
    </section>
  )
}

/** `2026-09-14T17:40:00Z` → "14 Sep, 17:40". */
function formatWhen(value: string): string {
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  return `${parsed.toLocaleDateString(undefined, { day: '2-digit', month: 'short' })}, ${parsed.toLocaleTimeString(
    undefined,
    { hour: '2-digit', minute: '2-digit', hour12: false },
  )}`
}
