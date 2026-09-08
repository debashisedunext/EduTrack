import { Mail, MessageSquare, Phone, ShieldAlert, Settings2, Users } from 'lucide-react'

import type { ObClientCommunication } from '@/api/generated/model/obClientCommunication'
import type { ObStepCommunication } from '@/api/generated/model/obStepCommunication'

/**
 * C-112 · what a communication looks like and what it is called — as data
 * rather than as a chain of ternaries inside two components.
 *
 * Split out for the reason `segmentState.ts` is: the part that has to be
 * *right* — which entry type gets which word, which icon, and what a screen
 * reader is told — is testable without a DOM, and the per-step timeline and
 * the client-level stitched view read the same vocabulary instead of
 * inventing a second one three weeks apart.
 *
 * ## The eight values are one column
 *
 * `ob_step_communications.entry_type` carries all of them, and the contract's
 * `channel` is that column. The first five are what a person may record; the
 * last three arrive from the portal (`COMMENT`), C-126's escalation mirror
 * (`ESCALATION`) and the module itself (`SYSTEM`). A reader filtering the
 * timeline is choosing between all eight with one control, which is why they
 * were widened into one enum rather than split into a channel beside a kind.
 *
 * ## Colour is never the only signal
 *
 * `tokens.css`'s own rule, CLAUDE.md's WCAG AA line, and `segmentState.ts`'s
 * header all say it. So every entry type carries an **icon and a word**, and
 * the chip's tint is the third channel rather than the first — an escalation
 * is not distinguished from a call by red against grey.
 */

/** Every value `channel` can carry on the wire. */
export const COMMUNICATION_CHANNELS = [
  'CALL',
  'EMAIL',
  'MEETING',
  'WHATSAPP',
  'OTHER',
  'COMMENT',
  'ESCALATION',
  'SYSTEM',
] as const
export type CommunicationChannel = (typeof COMMUNICATION_CHANNELS)[number]

/**
 * The five a person may record, in the order the form offers them.
 *
 * The other three are read-only here and the form must not offer them: the
 * server's `createObStepCommunication` rejects them, and a select that listed
 * `SYSTEM` would be a control whose only outcome is a 400.
 */
export const RECORDABLE_CHANNELS = ['CALL', 'EMAIL', 'MEETING', 'WHATSAPP', 'OTHER'] as const
export type RecordableChannel = (typeof RECORDABLE_CHANNELS)[number]

export interface ChannelLook {
  label: string
  Icon: typeof Phone
  /** A `Chip` variant — the tokens of blueprint §12.1, never a raw colour. */
  variant: 'neutral' | 'info' | 'warning' | 'danger'
}

const LOOK: Record<CommunicationChannel, ChannelLook> = {
  CALL: { label: 'Call', Icon: Phone, variant: 'info' },
  EMAIL: { label: 'Email', Icon: Mail, variant: 'info' },
  MEETING: { label: 'Meeting', Icon: Users, variant: 'info' },
  WHATSAPP: { label: 'WhatsApp', Icon: MessageSquare, variant: 'info' },
  OTHER: { label: 'Note', Icon: MessageSquare, variant: 'neutral' },
  COMMENT: { label: 'Portal comment', Icon: MessageSquare, variant: 'neutral' },
  ESCALATION: { label: 'Escalation', Icon: ShieldAlert, variant: 'danger' },
  SYSTEM: { label: 'System', Icon: Settings2, variant: 'neutral' },
}

/**
 * How one entry is labelled.
 *
 * **Tolerant, on purpose**, on `categoryLook`'s own precedent one directory
 * over. `entry_type` is a `VARCHAR` with no `CHECK` constraint behind it, so a
 * newer deploy can write a ninth value into a table this client reads. An
 * unrecognised one costs the icon's specificity and nothing else — it is never
 * a blank row and never a thrown render.
 */
export function channelLook(channel: string): ChannelLook {
  return LOOK[channel as CommunicationChannel] ?? { label: channel, Icon: MessageSquare, variant: 'neutral' }
}

/**
 * One line naming who said it and through what — the accessible name for a
 * timeline row, and the visible byline above it.
 *
 * `authorName` rather than `recordedBy.displayName`: the server sends the
 * staff user, the client contact's name or `System` in that one field
 * precisely so a renderer does not have to know which of
 * `ck_ob_comms_author`'s three shapes it is looking at.
 */
export function entryByline(entry: ObStepCommunication | ObClientCommunication): string {
  const look = channelLook(entry.channel)
  // Which side wrote it, said out loud on the one type where the name alone
  // does not carry it. A staff name and a contact name look identical.
  const side = entry.authorType === 'CLIENT' ? ' (client)' : ''
  return `${look.label} · ${entry.authorName}${side}`
}

/**
 * §11 and CP-03's never-visible list, said out loud rather than left to a
 * chip's colour.
 *
 * The distinction is the one thing on this timeline that cannot be undone if a
 * reader gets it wrong — an internal note repeated on a call because somebody
 * read the wrong chip is exactly the leak `is_client_visible` defaulting to
 * false exists to prevent. So both states are named in words, and the internal
 * one is never merely "the absence of a badge".
 */
export function visibilityLabel(isClientVisible: boolean): string {
  return isClientVisible ? 'Client can see this' : 'Internal only'
}
