import * as React from 'react'

import { ApiError } from '@/api/http'
import type { ObImplementationStage } from '@/api/generated/model/obImplementationStage'

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
  useCreateImplementationStage,
  useImplementationStage,
  useImplementationStages,
  useUpdateImplementationStage,
} from './implementationStageQueries'

/**
 * OB-15 · the Implementation Stage master.
 *
 * The vocabulary an implementation is described in — Configuration, Data
 * Migration, Reports, Training, Communication, Third Party Integration —
 * seeded by migration and extended from this screen. One page: the ordered
 * list, a create dialog and an edit dialog, which is the shape every master in
 * this codebase takes.
 *
 * <h2>Position is a number in a form, not a drag handle</h2>
 *
 * The two ways to reorder a list are arrows on the row and a position field in
 * the edit form; this screen has the second. It costs a dialog per move, and
 * buys three things worth more on a master of this size: it is reachable by
 * keyboard and screen reader with no extra work (CLAUDE.md's WCAG AA rule),
 * it needs no drag-and-drop dependency, and — the reason that actually
 * settled it — it can be preconditioned. A position typed against a list
 * somebody else has just reordered means something different from what its
 * author intended, and the `If-Match` on the `PATCH` refuses exactly that
 * save. An arrow control has no read to draw a tag from.
 *
 * **The server owns the numbering.** A save sends a position and the master
 * comes back renumbered 1..N, so rows nobody edited move. That is why every
 * write invalidates the whole list rather than patching one row into the
 * cache — `implementationStageQueries.ts` carries the reasoning.
 *
 * <h2>Retire, never delete</h2>
 *
 * There is no delete button because there is no delete route. Nothing points
 * at these rows today, which is precisely why the restraint is built in now:
 * the moment a stage is recorded against a step or a report filter, deleting
 * one turns those references into dangling text. A retired stage keeps its
 * slot in this list and drops out of the pickers.
 */
