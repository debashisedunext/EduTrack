import * as React from 'react'

import { ApiError } from '@/api/http'
import type { Status } from '@/api/generated/model/status'
import type { StatusCode } from '@/api/generated/model/statusCode'

import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { Input } from '@/components/ui/input'
import {
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
} from '@/components/ui/modal'
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

import {
  CATEGORY_LABELS,
  CONTRACT_STATUS_CODES,
  GOVERNANCE_NOTES,
  STATUS_CATEGORIES,
  STATUS_PALETTE,
  emptyStatusForm,
  matrixHasOnCreateMove,
  moveKey,
  statusFormErrors,
  toFormValues,
  toMatrixRows,
  toMatrixWriteRequest,
  toPatchRequest,
  toWriteRequest,
  type MatrixRow,
  type StatusFormValues,
} from './statusForm'
import { StagesTab } from '../stages/StagesTab'
import { TemplatesTab } from '../templates/TemplatesTab'
import {
  useCreateStatus,
  useReplaceStatusTransitions,
  useStatus,
  useStatusTransitions,
  useStatuses,
  useUpdateStatus,
} from './statusQueries'

/**
 * S-13 Status, Stage & Workflow Template Master. B-039 builds tab 1.
 *
 * §7.4 specifies three tabs — statuses, stages, workflow templates. B-040 filled
 * in tab 2 and B-041 tab 3, so all three are live. Each arrived as a disabled tab
 * naming its task first. A screen that grows a tab later is a screen whose
 * shape nobody could see coming; one that shows them greyed out tells an Admin
 * what this master will hold and tells the next developer where their work goes.
 *
 * **Status is not stage, and the tab split is the product saying so.** Blueprint
 * §3 keeps them apart on purpose: a ticket can be In Progress while sitting in
 * the QA stage. Tab 1 is "is work moving?"; tab 2 will be "who owns it right
 * now?". Collapsing them is the modelling mistake §3 exists to prevent.
 */
export function StatusMasterPage() {
  const [tab, setTab] = React.useState<'statuses' | 'stages' | 'templates'>('statuses')

  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-6 p-6">
      <header>
        <h1 className="text-2xl font-semibold text-content">Status, stage &amp; workflow</h1>
        <p className="mt-1 max-w-3xl text-sm text-content-muted">
          The vocabulary a ticket moves through, and who may move it. <strong>Status is
          not stage</strong> — a ticket can be In&nbsp;Progress while sitting in the QA
          stage. Status is &ldquo;is work moving?&rdquo;; the ribbon&rsquo;s stages are
          &ldquo;who owns it right now?&rdquo;.
        </p>
      </header>

      <div role="tablist" aria-label="Status master tabs" className="flex gap-1 border-b border-line">
        <TabButton
          id="statuses"
          label="Statuses"
          active={tab === 'statuses'}
          onSelect={() => setTab('statuses')}
        />
        <TabButton
          id="stages"
          label="Stages"
          active={tab === 'stages'}
          onSelect={() => setTab('stages')}
        />
        {/*
          B-041. The last of §7.4's three tabs, and the `disabled` prop on
          `TabButton` now has no caller — kept rather than removed, because it is
          how this screen named the tasks that owed it two tabs, and S-13 is not
          the only master that will grow one.
        */}
        <TabButton
          id="templates"
          label="Workflow templates"
          active={tab === 'templates'}
          onSelect={() => setTab('templates')}
        />
      </div>

      {tab === 'statuses' && <StatusesTab />}
      {tab === 'stages' && <StagesTab />}
      {tab === 'templates' && <TemplatesTab />}
    </div>
  )
}

