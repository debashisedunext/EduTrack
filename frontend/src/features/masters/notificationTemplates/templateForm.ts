import type { NotificationChannel } from '@/api/generated/model/notificationChannel'
import type { NotificationRecipient } from '@/api/generated/model/notificationRecipient'
import type { NotificationTemplate } from '@/api/generated/model/notificationTemplate'
import type { NotificationTemplatePatchRequest } from '@/api/generated/model/notificationTemplatePatchRequest'
import type { NotificationTemplateWriteRequest } from '@/api/generated/model/notificationTemplateWriteRequest'

/**
 * S-15 Notification Template Master — form state, validation, the merge-tag
 * check and the mapping onto the wire. B-022.
 *
 * Kept apart from the page for the reason `projectForm.ts`, `taskTypeForm.ts`
 * and `priorityForm.ts` give: the rules are worth testing without rendering
 * anything, and the two mappers are where a quiet mistake shows up as a save
 * that silently did nothing.
 */

/**
 * `{{ name }}` — braces doubled, inner whitespace tolerated, the name captured.
 *
 * The same shape `MergeTag.PLACEHOLDER` compiles server-side, and the tolerance
 * matters for the same reason: `{{ ticket_id }}` is what a paste from a
 * specification document produces, and refusing it would be a refusal about
 * spacing rather than about spelling.
 */
export const PLACEHOLDER = /\{\{\s*([A-Za-z0-9_]+)\s*}}/g

/**
 * Plain-English labels for the vocabularies, keyed by the server's codes.
 *
 * **The server's list is the source of truth for which values exist; this is
 * only how they are worded.** So a code with no entry here renders as itself
 * rather than disappearing — a screen that silently dropped a recipient the
 * server had just added would be worse than one showing `SUPPORT_DESK` in
 * capitals.
 */
export const RECIPIENT_LABELS: Record<string, string> = {
  ASSIGNEE: 'Assignee',
  STAGE_OWNER: 'Current stage owner',
  PREVIOUS_ASSIGNEE: 'Previous assignee',
  REPORTER: 'Reporter',
  PROJECT_MANAGER: "The project's PM",
  REPORTING_MANAGER: "The assignee's reporting manager",
  SUPPORT_DESK: "The project's support desk",
  WATCHERS: 'Watchers',
  MENTIONED_USER: 'The mentioned user',
  CLIENT_CONTACT: 'Client contacts',
  REQUESTER: 'Whoever asked',
  ALL_USERS: 'Everybody',
  ADMIN: 'Admins',
}

export const CHANNEL_LABELS: Record<string, string> = {
  IN_APP: 'In-app',
  EMAIL: 'Email',
  PUSH: 'Browser push',
}

export const CATEGORY_LABELS: Record<string, string> = {
  ASSIGNMENT: 'Assignment',
  ESCALATION: 'Escalation',
  STATUS_REQUEST: 'Status request',
  MENTION: 'Mention',
  OTHER: 'Other',
}

/**
 * `TICKET_ASSIGNED` → `Ticket assigned`.
 *
 * Derived rather than mapped, because the alternative is a 27-entry dictionary
 * that has to grow every time Stream D adds a producer — and the failure mode of
 * a missing entry is a blank cell in the grid. An underscore-to-space rewrite is
 * readable for every code in the enum, which is the whole bar it has to clear.
 */
