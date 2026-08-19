import * as React from 'react'
import { Link } from 'react-router-dom'
import { Plus, X } from 'lucide-react'

import { useCreateTicketLink, useDeleteTicketLink, useListTickets } from '@/api/generated/tickets/tickets'
import type { LinkedTicket } from '@/api/generated/model/linkedTicket'
import type { TicketLinkType } from '@/api/generated/model/ticketLinkType'
import type { TicketSummary } from '@/api/generated/model/ticketSummary'
import { ApiError } from '@/api/http'

import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { Modal, ModalContent, ModalFooter, ModalHeader, ModalTitle } from '@/components/ui/modal'
import { toast } from '@/components/ui/use-toast'

import { LEVEL_VARIANT } from '../list/columns'
import { ticketPath } from './entityLinks'

export interface TicketLinksControlProps {
  ticketId: string
  links?: LinkedTicket[]
  onChanged: () => void
}

/** The four names §7.5's create-form row offers — blueprint §16 item 17. */
const SUBMITTABLE_TYPES: { value: TicketLinkType; label: string }[] = [
  { value: 'BLOCKS', label: 'Blocks' },
  { value: 'BLOCKED_BY', label: 'Is blocked by' },
  { value: 'DUPLICATE_OF', label: 'Duplicate of' },
  { value: 'RELATES_TO', label: 'Relates to' },
]

/**
 * Every value `linkType` can carry on a row, including the two that a caller
 * can never submit: `DUPLICATED_BY` only ever arrives as a computed label on
 * the *original* side of a `DUPLICATE_OF` link — see the contract's
 * `TicketLinkType`.
 */
const LINK_LABEL: Record<string, string> = {
  BLOCKS: 'Blocks',
  BLOCKED_BY: 'Is blocked by',
  DUPLICATE_OF: 'Duplicate of',
  DUPLICATED_BY: 'Duplicated by',
  RELATES_TO: 'Relates to',
}

const DIALOG_ID = 'ticket-links-add'

function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = React.useState(value)
  React.useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs)
    return () => clearTimeout(timer)
  }, [value, delayMs])
  return debounced
}

/**
 * C-064 · the "Linked tickets" row of S-20's summary panel — blueprint §16
 * item 17 and §7.5's create-form row, and the traceability rule C-019 already
 * states for every other entity here: "linked ticket → that ticket".
 *
 * ## Why this is not gated on the sealed-cycle rule beside it
 *
 * `TicketLevelControl` refuses to edit on an earlier cycle because a level is
 * a fact about the *current* cycle's clock. A link is not cycle-scoped at
 * all — `ticket_links` carries no `cycle_no` — so there is no "which cycle"
 * question for viewing a sealed history to raise, and the add/remove
 * affordances stay live regardless of which cycle the page is showing.
 *
 * ## One combined mutation object per action, not one per row
 *
 * `useDeleteTicketLink()` is called once; `deleteMutation.variables?.linkId`
 * is compared against each row to decide which one is spinning. A hook per
 * row would work too, but this list is never long enough (a handful of
 * relationships, not a table) for the difference to matter, and one mutation
 * object is one place to read `isPending`/`isError` from rather than an array
 * of them.
 *
 * ## The dropped `Idempotency-Key`, and why it matters less here
 *
 * `createTicketLink` is a `201`-returning create and CONVENTIONS.md §4 asks
 * every one of those for the header — but orval drops header parameters, the
 * same gap C-010 found on `useCreateTicket`. It matters less here: retrying
 * an identical link request does not mint a second, indistinguishable row
 * the way retrying a ticket creation mints a second ticket — it collides with
 * `uq_ticket_links` and answers `409`, so a lost-response retry surfaces as a
 * conflict a user can dismiss rather than as a silent duplicate. Flagged
 * rather than worked around with a hand-rolled key, on that reasoning.
 */
