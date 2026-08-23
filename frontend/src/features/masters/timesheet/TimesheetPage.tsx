import * as React from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { addDays, format, parseISO } from 'date-fns'
import { CheckCircle2, ChevronLeft, ChevronRight, Info } from 'lucide-react'

import { ApiError } from '@/api/http'
import {
  getGetUserTimesheetQueryKey,
  useApproveTimesheet,
  useGetUserTimesheet,
  useListUsers,
} from '@/api/generated/users/users'
import type { Timesheet, TimesheetDay, TimesheetRow } from '@/api/generated/model'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { Modal, ModalContent, ModalFooter, ModalHeader, ModalTitle } from '@/components/ui/modal'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { toast } from '@/components/ui/use-toast'
import { cn } from '@/lib/utils'
import { useAuthStore } from '@/features/auth/authStore'
import { ticketPath } from '@/features/tickets/detail/entityLinks'
import { canApproveTimesheet } from './timesheetApprovalPermissions'
import { useQueryClient } from '@tanstack/react-query'

/**
 * B-063 · blueprint §21's timesheet — one resource's week across all tickets,
 * stage by stage.
 *
 * ## Why this file is under `features/masters/`
 *
 * A timesheet is not a master. The directory is an ownership statement rather
 * than a taxonomy one: `features/masters|clients` is Stream B's in TEAM-PLAN §6,
 * this is Stream B's task, and the alternative — `features/resources/`, where
 * A-069's profile lives — is Stream A's work. The *route* is `/timesheet`,
 * top-level, because that is where a person would look for it; A-071 made the
 * same split for the audit log, whose page sits in its own feature folder while
 * its path is not nested under `/masters`.
 *
 * ## The grid, and why the columns are always seven
 *
 * Rows are ticket × stage × iteration, which is what "stage-aware" means: six
 * hours on one ticket reads very differently when four of them are a second
 * pass through QA. Columns are the seven days the server returned, including
 * the empty ones — a grid whose columns move week to week is unreadable, and a
 * Tuesday holiday is exactly what somebody is looking for when a total comes
 * out thin. Non-working days are marked rather than hidden, because hours
 * logged on a Saturday are real and must still appear.
 *
 * ## Nothing is computed here
 *
 * Day totals, the week total, capacity and utilisation all come from the
 * payload. Recomputing them in the client is how a screen ends up quietly
 * disagreeing with the ticket's own effort tab about the same hours — and the
 * one figure worth checking, utilisation, depends on the working calendar,
 * which the browser does not have.
 */
export function TimesheetPage() {
  const { userId } = useParams()
  const [params, setParams] = useSearchParams()
  const me = useAuthStore((s) => s.user)

  const subjectId = Number(userId ?? me?.id ?? 0)
  const weekOf = params.get('weekOf') ?? undefined

  const query = useGetUserTimesheet(subjectId, { weekOf }, { query: { enabled: subjectId > 0 } })
  const sheet = query.data?.data

  const goToWeek = (date: string) => {
    const next = new URLSearchParams(params)
    next.set('weekOf', date)
    setParams(next, { replace: true })
  }

  if (!subjectId) {
    return (
      <div className="p-6">
        <EmptyState
          title="No resource selected"
          description="Sign in again, or open a timesheet from a resource's profile."
        />
      </div>
    )
  }

  return (
    <div className="mx-auto flex max-w-7xl flex-col gap-6 p-6">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-h2 font-semibold text-content">Timesheet</h1>
          <p className="mt-1 text-sm text-content-muted">
            {sheet
              ? `${sheet.person.displayName} · ${weekLabel(sheet)}`
              : 'One resource’s week, stage by stage.'}
          </p>
        </div>

        <div className="flex items-end gap-3">
          <WhoPicker subjectId={subjectId} weekOf={weekOf} />
          {sheet && <WeekNav weekStart={sheet.weekStart} onPick={goToWeek} />}
          {sheet && canApproveTimesheet({ role: me?.role }) && (
            <ApprovalControl subjectId={subjectId} sheet={sheet} />
          )}
        </div>
      </header>

      {sheet?.approval && (
        <p className="flex items-start gap-2 rounded-card border border-border bg-subtle px-3 py-2 text-caption text-content-muted">
          <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0 text-success-text" aria-hidden />
          <span>
            Reviewed by {sheet.approval.approvedBy.displayName} on{' '}
            {format(parseISO(sheet.approval.approvedAt), 'd MMM yyyy')}
            {sheet.approval.note ? ` — “${sheet.approval.note}”` : ''}
          </span>
        </p>
      )}

      {query.isLoading && <Skeleton className="h-72 w-full" />}

      {/*
        404 covers both "no such person" and "not yours to see" — the server does
        not distinguish them, deliberately, so that ids cannot be walked to learn
        who exists. The screen must not invent a distinction either.
      */}
      {query.isError && (
        <EmptyState
          title="Timesheet not available"
          description="This resource does not exist, or is not one you can view."
        />
      )}

      {sheet && (
        <>
          <Totals sheet={sheet} />

          {sheet.rows.length === 0 ? (
            <EmptyState
              title="Nothing logged this week"
              description="No effort was recorded against any ticket in this week. Hours are logged from a ticket’s Quick Update or its Effort tab."
            />
          ) : (
            <WeekGrid sheet={sheet} />
          )}

          <p className="flex items-start gap-2 text-caption text-content-muted">
            <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
            <span>
              Available hours come from the working calendar — weekends, org holidays and this
              resource’s approved leave. A corrected entry stays visible as a signed adjustment,
              because the effort log is append-only and a mistake is reversed rather than erased.
            </span>
          </p>
        </>
      )}
    </div>
  )
}

