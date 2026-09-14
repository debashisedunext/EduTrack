import * as React from 'react'
import { useQueryClient } from '@tanstack/react-query'

import type { ObJourneyStepItem } from '@/api/generated/model/obJourneyStepItem'
import type { UserRef } from '@/api/generated/model/userRef'
import {
  getGetObJourneyQueryKey,
  useUpdateObJourneyStepItem,
} from '@/api/generated/onboarding-journeys/onboarding-journeys'
import { cn } from '@/lib/utils'

import { ObTaskCommunications } from './ObTaskCommunications'
import { ObTaskTransitions } from './ObTaskTransitions'
import type { ProjectTask } from './useProjectTasks'

/**
 * One open task: its vital signs, its task list, its transitions.
 *
 * <h2>The vital signs are a line, not five columns</h2>
 *
 * <p>Responsible, position, TAT, due, TAT used and the answered count were six
 * stacked label/value blocks across the full width — a band of mostly air. They
 * wrap as one line of small-caps label + value pairs instead. TAT used keeps its
 * colour because it is the one a reader scans for, and it comes from the server
 * (`tatUsedPercent`) rather than from arithmetic here: deriving it in the
 * browser would count weekends, holidays and client waits as working time.
 *
 * <h2>The remark is on the item's own line</h2>
 *
 * <p>A three-column grid — label, remark, True/False — so the input flexes into
 * whatever is left rather than opening a full-width row under every item. The
 * old shape spent a row per item on a field that is usually empty.
 *
 * <h2>Three states, and False needs a reason</h2>
 *
 * <p>`answer` is true, false or null. `PATCH /journey-step-items/{id}` takes
 * `{answer, remark}`, and the server refuses False with no remark
 * (`ob-step-item-remark-required`) because `ck_ob_journey_step_items_remark`
 * says so. This mirrors that: pressing False on an item with an empty remark
 * focuses the field rather than sending a request that will be refused.
 *
 * <p>Pressing the answer a second time clears it back to unanswered, which is
 * the only way to undo a mis-click and is why the request accepts null.
 */
export interface ObProjectTaskPanelProps {
  task: ProjectTask
  /** Position within its stage — "Task 2 of 4". */
  index: number
  total: number
  users: readonly UserRef[]
  /** Whether the viewer owns this task, and may therefore change it. */
  yours: boolean
  /** This task's open client escalation, where it has one. */
  escalation?: { id: number; raisedBy: string; raisedAt: string; note: string } | null
  onResolveEscalation?: (escalationId: number) => void
}

const OUTSTANDING_CLASS = 'text-caption text-danger-text'

