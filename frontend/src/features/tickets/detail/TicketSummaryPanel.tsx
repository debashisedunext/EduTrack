import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { format, parseISO } from 'date-fns'
import { AlertTriangle } from 'lucide-react'

import type { Cycle } from '@/api/generated/model/cycle'
import type { HistoryEntry } from '@/api/generated/model/historyEntry'
import type { Ticket } from '@/api/generated/model/ticket'
import type { UserRef } from '@/api/generated/model/userRef'

import { AvatarStack } from '@/components/ui/avatar-stack'
import { Chip } from '@/components/ui/chip'
import { cn } from '@/lib/utils'

import { LEVEL_VARIANT, STATUS_LABEL, STATUS_VARIANT } from '../list/columns'
import { clientPath, cycleEffortPath, projectPath, resourcePath } from './entityLinks'
import {
  ageInDays,
  assignedBy,
  cycleEffortHrs,
  effortVariancePct,
  hoursLabel,
  totalEffortHrs,
} from './ticketSummary'

export interface TicketSummaryPanelProps {
  ticket: Ticket
  cycles?: Cycle[]
  history?: HistoryEntry[]
  watchers?: UserRef[]
  /** Which cycle the page is showing. Highlighted in the Cycles row. */
  selectedCycleNo: number
  /** `Ticket.taskTypeId` names a master row the payload does not embed. */
  taskTypeName?: string
  /** Same for `clientContactId`, resolved from `GET /clients/{id}/contacts`. */
  contactName?: string
  /** Injected so the panel renders deterministically under test. */
  now?: Date
}

const DATE_FORMAT = 'd MMM yyyy'

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[6.5rem_minmax(0,1fr)] items-baseline gap-3 px-4 py-1.5">
      <dt className="text-caption text-content-muted">{label}</dt>
      <dd className="min-w-0 text-sm text-content">{children}</dd>
    </div>
  )
}

const Dash = () => <span className="text-content-muted">—</span>

/**
 * An entity that resolves to a screen of its own.
 *
 * Deliberately one component rather than a styled `Link` per row: the
 * traceability rule is "*every* entity is a link", and a shared component is
 * how the next person adding a row inherits it without being told.
 */
function EntityLink({ to, children }: { to: string; children: ReactNode }) {
  return (
    <Link
      to={to}
      className="rounded-[2px] text-primary hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2"
    >
      {children}
    </Link>
  )
}

function PersonCell({ person }: { person: UserRef | null | undefined }) {
  if (!person) return <Dash />
  return (
    <span className="flex min-w-0 items-center gap-2">
      {/*
        Hidden from assistive tech on purpose. `AvatarStack` hardcodes
        `role="group" aria-label="Watchers"` (it was built for §12.3's watcher
        row), so an unhidden single avatar announces the assignee as "Watchers"
        — and the name beside it already carries the information. Fixing the
        label properly means a prop on a shared component every stream
        consumes, which is an additive change worth making on its own, not
        inside this task.
      */}
      <span aria-hidden="true">
        <AvatarStack people={[{ id: String(person.id), name: person.displayName }]} max={1} size="sm" />
      </span>
      <EntityLink to={resourcePath(person.id)}>{person.displayName}</EntityLink>
    </span>
  )
}

function DateCell({ value, warn }: { value?: string | null; warn?: boolean }) {
  if (!value) return <Dash />
  return (
    <span className={cn('inline-flex items-center gap-1', warn && 'text-warning-text')}>
      {format(parseISO(value), DATE_FORMAT)}
      {warn && <AlertTriangle className="h-3.5 w-3.5" aria-label="Past due" />}
    </span>
  )
}

/**
 * S-20's right rail.
 *
 * Read-only in C-019. The one field the blueprint calls inline-editable —
 * Level — is C-020, which owns the dropdown, the mandatory reason and the
 * `LEVEL_CHANGED` history row; it replaces the chip below rather than adding a
 * second place a level can be changed from.
 */