function TabButton({
  id,
  label,
  active,
  disabled,
  title,
  onSelect,
}: {
  id: string
  label: string
  active?: boolean
  disabled?: boolean
  title?: string
  onSelect?: () => void
}) {
  return (
    <button
      type="button"
      role="tab"
      id={`tab-${id}`}
      aria-selected={active ?? false}
      aria-controls={`panel-${id}`}
      disabled={disabled}
      title={title}
      onClick={onSelect}
      className={[
        '-mb-px border-b-2 px-4 py-2 text-sm font-medium transition-colors',
        active
          ? 'border-primary text-primary'
          : 'border-transparent text-content-muted hover:text-content',
        disabled ? 'cursor-not-allowed opacity-50 hover:text-content-muted' : '',
      ].join(' ')}
    >
      {label}
    </button>
  )
}

/**
 * Tab 1: the status list and the transition matrix, one under the other.
 *
 * Both on one panel rather than two sub-tabs, because §7.4 describes them as one
 * tab and because the matrix's rows are moves between the statuses above it —
 * an Admin who has just retired a status needs to see, without navigating, that
 * its rows have gone.
 */
function StatusesTab() {
  const { data: statuses, isPending, isError } = useStatuses()
  const [creating, setCreating] = React.useState(false)
  const [editingId, setEditingId] = React.useState<number | null>(null)

  const takenCodes = React.useMemo(
    () => new Set((statuses ?? []).map((s) => s.code)),
    [statuses],
  )
  const availableCodes = CONTRACT_STATUS_CODES.filter((code) => !takenCodes.has(code))

  return (
    <section id="panel-statuses" role="tabpanel" aria-labelledby="tab-statuses"
      className="flex flex-col gap-8">
      <div className="flex flex-col gap-4">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <h2 className="text-lg font-semibold text-content">Statuses</h2>
            <p className="mt-1 max-w-3xl text-sm text-content-muted">
              Categories group the lifecycle for boards and reports. <strong>Category is
              not the same as open</strong> — Resolved is done work on a ticket that stays
              open until sign-off. Statuses cannot be deleted, and a status cannot be
              retired while tickets are still in it: retiring clears every transition into
              and out of it, which would leave those tickets with no move offered anywhere.
            </p>
          </div>
          <Button onClick={() => setCreating(true)} disabled={availableCodes.length === 0}>
            New status
          </Button>
        </div>

        {isPending && <Skeleton className="h-64 w-full" />}
        {isError && (
          <p className="text-sm text-danger">Could not load the statuses. Reload to retry.</p>
        )}
        {statuses && <StatusGrid statuses={statuses} onEdit={setEditingId} />}

        {availableCodes.length === 0 && !isPending && (
          <p className="text-sm text-content-muted">
            All eight status codes this release supports are in use. A ninth is refused —
            the code is part of the API contract and types every ticket response, so
            opening the set is a coordinated change across the backend, the ticket screens
            and the reports rather than an edit here.
          </p>
        )}
      </div>

      <TransitionMatrix statuses={statuses ?? []} />

      {creating && (
        <StatusDialog
          title="New status"
          availableCodes={availableCodes}
          onClose={() => setCreating(false)}
        />
      )}
      {editingId != null && (
        <StatusDialog
          title="Edit status"
          statusId={editingId}
          availableCodes={availableCodes}
          onClose={() => setEditingId(null)}
        />
      )}
    </section>
  )
}

