import * as React from 'react'
import { useNavigate } from 'react-router-dom'
import { useForm, Controller, type Resolver } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { format } from 'date-fns'

import { useGetMe } from '@/api/generated/auth/auth'
import { useGetProjectSettings, useListProjects } from '@/api/generated/projects/projects'
import { useListClients, useListClientContacts } from '@/api/generated/clients/clients'
import { useListUsers } from '@/api/generated/users/users'
import { useListTaskTypes, useListPriorities, useListModules } from '@/api/generated/masters/masters'
import { newIdempotencyKey, ApiError } from '@/api/http'
import type { Project } from '@/api/generated/model/project'
import type { Client } from '@/api/generated/model/client'
import type { Contact } from '@/api/generated/model/contact'
import type { User } from '@/api/generated/model/user'
import type { TicketFieldCode } from '@/api/generated/model/ticketFieldCode'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Chip } from '@/components/ui/chip'
import { SearchableDropdown } from '@/components/ui/searchable-dropdown'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { RichTextEditor } from '@/components/ui/rich-text-editor'
import { AttachmentPicker, type AttachmentPickerHandle } from '@/components/ui/attachment-picker'
import { toast } from '@/components/ui/use-toast'
import {
  createTicketBodyDescriptionMax,
  createTicketBodyStepsToGenerateMax,
} from '@/api/generated/zod/tickets/tickets.zod'
import { useCurrentProjectStore } from '@/app/currentProjectStore'
// B-029 · both new-ticket gates, stated once. Stream B's module, read by
// Stream C's screen — the same crossing B-028 made for the first of the two.
import { newTicketBlockReason } from '@/features/clients/ticketEligibility'
// C-021 · the row editor behind S-33's Contacts tab, reused rather than
// rebuilt. Same crossing as `ticketEligibility` above: Stream B's module,
// read by Stream C's screen. It already knows how to add a contact under a
// client and refresh whatever reads `useListClientContacts`; duplicating
// that here would be the same form twice with one copy always slightly
// behind the other.
import { ContactEditorDialog } from '@/features/clients/ContactEditorDialog'

