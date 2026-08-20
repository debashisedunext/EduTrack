import * as React from 'react'
import { AlertTriangle, ArrowRightLeft, Check, CheckCircle2, Flag } from 'lucide-react'

import type { Level } from '@/api/generated/model/level'
import type { User } from '@/api/generated/model/user'
import type { BulkResultResponseData } from '@/api/generated/model/bulkResultResponseData'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { SearchableDropdown } from '@/components/ui/searchable-dropdown'
import {
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
} from '@/components/ui/modal'

import { levelLabel } from '../columns'
import { failedResults, summariseBulkResult } from './bulkActions'

const TEXTAREA_CLASS =
  'w-full rounded-control border border-border bg-surface px-3 py-2 text-sm text-content placeholder:text-content-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-1'

/** The minimum every `reason` and `resolutionSummary` field on the wire enforces. */
const MIN_REASON = 3

export type BulkAction = 'reassign' | 'level' | 'close'

export interface TicketBulkActionBarProps {
  selectedCount: number
  /** How many of the selection a close would skip, from `alreadyClosedCount`. */
  closedInSelection: number
  isPending: boolean
  onOpen: (action: BulkAction) => void
  onClear: () => void
}

/**
 * C-017 · the selection bar over the S-17 grid — **PM and Admin only**.
 *
 * The page decides whether to render this at all (`canBulkAct`); this component
 * assumes the decision has been made and does not re-check, the same split
 * `BulkStatusBar` uses on S-07. What it does own is the *shape* of the offer:
 * three named actions rather than a "Bulk actions" menu, because a menu hides
 * how destructive the third item is behind a click, and Close is not something
 * to discover by opening a dropdown over fifty ticked rows.
 *
 * Every action opens a dialog. None of them fires on the button press — each of
 * the three needs at least one field that cannot be defaulted (a target, a
 * level, a resolution), and a bulk operation with nothing to type is a bulk
 * operation somebody performs by mis-clicking.
 */
export function TicketBulkActionBar({
  selectedCount,
  closedInSelection,
  isPending,
  onOpen,
  onClear,
}: TicketBulkActionBarProps) {
  if (selectedCount === 0) return null

  const closable = selectedCount - closedInSelection

  return (
    <div
      role="region"
      aria-label="Bulk ticket actions"
      className="flex flex-wrap items-center gap-3 rounded-control border border-primary bg-primary-soft px-4 py-2"
    >
      <span className="text-sm font-medium text-primary">{selectedCount} selected</span>
      <Button size="sm" variant="secondary" disabled={isPending} onClick={() => onOpen('reassign')}>
        <ArrowRightLeft className="h-4 w-4" />
        Reassign
      </Button>
      <Button size="sm" variant="secondary" disabled={isPending} onClick={() => onOpen('level')}>
        <Flag className="h-4 w-4" />
        Change level
      </Button>
      <Button
        size="sm"
        variant="secondary"
        // Disabled rather than hidden when every ticked row is already closed:
        // a button that vanishes as the selection changes is harder to aim at
        // than one that greys out and explains itself.
        disabled={isPending || closable === 0}
        title={closable === 0 ? 'Every selected ticket is already closed' : undefined}
        onClick={() => onOpen('close')}
      >
        <CheckCircle2 className="h-4 w-4" />
        Close
      </Button>

      {closedInSelection > 0 && (
        <span className="flex items-center gap-1.5 text-caption text-content-muted">
          <AlertTriangle className="h-3.5 w-3.5 text-warning-text" aria-hidden />
          {closedInSelection} already closed
        </span>
      )}

      <button
        type="button"
        onClick={onClear}
        className="ml-auto text-sm text-primary underline-offset-2 hover:underline"
      >
        Clear selection
      </button>
    </div>
  )
}

// ---------------------------------------------------------------------------
// the three dialogs
// ---------------------------------------------------------------------------

interface DialogShellProps {
  title: string
  description: React.ReactNode
  confirmLabel: string
  confirmDisabled: boolean
  isPending: boolean
  error?: string | null
  onConfirm: () => void
  onCancel: () => void
  children: React.ReactNode
}

function DialogShell({
  title,
  description,
  confirmLabel,
  confirmDisabled,
  isPending,
  error,
  onConfirm,
  onCancel,
  children,
}: DialogShellProps) {
  return (
    <Modal
      open
      onOpenChange={(next) => {
        // A request in flight is not cancellable — closing the dialog would
        // hide a batch that is still being applied, and the result list is the
        // only place its refusals are ever named.
        if (!next && !isPending) onCancel()
      }}
    >
      <ModalContent>
        <form
          onSubmit={(e) => {
            e.preventDefault()
            if (!confirmDisabled && !isPending) onConfirm()
          }}
        >
          <ModalHeader>
            <ModalTitle>{title}</ModalTitle>
            <ModalDescription>{description}</ModalDescription>
          </ModalHeader>

          <div className="flex flex-col gap-4">{children}</div>

          {error && (
            <p role="alert" className="mt-4 text-sm text-danger-text">
              {error}
            </p>
          )}

          <ModalFooter>
            <Button
              type="button"
              variant="secondary"
              size="sm"
              disabled={isPending}
              onClick={onCancel}
            >
              Cancel
            </Button>
            <Button type="submit" size="sm" disabled={confirmDisabled || isPending}>
              {confirmLabel}
            </Button>
          </ModalFooter>
        </form>
      </ModalContent>
    </Modal>
  )
}