export function humaniseEvent(code: string): string {
  const words = code.toLowerCase().replace(/_/g, ' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

/** What the create and edit dialogs hold. */
export interface TemplateFormValues {
  eventCode: string
  channel: NotificationChannel
  recipients: NotificationRecipient[]
  subjectTemplate: string
  bodyTemplate: string
  isActive: boolean
}

export const emptyTemplateForm: TemplateFormValues = {
  eventCode: '',
  channel: 'PUSH',
  recipients: [],
  subjectTemplate: '',
  bodyTemplate: '',
  isActive: true,
}

/** The stored row as the edit dialog first renders it. */
export function toFormValues(template: NotificationTemplate): TemplateFormValues {
  return {
    eventCode: template.eventCode ?? '',
    channel: template.channel ?? 'IN_APP',
    recipients: [...(template.recipients ?? [])],
    subjectTemplate: template.subjectTemplate ?? '',
    bodyTemplate: template.bodyTemplate ?? '',
    isActive: template.isActive ?? true,
  }
}

/**
 * The `{{tags}}` in this text that are not in the catalogue.
 *
 * De-duplicated and in first-appearance order, so a body that misspells the same
 * tag four times reports it once and reports it where the reader will look
 * first. `MergeTag.unknownIn` does the identical thing server-side, and the
 * server stays the authority — this exists so the editor can underline the
 * mistake while it is being typed rather than only after a save.
 */
export function unknownMergeTags(text: string, known: readonly string[]): string[] {
  const found: string[] = []
  for (const match of text.matchAll(PLACEHOLDER)) {
    const tag = match[1]
    if (!known.includes(tag) && !found.includes(tag)) {
      found.push(tag)
    }
  }
  return found
}

/**
 * The same rules the server enforces, so the form refuses before the round trip
 * rather than after it.
 *
 * The server stays the authority — these are duplicated deliberately and
 * narrowly, and the ones that are *not* here are the ones a browser cannot know:
 * whether this (event, channel) pair already has a template, and whether the
 * event is one whose mail cannot be switched off. The second is knowable, in
 * fact, from `isMandatory` on the row — which is why the page renders that
 * toggle as a locked statement rather than letting this function refuse it.
 * A control whose only outcome is a refusal is a control that should not be
 * operable.
 *
 * @param mergeTags the catalogue from the vocabulary read. An empty array means
 *        it has not loaded, and no tag is reported unknown — refusing every tag
 *        in the body because a second request is in flight would be a validator
 *        that is wrong in the strict direction, which is worse than one that is
 *        briefly quiet.
 */
export function templateFormErrors(
  values: TemplateFormValues,
  mergeTags: readonly string[] = [],
): Partial<Record<keyof TemplateFormValues, string>> {
  const errors: Partial<Record<keyof TemplateFormValues, string>> = {}

  if (!values.eventCode.trim()) {
    errors.eventCode = 'Pick the event this template is for.'
  }

  if (values.recipients.length === 0) {
    errors.recipients = 'Name at least one recipient — a template with none sends nothing.'
  }

  if (values.channel === 'EMAIL' && !values.subjectTemplate.trim()) {
    errors.subjectTemplate = 'An email needs a subject line.'
  } else if (values.subjectTemplate.trim().length > 255) {
    errors.subjectTemplate = 'At most 255 characters.'
  }

  if (!values.bodyTemplate.trim()) {
    errors.bodyTemplate = 'A body is required.'
  }

  if (mergeTags.length > 0) {
    const unknown = unknownMergeTags(
      `${values.subjectTemplate}\n${values.bodyTemplate}`,
      mergeTags,
    )
    if (unknown.length > 0) {
      errors.bodyTemplate = unknown.length === 1
        ? `{{${unknown[0]}}} is not a merge tag — it would be printed literally, braces included.`
        : `Not merge tags, and they would be printed literally: ${unknown.map((t) => `{{${t}}}`).join(', ')}`
    }
  }

  return errors
}

export function toWriteRequest(values: TemplateFormValues): NotificationTemplateWriteRequest {
  return {
    eventCode: values.eventCode,
    channel: values.channel,
    recipients: values.recipients,
    // Empty means "this channel carries no subject", which is the stored state
    // of every in-app template. An empty string would be a subject line that is
    // blank, which is a different and useless thing.
    subjectTemplate: values.subjectTemplate.trim() || null,
    bodyTemplate: values.bodyTemplate.trim(),
    isActive: values.isActive,
  }
}

/**
 * The whole form on every save, `eventCode` and `channel` included.
 *
 * Sending the stored pair is a deliberate no-op on the server — S-15 is a
 * full-form submit, and any other reading would make every edit a 409. Sending
 * it is what makes a *changed* pair refusable, which is the point: the pair is
 * the row's identity, and `email_log` rows already sent point at this id.
 */
export function toPatchRequest(values: TemplateFormValues): NotificationTemplatePatchRequest {
  return {
    eventCode: values.eventCode,
    channel: values.channel,
    recipients: values.recipients,
    subjectTemplate: values.subjectTemplate.trim() || null,
    bodyTemplate: values.bodyTemplate.trim(),
    isActive: values.isActive,
  }
}