export function ObProjectTaskPanel({
  task,
  index,
  total,
  users,
  yours,
  escalation,
  onResolveEscalation,
}: ObProjectTaskPanelProps) {
  const queryClient = useQueryClient()
  const [drafts, setDrafts] = React.useState<Record<number, string>>({})
  const inputs = React.useRef<Record<number, HTMLInputElement | null>>({})

  const update = useUpdateObJourneyStepItem({
    mutation: {
      onSuccess: () => {
        void queryClient.invalidateQueries({ queryKey: getGetObJourneyQueryKey(task.journeyId) })
      },
    },
  })

  const ownerName = (id: number | null | undefined) =>
    id == null ? null : (users.find((u) => u.id === id)?.displayName ?? null)

  const remarkOf = (item: ObJourneyStepItem) => drafts[item.id] ?? item.remark ?? ''

  /**
   * Toggling: pressing the answer already recorded clears it.
   *
   * A False with no remark is stopped here rather than sent — the server would
   * refuse it, and focusing the field the caller has to fill is a better answer
   * than a toast repeating what the empty box already implies.
   */
  const answer = (item: ObJourneyStepItem, value: boolean) => {
    const next = item.answer === value ? null : value
    const remark = remarkOf(item).trim()
    if (next === false && !remark) {
      inputs.current[item.id]?.focus()
      return
    }
    update.mutate({ itemId: item.id, data: { answer: next, remark: next === null ? null : remark } })
  }

  const owner = ownerName(task.ownerUserId) ?? 'the task owner'
  const answered = task.items.filter((i) => i.answer !== null && i.answer !== undefined).length
  const exceptions = task.items.some((i) => i.answer === false)

  /*
    What Complete is waiting on, named rather than left to a 422. The mandatory
    filter matches `requireCompletionGate`, which counts an item answered either
    way as answered — so a False with a reason does not hold the gate.
  */
  const blockers = React.useMemo(() => {
    const out: string[] = []
    const unanswered = task.items.filter((i) => i.isMandatory && i.answer == null).length
    if (unanswered) out.push(`${unanswered} unanswered`)
    const missing = task.docs.filter((d) => d.isRequired && !d.isSatisfied).length
    if (missing) out.push(`${missing} required document${missing === 1 ? '' : 's'}`)
    return out
  }, [task.items, task.docs])

  return (
    <div className="mt-2 flex flex-col gap-2.5">
      {/* ---- vital signs ---- */}
      <dl className="m-0 flex flex-wrap gap-x-5 gap-y-1.5 rounded-control border border-border bg-surface px-3 py-2">
        <Fact label="Responsible">
          {task.ownerUserId != null ? (
            <span className="inline-flex items-center gap-1.5">
              <Avatar name={ownerName(task.ownerUserId) ?? '—'} />
              {ownerName(task.ownerUserId) ?? `User ${task.ownerUserId}`}
            </span>
          ) : (
            <span className="text-danger-text">Unassigned</span>
          )}
        </Fact>
        <Fact label="Task">{`${index + 1} of ${total}`}</Fact>
        <Fact label="TAT">{`${task.tatDays} working day${task.tatDays === 1 ? '' : 's'}`}</Fact>
        <Fact label="Due" tone={overdue(task.dueAt) ? 'bad' : undefined}>
          {task.dueAt ? `${formatDue(task.dueAt)}${overdue(task.dueAt) ? ' (missed)' : ''}` : '—'}
        </Fact>
        {task.tatUsedPercent != null && (
          <Fact
            label="TAT used"
            tone={task.tatUsedPercent > 100 ? 'bad' : task.tatUsedPercent >= 75 ? 'warn' : undefined}
          >
            {`${Math.round(task.tatUsedPercent)}%`}
          </Fact>
        )}
        <Fact label="Task list">
          {task.items.length ? `${answered}/${task.items.length} answered` : 'none'}
        </Fact>
      </dl>

      {/* ---- client escalation ---- */}
      {escalation && (
        <div
          role="status"
          className="flex flex-wrap items-start gap-2 rounded-control border border-danger bg-danger-soft px-3 py-2 text-caption text-danger-text"
        >
          <span aria-hidden="true">🔔</span>
          <span>
            <strong className="font-semibold">Client escalation</strong> —{' '}
            <em>“{escalation.note}”</em>{' '}
            <span className="text-content-muted">
              ({escalation.raisedBy}, {formatDue(escalation.raisedAt)})
            </span>
          </span>
          <span className="min-w-0 flex-1" aria-hidden="true" />
          {onResolveEscalation && (
            <button
              type="button"
              onClick={() => onResolveEscalation(escalation.id)}
              className="shrink-0 rounded-chip border border-current px-2.5 py-0.5 text-[11px] font-semibold focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
            >
              ✓ Resolve &amp; acknowledge
            </button>
          )}
        </div>
      )}

      {/* ---- required documents ---- */}
      {task.docs.length > 0 && (
        <div className="flex flex-wrap items-center gap-1.5 text-caption text-content-muted">
          <span aria-hidden="true">📋</span>
          <span>Required documents:</span>
          {task.docs.map((doc) => (
            <span
              key={doc.id}
              title={
                doc.isSatisfied
                  ? 'Attached'
                  : doc.isRequired
                    ? 'Required — missing'
                    : 'Optional'
              }
              className={cn(
                'inline-flex items-center gap-1 rounded-chip border px-2 py-0.5 text-[11px]',
                doc.isSatisfied
                  ? 'border-success bg-level-low-soft font-semibold text-success-text'
                  : 'border-border bg-surface text-content-muted',
              )}
            >
              <span aria-hidden="true">{doc.isSatisfied ? '✓' : '○'}</span>
              {doc.label}
            </span>
          ))}
        </div>
      )}

      {/* ---- task list ---- */}
      {task.items.length > 0 && (
        <section className="overflow-hidden rounded-control border border-border bg-surface">
          <header className="flex flex-wrap items-baseline gap-x-2 gap-y-1 border-b border-border bg-subtle px-3 py-1.5">
            <h5 className="m-0 text-caption font-semibold text-content">Task list</h5>
            <span className="text-caption tabular-nums text-content-muted">
              {answered}/{task.items.length} answered{exceptions ? ' · has exceptions' : ''}
            </span>
            <span className="ml-auto text-[10.5px] text-content-muted">
              All must be answered; False needs a remark
            </span>
          </header>

          <ul role="list" className="m-0 list-none p-0">
            {task.items.map((item) => {
              const busy = update.isPending && update.variables?.itemId === item.id
              const needsRemark = item.answer === false && !remarkOf(item).trim()
              return (
                <li
                  key={item.id}
                  className="grid grid-cols-1 items-center gap-2 border-b border-border px-3 py-1.5 last:border-b-0 sm:grid-cols-[minmax(7rem,auto)_minmax(0,1fr)_auto]"
                >
                  <span
                    className={cn(
                      'text-caption font-medium',
                      item.answer === false ? 'text-danger-text' : 'text-content',
                    )}
                  >
                    {item.label}
                    {item.isMandatory && (
                      <span className="ml-1 font-bold text-danger-text" title="Mandatory — blocks completion">
                        *
                      </span>
                    )}
                  </span>

                  {/* One line, between the label and the buttons. */}
                  <input
                    ref={(el) => {
                      inputs.current[item.id] = el
                    }}
                    type="text"
                    value={remarkOf(item)}
                    disabled={!yours || busy}
                    aria-label={`Remark for ${item.label}`}
                    placeholder={item.answer === false ? 'Reason required…' : 'Remark (optional)…'}
                    onChange={(e) => setDrafts((d) => ({ ...d, [item.id]: e.target.value }))}
                    onBlur={() => {
                      const text = remarkOf(item).trim()
                      if (item.answer != null && text !== (item.remark ?? '')) {
                        update.mutate({ itemId: item.id, data: { answer: item.answer, remark: text || null } })
                      }
                    }}
                    className={cn(
                      'w-full min-w-0 rounded-[6px] border px-2 py-1 text-caption text-content',
                      'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
                      needsRemark ? 'border-danger bg-danger-soft' : 'border-border bg-surface',
                      !yours && 'cursor-not-allowed bg-subtle',
                    )}
                  />

                  <span className="flex shrink-0 gap-1">
                    <AnswerButton
                      tone="true"
                      pressed={item.answer === true}
                      disabled={!yours || busy}
                      title={yours ? 'Answer True' : `Only ${owner} can answer this`}
                      onClick={() => answer(item, true)}
                    />
                    <AnswerButton
                      tone="false"
                      pressed={item.answer === false}
                      disabled={!yours || busy}
                      title={yours ? 'Answer False — needs a remark' : `Only ${owner} can answer this`}
                      onClick={() => answer(item, false)}
                    />
                  </span>
                </li>
              )
            })}
          </ul>
        </section>
      )}

      {update.isError && (
        <p className={OUTSTANDING_CLASS} role="alert">
          That answer was refused — a False needs a remark saying why.
        </p>
      )}

      {/* ---- the transitions ---- */}
      <ObTaskTransitions task={task} users={users} yours={yours} blockers={blockers} />

      {/* ---- the timeline ----
          Writable by any staff viewer: recording that a call happened is a log
          entry, not a task mutation, and `ObStepCommunication.recordedBy`
          exists because anyone might add one. */}
      <ObTaskCommunications stepId={task.id} canPost />

      {!yours && (
        <p className="rounded-control border border-dashed border-border px-3 py-2 text-caption text-content-muted">
          {task.ownerUserId != null
            ? `Read-only — ${owner} is the implementor on this task. You can still log communication.`
            : 'Read-only — nobody is assigned to this task yet.'}
        </p>
      )}
    </div>
  )
}

