import { Bot, ShieldAlert } from 'lucide-react'
import type { AuditLogEntry } from '@/api/generated/model'
import {
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { cn } from '@/lib/utils'

import { actionLabel, isRefusal, moduleLabel } from './auditVocabulary'

/**
 * A-071 · the grid S-16 describes, and nothing else.
 *
 * <h2>Every cell is text; no cell is a control</h2>
 *
 * <p>There is no row menu, no edit affordance and no delete. That is not an
 * omission to be filled in later — the routes those controls would call do not
 * exist and never will, and `AppendOnlyRulesTest` fails the build if one is
 * added. A disabled edit button would be worse than none: it says the operation
 * exists and is merely unavailable to you.
 *
 * <h2>Times are shown as UTC, deliberately</h2>
 *
 * <p>The rest of the product renders in the user's timezone (PLAN.md §3.1), and
 * this screen does not. An audit extract is read alongside server logs, mail
 * headers and someone else's screenshot, all of which are UTC, and a table
 * silently shifted by five and a half hours is how two people conclude an event
 * happened twice. The column says so in its heading rather than leaving it to
 * be inferred.
 */
export function AuditLogTable({ entries }: { entries: AuditLogEntry[] }) {
  return (
    <TableContainer>
      <Table>
        <caption className="sr-only">
          Audit log entries, most recent first. This table is read-only.
        </caption>
        <TableHeader>
          <TableRow>
            <TableHead scope="col">When (UTC)</TableHead>
            <TableHead scope="col">Who</TableHead>
            <TableHead scope="col">Action</TableHead>
            <TableHead scope="col">Module</TableHead>
            <TableHead scope="col">Record</TableHead>
            <TableHead scope="col">Detail</TableHead>
            <TableHead scope="col">Origin</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {entries.map((entry) => (
            <TableRow
              key={entry.id}
              // A soft red left border, the same cue the ticket list uses for a
              // critical row. The audit log is mostly ordinary activity, and
              // without this the three lines somebody opened it for are the
              // same weight as the four hundred they scrolled past.
              className={cn(isRefusal(entry.action) && 'border-l-2 border-l-danger')}
            >
              <TableCell className="whitespace-nowrap font-mono text-caption">
                {formatUtc(entry.createdAt)}
              </TableCell>
              <TableCell className="whitespace-nowrap">
                <Actor entry={entry} />
              </TableCell>
              <TableCell className="whitespace-nowrap">
                <span className="inline-flex items-center gap-1.5">
                  {isRefusal(entry.action) && (
                    <ShieldAlert className="h-3.5 w-3.5 shrink-0 text-danger-text" aria-hidden />
                  )}
                  {actionLabel(entry.action)}
                </span>
              </TableCell>
              <TableCell className="whitespace-nowrap text-content-muted">
                {moduleLabel(entry.entityType)}
              </TableCell>
              <TableCell className="whitespace-nowrap font-mono text-caption">
                {entry.entityId ?? '—'}
              </TableCell>
              <TableCell className="max-w-[24rem] truncate text-content-muted" title={detailOf(entry)}>
                {detailOf(entry)}
              </TableCell>
              <TableCell className="whitespace-nowrap text-caption text-content-muted">
                {entry.ipAddress ?? '—'}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  )
}

/**
 * The three states of an actor, all of which are real.
 *
 * <p>No actor means SYSTEM — a scanner or the mail engine. An actor whose
 * account has since been removed arrives with a name the server built from the
 * id, because a row outlives its actor and rendering it as "System" would file
 * somebody's actions under nobody's.
 */
function Actor({ entry }: { entry: AuditLogEntry }) {
  if (!entry.actor) {
    return (
      <span className="inline-flex items-center gap-1.5 text-content-muted">
        <Bot className="h-3.5 w-3.5 shrink-0" aria-hidden />
        System
      </span>
    )
  }
  return (
    <span>
      {entry.actor.displayName}
      {entry.actor.role && (
        <span className="ml-1.5 text-caption text-content-muted">{entry.actor.role}</span>
      )}
    </span>
  )
}

/**
 * `detail` absent is "nothing was recorded", which is most rows — the
 * interceptor sees that a request happened, not what changed underneath it.
 * Rendered as a dash rather than as an empty cell so the column reads as
 * deliberately blank rather than as a value that failed to load.
 */
function detailOf(entry: AuditLogEntry): string {
  const detail = entry.detail as Record<string, unknown> | undefined
  if (!detail) return '—'
  const parts = Object.entries(detail).map(([key, value]) => `${key}: ${String(value)}`)
  return parts.length === 0 ? '—' : parts.join(' · ')
}

/**
 * `2026-08-18T09:15:00.123456Z` as `2026-08-18 09:15:00`.
 *
 * <p>String slicing rather than `Date`, on purpose: constructing a `Date` and
 * formatting it is what applies the browser's timezone, which is the one thing
 * this column must not do. The server sends ISO-8601 UTC and this only makes it
 * readable.
 */
function formatUtc(iso: string | undefined): string {
  if (!iso) return '—'
  const match = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2}:\d{2})/.exec(iso)
  return match ? `${match[1]} ${match[2]}` : iso
}
