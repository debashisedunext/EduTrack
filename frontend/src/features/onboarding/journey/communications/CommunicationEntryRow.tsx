import { format, parseISO } from 'date-fns'

import type { ObClientCommunication } from '@/api/generated/model/obClientCommunication'
import type { ObStepCommunication } from '@/api/generated/model/obStepCommunication'
import { Chip } from '@/components/ui/chip'
import { cn } from '@/lib/utils'
import { channelLook, entryByline, visibilityLabel } from './communicationEntry'

/**
 * C-112 · one entry, drawn the same way on both timelines.
 *
 * The per-step panel and the client-level stitched view render the identical
 * row; the only difference is the `context` line the stitched one adds naming
 * which service the entry came from. One component rather than two, for the
 * reason `obNotificationQueries.ts` gives for its own shared look: two copies
 * of "what does an ESCALATION entry look like" is how they come to disagree
 * about it on the one screen where somebody sees both.
 *
 * ## The timestamp is `occurredAt`, and `createdAt` sits beside it only when
 * they differ
 *
 * The contract's own reason: people record a Friday call on Monday, and a
 * communication audit that ordered by entry time would misreport every one of
 * them. So the prominent date is when the conversation happened. But *when it
 * was written down* is the fact a dispute turns on second — "we told them on
 * the 4th" is a weaker claim if it was typed on the 29th — so when the two
 * dates differ, the row says both rather than quietly picking one.
 */
export function CommunicationEntryRow({
  entry,
  context,
}: {
  entry: ObStepCommunication | ObClientCommunication
  /** The stitched view's "Product · 3. Step name" line. Omitted per-step. */
  context?: string
}) {
  const look = channelLook(entry.channel)
  const { Icon } = look
  const occurred = formatMoment(entry.occurredAt)
  const recorded = formatMoment(entry.createdAt)
  const wasBackdated = recorded != null && occurred !== recorded

  return (
    <li className="flex gap-3 py-3" data-testid="communication-entry">
      <span
        className={cn(
          'mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-full',
          entry.channel === 'ESCALATION' ? 'bg-level-critical-soft' : 'bg-subtle',
        )}
        aria-hidden="true"
      >
        <Icon
          className={cn(
            'h-4 w-4',
            entry.channel === 'ESCALATION' ? 'text-danger-text' : 'text-content-muted',
          )}
        />
      </span>

      <div className="flex min-w-0 flex-col gap-1">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
          <span className="text-sm font-medium text-content">{entryByline(entry)}</span>
          {/*
            Both states are named in words, never just tinted. §11 and CP-03,
            and `visibilityLabel`'s own note on why this is the one distinction
            on the timeline that cannot be undone by getting it wrong.
          */}
          <Chip variant={entry.isClientVisible ? 'info' : 'neutral'}>
            {visibilityLabel(entry.isClientVisible)}
          </Chip>
        </div>

        {context && <p className="m-0 text-caption text-content-muted">{context}</p>}

        <p className="m-0 whitespace-pre-wrap text-sm text-content">{entry.summary}</p>

        <p className="m-0 text-caption text-content-muted">
          {occurred}
          {wasBackdated && <span> · recorded {recorded}</span>}
        </p>
      </div>
    </li>
  )
}

const MOMENT_FORMAT = 'd MMM yyyy, HH:mm'

/** `null` rather than a placeholder — the caller decides what an absent date means. */
function formatMoment(value: string | null | undefined): string | null {
  if (!value) return null
  const parsed = parseISO(value)
  return Number.isNaN(parsed.getTime()) ? null : format(parsed, MOMENT_FORMAT)
}
