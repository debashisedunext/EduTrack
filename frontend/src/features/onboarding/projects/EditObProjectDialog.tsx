import * as React from 'react'

import type { ApiError } from '@/api/http'
import type { ObProjectDetail } from '@/api/generated/model/obProjectDetail'
import type { UserRef } from '@/api/generated/model/userRef'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
} from '@/components/ui/modal'
import { SearchableDropdown } from '@/components/ui/searchable-dropdown'
import { toast } from '@/components/ui/use-toast'
import { FormField } from '@/features/masters/resources/FormField'

import {
  projectEditRequestOf,
  projectEditValuesOf,
  SETTABLE_STATUSES,
  STATUS_LABEL,
  statusNeedsReason,
  validateProjectEdit,
  type ProjectEditErrors,
  type ProjectEditValues,
  type SettableStatus,
} from './editProjectForm'
import { useUpdateObProject } from './projectQueries'
import { useObManagerOptions } from './useObManagerOptions'

/**
 * Edit a project — the fields entered when it was created, corrected later.
 *
 * <h2>The ETag is the whole point of the dialog being fed the page's read</h2>
 *
 * <p>`PATCH /onboarding/projects/{id}` requires `If-Match`, and the tag comes
 * off the same `GET` the page rendered from — `useObProject` keeps it beside
 * the data for exactly this. Fetching a fresh one at submit time would defeat
 * the guard: it would tag whatever the server holds <em>now</em>, not what the
 * reader saw, and a colleague's edit in between would be overwritten unseen.
 *
 * <p>So a `412` here is not an error to retry. It means the project changed
 * since it was read — including a step somebody completed, since the tag
 * covers the roll-up — and the honest answer is to reload and look again.
 *
 * <h2>What is not here</h2>
 *
 * <p>The client, the product and the module services. The first two are the
 * project's identity and the third are running journeys; `editProjectForm.ts`
 * carries the argument, and the description under the title says it to the
 * reader so the missing fields read as a decision rather than an omission.
 */
export interface EditObProjectDialogProps {
  project: ObProjectDetail
  /** From the page's own read. Null only if the server sent none. */
  etag: string | null
  open: boolean
  onClose: () => void
  people: readonly UserRef[]
}