export function ObImplementationStagePage() {
  const { data: stages, isPending, isError } = useImplementationStages()
  const [creating, setCreating] = React.useState(false)
  const [editingId, setEditingId] = React.useState<number | null>(null)

  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-6 p-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-content">Implementation stage</h1>
          <p className="mt-1 max-w-2xl text-sm text-content-muted">
            The stages an implementation is described in. Add as many as the organisation
            needs, and set the order they appear in by editing a stage&rsquo;s position.
            Stages cannot be deleted — anything already recorded against one still has to be
            able to name it — but they can be retired, which drops them out of the pickers
            and leaves them here.
          </p>
        </div>
        <Button onClick={() => setCreating(true)}>New stage</Button>
      </header>

      {isPending ? (
        <Skeleton className="h-64 w-full" />
      ) : isError || !stages ? (
        <p className="text-sm text-danger-text">Implementation stages could not be loaded.</p>
      ) : (
        <TableContainer>
          <Table>
            <TableHeader>
              <TableRow>
                {/* Narrow and first: it is the column the order is read from. */}
                <TableHead scope="col" className="w-20">
                  Position
                </TableHead>
                <TableHead scope="col">Stage</TableHead>
                <TableHead scope="col" className="w-28">
                  Status
                </TableHead>
                <TableHead scope="col" className="w-24">
                  <span className="sr-only">Actions</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {stages.map((stage) => (
                <StageRow
                  key={stage.id}
                  stage={stage}
                  onEdit={() => setEditingId(stage.id ?? null)}
                />
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      <CreateStageDialog
        open={creating}
        nextPosition={(stages?.length ?? 0) + 1}
        onOpenChange={setCreating}
      />
      <EditStageDialog
        stageId={editingId}
        total={stages?.length ?? 0}
        onClose={() => setEditingId(null)}
      />
    </div>
  )
}

function StageRow({ stage, onEdit }: { stage: ObImplementationStage; onEdit: () => void }) {
  return (
    <TableRow>
      <TableCell className="tabular-nums text-content-muted">{stage.sequence}</TableCell>
      <TableCell className="font-medium text-content">{stage.name}</TableCell>
      <TableCell>
        {stage.isActive ? (
          <Chip variant="success">Active</Chip>
        ) : (
          <Chip variant="neutral">Retired</Chip>
        )}
      </TableCell>
      <TableCell>
        <Button
          variant="secondary"
          onClick={onEdit}
          // Named for a screen reader, because "Edit" five times in a column
          // says nothing about which row it edits.
          aria-label={`Edit ${stage.name}`}
        >
          Edit
        </Button>
      </TableCell>
    </TableRow>
  )
}

// ── the shared form ────────────────────────────────────────────────────────

interface StageFormValues {
  name: string
  /** Held as a string, not a number: an empty input is a real state a `number` cannot hold. */
  position: string
  isActive: boolean
}

type StageFormErrors = Partial<Record<'name' | 'position', string>>

/**
 * The only validation done here is the part the server cannot express better.
 *
 * A blank name is refused locally because a round trip to be told so is a
 * round trip wasted. An out-of-range position is **not** refused: the server
 * clamps it deliberately — a large number means last — so rejecting it here
 * would invent a rule the API does not have and refuse an intent it can
 * satisfy exactly.
 */
function formErrors(values: StageFormValues): StageFormErrors {
  const errors: StageFormErrors = {}
  if (!values.name.trim()) errors.name = 'A name is required.'
  if (values.position.trim() && !/^\d+$/.test(values.position.trim())) {
    errors.position = 'Position must be a whole number.'
  }
  return errors
}

/** 409s and 400s are field-keyed, so they land on the input rather than in a toast. */
function fieldErrorsFrom(e: ApiError): StageFormErrors {
  const problem = e.problem as { errors?: Record<string, string[]> }
  const server = problem.errors ?? {}
  const mapped: StageFormErrors = {}
  if (server.name?.[0]) mapped.name = server.name[0]
  if (server.sequence?.[0]) mapped.position = server.sequence[0]
  return mapped
}

function StageFields({
  values,
  errors,
  total,
  onChange,
}: {
  values: StageFormValues
  errors: StageFormErrors
  /** How many stages the master holds, for the hint under the position field. */
  total: number
  onChange: (patch: Partial<StageFormValues>) => void
}) {
  return (
    /*
      `htmlFor` and an id rather than a wrapping `<label>`, which is what the
      rest of this codebase reaches for first. A wrapping label takes its
      accessible name from everything inside it, so the hint under the position
      field would become part of the field's name — "Position 1 is first, 6 is
      last…" is what a screen reader would announce, and what a test looking
      for the field by name would fail to find. Split, the name is the label
      and the hint arrives through `aria-describedby`, which is the role it was
      written for.
    */
    <div className="flex flex-col gap-4 px-6 py-4">
      <div className="flex flex-col gap-1 text-sm">
        <label htmlFor="stage-name" className="font-medium text-content">
          Stage name
        </label>
        <Input
          id="stage-name"
          value={values.name}
          maxLength={120}
          autoFocus
          aria-invalid={errors.name ? true : undefined}
          aria-describedby={errors.name ? 'stage-name-error' : undefined}
          onChange={(e) => onChange({ name: e.target.value })}
        />
        {errors.name ? (
          <span id="stage-name-error" className="text-xs text-danger-text">
            {errors.name}
          </span>
        ) : null}
      </div>

      <div className="flex flex-col gap-1 text-sm">
        <label htmlFor="stage-position" className="font-medium text-content">
          Position
        </label>
        <Input
          id="stage-position"
          // `inputMode` rather than `type="number"`, so a stray scroll over a
          // focused field cannot silently reorder the master.
          inputMode="numeric"
          value={values.position}
          className="w-24"
          aria-invalid={errors.position ? true : undefined}
          aria-describedby={
            errors.position ? 'stage-position-hint stage-position-error' : 'stage-position-hint'
          }
          onChange={(e) => onChange({ position: e.target.value })}
        />
        <span id="stage-position-hint" className="text-xs text-content-muted">
          {total > 0
            ? `1 is first, ${total} is last. Everything else shifts to make room.`
            : 'The first stage in the list.'}
        </span>
        {errors.position ? (
          <span id="stage-position-error" className="text-xs text-danger-text">
            {errors.position}
          </span>
        ) : null}
      </div>

      <fieldset className="flex flex-col gap-2 rounded-card bg-subtle p-3 text-sm">
        <legend className="sr-only">Availability</legend>
        <div className="flex items-start gap-2">
          <input
            id="stage-active"
            type="checkbox"
            className="mt-1"
            checked={values.isActive}
            aria-describedby="stage-active-hint"
            onChange={(e) => onChange({ isActive: e.target.checked })}
          />
          <span>
            <label htmlFor="stage-active" className="font-medium text-content">
              Available for selection
            </label>
            <span id="stage-active-hint" className="mt-1 block text-xs text-content-muted">
              Retiring a stage removes it from the pickers and changes nothing already
              recorded against it. It keeps this position, so bringing it back puts it where
              it was.
            </span>
          </span>
        </div>
      </fieldset>
    </div>
  )
}

// ── create ─────────────────────────────────────────────────────────────────

const emptyForm: StageFormValues = { name: '', position: '', isActive: true }

function CreateStageDialog({
  open,
  nextPosition,
  onOpenChange,
}: {
  open: boolean
  nextPosition: number
  onOpenChange: (open: boolean) => void
}) {
  const create = useCreateImplementationStage()
  const [values, setValues] = React.useState<StageFormValues>(emptyForm)
  const [errors, setErrors] = React.useState<StageFormErrors>({})

  React.useEffect(() => {
    if (!open) return
    // Pre-filled with the end of the list, which is where a new stage goes
    // unless somebody says otherwise. Showing the number beats an empty box
    // whose meaning ("append") the user has to know.
    setValues({ ...emptyForm, position: String(nextPosition) })
    setErrors({})
  }, [open, nextPosition])

  const onChange = (patch: Partial<StageFormValues>) =>
    setValues((current) => ({ ...current, ...patch }))

  const onSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    const found = formErrors(values)
    setErrors(found)
    if (Object.keys(found).length > 0) return

    create.mutate(
      {
        name: values.name.trim(),
        // Omitted when it is the append position anyway, so the common case
        // sends the smaller claim and the server renumbers nothing.
        sequence:
          values.position.trim() && Number(values.position) !== nextPosition
            ? Number(values.position)
            : undefined,
        isActive: values.isActive,
      },
      {
        onSuccess: (stage) => {
          onOpenChange(false)
          toast({
            title: `${stage.name} added`,
            description: `It sits at position ${stage.sequence}.`,
          })
        },
        onError: (e: ApiError) => {
          const fields = fieldErrorsFrom(e)
          if (Object.keys(fields).length > 0) {
            setErrors(fields)
            return
          }
          toast({ variant: 'danger', title: 'Could not add the stage' })
        },
      },
    )
  }

  return (
    <Modal open={open} onOpenChange={onOpenChange}>
      <ModalContent>
        <form onSubmit={onSubmit}>
          <ModalHeader>
            <ModalTitle>New implementation stage</ModalTitle>
            <ModalDescription>
              It joins the master immediately and is offered wherever an implementation
              stage is chosen.
            </ModalDescription>
          </ModalHeader>

          <StageFields
            values={values}
            errors={errors}
            total={Math.max(nextPosition - 1, 0)}
            onChange={onChange}
          />

          <ModalFooter>
            <Button type="button" variant="secondary" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={create.isPending}>
              {create.isPending ? 'Adding…' : 'Add stage'}
            </Button>
          </ModalFooter>
        </form>
      </ModalContent>
    </Modal>
  )
}

// ── edit, reorder and retire ───────────────────────────────────────────────

function EditStageDialog({
  stageId,
  total,
  onClose,
}: {
  stageId: number | null
  total: number
  onClose: () => void
}) {
  const { data, isPending } = useImplementationStage(stageId)
  const update = useUpdateImplementationStage()
  const [values, setValues] = React.useState<StageFormValues | null>(null)
  const [errors, setErrors] = React.useState<StageFormErrors>({})

  // Seeded from the read rather than from the list row, because the read is
  // what carries the `ETag` — editing values the tag does not cover would make
  // the precondition a formality. It matters more here than on most masters:
  // the tag covers `sequence`, so a reorder by somebody else while this form is
  // open is caught rather than silently applied to a list that has moved.
  React.useEffect(() => {
    setValues(
      data
        ? {
            name: data.stage.name,
            position: String(data.stage.sequence),
            isActive: data.stage.isActive,
          }
        : null,
    )
    setErrors({})
  }, [data])

  if (stageId == null) return null

  const onChange = (patch: Partial<StageFormValues>) =>
    setValues((current) => (current ? { ...current, ...patch } : current))

  const onSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    if (!values || !data) return
    const found = formErrors(values)
    setErrors(found)
    if (Object.keys(found).length > 0) return

    const position = Number(values.position)
    update.mutate(
      {
        stageId,
        etag: data.etag,
        data: {
          name: values.name.trim(),
          // Sent only when it actually changed. Saying nothing about order is a
          // distinct answer from restating the current position, and the
          // smaller claim is the honest one for a rename.
          sequence:
            values.position.trim() && position !== data.stage.sequence ? position : undefined,
          isActive: values.isActive,
        },
      },
      {
        onSuccess: (stage) => {
          onClose()
          toast({
            title: `${stage.name} saved`,
            // The server may have clamped the position, so the toast reports
            // where the stage actually ended up rather than what was typed.
            description: `It sits at position ${stage.sequence}.`,
          })
        },
        onError: (e: ApiError) => {
          if (e.status === 412) {
            toast({
              variant: 'danger',
              title: 'Someone else changed this stage',
              description: 'Close and reopen the form to see the current order, then reapply.',
            })
            return
          }
          const fields = fieldErrorsFrom(e)
          if (Object.keys(fields).length > 0) {
            setErrors(fields)
            return
          }
          toast({ variant: 'danger', title: 'Could not save the stage' })
        },
      },
    )
  }

  return (
    <Modal open onOpenChange={(next) => (next ? undefined : onClose())}>
      <ModalContent>
        <form onSubmit={onSubmit}>
          <ModalHeader>
            <ModalTitle>Edit implementation stage</ModalTitle>
            <ModalDescription>
              Changing the position moves every stage between here and there by one.
            </ModalDescription>
          </ModalHeader>

          {isPending || !values ? (
            <div className="px-6 py-4">
              <Skeleton className="h-48 w-full" />
            </div>
          ) : (
            <StageFields values={values} errors={errors} total={total} onChange={onChange} />
          )}

          <ModalFooter>
            <Button type="button" variant="secondary" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={update.isPending || isPending || !values}>
              {update.isPending ? 'Saving…' : 'Save stage'}
            </Button>
          </ModalFooter>
        </form>
      </ModalContent>
    </Modal>
  )
}
