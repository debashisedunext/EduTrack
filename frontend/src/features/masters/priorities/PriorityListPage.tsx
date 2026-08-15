import * as React from 'react'

import { ApiError } from '@/api/http'
import type { Level } from '@/api/generated/model/level'
import type { Priority } from '@/api/generated/model/priority'

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
  CONTRACT_LEVELS,
  PRIORITY_PALETTE,
  emptyPriorityForm,
  priorityFormErrors,
  toFormValues,
  toPatchRequest,
  toWriteRequest,
  type PriorityFormValues,
} from './priorityForm'
import {
  useCreatePriority,
  usePriorities,
  usePriority,
  useUpdatePriority,
} from './priorityQueries'

/**
 * S-12 Priority / Level Master. B-021.
 *
 * One page: the grid, a create dialog and an edit dialog — the shape B-020 gave
 * S-11 and predicted this screen would take.
 *
 * **Three usage counts sit on every row, not just in the retire confirmation**,
 * and unlike the task type master they are not three shades of one number. Each
 * is a different consequence, and only one of them can refuse: tickets keep
 * their level whatever happens here, SLA policy rows survive a retire but stop
 * resolving, and active task types defaulting to this level block it outright.
 * The grid shows all three because a retire is the consequential act on this
 * screen and its blast radius is invisible from the row.
 */
