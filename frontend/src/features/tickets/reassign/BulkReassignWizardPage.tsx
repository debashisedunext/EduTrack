import * as React from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { AlertTriangle, ArrowLeft, ArrowRight, Check, Loader2 } from 'lucide-react'

import { useListUsers } from '@/api/generated/users/users'
import { useListTickets } from '@/api/generated/tickets/tickets'
import { useGetMe } from '@/api/generated/auth/auth'
import { ApiError, newIdempotencyKey } from '@/api/http'

import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { Skeleton } from '@/components/ui/skeleton'
import { EmptyState } from '@/components/ui/empty-state'
import { SearchableDropdown } from '@/components/ui/searchable-dropdown'
import { TableContainer, Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '@/components/ui/table'
import { cn } from '@/lib/utils'

import { LEVEL_VARIANT, STATUS_VARIANT, STATUS_LABEL } from '../list/columns'
import { failedResults, summariseBulkResult } from '../list/bulk/bulkActions'
import { useBulkReassign } from '../list/bulk/useBulkTicketActions'
import { parseWizardHandoff, wizardDestination } from './bulkReassignWizard'

const TEXTAREA_CLASS =
  'w-full rounded-control border border-border bg-surface px-3 py-2 text-sm text-content placeholder:text-content-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-1'

const MIN_REASON = 3

const STEPS = [
  { title: 'Source resource', detail: 'Who is leaving, or going on leave' },
  { title: 'Tickets', detail: 'Their open tickets — all, or a chosen few' },
  { title: 'Target & reason', detail: 'Who receives them, and why' },
  { title: 'Confirm', detail: 'One move per ticket, each its own history row' },
] as const

/**
 * S-24 Bulk Reassignment Wizard — C-063.
 *
 * Blueprint §7.5: "used when a resource leaves or goes on leave; pick source
 * resource → select tickets → target resource → reason → confirm. Each move
 * writes its own history entry." The four rail steps above are that sentence,
 * named on screen — `ClientImportPage`'s own convention for a wizard whose
 * steps are worth knowing before starting.
 *
 * <h2>Two entry points, one screen</h2>
 *
 * The Resource Master (B-014) sends an admin here with `?fromUserId=&returnTo=`
 * when a deactivation is blocked by open tickets — `reassignHandoff.ts` is the
 * contract, and `parseWizardHandoff` reads it. A caller who arrives with
 * neither still gets a working wizard: it opens on step 1 empty, and "Done"
 * falls back to the tickets list rather than nowhere. That degrade-gracefully
 * shape is deliberate on both sides of the handoff, per its own javadoc.
 *
 * <h2>Reuses S-17's endpoint, not S-17's dialog</h2>
 *
 * `POST /tickets/bulk-reassign` is the same route C-017's grid-selection
 * dialog calls — the contract's own words are "two callers, one endpoint …
 * the difference is entirely in how ticketIds is assembled". Here it is
 * assembled from "every open ticket assigned to the source resource" rather
 * than a tick-box selection, so `useBulkReassign` and the pure `bulkActions.ts`
 * helpers are reused as-is rather than forked.
 */
export function BulkReassignWizardPage() {
  const location = useLocation()
  const navigate = useNavigate()
  const handoff = React.useMemo(() => parseWizardHandoff(location.search), [location.search])
  const destination = wizardDestination(handoff.returnTo)

  // `POST /tickets/bulk-reassign` is PM and Admin only (`BulkReassignController`'s
  // own note on why it is a role pair rather than a borrowed capability). Hiding
  // the wizard for anyone else is `canBulkAct`'s rule, restated here rather than
  // imported: a missing affordance is a nuisance, a phantom one — a four-step
  // wizard that ends in a 403 on step 4 — is worse, and this is the one screen
  // in the product where that would only surface after the reason has been typed.
  const { data: meData, isPending: mePending } = useGetMe()
  const role = meData?.data.role
  const permitted = role === 'ADMIN' || role === 'PM'

  // A `fromUserId` in the handoff skips step 1 rather than only pre-filling
  // it — `reassignHandoff.ts`'s own words are "the admin has already told us
  // who they are trying to deactivate; asking again is how a handoff becomes
  // a fresh task", and landing on step 1 still asking them to click Continue
  // is exactly that.
  const [step, setStep] = React.useState(handoff.fromUserId != null ? 1 : 0)
  const [sourceUserId, setSourceUserId] = React.useState<number | null>(handoff.fromUserId)
  const [selected, setSelected] = React.useState<Set<string>>(new Set())
  const [targetUserId, setTargetUserId] = React.useState<number | null>(null)
  const [reason, setReason] = React.useState('')

  // All resources for step 1 — an offboarded person can already be inactive
  // by the time somebody notices they still hold tickets, so this list is not
  // filtered to isActive, unlike the target picker below.
  const { data: sourceCandidatesData, isPending: sourceCandidatesPending } = useListUsers({ limit: 200 })
  const sourceCandidates = React.useMemo(() => sourceCandidatesData?.data ?? [], [sourceCandidatesData])
  const sourceUser = sourceCandidates.find((u) => u.id === sourceUserId) ?? null

  const {
    data: ticketsData,
    isPending: ticketsPending,
    isError: ticketsError,
  } = useListTickets(
    { assigneeId: sourceUserId ?? undefined, excludeClosed: true, limit: 200 },
    { query: { enabled: sourceUserId != null } },
  )
  const tickets = React.useMemo(() => ticketsData?.data ?? [], [ticketsData])

  // Every open ticket is selected the moment the source's list loads — "move
  // everything this person is leaving behind" is the common case the wizard
  // exists for, and the per-row checkbox is there for the exception, not the
  // rule. Re-runs only when the source changes, so unchecking a row survives
  // an unrelated re-render.
  const lastSeededFor = React.useRef<number | null>(null)
  React.useEffect(() => {
    if (sourceUserId != null && lastSeededFor.current !== sourceUserId && ticketsData) {
      setSelected(new Set(tickets.map((t) => t.ticketId)))
      lastSeededFor.current = sourceUserId
    }
  }, [sourceUserId, ticketsData, tickets])

  const { data: targetCandidatesData } = useListUsers({ isActive: true, limit: 200 })
  const targetCandidates = React.useMemo(
    () => (targetCandidatesData?.data ?? []).filter((u) => u.id !== sourceUserId),
    [targetCandidatesData, sourceUserId],
  )
  const targetUser = targetCandidates.find((u) => u.id === targetUserId) ?? null

  const reassign = useBulkReassign()
  const [submitError, setSubmitError] = React.useState<string | null>(null)

  const selectedIds = React.useMemo(() => [...selected], [selected])
  const reasonReady = reason.trim().length >= MIN_REASON
  const result = reassign.data?.data ?? null

  function toggleTicket(ticketId: string) {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(ticketId)) next.delete(ticketId)
      else next.add(ticketId)
      return next
    })
  }

  function toggleAll() {
    setSelected((prev) => (prev.size === tickets.length ? new Set() : new Set(tickets.map((t) => t.ticketId))))
  }

  function goBack() {
    setStep((s) => Math.max(0, s - 1))
  }

  function confirm() {
    if (!targetUserId || !reasonReady || selectedIds.length === 0) return
    setSubmitError(null)
    reassign.mutate(
      { data: { ticketIds: selectedIds, toUserId: targetUserId, reason: reason.trim() }, idempotencyKey: newIdempotencyKey() },
      {
        onError: (error) => {
          setSubmitError(
            error instanceof ApiError ? error.problem.detail ?? error.problem.title ?? 'Something went wrong.'
              : 'Something went wrong. Try again.',
          )
        },
      },
    )
  }

  const done = reassign.isSuccess

  if (!mePending && !permitted) {
    return (
      <div className="space-y-6">
        <h1 className="text-h1 text-content">Bulk reassignment wizard</h1>
        <EmptyState
          title="Not available"
          description="Bulk reassignment is restricted to Admin and PM."
          action={
            <Button
              size="sm"
              onClick={() => {
                navigate(destination)
              }}
            >
              <ArrowLeft className="h-4 w-4" />
              Back
            </Button>
          }
        />
      </div>
    )
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-h1 text-content">Bulk reassignment wizard</h1>
        <div className="ml-auto">
          <Button asChild variant="secondary" size="sm">
            <a
              href={destination}
              onClick={(e) => {
                e.preventDefault()
                navigate(destination)
              }}
            >
              <ArrowLeft className="h-4 w-4" />
              {done ? 'Done' : 'Cancel'}
            </a>
          </Button>
        </div>
      </div>

      <ol className="grid gap-2 sm:grid-cols-4" aria-label="Wizard steps">
        {STEPS.map((s, index) => {
          const current = index === step && !done
          const complete = index < step || done
          return (
            <li
              key={s.title}
              aria-current={current ? 'step' : undefined}
              className={cn(
                'rounded-card border p-3',
                current ? 'border-primary bg-primary-soft' : 'border-border bg-surface text-content-muted',
              )}
            >
              <p className="text-xs font-medium uppercase tracking-wide text-content-muted">
                Step {index + 1}
                {complete && <span className="sr-only"> (done)</span>}
              </p>
              <p className={cn('text-sm font-medium', current ? 'text-content' : undefined)}>{s.title}</p>
              <p className="mt-1 text-xs text-content-muted">{s.detail}</p>
            </li>
          )
        })}
      </ol>

      {done ? (
        <section className="rounded-card border border-border bg-surface p-5">
          <h2 className="text-h3 text-content">
            {result ? summariseBulkResult(result, 'reassigned') : 'Reassigned'}
          </h2>
          <p className="mt-1 text-sm text-content-muted">
            {targetUser ? `Moved to ${targetUser.displayName}.` : 'Moved.'} Each ticket wrote its own history entry.
          </p>

          {result && failedResults(result).length > 0 && (
            <ul className="mt-4 max-h-72 space-y-1 overflow-y-auto text-sm">
              {failedResults(result).map((row) => (
                <li key={row.ticketId} className="flex items-start gap-2 py-1">
                  <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warning-text" aria-hidden />
                  <span className="font-mono text-content">{row.ticketId}</span>
                  <span className="text-content-muted">{row.reason ?? 'Refused'}</span>
                </li>
              ))}
            </ul>
          )}

          <div className="mt-5 flex flex-wrap items-center gap-3">
            <Button
              onClick={() => {
                navigate(destination)
              }}
            >
              <Check className="h-4 w-4" />
              Done
            </Button>
          </div>
        </section>
      ) : (
        <>
          {step === 0 && (
            <section className="rounded-card border border-border bg-surface p-5">
              <h2 className="text-h3 text-content">Who is leaving?</h2>
              <p className="mt-1 max-w-2xl text-sm text-content-muted">
                Pick the resource whose open tickets need a new owner. The next step lists everything
                assigned to them.
              </p>

              <div className="mt-4 flex max-w-sm flex-col gap-1.5">
                <label htmlFor="source-resource" className="text-sm font-medium text-content">
                  Source resource
                </label>
                {sourceCandidatesPending ? (
                  <Skeleton className="h-9 w-full" />
                ) : (
                  <SearchableDropdown
                    id="source-resource"
                    options={sourceCandidates}
                    value={sourceUser}
                    onChange={(u) => {
                      setSourceUserId(u.id)
                      setSelected(new Set())
                      lastSeededFor.current = null
                    }}
                    getKey={(u) => String(u.id)}
                    getLabel={(u) => u.displayName}
                    getSearchable={(u) => [u.email ?? '']}
                    placeholder="Search resources…"
                    emptyText="No resources match"
                  />
                )}
              </div>

              <div className="mt-5">
                <Button onClick={() => setStep(1)} disabled={sourceUserId == null}>
                  Continue
                  <ArrowRight className="h-4 w-4" />
                </Button>
              </div>
            </section>
          )}

          {step === 1 && (
            <section className="rounded-card border border-border bg-surface p-5">
              <h2 className="text-h3 text-content">
                {sourceUser ? `${sourceUser.displayName}'s open tickets` : 'Open tickets'}
              </h2>
              <p className="mt-1 max-w-2xl text-sm text-content-muted">
                Every open ticket is selected by default — uncheck anything that should stay where it is.
              </p>

              <TableContainer className="mt-4 max-h-[26rem]">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead className="w-10">
                        <input
                          type="checkbox"
                          aria-label="Select all open tickets"
                          checked={tickets.length > 0 && selected.size === tickets.length}
                          ref={(node) => {
                            if (node) node.indeterminate = selected.size > 0 && selected.size < tickets.length
                          }}
                          onChange={toggleAll}
                          disabled={tickets.length === 0}
                          className="h-4 w-4 cursor-pointer accent-primary"
                        />
                      </TableHead>
                      <TableHead>Ticket</TableHead>
                      <TableHead>Title</TableHead>
                      <TableHead>Project</TableHead>
                      <TableHead>Level</TableHead>
                      <TableHead>Status</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {ticketsPending ? (
                      Array.from({ length: 5 }, (_, i) => (
                        <TableRow key={i}>
                          {Array.from({ length: 6 }, (_, c) => (
                            <TableCell key={c}>
                              <Skeleton className="h-4 w-full max-w-[10rem]" />
                            </TableCell>
                          ))}
                        </TableRow>
                      ))
                    ) : ticketsError ? (
                      <TableRow>
                        <TableCell colSpan={6} className="p-0">
                          <EmptyState title="Could not load tickets" description="Something went wrong. Try again." />
                        </TableCell>
                      </TableRow>
                    ) : tickets.length === 0 ? (
                      <TableRow>
                        <TableCell colSpan={6} className="p-0">
                          <EmptyState
                            title="No open tickets"
                            description="This resource has no open tickets to move."
                          />
                        </TableCell>
                      </TableRow>
                    ) : (
                      tickets.map((ticket) => (
                        <TableRow key={ticket.ticketId}>
                          <TableCell>
                            <input
                              type="checkbox"
                              aria-label={`Select ${ticket.ticketId}`}
                              checked={selected.has(ticket.ticketId)}
                              onChange={() => toggleTicket(ticket.ticketId)}
                              className="h-4 w-4 cursor-pointer accent-primary"
                            />
                          </TableCell>
                          <TableCell className="font-mono text-sm">{ticket.ticketId}</TableCell>
                          <TableCell className="max-w-xs truncate">{ticket.title}</TableCell>
                          <TableCell>{ticket.project?.name ?? '—'}</TableCell>
                          <TableCell>
                            <Chip variant={LEVEL_VARIANT[ticket.level]}>{ticket.level}</Chip>
                          </TableCell>
                          <TableCell>
                            <Chip variant={STATUS_VARIANT[ticket.status]}>{STATUS_LABEL[ticket.status]}</Chip>
                          </TableCell>
                        </TableRow>
                      ))
                    )}
                  </TableBody>
                </Table>
              </TableContainer>

              <div className="mt-5 flex flex-wrap items-center gap-3">
                <Button variant="secondary" onClick={goBack}>
                  <ArrowLeft className="h-4 w-4" />
                  Back
                </Button>
                <Button onClick={() => setStep(2)} disabled={selected.size === 0}>
                  Continue with {selected.size} {selected.size === 1 ? 'ticket' : 'tickets'}
                  <ArrowRight className="h-4 w-4" />
                </Button>
              </div>
            </section>
          )}

          {step === 2 && (
            <section className="rounded-card border border-border bg-surface p-5">
              <h2 className="text-h3 text-content">Who receives {selected.size === 1 ? 'it' : 'them'}?</h2>
              <p className="mt-1 max-w-2xl text-sm text-content-muted">
                Each ticket keeps its stage — this does not advance the ribbon. Every move writes its own
                history entry, so the reason is recorded once per ticket.
              </p>

              <div className="mt-4 flex flex-col gap-4 max-w-sm">
                <div className="flex flex-col gap-1.5">
                  <label htmlFor="target-resource" className="text-sm font-medium text-content">
                    Reassign to
                  </label>
                  <SearchableDropdown
                    id="target-resource"
                    options={targetCandidates}
                    value={targetUser}
                    onChange={(u) => setTargetUserId(u.id)}
                    getKey={(u) => String(u.id)}
                    getLabel={(u) => u.displayName}
                    getSearchable={(u) => [u.email ?? '']}
                    placeholder="Search resources…"
                    emptyText="No resources match"
                  />
                </div>

                <div className="flex flex-col gap-1.5">
                  <label htmlFor="wizard-reason" className="text-sm font-medium text-content">
                    Reason
                  </label>
                  <textarea
                    id="wizard-reason"
                    rows={3}
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                    className={TEXTAREA_CLASS}
                    placeholder="Why is this batch moving?"
                    aria-describedby="wizard-reason-hint"
                  />
                  <p id="wizard-reason-hint" className="text-caption text-content-muted">
                    Written to every selected ticket&rsquo;s history, one entry each.
                  </p>
                </div>
              </div>

              <div className="mt-5 flex flex-wrap items-center gap-3">
                <Button variant="secondary" onClick={goBack}>
                  <ArrowLeft className="h-4 w-4" />
                  Back
                </Button>
                <Button onClick={() => setStep(3)} disabled={targetUserId == null || !reasonReady}>
                  Continue
                  <ArrowRight className="h-4 w-4" />
                </Button>
              </div>
            </section>
          )}

          {step === 3 && (
            <section className="rounded-card border border-border bg-surface p-5">
              <h2 className="text-h3 text-content">Confirm</h2>
              <dl className="mt-3 grid gap-x-8 gap-y-2 text-sm sm:grid-cols-2">
                <div>
                  <dt className="text-content-muted">From</dt>
                  <dd className="text-content">{sourceUser?.displayName ?? '—'}</dd>
                </div>
                <div>
                  <dt className="text-content-muted">To</dt>
                  <dd className="text-content">{targetUser?.displayName ?? '—'}</dd>
                </div>
                <div>
                  <dt className="text-content-muted">Tickets</dt>
                  <dd className="text-content">
                    {selected.size} {selected.size === 1 ? 'ticket' : 'tickets'}
                  </dd>
                </div>
                <div className="sm:col-span-2">
                  <dt className="text-content-muted">Reason</dt>
                  <dd className="text-content">{reason.trim()}</dd>
                </div>
              </dl>

              <p className="mt-4 text-sm text-content-muted">
                Each ticket is its own transaction and its own history entry — one refusing does not
                undo the rest, and you will see exactly which (if any) here.
              </p>

              {submitError && (
                <p role="alert" className="mt-4 text-sm text-danger-text">
                  {submitError}
                </p>
              )}

              <div className="mt-5 flex flex-wrap items-center gap-3">
                <Button variant="secondary" onClick={goBack} disabled={reassign.isPending}>
                  <ArrowLeft className="h-4 w-4" />
                  Back
                </Button>
                <Button onClick={confirm} disabled={reassign.isPending}>
                  {reassign.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Check className="h-4 w-4" />}
                  {reassign.isPending ? 'Reassigning…' : `Reassign ${selected.size}`}
                </Button>
              </div>
            </section>
          )}
        </>
      )}
    </div>
  )
}