export function EditObProjectDialog({
  project,
  etag,
  open,
  onClose,
  people,
}: EditObProjectDialogProps) {
  const update = useUpdateObProject()
  const [values, setValues] = React.useState<ProjectEditValues>(() =>
    projectEditValuesOf(project),
  )
  const [errors, setErrors] = React.useState<ProjectEditErrors>({})

  /*
    Re-seeded every time the dialog opens, not only on mount: the page keeps
    this component mounted, and a project edited once and opened again must
    show what is saved now rather than what was typed last time.
  */
  React.useEffect(() => {
    if (!open) return
    setValues(projectEditValuesOf(project))
    setErrors({})
  }, [open, project])

  const set = (patch: Partial<ProjectEditValues>) =>
    setValues((current) => ({ ...current, ...patch }))

  const onSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    const found = validateProjectEdit(values)
    setErrors(found)
    if (Object.keys(found).length > 0) return

    update.mutate(
      { obProjectId: project.id, etag, data: projectEditRequestOf(values) },
      {
        onSuccess: (saved) => {
          toast({ title: `${saved.name} saved` })
          onClose()
        },
        onError: (error) => {
          if (error.status === 412) {
            toast({
              title: 'This project changed since you opened it',
              description: 'Reload the page and make your edit again.',
            })
            return
          }
          const mapped = fieldErrorsFrom(error)
          if (Object.keys(mapped).length > 0) setErrors(mapped)
          else toast({ title: 'The project was not saved', description: error.problem.detail })
        },
      },
    )
  }

  /*
    Which list to look the person up in matters, and it is not always `people`.
    The implementor manager is chosen from a narrower set, and resolving their
    id against the full directory would draw a name the dropdown below cannot
    offer — a value that looks chosen and disappears the moment it is opened.
  */
  const person = (id: number | null, from: readonly UserRef[] = people) =>
    from.find((u) => u.id === id) ?? null

  const { managers } = useObManagerOptions()

  return (
    <Modal open={open} onOpenChange={(next) => (next ? undefined : onClose())}>
      <ModalContent>
        <form onSubmit={onSubmit} data-testid="ob-edit-project">
          <ModalHeader>
            <ModalTitle>Edit project</ModalTitle>
            <ModalDescription>
              The client, the product and the module services stay as they are — the first two
              are what makes this <em>this</em> project, and the services are journeys already
              running.
            </ModalDescription>
          </ModalHeader>

          <div className="flex flex-col gap-4 px-6 py-4">
            <FormField id="edit-project-name" label="Project name" required error={errors.name}>
              {(aria) => (
                <Input
                  {...aria}
                  value={values.name}
                  maxLength={200}
                  onChange={(e) => set({ name: e.target.value })}
                />
              )}
            </FormField>

            <FormField
              id="edit-project-start"
              label="Start date"
              required
              hint="The tentative completion date is walked forward from here through the working calendar."
              error={errors.startDate}
            >
              {(aria) => (
                <Input
                  {...aria}
                  type="date"
                  value={values.startDate}
                  onChange={(e) => set({ startDate: e.target.value })}
                />
              )}
            </FormField>

            <PersonField
              id="edit-project-sales"
              label="Sales person"
              people={people}
              value={person(values.salesPersonId)}
              onChange={(id) => set({ salesPersonId: id })}
            />

            <PersonField
              id="edit-project-implementor"
              label="Implementor"
              hint="Every task with no responsible on its module service falls to this person. Clearing it leaves those tasks with nobody."
              people={people}
              value={person(values.implementorUserId)}
              onChange={(id) => set({ implementorUserId: id })}
            />

            {/*
              Managers only, unlike the two fields above it. This person
              verifies the project's check lists, and the review routes accept
              `OB_MANAGER` and `OB_ADMIN` alone — offering the directory here
              is how a project ends up escalating to somebody the platform then
              refuses. See `useObManagerOptions`.
            */}
            <PersonField
              id="edit-project-implementor-manager"
              label="Implementor manager"
              hint="Who this project escalates to, and who verifies its check lists. Onboarding managers and admins only. Nothing falls to them, so clearing it costs no task an owner — it costs the project its escalation path."
              people={managers}
              value={person(values.implementorManagerUserId, managers)}
              onChange={(id) => set({ implementorManagerUserId: id })}
            />

            {values.status != null ? (
              <>
                <FormField
                  id="edit-project-status"
                  label="Status"
                  hint="Completed is not on offer — a project earns it when every module service finishes."
                >
                  {(aria) => (
                    <select
                      {...aria}
                      value={values.status ?? 'RUNNING'}
                      onChange={(e) => set({ status: e.target.value as SettableStatus })}
                      className="h-10 w-full rounded-control border border-border bg-surface px-3 text-sm text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                    >
                      {SETTABLE_STATUSES.map((s) => (
                        <option key={s} value={s}>
                          {STATUS_LABEL[s]}
                        </option>
                      ))}
                    </select>
                  )}
                </FormField>

                {statusNeedsReason(values.status) ? (
                  <FormField
                    id="edit-project-reason"
                    label="Reason"
                    required
                    error={errors.statusReason}
                  >
                    {(aria) => (
                      <Input
                        {...aria}
                        value={values.statusReason}
                        maxLength={500}
                        placeholder={
                          values.status === 'ON_HOLD'
                            ? 'Why the project is on hold…'
                            : 'Why the project was dropped…'
                        }
                        onChange={(e) => set({ statusReason: e.target.value })}
                      />
                    )}
                  </FormField>
                ) : null}
              </>
            ) : (
              <p className="text-caption text-content-muted">
                This project is complete, so its status is not editable.
              </p>
            )}
          </div>

          <ModalFooter>
            <Button type="button" variant="secondary" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={update.isPending}>
              {update.isPending ? 'Saving…' : 'Save project'}
            </Button>
          </ModalFooter>
        </form>
      </ModalContent>
    </Modal>
  )
}

/**
 * A person picker that can also be emptied.
 *
 * <p>The dropdown picks; the small Clear beside it unassigns. Both people are
 * nullable on the update — the contract's own line is that an absent
 * implementor means <em>cleared</em> — and a picker with no way back to "nobody"
 * would make unassigning impossible from this screen.
 */
function PersonField({
  id,
  label,
  hint,
  people,
  value,
  onChange,
}: {
  id: string
  label: string
  hint?: string
  people: readonly UserRef[]
  value: UserRef | null
  onChange: (id: number | null) => void
}) {
  return (
    <FormField id={id} label={label} hint={hint}>
      {(aria) => (
        <div className="flex items-center gap-2">
          <div className="min-w-0 flex-1">
            <SearchableDropdown
              {...aria}
              options={[...people]}
              value={value}
              onChange={(u) => onChange(u.id)}
              getKey={(u) => String(u.id)}
              getLabel={(u) => u.displayName}
              placeholder="Search people…"
            />
          </div>
          {value ? (
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={() => onChange(null)}
              aria-label={`Clear ${label.toLowerCase()}`}
            >
              Clear
            </Button>
          ) : null}
        </div>
      )}
    </FormField>
  )
}

/** The server's field-keyed 400, mapped onto the fields this form has. */
function fieldErrorsFrom(error: ApiError): ProjectEditErrors {
  const mapped: ProjectEditErrors = {}
  const raw = error.fieldErrors
  for (const key of [
    'name',
    'startDate',
    'salesPersonId',
    'implementorUserId',
    'implementorManagerUserId',
    'status',
    'statusReason',
  ] as const) {
    const first = raw[key]?.[0]
    if (first) mapped[key] = first
  }
  return mapped
}