/**
 * The reason field, shared by reassign and change-level.
 *
 * **Mandatory in both**, which is stricter than the single-ticket endpoints —
 * `PATCH /tickets/{id}/priority` only requires one once the ticket is assigned.
 * A selection spans assigned and unassigned rows freely, so making it
 * conditional would mean the caller has to know which is which before deciding
 * whether to type; and the reason is the only surviving record of why fifty
 * levels moved at once.
 */
function ReasonField({
  id,
  label,
  hint,
  value,
  onChange,
}: {
  id: string
  label: string
  hint: string
  value: string
  onChange: (value: string) => void
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-medium text-content">
        {label}
      </label>
      <textarea
        id={id}
        rows={3}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className={TEXTAREA_CLASS}
        placeholder={hint}
        // Not `required`: the Confirm button already reflects the same rule, and
        // a native validation bubble on a field the button has greyed out for
        // says the same thing twice in two different registers.
        aria-describedby={`${id}-hint`}
      />
      <p id={`${id}-hint`} className="text-caption text-content-muted">
        Written to every selected ticket&rsquo;s history, one entry each.
      </p>
    </div>
  )
}

export interface BulkReassignDialogProps {
  selectedCount: number
  members: User[]
  isPending: boolean
  error?: string | null
  onConfirm: (input: { toUserId: number; reason: string }) => void
  onCancel: () => void
}

export function BulkReassignDialog({
  selectedCount,
  members,
  isPending,
  error,
  onConfirm,
  onCancel,
}: BulkReassignDialogProps) {
  const [target, setTarget] = React.useState<User | null>(null)
  const [reason, setReason] = React.useState('')
  const ready = target != null && reason.trim().length >= MIN_REASON

  return (
    <DialogShell
      title={`Reassign ${selectedCount} ${selectedCount === 1 ? 'ticket' : 'tickets'}`}
      description="Each ticket keeps its stage. Reassignment inside a stage does not advance the ribbon — it writes its own history entry per ticket and splits effort attribution so both resources still appear in the journey roll-up."
      confirmLabel={`Reassign ${selectedCount}`}
      confirmDisabled={!ready}
      isPending={isPending}
      error={error}
      onConfirm={() => {
        if (target) onConfirm({ toUserId: target.id, reason: reason.trim() })
      }}
      onCancel={onCancel}
    >
      <div className="flex flex-col gap-1.5">
        <label htmlFor="bulk-reassign-target" className="text-sm font-medium text-content">
          Reassign to
        </label>
        <SearchableDropdown
          id="bulk-reassign-target"
          options={members}
          value={target}
          onChange={setTarget}
          getKey={(u) => String(u.id)}
          getLabel={(u) => u.displayName}
          getSearchable={(u) => [u.email ?? '']}
          placeholder="Choose a resource…"
          emptyText="No resources match"
        />
      </div>
      <ReasonField
        id="bulk-reassign-reason"
        label="Reason"
        hint="Why is this batch moving?"
        value={reason}
        onChange={setReason}
      />
    </DialogShell>
  )
}

export interface BulkLevelDialogProps {
  selectedCount: number
  levels: Level[]
  isPending: boolean
  error?: string | null
  onConfirm: (input: { level: Level; reason: string }) => void
  onCancel: () => void
}

export function BulkLevelDialog({
  selectedCount,
  levels,
  isPending,
  error,
  onConfirm,
  onCancel,
}: BulkLevelDialogProps) {
  const [level, setLevel] = React.useState<Level | null>(null)
  const [reason, setReason] = React.useState('')
  const ready = level != null && reason.trim().length >= MIN_REASON

  return (
    <DialogShell
      title={`Change the level of ${selectedCount} ${selectedCount === 1 ? 'ticket' : 'tickets'}`}
      description="Each planned close date is recomputed against the working calendar from that ticket's own start. The level a ticket was born at is never overwritten, so born-critical stays distinguishable from became-critical."
      confirmLabel={level ? `Set ${levelLabel(level)}` : 'Set level'}
      confirmDisabled={!ready}
      isPending={isPending}
      error={error}
      onConfirm={() => {
        if (level) onConfirm({ level, reason: reason.trim() })
      }}
      onCancel={onCancel}
    >
      <fieldset className="flex flex-col gap-1.5">
        <legend className="mb-1.5 text-sm font-medium text-content">New level</legend>
        <div className="flex flex-wrap gap-2">
          {levels.map((option) => (
            <label
              key={option}
              className="flex cursor-pointer items-center gap-2 rounded-control border border-border px-3 py-1.5 text-sm text-content has-[:checked]:border-primary has-[:checked]:bg-primary-soft has-[:checked]:text-primary"
            >
              <input
                type="radio"
                name="bulk-level"
                value={option}
                checked={level === option}
                onChange={() => setLevel(option)}
                className="h-4 w-4 accent-primary"
              />
              {levelLabel(option)}
            </label>
          ))}
        </div>
      </fieldset>
      <ReasonField
        id="bulk-level-reason"
        label="Reason"
        hint="Why is this batch changing level?"
        value={reason}
        onChange={setReason}
      />
    </DialogShell>
  )
}