/** The three figures the week is judged on, straight from the payload. */
function Totals({ sheet }: { sheet: Timesheet }) {
  return (
    <dl className="grid gap-3 sm:grid-cols-3">
      <Figure label="Logged" value={`${trimHours(sheet.totalHours)} h`} />
      <Figure label="Available" value={`${trimHours(sheet.capacityHours)} h`} />
      <Figure
        label="Utilisation"
        /*
          An em dash, never 0%. The server sends null when the calendar offered
          no working hours at all — somebody on leave all week — and 0% would
          read as having wasted a week they were never expected to work.
        */
        value={sheet.utilisationPct == null ? '—' : `${sheet.utilisationPct}%`}
      />
    </dl>
  )
}

function Figure({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-card border border-border bg-surface px-4 py-3">
      <dt className="text-caption text-content-muted">{label}</dt>
      <dd className="mt-0.5 text-h3 font-semibold text-content">{value}</dd>
    </div>
  )
}

function WeekNav({ weekStart, onPick }: { weekStart: string; onPick: (date: string) => void }) {
  const monday = parseISO(weekStart)
  const button =
    'rounded-control border border-border bg-surface px-2.5 py-1.5 text-caption font-medium text-content transition-colors hover:bg-subtle focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary'

  return (
    <div className="flex items-center gap-1.5">
      <button
        type="button"
        className={button}
        aria-label="Previous week"
        onClick={() => onPick(format(addDays(monday, -7), 'yyyy-MM-dd'))}
      >
        <ChevronLeft className="h-4 w-4" aria-hidden />
      </button>
      <button
        type="button"
        className={button}
        onClick={() => onPick(format(new Date(), 'yyyy-MM-dd'))}
      >
        This week
      </button>
      <button
        type="button"
        className={button}
        aria-label="Next week"
        onClick={() => onPick(format(addDays(monday, 7), 'yyyy-MM-dd'))}
      >
        <ChevronRight className="h-4 w-4" aria-hidden />
      </button>
    </div>
  )
}

/**
 * B-065 · "Mark reviewed", and the dialog behind it.
 *
 * <p>Shown to any Admin or PM — `canApproveTimesheet`'s own header explains
 * why the button cannot know in advance whether *this* PM is *this*
 * resource's manager, and lets the server's 404 answer that instead.
 * Nothing renders once {@link Timesheet.approval} is set: there is no
 * un-approve, on the same reasoning the contract's `TimesheetApproval`
 * schema gives for shipping no `PATCH` or `DELETE`.
 */
function ApprovalControl({ subjectId, sheet }: { subjectId: number; sheet: Timesheet }) {
  const [open, setOpen] = React.useState(false)

  if (sheet.approval) return null

  return (
    <>
      <Button type="button" variant="secondary" size="sm" onClick={() => setOpen(true)}>
        <CheckCircle2 className="h-4 w-4" aria-hidden />
        Mark reviewed
      </Button>
      <Modal open={open} onOpenChange={setOpen}>
        <ModalContent aria-describedby={undefined}>
          {open && (
            <ApprovalForm
              subjectId={subjectId}
              weekStart={sheet.weekStart}
              personName={sheet.person.displayName}
              onDone={() => setOpen(false)}
            />
          )}
        </ModalContent>
      </Modal>
    </>
  )
}