export function PriorityListPage() {
  const { data: priorities, isPending, isError } = usePriorities()
  const [creating, setCreating] = React.useState(false)
  const [editingId, setEditingId] = React.useState<number | null>(null)

  const takenLevels = React.useMemo(
    () => new Set((priorities ?? []).map((p) => p.level)),
    [priorities],
  )
  const availableLevels = CONTRACT_LEVELS.filter((level) => !takenLevels.has(level))

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6 p-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-content">Priority levels</h1>
          <p className="mt-1 max-w-2xl text-sm text-content-muted">
            The severity a ticket is raised at. Order is severity rank, and it is also the
            column order of every project&rsquo;s SLA matrix. Levels cannot be deleted — every
            ticket ever raised at one still has to be able to name it — but they can be
            retired, which removes them from the create form and drops their column out of
            every SLA matrix.
          </p>
        </div>
        <Button onClick={() => setCreating(true)} disabled={availableLevels.length === 0}>
          New level
        </Button>
      </header>

      {/*
        The honest statement of what this screen cannot yet do. S-12 says an
        Admin can add levels without a release, and the API's `Level` is a
        closed four-value enum that types seven ticket and SLA schemas — so a
        fifth would serialise into a response the generated client rejects.
        Saying so beats a button that always fails.
      */}
      {availableLevels.length === 0 ? (
        <p className="rounded-card bg-subtle p-3 text-xs text-content-muted">
          All four levels the API can carry are in use. Adding a fifth needs the contract&rsquo;s{' '}
          <code>Level</code> enum opened, which retypes the ticket and SLA schemas across
          three streams — until then this master edits the four rather than extending them.
        </p>
      ) : null}

      {isPending ? (
        <Skeleton className="h-64 w-full" />
      ) : isError || !priorities ? (
        <p className="text-sm text-danger-text">Priority levels could not be loaded.</p>
      ) : (
        <TableContainer>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead scope="col">Level</TableHead>
                <TableHead scope="col">Code</TableHead>
                <TableHead scope="col">Default SLA</TableHead>
                <TableHead scope="col">Tickets</TableHead>
                <TableHead scope="col">Task types</TableHead>
                <TableHead scope="col">SLA rows</TableHead>
                <TableHead scope="col">Status</TableHead>
                <TableHead scope="col">
                  <span className="sr-only">Actions</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {priorities.map((priority) => (
                <PriorityRow
                  key={priority.id}
                  priority={priority}
                  onEdit={() => setEditingId(priority.id ?? null)}
                />
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      <CreatePriorityDialog
        open={creating}
        availableLevels={availableLevels}
        onOpenChange={setCreating}
      />
      <EditPriorityDialog priorityId={editingId} onClose={() => setEditingId(null)} />
    </div>
  )
}

function PriorityRow({ priority, onEdit }: { priority: Priority; onEdit: () => void }) {
  return (
    <TableRow>
      <TableCell>
        <span className="flex items-center gap-2">
          {/*
            The swatch is decorative and the name beside it is the label —
            blueprint §12.1: "never colour alone". A colour-blind user and a
            printed page both read the same row.
          */}
          <span
            aria-hidden
            className="inline-block h-3 w-3 shrink-0 rounded-full"
            style={{ backgroundColor: priority.colour }}
          />
          <span className="font-medium text-content">{priority.name}</span>
          {priority.autoEscalates ? (
            <Chip variant="critical">Escalation target</Chip>
          ) : null}
        </span>
      </TableCell>
      <TableCell>
        <code className="text-xs text-content-muted">{priority.level}</code>
      </TableCell>
      <TableCell>
        {priority.defaultSlaHrs == null ? '—' : `${priority.defaultSlaHrs} h`}
      </TableCell>
      <TableCell>{priority.ticketCount ?? 0}</TableCell>
      <TableCell>{priority.taskTypeCount ?? 0}</TableCell>
      <TableCell>{priority.slaPolicyCount ?? 0}</TableCell>
      <TableCell>
        <Chip variant={priority.isActive ? 'success' : 'neutral'}>
          {priority.isActive ? 'Active' : 'Retired'}
        </Chip>
      </TableCell>
      <TableCell className="text-right">
        <Button variant="ghost" size="sm" onClick={onEdit}>
          Edit
        </Button>
      </TableCell>
    </TableRow>
  )
}

// ── the shared field set ────────────────────────────────────────────────────

/**
 * One field set for both dialogs, because it is one form.
 *
 * Two components would be the same file twice with one copy always slightly
 * behind — B-011's argument for a single resource form, at a smaller scale. The
 * only differences between create and edit are whether `level` is editable and
 * whether the escalation control is already held, and both are props rather
 * than a fork.
 */
function PriorityFields({
  values,
  errors,
  levelOptions,
  levelLocked,
  holdsEscalationFlag,
  onChange,
}: {
  values: PriorityFormValues
  errors: Partial<Record<keyof PriorityFormValues, string>>
  levelOptions: readonly Level[]
  levelLocked: boolean
  holdsEscalationFlag: boolean
  onChange: (patch: Partial<PriorityFormValues>) => void
}) {
  return (
    <div className="flex flex-col gap-4 py-4">
      <label className="flex flex-col gap-1 text-sm">
        <span className="font-medium text-content">Code</span>
        {levelLocked ? (
          <Input value={values.level} disabled aria-describedby="priority-level-hint" />
        ) : (
          <select
            className="h-10 rounded-control border border-border bg-surface px-3 text-sm text-content"
            value={values.level}
            aria-describedby="priority-level-hint"
            onChange={(e) => onChange({ level: e.target.value as Level })}
          >
            {levelOptions.map((level) => (
              <option key={level} value={level}>
                {level}
              </option>
            ))}
          </select>
        )}
        <span id="priority-level-hint" className="text-xs text-content-muted">
          {levelLocked
            ? 'Permanent. Tickets, task-type defaults and SLA policy rows all store this string with no key behind it, so a rename would orphan them rather than follow them.'
            : 'A dropdown, not a free-text box: the API carries this as a closed four-value type.'}
        </span>
        {errors.level ? (
          <span role="alert" className="text-xs text-danger-text">
            {errors.level}
          </span>
        ) : null}
      </label>

      <label className="flex flex-col gap-1 text-sm">
        <span className="font-medium text-content">Name</span>
        <Input
          value={values.name}
          required
          aria-invalid={errors.name != null}
          aria-describedby={errors.name ? 'priority-name-error' : undefined}
          placeholder="High"
          onChange={(e) => onChange({ name: e.target.value })}
        />
        {errors.name ? (
          <span id="priority-name-error" role="alert" className="text-xs text-danger-text">
            {errors.name}
          </span>
        ) : null}
      </label>

      <fieldset className="flex flex-col gap-2 text-sm">
        <legend className="font-medium text-content">Colour</legend>
        {/*
          A palette, not a hex box. CLAUDE.md: never introduce a colour that is
          not a §12.1 token. The server only checks the shape — it has no
          palette to check against — so the choice is constrained here, where
          the choosing happens.
        */}
        <div className="flex flex-wrap gap-2">
          {PRIORITY_PALETTE.map((colour) => (
            <button
              key={colour}
              type="button"
              aria-label={colour}
              aria-pressed={values.colour === colour}
              className={
                values.colour === colour
                  ? 'h-7 w-7 rounded-full ring-2 ring-primary ring-offset-2'
                  : 'h-7 w-7 rounded-full ring-1 ring-border'
              }
              style={{ backgroundColor: colour }}
              onClick={() => onChange({ colour })}
            />
          ))}
        </div>
        <span className="text-xs text-content-muted">
          Used by this grid and the Priority Split chart. The ticket chip keeps its frozen{' '}
          <code>level-*</code> token — §12.1 owns those, and they are the pairs that pass AA
          at chip text size.
        </span>
        {errors.colour ? (
          <span role="alert" className="text-xs text-danger-text">
            {errors.colour}
          </span>
        ) : null}
      </fieldset>

      <label className="flex flex-col gap-1 text-sm">
        <span className="font-medium text-content">Default SLA (working hours)</span>
        <Input
          value={values.defaultSlaHrs}
          inputMode="decimal"
          placeholder="8"
          aria-invalid={errors.defaultSlaHrs != null}
          aria-describedby={errors.defaultSlaHrs ? 'priority-sla-error' : 'priority-sla-hint'}
          onChange={(e) => onChange({ defaultSlaHrs: e.target.value })}
        />
        <span id="priority-sla-hint" className="text-xs text-content-muted">
          Rung 4 of the §6 ladder — the last rung that still varies with the level. Blank
          means this level contributes no default and resolution falls through to the task
          type. A project&rsquo;s SLA matrix overrides both.
        </span>
        {errors.defaultSlaHrs ? (
          <span id="priority-sla-error" role="alert" className="text-xs text-danger-text">
            {errors.defaultSlaHrs}
          </span>
        ) : null}
      </label>

      {/*
        §6's pointer, and the control is deliberately one-way. Exactly one active
        level is the escalation target, so the level that holds it renders as a
        checked, disabled statement rather than a checkbox somebody can clear:
        clearing the last flag is refused by the server, and a control whose only
        outcome is a 409 is a control that should not be operable. Moving the
        target is a tick on the level it moves *to*.
      */}
      <fieldset className="flex flex-col gap-2 rounded-card bg-subtle p-3 text-sm">
        <legend className="sr-only">Escalation target</legend>
        <label className="flex items-start gap-2">
          <input
            type="checkbox"
            className="mt-1"
            checked={values.autoEscalates}
            disabled={holdsEscalationFlag}
            aria-describedby="priority-escalation-hint"
            onChange={(e) => onChange({ autoEscalates: e.target.checked })}
          />
          <span>
            <span className="font-medium text-content">
              Escalate overdue tickets to this level
            </span>
            <span id="priority-escalation-hint" className="mt-1 block text-xs text-content-muted">
              {holdsEscalationFlag
                ? 'This is the level the SLA engine promotes a ticket to when it crosses its Planned Close Date (§6). It cannot be switched off here — tick this box on another level to move the target, which clears it from this one.'
                : 'Ticking this moves the escalation target here and clears it from the level that currently holds it. Exactly one active level is the target at any time.'}
            </span>
          </span>
        </label>
        {errors.autoEscalates ? (
          <span role="alert" className="text-xs text-danger-text">
            {errors.autoEscalates}
          </span>
        ) : null}
      </fieldset>

      <label className="flex flex-col gap-1 text-sm">
        <span className="font-medium text-content">Order</span>
        <Input
          value={values.seq}
          inputMode="numeric"
          placeholder="30"
          aria-invalid={errors.seq != null}
          aria-describedby={errors.seq ? 'priority-seq-error' : 'priority-seq-hint'}
          onChange={(e) => onChange({ seq: e.target.value })}
        />
        <span id="priority-seq-hint" className="text-xs text-content-muted">
          Severity rank, low to high — and the column order of every project&rsquo;s SLA
          matrix. Blank adds it at the end.
        </span>
        {errors.seq ? (
          <span id="priority-seq-error" role="alert" className="text-xs text-danger-text">
            {errors.seq}
          </span>
        ) : null}
      </label>
    </div>
  )
}

/** 409s are field-keyed, so they land on the input rather than in a toast. */
function fieldErrorsFrom(e: ApiError): Partial<Record<keyof PriorityFormValues, string>> {
  const problem = e.problem as { errors?: Record<string, string[]> }
  const server = problem.errors ?? {}
  const mapped: Partial<Record<keyof PriorityFormValues, string>> = {}
  for (const key of ['level', 'name', 'colour', 'defaultSlaHrs', 'autoEscalates', 'seq'] as const) {
    const message = server[key]?.[0]
    if (message) {
      mapped[key] = message
    }
  }
  return mapped
}

// ── create ─────────────────────────────────────────────────────────────────

function CreatePriorityDialog({
  open,
  availableLevels,
  onOpenChange,
}: {
  open: boolean
  availableLevels: readonly Level[]
  onOpenChange: (open: boolean) => void
}) {
  const create = useCreatePriority()
  const [values, setValues] = React.useState<PriorityFormValues>(emptyPriorityForm)
  const [errors, setErrors] = React.useState<
    Partial<Record<keyof PriorityFormValues, string>>
  >({})

  React.useEffect(() => {
    if (!open) {
      setValues(emptyPriorityForm)
      setErrors({})
      return
    }
    // Seeded from what is actually available rather than from the constant, so
    // the dropdown and the value it starts on cannot disagree.
    if (availableLevels.length > 0) {
      setValues({ ...emptyPriorityForm, level: availableLevels[0] })
    }
  }, [open, availableLevels])

  const onChange = (patch: Partial<PriorityFormValues>) =>
    setValues((current) => ({ ...current, ...patch }))

  const onSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    const found = priorityFormErrors(values)
    setErrors(found)
    if (Object.keys(found).length > 0) return

    create.mutate(toWriteRequest(values), {
      onSuccess: (priority) => {
        onOpenChange(false)
        toast({
          title: `${priority.name} created`,
          description: 'It is now offered on the create-ticket form and on every SLA matrix.',
        })
      },
      onError: (e: ApiError) => {
        const fields = fieldErrorsFrom(e)
        if (Object.keys(fields).length > 0) {
          setErrors(fields)
          return
        }
        toast({ variant: 'danger', title: 'Could not create the level' })
      },
    })
  }

  return (
    <Modal open={open} onOpenChange={onOpenChange}>
      <ModalContent>
        <form onSubmit={onSubmit}>
          <ModalHeader>
            <ModalTitle>New priority level</ModalTitle>
            <ModalDescription>
              It will appear on the create-ticket form immediately, and as a new column on
              every project&rsquo;s SLA matrix inheriting the org-wide defaults.
            </ModalDescription>
          </ModalHeader>

          <PriorityFields
            values={values}
            errors={errors}
            levelOptions={availableLevels}
            levelLocked={false}
            holdsEscalationFlag={false}
            onChange={onChange}
          />

          <ModalFooter>
            <Button type="button" variant="secondary" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={create.isPending}>
              {create.isPending ? 'Creating…' : 'Create level'}
            </Button>
          </ModalFooter>
        </form>
      </ModalContent>
    </Modal>
  )
}

// ── edit and retire ────────────────────────────────────────────────────────

function EditPriorityDialog({
  priorityId,
  onClose,
}: {
  priorityId: number | null
  onClose: () => void
}) {
  const { data, isPending } = usePriority(priorityId)
  const update = useUpdatePriority()
  const [values, setValues] = React.useState<PriorityFormValues | null>(null)
  const [errors, setErrors] = React.useState<
    Partial<Record<keyof PriorityFormValues, string>>
  >({})

  // Seeded from the read rather than from the grid row, because the read is
  // what carries the `ETag` — editing values the tag does not cover would make
  // the precondition guard a formality.
  React.useEffect(() => {
    setValues(data ? toFormValues(data.priority) : null)
    setErrors({})
  }, [data])

  if (priorityId == null) return null

  const onChange = (patch: Partial<PriorityFormValues>) =>
    setValues((current) => (current ? { ...current, ...patch } : current))

  const save = (override?: Partial<PriorityFormValues>) => {
    if (!values) return
    const next = { ...values, ...override }
    const found = priorityFormErrors(next)
    setErrors(found)
    if (Object.keys(found).length > 0) return

    update.mutate(
      { priorityId, data: toPatchRequest(next), etag: data?.etag ?? null },
      {
        onSuccess: (priority) => {
          onClose()
          toast({
            title: `${priority.name} saved`,
            description: priority.isActive
              ? undefined
              : 'It no longer appears on the create-ticket form, and its column has left every project’s SLA matrix. Existing tickets are unaffected.',
          })
        },
        onError: (e: ApiError) => {
          const fields = fieldErrorsFrom(e)
          if (Object.keys(fields).length > 0) {
            setErrors(fields)
            return
          }
          if (e.status === 412) {
            toast({
              variant: 'danger',
              title: 'Somebody else changed this level',
              description: 'Close the dialog and reopen it to pick up their edit.',
            })
            return
          }
          toast({ variant: 'danger', title: 'Could not save the level' })
        },
      },
    )
  }

  const priority = data?.priority
  const ticketCount = priority?.ticketCount ?? 0
  const taskTypeCount = priority?.taskTypeCount ?? 0
  const slaPolicyCount = priority?.slaPolicyCount ?? 0
  const holdsFlag = priority?.autoEscalates ?? false
  const isActive = values?.isActive ?? true
  const blocked = taskTypeCount > 0 || holdsFlag

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent>
        <form
          onSubmit={(event) => {
            event.preventDefault()
            save()
          }}
        >
          <ModalHeader>
            <ModalTitle>{priority ? `Edit ${priority.name}` : 'Edit level'}</ModalTitle>
            <ModalDescription>
              {ticketCount === 0
                ? 'No tickets have been raised at this level yet.'
                : `${ticketCount} ticket${ticketCount === 1 ? '' : 's'} carry this level. They keep it whatever you change here.`}
            </ModalDescription>
          </ModalHeader>

          {isPending || !values ? (
            <Skeleton className="my-4 h-64 w-full" />
          ) : (
            <>
              <PriorityFields
                values={values}
                errors={errors}
                levelOptions={[values.level]}
                levelLocked
                holdsEscalationFlag={holdsFlag}
                onChange={onChange}
              />

              {/*
                The retire control is here rather than as a Delete button on the
                row, and the wording says what actually happens. A "Delete" that
                deactivates is the kind of label somebody later "fixes" into a
                real delete — and here that would succeed, because nothing has a
                foreign key to this table.

                Where the task type master states one consequence, this states
                three, and says which of them can refuse.
              */}
              <div className="rounded-card bg-subtle p-3 text-xs text-content-muted">
                {isActive ? (
                  <>
                    <p className="mb-2">
                      Retiring removes this level from the create-ticket form and from the
                      ticket list&rsquo;s filter, and drops its whole column out of every
                      project&rsquo;s SLA matrix. The {ticketCount} ticket
                      {ticketCount === 1 ? '' : 's'} already raised at it keep it and still
                      render its name, and the {slaPolicyCount} SLA policy row
                      {slaPolicyCount === 1 ? '' : 's'} written at it{' '}
                      {slaPolicyCount === 1 ? 'is' : 'are'} kept too — they simply stop
                      resolving until it is brought back. Nothing is deleted.
                    </p>
                    {holdsFlag ? (
                      <p className="mb-2 text-danger-text">
                        This is the SLA engine&rsquo;s escalation target (§6). Move the target
                        to another level before retiring this one, or auto-escalation would be
                        left with nowhere to promote an overdue ticket.
                      </p>
                    ) : null}
                    {taskTypeCount > 0 ? (
                      <p className="mb-2 text-danger-text">
                        {taskTypeCount} active task type{taskTypeCount === 1 ? '' : 's'} default
                        to this level. Retiring it would leave{' '}
                        {taskTypeCount === 1 ? 'that type' : 'those types'} pre-filling the
                        create form with a level its own picker no longer offers, and failing
                        validation on the next save. Repoint{' '}
                        {taskTypeCount === 1 ? 'it' : 'them'} on the task type master first.
                      </p>
                    ) : null}
                    <Button
                      type="button"
                      variant="secondary"
                      size="sm"
                      disabled={update.isPending || blocked}
                      onClick={() => save({ isActive: false })}
                    >
                      Retire this level
                    </Button>
                  </>
                ) : (
                  <>
                    <p className="mb-2">
                      This level is retired. It is not offered on the create-ticket form and
                      has no column on any project&rsquo;s SLA matrix.
                    </p>
                    <Button
                      type="button"
                      variant="secondary"
                      size="sm"
                      disabled={update.isPending}
                      onClick={() => save({ isActive: true })}
                    >
                      Bring it back
                    </Button>
                  </>
                )}
              </div>
            </>
          )}

          <ModalFooter>
            <Button type="button" variant="secondary" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={update.isPending || !values}>
              {update.isPending ? 'Saving…' : 'Save changes'}
            </Button>
          </ModalFooter>
        </form>
      </ModalContent>
    </Modal>
  )
}
