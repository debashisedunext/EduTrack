import { AlertTriangle, ArrowRight, Check, X } from 'lucide-react'
import { Link } from 'react-router-dom'

import { Button } from '@/components/ui/button'
import {
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
} from '@/components/ui/modal'
import type { BulkUserStatusResponseData } from '@/api/generated/model/bulkUserStatusResponseData'
import type { BulkUserStatusOutcome } from '@/api/generated/model/bulkUserStatusOutcome'

import { reassignWizardHref } from './reassignHandoff'

export interface BulkStatusBarProps {
  selectedCount: number
  isPending: boolean
  onApply: (isActive: boolean) => void
  onClear: () => void
}

/**
 * B-010 · the selection bar over the S-07 grid.
 *
 * Deliberately two explicit buttons rather than one "Toggle status". A mixed
 * selection has no toggle — half of it would go each way — and a bulk action
 * whose effect depends on rows the user cannot all see at once is exactly the
 * kind that gets clicked by accident.
 */
export function BulkStatusBar({ selectedCount, isPending, onApply, onClear }: BulkStatusBarProps) {
  if (selectedCount === 0) return null

  return (
    <div
      role="region"
      aria-label="Bulk actions"
      className="flex flex-wrap items-center gap-3 rounded-control border border-primary bg-primary-soft px-4 py-2"
    >
      <span className="text-sm font-medium text-primary">
        {selectedCount} selected
      </span>
      <Button size="sm" variant="secondary" disabled={isPending} onClick={() => onApply(true)}>
        <Check className="h-4 w-4" />
        Activate
      </Button>
      <Button size="sm" variant="secondary" disabled={isPending} onClick={() => onApply(false)}>
        <X className="h-4 w-4" />
        Deactivate
      </Button>
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
// B-014 · the forcing function
// ---------------------------------------------------------------------------

/** Just enough of a grid row to decide and to explain. */
export interface DeactivationCandidate {
  id: number
  displayName: string
  openTicketCount: number
}

export interface DeactivationConfirmDialogProps {
  /**
   * The blocked members of the selection — the ones this dialog exists to name.
   * Null when nothing is pending confirmation.
   */
  blocked: readonly DeactivationCandidate[] | null
  /**
   * How many of the selection would still be deactivated.
   *
   * <b>Passed in rather than derived from `blocked`.</b> The selection can span
   * pages, and rows the admin ticked two pages back are not on screen for their
   * counts to be read — so this component is not in a position to work out what
   * "the rest" is, and a version that tried would quietly drop them.
   */
  proceedCount: number
  /** The origin page's query string, so the return trip lands on it. */
  returnSearch: string
  isPending: boolean
  /** Proceed with everyone except the blocked. */
  onConfirm: () => void
  onCancel: () => void
}

/**
 * B-014 · what stands in the way, said **before** the request rather than after.
 *
 * The grid already knows every row's open-ticket count — it is a column. Firing
 * a deactivation that is certain to come back partly refused, and only then
 * naming the people it refused, wastes a round trip to tell the admin something
 * that was on screen the whole time. Worse, it makes the refusal feel like a
 * failure of the click rather than a fact about the organisation.
 *
 * So a selection with nobody blocked goes straight through with **no dialog at
 * all** — the common case must not grow a confirmation step — and a selection
 * with somebody blocked stops here, names them, and offers the way through.
 *
 * The counts are a snapshot and can be stale: a colleague may close the last
 * ticket, or open a new one, between the page rendering and the button being
 * clicked. That is why this does not replace the server's guard or the result
 * dialog behind it. It is the fast path, not the authority.
 */
export function DeactivationConfirmDialog({
  blocked,
  proceedCount,
  returnSearch,
  isPending,
  onConfirm,
  onCancel,
}: DeactivationConfirmDialogProps) {
  // Nothing to warn about, so nothing to interrupt. The page does not open this
  // dialog for a clean selection, and this is the second half of that rule
  // rather than a duplicate of it: a caller who opened it anyway would otherwise
  // get a heading reading "0 of these still hold open tickets".
  if (!blocked || blocked.length === 0) return null

  return (
    <Modal
      open
      onOpenChange={(next) => {
        if (!next) onCancel()
      }}
    >
      <ModalContent>
        <ModalHeader>
          <ModalTitle>
            {blocked.length === 1
              ? `${blocked[0].displayName} still holds open tickets`
              : `${blocked.length} of these still hold open tickets`}
          </ModalTitle>
          <ModalDescription>
            Deactivating {blocked.length === 1 ? 'them' : 'those'} now would leave live work with
            nobody accountable for it. Reassign their tickets first
            {/* Only true when there is somebody else. A selection of one that
                promised the rest could go through would be describing a button
                the footer does not render. */}
            {proceedCount > 0
              ? ` — the other ${proceedCount} in the selection can be deactivated straight away.`
              : '.'}
          </ModalDescription>
        </ModalHeader>

        <ul className="max-h-72 space-y-1 overflow-y-auto text-sm">
          {blocked.map((candidate) => (
            <li key={candidate.id} className="flex items-center gap-2 py-1">
              <AlertTriangle className="h-4 w-4 shrink-0 text-warning-text" aria-hidden />
              <span className="text-content">{candidate.displayName}</span>
              <ReassignLink
                userId={candidate.id}
                displayName={candidate.displayName}
                openTicketCount={candidate.openTicketCount}
                returnSearch={returnSearch}
              />
            </li>
          ))}
        </ul>

        <ModalFooter>
          <Button variant="secondary" size="sm" onClick={onCancel}>
            Cancel
          </Button>
          {proceedCount > 0 && (
            <Button size="sm" disabled={isPending} onClick={onConfirm}>
              Deactivate the other {proceedCount}
            </Button>
          )}
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}

/**
 * The link into S-24, from wherever a blocked resource is named.
 *
 * One component rather than three call sites building the same href, because
 * the accessible name is the fiddly part: "Reassign →" repeated down a list
 * reads identically to a screen reader pulling up links, and the count belongs
 * in the label rather than only in the row it sits beside.
 */
function ReassignLink({
  userId,
  displayName,
  openTicketCount,
  returnSearch,
}: {
  userId: number
  displayName: string
  openTicketCount: number
  returnSearch: string
}) {
  return (
    <Link
      to={reassignWizardHref({ fromUserId: userId, returnSearch })}
      aria-label={`Reassign ${displayName}'s ${openTicketCount} open ${
        openTicketCount === 1 ? 'ticket' : 'tickets'
      }`}
      className="ml-auto inline-flex shrink-0 items-center gap-1 text-sm font-medium text-primary underline-offset-2 hover:underline"
    >
      Reassign {openTicketCount} {openTicketCount === 1 ? 'ticket' : 'tickets'}
      <ArrowRight className="h-3.5 w-3.5" aria-hidden />
    </Link>
  )
}

export interface BulkStatusResultDialogProps {
  result: BulkUserStatusResponseData | null
  isActivating: boolean
  /** The origin page's query string, so the return trip lands on it. */
  returnSearch: string
  onClose: () => void
}

const OUTCOME_ORDER: BulkUserStatusOutcome['outcome'][] = [
  'BLOCKED_OPEN_TICKETS',
  'NOT_FOUND',
  'CHANGED',
  'UNCHANGED',
]

/**
 * What actually happened, per resource.
 *
 * <p>Shown as a dialog rather than a toast whenever anything was blocked or
 * missing. A toast is right for "38 deactivated" and wrong for "38
 * deactivated, 2 refused" — the second is a list somebody has to act on, and a
 * notification that disappears after five seconds is not a list.
 *
 * <p><b>Blocked resources are the point of this dialog.</b> They are named,
 * their open-ticket count is shown, and the reassignment route is offered.
 * Deactivating them anyway would orphan live work, which is how tickets stop
 * being anybody's problem.
 *
 * <p><b>B-014 made "offered" literal.</b> Until now this dialog said the wizard
 * did not exist and suggested reassigning ticket by ticket, which for somebody
 * holding thirty is not advice anybody follows. Each blocked row now carries a
 * link into S-24 with that resource preselected and a return trip that resumes
 * the deactivation.
 *
 * <p>Reaching this dialog at all, rather than
 * {@link DeactivationConfirmDialog}, means the counts the grid held were wrong
 * by the time the request landed — somebody opened a ticket in the seconds in
 * between. Rare, and exactly why the server's guard is the authority and the
 * pre-flight check is only a courtesy.
 */
export function BulkStatusResultDialog({
  result,
  isActivating,
  returnSearch,
  onClose,
}: BulkStatusResultDialogProps) {
  if (!result) return null

  const ordered = [...result.results].sort(
    (a, b) => OUTCOME_ORDER.indexOf(a.outcome) - OUTCOME_ORDER.indexOf(b.outcome),
  )
  const verb = isActivating ? 'activated' : 'deactivated'

  return (
    <Modal
      open
      onOpenChange={(next) => {
        if (!next) onClose()
      }}
    >
      <ModalContent>
        <ModalHeader>
          <ModalTitle>
            {result.changed} {verb}
          </ModalTitle>
          <ModalDescription>{summarise(result)}</ModalDescription>
        </ModalHeader>

        <ul className="max-h-72 space-y-1 overflow-y-auto text-sm">
          {ordered.map((outcome) => (
            <li key={outcome.userId} className="flex items-start gap-2 py-1">
              <OutcomeIcon outcome={outcome.outcome} />
              <div className="flex flex-col">
                <span className="text-content">{outcome.displayName ?? `#${outcome.userId}`}</span>
                <span className="text-caption text-content-muted">
                  {describe(outcome, isActivating)}
                </span>
              </div>
              {outcome.outcome === 'BLOCKED_OPEN_TICKETS' && (
                <ReassignLink
                  userId={outcome.userId}
                  displayName={outcome.displayName ?? `resource #${outcome.userId}`}
                  openTicketCount={outcome.openTicketCount ?? 0}
                  returnSearch={returnSearch}
                />
              )}
            </li>
          ))}
        </ul>

        <ModalFooter>
          <Button size="sm" onClick={onClose}>
            Done
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}

function summarise(result: BulkUserStatusResponseData): string {
  const parts: string[] = []
  if (result.unchanged > 0) parts.push(`${result.unchanged} already in that state`)
  if (result.blocked > 0) parts.push(`${result.blocked} blocked by open tickets`)
  if (result.notFound > 0) parts.push(`${result.notFound} no longer exist`)
  return parts.length > 0 ? parts.join(' · ') : 'Every selected resource was updated.'
}

function describe(outcome: BulkUserStatusOutcome, isActivating: boolean): string {
  switch (outcome.outcome) {
    case 'CHANGED':
      return isActivating ? 'Activated' : 'Deactivated'
    case 'UNCHANGED':
      return isActivating ? 'Already active' : 'Already inactive'
    case 'BLOCKED_OPEN_TICKETS':
      // The count is in the link beside this line as well, and deliberately so:
      // this sentence explains the state, the link is the action, and a screen
      // reader moving by links needs the number in the link's own name.
      return `Left active — ${outcome.openTicketCount ?? 0} open ticket${
        outcome.openTicketCount === 1 ? '' : 's'
      }`
    case 'NOT_FOUND':
      return 'No longer exists'
    default:
      return ''
  }
}

function OutcomeIcon({ outcome }: { outcome: BulkUserStatusOutcome['outcome'] }) {
  if (outcome === 'BLOCKED_OPEN_TICKETS' || outcome === 'NOT_FOUND') {
    return <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warning-text" aria-hidden />
  }
  return <Check className="mt-0.5 h-4 w-4 shrink-0 text-success-text" aria-hidden />
}