function StatusGrid({
  statuses,
  onEdit,
}: {
  statuses: Status[]
  onEdit: (id: number) => void
}) {
  return (
    <TableContainer>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Status</TableHead>
            <TableHead>Category</TableHead>
            <TableHead>Order</TableHead>
            <TableHead>Counts as open</TableHead>
            <TableHead>Terminal</TableHead>
            <TableHead className="text-right">Tickets</TableHead>
            <TableHead className="text-right">Transitions</TableHead>
            <TableHead>State</TableHead>
            <TableHead><span className="sr-only">Actions</span></TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {statuses.map((status) => (
            <TableRow key={status.id}>
              <TableCell>
                <span className="flex items-center gap-2">
                  {/*
                    §12.1: colour is never the only signal. The swatch is
                    decorative and the name carries the meaning, so the swatch is
                    aria-hidden rather than labelled.
                  */}
                  <span
                    aria-hidden="true"
                    className="inline-block size-3 rounded-full"
                    style={{ backgroundColor: status.colour ?? undefined }}
                  />
                  <span className="font-medium text-content">{status.name}</span>
                  <code className="text-xs text-content-muted">{status.code}</code>
                </span>
              </TableCell>
              <TableCell>
                <Chip variant="neutral">
                  {CATEGORY_LABELS[status.category ?? 'TODO']}
                </Chip>
              </TableCell>
              <TableCell>{status.seq}</TableCell>
              <TableCell>{status.isOpen ? 'Yes' : 'No'}</TableCell>
              <TableCell>{status.isTerminal ? 'Yes' : 'No'}</TableCell>
              <TableCell className="text-right tabular-nums">{status.ticketCount}</TableCell>
              <TableCell className="text-right tabular-nums">
                {status.transitionCount}
              </TableCell>
              <TableCell>
                {status.isActive
                  ? <Chip variant="success">Active</Chip>
                  : <Chip variant="neutral">Retired</Chip>}
              </TableCell>
              <TableCell className="text-right">
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => onEdit(status.id!)}
                  aria-label={`Edit ${status.name}`}
                >
                  Edit
                </Button>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  )
}

/**
 * The create and edit dialog.
 *
 * One component for both, as S-12's is: the fields are identical and the only
 * difference is where the initial values come from and which mutation runs.
 */
function StatusDialog({
  title,
  statusId,
  availableCodes,
  onClose,
}: {
  title: string
  statusId?: number
  availableCodes: readonly StatusCode[]
  onClose: () => void
}) {
  const editing = statusId != null
  const { data: loaded, isPending } = useStatus(statusId ?? null)
  const create = useCreateStatus()
  const update = useUpdateStatus()

  const [values, setValues] = React.useState<StatusFormValues>(() => ({
    ...emptyStatusForm,
    code: availableCodes[0] ?? 'NEW',
  }))
  const [serverErrors, setServerErrors] = React.useState<Record<string, string>>({})

  React.useEffect(() => {
    if (loaded) setValues(toFormValues(loaded.status))
  }, [loaded])

  const errors = { ...statusFormErrors(values), ...serverErrors }
  const set = <K extends keyof StatusFormValues>(key: K, value: StatusFormValues[K]) => {
    setValues((v) => ({ ...v, [key]: value }))
    // The server error for a field is cleared the moment that field is edited —
    // otherwise a 409 about a duplicate name stays under the box the user has
    // just fixed, and they resubmit expecting it to go away.
    setServerErrors((e) => Object.fromEntries(
      Object.entries(e).filter(([field]) => field !== key),
    ))
  }

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (Object.keys(statusFormErrors(values)).length > 0) return

    try {
      if (editing) {
        const result = await update.mutateAsync({
          statusId: statusId!,
          data: toPatchRequest(values),
          etag: loaded?.etag ?? null,
        })
        // The retire's blast radius, reported after the fact because the number
        // is only known once the server has counted. The confirmation before the
        // click states that it *will* happen; this states how much.
        const deactivated = result.deactivatedTransitions
        toast({
          title: 'Status saved',
          description: deactivated
            ? `${deactivated} transition${deactivated === 1 ? '' : 's'} deactivated. `
              + 'Reactivating the status does not bring them back — restore them on the '
              + 'matrix below.'
            : undefined,
        })
      } else {
        await create.mutateAsync(toWriteRequest(values))
        toast({ title: 'Status created' })
      }
      onClose()
    } catch (error) {
      setServerErrors(fieldErrors(error))
    }
  }

  const retiring = editing && loaded?.status.isActive === true && !values.isActive

  return (
    <Modal open onOpenChange={(next) => { if (!next) onClose() }}>
      <ModalContent>
        <form onSubmit={submit}>
          <ModalHeader>
            <ModalTitle>{title}</ModalTitle>
            <ModalDescription>
              The code is permanent — every ticket ever raised stores it as a string, so a
              rename would orphan rather than cascade.
            </ModalDescription>
          </ModalHeader>

          {isPending && editing ? (
            <Skeleton className="h-64 w-full" />
          ) : (
            <div className="flex flex-col gap-4 py-4">
              <label className="flex flex-col gap-1 text-sm">
                <span className="font-medium text-content">Code</span>
                <select
                  className="rounded-md border border-line bg-surface px-3 py-2 text-sm disabled:opacity-60"
                  value={values.code}
                  disabled={editing}
                  onChange={(e) => set('code', e.target.value as StatusCode)}
                >
                  {(editing ? [values.code] : availableCodes).map((code) => (
                    <option key={code} value={code}>{code}</option>
                  ))}
                </select>
                {errors.code && <span className="text-xs text-danger">{errors.code}</span>}
              </label>

              <label className="flex flex-col gap-1 text-sm">
                <span className="font-medium text-content">Name</span>
                <Input
                  value={values.name}
                  maxLength={40}
                  onChange={(e) => set('name', e.target.value)}
                  aria-invalid={errors.name ? true : undefined}
                />
                {errors.name && <span className="text-xs text-danger">{errors.name}</span>}
              </label>

              <label className="flex flex-col gap-1 text-sm">
                <span className="font-medium text-content">Category</span>
                <select
                  className="rounded-md border border-line bg-surface px-3 py-2 text-sm"
                  value={values.category}
                  onChange={(e) => set('category', e.target.value as StatusFormValues['category'])}
                >
                  {STATUS_CATEGORIES.map((category) => (
                    <option key={category} value={category}>{CATEGORY_LABELS[category]}</option>
                  ))}
                </select>
                <span className="text-xs text-content-muted">
                  How boards and reports group this status. Independent of the two switches
                  below: Resolved is <em>Done</em> work on a ticket that still counts as open.
                </span>
              </label>

              <fieldset className="flex flex-col gap-2">
                <legend className="text-sm font-medium text-content">Colour</legend>
                <div className="flex flex-wrap gap-2">
                  {STATUS_PALETTE.map((colour) => (
                    <button
                      key={colour}
                      type="button"
                      aria-label={colour}
                      aria-pressed={values.colour === colour}
                      onClick={() => set('colour', colour)}
                      className={[
                        'size-7 rounded-full border-2',
                        values.colour === colour ? 'border-content' : 'border-transparent',
                      ].join(' ')}
                      style={{ backgroundColor: colour }}
                    />
                  ))}
                </div>
                {errors.colour && <span className="text-xs text-danger">{errors.colour}</span>}
              </fieldset>

              <label className="flex flex-col gap-1 text-sm">
                <span className="font-medium text-content">Order</span>
                <Input
                  value={values.seq}
                  inputMode="numeric"
                  placeholder="Blank to add at the end"
                  onChange={(e) => set('seq', e.target.value)}
                />
                {errors.seq && <span className="text-xs text-danger">{errors.seq}</span>}
              </label>

              {/*
                The three switches below carry an explicit `aria-label` even
                though each sits inside a `<label>`. Without one the accessible
                name is the label's whole text content — the helper sentence
                included — so a screen reader announces a paragraph where a
                control name belongs.
              */}
              <label className="flex items-start gap-2 text-sm">
                <input
                  type="checkbox"
                  aria-label="Counts as open"
                  checked={values.isOpen}
                  onChange={(e) => set('isOpen', e.target.checked)}
                />
                <span>
                  <span className="font-medium text-content">Counts as open</span>
                  <span className="block text-xs text-content-muted">
                    Included in every &ldquo;open tickets&rdquo; figure on the dashboard.
                  </span>
                </span>
              </label>

              <label className="flex items-start gap-2 text-sm">
                <input
                  type="checkbox"
                  aria-label="Terminal"
                  checked={values.isTerminal}
                  onChange={(e) => set('isTerminal', e.target.checked)}
                />
                <span>
                  <span className="font-medium text-content">Terminal</span>
                  <span className="block text-xs text-content-muted">
                    Only a reopen moves a ticket out of this state.
                  </span>
                </span>
              </label>
              {errors.isTerminal && (
                <span className="text-xs text-danger">{errors.isTerminal}</span>
              )}

              {editing && (
                <label className="flex items-start gap-2 text-sm">
                  <input
                    type="checkbox"
                    aria-label="Active"
                    checked={values.isActive}
                    onChange={(e) => set('isActive', e.target.checked)}
                  />
                  <span>
                    <span className="font-medium text-content">Active</span>
                    <span className="block text-xs text-content-muted">
                      Unticking retires the status. There is no delete.
                    </span>
                  </span>
                </label>
              )}
              {errors.isActive && (
                <span className="text-xs text-danger">{errors.isActive}</span>
              )}

              {/*
                Stated before the click, not after. Somebody who reads the
                consequence afterwards has already pressed the button believing
                something else.
              */}
              {retiring && (
                <p className="rounded-md border border-warning/40 bg-warning/10 p-3 text-xs text-content">
                  Retiring deactivates all {loaded?.status.transitionCount} transitions into
                  and out of this status, and reactivating it will not bring them back —
                  they are restored on the matrix below. The save is refused outright if any
                  ticket is still in this status.
                </p>
              )}
            </div>
          )}

          <ModalFooter>
            <Button type="button" variant="ghost" onClick={onClose}>Cancel</Button>
            <Button type="submit" disabled={create.isPending || update.isPending}>
              Save
            </Button>
          </ModalFooter>
        </form>
      </ModalContent>
    </Modal>
  )
}

/**
 * The allowed-transition matrix — moves down the side, roles across the top.
 *
 * **A whitelist**: an unticked cell means the move is impossible for that role,
 * and there is no second place the server consults. Which is why governance
 * decision G-3 — may a Developer close a ticket? — is data rather than code, and
 * why this grid <em>flags</em> those cells rather than locking them. An
 * organisation whose sign-off process differs from ours has to be able to say
 * so; what it is owed is knowing which cells already carry somebody's decision.
 */
function TransitionMatrix({ statuses }: { statuses: Status[] }) {
  const { data, isPending, isError } = useStatusTransitions()
  const replace = useReplaceStatusTransitions()

  const [draft, setDraft] = React.useState<MatrixRow[] | null>(null)
  const [error, setError] = React.useState<string | null>(null)

  const serverRows = React.useMemo(
    () => (data ? toMatrixRows(data.transitions, statuses) : []),
    [data, statuses],
  )
  const rows = draft ?? serverRows

  const roles = React.useMemo(() => {
    const seen = new Set<string>()
    serverRows.forEach((row) => Object.keys(row.cells).forEach((r) => seen.add(r)))
    return [...seen].sort()
  }, [serverRows])

  const dirty = draft != null
  const canSave = dirty && matrixHasOnCreateMove(rows)

  const toggle = (rowIndex: number, role: string) => {
    setError(null)
    setDraft(
      rows.map((row, i) => {
        if (i !== rowIndex) return row
        const cell = row.cells[role] ?? {
          allowed: false, requiresReason: false, requiresEffort: false, wasCleared: false,
        }
        return { ...row, cells: { ...row.cells, [role]: { ...cell, allowed: !cell.allowed } } }
      }),
    )
  }

  const save = async () => {
    setError(null)
    try {
      await replace.mutateAsync({
        data: { transitions: toMatrixWriteRequest(rows) },
        etag: data?.etag ?? null,
      })
      setDraft(null)
      toast({ title: 'Transition matrix saved' })
    } catch (caught) {
      setError(problemDetail(caught) ?? 'The matrix could not be saved.')
    }
  }

  if (isPending) return <Skeleton className="h-64 w-full" />
  if (isError) {
    return <p className="text-sm text-danger">Could not load the transition matrix.</p>
  }

  return (
    <section className="flex flex-col gap-4">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-content">Allowed transitions</h2>
          <p className="mt-1 max-w-3xl text-sm text-content-muted">
            A ticked cell is the only thing that permits a move — there is no default and
            no second rule. <strong>On creation</strong> is how a ticket enters the system;
            at least one of those must stay ticked or nobody can raise a ticket at all.
          </p>
        </div>
        <div className="flex gap-2">
          {dirty && (
            <Button variant="ghost" onClick={() => { setDraft(null); setError(null) }}>
              Discard
            </Button>
          )}
          <Button onClick={save} disabled={!canSave || replace.isPending}>
            Save matrix
          </Button>
        </div>
      </div>

      {dirty && !matrixHasOnCreateMove(rows) && (
        <p role="alert" className="rounded-md border border-danger/40 bg-danger/10 p-3 text-sm text-content">
          At least one <strong>on creation</strong> move must stay ticked. With none, no
          role can raise a ticket on any screen — and this is the only screen that could
          undo it.
        </p>
      )}
      {error && (
        <p role="alert" className="rounded-md border border-danger/40 bg-danger/10 p-3 text-sm text-content">
          {error}
        </p>
      )}

      <TableContainer>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Move</TableHead>
              {roles.map((role) => <TableHead key={role}>{role}</TableHead>)}
              <TableHead>Requires</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((row, rowIndex) => {
              const key = moveKey(row.fromStatus, row.toStatus)
              const note = GOVERNANCE_NOTES[key]
              const sample = Object.values(row.cells)[0]
              return (
                <TableRow key={key}>
                  <TableCell>
                    <span className="flex flex-col">
                      <span className="font-medium text-content">
                        {row.fromStatus ? nameOf(row.fromStatus, statuses) : 'On creation'}
                        {' → '}
                        {nameOf(row.toStatus, statuses)}
                      </span>
                      {note && (
                        <span className="text-xs text-content-muted" title={note}>
                          ⚑ {note}
                        </span>
                      )}
                    </span>
                  </TableCell>
                  {roles.map((role) => {
                    const cell = row.cells[role]
                    return (
                      <TableCell key={role}>
                        <input
                          type="checkbox"
                          checked={cell?.allowed ?? false}
                          onChange={() => toggle(rowIndex, role)}
                          aria-label={`${role}: ${row.fromStatus ?? 'on creation'} to ${row.toStatus}`}
                        />
                      </TableCell>
                    )
                  })}
                  <TableCell className="text-xs text-content-muted">
                    {[
                      sample?.requiresReason ? 'a reason' : null,
                      sample?.requiresEffort ? 'effort logged' : null,
                    ].filter(Boolean).join(', ') || '—'}
                  </TableCell>
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
      </TableContainer>
    </section>
  )
}

// ---------------------------------------------------------------------------

const nameOf = (code: StatusCode, statuses: Status[]) =>
  statuses.find((s) => s.code === code)?.name ?? code

/**
 * The server's field-keyed 409s and 400s, onto the inputs they came from.
 *
 * Every refusal on this screen carries an `errors` map for exactly this — the
 * four the browser cannot check (code and name uniqueness, live tickets blocking
 * a retire, a ninth code) land on the field rather than in a banner nobody
 * associates with an input.
 */
function fieldErrors(error: unknown): Record<string, string> {
  if (!(error instanceof ApiError)) return {}
  const errors = (error.problem as { errors?: Record<string, string[]> }).errors
  if (!errors) return {}
  return Object.fromEntries(
    Object.entries(errors).map(([field, messages]) => [field, messages[0] ?? '']),
  )
}

function problemDetail(error: unknown): string | null {
  if (!(error instanceof ApiError)) return null
  return (error.problem as { detail?: string }).detail ?? null
}
