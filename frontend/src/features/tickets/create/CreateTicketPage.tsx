import * as React from 'react'
import { useNavigate } from 'react-router-dom'
import { useForm, Controller, type Resolver } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { format } from 'date-fns'

import { useGetMe } from '@/api/generated/auth/auth'
import { useListProjects } from '@/api/generated/projects/projects'
import { useListClients, useListClientContacts } from '@/api/generated/clients/clients'
import { useListUsers } from '@/api/generated/users/users'
import { useListTaskTypes, useListPriorities } from '@/api/generated/masters/masters'
import { newIdempotencyKey, ApiError } from '@/api/http'
import type { Level } from '@/api/generated/model/level'
import type { Project } from '@/api/generated/model/project'
import type { Client } from '@/api/generated/model/client'
import type { Contact } from '@/api/generated/model/contact'
import type { User } from '@/api/generated/model/user'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Chip } from '@/components/ui/chip'
import { SearchableDropdown } from '@/components/ui/searchable-dropdown'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { RichTextEditor } from '@/components/ui/rich-text-editor'
import { toast } from '@/components/ui/use-toast'
import { createTicketBodyDescriptionMax } from '@/api/generated/zod/tickets/tickets.zod'
import { useCurrentProjectStore } from '@/app/currentProjectStore'

import { FieldGroup, FormField, ReadOnlyField } from './FormField'
import { LevelPicker } from './LevelPicker'
import { WatcherPicker } from './WatcherPicker'
import { useCreateTicket } from './createTicketMutation'
import {
  clientRequiringTaskTypeIds,
  emptyTicketForm,
  retainedForNextTicket,
  ticketFormSchema,
  toCreateRequest,
  type TicketFormValues,
  type TicketSaveAction,
} from './ticketForm'

/**
 * S-19 Create Ticket — C-010, plus the four §7.5 actions from C-013.
 *
 * What is deliberately *not* here, and why, is in this folder's README: the
 * inline planned-close-date preview is C-012, attachments are C-023/C-024 and
 * the opening comment is C-029.
 */
