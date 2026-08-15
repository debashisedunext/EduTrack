import * as React from 'react'

import { ApiError } from '@/api/http'
import type { NotificationChannel } from '@/api/generated/model/notificationChannel'
import type { NotificationRecipient } from '@/api/generated/model/notificationRecipient'
import type { NotificationTemplate } from '@/api/generated/model/notificationTemplate'

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
  CHANNEL_LABELS,
  RECIPIENT_LABELS,
  emptyTemplateForm,
  humaniseEvent,
  templateFormErrors,
  toFormValues,
  toPatchRequest,
  toWriteRequest,
  unknownMergeTags,
  type TemplateFormValues,
} from './templateForm'
import {
  useCreateNotificationTemplate,
  useNotificationTemplate,
  useNotificationTemplates,
  useTemplateVocabulary,
  useUpdateNotificationTemplate,
} from './templateQueries'

/**
 * S-15 Notification Template Master. B-022.
 *
 * One page: a grid grouped by event, a create dialog and an edit dialog — the
 * shape B-020 gave S-11 and B-021 gave S-12.
 *
 * **Grouped by event rather than listed flat**, which the other masters are not,
 * because the unit an Admin thinks in is the event: "what does a handoff say,
 * and where does it go". A flat list of fifty rows makes the in-app and email
 * wording of one event two unrelated entries, and the question people actually
 * arrive with — is this event noisy — becomes a scan.
 *
 * **The lock is stated on the row, not discovered at the save.** `isMandatory`
 * comes off the server, derived from the event's category, so a mail blueprint
 * §4B.6 marks never-optional renders as a locked statement here. A toggle whose
 * only outcome is a 409 is a control that should not be operable — the same call
 * B-021 made on the escalation flag.
 */
export function NotificationTemplateListPage() {
  const { data: templates, isPending, isError } = useNotificationTemplates()
  const { data: vocabulary } = useTemplateVocabulary()
  const [creating, setCreating] = React.useState(false)
  const [editingId, setEditingId] = React.useState<number | null>(null)

  // Grouped in the order the server returned, which is already
  // (eventCode, channel) — so this preserves a sort rather than doing one.
  const groups = React.useMemo(() => {
    const byEvent = new Map<string, NotificationTemplate[]>()
    for (const template of templates ?? []) {
      const key = template.eventCode ?? ''
      byEvent.set(key, [...(byEvent.get(key) ?? []), template])
    }
    return [...byEvent.entries()]
  }, [templates])

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6 p-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-content">Notification templates</h1>
          <p className="mt-1 max-w-2xl text-sm text-content-muted">
            What every notification this system sends actually says, and who it goes to. One
            template per event per channel, editable without a release. Templates cannot be
            deleted — an event goes on firing whether or not it has wording, and a missing
            template is a notification that silently never arrives — but they can be switched
            off, except for the mail that must never be.
          </p>
        </div>
        <Button onClick={() => setCreating(true)}>New template</Button>
      </header>

      {isPending ? (
        <Skeleton className="h-96 w-full" />
      ) : isError || !templates ? (
        <p className="text-sm text-danger-text">Notification templates could not be loaded.</p>
      ) : (
        <div className="flex flex-col gap-6">
          {groups.map(([eventCode, rows]) => (
            <EventGroup
              key={eventCode}
              eventCode={eventCode}
              rows={rows}
              onEdit={(id) => setEditingId(id)}
            />
          ))}
        </div>
      )}

      <CreateTemplateDialog
        open={creating}
        existing={templates ?? []}
        onOpenChange={setCreating}
      />
      <EditTemplateDialog
        templateId={editingId}
        mergeTags={vocabulary?.mergeTags ?? []}
        recipientOptions={vocabulary?.recipients ?? []}
        onClose={() => setEditingId(null)}
      />
    </div>
  )
}