import { useAttachmentLimits } from '../attachments/attachmentLimits'
import { useTicketAttachments } from '../attachments/useTicketAttachments'
import { SlaPreview } from '../sla/SlaPreview'
import { usePlannedCloseDate } from '../sla/usePlannedCloseDate'
import { FieldGroup, FormField, ReadOnlyField } from './FormField'
import { LevelPicker } from './LevelPicker'
import { selectableLevels } from '../levels'
import { WatcherPicker } from './WatcherPicker'
import { useCreateTicket } from './createTicketMutation'
import {
  allowedTaskTypes,
  bugTaskTypeIds,
  clientRequiringTaskTypeIds,
  emptyTicketForm,
  projectRulesFrom,
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
  /*
    C-068 · §7.5's module master. **Inactive rows are filtered out here and
    nowhere else**: `GET /masters/modules` returns them on purpose, because a
    grid still has to render the name of a module some old ticket was raised
    against (D-060), but a *picker* offering a retired module would let somebody
    raise today's ticket against it — and `ModuleGuard` refuses that with a 400,
    so offering it is offering a refusal.
  */
  const { data: modulesData, isError: modulesFailed } = useListModules()

  const projects = React.useMemo(() => projectsData?.data ?? [], [projectsData])
  const taskTypes = React.useMemo(
    () => (taskTypesData?.data ?? []).filter((t) => t.isActive !== false),
    [taskTypesData],
  )
  /*
    C-072 · retired levels dropped, exactly as `taskTypes` and `modules` above
    drop theirs and for the same reason — this is the picker that decides what
    a *new* ticket may be raised at, so offering a level S-12 has retired is
    offering a choice the master has withdrawn. See `../levels.ts` for why the
    filter lives there rather than in the query parameter.
  */
  const levels = React.useMemo(() => selectableLevels(prioritiesData?.data), [prioritiesData])

  const modules = React.useMemo(
    () => (modulesData?.data ?? []).filter((m) => m.isActive !== false),
    [modulesData],
  )

  const clientRequiredIds = React.useMemo(() => clientRequiringTaskTypeIds(taskTypes), [taskTypes])
  const bugTypeIds = React.useMemo(() => bugTaskTypeIds(taskTypes), [taskTypes])
  // D-066 · the same list `LevelPicker` is fed, so the form cannot refuse a
  // level the user was just offered — and cannot accept one the master has
  // since retired.
  const levelCodes = React.useMemo(() => new Set<string>(levels), [levels])
  const taskTypeRules = React.useMemo(
    () => ({ clientRequired: clientRequiredIds, bugTypes: bugTypeIds, levels: levelCodes }),
    [clientRequiredIds, bugTypeIds, levelCodes],
  )
  /*
    C-071 · the selected project's settings, read at validation time rather than
    closed over — the same arrangement `submitAction` uses two blocks down and
    for the same reason. The resolver is built before a project has been picked
    and long before its settings come back, so one that captured the rules would
    validate against `noProjectRules` for the life of the form.
  */
  const projectRulesRef = React.useRef(projectRulesFrom(undefined))

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
      zodResolver(ticketFormSchema(taskTypeRules, submitAction.current, projectRulesRef.current))(
        values,
        context,
        options,
      ),
    [taskTypeRules],
  )

  const {
    control,
    register,
    handleSubmit,
    watch,
    setValue,
    getValues,
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
  const level = watch('level')
  const assigneeId = watch('assigneeId')
  const plannedCloseDate = watch('plannedCloseDate')
  /*
    C-068 · whether the Module field draws its asterisk. `bugTypeIds` is empty
    while the task-type master is still loading, so this is false first and true
    once the answer arrives — the same direction `canChangeLevel` chose on S-20
    and for the same reason: a required marker that appears late is a marker
    somebody has already read past.
  */
  const moduleRequired = taskTypeId != null && bugTypeIds.has(taskTypeId)
  const modulesEmpty = modulesFailed || (modulesData != null && modules.length === 0)

  /*
    C-071 · B-019's Settings tab, obeyed. Every role may read it — the contract
    says so in as many words, because "all six can raise a ticket, and the create
    form cannot mark a field mandatory or filter its task-type picker without
    this".

    `projectId ?? 0` rather than a `query.enabled` of our own: the generated hook
    already carries `enabled: !!(projectId)`, so a falsy id is a query that never
    runs. Its 404 is left to fall through to the empty rules below — a project
    the caller cannot see is one they cannot raise a ticket on either, and the
    create call is where they find that out.
  */
  const { data: settingsData } = useGetProjectSettings(projectId ?? 0)
  const projectRules = React.useMemo(() => projectRulesFrom(settingsData?.data), [settingsData])
  projectRulesRef.current = projectRules

  const mandates = React.useCallback(
    (field: TicketFieldCode) => projectRules.mandatoryFields.has(field),
    [projectRules],
  )

  /*
    What the task-type picker offers, as against what the rules above are
    computed from. `clientRequiredIds` and `bugTypeIds` stay derived from the
    whole active master: they answer "does *this* task type need a client", which
    does not change because a project stopped accepting the type.
  */
  const offeredTaskTypes = React.useMemo(
    () => allowedTaskTypes(taskTypes, projectRules),
    [taskTypes, projectRules],
  )
  const taskTypesRestricted = projectRules.allowedTaskTypeIds != null

  /*
    A task type the newly-selected project does not accept is cleared rather than
    left standing. It has to be one or the other and there is no third option:
    the picker no longer lists it, so `SelectValue` renders its placeholder and
    the form *looks* empty while still holding the old id — which is the state
    that submits a value the user cannot see and gets a 400 for it.

    Deliberately keyed on the settings arriving rather than done in the project
    picker's `onChange`, which is a tick too early: the allow-list is still in
    flight there, so nothing yet knows whether the type survives the move. Most
    moves keep it, which is why it is not simply cleared alongside the client and
    the assignee.
  */
  React.useEffect(() => {
    const allowed = projectRules.allowedTaskTypeIds
    if (taskTypeId != null && allowed != null && !allowed.has(taskTypeId)) {
      setValue('taskTypeId', null)
    }
  }, [projectRules, taskTypeId, setValue])

  /**
   * C-012 · recomputed server-side on every change to the four inputs that move
   * it, and shown before the user commits (§4B.1).
   *
   * The assignee is in there because their approved leave stops the SLA clock —
   * promising a date against someone who is away all next week is the kind of
   * commitment that reaches a client. `from` is omitted rather than passed as
   * `new Date()`: a fresh instant every render would mint a new query key and
   * refetch forever. The server defaults it to now, which is what a ticket being
   * raised right now means.
   */
  const slaPreview = usePlannedCloseDate({ projectId, taskTypeId, level, assigneeId })

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
  //
  // **B-029 · `isActive: true` stays, and the S-15 ticket list's dropped.**
  // Same operation, opposite answers, and the question is "new or historical":
  // this is a picker for a ticket that does not exist yet, which is exactly
  // what deactivating a client blocks (blueprint line 523). The ticket list
  // filters tickets that already exist, so sending it there hid the history
  // the same sentence says must never be hidden.
  //
  // C-021 · `showAllClients` drops `projectId` rather than adding a second
  // parameter — §4B.2 calls it a toggle on the same dropdown, not a second
  // list beside it, and the client is still selectable and refused by the
  // same `newTicketBlockReason` gate either way.
  const [showAllClients, setShowAllClients] = React.useState(false)
  const { data: clientsData, isSuccess: clientsLoaded } = useListClients(
    { ...(showAllClients ? {} : { projectId: projectId ?? undefined }), isActive: true, limit: 200 },
    { query: { enabled: projectId != null } },
  )
  const clients = React.useMemo(() => clientsData?.data ?? [], [clientsData])

  // C-021 · the account manager auto-fills onto watchers below whether or not
  // they are staffed on this project — an account manager's job is client
  // visibility, not project membership. `WatcherPicker` can only render a
  // selected id it finds in its candidate list, so without this a manager who
  // is not a project member would be sent on the wire and silently missing
  // from their own chip.
  const watcherCandidates = React.useMemo(() => {
    const manager = clients.find((c) => c.id === clientId)?.accountManager
    if (manager == null || members.some((u) => u.id === manager.id)) return members
    return [...members, manager as User]
  }, [members, clients, clientId])

  // B-027 · `includeInactive` is deliberately left off here, unlike on the
  // ticket *detail* page. This is a picker, and a contact removed from the
  // client master must stop being offered on new tickets — which is the whole
  // reason that parameter defaults to false. Stream B's edit, one argument
  // wide, flagged for Stream C.
  const { data: contactsData, isSuccess: contactsLoaded } = useListClientContacts(clientId ?? 0, undefined, {
    query: { enabled: clientId != null },
  })
  const contacts = React.useMemo(
    () => (contactsData?.data ?? []).filter((c): c is Contact & { id: number } => c.id != null),
    [contactsData],
  )

  const canBackdateOrOverride = me?.data.role === 'ADMIN' || me?.data.role === 'PM'
  // §4B.2's "All clients toggle for Admin/PM" — the same two roles §7.5
  // already trusts to backdate or override the planned close date, kept as
  // its own name here so the two rules do not read as one just because they
  // happen to share a role check today.
  const canViewAllClients = canBackdateOrOverride
  // C-021 · `createClientContact` is `master.write` on the server
  // (`ClientController`), which today is Admin alone — not the "support desk
  // never has to leave the form" every role §4B.2 implies. Gated to match
  // rather than let a Support agent fill the dialog in and hit a 403 the
  // button never warned them about; see the folder README for the contract
  // gap this leaves open for Stream B.
  const canAddContact = me?.data.role === 'ADMIN'

  /**
   * C-021 · the inline "+ Add contact" dialog and the contact it hands back.
   *
   * `ContactEditorDialog` reports success only by invalidating the query
   * `useListClientContacts` already reads here — it has no callback to hand
   * a fresh id up to this screen, and it is Stream B's file rather than a
   * seam this task should be widening without their sign-off. So the new
   * contact is found the same way its own refetch does: snapshot which ids
   * exist before the dialog opens, then watch `contacts` for the one id that
   * was not in that snapshot.
   */
  const [contactDialogOpen, setContactDialogOpen] = React.useState(false)
  const priorContactIdsRef = React.useRef<Set<number>>(new Set())
  const awaitingNewContactRef = React.useRef(false)
  React.useEffect(() => {
    if (!awaitingNewContactRef.current) return
    const created = contacts.find((c) => !priorContactIdsRef.current.has(c.id))
    if (created) {
      setValue('clientContactId', created.id, { shouldValidate: true, shouldDirty: true })
      awaitingNewContactRef.current = false
    }
  }, [contacts, setValue])

  // One key per logical ticket, minted before the first attempt and rotated only
  // once a ticket actually exists. A key regenerated per click would make the
  // resubmit-after-timeout case — the one it is for — look like a new ticket.
  const idempotencyKey = React.useRef(newIdempotencyKey())
  const createTicket = useCreateTicket()

  // `ticketId: null` — deferred mode. Nothing uploads until `flush` is handed
  // the ID the 201 returns; see the picker in the Extra group below.
  const attachments = useTicketAttachments({ ticketId: null })
  // C-027. This form stages files locally and uploads them after the 201, so its
  // gate is the *only* one a file passes before the ticket exists — the numbers
  // it enforces have to be the server's, or a staged file is refused here and
  // never gets the chance to be accepted there.
  const attachmentLimits = useAttachmentLimits()

  // C-024. `RichTextEditor` intercepts an image paste itself and hands the files
  // out through `onPasteFiles`; without a handler it drops them, which is the
  // right default for the component and the wrong outcome on this screen — the
  // description's own hint invites the user to paste from a client's email.
  // Routing them through the picker's handle rather than straight to
  // `attachments.add` is what keeps them inside the same running totals as a
  // drop: `add` uploads whatever it is given, and validation lives in the picker.
  const pickerRef = React.useRef<AttachmentPickerHandle>(null)

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
    // C-072 · only if the master still offers it. `TaskTypeService` refuses a
    // retired level as a default, so the two masters should agree — but they
    // arrive here as two independent queries that go stale independently, and
    // a `defaultLevel` the picker below does not show is the worst kind of
    // disagreement: the form renders with no chip selected, clicking the chip
    // the user wanted is a no-op if it is the one already in state, and
    // `taskTypeRules.levels` refuses the submit with a message about a level
    // nobody chose. One `includes` avoids all of it.
    if (fallback && levels.includes(fallback)) setValue('level', fallback, { shouldValidate: false })
  }, [taskTypeId, taskTypes, levels, setValue])

  // A contact belongs to exactly one client, so it cannot survive the client changing.
  //
  // C-021 · the same real-change guard also carries §4B.2's "the account
  // manager as a watcher" auto-fill. `setValue` rather than a chip the user
  // cannot remove — it is a starting point, not a rule, so a desk that knows
  // this ticket does not need the account manager watching can still take
  // them off. Keyed off the same `previousClientId` transition as the contact
  // clear above so Save & Create Another's retained client does not re-add a
  // manager the user removed earlier in the same batch.
  const previousClientId = React.useRef(clientId)
  React.useEffect(() => {
    if (previousClientId.current !== clientId) {
      previousClientId.current = clientId
      setValue('clientContactId', null)
      const manager = clients.find((c) => c.id === clientId)?.accountManager
      if (manager?.id != null) {
        const current = getValues('watcherIds')
        if (!current.includes(manager.id)) setValue('watcherIds', [...current, manager.id])
      }
    }
  }, [clientId, clients, setValue, getValues])

  /**
   * B-029 · the last gate before a ticket is posted against a client that may
   * no longer take one.
   *
   * <h2>Why the dropdown is not enough</h2>
   *
   * `getOptionDisabled` guards a *click*. It does not guard a `clientId` that
   * is already in the form — and this form outlives its own fetches: Save &
   * Create Another keeps the client (`retainedForNextTicket`) across an
   * arbitrary number of tickets, so the selection can easily predate a
   * deactivation or the removal of the client's last primary contact by an
   * hour. Both gates are administrative acts by somebody else, on another
   * screen, while this one sits open.
   *
   * <h2>And there is nothing behind it</h2>
   *
   * `POST /tickets` has no controller. B-028 stated both refusals in
   * `createTicket`'s contract description — a 400 keyed on `clientId`, not a
   * 404, because the client is legitimately visible and it is the *combination*
   * being refused — for whoever mounts it. Until then this is the only
   * enforcement in the system, which is a thing to say out loud rather than to
   * leave implied by a disabled option.
   *
   * <h2>Absence counts, but only once the list has actually loaded</h2>
   *
   * A deactivated client is not merely blocked here, it is gone: the query
   * sends `isActive: true`. So "selected but not in the list" is the ordinary
   * shape of this refusal rather than an odd case, and `clientsLoaded` is what
   * keeps it from firing against an in-flight fetch — refusing a valid ticket
   * because a request had not come back yet would be a worse bug than the one
   * this prevents.
   */
  function clientRefusal(values: TicketFormValues): string | null {
    if (values.clientId == null || !clientsLoaded) return null
    const client = clients.find((c) => c.id === values.clientId)
    if (!client) {
      return 'That client is no longer available on this project. Pick another.'
    }
    const blocked = newTicketBlockReason(client)
    return blocked == null ? null : `${blocked}. Pick another client or fix the client master.`
  }

  async function onSubmit(values: TicketFormValues, action: TicketSaveAction) {
    const refusal = clientRefusal(values)
    if (refusal) {
      // On the field, not in a toast: the input is what has to change, and a
      // toast on a form this long is read after the user has scrolled past it.
      setError('clientId', { type: 'manual', message: refusal })
      setFocus('clientId')
      return
    }

    setSaving(action)
    try {
      const response = await createTicket.mutateAsync({
        data: toCreateRequest(values, action),
        idempotencyKey: idempotencyKey.current,
      })
      const ticketId = response.data.ticketId

      // The ticket now exists, so the staged files finally have somewhere to go.
      // Deliberately after the create and never before it: uploading first would
      // need a ticket ID we do not have, and `TicketCreateRequest` has no
      // `attachmentIds` field to carry them in the create call itself.
      //
      // A failure here is **not** a failed create. The ticket is real and the
      // user is told which files did not make it, rather than being shown a
      // "ticket was not created" error for a ticket that was.
      const upload = attachments.pendingCount > 0 ? await attachments.flush(ticketId) : null
      if (upload && upload.failed.length > 0) {
        toast({
          variant: 'danger',
          title: `${ticketId} was created, but ${upload.failed.length} file${upload.failed.length === 1 ? '' : 's'} did not upload`,
          description: `${upload.failed.join(', ')} — attach ${upload.failed.length === 1 ? 'it' : 'them'} again from the ticket.`,
        })
      }

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
        // Attachments describe one ticket, never a batch — the same rule that
        // decides what `retainedForNextTicket` keeps. Carrying them over would
        // silently re-upload the last ticket's screenshots onto the next one.
        attachments.reset()
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
                      setShowAllClients(false)
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
            hint={
              /*
                C-071 · a restricted project says so. A picker that is simply
                shorter than it was on the last project reads as a list that has
                lost its data, and the person raising the ticket cannot change
                the setting — only a PM or Admin can, on a screen most of the six
                roles cannot open. Naming it is what turns "where did it go" into
                "ask the PM".
              */
              taskTypesRestricted
                ? 'This project accepts only these task types. Sets the default priority and the SLA the close date is computed from.'
                : 'Sets the default priority and the SLA the close date is computed from.'
            }
          >
            {(aria) => (
              <Controller
                control={control}
                name="taskTypeId"
                render={({ field }) => (
                  <Select
                    /*
                      C-071 · `''` and not `undefined` for "nothing chosen".
                      Radix reads an `undefined` value as *uncontrolled* and goes
                      on displaying whatever it last had, so clearing a task type
                      the new project does not accept would leave the old label
                      drawn under a picker that no longer offers it — the exact
                      invisible-stale-value this clearing exists to prevent. It
                      also silences the "changing from uncontrolled to
                      controlled" warning the first selection used to emit.
                    */
                    value={field.value != null ? String(field.value) : ''}
                    onValueChange={(value) => field.onChange(Number(value))}
                  >
                    <SelectTrigger {...aria}>
                      <SelectValue placeholder="Select a task type" />
                    </SelectTrigger>
                    <SelectContent>
                      {offeredTaskTypes.map((type) => (
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
            required={mandates('CLIENT')}
            error={errors.clientId?.message}
            hint={
              <>
                {mandates('CLIENT')
                  ? 'Required on every ticket in this project.'
                  : taskTypeId != null && clientRequiredIds.has(taskTypeId)
                    ? 'Required for this task type.'
                    : showAllClients
                      ? 'Every active client, not only this project’s.'
                      : 'Clients mapped to the selected project.'}
                {canViewAllClients && (
                  <label className="ml-1 inline-flex items-center gap-1.5">
                    <input
                      type="checkbox"
                      checked={showAllClients}
                      onChange={(event) => setShowAllClients(event.target.checked)}
                      className="h-3.5 w-3.5 rounded border-border text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                    />
                    Show all clients
                  </label>
                )}
              </>
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
                    // B-028 · blueprint line 948 — "at least one primary
                    // contact before the client can be selected on a ticket".
                    // **B-029 ·** and line 523's "blocks new ticket creation",
                    // which is the second gate on the same control.
                    //
                    // Both come from `newTicketBlockReason` rather than being
                    // derived here. B-028 wrote its half inline, correctly,
                    // while it was the only half; a second one beside it is how
                    // a rule ends up with two answers, which is the drift this
                    // stream has now fixed three times.
                    //
                    // Shown and refused, never filtered out. A client that
                    // simply is not in the list looks like a list that has lost
                    // its data, and the person raising the ticket is usually
                    // the one who can go and fix the master. (An *inactive*
                    // client is filtered out upstream by `isActive: true`, and
                    // that stays: it is not an oversight to be corrected but a
                    // deliberate administrative act, and greying every closed
                    // client forever would grow the list without bound. The
                    // rule still runs here, because the query says what the
                    // list holds and this says what may be chosen.)
                    getOptionDisabled={newTicketBlockReason}
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
            required={mandates('CLIENT_CONTACT')}
            error={errors.clientContactId?.message}
            hint={
              canAddContact
                ? 'The person who reported it.'
                : 'The person who reported it. A new one takes an Admin, from the Client Master.'
            }
          >
            {(aria) => (
              <div className="flex items-start gap-2">
                <div className="min-w-0 flex-1">
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
                </div>
                {/*
                  C-021 · Admin only — see `canAddContact` above. `clientId`
                  is non-null by the time this is reachable (the button is
                  disabled otherwise), so the dialog below never mounts
                  against a client that does not exist yet.
                */}
                {canAddContact && (
                  <Button
                    type="button"
                    variant="secondary"
                    size="sm"
                    className="mt-0 shrink-0"
                    // `!contactsLoaded` as well as `clientId == null`: opening
                    // the dialog before this client's contacts have loaded
                    // once would snapshot an empty prior-ids set, and the
                    // effect below would then read the ordinary first load —
                    // Sara Kapoor, say — as "the contact that was just added".
                    disabled={clientId == null || !contactsLoaded || isSaving}
                    onClick={() => {
                      priorContactIdsRef.current = new Set(contacts.map((c) => c.id))
                      awaitingNewContactRef.current = true
                      setContactDialogOpen(true)
                    }}
                  >
                    + Add contact
                  </Button>
                )}
              </div>
            )}
          </FormField>

          {clientId != null && (
            <ContactEditorDialog
              clientId={clientId}
              contact={null}
              open={contactDialogOpen}
              onOpenChange={setContactDialogOpen}
            />
          )}

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
                    onPasteFiles={(files) => pickerRef.current?.addFiles(files)}
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

        {/* ── Where it happened ────────────────────────────────────────── */}
        {/*
          C-068 · §7.5's fourth field group, and it sits here rather than inside
          Extra because that is where §7.5's own table puts it — between Core and
          People. The whole group exists so that "which module generates the most
          concerns" is a query rather than a reading exercise, and so that a
          developer opening a bug is not starting from a one-line title.
        */}
        <FieldGroup
          title="Where it happened"
          description="Routes the ticket and saves the assignee a round trip asking where to look."
        >
          <FormField
            id="moduleId"
            label="Module"
            required={moduleRequired || mandates('MODULE')}
            error={errors.moduleId?.message}
            hint={
              /*
                The empty case is spelled out rather than left as a dropdown
                that opens onto nothing. `GET /masters/modules` is B-064 and is
                unbuilt on the real backend today — it answers 404 — so against
                a live server this list *is* empty, and a bug-type ticket cannot
                be raised until it lands.

                **The requirement is not waived when the master is missing**,
                which is the tempting fix and the wrong one: it would let a
                network failure silently disable a validation rule, and the
                tickets raised during the outage would carry no module at all —
                the data poisoning §7.5 wrote the rule to prevent, except
                invisible. Saying plainly why the field is empty is the honest
                version of being blocked.
              */
              modulesEmpty
                ? 'The module list could not be loaded, so there is nothing to choose from yet.'
                : /*
                     C-071 · the project's rule is named ahead of §7.5's, because
                     it is the one that surprises: a change request needing a
                     module is not something §7.5 would lead anyone to expect,
                     and "required for bug-type tickets" shown on a change
                     request reads as a bug in the form.
                  */
                  mandates('MODULE')
                  ? 'Required on every ticket in this project — it is what routes this to the right team.'
                  : moduleRequired
                    ? 'Required for bug-type tickets — it is what routes this to the right team.'
                    : 'Optional for change requests and internal work. Leave it blank rather than guessing.'
            }
          >
            {(aria) => (
              <Controller
                control={control}
                name="moduleId"
                render={({ field }) => (
                  <Select
                    value={field.value != null ? String(field.value) : undefined}
                    onValueChange={(value) => field.onChange(Number(value))}
                  >
                    <SelectTrigger {...aria}>
                      <SelectValue placeholder="Select a module" />
                    </SelectTrigger>
                    <SelectContent>
                      {modules.map((module) => (
                        <SelectItem key={module.id} value={String(module.id)}>
                          {module.name}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                )}
              />
            )}
          </FormField>

          <FormField
            id="screenName"
            label="Screen name"
            required={mandates('SCREEN_NAME')}
            error={errors.screenName?.message}
            hint="The screen it happened on."
          >
            {(aria) => <Input {...aria} {...register('screenName')} placeholder="Fee Receipt Print" />}
          </FormField>

          <FormField
            id="feature"
            label="Feature"
            required={mandates('FEATURE')}
            error={errors.feature?.message}
            hint="The feature within that screen."
            className="sm:col-span-2"
          >
            {(aria) => (
              <Input {...aria} {...register('feature')} placeholder="Reprint with duplicate watermark" />
            )}
          </FormField>

          <FormField
            id="stepsToGenerate"
            label="Steps to generate"
            required={mandates('STEPS_TO_GENERATE')}
            error={errors.stepsToGenerate?.message}
            hint="Numbered steps and screenshots — what a developer needs in order to reproduce it without coming back to ask."
            className="sm:col-span-2"
          >
            {(aria) => (
              // Same `Controller` binding and the same shared editor the
              // description uses, for the reason C-066 gives: `register()`
              // cannot bind a contentEditable, which emits no `input` event
              // carrying a `value`. A pasted screenshot goes to the attachment
              // picker here too — the person writing repro steps is the most
              // likely person on this form to be pasting one.
              <Controller
                name="stepsToGenerate"
                control={control}
                render={({ field }) => (
                  <RichTextEditor
                    {...aria}
                    value={field.value}
                    onChange={field.onChange}
                    onBlur={field.onBlur}
                    showCount
                    maxLength={createTicketBodyStepsToGenerateMax}
                    placeholder="1. Open Fees → Receipts&#10;2. Print a receipt already printed once&#10;3. The duplicate watermark is missing"
                    onPasteFiles={(files) => pickerRef.current?.addFiles(files)}
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
            // Unconditional, like Task description and Estimated effort and
            // unlike the `mandates(…)` fields around it: this is the form's own
            // rule now, not a project setting. Drawn on the draft path too, for
            // the same reason those two are — a marker that appears and
            // disappears as the pointer moves between save buttons is worse
            // than one that states the primary action's rule.
            required
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
                    candidates={watcherCandidates}
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
              hint="Leave blank to use the computed date. Overriding it is a PM and Admin decision, and the ticket is measured against whatever is here."
            >
              {(aria) => <Input {...aria} {...register('plannedCloseDate')} type="datetime-local" />}
            </FormField>
          ) : (
            <ReadOnlyField
              label="Planned close date"
              value="Computed from the SLA policy on save"
              hint="Overriding it requires PM or Admin."
            />
          )}

          <ReadOnlyField label="Actual close date" value="—" hint="Set at closure, never here." />

          {/*
            Full width and below both dates, because it is about the pair of
            them: for everyone it explains the date the server will store, and
            for a PM who has typed one it is the alternative they are choosing
            against. `setValue` marks it dirty and validates, so pressing Use
            the computed date behaves exactly as typing that value would.
          */}
          <SlaPreview
            {...slaPreview}
            className="sm:col-span-2"
            clientTimezone={selectedClient?.timezone}
            overrideValue={canBackdateOrOverride ? plannedCloseDate : ''}
            onUseComputed={
              canBackdateOrOverride
                ? (value) =>
                    setValue('plannedCloseDate', value, { shouldDirty: true, shouldValidate: true })
                : undefined
            }
          />
        </FieldGroup>

        {/* ── Extra ────────────────────────────────────────────────────── */}
        <FieldGroup title="Extra">
          {/*
            C-022 · §4B.2: "When the client is set and the reporter is a
            client contact, the ticket is marked client-raised" — a fact the
            server derives from the Core group's two fields above, not a
            choice offered here. S-19's own field table (§7.5) has no
            Client-raised row for the same reason Ticket ID and Assigned By
            have none: it is written, not filled in. `toCreateRequest` sends
            the derived value; nothing renders it on this form.
          */}

          {/*
            Deferred mode — there is no ticket to attach to until the 201 comes
            back, and `TicketCreateRequest` carries no `attachmentIds` to send
            even if there were. Files stage here and upload in `onSubmit` once
            the ticket has an ID. See `useTicketAttachments`.
          */}
          <FormField
            id="ticket-attachments"
            label="Attachments"
            className="sm:col-span-2"
            hint="Paste a screenshot anywhere on this form, drag files in, or browse. Uploaded once the ticket is created; scanned before anyone can open them."
          >
            {(aria) => (
              <AttachmentPicker
                {...aria}
                ref={pickerRef}
                items={attachments.items}
                onAdd={attachments.add}
                onRemove={attachments.remove}
                limits={attachmentLimits}
                disabled={isSaving}
              />
            )}
          </FormField>

          <div className="sm:col-span-2 flex flex-wrap items-center gap-2 rounded-control bg-subtle px-3 py-2.5">
            <span className="text-caption text-content-muted">Arriving in their own tasks:</span>
            <Chip>First comment · C-029</Chip>
          </div>
        </FieldGroup>
      </div>

      {/* ── Actions — §7.5 ───────────────────────────────────────────────── */}
      <div className="fixed inset-x-0 bottom-0 border-t border-border bg-surface px-8 py-4 shadow-modal">
        <div className="mx-auto flex max-w-4xl items-center gap-4">
          {/* What the save will actually do with the assignee. Save & Assign
              now insists on one, so the empty state is no longer "this will
              save unassigned" but "Save as Draft is the only button that will
              take this" — the one path that still waives the rule. Saying which
              button works is worth more than repeating the field's own error.

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
              'Pick who it is assigned to — only Save as Draft will take it without one.'
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