export function CreateTicketPage() {
  const navigate = useNavigate()
  const currentProject = useCurrentProjectStore((s) => s.project)
  const setCurrentProject = useCurrentProjectStore((s) => s.setProject)

  const { data: me } = useGetMe()
  const { data: projectsData, isPending: projectsPending } = useListProjects({ isActive: true, limit: 200 })
  const { data: taskTypesData, isPending: taskTypesPending } = useListTaskTypes()
  const { data: prioritiesData } = useListPriorities()

  const projects = React.useMemo(() => projectsData?.data ?? [], [projectsData])
  const taskTypes = React.useMemo(
    () => (taskTypesData?.data ?? []).filter((t) => t.isActive !== false),
    [taskTypesData],
  )
  const levels = React.useMemo(
    () => (prioritiesData?.data ?? []).map((p) => p.level).filter((l): l is Level => l != null),
    [prioritiesData],
  )

  const clientRequiredIds = React.useMemo(() => clientRequiringTaskTypeIds(taskTypes), [taskTypes])

  /**
   * Which button is being validated for. Held in a ref because the resolver has
   * to read it at validation time, and a state update would not have landed by
   * then — `handleSubmit` validates in the same tick as the click.
   *
   * It falls back to `assign` after every attempt so that blur validation, and
   * the revalidate-on-change that follows a failed submit, keep measuring the
   * form against the primary action rather than the last button pressed. A
   * draft attempt must not leave the form permanently lenient.
   */
  const submitAction = React.useRef<TicketSaveAction>('assign')
  const resolver = React.useMemo<Resolver<TicketFormValues>>(
    () => (values, context, options) =>
      zodResolver(ticketFormSchema(clientRequiredIds, submitAction.current))(values, context, options),
    [clientRequiredIds],
  )

  const {
    control,
    register,
    handleSubmit,
    watch,
    setValue,
    setError,
    setFocus,
    reset,
    formState: { errors },
  } = useForm<TicketFormValues>({
    resolver,
    defaultValues: { ...emptyTicketForm, projectId: currentProject?.id ?? null },
    mode: 'onBlur',
  })

  const projectId = watch('projectId')
  const taskTypeId = watch('taskTypeId')
  const clientId = watch('clientId')

  // Only the project's members can be assigned or watch — §7.5. Both lists wait
  // for a project rather than showing everyone and filtering after the fact.
  const { data: membersData } = useListUsers(
    { projectId: projectId ?? undefined, isActive: true, limit: 200 },
    { query: { enabled: projectId != null } },
  )
  const members = React.useMemo(() => membersData?.data ?? [], [membersData])

  // `projectId` is sent per §4B.2 ("filtered to clients mapped to the project").
  // D-004's mock ignores the parameter today and returns every client, so in
  // `npm run dev` the list looks unfiltered; against the real API it is not.
  const { data: clientsData } = useListClients(
    { projectId: projectId ?? undefined, isActive: true, limit: 200 },
    { query: { enabled: projectId != null } },
  )
  const clients = React.useMemo(() => clientsData?.data ?? [], [clientsData])

  const { data: contactsData } = useListClientContacts(clientId ?? 0, {
    query: { enabled: clientId != null },
  })
  const contacts = React.useMemo(
    () => (contactsData?.data ?? []).filter((c): c is Contact & { id: number } => c.id != null),
    [contactsData],
  )

  const canBackdateOrOverride = me?.data.role === 'ADMIN' || me?.data.role === 'PM'

  // One key per logical ticket, minted before the first attempt and rotated only
  // once a ticket actually exists. A key regenerated per click would make the
  // resubmit-after-timeout case — the one it is for — look like a new ticket.
  const idempotencyKey = React.useRef(newIdempotencyKey())
  const createTicket = useCreateTicket()

  // Which action is in flight, rather than a single `isSubmitting`: with four
  // buttons the user needs to see which one they pressed, and all four have to
  // lock so a second save cannot start on the same idempotency key.
  const [saving, setSaving] = React.useState<TicketSaveAction | null>(null)
  const isSaving = saving != null

  /**
   * Bumped by Save & Create Another so focus can move on the *next* commit.
   *
   * `reset` drops the field-ref registry and lets the inputs re-register as
   * they render, so a `setFocus` in the same tick looks up a field that is
   * momentarily not there and silently does nothing. An effect runs after that
   * commit, by which time the ref is back.
   */
  const [ticketsInBatch, setTicketsInBatch] = React.useState(0)
  React.useEffect(() => {
    if (ticketsInBatch > 0) setFocus('title')
  }, [ticketsInBatch, setFocus])

  /**
   * The last ticket a batch produced, confirmed in the action bar rather than
   * with a toast.
   *
   * The shared toast viewport is `fixed bottom-0 right-0 z-[100]`, which is
   * exactly where this screen's primary action sits — so a success toast
   * physically covers Save & Assign. Every other path navigates away before
   * that matters; Save & Create Another is the one that leaves the user here
   * and expects them to press a button again, so on that path a toast blocks
   * the very action it is congratulating them for. Moving the viewport would
   * change every screen in all four streams, so the confirmation moves instead
   * — into the bar's existing live region, which is where the user is looking
   * anyway.
   */
  const [lastCreated, setLastCreated] = React.useState<string | null>(null)

  // Level is pre-filled from the task type's default and stays pre-filled until
  // the user picks one themselves; after that, changing the task type must not
  // silently overwrite their choice.
  const levelTouched = React.useRef(false)
  React.useEffect(() => {
    if (levelTouched.current || taskTypeId == null) return
    const fallback = taskTypes.find((t) => t.id === taskTypeId)?.defaultLevel
    if (fallback) setValue('level', fallback, { shouldValidate: false })
  }, [taskTypeId, taskTypes, setValue])

  // A contact belongs to exactly one client, so it cannot survive the client changing.
  const previousClientId = React.useRef(clientId)
  React.useEffect(() => {
    if (previousClientId.current !== clientId) {
      previousClientId.current = clientId
      setValue('clientContactId', null)
    }
  }, [clientId, setValue])

  async function onSubmit(values: TicketFormValues, action: TicketSaveAction) {
    setSaving(action)
    try {
      const response = await createTicket.mutateAsync({
        data: toCreateRequest(values, action),
        idempotencyKey: idempotencyKey.current,
      })
      const ticketId = response.data.ticketId

      // Rotated the moment a ticket exists, and before anything can submit
      // again. Save & Create Another is the one path that reaches the form a
      // second time without a remount, so it is the one path where a stale key
      // would make the second ticket replay the first response — the server
      // returns the original for 24 hours — and the user would watch the same
      // ID appear twice with the second ticket never created.
      idempotencyKey.current = newIdempotencyKey()

      if (action === 'another') {
        // `levelTouched` is deliberately left as it stands: if the user chose a
        // level it stays chosen, and if it was pre-filled it re-derives when
        // they pick a different task type for the next ticket.
        reset({ ...emptyTicketForm, ...retainedForNextTicket(values) })
        // Confirmed in the action bar, not with a toast — see `lastCreated`.
        setLastCreated(ticketId)
        // Without this the user is left at the bottom of a form that looks
        // unchanged, with no cue that it is now blank.
        setTicketsInBatch((count) => count + 1)
        return
      }

      toast({
        variant: 'success',
        title: action === 'draft' ? `${ticketId} saved as a draft` : `${ticketId} created`,
        description: values.title.trim(),
      })
      navigate(`/tickets/${ticketId}`)
    } catch (error) {
      if (error instanceof ApiError && error.status === 400) {
        // Server-side field messages land on the fields they belong to rather
        // than in a banner the user has to map back onto the form themselves.
        for (const [field, messages] of Object.entries(error.fieldErrors)) {
          if (field in emptyTicketForm) {
            setError(field as keyof TicketFormValues, { type: 'server', message: messages.join('. ') })
          }
        }
      }
      toast({
        variant: 'danger',
        title: action === 'draft' ? 'The draft was not saved' : 'The ticket was not created',
        description: error instanceof ApiError ? error.problem.detail ?? error.message : 'Try again in a moment.',
      })
    } finally {
      setSaving(null)
    }
  }

  /**
   * Validate against `action`'s rules, then save under it.
   *
   * The ref is set before `handleSubmit` runs so the resolver sees it, and the
   * action is also passed down the closure so `onSubmit` never has to read a
   * ref that has since been reset.
   */
  function save(action: TicketSaveAction) {
    submitAction.current = action
    return handleSubmit((values) => onSubmit(values, action))().finally(() => {
      submitAction.current = 'assign'
    })
  }

  if (projectsPending || taskTypesPending) {
    return (
      <div className="mx-auto flex max-w-4xl flex-col gap-4 p-8" aria-busy>
        <Skeleton className="h-8 w-64" />
        <Skeleton className="h-64 w-full" />
        <Skeleton className="h-64 w-full" />
      </div>
    )
  }

  const selectedProject = projects.find((p) => p.id === projectId) ?? null
  const selectedClient = clients.find((c) => c.id === clientId) ?? null
  const selectedContact = contacts.find((c) => c.id === watch('clientContactId')) ?? null
  const selectedAssignee = members.find((u) => u.id === watch('assigneeId')) ?? null

  return (
    <form
      // Save & Assign is the primary, so it is also what Enter in a text field
      // does. The other two are `type="button"` and route through `save`.
      onSubmit={(event) => {
        event.preventDefault()
        void save('assign')
      }}
      noValidate
      className="mx-auto max-w-4xl p-8 pb-28"
    >
      <header className="mb-6">
        <h1 className="text-h1 text-content">New ticket</h1>
        <p className="mt-1 text-sm text-content-muted">
          Fields marked <span className="text-danger-text">*</span> are required.
        </p>
      </header>

      <div className="flex flex-col gap-6">
        {/* ── Identity ─────────────────────────────────────────────────── */}
        <FieldGroup
          title="Identity"
          description="The ticket ID is issued by the server when you save, from the project's own sequence."
        >
          <ReadOnlyField
            label="Ticket ID"
            value={
              selectedProject ? (
                <span className="font-mono">
                  {selectedProject.projectCode}-{format(new Date(), 'yy')}-·····
                </span>
              ) : (
                'Select a project'
              )
            }
            hint="Generated on save — never reused, never reset at year rollover."
          />
        </FieldGroup>

        {/* ── Core ─────────────────────────────────────────────────────── */}
        <FieldGroup title="Core">
          <FormField id="projectId" label="Project" required error={errors.projectId?.message}>
            {(aria) => (
              <Controller
                control={control}
                name="projectId"
                render={({ field }) => (
                  <SearchableDropdown<Project>
                    {...aria}
                    options={projects}
                    value={selectedProject}
                    onChange={(project) => {
                      field.onChange(project.id)
                      // Everything below the project depends on it.
                      setValue('clientId', null)
                      setValue('clientContactId', null)
                      setValue('assigneeId', null)
                      setValue('watcherIds', [])
                      setCurrentProject(project)
                    }}
                    getKey={(project) => String(project.id)}
                    getLabel={(project) => `${project.projectCode} — ${project.name}`}
                    getSearchable={(project) => [project.projectCode, project.name]}
                    placeholder="Search projects…"
                  />
                )}
              />
            )}
          </FormField>

          <FormField
            id="taskTypeId"
            label="Task type"
            required
            error={errors.taskTypeId?.message}
            hint="Sets the default priority and the SLA the close date is computed from."
          >
            {(aria) => (
              <Controller
                control={control}
                name="taskTypeId"
                render={({ field }) => (
                  <Select
                    value={field.value != null ? String(field.value) : undefined}
                    onValueChange={(value) => field.onChange(Number(value))}
                  >
                    <SelectTrigger {...aria}>
                      <SelectValue placeholder="Select a task type" />
                    </SelectTrigger>
                    <SelectContent>
                      {taskTypes.map((type) => (
                        <SelectItem key={type.id} value={String(type.id)}>
                          {type.name}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                )}
              />
            )}
          </FormField>

          <FormField
            id="clientId"
            label="Client"
            error={errors.clientId?.message}
            hint={
              taskTypeId != null && clientRequiredIds.has(taskTypeId)
                ? 'Required for this task type.'
                : 'Clients mapped to the selected project.'
            }
          >
            {(aria) => (
              <Controller
                control={control}
                name="clientId"
                render={({ field }) => (
                  <SearchableDropdown<Client>
                    {...aria}
                    options={clients}
                    value={selectedClient}
                    onChange={(client) => field.onChange(client.id)}
                    getKey={(client) => String(client.id)}
                    getLabel={(client) => `${client.clientCode} — ${client.name}`}
                    getSearchable={(client) => [client.clientCode ?? '', client.name ?? '', client.domain ?? '']}
                    placeholder={projectId == null ? 'Select a project first' : 'Search name, code or domain…'}
                    disabled={projectId == null}
                  />
                )}
              />
            )}
          </FormField>

          <FormField
            id="clientContactId"
            label="Client contact"
            error={errors.clientContactId?.message}
            hint="The person who reported it. Adding one without leaving the form is C-021."
          >
            {(aria) => (
              <Controller
                control={control}
                name="clientContactId"
                render={({ field }) => (
                  <SearchableDropdown<Contact & { id: number }>
                    {...aria}
                    options={contacts}
                    value={(selectedContact as (Contact & { id: number }) | null) ?? null}
                    onChange={(contact) => field.onChange(contact.id)}
                    getKey={(contact) => String(contact.id)}
                    getLabel={(contact) => contact.name ?? contact.email ?? `Contact ${contact.id}`}
                    getSearchable={(contact) => [contact.email ?? '']}
                    placeholder={clientId == null ? 'Select a client first' : 'Search contacts…'}
                    emptyText="This client has no contacts yet"
                    disabled={clientId == null}
                  />
                )}
              />
            )}
          </FormField>

          <FormField
            id="title"
            label="Title / summary"
            required
            error={errors.title?.message}
            className="sm:col-span-2"
          >
            {(aria) => <Input {...aria} {...register('title')} placeholder="One line a manager can scan" />}
          </FormField>

          <FormField
            id="description"
            label="Task description"
            required
            error={errors.description?.message}
            hint="Bold, headings, lists, code blocks and links. Paste from a client's email — the formatting is kept and the styling is stripped."
            className="sm:col-span-2"
          >
            {(aria) => (
              // `Controller`, not `register`: a contentEditable emits no
              // `input` event carrying a `value`, so there is nothing for
              // react-hook-form's uncontrolled path to read.
              <Controller
                name="description"
                control={control}
                render={({ field }) => (
                  <RichTextEditor
                    {...aria}
                    value={field.value}
                    onChange={field.onChange}
                    onBlur={field.onBlur}
                    showCount
                    maxLength={createTicketBodyDescriptionMax}
                    placeholder="What happened, what was expected, and how to reproduce it."
                  />
                )}
              />
            )}
          </FormField>

          <FormField
            id="level"
            label="Level (priority)"
            required
            error={errors.level?.message}
            hint="Pre-filled from the task type's default until you choose one."
            className="sm:col-span-2"
          >
            {(aria) => (
              <Controller
                control={control}
                name="level"
                render={({ field }) => (
                  <LevelPicker
                    {...aria}
                    levels={levels}
                    value={field.value}
                    onChange={(level) => {
                      levelTouched.current = true
                      field.onChange(level)
                    }}
                  />
                )}
              />
            )}
          </FormField>
        </FieldGroup>

        {/* ── People ───────────────────────────────────────────────────── */}
        <FieldGroup title="People">
          <ReadOnlyField
            label="Date reported"
            value={format(new Date(), 'dd MMM yyyy, HH:mm')}
            hint="Stamped by the server on save. Backdating for Admin and PM needs a contract field — see the README."
          />
          <ReadOnlyField
            label="Reported by"
            value={me?.data.displayName ?? '—'}
            hint="You. A Support Desk agent reporting on a client contact's behalf needs a contract field — see the README."
          />

          <FormField
            id="assigneeId"
            label="Assigned to"
            error={errors.assigneeId?.message}
            hint="Open-ticket load shown per person, so you can see who is free."
          >
            {(aria) => (
              <Controller
                control={control}
                name="assigneeId"
                render={({ field }) => (
                  <SearchableDropdown<User>
                    {...aria}
                    options={members}
                    value={selectedAssignee}
                    onChange={(user) => field.onChange(user.id)}
                    getKey={(user) => String(user.id)}
                    getLabel={(user) =>
                      user.openTicketCount != null
                        ? `${user.displayName} · ${user.openTicketCount} open`
                        : user.displayName
                    }
                    getSearchable={(user) => [user.email ?? '', user.role ?? '']}
                    placeholder={projectId == null ? 'Select a project first' : 'Search project members…'}
                    emptyText="No active members on this project"
                    disabled={projectId == null}
                  />
                )}
              />
            )}
          </FormField>

          <ReadOnlyField label="Assigned by" value={me?.data.displayName ?? '—'} hint="Always the current user." />

          <FormField
            id="watcherIds"
            label="Watchers"
            error={errors.watcherIds?.message}
            hint="They get the notifications too."
            className="sm:col-span-2"
          >
            {(aria) => (
              <Controller
                control={control}
                name="watcherIds"
                render={({ field }) => (
                  <WatcherPicker
                    {...aria}
                    candidates={members}
                    value={field.value}
                    onChange={field.onChange}
                    disabled={projectId == null}
                  />
                )}
              />
            )}
          </FormField>
        </FieldGroup>

        {/* ── Effort ───────────────────────────────────────────────────── */}
        <FieldGroup title="Effort">
          <FormField
            id="estimatedHrs"
            label="Estimated effort (hrs)"
            required
            error={errors.estimatedHrs?.message}
          >
            {(aria) => (
              <Input {...aria} {...register('estimatedHrs')} inputMode="decimal" placeholder="4.5" />
            )}
          </FormField>

          {canBackdateOrOverride ? (
            <FormField
              id="plannedCloseDate"
              label="Planned close date"
              error={errors.plannedCloseDate?.message}
              hint="Leave blank to compute it from the SLA policy against the working calendar. The inline preview is C-012."
            >
              {(aria) => <Input {...aria} {...register('plannedCloseDate')} type="datetime-local" />}
            </FormField>
          ) : (
            <ReadOnlyField
              label="Planned close date"
              value="Computed from the SLA policy on save"
              hint="Weekends, holidays and resource leave are taken out of the clock. Overriding it requires PM or Admin."
            />
          )}

          <ReadOnlyField label="Actual close date" value="—" hint="Set at closure, never here." />
        </FieldGroup>

        {/* ── Extra ────────────────────────────────────────────────────── */}
        <FieldGroup title="Extra">
          <FormField
            id="isClientRaised"
            label="Client-raised"
            error={errors.isClientRaised?.message}
            hint="Drives the client-wise reports, the CSAT survey and the client-visible default on comments."
            className="sm:col-span-2"
          >
            {(aria) => (
              <label className="flex h-10 items-center gap-2 text-sm text-content">
                <input
                  {...aria}
                  {...register('isClientRaised')}
                  type="checkbox"
                  className="h-4 w-4 rounded border-border text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-1"
                />
                This ticket was raised by the client
              </label>
            )}
          </FormField>

          <div className="sm:col-span-2 flex flex-wrap items-center gap-2 rounded-control bg-subtle px-3 py-2.5">
            <span className="text-caption text-content-muted">Arriving in their own tasks:</span>
            <Chip>Attachments · C-023</Chip>
            <Chip>Clipboard paste · C-024</Chip>
            <Chip>First comment · C-029</Chip>
          </div>
        </FieldGroup>
      </div>

      {/* ── Actions — §7.5 ───────────────────────────────────────────────── */}
      <div className="fixed inset-x-0 bottom-0 border-t border-border bg-surface px-8 py-4 shadow-modal">
        <div className="mx-auto flex max-w-4xl items-center gap-4">
          {/* What the save will actually do with the assignee. §7.5 does not
              mark Assigned To required and C-015 ships an Unassigned saved
              view, so saving without one is a supported outcome — but it is
              worth stating, because "Save & Assign" implies otherwise.

              `min-w-0 flex-1` is load-bearing: without it the hint holds its
              full width and pushes the primary action onto a second row, off
              the bar. The hint is what wraps when the bar runs out of room. */}
          <p className="min-w-0 flex-1 text-caption text-content-muted" role="status">
            {lastCreated && (
              <span className="mr-2 font-medium text-success-text">
                {lastCreated} created — {ticketsInBatch} in this batch.
              </span>
            )}
            {selectedAssignee ? (
              <>
                Will be assigned to <span className="font-medium text-content">{selectedAssignee.displayName}</span>.
              </>
            ) : (
              'Saves unassigned — it will show in the Unassigned view.'
            )}
          </p>
          <div className="flex shrink-0 items-center gap-2">
            <Button type="button" variant="ghost" onClick={() => navigate(-1)} disabled={isSaving}>
              Cancel
            </Button>
            <Button type="button" variant="secondary" onClick={() => void save('draft')} disabled={isSaving}>
              {saving === 'draft' ? 'Saving draft…' : 'Save as Draft'}
            </Button>
            <Button type="button" variant="secondary" onClick={() => void save('another')} disabled={isSaving}>
              {saving === 'another' ? 'Saving…' : 'Save & Create Another'}
            </Button>
            <Button type="submit" disabled={isSaving}>
              {saving === 'assign' ? 'Saving…' : 'Save & Assign'}
            </Button>
          </div>
        </div>
      </div>
    </form>
  )
}
