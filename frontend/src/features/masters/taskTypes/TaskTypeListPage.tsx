import * as React from 'react'

import { ApiError } from '@/api/http'
import type { TaskType } from '@/api/generated/model/taskType'

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
  CHART_PALETTE,
  LEVELS,
  emptyTaskTypeForm,
  taskTypeFormErrors,
  toFormValues,
  toPatchRequest,
  toWriteRequest,
  type TaskTypeFormValues,
} from './taskTypeForm'
import { useCreateTaskType, useTaskType, useTaskTypes, useUpdateTaskType } from './taskTypeQueries'

/**
 * S-11 Task Type Master. B-020.
 *
 * One page: the grid, a create dialog and an edit dialog. There is no separate
 * detail route, because a task type is eight fields — B-016's project form
 * earns its own page by having twenty, and S-12's priority master will be the
 * same shape as this one.
 *
 * **`ticketCount` is on every row, not only in the retire confirmation.**
 * Retiring is the consequential act on this screen and its blast radius is
 * invisible from the row: the type leaves the create form's picker *and* every
 * project's SLA matrix. An admin should see the size of that before clicking —
 * the call B-015 made with `userCount` and B-014 with `openTicketCount`.
 */
export function TaskTypeListPage() {
  const { data: taskTypes, isPending, isError } = useTaskTypes()
  const [creating, setCreating] = React.useState(false)
  const [editingId, setEditingId] = React.useState<number | null>(null)

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6 p-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-content">Task types</h1>
          <p className="mt-1 max-w-2xl text-sm text-content-muted">
            The kinds of work a ticket can represent. Each one pre-fills the create form&rsquo;s
            level and planned close date. Types cannot be deleted — every ticket ever raised
            against one still has to be able to name it — but they can be retired, which removes
            them from the create form and from every project&rsquo;s SLA matrix.
          </p>
        </div>
        <Button onClick={() => setCreating(true)}>New task type</Button>
      </header>

      {isPending ? (
        <Skeleton className="h-64 w-full" />
      ) : isError || !taskTypes ? (
        <p className="text-sm text-danger-text">Task types could not be loaded.</p>
      ) : (
        <TableContainer>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead scope="col">Type</TableHead>
                <TableHead scope="col">Code</TableHead>
                <TableHead scope="col">Default level</TableHead>
                <TableHead scope="col">Default SLA</TableHead>
                <TableHead scope="col">Tickets</TableHead>
                <TableHead scope="col">Status</TableHead>
                <TableHead scope="col">
                  <span className="sr-only">Actions</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {taskTypes.map((type) => (
                <TaskTypeRow
                  key={type.id}
                  taskType={type}
                  onEdit={() => setEditingId(type.id ?? null)}
                />
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      <CreateTaskTypeDialog open={creating} onOpenChange={setCreating} />
      <EditTaskTypeDialog taskTypeId={editingId} onClose={() => setEditingId(null)} />
    </div>
  )
}

function TaskTypeRow({ taskType, onEdit }: { taskType: TaskType; onEdit: () => void }) {
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
            style={{ backgroundColor: taskType.colour }}
          />
          <span className="font-medium text-content">{taskType.name}</span>
        </span>
      </TableCell>
      <TableCell>
        <code className="text-xs text-content-muted">{taskType.code}</code>
      </TableCell>
      <TableCell>{taskType.defaultLevel ?? '—'}</TableCell>
      <TableCell>
        {taskType.defaultSlaHrs == null ? '—' : `${taskType.defaultSlaHrs} h`}
      </TableCell>
      <TableCell>{taskType.ticketCount ?? 0}</TableCell>
      <TableCell>
        <Chip variant={taskType.isActive ? 'success' : 'neutral'}>
          {taskType.isActive ? 'Active' : 'Retired'}
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
 * behind — B-011's argument for a single resource form, at a smaller scale.
 * The only difference between create and edit is whether `code` is editable,
 * and that is a prop rather than a fork.
 */
function TaskTypeFields({
  values,
  errors,
  codeLocked,
  onChange,
}: {
  values: TaskTypeFormValues
  errors: Partial<Record<keyof TaskTypeFormValues, string>>
  codeLocked: boolean
  onChange: (patch: Partial<TaskTypeFormValues>) => void
}) {
  return (
    <div className="flex flex-col gap-4 py-4">
      <label className="flex flex-col gap-1 text-sm">
        <span className="font-medium text-content">Code</span>
        <Input
          value={values.code}
          required
          disabled={codeLocked}
          aria-invalid={errors.code != null}
          aria-describedby={errors.code ? 'task-type-code-error' : 'task-type-code-hint'}
          placeholder="DATA_FIX"
          onChange={(e) => onChange({ code: e.target.value.toUpperCase() })}
        />
        <span id="task-type-code-hint" className="text-xs text-content-muted">
          {codeLocked
            ? 'Permanent. The Excel import matches on it, and it identifies the type to anything that cannot rely on the name.'
            : 'Permanent once saved — the Excel import matches on it. Letters, digits and underscores.'}
        </span>
        {errors.code ? (
          <span id="task-type-code-error" role="alert" className="text-xs text-danger-text">
            {errors.code}
          </span>
        ) : null}
      </label>

      <label className="flex flex-col gap-1 text-sm">
        <span className="font-medium text-content">Name</span>
        <Input
          value={values.name}
          required
          aria-invalid={errors.name != null}
          aria-describedby={errors.name ? 'task-type-name-error' : undefined}
          placeholder="Data Fix"
          onChange={(e) => onChange({ name: e.target.value })}
        />
        {errors.name ? (
          <span id="task-type-name-error" role="alert" className="text-xs text-danger-text">
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
          {CHART_PALETTE.map((colour) => (
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
        {errors.colour ? (
          <span role="alert" className="text-xs text-danger-text">
            {errors.colour}
          </span>
        ) : null}
      </fieldset>

      <label className="flex flex-col gap-1 text-sm">
        <span className="font-medium text-content">Icon</span>
        <Input
          value={values.icon}
          placeholder="database"
          aria-describedby="task-type-icon-hint"
          onChange={(e) => onChange({ icon: e.target.value })}
        />
        <span id="task-type-icon-hint" className="text-xs text-content-muted">
          A <code>lucide-react</code> icon name. Leave blank for none.
        </span>
      </label>

      <label className="flex flex-col gap-1 text-sm">
        <span className="font-medium text-content">Default level</span>
        {/*
          A native select, not the Radix one. Four fixed options with no search,
          no grouping and no custom rendering is exactly the case the platform
          control already handles — and it is keyboard- and screen-reader-correct
          for free.
        */}
        <select
          className="h-10 rounded-control border border-border bg-surface px-3 text-sm text-content"
          value={values.defaultLevel}
          onChange={(e) => onChange({ defaultLevel: e.target.value as TaskTypeFormValues['defaultLevel'] })}
        >
          {LEVELS.map((level) => (
            <option key={level} value={level}>
              {level}
            </option>
          ))}
        </select>
      </label>

      <label className="flex flex-col gap-1 text-sm">
        <span className="font-medium text-content">Default SLA (working hours)</span>
        <Input
          value={values.defaultSlaHrs}
          inputMode="decimal"
          placeholder="24"
          aria-invalid={errors.defaultSlaHrs != null}
          aria-describedby={errors.defaultSlaHrs ? 'task-type-sla-error' : 'task-type-sla-hint'}
          onChange={(e) => onChange({ defaultSlaHrs: e.target.value })}
        />
        <span id="task-type-sla-hint" className="text-xs text-content-muted">
          Blank means no default — a ticket of this type then takes its target from the priority
          level instead. A project&rsquo;s SLA matrix overrides both.
        </span>
        {errors.defaultSlaHrs ? (
          <span id="task-type-sla-error" role="alert" className="text-xs text-danger-text">
            {errors.defaultSlaHrs}
          </span>
        ) : null}
      </label>

      <label className="flex flex-col gap-1 text-sm">
        <span className="font-medium text-content">Order</span>
        <Input
          value={values.seq}
          inputMode="numeric"
          placeholder="120"
          aria-invalid={errors.seq != null}
          aria-describedby={errors.seq ? 'task-type-seq-error' : 'task-type-seq-hint'}
          onChange={(e) => onChange({ seq: e.target.value })}
        />
        <span id="task-type-seq-hint" className="text-xs text-content-muted">
          Where it sits in the picker. Blank adds it at the end.
        </span>
        {errors.seq ? (
          <span id="task-type-seq-error" role="alert" className="text-xs text-danger-text">
            {errors.seq}
          </span>
        ) : null}
      </label>
    </div>
  )
}

/** 409s are field-keyed, so they land on the input rather than in a toast. */
function fieldErrorsFrom(e: ApiError): Partial<Record<keyof TaskTypeFormValues, string>> {
  const problem = e.problem as { errors?: Record<string, string[]> }
  const server = problem.errors ?? {}
  const mapped: Partial<Record<keyof TaskTypeFormValues, string>> = {}
  for (const key of ['code', 'name', 'colour', 'defaultLevel', 'defaultSlaHrs', 'seq'] as const) {
    const message = server[key]?.[0]
    if (message) {
      mapped[key] = message
    }
  }
  return mapped
}

// ── create ─────────────────────────────────────────────────────────────────

function CreateTaskTypeDialog({
  open,
  onOpenChange,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const create = useCreateTaskType()
  const [values, setValues] = React.useState<TaskTypeFormValues>(emptyTaskTypeForm)
  const [errors, setErrors] = React.useState<
    Partial<Record<keyof TaskTypeFormValues, string>>
  >({})

  React.useEffect(() => {
    if (!open) {
      setValues(emptyTaskTypeForm)
      setErrors({})
    }
  }, [open])

  const onChange = (patch: Partial<TaskTypeFormValues>) =>
    setValues((current) => ({ ...current, ...patch }))

  const onSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    const found = taskTypeFormErrors(values)
    setErrors(found)
    if (Object.keys(found).length > 0) return

    create.mutate(toWriteRequest(values), {
      onSuccess: (type) => {
        onOpenChange(false)
        toast({
          title: `${type.name} created`,
          description: 'It is now offered on the create-ticket form.',
        })
      },
      onError: (e: ApiError) => {
        const fields = fieldErrorsFrom(e)
        if (Object.keys(fields).length > 0) {
          setErrors(fields)
          return
        }
        toast({ variant: 'danger', title: 'Could not create the task type' })
      },
    })
  }

  return (
    <Modal open={open} onOpenChange={onOpenChange}>
      <ModalContent>
        <form onSubmit={onSubmit}>
          <ModalHeader>
            <ModalTitle>New task type</ModalTitle>
            <ModalDescription>
              It will appear on the create-ticket form immediately, and as a new row on every
              project&rsquo;s SLA matrix inheriting the org-wide defaults.
            </ModalDescription>
          </ModalHeader>

          <TaskTypeFields
            values={values}
            errors={errors}
            codeLocked={false}
            onChange={onChange}
          />

          <ModalFooter>
            <Button type="button" variant="secondary" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={create.isPending}>
              {create.isPending ? 'Creating…' : 'Create task type'}
            </Button>
          </ModalFooter>
        </form>
      </ModalContent>
    </Modal>
  )
}

// ── edit and retire ────────────────────────────────────────────────────────

function EditTaskTypeDialog({
  taskTypeId,
  onClose,
}: {
  taskTypeId: number | null
  onClose: () => void
}) {
  const { data, isPending } = useTaskType(taskTypeId)
  const update = useUpdateTaskType()
  const [values, setValues] = React.useState<TaskTypeFormValues | null>(null)
  const [errors, setErrors] = React.useState<
    Partial<Record<keyof TaskTypeFormValues, string>>
  >({})

  // Seeded from the read rather than from the grid row, because the read is
  // what carries the `ETag` — editing values the tag does not cover would make
  // the precondition guard a formality.
  React.useEffect(() => {
    setValues(data ? toFormValues(data.taskType) : null)
    setErrors({})
  }, [data])

  if (taskTypeId == null) return null

  const onChange = (patch: Partial<TaskTypeFormValues>) =>
    setValues((current) => (current ? { ...current, ...patch } : current))

  const save = (override?: Partial<TaskTypeFormValues>) => {
    if (!values) return
    const next = { ...values, ...override }
    const found = taskTypeFormErrors(next)
    setErrors(found)
    if (Object.keys(found).length > 0) return

    update.mutate(
      { taskTypeId, data: toPatchRequest(next), etag: data?.etag ?? null },
      {
        onSuccess: (type) => {
          onClose()
          toast({
            title: `${type.name} saved`,
            description: type.isActive
              ? undefined
              : 'It no longer appears on the create-ticket form or on any project’s SLA matrix. Existing tickets are unaffected.',
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
              title: 'Somebody else changed this task type',
              description: 'Close the dialog and reopen it to pick up their edit.',
            })
            return
          }
          toast({ variant: 'danger', title: 'Could not save the task type' })
        },
      },
    )
  }

  const ticketCount = data?.taskType.ticketCount ?? 0
  const isActive = values?.isActive ?? true

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
            <ModalTitle>{data ? `Edit ${data.taskType.name}` : 'Edit task type'}</ModalTitle>
            <ModalDescription>
              {ticketCount === 0
                ? 'No tickets have been raised against this type yet.'
                : `${ticketCount} ticket${ticketCount === 1 ? '' : 's'} carry this type. They keep it whatever you change here.`}
            </ModalDescription>
          </ModalHeader>

          {isPending || !values ? (
            <Skeleton className="my-4 h-64 w-full" />
          ) : (
            <>
              <TaskTypeFields
                values={values}
                errors={errors}
                codeLocked
                onChange={onChange}
              />

              {/*
                The retire control is here rather than as a Delete button on the
                row, and the wording says what actually happens. A "Delete" that
                deactivates is the kind of label somebody later "fixes" into a
                real delete.
              */}
              <div className="rounded-card bg-subtle p-3 text-xs text-content-muted">
                {isActive ? (
                  <>
                    <p className="mb-2">
                      Retiring removes this type from the create-ticket form and from every
                      project&rsquo;s SLA matrix. The {ticketCount} ticket
                      {ticketCount === 1 ? '' : 's'} already raised against it keep it and still
                      render its name. Nothing is deleted, and it can be brought back.
                    </p>
                    <Button
                      type="button"
                      variant="secondary"
                      size="sm"
                      disabled={update.isPending}
                      onClick={() => save({ isActive: false })}
                    >
                      Retire this type
                    </Button>
                  </>
                ) : (
                  <>
                    <p className="mb-2">
                      This type is retired. It is not offered on the create-ticket form and does
                      not appear on any project&rsquo;s SLA matrix.
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
