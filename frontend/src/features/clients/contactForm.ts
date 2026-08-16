import { z } from 'zod'

import { EMAIL_MESSAGE, isWellFormedEmail } from '@/lib/email'
import type { Contact } from '@/api/generated/model/contact'
import type { ContactWriteRequest } from '@/api/generated/model/contactWriteRequest'

/**
 * B-027 · S-33's Contacts tab — the row editor's shape, validation and its two
 * translations.
 *
 * Kept out of the component for the reason `clientForm.ts` gives one file over:
 * the mapping between a form and a request is the part worth testing on its own,
 * and it is unreachable behind a rendered dialog.
 *
 * <h2>Empty string is the form's null</h2>
 *
 * Every optional input holds `''` when untouched, never `undefined` — React
 * would otherwise switch the input from uncontrolled to controlled on the first
 * keystroke and warn. {@link toContactWriteRequest} is the only place that turns
 * `''` back into the `null` the column means, so no component has to remember it.
 *
 * <h2>The lengths are the columns'</h2>
 *
 * 120 for a name and 30 for a phone are `client_contacts`' own widths, not
 * round numbers picked here. The contract had `name` at 150 against a
 * `VARCHAR(120)` until B-027 — a value the form would accept, the server would
 * accept, and MySQL would refuse. Restating them here rather than importing them
 * is unavoidable (there is nothing to import), so `contactForm.test.ts` asserts
 * the boundary in both directions: the drift this file can suffer is silent.
 */

export interface ContactFormValues {
  name: string
  designation: string
  email: string
  phone: string
  isPrimary: boolean
  notificationOptIn: boolean
  portalAccess: boolean
}

export const contactFormSchema = z.object({
  name: z
    .string()
    .trim()
    .min(1, 'A name is required.')
    .max(120, 'A name cannot exceed 120 characters.'),
  designation: z.string().trim().max(80, 'A designation cannot exceed 80 characters.'),
  /*
   * Required on the form, where `Contact.email` is nullable on the wire — and
   * that asymmetry is deliberate rather than an oversight. The column is
   * nullable because B-035's import will write rows from spreadsheets that omit
   * an address; a contact *entered here* must have one, because
   * `notificationOptIn` defaults to true and a mail D-036 can never deliver is
   * worse than a refused save.
   */
  /*
   * B-028 · `isWellFormedEmail`, not zod's `.email()`. zod accepts
   * `sara@acme` — no dotted TLD — and so did Jakarta's `@Email` on the server
   * until B-028, while B-030's importer refused it on the same column. A form
   * looser than the server is the worse direction: the save is accepted here
   * and refused there, on a field the user was just told was fine.
   */
  email: z
    .string()
    .trim()
    .min(1, 'An email address is required.')
    .max(150, 'An email address cannot exceed 150 characters.')
    .refine(isWellFormedEmail, EMAIL_MESSAGE),
  phone: z.string().trim().max(30, 'A phone number cannot exceed 30 characters.'),
  isPrimary: z.boolean(),
  notificationOptIn: z.boolean(),
  portalAccess: z.boolean(),
})

/**
 * A new contact.
 *
 * **`notificationOptIn` starts true and the other two start false**, matching
 * the column defaults rather than being neutral about it. §11's recipient lists
 * name the client contact on the mails a client is meant to receive, so somebody
 * added without the question being answered should hear about their own tickets;
 * being the primary and holding portal access are both decisions somebody has to
 * make.
 */
export const emptyContactForm: ContactFormValues = {
  name: '',
  designation: '',
  email: '',
  phone: '',
  isPrimary: false,
  notificationOptIn: true,
  portalAccess: false,
}

/** An existing contact, ready to edit. Null columns become the form's `''`. */
export function toContactFormValues(contact: Contact): ContactFormValues {
  return {
    name: contact.name ?? '',
    designation: contact.designation ?? '',
    email: contact.email ?? '',
    phone: contact.phone ?? '',
    isPrimary: contact.isPrimary ?? false,
    notificationOptIn: contact.notificationOptIn ?? true,
    portalAccess: contact.portalAccess ?? false,
  }
}

/**
 * The form as the contract's body.
 *
 * **Every field is sent on every save**, because the server reads this body as
 * the whole representation — B-026's call on `ClientWriteRequest`, one tab over.
 * Omitting an emptied input would mean the admin who cleared a designation is
 * told the save succeeded and finds it still there.
 *
 * `isActive` is deliberately not here and cannot be: removal is the `DELETE`,
 * and a second way to do it is how two controls for one outcome end up
 * disagreeing.
 */
export function toContactWriteRequest(values: ContactFormValues): ContactWriteRequest {
  return {
    name: values.name.trim(),
    designation: blankToNull(values.designation),
    email: values.email.trim(),
    phone: blankToNull(values.phone),
    isPrimary: values.isPrimary,
    notificationOptIn: values.notificationOptIn,
    portalAccess: values.portalAccess,
  }
}

function blankToNull(raw: string): string | null {
  const trimmed = raw.trim()
  return trimmed === '' ? null : trimmed
}