function Fact({
  label,
  tone,
  children,
}: {
  label: string
  tone?: 'bad' | 'warn'
  children: React.ReactNode
}) {
  return (
    <div className="flex items-baseline gap-1.5">
      <dt className="text-[9.5px] font-bold uppercase tracking-wide text-content-muted">{label}</dt>
      <dd
        className={cn(
          'm-0 text-caption font-semibold tabular-nums',
          tone === 'bad' ? 'text-danger-text' : tone === 'warn' ? 'text-warning-text' : 'text-content',
        )}
      >
        {children}
      </dd>
    </div>
  )
}

function Avatar({ name }: { name: string }) {
  const initials = name
    .split(/\s+/)
    .map((w) => w[0])
    .join('')
    .slice(0, 2)
    .toUpperCase()
  return (
    <span
      aria-hidden="true"
      className="flex size-[19px] shrink-0 items-center justify-center rounded-chip bg-primary-soft text-[9px] font-bold text-primary"
    >
      {initials}
    </span>
  )
}

function AnswerButton({
  tone,
  pressed,
  disabled,
  title,
  onClick,
}: {
  tone: 'true' | 'false'
  pressed: boolean
  disabled: boolean
  title: string
  onClick: () => void
}) {
  return (
    <button
      type="button"
      aria-pressed={pressed}
      disabled={disabled}
      title={title}
      onClick={onClick}
      className={cn(
        'whitespace-nowrap rounded-[6px] border px-2.5 py-1 text-[11px] font-semibold',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
        disabled && 'cursor-not-allowed opacity-55',
        !pressed && 'border-border bg-surface text-content-muted',
        pressed && tone === 'true' && 'border-success bg-level-low-soft text-success-text',
        pressed && tone === 'false' && 'border-danger bg-danger-soft text-danger-text',
      )}
    >
      {tone === 'true' ? '✓ True' : '✗ False'}
    </button>
  )
}

/** `2026-09-15T11:00:00Z` → "15 Sep, 11:00". */
function formatDue(value: string): string {
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  return `${parsed.toLocaleDateString(undefined, { day: '2-digit', month: 'short' })}, ${parsed.toLocaleTimeString(
    undefined,
    { hour: '2-digit', minute: '2-digit', hour12: false },
  )}`
}

function overdue(value: string | null | undefined): boolean {
  if (!value) return false
  const parsed = new Date(value)
  return !Number.isNaN(parsed.getTime()) && parsed.getTime() < Date.now()
}
