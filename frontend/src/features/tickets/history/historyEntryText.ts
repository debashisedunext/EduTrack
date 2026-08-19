import type { HistoryEntry } from '@/api/generated/model/historyEntry'

/**
 * C-059 · turns one `HistoryEntry` into the sentence blueprint §7 shows —
 * `"Level changed High → Critical by System (SLA breach) on 13 Aug 10:15"`,
 * `"Handed off Development → QA by Ravi K. to Anil S. on 03 Aug 14:20"`.
 *
 * The actor/date half of that sentence is `HistoryEntryRow`'s job — this file
 * is the field-change half, kept pure and separately testable because it is
 * the part with real branching (which field changed, whether a destination
 * value exists) and no DOM.
 *
 * ## Why `STAGE_ADVANCED`/`REWORK` do not always print `"X → Y"`
 *
 * The blueprint's own handoff sample names both stages, but a real
 * `ticket_history` row only ever carries the stage that was **left**
 * (`oldValue`) — the row is stamped from the hop that just closed, before
 * `TicketJournal` knows which stage the next hop will open in. Printing an
 * invented destination would be worse than a shorter sentence that is true:
 * `describeHistoryEntry` renders `"X → Y"` only when the server actually sent
 * a `newValue`, and falls back to naming only the stage that changed.
 */
export function describeHistoryEntry(entry: HistoryEntry): string {
  switch (entry.action) {
    case 'CREATED':
      return 'Ticket created'
    case 'CLOSED':
      return 'Ticket closed'
    case 'REOPENED':
      return entry.note ? `Reopened — ${entry.note}` : 'Reopened'
    case 'STATUS_CHANGED':
      return describeChange('Status', entry)
    case 'LEVEL_CHANGED':
      return describeChange('Level', entry)
    case 'REASSIGNED':
      return describeChange('Assignee', entry)
    case 'STAGE_REASSIGNED':
      return 'Reassigned within the stage'
    case 'STAGE_ADVANCED':
      return entry.newValue
        ? `Moved on — ${entry.oldValue ?? '—'} → ${entry.newValue}`
        : `Moved on from ${entry.oldValue ?? 'the previous stage'}`
    case 'REWORK':
      return entry.newValue
        ? `Sent back — ${entry.oldValue ?? '—'} → ${entry.newValue}`
        : `Sent back from ${entry.oldValue ?? 'the previous stage'}`
    case 'FIELD_CHANGED':
      return describeChange(fieldLabel(entry.fieldName), entry)
    case 'COMMENTED':
      // Rendered as the comment's own body — HistoryEntryRow draws this
      // differently from every other row, so this branch exists only so an
      // exhaustive caller (a test, a future export) still gets a sentence.
      return entry.note ?? 'Commented'
    default:
      return describeChange(entry.action ?? 'Changed', entry)
  }
}

/** `"Label changed X → Y"`, degrading gracefully as either side goes missing. */
function describeChange(label: string, entry: HistoryEntry): string {
  const { oldValue, newValue } = entry
  if (oldValue != null && newValue != null) return `${label} changed ${oldValue} → ${newValue}`
  if (newValue != null) return `${label} set to ${newValue}`
  if (oldValue != null) return `${label} changed from ${oldValue}`
  return `${label} changed`
}

/**
 * `pctComplete` → `"Percent complete"`. Only the field names
 * `QuickUpdateService` actually writes today are known; anything else falls
 * back to the raw column name rather than guessing a label that might be
 * wrong.
 */
function fieldLabel(fieldName: string | null | undefined): string {
  switch (fieldName) {
    case 'pctComplete':
      return 'Percent complete'
    case 'plannedCloseDate':
      return 'Planned close date'
    case 'assigneeId':
      return 'Assignee'
    default:
      return fieldName ?? 'Field'
  }
}