function EventGroup({
  eventCode,
  rows,
  onEdit,
}: {
  eventCode: string
  rows: NotificationTemplate[]
  onEdit: (templateId: number) => void
}) {
  const category = rows[0]?.category ?? 'OTHER'

  return (
    <section className="flex flex-col gap-2">
      <div className="flex flex-wrap items-baseline gap-2">
        <h2 className="text-base font-semibold text-content">{humaniseEvent(eventCode)}</h2>
        <Chip variant="neutral">{CATEGORY_LABELS[category] ?? category}</Chip>
        <code className="text-xs text-content-muted">{eventCode}</code>
      </div>
      <TableContainer>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead scope="col">Channel</TableHead>
              <TableHead scope="col">Goes to</TableHead>
              <TableHead scope="col">Subject</TableHead>
              <TableHead scope="col">Status</TableHead>
              <TableHead scope="col">
                <span className="sr-only">Actions</span>
              </TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((template) => (
              <TemplateRow
                key={template.id}
                template={template}
                onEdit={() => onEdit(template.id ?? -1)}
              />
            ))}
          </TableBody>
        </Table>
      </TableContainer>
    </section>
  )
}

function TemplateRow({
  template,
  onEdit,
}: {
  template: NotificationTemplate
  onEdit: () => void
}) {
  const recipients = template.recipients ?? []
  const goesToClient = recipients.includes('CLIENT_CONTACT')

  return (
    <TableRow>
      <TableCell className="font-medium text-content">
        {CHANNEL_LABELS[template.channel ?? ''] ?? template.channel}
      </TableCell>
      <TableCell>
        <span className="flex flex-wrap gap-1">
          {recipients.map((recipient) => (
            <Chip
              key={recipient}
              // The one recipient that reaches outside the organisation, called
              // out on the row rather than only in the dialog: this is the
              // wording a customer reads, and knowing that before you start
              // editing is the difference between a reword and an incident.
              variant={recipient === 'CLIENT_CONTACT' ? 'warning' : 'neutral'}
            >
              {RECIPIENT_LABELS[recipient] ?? recipient}
            </Chip>
          ))}
        </span>
      </TableCell>
      <TableCell className="max-w-xs truncate text-content-muted">
        {template.subjectTemplate ?? (
          <span className="text-xs italic">no subject — in-app entries have a title</span>
        )}
      </TableCell>
      <TableCell>
        {template.isActive ? (
          template.isMandatory ? (
            <Chip variant="info">Always on</Chip>
          ) : (
            <Chip variant="success">On</Chip>
          )
        ) : (
          <Chip variant="neutral">Off</Chip>
        )}
      </TableCell>
      <TableCell className="text-right">
        <Button variant="ghost" size="sm" onClick={onEdit}>
          {goesToClient ? 'Edit — client-facing' : 'Edit'}
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
 * behind — B-011's argument for a single resource form, repeated by B-021. The
 * only differences between create and edit are whether the event and channel are
 * editable and whether the on/off toggle is locked, and all three are props
 * rather than a fork.
 */
function TemplateFields({
  values,
  errors,
  identityLocked,
  mandatory,
  eventOptions,
  channelOptions,
  recipientOptions,
  mergeTags,
  onChange,
}: {
  values: TemplateFormValues
  errors: Partial<Record<keyof TemplateFormValues, string>>
  identityLocked: boolean
  mandatory: boolean
  eventOptions: readonly string[]
  channelOptions: readonly NotificationChannel[]
  recipientOptions: readonly string[]
  mergeTags: readonly string[]
  onChange: (patch: Partial<TemplateFormValues>) => void
}) {
  const bodyRef = React.useRef<HTMLTextAreaElement>(null)
  const unknown = unknownMergeTags(
    `${values.subjectTemplate}\n${values.bodyTemplate}`,
    mergeTags,
  )

  /**
   * Inserts at the caret, not at the end.
   *
   * Appending would be simpler and would put `{{ticket_id}}` after the closing
   * `</p>` of a body somebody was editing the middle of, which is a helper that
   * makes more work than it saves.
   */
  const insertTag = (tag: string) => {
    const element = bodyRef.current
    const placeholder = `{{${tag}}}`
    if (!element) {
      onChange({ bodyTemplate: values.bodyTemplate + placeholder })
      return
    }
    const start = element.selectionStart
    const end = element.selectionEnd
    const next = values.bodyTemplate.slice(0, start) + placeholder + values.bodyTemplate.slice(end)
    onChange({ bodyTemplate: next })
    requestAnimationFrame(() => {
      element.focus()
      element.setSelectionRange(start + placeholder.length, start + placeholder.length)
    })
  }

  return (
    <div className="flex flex-col gap-4 py-4">
      <div className="grid gap-4 sm:grid-cols-2">
        <label className="flex flex-col gap-1 text-sm">
          <span className="font-medium text-content">Event</span>
          {identityLocked ? (
            <Input value={values.eventCode} disabled aria-describedby="template-identity-hint" />
          ) : (
            <select
              className="h-10 rounded-control border border-border bg-surface px-3 text-sm text-content"
              value={values.eventCode}
              aria-describedby="template-identity-hint"
              onChange={(e) => onChange({ eventCode: e.target.value })}
            >
              <option value="">Pick an event…</option>
              {eventOptions.map((code) => (
                <option key={code} value={code}>
                  {humaniseEvent(code)}
                </option>
              ))}
            </select>
          )}
          {errors.eventCode ? (
            <span role="alert" className="text-xs text-danger-text">
              {errors.eventCode}
            </span>
          ) : null}
        </label>

        <label className="flex flex-col gap-1 text-sm">
          <span className="font-medium text-content">Channel</span>
          {identityLocked ? (
            <Input
              value={CHANNEL_LABELS[values.channel] ?? values.channel}
              disabled
              aria-describedby="template-identity-hint"
            />
          ) : (
            <select
              className="h-10 rounded-control border border-border bg-surface px-3 text-sm text-content"
              value={values.channel}
              aria-describedby="template-identity-hint"
              onChange={(e) => onChange({ channel: e.target.value as NotificationChannel })}
            >
              {channelOptions.map((channel) => (
                <option key={channel} value={channel}>
                  {CHANNEL_LABELS[channel] ?? channel}
                </option>
              ))}
            </select>
          )}
        </label>
      </div>

      <span id="template-identity-hint" className="text-xs text-content-muted">
        {identityLocked
          ? 'Permanent. The event and channel together are this template’s identity — the mail engine resolves by the pair, and sent mail points at this row, so re-pointing it would change what those records claim to have been rendered from.'
          : 'The bell is not a channel: it renders the same wording the in-app toast does, from the in-app template.'}
      </span>

      <fieldset className="flex flex-col gap-2 text-sm">
        <legend className="font-medium text-content">Goes to</legend>
        <div className="flex flex-wrap gap-x-4 gap-y-2">
          {recipientOptions.map((recipient) => (
            <label key={recipient} className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={values.recipients.includes(recipient as NotificationRecipient)}
                onChange={(e) =>
                  onChange({
                    recipients: e.target.checked
                      ? [...values.recipients, recipient as NotificationRecipient]
                      : values.recipients.filter((r) => r !== recipient),
                  })
                }
              />
              <span>{RECIPIENT_LABELS[recipient] ?? recipient}</span>
            </label>
          ))}
        </div>
        <span className="text-xs text-content-muted">
          These are positions relative to a ticket, not roles —{' '}
          <em>the project&rsquo;s PM</em> means the manager of the project this ticket is on,
          not everybody holding the PM role. <strong>Client contacts</strong> reaches outside
          the organisation.
        </span>
        {errors.recipients ? (
          <span role="alert" className="text-xs text-danger-text">
            {errors.recipients}
          </span>
        ) : null}
      </fieldset>

      {/*
        The hint and the error sit *outside* the `<label>`, unlike the compact
        fields above. A wrapping label contributes its whole text content to the
        control's accessible name, so a hint inside one makes a screen reader
        announce three sentences of guidance every time focus lands on the box —
        and the guidance is already reachable through `aria-describedby`, which
        is announced separately and at the right moment.
      */}
      <div className="flex flex-col gap-1 text-sm">
        <label className="flex flex-col gap-1">
          <span className="font-medium text-content">
            Subject {values.channel === 'EMAIL' ? '' : '(optional)'}
          </span>
          <Input
            value={values.subjectTemplate}
            aria-invalid={errors.subjectTemplate != null}
            aria-describedby={
              errors.subjectTemplate ? 'template-subject-error' : 'template-subject-hint'
            }
            placeholder="Handed to you at {{stage}} by {{actor}}"
            onChange={(e) => onChange({ subjectTemplate: e.target.value })}
          />
        </label>
        <span id="template-subject-hint" className="text-xs text-content-muted">
          {values.channel === 'EMAIL'
            ? 'Write what happened, not the ticket code — the sender prefixes [CRM-26-00347] itself so the thread groups cleanly, and repeating it here would double it.'
            : 'An in-app entry has a title rather than a subject, so this is usually blank. A browser push does have one.'}
        </span>
        {errors.subjectTemplate ? (
          <span id="template-subject-error" role="alert" className="text-xs text-danger-text">
            {errors.subjectTemplate}
          </span>
        ) : null}
      </div>

      <div className="flex flex-col gap-1 text-sm">
        <label className="flex flex-col gap-1">
          <span className="font-medium text-content">Body</span>
          <textarea
            ref={bodyRef}
            className="min-h-40 rounded-control border border-border bg-surface p-3 font-mono text-xs text-content focus:outline-none focus:ring-2 focus:ring-primary"
            value={values.bodyTemplate}
            aria-invalid={errors.bodyTemplate != null}
            aria-describedby={errors.bodyTemplate ? 'template-body-error' : 'template-body-hint'}
            onChange={(e) => onChange({ bodyTemplate: e.target.value })}
          />
        </label>
        <span id="template-body-hint" className="text-xs text-content-muted">
          {values.channel === 'EMAIL'
            ? 'HTML. The house layout, logo and button chrome wrap around this — keep it to the content.'
            : 'Plain text. It is the bell entry and the toast, so one or two sentences.'}
        </span>
        {errors.bodyTemplate ? (
          <span id="template-body-error" role="alert" className="text-xs text-danger-text">
            {errors.bodyTemplate}
          </span>
        ) : null}
      </div>

      {/*
        The merge-tag catalogue, offered rather than only validated. An Admin
        who cannot see the list has to guess, and a guess that is wrong saves
        cleanly and prints literal braces in a client-facing mail — which is
        exactly what the server refuses and what this row is here to prevent
        being necessary.
      */}
      <fieldset className="flex flex-col gap-2 rounded-card bg-subtle p-3 text-sm">
        <legend className="sr-only">Merge tags</legend>
        <span className="text-xs font-medium text-content">Insert a merge tag</span>
        <div className="flex flex-wrap gap-1">
          {mergeTags.map((tag) => (
            <button
              key={tag}
              type="button"
              className="rounded-chip bg-surface px-2 py-0.5 font-mono text-xs text-content-muted ring-1 ring-border hover:text-content"
              onClick={() => insertTag(tag)}
            >
              {`{{${tag}}}`}
            </button>
          ))}
        </div>
        {unknown.length > 0 ? (
          <span role="alert" className="text-xs text-danger-text">
            {unknown.map((t) => `{{${t}}}`).join(', ')}{' '}
            {unknown.length === 1 ? 'is not a merge tag' : 'are not merge tags'} and would be
            printed literally, braces included.
          </span>
        ) : null}
      </fieldset>

      <fieldset className="flex flex-col gap-2 rounded-card bg-subtle p-3 text-sm">
        <legend className="sr-only">On or off</legend>
        <label className="flex items-start gap-2">
          <input
            type="checkbox"
            className="mt-1"
            checked={mandatory ? true : values.isActive}
            disabled={mandatory}
            aria-describedby="template-active-hint"
            onChange={(e) => onChange({ isActive: e.target.checked })}
          />
          <span>
            <span className="font-medium text-content">Send this notification</span>
            <span id="template-active-hint" className="mt-1 block text-xs text-content-muted">
              {mandatory
                ? 'This mail cannot be switched off. Blueprint §4B.6 marks assignment, handoff, escalation and status-request mail as never optional, and an individual user cannot mute it either — switching it off here would silence it for everybody at once. The in-app template for this event can be switched off; mail is the channel the product guarantees.'
                : 'Switching this off silences the event on this channel, for everybody. Nothing is deleted, and turning it back on restores the wording exactly.'}
            </span>
          </span>
        </label>
        {errors.isActive ? (
          <span role="alert" className="text-xs text-danger-text">
            {errors.isActive}
          </span>
        ) : null}
      </fieldset>
    </div>
  )
}

/** Field-keyed problems land on the input rather than in a toast. */
function fieldErrorsFrom(e: ApiError): Partial<Record<keyof TemplateFormValues, string>> {
  const problem = e.problem as { errors?: Record<string, string[]> }
  const server = problem.errors ?? {}
  const mapped: Partial<Record<keyof TemplateFormValues, string>> = {}
  for (const key of [
    'eventCode', 'channel', 'recipients', 'subjectTemplate', 'bodyTemplate', 'isActive',
  ] as const) {
    const message = server[key]?.[0]
    if (message) {
      mapped[key] = message
    }
  }
  return mapped
}

// ── create ─────────────────────────────────────────────────────────────────

/**
 * In a seeded system this dialog adds a browser-push template: blueprint §11 has
 * no push column, so the migration seeded none, and every (event, in-app) and
 * (event, email) pair the matrix ticks already exists.
 *
 * So the event dropdown offers only events that still have a channel free, and
 * the channel dropdown offers only the channels that event is missing. Both are
 * derived from the list rather than from the vocabulary, because "which pairs
 * are taken" is a fact about the data and not about the enum — and offering a
 * pair that is taken would make the create button a 409 generator.
 */
function CreateTemplateDialog({
  open,
  existing,
  onOpenChange,
}: {
  open: boolean
  existing: NotificationTemplate[]
  onOpenChange: (open: boolean) => void
}) {
  const create = useCreateNotificationTemplate()
  const { data: vocabulary } = useTemplateVocabulary()
  const [values, setValues] = React.useState<TemplateFormValues>(emptyTemplateForm)
  const [errors, setErrors] = React.useState<
    Partial<Record<keyof TemplateFormValues, string>>
  >({})

  const channels = vocabulary?.channels ?? []
  const takenBy = React.useMemo(() => {
    const map = new Map<string, Set<string>>()
    for (const template of existing) {
      const key = template.eventCode ?? ''
      map.set(key, new Set([...(map.get(key) ?? []), template.channel ?? '']))
    }
    return map
  }, [existing])

  const eventOptions = (vocabulary?.events ?? [])
    .map((event) => event.code)
    .filter((code) => (takenBy.get(code)?.size ?? 0) < channels.length)

  const freeChannels = channels.filter(
    (channel) => !takenBy.get(values.eventCode)?.has(channel),
  )

  // Kept in step with what is actually offered, so the select and the value it
  // holds cannot disagree — the bug B-021's create dialog fixed by seeding from
  // the available list rather than from a constant.
  React.useEffect(() => {
    if (!open) {
      setValues(emptyTemplateForm)
      setErrors({})
      return
    }
    setValues((current) => ({
      ...current,
      eventCode: current.eventCode || (eventOptions[0] ?? ''),
    }))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  React.useEffect(() => {
    if (freeChannels.length > 0 && !freeChannels.includes(values.channel)) {
      setValues((current) => ({ ...current, channel: freeChannels[0] }))
    }
  }, [freeChannels, values.channel])

  const onChange = (patch: Partial<TemplateFormValues>) =>
    setValues((current) => ({ ...current, ...patch }))

  const onSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    const found = templateFormErrors(values, vocabulary?.mergeTags ?? [])
    setErrors(found)
    if (Object.keys(found).length > 0) return

    create.mutate(toWriteRequest(values), {
      onSuccess: (template) => {
        onOpenChange(false)
        toast({
          title: `${humaniseEvent(template.eventCode ?? '')} — ${CHANNEL_LABELS[template.channel ?? ''] ?? template.channel} template created`,
          description: 'It is used the next time that event fires.',
        })
      },
      onError: (e: ApiError) => {
        const fields = fieldErrorsFrom(e)
        if (Object.keys(fields).length > 0) {
          setErrors(fields)
          return
        }
        toast({ variant: 'danger', title: 'Could not create the template' })
      },
    })
  }

  return (
    <Modal open={open} onOpenChange={onOpenChange}>
      <ModalContent>
        <form onSubmit={onSubmit}>
          <ModalHeader>
            <ModalTitle>New notification template</ModalTitle>
            <ModalDescription>
              Every event already has in-app and email wording where blueprint §11 asks for it,
              so this is usually how a browser-push template gets added. An event and channel
              that already have one cannot have a second.
            </ModalDescription>
          </ModalHeader>

          {eventOptions.length === 0 ? (
            <p className="my-4 rounded-card bg-subtle p-3 text-xs text-content-muted">
              Every event already has a template on every channel. Edit one instead.
            </p>
          ) : (
            <TemplateFields
              values={values}
              errors={errors}
              identityLocked={false}
              mandatory={false}
              eventOptions={eventOptions}
              channelOptions={freeChannels}
              recipientOptions={vocabulary?.recipients ?? []}
              mergeTags={vocabulary?.mergeTags ?? []}
              onChange={onChange}
            />
          )}

          <ModalFooter>
            <Button type="button" variant="secondary" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={create.isPending || eventOptions.length === 0}>
              {create.isPending ? 'Creating…' : 'Create template'}
            </Button>
          </ModalFooter>
        </form>
      </ModalContent>
    </Modal>
  )
}

// ── edit ───────────────────────────────────────────────────────────────────

function EditTemplateDialog({
  templateId,
  mergeTags,
  recipientOptions,
  onClose,
}: {
  templateId: number | null
  mergeTags: readonly string[]
  recipientOptions: readonly string[]
  onClose: () => void
}) {
  const { data, isPending } = useNotificationTemplate(templateId)
  const update = useUpdateNotificationTemplate()
  const [values, setValues] = React.useState<TemplateFormValues | null>(null)
  const [errors, setErrors] = React.useState<
    Partial<Record<keyof TemplateFormValues, string>>
  >({})

  // Seeded from the read rather than from the grid row, because the read is
  // what carries the `ETag` — editing values the tag does not cover would make
  // the precondition guard a formality.
  //
  // `isActive` is forced true on a mandatory template, so that what the locked
  // checkbox shows and what the form submits are the same value. Without it, a
  // row that reached `isActive: false` some other way — a hand-run `UPDATE`,
  // a restore from before this rule existed — would render as checked, submit
  // `false` on every save, and be uneditable: each attempt would earn the 409
  // the lock exists to describe, on a field the user cannot reach. This way the
  // first save repairs it instead.
  React.useEffect(() => {
    if (!data) {
      setValues(null)
      setErrors({})
      return
    }
    const seeded = toFormValues(data.template)
    setValues(data.template.isMandatory ? { ...seeded, isActive: true } : seeded)
    setErrors({})
  }, [data])

  if (templateId == null) return null

  const template = data?.template
  const mandatory = template?.isMandatory ?? false
  const goesToClient = (template?.recipients ?? []).includes('CLIENT_CONTACT')

  const onChange = (patch: Partial<TemplateFormValues>) =>
    setValues((current) => (current ? { ...current, ...patch } : current))

  const onSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    if (!values) return
    const found = templateFormErrors(values, mergeTags)
    setErrors(found)
    if (Object.keys(found).length > 0) return

    update.mutate(
      { templateId, data: toPatchRequest(values), etag: data?.etag ?? null },
      {
        onSuccess: (saved) => {
          onClose()
          toast({
            title: `${humaniseEvent(saved.eventCode ?? '')} saved`,
            description: saved.isActive
              ? undefined
              : 'This notification is switched off. Nothing was deleted — turning it back on restores the wording exactly.',
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
              title: 'Somebody else changed this template',
              description: 'Close the dialog and reopen it to pick up their edit.',
            })
            return
          }
          toast({ variant: 'danger', title: 'Could not save the template' })
        },
      },
    )
  }

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent>
        <form onSubmit={onSubmit}>
          <ModalHeader>
            <ModalTitle>
              {template
                ? `${humaniseEvent(template.eventCode ?? '')} · ${CHANNEL_LABELS[template.channel ?? ''] ?? template.channel}`
                : 'Edit template'}
            </ModalTitle>
            <ModalDescription>
              {goesToClient
                ? 'This wording is read by a client contact, outside the organisation.'
                : 'This wording goes to colleagues inside the organisation.'}
            </ModalDescription>
          </ModalHeader>

          {isPending || !values ? (
            <Skeleton className="my-4 h-96 w-full" />
          ) : (
            <TemplateFields
              values={values}
              errors={errors}
              identityLocked
              mandatory={mandatory}
              eventOptions={[values.eventCode]}
              channelOptions={[values.channel]}
              recipientOptions={recipientOptions}
              mergeTags={mergeTags}
              onChange={onChange}
            />
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