export function TicketSummaryPanel({
  ticket,
  cycles,
  history,
  watchers,
  selectedCycleNo,
  taskTypeName,
  contactName,
  now = new Date(),
}: TicketSummaryPanelProps) {
  const total = totalEffortHrs(ticket, cycles)
  const variance = effortVariancePct(ticket.estimatedHrs, total)
  const age = ageInDays(ticket, now)
  const assigner = assignedBy(history)
  const isOpen = ticket.status !== 'CLOSED' && ticket.status !== 'RESOLVED'
  // `originalLevel` is set once at creation and never overwritten, so a
  // difference here is the whole "born critical vs became critical" question a
  // manager asks first — surfaced inline rather than buried in the History tab.
  const escalatedFrom = ticket.originalLevel && ticket.originalLevel !== ticket.level ? ticket.originalLevel : null

  return (
    <aside
      aria-labelledby="ticket-summary-heading"
      className="h-fit rounded-card border border-border bg-surface shadow-rest"
    >
      <h2 id="ticket-summary-heading" className="border-b border-border px-4 py-3 text-h3 text-content">
        Summary
      </h2>

      <dl className="py-2">
        <Row label="Project">
          {ticket.project?.id != null ? (
            <EntityLink to={projectPath(ticket.project.id)}>
              {ticket.project.projectCode ? `${ticket.project.projectCode} — ` : ''}
              {ticket.project.name}
            </EntityLink>
          ) : (
            <Dash />
          )}
        </Row>

        <Row label="Client">
          {ticket.client?.id != null ? (
            <EntityLink to={clientPath(ticket.client.id)}>{ticket.client.name ?? ticket.client.clientCode}</EntityLink>
          ) : (
            <Dash />
          )}
        </Row>

        <Row label="Contact">{contactName ?? <Dash />}</Row>

        <Row label="Type">{taskTypeName ? <Chip variant="neutral">{taskTypeName}</Chip> : <Dash />}</Row>

        <Row label="Level">
          <span className="flex flex-wrap items-center gap-1.5">
            <Chip variant={LEVEL_VARIANT[ticket.level]}>{ticket.level}</Chip>
            {escalatedFrom && <span className="text-caption text-content-muted">was {escalatedFrom}</span>}
          </span>
        </Row>

        <Row label="Status">
          <Chip variant={STATUS_VARIANT[ticket.status]}>{STATUS_LABEL[ticket.status]}</Chip>
        </Row>

        <Row label="Reported by">
          <PersonCell person={ticket.reportedBy} />
        </Row>

        <Row label="Date reported">
          <DateCell value={ticket.createdAt} />
        </Row>

        <Row label="Assigned to">
          <PersonCell person={ticket.assignee} />
        </Row>

        {/* ⚠ Derived from history — the contract has no `assignedBy`. See ticketSummary.ts. */}
        <Row label="Assigned by">
          <PersonCell person={assigner} />
        </Row>

        <Row label="Est. effort">{hoursLabel(ticket.estimatedHrs)}</Row>

        <Row label="Logged">
          <span className="flex flex-wrap items-baseline gap-1.5">
            <span className="tabular-nums">{hoursLabel(total)}</span>
            {variance != null && variance !== 0 && (
              <span className={cn('text-caption', variance > 0 ? 'text-warning-text' : 'text-success-text')}>
                {variance > 0 ? '+' : ''}
                {variance}% vs estimate
              </span>
            )}
          </span>
        </Row>

        <Row label="Planned close">
          <DateCell value={ticket.plannedCloseDate} warn={Boolean(ticket.isDelayed) && isOpen} />
        </Row>

        <Row label="Actual close">
          <DateCell value={ticket.actualCloseDate} />
        </Row>

        <Row label="Reopened">
          <span className="tabular-nums">{ticket.reopenCount ?? 0}×</span>
        </Row>

        {/*
          Cycle → that cycle's effort logs. Each cycle keeps its own ribbon and
          its own hours, so the link carries `?cycle=` as well as the tab —
          landing on the effort tab without switching cycle would show the
          current cycle's rows under an earlier cycle's label.
        */}
        <Row label="Cycles">
          {cycles?.length ? (
            <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
              {cycles.map((cycle) => {
                const cycleNo = cycle.cycleNo ?? 1
                const selected = cycleNo === selectedCycleNo
                return (
                  <EntityLink key={cycleNo} to={cycleEffortPath(ticket.ticketId, cycleNo)}>
                    <span aria-current={selected ? 'true' : undefined} className={cn(selected && 'font-semibold')}>
                      Cycle {cycleNo}
                    </span>
                    <span className="ml-1 text-caption tabular-nums text-content-muted">
                      {hoursLabel(cycleEffortHrs(cycles, cycleNo))}
                    </span>
                  </EntityLink>
                )
              })}
            </span>
          ) : (
            <Dash />
          )}
        </Row>

        <Row label="Total effort">
          <span className="font-medium tabular-nums">{hoursLabel(total)}</span>
        </Row>

        <Row label="Age">{age == null ? <Dash /> : `${age} d`}</Row>

        <Row label="Watchers">
          {watchers?.length ? (
            <span className="flex flex-wrap items-center gap-2">
              <span aria-hidden="true">
                <AvatarStack
                  people={watchers.map((w) => ({ id: String(w.id), name: w.displayName }))}
                  max={4}
                  size="sm"
                />
              </span>
              <span className="flex flex-wrap gap-x-2 text-caption">
                {watchers.map((w) => (
                  <EntityLink key={w.id} to={resourcePath(w.id)}>
                    {w.displayName}
                  </EntityLink>
                ))}
              </span>
            </span>
          ) : (
            <span className="text-content-muted">None</span>
          )}
        </Row>
      </dl>
    </aside>
  )
}