export interface BulkCloseDialogProps {
  /** How many will actually be sent — the selection minus what is already closed. */
  closableCount: number
  alreadyClosed: number
  isPending: boolean
  error?: string | null
  onConfirm: (input: { resolutionSummary: string; rootCauseCategory?: string }) => void
  onCancel: () => void
}

export function BulkCloseDialog({
  closableCount,
  alreadyClosed,
  isPending,
  error,
  onConfirm,
  onCancel,
}: BulkCloseDialogProps) {
  const [summary, setSummary] = React.useState('')
  const [rootCause, setRootCause] = React.useState('')
  const ready = summary.trim().length >= MIN_REASON

  return (
    <DialogShell
      title={`Close ${closableCount} ${closableCount === 1 ? 'ticket' : 'tickets'}`}
      description={
        <>
          Each ticket is closed as its own transaction — the close date is stamped, the open stage
          is sealed and a history entry is written, per ticket. One refusing does not roll back the
          rest.
          {alreadyClosed > 0 && (
            <>
              {' '}
              <strong className="text-content">
                {alreadyClosed} of the selection {alreadyClosed === 1 ? 'is' : 'are'} already closed
                and will be left alone
              </strong>{' '}
              — re-stamping a close date would move something reports already depend on.
            </>
          )}
        </>
      }
      confirmLabel={`Close ${closableCount}`}
      confirmDisabled={!ready}
      isPending={isPending}
      error={error}
      onConfirm={() =>
        onConfirm({
          resolutionSummary: summary.trim(),
          rootCauseCategory: rootCause.trim() || undefined,
        })
      }
      onCancel={onCancel}
    >
      <div className="flex flex-col gap-1.5">
        <label htmlFor="bulk-close-summary" className="text-sm font-medium text-content">
          Resolution summary
        </label>
        <textarea
          id="bulk-close-summary"
          rows={4}
          value={summary}
          onChange={(e) => setSummary(e.target.value)}
          className={TEXTAREA_CLASS}
          placeholder="What resolved this batch?"
          aria-describedby="bulk-close-summary-hint"
        />
        <p id="bulk-close-summary-hint" className="text-caption text-content-muted">
          One summary for the batch — a bulk close is one decision, and fifty separate boxes would
          produce fifty copies of the same sentence.
        </p>
      </div>
      <div className="flex flex-col gap-1.5">
        <label htmlFor="bulk-close-root-cause" className="text-sm font-medium text-content">
          Root cause category <span className="text-content-muted">(optional)</span>
        </label>
        <Input
          id="bulk-close-root-cause"
          value={rootCause}
          onChange={(e) => setRootCause(e.target.value)}
          maxLength={100}
          placeholder="e.g. Configuration"
        />
      </div>
    </DialogShell>
  )
}

// ---------------------------------------------------------------------------
// the result
// ---------------------------------------------------------------------------

export interface BulkResultDialogProps {
  result: BulkResultResponseData | null
  /** Past participle — "reassigned", "updated", "closed". */
  verb: string
  onClose: () => void
}

/**
 * What actually happened, per ticket — and shown **only when something was
 * refused**.
 *
 * A dialog for "38 closed" would be a modal whose entire content the user
 * already knows, dismissed without reading. A dialog for "38 closed, 2 refused"
 * is a list somebody has to act on, and those two rows are unreachable in any
 * other way once the grid refetches and they blend back into it. So a clean
 * result renders nothing and lets the grid refresh speak for itself.
 */
export function BulkResultDialog({ result, verb, onClose }: BulkResultDialogProps) {
  if (!result) return null
  const failures = failedResults(result)
  if (failures.length === 0) return null

  return (
    <Modal
      open
      onOpenChange={(next) => {
        if (!next) onClose()
      }}
    >
      <ModalContent>
        <ModalHeader>
          <ModalTitle>{summariseBulkResult(result, verb)}</ModalTitle>
          <ModalDescription>
            These stayed as they were. They are still selected, so you can act on them once the
            reason no longer applies.
          </ModalDescription>
        </ModalHeader>

        <ul className="max-h-72 space-y-1 overflow-y-auto text-sm">
          {failures.map((row) => (
            <li key={row.ticketId} className="flex items-start gap-2 py-1">
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warning-text" aria-hidden />
              <span className="font-mono text-content">{row.ticketId}</span>
              <span className="text-content-muted">{row.reason ?? 'Refused'}</span>
            </li>
          ))}
        </ul>

        <ModalFooter>
          <Button size="sm" onClick={onClose}>
            <Check className="h-4 w-4" />
            Done
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}
