import {
  ArrowRightCircle,
  CheckCircle2,
  FilePlus,
  type LucideIcon,
  MessageSquare,
  RefreshCw,
  RotateCcw,
  TrendingUp,
  Undo2,
} from 'lucide-react'

import type { HistoryEntry } from '@/api/generated/model/historyEntry'
import { cn } from '@/lib/utils'

import { describeHistoryEntry } from './historyEntryText'

const ICON_BY_ACTION: Record<string, LucideIcon> = {
  CREATED: FilePlus,
  CLOSED: CheckCircle2,
  REOPENED: RotateCcw,
  STATUS_CHANGED: RefreshCw,
  LEVEL_CHANGED: TrendingUp,
  STAGE_ADVANCED: ArrowRightCircle,
  REWORK: Undo2,
  COMMENTED: MessageSquare,
}

/**
 * One line of the interleaved stream — blueprint §7's sample transcript:
 * `06 Aug 14:25  ➡ Handoff  Development → QA   Ravi Kumar → Anil Sharma`.
 *
 * A `COMMENTED` row (`include=comments`) renders its `note` as the comment
 * body rather than through `describeHistoryEntry`'s generic sentence, and gets
 * a quoted, indented treatment — the one row in the stream that is prose
 * someone wrote rather than a fact the server recorded about a field.
 *
 * No edit or delete affordance is drawn here, and none ever will be — the
 * component has nothing to call even if it wanted one. `TicketHistoryController`
 * registers no `PUT`/`PATCH`/`DELETE` for this path (CLAUDE.md's append-only
 * rule), so there is no mutation this row could offer that the server would
 * accept.
 */
export function HistoryEntryRow({ entry }: { entry: HistoryEntry }) {
  const isComment = entry.action === 'COMMENTED'
  const Icon: LucideIcon = (entry.action ? ICON_BY_ACTION[entry.action] : undefined) ?? RefreshCw

  return (
    <li className="flex gap-3 py-2">
      <Icon aria-hidden="true" className="mt-0.5 h-4 w-4 shrink-0 text-content-muted" />
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        {isComment ? (
          <p className="whitespace-pre-wrap border-l-2 border-border pl-2 text-body text-content">
            {entry.note}
          </p>
        ) : (
          <p className={cn('text-body text-content', entry.isCorrection && 'italic text-content-muted')}>
            {describeHistoryEntry(entry)}
            {entry.isCorrection && <span className="ml-1 text-caption">(correction)</span>}
          </p>
        )}
        <p className="text-caption text-content-muted">
          {actorLabel(entry)} · {formatWhen(entry.createdAt)}
        </p>
      </div>
    </li>
  )
}

/** `"System"` for an escalation or a scanner — never a name nobody wrote. */
function actorLabel(entry: HistoryEntry): string {
  if (entry.actor?.displayName) return entry.actor.displayName
  if (entry.actorType === 'SYSTEM') return 'System'
  return 'Unknown'
}

/**
 * The reader's local time, from the UTC the server stores — `CommentCard.formatPosted`'s
 * identical rule, repeated here rather than imported: two features each
 * declaring the format they need is this codebase's own precedent
 * (`CommentDtos.UserRef`'s note) for why a shared helper gets renegotiated the
 * first time one caller wants a different one.
 */
function formatWhen(iso: string | undefined): string {
  if (!iso) return ''
  const at = new Date(iso)
  if (Number.isNaN(at.getTime())) return ''
  return at.toLocaleString(undefined, {
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  })
}