function ApprovalForm({
  subjectId,
  weekStart,
  personName,
  onDone,
}: {
  subjectId: number
  weekStart: string
  personName: string
  onDone: () => void
}) {
  const [note, setNote] = React.useState('')
  const queryClient = useQueryClient()

  // No hand-written Idempotency-Key wrapper here, unlike `useHandoffMutation` —
  // the header is accepted server-side but not yet honoured either way
  // (EffortLogController's precedent), and unlike a handoff a retried approve
  // is already caught by `uq_timesheet_approvals_week` as a 409 rather than a
  // silent second row, so there is nothing for a replay key to protect yet.
  const approve = useApproveTimesheet()

  function submit(e: React.FormEvent) {
    e.preventDefault()
    approve.mutate(
      { userId: subjectId, data: { note: note.trim() || null }, params: { weekOf: weekStart } },
      {
        onSuccess: () => {
          toast({ title: 'Week marked reviewed', description: `${personName}’s week of ${weekStart}.` })
          void queryClient.invalidateQueries({
            queryKey: getGetUserTimesheetQueryKey(subjectId, { weekOf: weekStart }),
          })
          onDone()
        },
      },
    )
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      <ModalHeader>
        <ModalTitle>Mark {personName}’s week reviewed</ModalTitle>
      </ModalHeader>

      <label className="flex flex-col gap-1">
        <span className="text-caption text-content-muted">Note (optional)</span>
        <textarea
          value={note}
          onChange={(e) => setNote(e.target.value)}
          rows={3}
          maxLength={500}
          disabled={approve.isPending}
          placeholder="Anything worth recording about this review…"
          className="w-full rounded-control border border-border bg-surface px-3 py-2 text-sm text-content placeholder:text-content-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        />
      </label>

      {/*
        A 404 here means this caller turned out not to be this resource's
        direct manager — the row question `canApproveTimesheet` could not
        check up front — and a 409 means somebody else reviewed this week
        first. Shown as the server phrased it, on `HandoffDialog`'s own
        precedent for a refusal the caller can act on.
      */}
      {approve.isError && (
        <p role="alert" className="text-caption text-danger-text">
          {approve.error instanceof ApiError
            ? (approve.error.problem.detail ?? approve.error.message)
            : 'This week could not be marked reviewed. Please try again.'}
        </p>
      )}

      <ModalFooter>
        <Button type="button" variant="secondary" size="sm" onClick={onDone} disabled={approve.isPending}>
          Cancel
        </Button>
        <Button type="submit" size="sm" disabled={approve.isPending}>
          {approve.isPending ? 'Marking reviewed…' : 'Mark reviewed'}
        </Button>
      </ModalFooter>
    </form>
  )
}

/**
 * Whose week to show.
 *
 * <p>Fed by `GET /users`, which scopes itself server-side — so this offers the
 * people the server was already willing to name to this caller. Picking
 * somebody it will not show a timesheet for lands on the same "not available"
 * state as a user id that does not exist, which is the answer the endpoint
 * gives on purpose.
 */