export function TicketLinksControl({ ticketId, links, onChanged }: TicketLinksControlProps) {
  const [open, setOpen] = React.useState(false)
  const [type, setType] = React.useState<TicketLinkType>('RELATES_TO')
  const [query, setQuery] = React.useState('')
  const [target, setTarget] = React.useState<TicketSummary | null>(null)
  const debouncedQuery = useDebouncedValue(query, 250)

  const createMutation = useCreateTicketLink()
  const deleteMutation = useDeleteTicketLink()

  const searchEnabled = open && !target && debouncedQuery.trim().length >= 2
  const { data: searchData, isFetching: isSearching } = useListTickets(
    { q: debouncedQuery.trim(), limit: 8 },
    { query: { enabled: searchEnabled } },
  )
  // The path ticket cannot link to itself — filtered client-side so it is
  // never offered, though `createTicketLink` refuses it either way.
  const results = (searchData?.data ?? []).filter((t) => t.ticketId !== ticketId)

  const reset = React.useCallback(() => {
    setType('RELATES_TO')
    setQuery('')
    setTarget(null)
    createMutation.reset()
  }, [createMutation])

  const onOpenChange = (next: boolean) => {
    if (next) reset()
    setOpen(next)
  }

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    if (!target) return

    createMutation.mutate(
      { ticketId, data: { targetTicketId: target.ticketId, linkType: type } },
      {
        onSuccess: () => {
          setOpen(false)
          onChanged()
          toast({
            title: 'Ticket linked',
            description: `${LINK_LABEL[type]} ${target.ticketId}`,
          })
        },
      },
    )
  }

  const remove = (link: LinkedTicket) => {
    deleteMutation.mutate(
      { ticketId, linkId: link.id },
      {
        onSuccess: () => {
          onChanged()
          toast({ title: 'Link removed', description: `${LINK_LABEL[link.linkType]} ${link.ticket.ticketId}` })
        },
      },
    )
  }

  return (
    <span className="flex flex-wrap items-center gap-1.5">
      {links?.length ? (
        <span className="flex flex-wrap items-center gap-1.5">
          {links.map((link) => {
            const removing = deleteMutation.isPending && deleteMutation.variables?.linkId === link.id
            return (
              <Chip key={link.id} variant="neutral" className="gap-1 pr-1">
                <span className="text-content-muted">{LINK_LABEL[link.linkType] ?? link.linkType}</span>
                <Link
                  to={ticketPath(link.ticket.ticketId)}
                  className="rounded-[2px] font-medium text-primary hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-1"
                >
                  {link.ticket.ticketId}
                </Link>
                <button
                  type="button"
                  onClick={() => remove(link)}
                  disabled={removing}
                  aria-label={`Remove link: ${LINK_LABEL[link.linkType] ?? link.linkType} ${link.ticket.ticketId}`}
                  className="ml-0.5 rounded-full p-0.5 text-content-muted hover:bg-subtle hover:text-danger-text focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary disabled:opacity-50"
                >
                  <X className="h-3 w-3" aria-hidden="true" />
                </button>
              </Chip>
            )
          })}
        </span>
      ) : (
        <span className="text-content-muted">None</span>
      )}

      <button
        type="button"
        onClick={() => onOpenChange(true)}
        aria-haspopup="dialog"
        className="inline-flex items-center gap-1 rounded-control px-1.5 py-0.5 text-caption text-content-muted hover:bg-subtle hover:text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
      >
        <Plus className="h-3 w-3" aria-hidden="true" />
        Add link
      </button>

      <Modal open={open} onOpenChange={onOpenChange}>
        <ModalContent aria-describedby={undefined}>
          <ModalHeader>
            <ModalTitle>Link this ticket to another</ModalTitle>
          </ModalHeader>

          <form onSubmit={submit} className="flex flex-col gap-4">
            <div className="flex flex-col gap-1.5">
              <label htmlFor={`${DIALOG_ID}-type`} className="text-caption text-content-muted">
                Relationship
              </label>
              <select
                id={`${DIALOG_ID}-type`}
                value={type}
                onChange={(e) => setType(e.target.value as TicketLinkType)}
                disabled={createMutation.isPending}
                className="w-full rounded-control border border-border bg-surface px-3 py-2 text-sm text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
              >
                {SUBMITTABLE_TYPES.map((t) => (
                  <option key={t.value} value={t.value}>
                    {t.label}
                  </option>
                ))}
              </select>
            </div>

            <div className="flex flex-col gap-1.5">
              <label htmlFor={`${DIALOG_ID}-target`} className="text-caption text-content-muted">
                Ticket
              </label>
              {target ? (
                <span className="flex items-center justify-between rounded-control border border-border bg-subtle px-3 py-2 text-sm">
                  <span className="flex min-w-0 items-center gap-2">
                    <Chip variant={LEVEL_VARIANT[target.level]}>{target.level}</Chip>
                    <span className="truncate">
                      {target.ticketId} — {target.title}
                    </span>
                  </span>
                  <button
                    type="button"
                    onClick={() => setTarget(null)}
                    disabled={createMutation.isPending}
                    aria-label="Change ticket"
                    className="shrink-0 rounded-full p-0.5 text-content-muted hover:bg-surface focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                  >
                    <X className="h-3.5 w-3.5" aria-hidden="true" />
                  </button>
                </span>
              ) : (
                <>
                  <input
                    id={`${DIALOG_ID}-target`}
                    type="text"
                    value={query}
                    onChange={(e) => setQuery(e.target.value)}
                    placeholder="Search by ID or title…"
                    autoComplete="off"
                    disabled={createMutation.isPending}
                    className="w-full rounded-control border border-border bg-surface px-3 py-2 text-sm text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                  />
                  {debouncedQuery.trim().length >= 2 && (
                    <ul className="max-h-48 overflow-y-auto rounded-control border border-border">
                      {isSearching ? (
                        <li className="px-3 py-2 text-caption text-content-muted">Searching…</li>
                      ) : results.length === 0 ? (
                        <li className="px-3 py-2 text-caption text-content-muted">No matching tickets</li>
                      ) : (
                        results.map((t) => (
                          <li key={t.ticketId}>
                            <button
                              type="button"
                              onClick={() => {
                                setTarget(t)
                                setQuery('')
                              }}
                              className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm hover:bg-subtle focus-visible:outline-none focus-visible:bg-subtle"
                            >
                              <Chip variant={LEVEL_VARIANT[t.level]}>{t.level}</Chip>
                              <span className="min-w-0 flex-1 truncate">
                                {t.ticketId} — {t.title}
                              </span>
                            </button>
                          </li>
                        ))
                      )}
                    </ul>
                  )}
                </>
              )}
            </div>

            {createMutation.isError && (
              <p role="alert" className="text-caption text-danger-text">
                {createMutation.error instanceof ApiError
                  ? createMutation.error.message
                  : 'This link could not be created. Please try again.'}
              </p>
            )}

            <ModalFooter>
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={() => onOpenChange(false)}
                disabled={createMutation.isPending}
              >
                Cancel
              </Button>
              <Button type="submit" size="sm" disabled={!target || createMutation.isPending}>
                {createMutation.isPending ? 'Linking…' : 'Link'}
              </Button>
            </ModalFooter>
          </form>
        </ModalContent>
      </Modal>
    </span>
  )
}