function WhoPicker({ subjectId, weekOf }: { subjectId: number; weekOf: string | undefined }) {
  const navigate = useNavigate()
  const users = useListUsers({ limit: 200 })
  const rows = users.data?.data ?? []

  if (rows.length <= 1) return null

  return (
    <label className="flex flex-col gap-1">
      <span className="text-caption text-content-muted">Resource</span>
      <Select
        value={String(subjectId)}
        onValueChange={(value) => {
          // Router navigation, not a location assignment: a full reload would
          // throw away the query cache and the session this page was opened in.
          navigate({ pathname: `/timesheet/${value}`, search: weekOf ? `?weekOf=${weekOf}` : '' })
        }}
      >
        <SelectTrigger className="w-56" aria-label="Whose timesheet to show">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {rows.map((u) => (
            <SelectItem key={u.id} value={String(u.id)}>
              {u.displayName}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </label>
  )
}

function WeekGrid({ sheet }: { sheet: Timesheet }) {
  return (
    <TableContainer>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead scope="col">Ticket</TableHead>
            <TableHead scope="col">Stage</TableHead>
            {sheet.days.map((day) => (
              <TableHead
                key={day.date}
                scope="col"
                className={cn('text-right', !day.isWorkingDay && 'bg-subtle')}
              >
                <span className="block">{format(parseISO(day.date), 'EEE')}</span>
                <span className="block text-caption font-normal text-content-muted">
                  {format(parseISO(day.date), 'd MMM')}
                </span>
              </TableHead>
            ))}
            <TableHead scope="col" className="text-right">
              Total
            </TableHead>
          </TableRow>
        </TableHeader>

        <TableBody>
          {sheet.rows.map((row) => (
            <GridRow key={rowKey(row)} row={row} days={sheet.days} />
          ))}

          <TableRow>
            <TableCell colSpan={2} className="font-medium text-content">
              Logged
            </TableCell>
            {sheet.days.map((day) => (
              <TableCell
                key={day.date}
                className={cn('text-right font-medium text-content', !day.isWorkingDay && 'bg-subtle')}
              >
                {day.loggedHours === 0 ? '—' : trimHours(day.loggedHours)}
              </TableCell>
            ))}
            <TableCell className="text-right font-semibold text-content">
              {trimHours(sheet.totalHours)}
            </TableCell>
          </TableRow>

          <TableRow>
            <TableCell colSpan={2} className="text-content-muted">
              Available
            </TableCell>
            {sheet.days.map((day) => (
              <TableCell
                key={day.date}
                className={cn('text-right text-content-muted', !day.isWorkingDay && 'bg-subtle')}
              >
                {/*
                  A zero is a day the calendar offered nothing — a weekend, a
                  holiday, or this person's leave. Shown as a dash rather than
                  "0", which reads as a figure somebody entered.
                */}
                {day.isWorkingDay ? trimHours(day.capacityHours) : '—'}
              </TableCell>
            ))}
            <TableCell className="text-right text-content-muted">
              {trimHours(sheet.capacityHours)}
            </TableCell>
          </TableRow>
        </TableBody>
      </Table>
    </TableContainer>
  )
}

function GridRow({ row, days }: { row: TimesheetRow; days: TimesheetDay[] }) {
  return (
    <TableRow>
      <TableCell>
        <Link
          to={ticketPath(row.ticketId)}
          className="font-medium text-primary hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        >
          {row.ticketId}
        </Link>
        <span className="block max-w-[22rem] truncate text-caption text-content-muted">
          {row.ticketTitle}
        </span>
      </TableCell>

      <TableCell>
        <span className="text-sm text-content">{row.stageName ?? row.stage ?? '—'}</span>
        {/*
          The iteration is only worth saying when it is not the first. "QA" and
          "QA (pass 2)" is the distinction the row exists to draw; "QA (pass 1)"
          on every line is noise that hides it.
        */}
        {row.iterationNo > 1 && (
          <span className="block text-caption text-content-muted">pass {row.iterationNo}</span>
        )}
        {row.hasCorrection && (
          <span className="block text-caption text-warning-text">includes a correction</span>
        )}
      </TableCell>

      {days.map((day) => {
        const hours = row.hours[day.date]
        return (
          <TableCell
            key={day.date}
            className={cn('text-right tabular-nums', !day.isWorkingDay && 'bg-subtle')}
          >
            {hours == null ? (
              <span className="text-content-muted">·</span>
            ) : (
              <span className={cn(hours < 0 && 'text-warning-text')}>{trimHours(hours)}</span>
            )}
          </TableCell>
        )
      })}

      <TableCell className="text-right font-medium tabular-nums text-content">
        {trimHours(row.totalHours)}
      </TableCell>
    </TableRow>
  )
}

const rowKey = (row: TimesheetRow) =>
  `${row.ticketId}|${row.stage ?? ''}|${row.iterationNo}|${row.cycleNo}`

const weekLabel = (sheet: Timesheet) =>
  `${format(parseISO(sheet.weekStart), 'd MMM')} – ${format(parseISO(sheet.weekEnd), 'd MMM yyyy')}`

/**
 * Two decimals where they carry something, none where they do not.
 *
 * `hoursLabel` rounds to one place, which is right for a ticket total and wrong
 * here: a quarter of an hour is a perfectly ordinary entry and 0.25 must not
 * render as 0.3 in a grid somebody is checking against their own week.
 */
function trimHours(hours: number): string {
  return String(Number(hours.toFixed(2)))
}
